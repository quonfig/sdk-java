package com.quonfig.sdk.telemetry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.quonfig.sdk.telemetry.TelemetryTransportQueue.StatusClass;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.Test;

/** Unit tests for the telemetry transport queue helpers (qfg-y8je.9). */
class TelemetryTransportUnitsTest {

  @Test
  void classifyStatus() {
    assertEquals(StatusClass.OK, TelemetryTransportQueue.classify(200));
    assertEquals(StatusClass.OK, TelemetryTransportQueue.classify(204));
    assertEquals(StatusClass.REJECTED, TelemetryTransportQueue.classify(301));
    assertEquals(StatusClass.REJECTED, TelemetryTransportQueue.classify(400));
    assertEquals(StatusClass.AUTH, TelemetryTransportQueue.classify(401));
    assertEquals(StatusClass.AUTH, TelemetryTransportQueue.classify(403));
    assertEquals(StatusClass.AUTH, TelemetryTransportQueue.classify(404));
    assertEquals(StatusClass.RETRYABLE, TelemetryTransportQueue.classify(408));
    assertEquals(StatusClass.REJECTED, TelemetryTransportQueue.classify(413));
    assertEquals(StatusClass.REJECTED, TelemetryTransportQueue.classify(422));
    assertEquals(StatusClass.RETRYABLE, TelemetryTransportQueue.classify(429));
    assertEquals(StatusClass.RETRYABLE, TelemetryTransportQueue.classify(500));
    assertEquals(StatusClass.RETRYABLE, TelemetryTransportQueue.classify(503));
  }

  @Test
  void parseRetryAfter() {
    long now = Instant.parse("2026-09-25T00:00:00Z").toEpochMilli();
    assertEquals(120_000L, TelemetryTransportQueue.parseRetryAfterMs("120", now));
    assertEquals(5_000L, TelemetryTransportQueue.parseRetryAfterMs(" 5 ", now));
    assertEquals(600_000L, TelemetryTransportQueue.parseRetryAfterMs("3600", now));
    assertEquals(600_000L, TelemetryTransportQueue.parseRetryAfterMs("99999999999999999999", now));
    DateTimeFormatter f = DateTimeFormatter.RFC_1123_DATE_TIME;
    String future = f.format(Instant.ofEpochMilli(now + 90_000).atOffset(ZoneOffset.UTC));
    String past = f.format(Instant.ofEpochMilli(now - 90_000).atOffset(ZoneOffset.UTC));
    assertEquals(90_000L, TelemetryTransportQueue.parseRetryAfterMs(future, now));
    assertEquals(0L, TelemetryTransportQueue.parseRetryAfterMs(past, now));
    assertNull(TelemetryTransportQueue.parseRetryAfterMs("soon", now));
    assertNull(TelemetryTransportQueue.parseRetryAfterMs("", now));
    assertNull(TelemetryTransportQueue.parseRetryAfterMs(null, now));
  }

  @Test
  void describeTransportErrors() {
    assertEquals(
        "timeout",
        TelemetryTransportQueue.describe(
            new CompletionException(new HttpTimeoutException("request timed out"))));
    assertEquals(
        "connect timeout",
        TelemetryTransportQueue.describe(new HttpConnectTimeoutException("connect timed out")));
    assertEquals(
        "network error: ConnectException refused",
        TelemetryTransportQueue.describe(new java.net.ConnectException("refused")));
  }

  private static TelemetryTransportQueue queue(
      ManualScheduler clock, List<Integer> statuses, int maxBatches, long maxBytes) {
    return new TelemetryTransportQueue(
        (body, ms) ->
            CompletableFuture.completedFuture(
                new TelemetryHttpResult(statuses.isEmpty() ? 200 : statuses.remove(0), null, "")),
        "http://stub",
        new CaptureLogger(),
        15_000,
        maxBatches,
        maxBytes,
        300_000,
        () -> {},
        clock.clock(),
        clock);
  }

  @Test
  void ageBoundaryIsStrictlyGreater() {
    ManualScheduler clock = new ManualScheduler();
    TelemetryTransportQueue q = queue(clock, new ArrayList<>(), 5, 1024);
    q.append(new byte[10]);
    clock.advance(300_000);
    q.expire();
    assertEquals(1, q.retainedCount(), "a batch aged exactly 300000ms survives");
    clock.advance(1);
    q.expire();
    assertEquals(0, q.retainedCount());
  }

  @Test
  void byteCapEvictsOldestAndOversizeIsNotCountedAgainstTheCaps() {
    ManualScheduler clock = new ManualScheduler();
    TelemetryTransportQueue q = queue(clock, new ArrayList<>(List.of(503)), 5, 100);
    q.append(new byte[40]);
    q.append(new byte[40]);
    q.append(new byte[40]); // 120 > 100: the oldest is evicted
    assertEquals(2, q.retainedCount());
    assertEquals(80, q.retainedBytes());
    q.append(new byte[500]); // oversize: queued for one send, evicts nothing
    assertEquals(3, q.retainedCount());
    q.drain().join(); // 503 on the oldest: stop; the unsent oversize batch is dropped
    assertEquals(2, q.retainedCount());
    assertEquals(80, q.retainedBytes());
  }
}
