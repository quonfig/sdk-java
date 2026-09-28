package com.quonfig.sdk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.quonfig.sdk.ApiUrlsFailoverWarnTest.RecordingLogger;
import com.quonfig.sdk.eval.ContextSet;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Weighted rollout whose {@code hashByPropertyName} is missing from the context (qfg-9dxb.8).
 *
 * <p>The config mirrors integration-test-data {@code feature-flag.weighted}: hashes on {@code
 * user.tracking_id}; weights value 1 at 1000, value 3 at 2000, value 2 at 97000. The first weighted
 * variant is value 1.
 *
 * <p>Contract: a missing hash property serves bucket 0 (the first variant) with reason SPLIT, flags
 * {@code hashPropertyMissing: true} in metadata, and WARNs once per config key per client. A
 * property that is present (including null or empty string) is hashed as before and is not reported
 * as missing.
 */
class WeightedHashPropertyMissingTest {

  private static final String KEY = "feature-flag.weighted";
  private static final String WARN_FRAGMENT = "hashes on \"{}\" which is missing from context";

  @TempDir Path workspaceDir;

  private RecordingLogger logger;

  @BeforeEach
  void writeWorkspace() throws Exception {
    Files.writeString(
        workspaceDir.resolve("quonfig.json"),
        "{\"workspace\":\"test-ws\",\"environments\":[\"production\"]}");
    Files.createDirectories(workspaceDir.resolve("configs"));
    Files.createDirectories(workspaceDir.resolve("feature-flags"));
    Files.createDirectories(workspaceDir.resolve("segments"));
    Files.writeString(
        workspaceDir.resolve("feature-flags").resolve(KEY + ".json"),
        "{\"id\":\"16838163869852699\",\"key\":\""
            + KEY
            + "\",\"type\":\"feature_flag\",\"valueType\":\"int\","
            + "\"default\":{\"rules\":[{\"criteria\":[{\"operator\":\"ALWAYS_TRUE\"}],"
            + "\"value\":{\"type\":\"weighted_values\",\"value\":{\"weightedValues\":["
            + "{\"weight\":1000,\"value\":{\"type\":\"int\",\"value\":\"1\"}},"
            + "{\"weight\":2000,\"value\":{\"type\":\"int\",\"value\":\"3\"}},"
            + "{\"weight\":97000,\"value\":{\"type\":\"int\",\"value\":\"2\"}}],"
            + "\"hashByPropertyName\":\"user.tracking_id\"}}}]}}");
    logger = new RecordingLogger();
  }

  private Quonfig newClient() {
    return new Quonfig(
        Options.builder()
            .datadir(workspaceDir.toString())
            .environment("production")
            .logger(logger)
            .envLookup(k -> Optional.empty())
            .enableQuonfigUserContext(false)
            .disableTelemetry(true)
            .build());
  }

  private static ContextSet userWithTrackingId(Object trackingId) {
    Map<String, Object> user = new HashMap<>();
    user.put("tracking_id", trackingId);
    return new ContextSet().withNamedContext("user", user);
  }

  private static void assertSplit(EvaluationDetails<Long> d, long value, int index) {
    assertEquals(value, d.value(), "value: " + d);
    assertEquals(Reason.SPLIT, d.reason(), "reason: " + d);
    assertEquals(index, d.variantIndex(), "variantIndex: " + d);
    assertEquals("split:" + index, d.variant(), "variant: " + d);
    assertEquals(index, d.metadata().get("weightedValueIndex"), "metadata: " + d);
  }

  private long warnCount() {
    return logger.entries.stream()
        .filter(e -> e.level == org.slf4j.event.Level.WARN)
        .filter(e -> e.format.contains(WARN_FRAGMENT))
        .count();
  }

  // ---- Characterization: evaluation results (identical before and after qfg-9dxb.8) ----

  @Test
  void noContextAtAll_servesFirstVariant() {
    try (Quonfig q = newClient()) {
      assertSplit(q.getIntDetails(KEY, 0L), 1L, 0);
      assertEquals(1L, q.getInt(KEY, 0L));
    }
  }

  @Test
  void namedContextMissing_servesFirstVariant() {
    try (Quonfig q = newClient()) {
      ContextSet ctx = new ContextSet().withNamedContext("team", Map.of("tracking_id", "user-17"));
      assertSplit(q.getIntDetails(KEY, 0L, ctx), 1L, 0);
    }
  }

  @Test
  void propertyMissingFromPresentContext_servesFirstVariant() {
    try (Quonfig q = newClient()) {
      ContextSet ctx = new ContextSet().withNamedContext("user", Map.of("key", "u-1"));
      assertSplit(q.getIntDetails(KEY, 0L, ctx), 1L, 0);
    }
  }

  /**
   * A present-but-null property is NOT treated as missing: it is hashed as the string "null" (the
   * same bucket as tracking_id "null"), which lands in bucket 2 (value 2) for this key.
   */
  @Test
  void propertyPresentButNull_isHashedAsStringNull() {
    try (Quonfig q = newClient()) {
      assertSplit(q.getIntDetails(KEY, 0L, userWithTrackingId(null)), 2L, 2);
      assertSplit(q.getIntDetails(KEY, 0L, userWithTrackingId("null")), 2L, 2);
    }
  }

  /** A present-but-empty property is hashed as-is (configKey + ""), landing in bucket 2. */
  @Test
  void propertyPresentButEmpty_isHashed() {
    try (Quonfig q = newClient()) {
      assertSplit(q.getIntDetails(KEY, 0L, userWithTrackingId("")), 2L, 2);
    }
  }

  /** Present property: known (tracking_id -> variant) pins. Verified against released v1.3.0. */
  @Test
  void propertyPresent_bucketsAreUnchanged() {
    try (Quonfig q = newClient()) {
      assertSplit(q.getIntDetails(KEY, 0L, userWithTrackingId("user-71")), 1L, 0);
      assertSplit(q.getIntDetails(KEY, 0L, userWithTrackingId("user-185")), 1L, 0);
      assertSplit(q.getIntDetails(KEY, 0L, userWithTrackingId("user-17")), 3L, 1);
      assertSplit(q.getIntDetails(KEY, 0L, userWithTrackingId("user-61")), 3L, 1);
      assertSplit(q.getIntDetails(KEY, 0L, userWithTrackingId("user-0")), 2L, 2);
      assertSplit(q.getIntDetails(KEY, 0L, userWithTrackingId("user-1")), 2L, 2);
    }
  }

  // ---- qfg-9dxb.8: hashPropertyMissing metadata + WARN once per key ----

  @Test
  void missingProperty_setsHashPropertyMissingMetadata() {
    try (Quonfig q = newClient()) {
      assertEquals(true, q.getIntDetails(KEY, 0L).metadata().get("hashPropertyMissing"));
      ContextSet team = new ContextSet().withNamedContext("team", Map.of("id", "t"));
      assertEquals(true, q.getIntDetails(KEY, 0L, team).metadata().get("hashPropertyMissing"));
      ContextSet user = new ContextSet().withNamedContext("user", Map.of("key", "u-1"));
      assertEquals(true, q.getIntDetails(KEY, 0L, user).metadata().get("hashPropertyMissing"));
    }
  }

  @Test
  void presentProperty_doesNotSetHashPropertyMissing() {
    try (Quonfig q = newClient()) {
      for (Object id : new Object[] {"user-17", "user-71", "", null}) {
        EvaluationDetails<Long> d = q.getIntDetails(KEY, 0L, userWithTrackingId(id));
        assertFalse(d.metadata().containsKey("hashPropertyMissing"), "id=" + id + ": " + d);
      }
      assertEquals(0, warnCount(), "present property must not warn: " + logger.entries);
    }
  }

  @Test
  void missingProperty_warnsOncePerKeyPerClient() {
    try (Quonfig q = newClient()) {
      q.getIntDetails(KEY, 0L);
      q.getInt(KEY, 0L);
      q.getIntDetails(KEY, 0L, new ContextSet().withNamedContext("user", Map.of("key", "u-1")));
      assertEquals(1, warnCount(), "saw: " + logger.entries);
      RecordingLogger.Entry e =
          logger.entries.stream().filter(x -> x.format.contains(WARN_FRAGMENT)).findFirst().get();
      assertEquals(
          "quonfig: weighted rollout for \"{}\" hashes on \"{}\" which is missing from context;"
              + " using first variant",
          e.format);
      assertEquals("[" + KEY + ", user.tracking_id]", e.args);
    }
    // A second client has its own bookkeeping and warns again.
    try (Quonfig q2 = newClient()) {
      q2.getInt(KEY, 0L);
      assertEquals(2, warnCount(), "saw: " + logger.entries);
    }
  }
}
