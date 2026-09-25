package com.quonfig.sdk.telemetry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.quonfig.sdk.eval.ContextSet;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class TelemetryReporterTest {

  private static EvaluationStat stat(String key, Object value) {
    return new EvaluationStat("cfg-1", key, "CONFIG", 0, -1, value, null, 1);
  }

  static final class CapturingSender implements TelemetrySender {
    final List<Map<String, Object>> sent = new ArrayList<>();
    final AtomicInteger failuresLeft = new AtomicInteger(0);

    @Override
    public synchronized void send(Map<String, Object> payload) throws IOException {
      if (failuresLeft.get() > 0) {
        failuresLeft.decrementAndGet();
        throw new IOException("simulated send failure");
      }
      sent.add(payload);
    }
  }

  @Test
  void flushDrainsAllThreeCollectorsIntoOneEnvelope() throws IOException {
    EvaluationSummaryCollector summaries = new EvaluationSummaryCollector(true);
    ContextShapeCollector shapes = new ContextShapeCollector(ContextUploadMode.PERIODIC_EXAMPLE);
    ExampleContextCollector examples =
        new ExampleContextCollector(ContextUploadMode.PERIODIC_EXAMPLE);

    summaries.push(stat("greeting", "hello"));
    ContextSet ctx = new ContextSet().withNamedContext("user", Map.of("key", "u-1", "plan", "pro"));
    shapes.push(ctx);
    examples.push(ctx);

    CapturingSender sender = new CapturingSender();
    TelemetryReporter reporter =
        new TelemetryReporter(
            sender,
            "instance-hash",
            summaries,
            shapes,
            examples,
            Duration.ofMillis(8000),
            Duration.ofMillis(60_000),
            Duration.ofMillis(600_000));

    reporter.flush();

    assertEquals(1, sender.sent.size());
    Map<String, Object> envelope = sender.sent.get(0);
    assertEquals("instance-hash", envelope.get("instanceHash"));
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> events = (List<Map<String, Object>>) envelope.get("events");
    assertEquals(3, events.size()); // summaries, contextShapes, exampleContexts

    boolean hasSummaries = false;
    boolean hasShapes = false;
    boolean hasExamples = false;
    for (Map<String, Object> e : events) {
      if (e.containsKey("summaries")) hasSummaries = true;
      if (e.containsKey("contextShapes")) hasShapes = true;
      if (e.containsKey("exampleContexts")) hasExamples = true;
    }
    assertTrue(hasSummaries);
    assertTrue(hasShapes);
    assertTrue(hasExamples);
  }

  @Test
  void flushSkipsSendWhenAllCollectorsEmpty() throws IOException {
    CapturingSender sender = new CapturingSender();
    TelemetryReporter reporter =
        new TelemetryReporter(
            sender,
            "h",
            new EvaluationSummaryCollector(true),
            new ContextShapeCollector(ContextUploadMode.PERIODIC_EXAMPLE),
            new ExampleContextCollector(ContextUploadMode.PERIODIC_EXAMPLE),
            Duration.ofMillis(8000),
            Duration.ofMillis(60_000),
            Duration.ofMillis(600_000));
    reporter.flush();
    assertTrue(sender.sent.isEmpty());
  }

  @Test
  void failedSendIsRetainedAndResentUnchangedAfterTheFloor() throws IOException {
    // The adaptive backoff is gone (transport policy P4, qfg-y8je.9): a failed batch is kept and
    // resent unchanged on the first tick at least 30s after the failure.
    EvaluationSummaryCollector summaries = new EvaluationSummaryCollector(true);
    ManualScheduler clock = new ManualScheduler();
    CapturingSender sender = new CapturingSender();
    sender.failuresLeft.set(1);
    TelemetryReporter reporter =
        new TelemetryReporter(
            sender,
            "h",
            summaries,
            new ContextShapeCollector(ContextUploadMode.PERIODIC_EXAMPLE),
            new ExampleContextCollector(ContextUploadMode.PERIODIC_EXAMPLE),
            null,
            com.quonfig.sdk.Options.builder()
                .telemetryClock(clock.clock())
                .telemetryScheduler(clock)
                .build());

    summaries.push(stat("x", "v"));
    reporter.flush(); // fails: retained
    assertTrue(sender.sent.isEmpty());
    assertEquals(1, reporter.debugState().retainedCount());

    summaries.push(stat("y", "v"));
    reporter.flush(); // inside the 30s floor: nothing sent, the live window keeps aggregating
    assertTrue(sender.sent.isEmpty());

    clock.advance(30_000);
    reporter.flush(); // resend the retained batch, then the live window
    assertEquals(2, sender.sent.size());
    assertTrue(sender.sent.get(0).toString().contains("key=x"));
    assertFalse(sender.sent.get(0).toString().contains("key=y"));
    assertTrue(sender.sent.get(1).toString().contains("key=y"));
    assertEquals(0, reporter.debugState().retainedCount());
    assertEquals(Duration.ofMillis(60_000), reporter.currentInterval());
  }

  @Test
  void closeFlushesPendingDataAndStopsScheduler() throws Exception {
    EvaluationSummaryCollector summaries = new EvaluationSummaryCollector(true);
    ContextShapeCollector shapes = new ContextShapeCollector(ContextUploadMode.PERIODIC_EXAMPLE);
    ExampleContextCollector examples =
        new ExampleContextCollector(ContextUploadMode.PERIODIC_EXAMPLE);

    CapturingSender sender = new CapturingSender();
    TelemetryReporter reporter =
        new TelemetryReporter(
            sender,
            "h",
            summaries,
            shapes,
            examples,
            Duration.ofMillis(8000),
            Duration.ofMillis(60_000),
            Duration.ofMillis(600_000));

    reporter.start();
    summaries.push(stat("x", "v"));
    reporter.close();

    assertEquals(1, sender.sent.size());
    assertTrue(reporter.isClosed());
  }

  @Test
  void redactionIsCarriedThroughToWirePayload() throws IOException {
    EvaluationSummaryCollector summaries = new EvaluationSummaryCollector(true);
    ContextShapeCollector shapes = new ContextShapeCollector(ContextUploadMode.PERIODIC_EXAMPLE);
    ExampleContextCollector examples =
        new ExampleContextCollector(ContextUploadMode.PERIODIC_EXAMPLE);

    summaries.push(
        new EvaluationStat(
            "cfg-secret", "secret", "CONFIG", 0, -1, "real-plaintext-password", "*****abc12", 1));

    CapturingSender sender = new CapturingSender();
    TelemetryReporter reporter =
        new TelemetryReporter(
            sender,
            "h",
            summaries,
            shapes,
            examples,
            Duration.ofMillis(8000),
            Duration.ofMillis(60_000),
            Duration.ofMillis(600_000));

    reporter.flush();

    // Walk the envelope and assert the plaintext is nowhere
    Map<String, Object> envelope = sender.sent.get(0);
    String json = envelope.toString();
    assertFalse(json.contains("real-plaintext-password"), "plaintext leaked into payload: " + json);
    assertTrue(json.contains("*****abc12"));
  }

  @Test
  void includesInstanceHashOnEveryEnvelope() throws IOException {
    EvaluationSummaryCollector summaries = new EvaluationSummaryCollector(true);
    ContextShapeCollector shapes = new ContextShapeCollector(ContextUploadMode.PERIODIC_EXAMPLE);
    ExampleContextCollector examples =
        new ExampleContextCollector(ContextUploadMode.PERIODIC_EXAMPLE);

    summaries.push(stat("k", "v"));

    CapturingSender sender = new CapturingSender();
    TelemetryReporter reporter =
        new TelemetryReporter(
            sender,
            "abcd-1234",
            summaries,
            shapes,
            examples,
            Duration.ofMillis(8000),
            Duration.ofMillis(60_000),
            Duration.ofMillis(600_000));
    reporter.flush();
    assertEquals("abcd-1234", sender.sent.get(0).get("instanceHash"));
  }

  @Test
  void failoverEventRidesTheFlushWithEvalAndContextCollectionOff() throws IOException {
    // Eval-summary collection OFF and context mode NONE: the failover event must still ride the
    // flush (it carries no user data and is the operational failover signal). Mirrors sdk-go's
    // submitter round-trip.
    EvaluationSummaryCollector summaries = new EvaluationSummaryCollector(false);
    ContextShapeCollector shapes = new ContextShapeCollector(ContextUploadMode.NONE);
    ExampleContextCollector examples = new ExampleContextCollector(ContextUploadMode.NONE);
    FailoverCollector failover = new FailoverCollector();

    failover.recordHedgeFired();
    failover.recordGuardRejected();
    failover.recordResolvedFrom(0);
    failover.recordResolvedFrom(1);

    CapturingSender sender = new CapturingSender();
    TelemetryReporter reporter =
        new TelemetryReporter(
            sender,
            "instance-hash",
            summaries,
            shapes,
            examples,
            failover,
            Duration.ofMillis(8000),
            Duration.ofMillis(60_000),
            Duration.ofMillis(600_000));

    reporter.flush();

    assertEquals(1, sender.sent.size());
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> events = (List<Map<String, Object>>) sender.sent.get(0).get("events");
    assertEquals(1, events.size(), "only the failover event should be present");
    Map<String, Object> event = events.get(0);
    assertTrue(event.containsKey("failover"));
    @SuppressWarnings("unchecked")
    Map<String, Object> f = (Map<String, Object>) event.get("failover");
    assertNotNull(f.get("start"));
    assertNotNull(f.get("end"));
    assertEquals(1L, ((Number) f.get("hedgeFired")).longValue());
    assertEquals(1L, ((Number) f.get("guardRejected")).longValue());
    assertEquals(1L, ((Number) f.get("resolvedFromPrimary")).longValue());
    assertEquals(1L, ((Number) f.get("resolvedFromSecondary")).longValue());
    assertEquals(0L, ((Number) f.get("resolvedFromLkg")).longValue());
  }

  @Test
  void schemaShapeMatchesApiTelemetry() throws IOException {
    EvaluationSummaryCollector summaries = new EvaluationSummaryCollector(true);
    ContextShapeCollector shapes = new ContextShapeCollector(ContextUploadMode.PERIODIC_EXAMPLE);
    ExampleContextCollector examples =
        new ExampleContextCollector(ContextUploadMode.PERIODIC_EXAMPLE);

    summaries.push(stat("greeting", "hello"));
    ContextSet ctx = new ContextSet().withNamedContext("user", Map.of("key", "u-1", "plan", "pro"));
    shapes.push(ctx);
    examples.push(ctx);

    CapturingSender sender = new CapturingSender();
    TelemetryReporter reporter =
        new TelemetryReporter(
            sender,
            "abcd",
            summaries,
            shapes,
            examples,
            Duration.ofMillis(8000),
            Duration.ofMillis(60_000),
            Duration.ofMillis(600_000));
    reporter.flush();

    Map<String, Object> env = sender.sent.get(0);
    assertNotNull(env.get("instanceHash"));
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> events = (List<Map<String, Object>>) env.get("events");
    for (Map<String, Object> e : events) {
      if (e.containsKey("summaries")) {
        @SuppressWarnings("unchecked")
        Map<String, Object> s = (Map<String, Object>) e.get("summaries");
        assertNotNull(s.get("start"));
        assertNotNull(s.get("end"));
        assertNotNull(s.get("summaries"));
      } else if (e.containsKey("contextShapes")) {
        @SuppressWarnings("unchecked")
        Map<String, Object> cs = (Map<String, Object>) e.get("contextShapes");
        assertNotNull(cs.get("shapes"));
      } else if (e.containsKey("exampleContexts")) {
        @SuppressWarnings("unchecked")
        Map<String, Object> ex = (Map<String, Object>) e.get("exampleContexts");
        assertNotNull(ex.get("examples"));
      } else {
        throw new AssertionError("unexpected event keys: " + e.keySet());
      }
    }
  }
}
