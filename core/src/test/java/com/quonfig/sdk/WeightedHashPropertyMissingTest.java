package com.quonfig.sdk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.quonfig.sdk.ApiUrlsFailoverWarnTest.RecordingLogger;
import com.quonfig.sdk.eval.ContextSet;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicIntegerArray;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Weighted rollout contract (qfg-9dxb.8 revised decision + qfg-t9wo, 2026-09-28).
 *
 * <p>{@code feature-flag.weighted} mirrors integration-test-data: hashes on {@code
 * user.tracking_id}; weights value 1 at 1000, value 3 at 2000, value 2 at 97000.
 *
 * <ul>
 *   <li>Hash property set but missing (no context, named context absent, property absent, or null)
 *       or empty: hash {@code configKey + ""} and walk the weights as normal. Missing and empty
 *       land in the same bucket, which is the bucket released v1.3.0 gave a present {@code ""}.
 *   <li>No hash property configured: a random variant on every evaluation, weighted by weight.
 *   <li>{@code hashPropertyMissing: true} metadata and one WARN per config key per client, only
 *       when the property is missing (null counts as missing; present {@code ""} does not).
 * </ul>
 *
 * <p>This supersedes the 1.3.0 characterization (missing -> first variant, null hashed as the text
 * {@code "null"}, no hash property -> first variant).
 */
class WeightedHashPropertyMissingTest {

  private static final String KEY = "feature-flag.weighted";
  private static final String[] ZERO_FIRST_KEYS = {
    "zero-first.a", "zero-first.b", "zero-first.c", "zero-first.d", "zero-first.e"
  };
  private static final String NO_HASH_KEY = "coin.no-hash";
  private static final String EMPTY_HASH_KEY = "coin.empty-hash";
  private static final String WARN_FRAGMENT = "hashes on \"{}\" which is missing from context";

  @TempDir Path workspaceDir;

  private RecordingLogger logger;

  private void writeFlag(String key, String weightedValues, String hashProperty) throws Exception {
    String hash = hashProperty == null ? "" : ",\"hashByPropertyName\":\"" + hashProperty + "\"";
    Files.writeString(
        workspaceDir.resolve("feature-flags").resolve(key + ".json"),
        "{\"id\":\"id-"
            + key
            + "\",\"key\":\""
            + key
            + "\",\"type\":\"feature_flag\",\"valueType\":\"int\","
            + "\"default\":{\"rules\":[{\"criteria\":[{\"operator\":\"ALWAYS_TRUE\"}],"
            + "\"value\":{\"type\":\"weighted_values\",\"value\":{\"weightedValues\":["
            + weightedValues
            + "]"
            + hash
            + "}}}]}}");
  }

  private static String wv(long weight, int value) {
    return "{\"weight\":" + weight + ",\"value\":{\"type\":\"int\",\"value\":\"" + value + "\"}}";
  }

  @BeforeEach
  void writeWorkspace() throws Exception {
    Files.writeString(
        workspaceDir.resolve("quonfig.json"),
        "{\"workspace\":\"test-ws\",\"environments\":[\"production\"]}");
    Files.createDirectories(workspaceDir.resolve("configs"));
    Files.createDirectories(workspaceDir.resolve("feature-flags"));
    Files.createDirectories(workspaceDir.resolve("segments"));
    writeFlag(KEY, wv(1000, 1) + "," + wv(2000, 3) + "," + wv(97000, 2), "user.tracking_id");
    for (String k : ZERO_FIRST_KEYS) {
      writeFlag(k, wv(0, 0) + "," + wv(50000, 1) + "," + wv(50000, 2), "user.tracking_id");
    }
    writeFlag(NO_HASH_KEY, wv(50000, 1) + "," + wv(50000, 2), null);
    writeFlag(EMPTY_HASH_KEY, wv(50000, 1) + "," + wv(50000, 2), "");
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

  /** Every shape in which the hash property counts as missing (null included). */
  private static List<ContextSet> missingShapes() {
    List<ContextSet> shapes = new ArrayList<>();
    shapes.add(null); // no context at all
    shapes.add(new ContextSet().withNamedContext("team", Map.of("tracking_id", "user-17")));
    shapes.add(new ContextSet().withNamedContext("user", Map.of("key", "u-1")));
    shapes.add(userWithTrackingId(null));
    return shapes;
  }

  private static EvaluationDetails<Long> eval(Quonfig q, String key, ContextSet ctx) {
    return ctx == null ? q.getIntDetails(key, -1L) : q.getIntDetails(key, -1L, ctx);
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

  // ---- Case A: hash property set, value missing or empty -> hash configKey + "" ----

  /** Released v1.3.0 served value 2 (index 2) for a present "" on this key. */
  @Test
  void presentEmpty_bucketUnchangedFromV130() {
    try (Quonfig q = newClient()) {
      assertSplit(q.getIntDetails(KEY, 0L, userWithTrackingId("")), 2L, 2);
    }
  }

  @Test
  void missingShapes_hashEmptyValue_sameVariantAsPresentEmpty() {
    try (Quonfig q = newClient()) {
      for (ContextSet ctx : missingShapes()) {
        assertSplit(eval(q, KEY, ctx), 2L, 2);
      }
      assertEquals(2L, q.getInt(KEY, 0L));
    }
  }

  @Test
  void zeroWeightFirstVariant_isNeverServedWhenPropertyMissing() {
    try (Quonfig q = newClient()) {
      for (String k : ZERO_FIRST_KEYS) {
        EvaluationDetails<Long> empty = eval(q, k, userWithTrackingId(""));
        assertNotEquals(0, empty.variantIndex(), k + ": " + empty);
        for (ContextSet ctx : missingShapes()) {
          EvaluationDetails<Long> d = eval(q, k, ctx);
          assertNotEquals(0L, d.value(), k + ": " + d);
          assertNotEquals(0, d.variantIndex(), k + ": " + d);
          assertEquals(empty.value(), d.value(), k + " missing == present empty: " + d);
        }
      }
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
      assertSplit(q.getIntDetails(KEY, 0L, userWithTrackingId("null")), 2L, 2);
    }
  }

  // ---- Case B: no hash property -> random variant per evaluation, weighted ----

  private static int[] countValues(Quonfig q, String key, ContextSet ctx, int n) {
    int[] counts = new int[3];
    for (int i = 0; i < n; i++) {
      EvaluationDetails<Long> d = eval(q, key, ctx);
      assertEquals(Reason.SPLIT, d.reason(), "reason: " + d);
      counts[d.value().intValue()]++;
    }
    return counts;
  }

  @Test
  void noHashProperty_picksRandomVariantEachEvaluation() {
    try (Quonfig q = newClient()) {
      for (String k : new String[] {NO_HASH_KEY, EMPTY_HASH_KEY}) {
        for (ContextSet ctx : new ContextSet[] {null, userWithTrackingId("user-17")}) {
          int[] c = countValues(q, k, ctx, 1000);
          assertEquals(0, c[0], k);
          assertTrue(
              c[1] > 300 && c[2] > 300, k + " ctx=" + ctx + " counts 1=" + c[1] + " 2=" + c[2]);
        }
      }
    }
  }

  @Test
  void noHashProperty_randomAcrossThreads() throws Exception {
    AtomicIntegerArray counts = new AtomicIntegerArray(3);
    ExecutorService pool = Executors.newFixedThreadPool(8);
    try (Quonfig q = newClient()) {
      List<Future<?>> futures = new ArrayList<>();
      for (int t = 0; t < 8; t++) {
        futures.add(
            pool.submit(
                () -> {
                  for (int i = 0; i < 250; i++) {
                    counts.incrementAndGet(q.getInt(NO_HASH_KEY, 0L).intValue());
                  }
                }));
      }
      for (Future<?> f : futures) f.get(30, TimeUnit.SECONDS);
    } finally {
      pool.shutdownNow();
    }
    assertEquals(0, counts.get(0));
    assertEquals(2000, counts.get(1) + counts.get(2));
    assertTrue(counts.get(1) > 600 && counts.get(2) > 600, "counts: " + counts);
  }

  @Test
  void noHashProperty_noMetadataNoWarn() {
    try (Quonfig q = newClient()) {
      for (String k : new String[] {NO_HASH_KEY, EMPTY_HASH_KEY}) {
        EvaluationDetails<Long> d = q.getIntDetails(k, 0L);
        assertFalse(d.metadata().containsKey("hashPropertyMissing"), k + ": " + d);
      }
      assertEquals(0, warnCount(), "saw: " + logger.entries);
    }
  }

  // ---- hashPropertyMissing metadata + WARN once per key ----

  @Test
  void missingProperty_setsHashPropertyMissingMetadata() {
    try (Quonfig q = newClient()) {
      for (ContextSet ctx : missingShapes()) {
        EvaluationDetails<Long> d = eval(q, KEY, ctx);
        assertEquals(true, d.metadata().get("hashPropertyMissing"), "ctx=" + ctx + ": " + d);
      }
    }
  }

  @Test
  void presentProperty_doesNotSetHashPropertyMissing() {
    try (Quonfig q = newClient()) {
      for (Object id : new Object[] {"user-17", "user-71", ""}) {
        EvaluationDetails<Long> d = q.getIntDetails(KEY, 0L, userWithTrackingId(id));
        assertFalse(d.metadata().containsKey("hashPropertyMissing"), "id=" + id + ": " + d);
      }
      assertEquals(0, warnCount(), "present property must not warn: " + logger.entries);
    }
  }

  @Test
  void missingProperty_warnsOncePerKeyPerClient() {
    try (Quonfig q = newClient()) {
      for (ContextSet ctx : missingShapes()) {
        eval(q, KEY, ctx);
        eval(q, KEY, ctx);
      }
      q.getInt(KEY, 0L);
      assertEquals(1, warnCount(), "saw: " + logger.entries);
      RecordingLogger.Entry e =
          logger.entries.stream().filter(x -> x.format.contains(WARN_FRAGMENT)).findFirst().get();
      assertEquals(
          "quonfig: weighted rollout for \"{}\" hashes on \"{}\" which is missing from context;"
              + " hashing an empty value instead",
          e.format);
      assertEquals("[" + KEY + ", user.tracking_id]", e.args);

      // A different key warns once on its own.
      q.getInt(ZERO_FIRST_KEYS[0], 0L);
      q.getInt(ZERO_FIRST_KEYS[0], 0L);
      assertEquals(2, warnCount(), "saw: " + logger.entries);
    }
    // A second client has its own bookkeeping and warns again.
    try (Quonfig q2 = newClient()) {
      q2.getInt(KEY, 0L);
      assertEquals(3, warnCount(), "saw: " + logger.entries);
    }
  }
}
