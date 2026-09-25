package com.quonfig.sdk.telemetry;

import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.time.Clock;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;

/**
 * Telemetry transport policy (qfg-y8je.9; policy P1-P10 in
 * project/plans/2026-09-24-sdk-telemetry-transport-policy.md, contract tests in
 * integration-test-data/chaos/telemetry-transport-contract.md). Java port of sdk-node's {@code
 * src/telemetry/transportQueue.ts}.
 *
 * <p>Owns the retained queue of serialized batches, the send gate (30s floor after a failure +
 * {@code Retry-After}), the drain loop, disable-on-auth and the P7 logging episodes. It knows
 * nothing about collectors or payload shape: it stores and resends opaque bytes.
 */
final class TelemetryTransportQueue {

  /** No send sooner than this after a failed POST (P4). */
  static final long RESEND_FLOOR_MS = 30_000;

  /** {@code Retry-After} is honored up to this (P4). */
  static final long RETRY_AFTER_CAP_MS = 600_000;

  /** At most one drop WARN per this interval while dropping continues (P7). */
  static final long DROP_WARN_INTERVAL_MS = 600_000;

  enum StatusClass {
    OK,
    RETRYABLE,
    AUTH,
    REJECTED
  }

  /** One POST of serialized bytes; completes exceptionally on a transport error. */
  @FunctionalInterface
  interface Poster {
    CompletableFuture<TelemetryHttpResult> post(byte[] body, long timeoutMs);
  }

  /**
   * 2xx -> OK; 401, 403, 404 -> AUTH; 408, 429, 5xx -> RETRYABLE; every other status (other 4xx,
   * 3xx, 1xx) -> REJECTED (P3).
   */
  static StatusClass classify(int status) {
    if (status >= 200 && status < 300) return StatusClass.OK;
    if (status == 401 || status == 403 || status == 404) return StatusClass.AUTH;
    if (status == 408 || status == 429 || (status >= 500 && status < 600)) {
      return StatusClass.RETRYABLE;
    }
    return StatusClass.REJECTED;
  }

  /**
   * Parse a {@code Retry-After} header into a wait in ms: delta-seconds, or an HTTP-date relative
   * to {@code nowMs} (past dates -> 0). Unparseable -> null. Clamped to {@link
   * #RETRY_AFTER_CAP_MS}.
   */
  static Long parseRetryAfterMs(String header, long nowMs) {
    if (header == null) return null;
    String v = header.trim();
    if (v.isEmpty()) return null;
    long ms;
    if (v.chars().allMatch(Character::isDigit)) {
      try {
        ms = Math.multiplyExact(Long.parseLong(v), 1000L);
      } catch (ArithmeticException | NumberFormatException e) {
        ms = RETRY_AFTER_CAP_MS;
      }
    } else {
      try {
        long at =
            ZonedDateTime.parse(v, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli();
        ms = Math.max(0, at - nowMs);
      } catch (DateTimeParseException e) {
        return null;
      }
    }
    return Math.min(ms, RETRY_AFTER_CAP_MS);
  }

  private static final class Batch {
    final byte[] body;
    final long createdAt;
    final boolean oversize;

    Batch(byte[] body, long createdAt, boolean oversize) {
      this.body = body;
      this.createdAt = createdAt;
      this.oversize = oversize;
    }
  }

  /** A POST outcome: an HTTP result, or a failure description when no response came back. */
  private record Outcome(TelemetryHttpResult result, String failure) {
    static final String ABORTED = "aborted";
  }

  private final Poster poster;
  private final String telemetryUrl;
  private final Logger logger;
  private final long timeoutMs;
  private final int maxRetainedBatches;
  private final long maxRetainedBytes;
  private final long maxRetainedAgeMs;
  private final Runnable onDisabled;
  private final Clock clock;
  private final ScheduledExecutorService scheduler;

  // Guarded by this.
  private final List<Batch> queue = new ArrayList<>();
  private CompletableFuture<Outcome> inFlight;
  private CompletableFuture<TelemetryHttpResult> inFlightHttp;
  private int postsStarted;
  private boolean stopped;
  private long lastFailureAt = Long.MIN_VALUE;
  private long retryAfterUntil = Long.MIN_VALUE;
  private volatile boolean disabled;

  // Outage episode (P7).
  private int failuresSinceSuccess;
  private Long firstFailureAt;
  private String lastResult = "";
  private Long lastDropWarnAt;
  private int dropsSinceWarn;
  private int dropsThisOutage;

  // Rejected-batch (other 4xx) cadence.
  private Long lastRejectErrorAt;
  private int rejectsSinceError;

  TelemetryTransportQueue(
      Poster poster,
      String telemetryUrl,
      Logger logger,
      long timeoutMs,
      int maxRetainedBatches,
      long maxRetainedBytes,
      long maxRetainedAgeMs,
      Runnable onDisabled,
      Clock clock,
      ScheduledExecutorService scheduler) {
    this.poster = poster;
    this.telemetryUrl = telemetryUrl;
    this.logger = logger;
    this.timeoutMs = timeoutMs;
    this.maxRetainedBatches = maxRetainedBatches;
    this.maxRetainedBytes = maxRetainedBytes;
    this.maxRetainedAgeMs = maxRetainedAgeMs;
    this.onDisabled = onDisabled;
    this.clock = clock;
    this.scheduler = scheduler;
  }

  /** A POST is in flight. */
  synchronized boolean busy() {
    return inFlight != null;
  }

  boolean disabled() {
    return disabled;
  }

  /** Every queued batch, including a not-yet-sent oversize one. */
  synchronized int retainedCount() {
    return queue.size();
  }

  synchronized long retainedBytes() {
    long n = 0;
    for (Batch b : queue) n += b.body.length;
    return n;
  }

  /** POSTs started so far (test visibility). */
  synchronized int postsStarted() {
    return postsStarted;
  }

  /** Discard batches older than the max age (strictly greater). Tick step 2. */
  synchronized void expire() {
    long now = clock.millis();
    // Batches are appended in time order, so only the head can be the oldest.
    while (!queue.isEmpty() && now - queue.get(0).createdAt > maxRetainedAgeMs) {
      queue.remove(0);
      recordDrop("batch older than " + Math.round(maxRetainedAgeMs / 60_000.0) + " min");
    }
  }

  /** The 30s floor after a failure and any Retry-After have both elapsed. Tick step 3. */
  synchronized boolean sendAllowed() {
    long now = clock.millis();
    boolean floorElapsed =
        lastFailureAt == Long.MIN_VALUE || now >= lastFailureAt + RESEND_FLOOR_MS;
    return floorElapsed && now >= retryAfterUntil;
  }

  /** Append a serialized window and enforce the caps (drop oldest). Tick step 4. */
  synchronized void append(byte[] body) {
    boolean oversize = body.length > maxRetainedBytes;
    queue.add(new Batch(body, clock.millis(), oversize));

    int count = 0;
    long bytes = 0;
    for (Batch b : queue) {
      if (b.oversize) continue;
      count++;
      bytes += b.body.length;
    }
    // An oversize batch is never evicted by cap enforcement and never counted against the caps.
    while (count > maxRetainedBatches || bytes > maxRetainedBytes) {
      int i = firstNonOversize();
      if (i < 0) break;
      Batch evicted = queue.remove(i);
      count--;
      bytes -= evicted.body.length;
      recordDrop("retained queue full");
    }
  }

  private int firstNonOversize() {
    for (int i = 0; i < queue.size(); i++) if (!queue.get(i).oversize) return i;
    return -1;
  }

  /** POST queued batches oldest-first, one at a time; stop at the first failure. Tick step 5. */
  CompletableFuture<Void> drain() {
    Batch batch;
    synchronized (this) {
      if (queue.isEmpty() || disabled || stopped) {
        dropUnsentOversize();
        return CompletableFuture.completedFuture(null);
      }
      batch = queue.get(0);
    }
    return send(batch.body, timeoutMs, false)
        .thenCompose(
            outcome -> {
              if (handle(batch, outcome)) return drain();
              synchronized (this) {
                dropUnsentOversize();
              }
              return CompletableFuture.completedFuture(null);
            });
  }

  /** Stop draining: no new POST starts except {@link #sendFinal}. */
  synchronized void stop() {
    stopped = true;
  }

  /** Abort the in-flight POST, if any (close()). The aborted batch is kept but never resent. */
  void abortInFlight() {
    CompletableFuture<Outcome> out;
    CompletableFuture<TelemetryHttpResult> http;
    synchronized (this) {
      out = inFlight;
      http = inFlightHttp;
    }
    if (out != null) out.complete(new Outcome(null, Outcome.ABORTED));
    if (http != null) http.cancel(true);
  }

  /**
   * close(): one POST of the live window bounded by {@code deadlineMs}. Never retains, never
   * touches the outage episode, never completes exceptionally.
   */
  CompletableFuture<Void> sendFinal(byte[] body, long deadlineMs) {
    return send(body, deadlineMs, true)
        .<Void>handle(
            (outcome, err) -> {
              String result = null;
              if (err != null) {
                result = describe(err);
              } else if (outcome.failure() != null) {
                result = outcome.failure();
              } else if (classify(outcome.result().status()) != StatusClass.OK) {
                result = String.valueOf(outcome.result().status());
              }
              if (result != null) {
                logger.debug(
                    "Telemetry final flush at shutdown failed ({}); {} bytes dropped, {} retained"
                        + " batch(es) abandoned",
                    result,
                    body.length,
                    retainedCount());
              }
              return null;
            });
  }

  /**
   * One POST with an overall deadline driven by the injected scheduler. Completes with an {@link
   * Outcome}; never exceptionally.
   */
  private CompletableFuture<Outcome> send(byte[] body, long deadlineMs, boolean evenIfStopped) {
    CompletableFuture<Outcome> out = new CompletableFuture<>();
    synchronized (this) {
      if (stopped && !evenIfStopped) {
        out.complete(new Outcome(null, Outcome.ABORTED));
        return out;
      }
      postsStarted++;
      inFlight = out;
    }
    CompletableFuture<TelemetryHttpResult> http;
    try {
      http = poster.post(body, deadlineMs);
    } catch (RuntimeException e) {
      http = CompletableFuture.failedFuture(e);
    }
    CompletableFuture<TelemetryHttpResult> h = http;
    synchronized (this) {
      if (inFlight == out) inFlightHttp = h;
    }
    ScheduledFuture<?> timer = null;
    if (!h.isDone()) {
      try {
        timer =
            scheduler.schedule(
                () -> {
                  if (out.complete(new Outcome(null, "timeout"))) h.cancel(true);
                },
                deadlineMs,
                TimeUnit.MILLISECONDS);
      } catch (RejectedExecutionException e) {
        // Scheduler already shut down; the JDK request timeout still bounds the POST.
      }
    }
    ScheduledFuture<?> t = timer;
    h.whenComplete(
        (res, err) -> {
          if (t != null) t.cancel(false);
          out.complete(err == null ? new Outcome(res, null) : new Outcome(null, describe(err)));
        });
    return out.whenComplete(
        (o, e) -> {
          synchronized (this) {
            if (inFlight == out) {
              inFlight = null;
              inFlightHttp = null;
            }
          }
        });
  }

  /** Apply one POST outcome. Returns true to continue the drain with the next batch. */
  private boolean handle(Batch batch, Outcome outcome) {
    boolean fireDisabled = false;
    boolean cont = false;
    synchronized (this) {
      if (Outcome.ABORTED.equals(outcome.failure())) {
        return false;
      }
      if (outcome.failure() != null) {
        onRetryableFailure(batch, outcome.failure(), null);
      } else {
        TelemetryHttpResult res = outcome.result();
        int status = res.status();
        switch (classify(status)) {
          case OK:
            queue.remove(batch);
            onSuccess();
            cont = true;
            break;
          case RETRYABLE:
            onRetryableFailure(batch, String.valueOf(status), res.retryAfter());
            break;
          case AUTH:
            disable(status);
            fireDisabled = true;
            break;
          default:
            // Rejected: drop this batch, report, carry on with the next one.
            queue.remove(batch);
            onRejected(status, batch.body.length, res.bodySnippet());
            cont = true;
        }
      }
    }
    if (fireDisabled) onDisabled.run();
    return cont;
  }

  /** Oversize batches are never carried across ticks. */
  private void dropUnsentOversize() {
    for (int i = queue.size() - 1; i >= 0; i--) {
      if (queue.get(i).oversize) {
        queue.remove(i);
        recordDrop("batch larger than the byte cap");
      }
    }
  }

  private void onSuccess() {
    if (failuresSinceSuccess == 0) return;
    long now = clock.millis();
    long seconds = Math.round((now - (firstFailureAt != null ? firstFailureAt : now)) / 1000.0);
    logger.info(
        "Telemetry recovered: POST succeeded after {} failed attempt(s) over {}s; {} batch(es)"
            + " were dropped.",
        failuresSinceSuccess,
        seconds,
        dropsThisOutage);
    failuresSinceSuccess = 0;
    firstFailureAt = null;
    dropsThisOutage = 0;
    lastDropWarnAt = null;
    dropsSinceWarn = 0;
  }

  private void onRetryableFailure(Batch batch, String result, String retryAfter) {
    long now = clock.millis();
    failuresSinceSuccess++;
    if (firstFailureAt == null) firstFailureAt = now;
    lastFailureAt = now;
    lastResult = result;
    Long wait = parseRetryAfterMs(retryAfter, now);
    if (wait != null) retryAfterUntil = now + wait;

    long nextMs = Math.max(lastFailureAt + RESEND_FLOOR_MS, retryAfterUntil) - now;
    logger.debug(
        "Telemetry POST failed ({}); {} batch(es) / {} bytes retained, next send in >= {}s",
        result,
        queue.size(),
        retainedBytesLocked(),
        (nextMs + 999) / 1000);

    if (batch.oversize && queue.remove(batch)) {
      recordDrop("batch larger than the byte cap");
    }
  }

  private void disable(int status) {
    String hint = status == 404 ? "wrong telemetryUrl" : "the SDK key was rejected";
    logger.error(
        "Telemetry disabled for this process: {} answered {} ({}). Flag evaluation is unaffected.",
        telemetryUrl,
        status,
        hint);
    queue.clear();
    disabled = true;
  }

  private void onRejected(int status, int bytes, String bodySnippet) {
    long now = clock.millis();
    if (lastRejectErrorAt == null || now - lastRejectErrorAt >= DROP_WARN_INTERVAL_MS) {
      int n = rejectsSinceError;
      logger.error(
          "Telemetry batch rejected with {} and dropped ({} bytes{}): {}. This is likely an SDK"
              + " bug; please report it.",
          status,
          bytes,
          n > 0 ? ", " + n + " more since the last report" : "",
          bodySnippet);
      lastRejectErrorAt = now;
      rejectsSinceError = 0;
    } else {
      rejectsSinceError++;
      logger.debug("Telemetry batch rejected with {} and dropped ({} bytes)", status, bytes);
    }
  }

  private void recordDrop(String reason) {
    long now = clock.millis();
    dropsSinceWarn++;
    dropsThisOutage++;
    String last = lastResult.isEmpty() ? "none" : lastResult;
    if (lastDropWarnAt == null) {
      logger.warn(
          "Telemetry is dropping data: {} (last POST result: {}). {} batch(es) dropped so far;"
              + " retained queue {}/{} batches, {} bytes. Flag evaluation is unaffected; further"
              + " drops log at debug with a summary every 10 min.",
          reason,
          last,
          dropsThisOutage,
          queue.size(),
          maxRetainedBatches,
          retainedBytesLocked());
      lastDropWarnAt = now;
      dropsSinceWarn = 0;
    } else if (now - lastDropWarnAt >= DROP_WARN_INTERVAL_MS) {
      long minutes = Math.round((now - lastDropWarnAt) / 60_000.0);
      logger.warn(
          "Telemetry still dropping data: {} batch(es) dropped in the last {} min (last POST"
              + " result: {}); retained queue {} batches, {} bytes.",
          dropsSinceWarn,
          minutes,
          last,
          queue.size(),
          retainedBytesLocked());
      lastDropWarnAt = now;
      dropsSinceWarn = 0;
    } else {
      logger.debug(
          "Telemetry dropped a batch: {}; {} since the last warning", reason, dropsSinceWarn);
    }
  }

  private long retainedBytesLocked() {
    long n = 0;
    for (Batch b : queue) n += b.body.length;
    return n;
  }

  /** {@code lastResult} text for a request that got no HTTP response. */
  static String describe(Throwable err) {
    Throwable e = err;
    while ((e instanceof CompletionException || e instanceof ExecutionException)
        && e.getCause() != null) {
      e = e.getCause();
    }
    if (e instanceof HttpConnectTimeoutException) return "connect timeout";
    if (e instanceof HttpTimeoutException) return "timeout";
    if (e instanceof CancellationException) return Outcome.ABORTED;
    String msg = e.getMessage();
    return "network error: "
        + e.getClass().getSimpleName()
        + (msg != null && !msg.isEmpty() ? " " + msg : "");
  }
}
