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
  void backoffGrowsOnFailureAndResetsOnSuccess() throws IOException {
    EvaluationSummaryCollector summaries = new EvaluationSummaryCollector(true);
    ContextShapeCollector shapes = new ContextShapeCollector(ContextUploadMode.PERIODIC_EXAMPLE);
    ExampleContextCollector examples =
        new ExampleContextCollector(ContextUploadMode.PERIODIC_EXAMPLE);

    CapturingSender sender = new CapturingSender();
    sender.failuresLeft.set(2);

    TelemetryReporter reporter =
        new TelemetryReporter(
            sender,
            "h",
            summaries,
            shapes,
            examples,
            Duration.ofMillis(1000),
            Duration.ofMillis(60_000),
            Duration.ofMillis(600_000));
    Duration baseline = reporter.currentInterval();
    assertEquals(Duration.ofMillis(60_000), baseline);

    summaries.push(stat("x", "v"));
    boolean ok1 = reporter.flushAndApplyBackoff();
    assertFalse(ok1);
    Duration afterFirstFail = reporter.currentInterval();
    assertTrue(
        afterFirstFail.toMillis() > 60_000,
        "expected backoff > 60000 ms after failure, got " + afterFirstFail);

    summaries.push(stat("x", "v"));
    boolean ok2 = reporter.flushAndApplyBackoff();
    assertFalse(ok2);
    Duration afterSecondFail = reporter.currentInterval();
    assertTrue(
        afterSecondFail.toMillis() > afterFirstFail.toMillis(),
        "backoff should grow further on consecutive failures");

    // Now sender succeeds — interval should reset to base
    summaries.push(stat("x", "v"));
    boolean ok3 = reporter.flushAndApplyBackoff();
    assertTrue(ok3);
    assertEquals(Duration.ofMillis(60_000), reporter.currentInterval());
    assertEquals(1, sender.sent.size());
  }

  @Test
  void backoffCappedAtMax() throws IOException {
    EvaluationSummaryCollector summaries = new EvaluationSummaryCollector(true);
    ContextShapeCollector shapes = new ContextShapeCollector(ContextUploadMode.PERIODIC_EXAMPLE);
    ExampleContextCollector examples =
        new ExampleContextCollector(ContextUploadMode.PERIODIC_EXAMPLE);

    CapturingSender sender = new CapturingSender();
    sender.failuresLeft.set(50); // many failures

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

    for (int i = 0; i < 50; i++) {
      summaries.push(stat("x", "v"));
      reporter.flushAndApplyBackoff();
    }

    assertTrue(reporter.currentInterval().toMillis() <= 600_000);
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
