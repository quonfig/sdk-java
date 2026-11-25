// AUTO-GENERATED from integration-test-data/tests/eval/get_weighted_values.yaml. DO NOT EDIT.
// Regenerate with:
//   cd integration-test-data/generators && npm run generate -- --target=java
// Source: integration-test-data/generators/src/targets/java.ts

package com.quonfig.sdk.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.quonfig.sdk.Quonfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class GetWeightedValuesTest {

  @Test
  @DisplayName("weighted value is consistent 1")
  void weightedValueIsConsistent1() throws Exception {
    Quonfig client = TestSetup.client();
    assertEquals(
        1L,
        client.getLong(
            "feature-flag.weighted",
            null,
            TestSetup.ctx(TestSetup.map("user", TestSetup.map("tracking_id", "a72c15f5")))));
  }

  @Test
  @DisplayName("weighted value is consistent 2")
  void weightedValueIsConsistent2() throws Exception {
    Quonfig client = TestSetup.client();
    assertEquals(
        2L,
        client.getLong(
            "feature-flag.weighted",
            null,
            TestSetup.ctx(TestSetup.map("user", TestSetup.map("tracking_id", "92a202f2")))));
  }

  @Test
  @DisplayName("weighted value is consistent 3")
  void weightedValueIsConsistent3() throws Exception {
    Quonfig client = TestSetup.client();
    assertEquals(
        3L,
        client.getLong(
            "feature-flag.weighted",
            null,
            TestSetup.ctx(TestSetup.map("user", TestSetup.map("tracking_id", "8f414100")))));
  }

  @Test
  @DisplayName("even split ones serves first variant at low hash fraction")
  void evenSplitOnesServesFirstVariantAtLowHashFraction() throws Exception {
    Quonfig client = TestSetup.client();
    assertEquals(
        "a",
        client.getString(
            "feature-flag.weighted.even-split-ones",
            null,
            TestSetup.ctx(TestSetup.map("user", TestSetup.map("tracking_id", "b7ff78c8")))));
  }

  @Test
  @DisplayName("even split ones serves first variant at low hash fraction 2")
  void evenSplitOnesServesFirstVariantAtLowHashFraction2() throws Exception {
    Quonfig client = TestSetup.client();
    assertEquals(
        "a",
        client.getString(
            "feature-flag.weighted.even-split-ones",
            null,
            TestSetup.ctx(TestSetup.map("user", TestSetup.map("tracking_id", "289f4748")))));
  }

  @Test
  @DisplayName("even split ones serves second variant at high hash fraction")
  void evenSplitOnesServesSecondVariantAtHighHashFraction() throws Exception {
    Quonfig client = TestSetup.client();
    assertEquals(
        "b",
        client.getString(
            "feature-flag.weighted.even-split-ones",
            null,
            TestSetup.ctx(TestSetup.map("user", TestSetup.map("tracking_id", "d60b2cb6")))));
  }

  @Test
  @DisplayName("even split ones serves second variant at high hash fraction 2")
  void evenSplitOnesServesSecondVariantAtHighHashFraction2() throws Exception {
    Quonfig client = TestSetup.client();
    assertEquals(
        "b",
        client.getString(
            "feature-flag.weighted.even-split-ones",
            null,
            TestSetup.ctx(TestSetup.map("user", TestSetup.map("tracking_id", "21bcfd13")))));
  }

  @Test
  @DisplayName("non-ascii tracking_id emoji hashes utf-8 bytes")
  void nonAsciiTrackingIdEmojiHashesUtf8Bytes() throws Exception {
    Quonfig client = TestSetup.client();
    assertEquals(
        "a",
        client.getString(
            "feature-flag.weighted.even-split-ones",
            null,
            TestSetup.ctx(
                TestSetup.map("user", TestSetup.map("tracking_id", "\ud83d\ude80-rocket")))));
  }

  @Test
  @DisplayName("non-ascii tracking_id latin hashes utf-8 bytes")
  void nonAsciiTrackingIdLatinHashesUtf8Bytes() throws Exception {
    Quonfig client = TestSetup.client();
    assertEquals(
        "a",
        client.getString(
            "feature-flag.weighted.even-split-ones",
            null,
            TestSetup.ctx(TestSetup.map("user", TestSetup.map("tracking_id", "münchen-7")))));
  }

  @Test
  @DisplayName("non-ascii tracking_id cjk hashes utf-8 bytes")
  void nonAsciiTrackingIdCjkHashesUtf8Bytes() throws Exception {
    Quonfig client = TestSetup.client();
    assertEquals(
        "b",
        client.getString(
            "feature-flag.weighted.even-split-ones",
            null,
            TestSetup.ctx(TestSetup.map("user", TestSetup.map("tracking_id", "ユーザー1")))));
  }

  @Test
  @DisplayName("non-standard sum still serves normalized true bucket")
  void nonStandardSumStillServesNormalizedTrueBucket() throws Exception {
    Quonfig client = TestSetup.client();
    assertEquals(
        true,
        client.getBool(
            "feature-flag.weighted.non-standard",
            null,
            TestSetup.ctx(TestSetup.map("user", TestSetup.map("tracking_id", "ff8adf17")))));
  }

  @Test
  @DisplayName("non-standard sum still serves normalized true bucket 2")
  void nonStandardSumStillServesNormalizedTrueBucket2() throws Exception {
    Quonfig client = TestSetup.client();
    assertEquals(
        true,
        client.getBool(
            "feature-flag.weighted.non-standard",
            null,
            TestSetup.ctx(TestSetup.map("user", TestSetup.map("tracking_id", "36ef1a7a")))));
  }

  @Test
  @DisplayName("non-standard sum still serves normalized false bucket")
  void nonStandardSumStillServesNormalizedFalseBucket() throws Exception {
    Quonfig client = TestSetup.client();
    assertEquals(
        false,
        client.getBool(
            "feature-flag.weighted.non-standard",
            null,
            TestSetup.ctx(TestSetup.map("user", TestSetup.map("tracking_id", "f667c76a")))));
  }

  @Test
  @DisplayName("non-standard sum still serves normalized false bucket 2")
  void nonStandardSumStillServesNormalizedFalseBucket2() throws Exception {
    Quonfig client = TestSetup.client();
    assertEquals(
        false,
        client.getBool(
            "feature-flag.weighted.non-standard",
            null,
            TestSetup.ctx(TestSetup.map("user", TestSetup.map("tracking_id", "7467ca21")))));
  }

  @Test
  @DisplayName("weighted value with hash property missing from context hashes empty string")
  void weightedValueWithHashPropertyMissingFromContextHashesEmptyString() throws Exception {
    Quonfig client = TestSetup.client();
    assertEquals(
        2L,
        client.getLong(
            "feature-flag.weighted.missing-hash",
            null,
            TestSetup.ctx(TestSetup.map("user", TestSetup.map("key", "no-tracking-id-user")))));
  }

  @Test
  @DisplayName("weighted value with no context hashes empty string")
  void weightedValueWithNoContextHashesEmptyString() throws Exception {
    Quonfig client = TestSetup.client();
    assertEquals(2L, client.getLong("feature-flag.weighted.missing-hash", null));
  }

  @Test
  @DisplayName("weighted value with hash property empty string hashes empty string")
  void weightedValueWithHashPropertyEmptyStringHashesEmptyString() throws Exception {
    Quonfig client = TestSetup.client();
    assertEquals(
        2L,
        client.getLong(
            "feature-flag.weighted.missing-hash",
            null,
            TestSetup.ctx(
                TestSetup.map(
                    "user", TestSetup.map("key", "empty-tracking-id-user", "tracking_id", "")))));
  }

  @Test
  @DisplayName(
      "weighted value with zero-weight first variant and hash property missing never serves zero-weight variant")
  void weightedValueWithZeroWeightFirstVariantAndHashPropertyMissingNeverServesZeroWeightVariant()
      throws Exception {
    Quonfig client = TestSetup.client();
    assertEquals(
        2L,
        client.getLong(
            "feature-flag.weighted.zero-first",
            null,
            TestSetup.ctx(TestSetup.map("user", TestSetup.map("key", "no-tracking-id-user")))));
  }

  @Test
  @DisplayName("weighted value with no hash property is random on every evaluation")
  void weightedValueWithNoHashPropertyIsRandomOnEveryEvaluation() throws Exception {
    Quonfig client = TestSetup.client();
    java.util.Set<Object> seen = new java.util.HashSet<>();
    for (int i = 0; i < 200; i++) {
      seen.add(client.getLong("feature-flag.weighted.no-hash", null));
    }
    assertEquals(java.util.Set.of(1L, 2L), seen, "values seen over 200 evaluations");
  }

  @Test
  @DisplayName("weighted value with no hash property is random on every evaluation with context")
  void weightedValueWithNoHashPropertyIsRandomOnEveryEvaluationWithContext() throws Exception {
    Quonfig client = TestSetup.client();
    java.util.Set<Object> seen = new java.util.HashSet<>();
    for (int i = 0; i < 200; i++) {
      seen.add(
          client.getLong(
              "feature-flag.weighted.no-hash",
              null,
              TestSetup.ctx(
                  TestSetup.map(
                      "user",
                      TestSetup.map(
                          "key", "same-user-every-time", "tracking_id", "same-tracking-id")))));
    }
    assertEquals(java.util.Set.of(1L, 2L), seen, "values seen over 200 evaluations");
  }

  @Test
  @DisplayName("weighted json variant returns the json object")
  void weightedJsonVariantReturnsTheJsonObject() throws Exception {
    Quonfig client = TestSetup.client();
    assertEquals(
        TestSetup.map("variant", "json", "n", 1L),
        client.getJson(
            "weighted.variant.json",
            null,
            TestSetup.ctx(TestSetup.map("user", TestSetup.map("tracking_id", "92a202f2")))));
  }

  @Test
  @DisplayName("weighted duration variant returns the duration")
  void weightedDurationVariantReturnsTheDuration() throws Exception {
    Quonfig client = TestSetup.client();
    java.time.Duration actual =
        client.getDuration(
            "weighted.variant.duration",
            null,
            TestSetup.ctx(TestSetup.map("user", TestSetup.map("tracking_id", "92a202f2"))));
    assertNotNull(actual, "getDuration returned null");
    assertEquals(90000L, actual.toMillis());
    assertEquals(
        90000L,
        client
            .getDurationDetails(
                "weighted.variant.duration",
                null,
                TestSetup.ctx(TestSetup.map("user", TestSetup.map("tracking_id", "92a202f2"))))
            .value()
            .toMillis());
  }

  @Test
  @DisplayName("weighted ENV_VAR variant reads the env var coerced to int")
  void weightedEnvVarVariantReadsTheEnvVarCoercedToInt() throws Exception {
    Quonfig client = TestSetup.client();
    assertEquals(
        1234L,
        client.getLong(
            "weighted.variant.provided.int",
            null,
            TestSetup.ctx(TestSetup.map("user", TestSetup.map("tracking_id", "92a202f2")))));
  }

  @Test
  @DisplayName("weighted ENV_VAR variant with a missing env var returns the default")
  void weightedEnvVarVariantWithAMissingEnvVarReturnsTheDefault() throws Exception {
    Quonfig client = TestSetup.client();
    assertEquals(
        "DEFAULT",
        client.getString(
            "weighted.variant.provided.missing",
            "DEFAULT",
            TestSetup.ctx(TestSetup.map("user", TestSetup.map("tracking_id", "92a202f2")))));
  }

  @Test
  @DisplayName("weighted ENV_VAR variant with a missing env var and no default returns nil")
  void weightedEnvVarVariantWithAMissingEnvVarAndNoDefaultReturnsNil() throws Exception {
    Quonfig client = TestSetup.client();
    assertNull(
        client.getString(
            "weighted.variant.provided.missing",
            null,
            TestSetup.ctx(TestSetup.map("user", TestSetup.map("tracking_id", "92a202f2")))));
  }
}
