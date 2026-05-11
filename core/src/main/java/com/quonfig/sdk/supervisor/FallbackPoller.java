package com.quonfig.sdk.supervisor;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
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
 * SSE state callback) feed transitions in; the poller maintains its own disconnect-since timestamp.
 *
 * <p>See {@code project/plans/sdk-hardening-and-verification.md §"Phase 3 — Layer 2 fallback
 * standardization"} for the cross-SDK contract.
 */
public final class FallbackPoller {

  /** Cross-SDK default: 120s of disconnect before Layer 2 engages. */
  public static final Duration DEFAULT_THRESHOLD = Duration.ofSeconds(120);

  /** Cross-SDK default poll cadence once engaged. */
  public static final Duration DEFAULT_INTERVAL = Duration.ofSeconds(60);

  private static final Logger LOG = LoggerFactory.getLogger(FallbackPoller.class);

  private final Duration interval;
  private final Duration threshold;
  private final Runnable fetch;
  private final Runnable onEngage;
  private final Runnable onDisengage;

  private final Object lock = new Object();
  // guarded by lock:
  // sseConnected starts true so the poller stays idle until the first real disconnect edge.
  // The Quonfig client's SSE state callback fires on every transition, so the very first
  // setSseConnected(false) — whether that's a failed-to-establish or a drop after connect —
  // arms the threshold timer.
  private boolean sseConnected = true;
  private long disconnectedSinceMillis = -1L;
  private boolean engaged;

  private FallbackPoller(Builder b) {
    this.interval = b.interval != null ? b.interval : DEFAULT_INTERVAL;
    this.threshold = b.threshold != null ? b.threshold : DEFAULT_THRESHOLD;
    this.fetch = Objects.requireNonNull(b.fetch, "fetch");
    this.onEngage = b.onEngage;
    this.onDisengage = b.onDisengage;
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
      if (connected) {
        disconnectedSinceMillis = -1L;
      } else if (disconnectedSinceMillis < 0L) {
        disconnectedSinceMillis = System.currentTimeMillis();
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

  /** Returns the {@link Supervisor.Worker} body. Register this under layer label {@code "2"}. */
  public Supervisor.Worker worker() {
    return this::run;
  }

  private enum Action {
    NONE,
    ENGAGE_AND_FETCH,
    FETCH,
    DISENGAGE
  }

  private void run(Supervisor.WorkerContext ctx) throws InterruptedException {
    boolean engagedLocal = false;
    try {
      while (!ctx.isStopped()) {
        Action action;
        long waitMillis;
        synchronized (lock) {
          if (sseConnected) {
            if (engagedLocal) {
              engaged = false;
              engagedLocal = false;
              action = Action.DISENGAGE;
            } else {
              action = Action.NONE;
            }
            // No deadline pending while connected; wake on setSseConnected via notifyAll.
            waitMillis = TimeUnit.HOURS.toMillis(1);
          } else {
            long sinceMs = System.currentTimeMillis() - disconnectedSinceMillis;
            if (engagedLocal) {
              action = Action.FETCH;
              waitMillis = interval.toMillis();
            } else if (sinceMs >= threshold.toMillis()) {
              engaged = true;
              engagedLocal = true;
              action = Action.ENGAGE_AND_FETCH;
              waitMillis = interval.toMillis();
            } else {
              action = Action.NONE;
              waitMillis = Math.max(1L, threshold.toMillis() - sinceMs);
            }
          }
        }

        switch (action) {
          case ENGAGE_AND_FETCH:
            safeRun(onEngage, "onEngage");
            safeRun(fetch, "fetch");
            break;
          case FETCH:
            safeRun(fetch, "fetch");
            break;
          case DISENGAGE:
            safeRun(onDisengage, "onDisengage");
            break;
          case NONE:
          default:
            break;
        }

        synchronized (lock) {
          if (ctx.isStopped()) return;
          if (waitMillis > 0) {
            lock.wait(waitMillis);
          }
        }
      }
    } finally {
      boolean wasEngaged;
      synchronized (lock) {
        wasEngaged = engaged;
        engaged = false;
      }
      if (wasEngaged) safeRun(onDisengage, "onDisengage");
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

  public static final class Builder {
    private Duration interval;
    private Duration threshold;
    private Runnable fetch;
    private Runnable onEngage;
    private Runnable onDisengage;

    public Builder interval(Duration v) {
      this.interval = v;
      return this;
    }

    public Builder threshold(Duration v) {
      this.threshold = v;
      return this;
    }

    public Builder fetch(Runnable v) {
      this.fetch = v;
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

    public FallbackPoller build() {
      return new FallbackPoller(this);
    }
  }
}
