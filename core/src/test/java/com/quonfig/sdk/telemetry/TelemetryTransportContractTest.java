package com.quonfig.sdk.telemetry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.quonfig.sdk.Options;
import com.quonfig.sdk.Quonfig;
import com.quonfig.sdk.eval.ContextSet;
import com.quonfig.sdk.telemetry.TelemetryStub.Step;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.event.Level;

/**
 * Telemetry transport contract T1-T8 (qfg-y8je.9): the sdk-java implementation of
 * integration-test-data/chaos/telemetry-transport-contract.md, mirroring the sdk-node reference
 * (test/telemetry-transport.test.ts).
 *
 * <p>Fixture: a real {@link Quonfig} client (datadir mode, real reporter + queue + JDK HttpClient)
 * pointed at a scriptable {@link TelemetryStub}; a {@link ManualScheduler} (injected through {@code
 * telemetryClock} / {@code telemetryScheduler}) drives the tick cadence, the request deadline and
 * every telemetry time comparison; a {@link CaptureLogger} (the {@code logger} option) records
 * every level. Only the endpoint, the clock and the logger are mocked.
 */
class TelemetryTransportContractTest {

  private static final long MIN = 60_000;
  private static final ObjectMapper JSON = new ObjectMapper();

  @TempDir Path workspaceDir;

  private TelemetryStub stub;
  private CaptureLogger logger;
  private ManualScheduler clock;
  private Quonfig q;
  private TelemetryReporter r;

  @BeforeEach
  void setUp() throws Exception {
    Files.writeString(
        workspaceDir.resolve("quonfig.json"),
        "{\"workspace\":\"test-ws\",\"environments\":[\"production\"]}");
    Files.createDirectories(workspaceDir.resolve("configs"));
    Files.createDirectories(workspaceDir.resolve("feature-flags"));
    Files.createDirectories(workspaceDir.resolve("segments"));
    for (int i = 0; i < 20; i++) {
      String key = cfg(i);
      Files.writeString(
          workspaceDir.resolve("configs").resolve(key + ".json"),
          "{\"id\":\"id-"
              + key
              + "\",\"key\":\""
              + key
              + "\",\"type\":\"config\",\"valueType\":\"string\",\"default\":{\"rules\":"
              + "[{\"criteria\":[],\"value\":{\"type\":\"string\",\"value\":\"v-"
              + key
              + "\"}}]}}");
    }
    stub = new TelemetryStub();
    logger = new CaptureLogger();
    clock = new ManualScheduler();
    clock.onSettle(this::settle);
  }

  @AfterEach
  void tearDown() {
    stub.close();
    if (q != null) q.close();
  }

  private static String cfg(int i) {
    return String.format("cfg-%02d", i % 20);
  }

  private void client() throws Exception {
    client(b -> {});
  }

  private void client(Consumer<Options.Builder> overrides) throws Exception {
    Options.Builder b =
        Options.builder()
            .sdkKey("test-sdk-key")
            .datadir(workspaceDir.toString())
            .environment("production")
            .telemetryUrl(stub.url())
            .logger(logger)
            .telemetryClock(clock.clock())
            .telemetryScheduler(clock);
    overrides.accept(b);
    q = new Quonfig(b.build());
    Field f = Quonfig.class.getDeclaredField("telemetryReporter");
    f.setAccessible(true);
    r = (TelemetryReporter) f.get(q);
    assertNotNull(r, "telemetry reporter must be running");
    logger.clear();
  }

  /**
   * Evaluation set {@code tag}: three evaluations over configs {@code cfgBase..cfgBase+2}, each
   * with a distinct context key {@code tag-i}, so a body identifies its set by example-context key.
   */
  private void record(String tag, int cfgBase) {
    for (int i = 0; i < 3; i++) {
      q.getString(
          cfg(cfgBase + i),
          "fallback",
          new ContextSet().withNamedContext("user", Map.of("key", tag + "-" + i)));
    }
  }

  private boolean has(int i, String tag) {
    return stub.text(i).contains("\"" + tag + "-0\"");
  }

  private boolean hasConfig(int i, String key) {
    return stub.text(i).contains("\"key\":\"" + key + "\"");
  }

  /**
   * Wait (real time) until no telemetry work is running, or the one POST in flight is being held by
   * a hang step at the stub (only a virtual deadline can end it).
   */
  private void settle() {
    if (r == null) return;
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    for (; ; ) {
      TelemetryReporter.DebugState s = r.debugState();
      if (!s.busy()) return;
      int latest = s.postsStarted() - 1;
      if (latest >= 0 && stub.postCount() == s.postsStarted() && stub.isHeld(latest)) return;
      if (System.nanoTime() > deadline) throw new AssertionError("telemetry never settled: " + s);
      try {
        Thread.sleep(1);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
      }
    }
  }

  private void advance(long ms) {
    clock.advance(ms);
  }

  private void advance(long ms, int expectPosts) throws InterruptedException {
    clock.advance(ms);
    stub.waitForPosts(expectPosts);
    settle();
  }

  private TelemetryReporter.DebugState state() {
    return r.debugState();
  }

  // ---------------------------------------------------------------------------------------------
  // T1 - Timeout aborts and retains (P1, P5, P7)

  @Test
  void T1_timeoutAbortsAndRetains() throws Exception {
    client();
    record("A", 0);
    stub.script(Step.hang(), Step.status(200));

    advance(MIN, 1); // tick 1: POST 0 hangs
    advance(15_000); // the request is aborted
    assertEquals(1, stub.postCount());
    assertEquals(1, state().retainedCount());
    assertEquals(0, logger.count(Level.WARN));
    assertEquals(0, logger.count(Level.ERROR));
    assertEquals(1, logger.count(Level.DEBUG, "Telemetry POST failed \\(timeout\\)"));

    advance(45_000, 2); // tick 2, 45s after the failure
    assertEquals(2, stub.postCount());
    assertEquals(stub.sha(0), stub.sha(1));
    assertEquals(0, state().retainedCount());
    assertEquals(1, logger.count(Level.INFO, "(?i)recover"));
    assertEquals(0, logger.count(Level.WARN));
  }

  @Test
  void T1_defaults_timeout15000_connect5000_interval60000() throws Exception {
    client();
    assertEquals(15_000, r.config().timeoutMs());
    assertEquals(5_000, r.config().connectTimeoutMs());
    assertEquals(60_000, r.config().flushIntervalMs());
    Options o = Options.builder().build();
    assertEquals(Duration.ofSeconds(15), o.telemetryTimeout());
    assertEquals(Duration.ofSeconds(5), o.telemetryConnectTimeout());
    assertEquals(Duration.ofSeconds(60), o.telemetryFlushInterval());
    assertEquals(Duration.ofSeconds(60), o.telemetryInitialDelay());
  }

  // ---------------------------------------------------------------------------------------------
  // T2 - 5xx retains verbatim and resends (P4, P5)

  @Test
  void T2_5xxRetainsVerbatimAndResends() throws Exception {
    client();
    record("A", 0);
    stub.script(Step.status(503), Step.status(503), Step.status(200), Step.status(200));

    advance(MIN, 1);
    assertEquals(1, state().retainedCount());

    record("B", 3);
    advance(MIN, 2);
    assertEquals(2, state().retainedCount());

    advance(MIN, 4);
    assertEquals(4, stub.postCount());
    assertEquals(stub.sha(0), stub.sha(1));
    assertEquals(stub.sha(0), stub.sha(2));
    assertTrue(has(3, "B"));
    assertTrue(hasConfig(3, "cfg-03"));
    assertFalse(has(3, "A"));
    assertFalse(hasConfig(3, "cfg-00"));
    assertEquals(0, state().retainedCount());
  }

  // ---------------------------------------------------------------------------------------------
  // T3 - Non-retryable 4xx (P3)

  @ParameterizedTest(name = "T3a {0} disables telemetry for the process")
  @ValueSource(ints = {401, 403, 404})
  void T3a_authStatusDisablesTelemetry(int status) throws Exception {
    client();
    record("A", 0);
    stub.script(Step.status(503), Step.status(status));
    advance(MIN, 1);
    assertEquals(1, state().retainedCount());

    record("B", 3);
    advance(MIN, 2);
    assertEquals(1, logger.count(Level.ERROR, String.valueOf(status)));
    assertFalse(state().enabled());
    assertEquals(0, state().retainedCount());
    assertFalse(state().timerActive());
    assertEquals(0, logger.count(Level.WARN));

    for (int k = 0; k < 3; k++) {
      record("C" + k, 6);
      advance(MIN);
    }
    q.flush();
    assertEquals(2, stub.postCount());
    assertEquals(1, logger.count(Level.ERROR));
  }

  @ParameterizedTest(name = "T3b {0} drops the batch and keeps ticking")
  @ValueSource(ints = {400, 413, 422})
  void T3b_rejectedStatusDropsBatchAndKeepsTicking(int status) throws Exception {
    client();
    record("A", 0);
    stub.script(Step.withBody(status, "bad payload"), Step.status(200));
    advance(MIN, 1);
    assertEquals(0, state().retainedCount());
    assertEquals(1, logger.count(Level.ERROR));
    assertEquals(1, logger.count(Level.ERROR, "bad payload"));
    assertEquals(0, logger.count(Level.WARN));
    assertTrue(state().enabled());

    record("B", 3);
    advance(MIN, 2);
    assertEquals(2, stub.postCount());
    assertNotEquals(stub.sha(0), stub.sha(1));
  }

  @Test
  void T3_408IsRetryable_antiVacuity() throws Exception {
    client();
    record("A", 0);
    stub.script(Step.status(408));
    advance(MIN, 1);
    assertEquals(1, state().retainedCount());
    assertTrue(state().enabled());
    assertEquals(0, logger.count(Level.ERROR));
  }

  // ---------------------------------------------------------------------------------------------
  // T4 - Retry-After and the 30s floor (P4)

  @Test
  void T4a_30sFloorAfterAFailure() throws Exception {
    client(b -> b.telemetryFlushInterval(Duration.ofSeconds(8)));
    record("A", 0);
    stub.script(Step.status(503), Step.status(200));
    advance(8_000, 1); // F = 8s
    for (int k = 0; k < 3; k++) {
      advance(8_000); // F+8, F+16, F+24
      assertEquals(1, stub.postCount());
    }
    advance(8_000, 2); // F+32: first tick at or after F+30
    assertEquals(2, stub.postCount());
    assertEquals(stub.sha(0), stub.sha(1));
  }

  @Test
  void T4b_retryAfterDeltaSecondsHonored() throws Exception {
    client();
    record("A", 0);
    stub.script(Step.status(429, "120"), Step.status(200));
    advance(MIN, 1); // F = 60s
    advance(MIN); // F+60
    advance(59_000); // F+119
    assertEquals(1, stub.postCount());
    advance(1_000); // F+120, the timer tick at 180s
    advance(0, 2);
    assertEquals(2, stub.postCount());
    assertEquals(stub.sha(0), stub.sha(1));
  }

  @Test
  void T4c_retryAfterClampedTo600s_agedBatchDiscardedWithOneWarn() throws Exception {
    client();
    record("A", 0);
    stub.script(Step.status(503, "3600"), Step.status(200));
    advance(MIN, 1); // F = 60s
    record("B", 3);
    for (int k = 0; k < 9; k++) advance(MIN); // up to F+540
    advance(59_000); // F+599
    assertEquals(1, stub.postCount());
    advance(1_000, 2); // F+600 (tick 11 at 660s)
    assertEquals(2, stub.postCount());
    assertTrue(has(1, "B"));
    assertFalse(has(1, "A"));
    assertEquals(1, logger.count(Level.WARN));
    assertEquals(1, logger.count(Level.WARN, "older than 5 min"));
  }

  @Test
  void T4d_retryAfterHttpDateHonored() throws Exception {
    client();
    record("A", 0);
    String at =
        DateTimeFormatter.RFC_1123_DATE_TIME.format(
            Instant.ofEpochMilli(clock.now() + 3 * MIN).atOffset(ZoneOffset.UTC));
    stub.script(Step.status(503, at));
    advance(MIN, 1); // F = 60s, Retry-After = 180s wall clock -> 120s after F
    advance(MIN); // F+60
    assertEquals(1, stub.postCount());
    advance(MIN, 2); // F+120
    assertEquals(stub.sha(0), stub.sha(1));
  }

  // ---------------------------------------------------------------------------------------------
  // T5 - Caps under outage (P5, P6)

  @Test
  void T5_queueCaps_5Batches_oldestEvicted_resentOldestFirst() throws Exception {
    client();
    stub.setDefault(Step.status(503));
    Map<String, String> firstPost = new HashMap<>();
    for (int k = 1; k <= 8; k++) {
      record("E" + k, k);
      advance(MIN, stub.postCount() + 1);
      int i = stub.postCount() - 1;
      for (int s = 1; s <= k; s++) {
        if (has(i, "E" + s)) firstPost.putIfAbsent("E" + s, stub.sha(i));
      }
      assertTrue(state().retainedCount() <= 5);
      assertTrue(state().retainedBytes() <= r.config().maxRetainedBytes());
    }
    assertEquals(5, state().retainedCount());

    stub.setDefault(Step.status(200));
    int start = stub.postCount();
    advance(MIN, start + 5);
    assertEquals(start + 5, stub.postCount());
    String[] order = {"E4", "E5", "E6", "E7", "E8"};
    for (int j = 0; j < order.length; j++) {
      assertTrue(has(start + j, order[j]), "POST " + (start + j) + " carries " + order[j]);
      if (firstPost.containsKey(order[j])) {
        assertEquals(firstPost.get(order[j]), stub.sha(start + j));
      }
    }
    for (int i = start; i < start + 5; i++) assertFalse(has(i, "E1"));
    assertEquals(0, state().retainedCount());
  }

  @Test
  void T5_maxAgeDiscardsBatchesOlderThan5Min() throws Exception {
    client();
    stub.setDefault(Step.status(503));
    for (int k = 1; k <= 3; k++) {
      record("E" + k, k);
      advance(MIN, stub.postCount() + 1);
    }
    for (int k = 0; k < 6; k++) advance(MIN);
    assertEquals(0, state().retainedCount());

    stub.setDefault(Step.status(200));
    int before = stub.postCount();
    advance(MIN);
    for (int i = before; i < stub.postCount(); i++) {
      for (String tag : List.of("E1", "E2", "E3")) assertFalse(has(i, tag));
    }
  }

  @Test
  void T5_oversizeBatchIsDroppedNotRetained() throws Exception {
    client(b -> b.telemetryMaxRetainedBytes(4096));
    for (int k = 0; k < 20; k++) record("X" + k, k);
    stub.script(Step.status(503));
    advance(MIN, 1);
    assertTrue(stub.body(0).length > 4096, "body is " + stub.body(0).length + " bytes");
    assertEquals(0, state().retainedCount());
    assertEquals(0, state().retainedBytes());
    assertEquals(1, logger.count(Level.WARN));
    assertEquals(1, logger.count(Level.WARN, "byte cap"));
  }

  @Test
  void T5_shippedQueueDefaults_5_2097152_300000() throws Exception {
    client();
    assertEquals(5, r.config().maxRetainedBatches());
    assertEquals(2_097_152L, r.config().maxRetainedBytes());
    assertEquals(300_000L, r.config().maxRetainedAgeMs());
  }

  @Test
  void T5_aggregatorDefaults_10000Each() throws Exception {
    client();
    Options o = Options.builder().build();
    assertEquals(10_000, o.telemetryMaxEvaluationSummaries());
    assertEquals(10_000, o.telemetryMaxContextShapeFields());
    assertEquals(10_000, o.telemetryMaxExampleContexts());
    assertEquals(10_000, r.summaries().maxDataSize());
    assertEquals(10_000, r.shapes().maxDataSize());
    assertEquals(10_000, r.examples().maxDataSize());
  }

  private JsonNode event(int i, String name) throws Exception {
    for (JsonNode e : JSON.readTree(stub.body(i)).get("events")) {
      if (e.has(name)) return e.get(name);
    }
    throw new AssertionError("no " + name + " event in POST " + i + ": " + stub.text(i));
  }

  @Test
  void T5_aggregatorCaps_evaluationSummaries_existingKeyKeepsCounting() throws Exception {
    client(b -> b.telemetryMaxEvaluationSummaries(3));
    ContextSet u = new ContextSet().withNamedContext("user", Map.of("key", "u"));
    for (int i = 0; i < 6; i++) q.getString(cfg(i), "fallback", u);
    q.getString(cfg(0), "fallback", u);
    advance(MIN, 1);
    List<String> keys = new ArrayList<>();
    long c0 = -1;
    for (JsonNode s : event(0, "summaries").get("summaries")) {
      keys.add(s.get("key").asText());
      if (s.get("key").asText().equals("cfg-00"))
        c0 = s.get("counters").get(0).get("count").asLong();
    }
    keys.sort(null);
    assertEquals(List.of("cfg-00", "cfg-01", "cfg-02"), keys);
    assertEquals(2, c0);
  }

  @Test
  void T5_aggregatorCaps_contextShapeFields() throws Exception {
    client(b -> b.telemetryMaxContextShapeFields(3));
    q.getString(
        cfg(0),
        "fallback",
        new ContextSet()
            .withNamedContext("user", Map.of("key", "u", "a", 1, "b", 2))
            .withNamedContext("team", Map.of("c", 3, "d", 4)));
    advance(MIN, 1);
    int fields = 0;
    for (JsonNode s : event(0, "contextShapes").get("shapes")) fields += s.get("fieldTypes").size();
    assertEquals(3, fields);
  }

  @Test
  void T5_aggregatorCaps_exampleContexts() throws Exception {
    client(b -> b.telemetryMaxExampleContexts(3));
    for (int i = 0; i < 6; i++) {
      q.getString(
          cfg(0), "fallback", new ContextSet().withNamedContext("user", Map.of("key", "u" + i)));
    }
    advance(MIN, 1);
    assertEquals(3, event(0, "exampleContexts").get("examples").size());
  }

  // ---------------------------------------------------------------------------------------------
  // T6 - Logging episodes (P7)

  @Test
  void T6a_blip_noWarn_oneRecoveryInfo() throws Exception {
    client();
    record("A", 0);
    stub.script(Step.status(503), Step.status(200));
    advance(MIN, 1);
    advance(MIN, 2);
    assertEquals(0, logger.count(Level.WARN));
    assertEquals(1, logger.count(Level.INFO, "(?i)recover"));
    assertTrue(logger.count(Level.DEBUG) >= 1);
    assertEquals(0, logger.count(Level.ERROR));
  }

  @Test
  void T6b_sustained503_oneWarnAtFirstDrop_infoOnRecovery() throws Exception {
    client();
    stub.setDefault(Step.status(503));
    for (int k = 1; k <= 5; k++) {
      record("E" + k, k);
      advance(MIN, stub.postCount() + 1);
    }
    assertEquals(0, logger.count(Level.WARN));
    record("E6", 6);
    advance(MIN, stub.postCount() + 1); // tick 6 evicts E1
    assertEquals(1, logger.count(Level.WARN));
    String warn = logger.first(Level.WARN);
    assertTrue(warn.contains("last POST result: 503"), warn);
    assertTrue(warn.contains("retained queue 5/5 batches"), warn);
    assertTrue(warn.contains("1 batch(es) dropped so far"), warn);
    for (int k = 7; k <= 9; k++) {
      record("E" + k, k);
      advance(MIN, stub.postCount() + 1);
    }
    assertEquals(1, logger.count(Level.WARN));
    stub.setDefault(Step.status(200));
    advance(MIN, stub.postCount() + 1);
    assertEquals(1, logger.count(Level.INFO, "(?i)recover"));
    assertEquals(0, logger.count(Level.ERROR));
  }

  @Test
  void T6c_warnSummaryAtMostOncePer10Min() throws Exception {
    client();
    stub.setDefault(Step.status(503));
    // Tick 6 (360s) is the first drop; the next WARN is due at >= 960s (tick 16).
    for (int k = 1; k <= 15; k++) {
      record("E" + k, k);
      advance(MIN, stub.postCount() + 1);
    }
    assertEquals(1, logger.count(Level.WARN));
    record("E16", 16);
    advance(MIN, stub.postCount() + 1);
    assertEquals(2, logger.count(Level.WARN));
    assertEquals(
        1,
        logger.count(
            Level.WARN, "still dropping data: \\d+ batch\\(es\\) dropped in the last 10 min"));
    for (int k = 17; k <= 25; k++) {
      record("E" + k, k);
      advance(MIN, stub.postCount() + 1);
    }
    assertEquals(2, logger.count(Level.WARN));
    assertEquals(0, logger.count(Level.ERROR));
  }

  // ---------------------------------------------------------------------------------------------
  // T7 - One POST in flight (P2)

  @Test
  void T7_onePostInFlight_skippedWindowsAggregate() throws Exception {
    client();
    record("A", 0);
    stub.script(Step.hang());
    r.tick();
    stub.waitForPosts(1);
    assertTrue(state().busy());

    record("B", 3);
    r.tick();
    record("C", 6);
    r.tick();
    assertEquals(1, stub.postCount());

    stub.release(0, Step.status(200));
    settle();
    r.tick();
    stub.waitForPosts(2);
    settle();
    assertEquals(2, stub.postCount());
    assertTrue(has(1, "B"));
    assertTrue(has(1, "C"));
    assertFalse(has(1, "A"));
  }

  // ---------------------------------------------------------------------------------------------
  // T8 - Shutdown (P8)

  @Test
  void T8_close_5sFinalFlush_retainedQueueNotDrained_nothingLeftRunning() throws Exception {
    client();
    stub.script(Step.status(503), Step.status(503));
    record("A", 0);
    advance(MIN, 1);
    record("B", 3);
    advance(MIN, 2);
    assertEquals(2, state().retainedCount());
    record("C", 6);
    stub.setDefault(Step.hang());

    Quonfig client = q;
    CompletableFuture<Void> closing = CompletableFuture.runAsync(client::close);
    stub.waitForPosts(3);
    Thread.sleep(50);
    assertFalse(closing.isDone(), "close() must wait for the final flush deadline");
    clock.advance(5_000);
    closing.get(2, TimeUnit.SECONDS);
    q = null;

    assertEquals(3, stub.postCount());
    assertTrue(has(2, "C"));
    assertFalse(has(2, "A"));
    assertFalse(has(2, "B"));
    assertNotEquals(stub.sha(0), stub.sha(2));
    assertNotEquals(stub.sha(1), stub.sha(2));

    clock.advance(10 * MIN);
    assertEquals(3, stub.postCount());
    assertFalse(state().busy());
    assertFalse(state().timerActive());
    assertEquals(0, clock.pending());
    client.close(); // second close() is a no-op and does not throw
    assertEquals(3, stub.postCount());
  }
}
