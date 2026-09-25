package com.quonfig.sdk.telemetry;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.quonfig.sdk.Options;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Drives periodic telemetry submission for a {@link com.quonfig.sdk.Quonfig} client.
 *
 * <p>Once per tick (default 60s) the reporter drains the collectors into one serialized batch and
 * hands it to a retained queue that implements the telemetry transport policy (qfg-y8je.9): at most
 * one POST in flight, a 15s deadline per POST, failed batches kept byte-for-byte (5 batches / 2MB /
 * 5 min) and resent unchanged no sooner than 30s after a failure and after any {@code Retry-After}
 * (up to 10 min), telemetry disabled for the process on 401/403/404. Logging is DEBUG per failed
 * POST, one WARN when data is actually dropped (then a summary at most every 10 min), one INFO on
 * recovery. Telemetry never affects flag evaluation.
 */
public final class TelemetryReporter implements AutoCloseable {

  private static final Logger DEFAULT_LOG = LoggerFactory.getLogger(TelemetryReporter.class);

  /** close() gives the live window one POST with this deadline (P8). */
  static final long SHUTDOWN_FLUSH_DEADLINE_MS = 5_000;

  /** Resolved transport settings (test visibility). */
  record Config(
      long initialDelayMs,
      long flushIntervalMs,
      long timeoutMs,
      long connectTimeoutMs,
      int maxRetainedBatches,
      long maxRetainedBytes,
      long maxRetainedAgeMs) {}

  /** The contract's retained_count / retained_bytes / telemetry_enabled, plus liveness. */
  record DebugState(
      int retainedCount,
      long retainedBytes,
      boolean enabled,
      boolean busy,
      boolean timerActive,
      int postsStarted) {}

  private final String instanceHash;
  private final EvaluationSummaryCollector summaries;
  private final ContextShapeCollector shapes;
  private final ExampleContextCollector examples;
  private final FailoverCollector failover;
  private final Config config;
  private final Logger logger;
  private final ScheduledExecutorService scheduler;
  private final boolean ownsScheduler;
  private final TelemetryTransportQueue queue;
  private final ObjectMapper mapper = new ObjectMapper();

  private volatile boolean closed;
  private ScheduledFuture<?> timer;
  private CompletableFuture<Void> pendingTick;

  /**
   * Builds a reporter with the transport settings from {@code options} ({@code telemetry*}). Used
   * by {@link com.quonfig.sdk.Quonfig}; applications do not normally construct one.
   */
  public TelemetryReporter(
      TelemetrySender sender,
      String instanceHash,
      EvaluationSummaryCollector summaries,
      ContextShapeCollector shapes,
      ExampleContextCollector examples,
      FailoverCollector failover,
      Options options) {
    this(
        sender,
        instanceHash,
        summaries,
        shapes,
        examples,
        failover,
        options.telemetryInitialDelay(),
        options.telemetryFlushInterval(),
        options.telemetryTimeout().toMillis(),
        options.telemetryMaxRetainedBatches(),
        options.telemetryMaxRetainedBytes(),
        options.telemetryMaxRetainedAge().toMillis(),
        options.logger(),
        options.telemetryClock(),
        options.telemetryScheduler());
  }

  /**
   * @deprecated {@code maxInterval} is ignored since the transport policy removed the adaptive
   *     backoff; use {@link #TelemetryReporter(TelemetrySender, String, EvaluationSummaryCollector,
   *     ContextShapeCollector, ExampleContextCollector, FailoverCollector, Options)}.
   */
  @Deprecated
  public TelemetryReporter(
      TelemetrySender sender,
      String instanceHash,
      EvaluationSummaryCollector summaries,
      ContextShapeCollector shapes,
      ExampleContextCollector examples,
      Duration initialDelay,
      Duration baseInterval,
      Duration maxInterval) {
    this(
        sender,
        instanceHash,
        summaries,
        shapes,
        examples,
        null,
        initialDelay,
        baseInterval,
        maxInterval);
  }

  /**
   * @deprecated {@code maxInterval} is ignored since the transport policy removed the adaptive
   *     backoff; use {@link #TelemetryReporter(TelemetrySender, String, EvaluationSummaryCollector,
   *     ContextShapeCollector, ExampleContextCollector, FailoverCollector, Options)}.
   */
  @Deprecated
  public TelemetryReporter(
      TelemetrySender sender,
      String instanceHash,
      EvaluationSummaryCollector summaries,
      ContextShapeCollector shapes,
      ExampleContextCollector examples,
      FailoverCollector failover,
      Duration initialDelay,
      Duration baseInterval,
      Duration maxInterval) {
    this(
        sender,
        instanceHash,
        summaries,
        shapes,
        examples,
        failover,
        initialDelay,
        baseInterval,
        Options.DEFAULT_TELEMETRY_TIMEOUT.toMillis(),
        Options.DEFAULT_TELEMETRY_MAX_RETAINED_BATCHES,
        Options.DEFAULT_TELEMETRY_MAX_RETAINED_BYTES,
        Options.DEFAULT_TELEMETRY_MAX_RETAINED_AGE.toMillis(),
        DEFAULT_LOG,
        null,
        null);
  }

  private TelemetryReporter(
      TelemetrySender sender,
      String instanceHash,
      EvaluationSummaryCollector summaries,
      ContextShapeCollector shapes,
      ExampleContextCollector examples,
      FailoverCollector failover,
      Duration initialDelay,
      Duration flushInterval,
      long timeoutMs,
      int maxRetainedBatches,
      long maxRetainedBytes,
      long maxRetainedAgeMs,
      Logger logger,
      Clock clock,
      ScheduledExecutorService scheduler) {
    this.instanceHash = instanceHash;
    this.summaries = summaries;
    this.shapes = shapes;
    this.examples = examples;
    this.failover = failover;
    this.logger = logger != null ? logger : DEFAULT_LOG;
    long interval = positiveMs(flushInterval, Options.DEFAULT_TELEMETRY_FLUSH_INTERVAL.toMillis());
    HttpTelemetrySender http =
        sender instanceof HttpTelemetrySender ? (HttpTelemetrySender) sender : null;
    this.config =
        new Config(
            positiveMs(initialDelay, interval),
            interval,
            timeoutMs,
            http != null ? http.connectTimeoutMs() : -1,
            maxRetainedBatches,
            maxRetainedBytes,
            maxRetainedAgeMs);
    this.ownsScheduler = scheduler == null;
    this.scheduler = scheduler != null ? scheduler : newDaemonScheduler();
    TelemetryTransportQueue.Poster poster =
        http != null
            ? (body, ms) -> http.postAsync(body, Duration.ofMillis(ms))
            : legacyPoster(sender);
    this.queue =
        new TelemetryTransportQueue(
            poster,
            http != null ? http.endpoint() : "the configured telemetrySender",
            this.logger,
            timeoutMs,
            maxRetainedBatches,
            maxRetainedBytes,
            maxRetainedAgeMs,
            this::onDisabled,
            clock != null ? clock : Clock.systemUTC(),
            this.scheduler);
  }

  /**
   * Adapter for a caller-supplied {@link TelemetrySender}, which takes a map, not bytes: the
   * retained bytes are parsed back into the same map (Jackson round-trips them unchanged) and a
   * normal return counts as a 2xx. An exception counts as a retryable network failure.
   */
  private TelemetryTransportQueue.Poster legacyPoster(TelemetrySender sender) {
    return (body, ms) -> {
      try {
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = mapper.readValue(body, LinkedHashMap.class);
        sender.send(payload);
        return CompletableFuture.completedFuture(new TelemetryHttpResult(200, null, ""));
      } catch (IOException | RuntimeException e) {
        return CompletableFuture.failedFuture(e);
      }
    };
  }

  private static long positiveMs(Duration d, long fallback) {
    return d != null && !d.isNegative() && !d.isZero() ? d.toMillis() : fallback;
  }

  private static ScheduledExecutorService newDaemonScheduler() {
    return Executors.newSingleThreadScheduledExecutor(
        r -> {
          Thread t = new Thread(r, "quonfig-telemetry");
          t.setDaemon(true);
          return t;
        });
  }

  /**
   * @deprecated the adaptive interval was removed; always returns the flush interval.
   */
  @Deprecated
  public Duration currentInterval() {
    return Duration.ofMillis(config.flushIntervalMs());
  }

  public boolean isClosed() {
    return closed;
  }

  /**
   * Start the tick timer. Fixed cadence: the first tick after the initial delay (default one
   * interval), then one every flush interval regardless of how long a drain takes.
   */
  public synchronized void start() {
    if (closed || queue.disabled() || timer != null) return;
    schedule(config.initialDelayMs());
  }

  private synchronized void schedule(long delayMs) {
    try {
      timer = scheduler.schedule(this::fire, delayMs, TimeUnit.MILLISECONDS);
    } catch (RejectedExecutionException e) {
      timer = null;
    }
  }

  private void fire() {
    synchronized (this) {
      timer = null;
      if (closed || queue.disabled()) return;
      schedule(config.flushIntervalMs());
    }
    try {
      tick();
    } catch (RuntimeException e) {
      logger.debug("Telemetry tick failed: {}", e.toString());
    }
  }

  private synchronized void cancelTimer() {
    if (timer != null) {
      timer.cancel(false);
      timer = null;
    }
  }

  private synchronized boolean busyLocked() {
    return (pendingTick != null && !pendingTick.isDone()) || queue.busy();
  }

  /**
   * One tick of the contract's model: skip if closed, disabled or a POST is in flight (P2; the live
   * window keeps aggregating); expire aged batches; skip if the 30s floor or Retry-After has not
   * elapsed; serialize the live window once and append it; drain oldest-first. Returns a future
   * that completes when the drain has finished.
   */
  CompletableFuture<Void> tick() {
    CompletableFuture<Void> marker;
    synchronized (this) {
      if (closed || queue.disabled() || busyLocked()) {
        return CompletableFuture.completedFuture(null);
      }
      queue.expire();
      if (!queue.sendAllowed()) return CompletableFuture.completedFuture(null);
      byte[] body = serializeWindow();
      if (body != null) queue.append(body);
      marker = new CompletableFuture<>();
      pendingTick = marker;
    }
    try {
      queue
          .drain()
          .whenComplete(
              (v, err) -> {
                if (err != null) logger.debug("Telemetry drain failed: {}", err.toString());
                marker.complete(null);
              });
    } catch (RuntimeException e) {
      logger.debug("Telemetry drain failed: {}", e.toString());
      marker.complete(null);
    }
    return marker;
  }

  /** Wait (real time, up to {@code ms}) until no tick and no POST is running. */
  boolean awaitIdle(long ms) {
    long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(ms);
    while (busyLocked()) {
      if (System.nanoTime() > deadline) return false;
      try {
        Thread.sleep(2);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return false;
      }
    }
    return true;
  }

  /**
   * Sends the live window now (public {@code Quonfig.flush()}), useful in serverless handlers.
   * Waits for an in-flight POST first (bounded by the request timeout), then runs a tick and waits
   * for it, so after a failure it respects the 30s floor and {@code Retry-After}. Never throws: a
   * failed batch is retained and resent; the {@code throws} clause is kept for source
   * compatibility.
   */
  public void flush() throws IOException {
    if (closed || queue.disabled()) return;
    long bound = config.timeoutMs() + 1_000;
    awaitIdle(bound);
    CompletableFuture<Void> run = tick();
    try {
      run.get(bound, TimeUnit.MILLISECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    } catch (ExecutionException | TimeoutException e) {
      logger.debug("Telemetry flush did not finish: {}", e.toString());
    }
  }

  /**
   * @deprecated use {@link #flush()}. Runs one tick; returns {@code true} when no batch is left
   *     retained afterwards.
   */
  @Deprecated
  public boolean flushAndApplyBackoff() {
    try {
      flush();
    } catch (IOException e) {
      return false;
    }
    return queue.retainedCount() == 0;
  }

  /**
   * Shutdown (P8): stop the timer, abort any in-flight POST, then give the live window one POST
   * with a 5s deadline. The retained queue is not drained. Idempotent; returns within the deadline
   * even against a hanging endpoint, and leaves no telemetry thread running.
   */
  @Override
  public void close() {
    synchronized (this) {
      if (closed) return;
      closed = true;
    }
    cancelTimer();
    queue.stop();
    queue.abortInFlight();
    long deadline = Math.min(SHUTDOWN_FLUSH_DEADLINE_MS, config.timeoutMs());
    if (!queue.disabled()) {
      byte[] body = serializeWindow();
      if (body != null) {
        try {
          queue.sendFinal(body, deadline).get(deadline + 1_000, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        } catch (ExecutionException | TimeoutException | RuntimeException e) {
          logger.debug("Telemetry final flush at shutdown did not finish: {}", e.toString());
        }
      }
    }
    if (ownsScheduler) scheduler.shutdownNow();
  }

  Config config() {
    return config;
  }

  DebugState debugState() {
    boolean timerActive;
    synchronized (this) {
      timerActive = timer != null && !timer.isDone();
    }
    return new DebugState(
        queue.retainedCount(),
        queue.retainedBytes(),
        !queue.disabled(),
        busyLocked(),
        timerActive,
        queue.postsStarted());
  }

  EvaluationSummaryCollector summaries() {
    return summaries;
  }

  ContextShapeCollector shapes() {
    return shapes;
  }

  ExampleContextCollector examples() {
    return examples;
  }

  /** 401/403/404 (P3): stop ticking and stop aggregating for a dead endpoint. */
  private void onDisabled() {
    cancelTimer();
    summaries.disable();
    shapes.disable();
    examples.disable();
    if (failover != null) failover.disable();
  }

  /**
   * Drain the collectors into one serialized payload. This is the only serialization: the queue
   * stores and resends these exact bytes (P5, P9).
   */
  private byte[] serializeWindow() {
    List<Map<String, Object>> events = new ArrayList<>(4);
    Map<String, Object> s = summaries.drain();
    if (s != null) events.add(s);
    Map<String, Object> sh = shapes.drain();
    if (sh != null) events.add(sh);
    Map<String, Object> ex = examples.drain();
    if (ex != null) events.add(ex);
    if (failover != null) {
      Map<String, Object> fo = failover.drain();
      if (fo != null) events.add(fo);
    }
    if (events.isEmpty()) return null;

    Map<String, Object> envelope = new LinkedHashMap<>();
    envelope.put("instanceHash", instanceHash);
    envelope.put("events", events);
    try {
      return mapper.writeValueAsBytes(envelope);
    } catch (JsonProcessingException e) {
      logger.debug("Telemetry window could not be serialized and was dropped: {}", e.toString());
      return null;
    }
  }
}
