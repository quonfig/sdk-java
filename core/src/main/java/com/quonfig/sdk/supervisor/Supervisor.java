package com.quonfig.sdk.supervisor;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Watcher-of-the-watchers for sdk-java background workers (qfg-47c2.18, B6 parity with sdk-go's
 * {@code Supervisor}). One Supervisor instance per {@code Quonfig} client. It owns the Layer 1
 * (SSE) worker today and the Layer 2 (fallback poll) worker in a follow-up bead. Every worker runs
 * inside a try/catch at the supervisor boundary: an unhandled {@link Throwable} or non-cancellation
 * exit logs at ERROR, increments {@code quonfig_sdk_worker_restart_total{layer="<n>"}}, sleeps for
 * an exponential backoff (500ms → 30s cap), and restarts. A clean exit driven by {@link #stop()} is
 * <em>not</em> counted as a restart.
 *
 * <p>The Supervisor is the source of truth for {@link #connectionState()} and {@link
 * #lastSuccessfulRefresh()}. Workers report into it; callers read out of it.
 *
 * <p>See {@code project/plans/sdk-hardening-and-verification.md §"Watcher of the watchers"} for the
 * full design.
 */
public final class Supervisor {

  private static final Logger LOG = LoggerFactory.getLogger(Supervisor.class);

  /**
   * Customer-visible health surface; values match the cross-SDK spec in {@code
   * project/plans/sdk-hardening-and-verification.md}.
   */
  public enum ConnectionState {
    /**
     * Pre-{@link #start()} state and the state during the first connection attempt before any
     * worker has reported success.
     */
    INITIALIZING,
    /** An SSE stream (Layer 1) is live. */
    CONNECTED,
    /**
     * The Layer 1 worker is between connection attempts (after a drop, before the next reconnect
     * succeeds).
     */
    DISCONNECTED,
    /** Layer 1 is unable to maintain a connection and the Layer 2 fallback poller is active. */
    FALLING_BACK
  }

  /**
   * One supervised unit of background work. {@link #run} is invoked on a dedicated thread; the
   * supervisor restarts it after any unchecked exception or non-cancellation return. {@link #run}
   * is expected to return promptly when {@link WorkerContext#isStopped()} is true (typically by
   * blocking on {@link WorkerContext#awaitStop()} or polling).
   */
  @FunctionalInterface
  public interface Worker {
    void run(WorkerContext ctx) throws Exception;
  }

  /** Layer label + worker body. The layer label is what shows up on the restart metric. */
  public static final class WorkerSpec {
    final String layer;
    final Worker worker;

    public WorkerSpec(String layer, Worker worker) {
      this.layer = Objects.requireNonNull(layer, "layer");
      this.worker = Objects.requireNonNull(worker, "worker");
    }
  }

  /**
   * Cooperation surface passed to each {@link Worker}. Workers either block on {@link #awaitStop()}
   * (woken by {@link Supervisor#stop()}) or poll {@link #isStopped()} between iterations.
   */
  public static final class WorkerContext {
    private final Supervisor s;

    WorkerContext(Supervisor s) {
      this.s = s;
    }

    /**
     * @return true once {@link Supervisor#stop()} has been called.
     */
    public boolean isStopped() {
      return s.stopping;
    }

    /** Block until {@link Supervisor#stop()} is called. Returns immediately if already stopped. */
    public void awaitStop() throws InterruptedException {
      synchronized (s.stopLock) {
        while (!s.stopping) {
          s.stopLock.wait();
        }
      }
    }

    /**
     * Block until {@link Supervisor#stop()} is called or {@code timeout} elapses. Returns true if
     * stop fired, false on timeout.
     */
    public boolean awaitStop(Duration timeout) throws InterruptedException {
      Objects.requireNonNull(timeout, "timeout");
      long remainingNanos = timeout.toNanos();
      long deadline = System.nanoTime() + remainingNanos;
      synchronized (s.stopLock) {
        while (!s.stopping) {
          if (remainingNanos <= 0) return false;
          long ms = remainingNanos / 1_000_000L;
          int ns = (int) (remainingNanos % 1_000_000L);
          s.stopLock.wait(ms, ns);
          remainingNanos = deadline - System.nanoTime();
        }
        return true;
      }
    }
  }

  /** Builder for {@link Supervisor}. Zero/null knobs get production defaults. */
  public static final class Builder {
    private Duration initialDelay = Duration.ofMillis(500);
    private Duration maxDelay = Duration.ofSeconds(30);
    private Duration stopTimeout = Duration.ofSeconds(5);
    private List<WorkerSpec> workers = Collections.emptyList();

    public Builder initialDelay(Duration d) {
      this.initialDelay = Objects.requireNonNull(d);
      return this;
    }

    public Builder maxDelay(Duration d) {
      this.maxDelay = Objects.requireNonNull(d);
      return this;
    }

    public Builder stopTimeout(Duration d) {
      this.stopTimeout = Objects.requireNonNull(d);
      return this;
    }

    public Builder workers(List<WorkerSpec> workers) {
      this.workers = List.copyOf(workers);
      return this;
    }

    public Supervisor build() {
      return new Supervisor(this);
    }
  }

  public static Builder builder() {
    return new Builder();
  }

  private final Duration initialDelay;
  private final Duration maxDelay;
  private final Duration stopTimeout;
  private final List<WorkerSpec> workers;

  private final Object stopLock = new Object();
  private volatile boolean stopping;
  private final AtomicBoolean startedOnce = new AtomicBoolean();
  private final AtomicBoolean stoppedOnce = new AtomicBoolean();
  private final List<Thread> threads = Collections.synchronizedList(new ArrayList<>());

  private final ConcurrentMap<String, AtomicLong> restartTotals = new ConcurrentHashMap<>();
  private volatile ConnectionState connectionState = ConnectionState.INITIALIZING;
  private volatile Instant lastSuccessfulRefresh; // null = never

  private Supervisor(Builder b) {
    this.initialDelay = b.initialDelay;
    this.maxDelay = b.maxDelay;
    this.stopTimeout = b.stopTimeout;
    this.workers = b.workers;
  }

  /** Spawns every worker on its own daemon thread. Idempotent. */
  public void start() {
    if (!startedOnce.compareAndSet(false, true)) return;
    for (WorkerSpec spec : workers) {
      Thread t = new Thread(() -> runWorker(spec), "quonfig-supervisor-layer-" + spec.layer);
      t.setDaemon(true);
      threads.add(t);
      t.start();
    }
  }

  /**
   * Signals every worker to wind down and waits for them to exit, up to {@link Builder#stopTimeout}
   * (default 5s). Idempotent. A wedged worker (shouldn't happen — the stop signal is delivered both
   * via the stop flag and via {@link Thread#interrupt()}) must not deadlock the parent client's
   * {@code close()}.
   */
  public void stop() {
    if (!stoppedOnce.compareAndSet(false, true)) return;
    synchronized (stopLock) {
      stopping = true;
      stopLock.notifyAll();
    }
    // Interrupt threads so any worker blocked in interruptible I/O can wake. Workers that
    // block on activeBody.close()-style cancellation should check isStopped() and return.
    for (Thread t : threads) {
      t.interrupt();
    }
    long deadlineNanos = System.nanoTime() + stopTimeout.toNanos();
    for (Thread t : threads) {
      long remaining = deadlineNanos - System.nanoTime();
      if (remaining <= 0) {
        LOG.warn("quonfig: supervisor.stop() deadline exceeded after {}", stopTimeout);
        return;
      }
      try {
        t.join(Math.max(1L, TimeUnit.NANOSECONDS.toMillis(remaining)));
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
      }
      if (t.isAlive()) {
        LOG.warn(
            "quonfig: supervisor worker thread {} did not exit within {}",
            t.getName(),
            stopTimeout);
      }
    }
  }

  /** Per-worker outer loop: catch crashes, count them, back off, restart. */
  private void runWorker(WorkerSpec spec) {
    WorkerContext ctx = new WorkerContext(this);
    int attempt = 0;
    while (!stopping) {
      boolean crashed = runOnce(spec, ctx);
      if (stopping) return;
      if (crashed) {
        incRestartTotal(spec.layer);
      }
      // Backoff. Even a non-crashed early return gets one tick of delay so a runaway worker
      // can't hot-loop.
      Duration delay = backoffFor(attempt++);
      try {
        synchronized (stopLock) {
          if (stopping) return;
          long ms = delay.toMillis();
          int ns = (int) (delay.toNanosPart() % 1_000_000L);
          if (ms == 0 && ns == 0) {
            // Object.wait(0,0) blocks indefinitely — coerce to 1ns so we still yield.
            ns = 1;
          }
          stopLock.wait(ms, ns);
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
      }
    }
  }

  /**
   * Invokes {@link Worker#run} inside a try/catch. Returns {@code true} if the worker crashed
   * (uncaught Throwable other than a stop-driven interrupt), {@code false} on clean exit driven by
   * {@link #stop()}.
   */
  private boolean runOnce(WorkerSpec spec, WorkerContext ctx) {
    try {
      spec.worker.run(ctx);
      return false;
    } catch (InterruptedException e) {
      // Most likely we're being stopped; only count as a crash if we weren't.
      Thread.currentThread().interrupt();
      if (stopping) return false;
      LOG.error("quonfig: worker interrupted unexpectedly; restarting layer={}", spec.layer, e);
      return true;
    } catch (Throwable t) {
      if (stopping) return false;
      LOG.error("quonfig: worker threw; restarting layer={} err={}", spec.layer, t.toString(), t);
      return true;
    }
  }

  /**
   * Returns the sleep duration before the {@code (attempt+1)}th restart. 500ms → 1s → 2s → 4s → 8s
   * → 16s → 30s (cap). {@code attempt} is 0-indexed; the returned value is capped at {@link
   * Builder#maxDelay}.
   */
  Duration backoffFor(int attempt) {
    long initialMs = initialDelay.toMillis();
    long maxMs = maxDelay.toMillis();
    long d = initialMs;
    for (int i = 0; i < attempt; i++) {
      long next = d * 2;
      if (next >= maxMs || next < d /* overflow guard */) {
        return Duration.ofMillis(maxMs);
      }
      d = next;
    }
    if (d > maxMs) d = maxMs;
    return Duration.ofMillis(d);
  }

  /**
   * Returns the running count of {@code quonfig_sdk_worker_restart_total{layer="<layer>"}} for this
   * supervisor. Unknown layers return 0.
   */
  public long workerRestartTotal(String layer) {
    AtomicLong c = restartTotals.get(layer);
    return c == null ? 0L : c.get();
  }

  private void incRestartTotal(String layer) {
    restartTotals.computeIfAbsent(layer, k -> new AtomicLong()).incrementAndGet();
  }

  /**
   * Returns the most recent transport state reported by any worker. Defaults to {@link
   * ConnectionState#INITIALIZING} before any state has been set.
   */
  public ConnectionState connectionState() {
    return connectionState;
  }

  /**
   * Records a transport-state transition. Workers call this (e.g. the SSE worker on
   * connect/disconnect, the fallback poller when it engages).
   */
  public void setConnectionState(ConnectionState state) {
    this.connectionState = Objects.requireNonNull(state, "state");
  }

  /**
   * Returns the wall-clock time of the most recent successful config install (any source). {@code
   * null} before the first install.
   */
  public Instant lastSuccessfulRefresh() {
    return lastSuccessfulRefresh;
  }

  /**
   * Stamps "now" as the most recent successful install. Callers (workers, the client install path)
   * invoke it after atomically swapping a new envelope into the store.
   */
  public void recordSuccessfulRefresh() {
    this.lastSuccessfulRefresh = Instant.now();
  }
}
