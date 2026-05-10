package com.quonfig.sdk.telemetry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.quonfig.sdk.Options;
import com.quonfig.sdk.Quonfig;
import com.quonfig.sdk.eval.ContextSet;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class QuonfigTelemetryIntegrationTest {

  @TempDir Path workspaceDir;

  static final class CapturingSender implements TelemetrySender {
    final List<Map<String, Object>> sent = new ArrayList<>();

    @Override
    public synchronized void send(Map<String, Object> payload) {
      sent.add(payload);
    }
  }

  @BeforeEach
  void writeWorkspaceManifest() throws Exception {
    Files.writeString(
        workspaceDir.resolve("quonfig.json"),
        "{\"workspace\":\"test-ws\",\"environments\":[\"production\"]}");
    Files.createDirectories(workspaceDir.resolve("configs"));
    Files.createDirectories(workspaceDir.resolve("feature-flags"));
    Files.createDirectories(workspaceDir.resolve("segments"));
  }

  private void writeConfig(String subdir, String key, String json) throws Exception {
    Files.writeString(workspaceDir.resolve(subdir).resolve(key + ".json"), json);
  }

  private Quonfig newClient(CapturingSender sender) {
    return new Quonfig(
        Options.builder()
            .datadir(workspaceDir.toString())
            .environment("production")
            .telemetrySender(sender)
            .telemetryInitialDelay(
                Duration.ofSeconds(60)) // long enough that we drive flush manually
            .telemetryFlushInterval(Duration.ofSeconds(60))
            .telemetryMaxInterval(Duration.ofSeconds(600))
            .build());
  }

  @Test
  void hundredEvaluationsCollapseToOneSummaryRowWithCount100() throws Exception {
    writeConfig(
        "configs",
        "greeting",
        "{\"id\":\"cfg-1\",\"key\":\"greeting\",\"type\":\"config\",\"valueType\":\"string\","
            + "\"default\":{\"rules\":[{\"criteria\":[],\"value\":{\"type\":\"string\",\"value\":\"hello\"}}]}}");

    CapturingSender sender = new CapturingSender();
    try (Quonfig q = newClient(sender)) {
      for (int i = 0; i < 100; i++) {
        q.getString("greeting", "fallback");
      }
      q.flush();
    }

    assertFalse(sender.sent.isEmpty());
    Map<String, Object> envelope = sender.sent.get(0);
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> events = (List<Map<String, Object>>) envelope.get("events");
    Map<String, Object> summariesEvent = null;
    for (Map<String, Object> e : events) {
      if (e.containsKey("summaries")) summariesEvent = e;
    }
    assertNotNull(summariesEvent);
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> rows =
        (List<Map<String, Object>>)
            ((Map<String, Object>) summariesEvent.get("summaries")).get("summaries");
    assertEquals(1, rows.size());
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> counters = (List<Map<String, Object>>) rows.get(0).get("counters");
    assertEquals(1, counters.size());
    assertEquals(100L, ((Number) counters.get(0).get("count")).longValue());
  }

  @Test
  void closeFlushesPendingTelemetryBeforeStopping() throws Exception {
    writeConfig(
        "configs",
        "greeting",
        "{\"id\":\"cfg-1\",\"key\":\"greeting\",\"type\":\"config\",\"valueType\":\"string\","
            + "\"default\":{\"rules\":[{\"criteria\":[],\"value\":{\"type\":\"string\",\"value\":\"hello\"}}]}}");

    CapturingSender sender = new CapturingSender();
    try (Quonfig q = newClient(sender)) {
      q.getString("greeting", "fallback");
      // No explicit flush — close() should flush.
    }
    assertEquals(1, sender.sent.size());
  }

  @Test
  void confidentialPlaintextNeverAppearsInPayload() throws Exception {
    // A plain confidential string config: confidential=true, no decryptWith. The Resolver
    // returns the value as-is, but Resolver.reportableValueFor() returns "*****<5-hex>" and
    // the summary collector wires that into the wire payload instead of the plaintext.
    writeConfig(
        "configs",
        "secret",
        "{\"id\":\"cfg-secret\",\"key\":\"secret\",\"type\":\"config\",\"valueType\":\"string\","
            + "\"default\":{\"rules\":[{\"criteria\":[],"
            + "\"value\":{\"type\":\"string\",\"value\":\"super-secret-plaintext\","
            + "\"confidential\":true}}]}}");

    CapturingSender sender = new CapturingSender();
    try (Quonfig q = newClient(sender)) {
      q.getString("secret", "fallback");
      q.flush();
    }

    String wire = sender.sent.toString();
    assertFalse(wire.contains("super-secret-plaintext"), "plaintext leaked: " + wire);
    assertTrue(wire.contains("*****"), "expected redacted prefix in payload: " + wire);
  }

  @Test
  void contextShapeAndExampleAreCollectedFromCallContext() throws Exception {
    writeConfig(
        "configs",
        "greeting",
        "{\"id\":\"cfg-1\",\"key\":\"greeting\",\"type\":\"config\",\"valueType\":\"string\","
            + "\"default\":{\"rules\":[{\"criteria\":[],\"value\":{\"type\":\"string\",\"value\":\"hello\"}}]}}");

    CapturingSender sender = new CapturingSender();
    try (Quonfig q = newClient(sender)) {
      q.getString(
          "greeting",
          "fallback",
          new ContextSet()
              .withNamedContext("user", Map.of("key", "u-1", "plan", "pro", "age", 38)));
      q.flush();
    }

    Map<String, Object> envelope = sender.sent.get(0);
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> events = (List<Map<String, Object>>) envelope.get("events");

    Map<String, Object> shapesEvent = null;
    Map<String, Object> examplesEvent = null;
    for (Map<String, Object> e : events) {
      if (e.containsKey("contextShapes")) shapesEvent = e;
      if (e.containsKey("exampleContexts")) examplesEvent = e;
    }
    assertNotNull(shapesEvent);
    assertNotNull(examplesEvent);

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> shapes =
        (List<Map<String, Object>>)
            ((Map<String, Object>) shapesEvent.get("contextShapes")).get("shapes");
    assertEquals(1, shapes.size());
    assertEquals("user", shapes.get(0).get("name"));

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> examples =
        (List<Map<String, Object>>)
            ((Map<String, Object>) examplesEvent.get("exampleContexts")).get("examples");
    assertEquals(1, examples.size());
  }

  @Test
  void disabledTelemetryDoesNotSendOrPushAnything() throws Exception {
    writeConfig(
        "configs",
        "greeting",
        "{\"id\":\"cfg-1\",\"key\":\"greeting\",\"type\":\"config\",\"valueType\":\"string\","
            + "\"default\":{\"rules\":[{\"criteria\":[],\"value\":{\"type\":\"string\",\"value\":\"hello\"}}]}}");

    CapturingSender sender = new CapturingSender();
    try (Quonfig q =
        new Quonfig(
            Options.builder()
                .datadir(workspaceDir.toString())
                .environment("production")
                .telemetrySender(sender)
                .disableTelemetry(true)
                .build())) {
      q.getString("greeting", "fallback");
      q.flush();
    }
    assertTrue(sender.sent.isEmpty());
  }

  @Test
  void noTelemetrySenderAndNoSdkKey_telemetryIsAQuietNoop() throws Exception {
    writeConfig(
        "configs",
        "greeting",
        "{\"id\":\"cfg-1\",\"key\":\"greeting\",\"type\":\"config\",\"valueType\":\"string\","
            + "\"default\":{\"rules\":[{\"criteria\":[],\"value\":{\"type\":\"string\",\"value\":\"hello\"}}]}}");

    try (Quonfig q =
        new Quonfig(
            Options.builder().datadir(workspaceDir.toString()).environment("production").build())) {
      // No sender, no sdk key → no reporter started; flush() must not throw.
      q.getString("greeting", "fallback");
      q.flush();
    }
  }

  @Test
  void getterStillReturnsValueIfSenderThrows() throws Exception {
    writeConfig(
        "configs",
        "greeting",
        "{\"id\":\"cfg-1\",\"key\":\"greeting\",\"type\":\"config\",\"valueType\":\"string\","
            + "\"default\":{\"rules\":[{\"criteria\":[],\"value\":{\"type\":\"string\",\"value\":\"hello\"}}]}}");

    TelemetrySender broken =
        payload -> {
          throw new IOException("boom");
        };
    Quonfig q =
        new Quonfig(
            Options.builder()
                .datadir(workspaceDir.toString())
                .environment("production")
                .telemetrySender(broken)
                .telemetryInitialDelay(Duration.ofSeconds(60))
                .telemetryFlushInterval(Duration.ofSeconds(60))
                .telemetryMaxInterval(Duration.ofSeconds(600))
                .build());
    try {
      assertEquals("hello", q.getString("greeting", "fallback"));
    } finally {
      try {
        q.close();
      } catch (RuntimeException ignored) {
        // close() may swallow internally; we don't care which path it takes.
      }
    }
  }
}
