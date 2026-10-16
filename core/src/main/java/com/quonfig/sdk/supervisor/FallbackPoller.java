package com.quonfig.sdk.supervisor;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Layer 2 fallback poller (qfg-47c2.21 — parity with sdk-go's {@code fallback_poller.go}).
 *
 * <p>The poller is idle while SSE is connected. When SSE has been disconnected for at least {@link
 * Builder#threshold (default 120s)} the poller engages: it fires an immediate fetch and then ticks
 * at {@link Builder#interval (default 60s)} until SSE reconnects (at which point it disengages and
 * returns to idle). The supervisor owns the worker thread; this class does not spawn its own.
 *
 * <p>{@link #setSseConnected(boolean)} is the only state-edge input. Callers (the Quonfig client's
 * SSE state callback) feed transitions in; the poller keeps its own engage deadline.
 *
 * <p><b>Auth-failure rule</b> (qfg-goi1.10.27, plan {@code
 * project/plans/2026-10-07-sdk-auth-failure-rule.md} R2-R5; sdk-go 12e0e6c parity): when one of the
 * poller's fetch cycles reports {@link CycleResult#authRejected} (no leg returned 200/304 and the
 * primary leg answered 401/403/404/410), the poller enters auth-failed. It stops polling, has the
 * client stop SSE ({@link Builder#onAuthFailed onAuthFailed(true)}), logs one ERROR, and from then
 * on runs one probe cycle per T uniform in [150s, 300s] for as long as the process lives (no
 * doubling, no latch). The first probe that gets a 200 or 304 on any leg exits: one INFO, {@code
 * onAuthFailed(false)} restarts SSE, and the poller returns to its normal engage rule.
 *
 * <p>See {@code project/plans/sdk-hardening-and-verification.md §"Phase 3 — Layer 2 fallback
 * standardization"} for the cross-SDK contract.
 */
public final class FallbackPoller {

  /** Cross-SDK default: 120s of disconnect before Layer 2 engages. */
  public static final Duration DEFAULT_THRESHOLD = Duration.ofSeconds(120);

  /** Cross-SDK default poll cadence once engaged. */
  public static final Duration DEFAULT_INTERVAL = Duration.ofSeconds(60);

  /**
   * The auth-failed probe period T is uniform in [AUTH_PROBE_MIN, AUTH_PROBE_MAX] (rule R3).
   * Internal: tests reach it through the wait hook, never an option.
   */
  static final Duration AUTH_PROBE_MIN = Duration.ofSeconds(150);

  static final Duration AUTH_PROBE_MAX = Duration.ofSeconds(300);

  private static final Logger LOG = LoggerFactory.getLogger(FallbackPoller.class);

  /** The outcome of one fetch cycle across the configured legs. */
  public static final class CycleResult {
    /** What a fetch cycle came back with. */
    public enum Kind {
      /** A leg answered 200 with a valid envelope, or 304. */
      ANSWERED,
      /** Every leg failed, and the primary's status is not in the auth class. */
      FAILED,
      /** Every leg failed and the primary answered 401/403/404/410 (rule R2). */
      AUTH_REJECTED
    }

    private static final CycleResult ANSWERED = new CycleResult(Kind.ANSWERED, 0, null);
    private static final CycleResult FAILED = new CycleResult(Kind.FAILED, 0, null);

    private final Kind kind;
    private final int status;
    private final String host;

    private CycleResult(Kind kind, int status, String host) {
      this.kind = kind;
      this.status = status;
      this.host = host;
    }

    public static CycleResult answered() {
      return ANSWERED;
    }

    public static CycleResult failed() {
      return FAILED;
    }

    /**
     * An entering cycle: {@code status} is the primary leg's status and {@code host} its host
     * (never the key).
     */
    public static CycleResult authRejected(int status, String host) {
      return new CycleResult(Kind.AUTH_REJECTED, status, host);
    }

    public Kind kind() {
      return kind;
    }

    public int status() {
      return status;
    }

    public String host() {
      return host;
    }
  }

  private final Duration interval;
  private final Duration threshold;
  private final Supplier<CycleResult> cycle;
  private final Runnable onEngage;
  private final Runnable onDisengage;
  private final Consumer<Boolean> onAuthFailed;
  private final Logger logger;
  private final BiFunction<String, Duration, Duration> waitHook;

  private final Object lock = new Object();
  // guarded by lock:
  // sseConnected starts true so the poller stays idle until the first real disconnect edge.
  // The Quonfig client's SSE state callback fires on every transition, so the very first
  // setSseConnected(false) — whether that's a failed-to-establish or a drop after connect —
  // arms the threshold timer.
  private boolean sseConnected = true;
  // Engage deadline (System.nanoTime), armed on the first disconnect edge and cleared by a
  // connect edge. engageArmed guards it because nanoTime values may be negative.
  private boolean engageArmed;
  private long engageAtNanos;
  private boolean engaged;
  // Set while the client is in the auth-failed regime. It lives on the poller, not in run(), so a
  // run restarted by the supervisor resumes probing instead of polling with SSE stopped.
  private boolean authFailed;

  private FallbackPoller(Builder b) {
    this.interval = b.interval != null ? b.interval : DEFAULT_INTERVAL;
    this.threshold = b.threshold != null ? b.threshold : DEFAULT_THRESHOLD;
    this.cycle = Objects.requireNonNull(b.cycle, "fetch");
    this.onEngage = b.onEngage;
    this.onDisengage = b.onDisengage;
    this.onAuthFailed = b.onAuthFailed;
    this.logger = b.logger != null ? b.logger : LOG;
    this.waitHook = b.waitHook;
  }

  public static Builder builder() {
    return new Builder();
  }

  /**
   * Feeds an SSE connection state edge into the poller. Safe to call from any thread; never blocks.
   */
  public void setSseConnected(boolean connected) {
    synchronized (lock) {
      sseConnected = connected;
      if (authFailed) {
        // SSE is stopped while auth-failed; a late edge from the stream being torn down changes
        // nothing. The exit path re-arms the engage rule itself.
        lock.notifyAll();
        return;
      }
      if (connected) {
        engageArmed = false;
      } else if (!engageArmed && !engaged) {
        engageArmed = true;
        engageAtNanos = System.nanoTime() + waitFor("engage", threshold).toNanos();
      }
      lock.notifyAll();
    }
  }

  /** True while the poller is engaged (i.e. SSE has been down past the threshold). */
  public boolean active() {
    synchronized (lock) {
      return engaged;
    }
  }

  /** True while the client is in the auth-failed regime (rule R3). */
  public boolean authFailed() {
    synchronized (lock) {
      return authFailed;
    }
  }

  /** Returns the {@link Supervisor.Worker} body. Register this under layer label {@code "2"}. */
  public Supervisor.Worker worker() {
    return this::run;
  }

  private enum Action {
    NONE,
    ENGAGE_AND_FETCH,
    FETCH,
    DISENGAGE,
    PROBE
  }

  /** Applies the test wait hook, if any: maps a requested wait to the one actually waited. */
  private Duration waitFor(String kind, Duration requested) {
    BiFunction<String, Duration, Duration> hook = waitHook;
    if (hook == null) return requested;
    Duration d = hook.apply(kind, requested);
    return d != null ? d : requested;
  }

  /** The wait before the next auth-failed probe: T uniform in [150s, 300s], no doubling (R3). */
  private static Duration authProbeWait() {
    long minMs = AUTH_PROBE_MIN.toMillis();
    long maxMs = AUTH_PROBE_MAX.toMillis();
    return Duration.ofMillis(minMs + ThreadLocalRandom.current().nextLong(maxMs - minMs + 1));
  }

  private void run(Supervisor.WorkerContext ctx) throws InterruptedException {
    boolean engagedLocal = false;
    // Poll period, resolved once per engagement (like a ticker) so the wait hook sees one "poll"
    // request per engagement, not one per tick.
    long pollPeriodNanos = 0L;
    long nextPollAtNanos = 0L;
    // Probe deadline. Local so a run restarted by the supervisor while auth-failed arms a fresh
    // one.
    boolean probeArmed = false;
    long probeAtNanos = 0L;
    try {
      while (!ctx.isStopped()) {
        Action action;
        long waitNanos;
        synchronized (lock) {
          long now = System.nanoTime();
          if (authFailed) {
            if (!probeArmed) {
              probeArmed = true;
              probeAtNanos = now + waitFor("probe", authProbeWait()).toNanos();
            }
            if (now - probeAtNanos >= 0) {
              probeArmed = false;
              action = Action.PROBE;
              waitNanos = 0L;
            } else {
              action = Action.NONE;
              waitNanos = probeAtNanos - now;
            }
          } else if (sseConnected) {
            if (engagedLocal) {
              engaged = false;
              engagedLocal = false;
              action = Action.DISENGAGE;
            } else {
              action = Action.NONE;
            }
            // No deadline pending while connected; wake on setSseConnected via notifyAll.
            waitNanos = TimeUnit.HOURS.toNanos(1);
          } else if (engagedLocal) {
            if (now - nextPollAtNanos >= 0) {
              action = Action.FETCH;
              nextPollAtNanos = now + pollPeriodNanos;
              waitNanos = pollPeriodNanos;
            } else {
              action = Action.NONE;
              waitNanos = nextPollAtNanos - now;
            }
          } else if (engageArmed && now - engageAtNanos >= 0) {
            engaged = true;
            engagedLocal = true;
            engageArmed = false;
            action = Action.ENGAGE_AND_FETCH;
            pollPeriodNanos = Math.max(1L, waitFor("poll", interval).toNanos());
            nextPollAtNanos = now + pollPeriodNanos;
            waitNanos = pollPeriodNanos;
          } else {
            action = Action.NONE;
            waitNanos = engageArmed ? engageAtNanos - now : TimeUnit.HOURS.toNanos(1);
          }
        }

        switch (action) {
          case ENGAGE_AND_FETCH:
            safeRun(onEngage, "onEngage");
            if (fetchCycle()) engagedLocal = false;
            break;
          case FETCH:
            if (fetchCycle()) engagedLocal = false;
            break;
          case DISENGAGE:
            safeRun(onDisengage, "onDisengage");
            break;
          case PROBE:
            probe();
            break;
          case NONE:
          default:
            break;
        }

        synchronized (lock) {
          if (ctx.isStopped()) return;
          if (action == Action.NONE && waitNanos > 0) {
            TimeUnit.NANOSECONDS.timedWait(lock, waitNanos);
          }
        }
      }
    } finally {
      boolean wasEngaged;
      synchronized (lock) {
        wasEngaged = engaged;
        engaged = false;
        if (wasEngaged && !sseConnected) {
          // A supervisor restart of an engaged poller re-engages at once: SSE is still down past
          // the threshold, and no fresh disconnect edge will arrive to re-arm it.
          engageArmed = true;
          engageAtNanos = System.nanoTime();
        }
      }
      if (wasEngaged) safeRun(onDisengage, "onDisengage");
    }
  }

  /**
   * Runs one ordinary poll cycle and enters auth-failed when the cycle qualifies (R2). Returns true
   * iff it entered (the caller drops its engaged state).
   */
  private boolean fetchCycle() {
    CycleResult r = runCycle();
    if (r.kind() != CycleResult.Kind.AUTH_REJECTED) return false;
    synchronized (lock) {
      engaged = false;
      engageArmed = false;
      authFailed = true;
    }
    safeAccept(onAuthFailed, true, "onAuthFailed");
    logger.error(
        "quonfig: config fetch rejected: the SDK key is invalid or revoked (check the SDK key);"
            + " streaming and polling stopped, retrying every few minutes until it is accepted"
            + " (status={}, host={})",
        r.status(),
        r.host());
    return true;
  }

  /** Runs one auth-failed probe cycle; the first 200/304 on any leg exits auth-failed (R4). */
  private void probe() {
    CycleResult r = runCycle();
    if (r.kind() != CycleResult.Kind.ANSWERED) {
      if (r.kind() == CycleResult.Kind.AUTH_REJECTED) {
        logger.debug(
            "quonfig: auth-failed probe rejected (status={}, host={}); retrying later",
            r.status(),
            r.host());
      } else {
        logger.debug("quonfig: auth-failed probe failed; retrying later");
      }
      return; // the run loop arms the next probe
    }
    synchronized (lock) {
      authFailed = false;
    }
    logger.info(
        "quonfig: config fetch accepted; leaving auth-failed and resuming streaming and polling");
    // SSE restarts as a fresh stream, so from its base backoff.
    safeAccept(onAuthFailed, false, "onAuthFailed");
    synchronized (lock) {
      // Back on the normal engage rule. A fresh stream that never connects sends no disconnect
      // edge, so arm the threshold here unless it already connected.
      if (!authFailed && !sseConnected && !engageArmed && !engaged) {
        engageArmed = true;
        engageAtNanos = System.nanoTime() + waitFor("engage", threshold).toNanos();
      }
      lock.notifyAll();
    }
  }

  private CycleResult runCycle() {
    try {
      CycleResult r = cycle.get();
      return r != null ? r : CycleResult.failed();
    } catch (RuntimeException e) {
      LOG.debug("quonfig: fallback poller fetch callback threw: {}", e.toString());
      return CycleResult.failed();
    }
  }

  private static void safeRun(Runnable r, String name) {
    if (r == null) return;
    try {
      r.run();
    } catch (RuntimeException e) {
      LOG.debug("quonfig: fallback poller {} callback threw: {}", name, e.toString());
    }
  }

  private static void safeAccept(Consumer<Boolean> c, boolean v, String name) {
    if (c == null) return;
    try {
      c.accept(v);
    } catch (RuntimeException e) {
      LOG.debug("quonfig: fallback poller {} callback threw: {}", name, e.toString());
    }
  }

  public static final class Builder {
    private Duration interval;
    private Duration threshold;
    private Supplier<CycleResult> cycle;
    private Runnable onEngage;
    private Runnable onDisengage;
    private Consumer<Boolean> onAuthFailed;
    private Logger logger;
    private BiFunction<String, Duration, Duration> waitHook;

    public Builder interval(Duration v) {
      this.interval = v;
      return this;
    }

    public Builder threshold(Duration v) {
      this.threshold = v;
      return this;
    }

    /**
     * One poll. A thrown {@link RuntimeException} is logged at DEBUG and the poller keeps ticking.
     * A fetch registered this way never enters auth-failed; use {@link #cycle} for that.
     */
    public Builder fetch(Runnable v) {
      this.cycle =
          v == null
              ? null
              : () -> {
                v.run();
                return CycleResult.answered();
              };
      return this;
    }

    /**
     * One poll cycle across the configured legs, reporting its {@link CycleResult}. An {@link
     * CycleResult#authRejected} result enters the auth-failed regime (see the class doc).
     */
    public Builder cycle(Supplier<CycleResult> v) {
      this.cycle = v;
      return this;
    }

    public Builder onEngage(Runnable v) {
      this.onEngage = v;
      return this;
    }

    public Builder onDisengage(Runnable v) {
      this.onDisengage = v;
      return this;
    }

    /**
     * Fires with {@code true} when a poll cycle enters auth-failed (before the ERROR is logged) and
     * with {@code false} when a probe exits it (after the INFO). The client stops SSE on entry and
     * restarts it on exit. Runs on the poller thread.
     */
    public Builder onAuthFailed(Consumer<Boolean> v) {
      this.onAuthFailed = v;
      return this;
    }

    /** Receives the auth-failed ERROR/DEBUG/INFO lines. Defaults to this class's logger. */
    public Builder logger(Logger v) {
      this.logger = v;
      return this;
    }

    /**
     * Internal test seam, not a customer option: sees every wait the poller requests ({@code
     * "engage"} for the SSE-down threshold, {@code "poll"} for the interval, {@code "probe"} for an
     * auth-failed probe) and returns the duration to actually wait. Supervisor contract Test 8 uses
     * it to record the requested waits and compress them. Null (the default) waits as requested.
     */
    public Builder waitHook(BiFunction<String, Duration, Duration> v) {
      this.waitHook = v;
      return this;
    }

    public FallbackPoller build() {
      return new FallbackPoller(this);
    }
  }
}
