package com.quonfig.sdk.telemetry;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Drives periodic telemetry submission for a {@link com.quonfig.sdk.Quonfig} client.
 *
 * <p>Schedules an initial flush after {@link #initialDelay} and then re-schedules itself with
 * {@link #currentInterval()} after each attempt. On success the interval is reset to {@code
 * baseInterval}; on failure it grows by ×1.5 up to {@code maxInterval}.
 */
public final class TelemetryReporter implements AutoCloseable {

  private static final Logger LOG = LoggerFactory.getLogger(TelemetryReporter.class);

  private final TelemetrySender sender;
  private final String instanceHash;
  private final EvaluationSummaryCollector summaries;
  private final ContextShapeCollector shapes;
  private final ExampleContextCollector examples;
  private final Duration initialDelay;
  private final Duration baseInterval;
  private final Duration maxInterval;

  private volatile Duration currentInterval;
  private volatile boolean closed;
  private ScheduledExecutorService scheduler;
  private ScheduledFuture<?> nextRun;

  public TelemetryReporter(
      TelemetrySender sender,
      String instanceHash,
      EvaluationSummaryCollector summaries,
      ContextShapeCollector shapes,
      ExampleContextCollector examples,
      Duration initialDelay,
      Duration baseInterval,
      Duration maxInterval) {
    this.sender = sender;
    this.instanceHash = instanceHash;
    this.summaries = summaries;
    this.shapes = shapes;
    this.examples = examples;
    this.initialDelay = initialDelay;
    this.baseInterval = baseInterval;
    this.maxInterval = maxInterval;
    this.currentInterval = baseInterval;
  }

  public Duration currentInterval() {
    return currentInterval;
  }

  public boolean isClosed() {
    return closed;
  }

  public synchronized void start() {
    if (closed || scheduler != null) return;
    scheduler =
        Executors.newSingleThreadScheduledExecutor(
            r -> {
              Thread t = new Thread(r, "quonfig-telemetry");
              t.setDaemon(true);
              return t;
            });
    scheduleNext(initialDelay);
  }

  /**
   * Drains pending data synchronously and posts it. Returns silently when no events are pending.
   * Throws on transport failure so callers can decide whether to surface the error.
   */
  public void flush() throws IOException {
    Map<String, Object> envelope = buildEnvelope();
    if (envelope == null) return;
    sender.send(envelope);
  }

  /**
   * Internal method used by the periodic scheduler and tests: drains, sends, and updates {@link
   * #currentInterval()}. Returns {@code true} on successful send, {@code false} on swallowed error.
   * Returns {@code true} (no-op success) when no events are pending.
   */
  public synchronized boolean flushAndApplyBackoff() {
    Map<String, Object> envelope = buildEnvelope();
    if (envelope == null) {
      // Treat empty as success — no need to back off when there's nothing to send.
      currentInterval = baseInterval;
      return true;
    }
    try {
      sender.send(envelope);
      currentInterval = baseInterval;
      return true;
    } catch (Exception e) {
      long grown = (long) (currentInterval.toMillis() * 1.5);
      // Force progress when starting interval is small enough that ×1.5 rounds to itself.
      if (grown <= currentInterval.toMillis()) grown = currentInterval.toMillis() + 1;
      long next = Math.min(grown, maxInterval.toMillis());
      currentInterval = Duration.ofMillis(next);
      LOG.warn("telemetry sync failed; backing off to {} ms: {}", next, e.toString());
      return false;
    }
  }

  @Override
  public synchronized void close() {
    if (closed) return;
    closed = true;
    if (nextRun != null) nextRun.cancel(false);
    try {
      flush();
    } catch (IOException e) {
      LOG.warn("final telemetry flush failed: {}", e.toString());
    }
    if (scheduler != null) {
      scheduler.shutdown();
      try {
        if (!scheduler.awaitTermination(2, TimeUnit.SECONDS)) {
          scheduler.shutdownNow();
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        scheduler.shutdownNow();
      }
    }
  }

  private Map<String, Object> buildEnvelope() {
    List<Map<String, Object>> events = new ArrayList<>(3);
    Map<String, Object> s = summaries.drain();
    if (s != null) events.add(s);
    Map<String, Object> sh = shapes.drain();
    if (sh != null) events.add(sh);
    Map<String, Object> ex = examples.drain();
    if (ex != null) events.add(ex);
    if (events.isEmpty()) return null;

    Map<String, Object> envelope = new LinkedHashMap<>();
    envelope.put("instanceHash", instanceHash);
    envelope.put("events", events);
    return envelope;
  }

  private synchronized void scheduleNext(Duration delay) {
    if (closed || scheduler == null) return;
    nextRun =
        scheduler.schedule(
            () -> {
              try {
                flushAndApplyBackoff();
              } catch (RuntimeException e) {
                LOG.warn("telemetry tick failed: {}", e.toString());
              } finally {
                scheduleNext(currentInterval);
              }
            },
            delay.toMillis(),
            TimeUnit.MILLISECONDS);
  }
}
