// AUTO-GENERATED from integration-test-data/tests/eval/get_weighted_values.yaml. DO NOT EDIT.
// Regenerate with:
//   cd integration-test-data/generators && npm run generate -- --target=java
// Source: integration-test-data/generators/src/targets/java.ts

package com.quonfig.sdk.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class GetWeightedValuesTest {

  @Test
  @DisplayName("weighted value is consistent 1")
  void weightedValueIsConsistent1() throws Exception {
    Object actual =
        TestSetup.resolveCase(
            "feature-flag.weighted",
            TestSetup.map("user", TestSetup.map("tracking_id", "a72c15f5")));
    assertEquals(1L, actual);
  }

  @Test
  @DisplayName("weighted value is consistent 2")
  void weightedValueIsConsistent2() throws Exception {
    Object actual =
        TestSetup.resolveCase(
            "feature-flag.weighted",
            TestSetup.map("user", TestSetup.map("tracking_id", "92a202f2")));
    assertEquals(2L, actual);
  }

  @Test
  @DisplayName("weighted value is consistent 3")
  void weightedValueIsConsistent3() throws Exception {
    Object actual =
        TestSetup.resolveCase(
            "feature-flag.weighted",
            TestSetup.map("user", TestSetup.map("tracking_id", "8f414100")));
    assertEquals(3L, actual);
  }

  @Test
  @DisplayName("even split ones serves first variant at low hash fraction")
  void evenSplitOnesServesFirstVariantAtLowHashFraction() throws Exception {
    Object actual =
        TestSetup.resolveCase(
            "feature-flag.weighted.even-split-ones",
            TestSetup.map("user", TestSetup.map("tracking_id", "b7ff78c8")));
    assertEquals("a", actual);
  }

  @Test
  @DisplayName("even split ones serves first variant at low hash fraction 2")
  void evenSplitOnesServesFirstVariantAtLowHashFraction2() throws Exception {
    Object actual =
        TestSetup.resolveCase(
            "feature-flag.weighted.even-split-ones",
            TestSetup.map("user", TestSetup.map("tracking_id", "289f4748")));
    assertEquals("a", actual);
  }

  @Test
  @DisplayName("even split ones serves second variant at high hash fraction")
  void evenSplitOnesServesSecondVariantAtHighHashFraction() throws Exception {
    Object actual =
        TestSetup.resolveCase(
            "feature-flag.weighted.even-split-ones",
            TestSetup.map("user", TestSetup.map("tracking_id", "d60b2cb6")));
    assertEquals("b", actual);
  }

  @Test
  @DisplayName("even split ones serves second variant at high hash fraction 2")
  void evenSplitOnesServesSecondVariantAtHighHashFraction2() throws Exception {
    Object actual =
        TestSetup.resolveCase(
            "feature-flag.weighted.even-split-ones",
            TestSetup.map("user", TestSetup.map("tracking_id", "21bcfd13")));
    assertEquals("b", actual);
  }

  @Test
  @DisplayName("non-standard sum still serves normalized true bucket")
  void nonStandardSumStillServesNormalizedTrueBucket() throws Exception {
    Object actual =
        TestSetup.resolveCase(
            "feature-flag.weighted.non-standard",
            TestSetup.map("user", TestSetup.map("tracking_id", "ff8adf17")));
    assertEquals(true, actual);
  }

  @Test
  @DisplayName("non-standard sum still serves normalized true bucket 2")
  void nonStandardSumStillServesNormalizedTrueBucket2() throws Exception {
    Object actual =
        TestSetup.resolveCase(
            "feature-flag.weighted.non-standard",
            TestSetup.map("user", TestSetup.map("tracking_id", "36ef1a7a")));
    assertEquals(true, actual);
  }

  @Test
  @DisplayName("non-standard sum still serves normalized false bucket")
  void nonStandardSumStillServesNormalizedFalseBucket() throws Exception {
    Object actual =
        TestSetup.resolveCase(
            "feature-flag.weighted.non-standard",
            TestSetup.map("user", TestSetup.map("tracking_id", "f667c76a")));
    assertEquals(false, actual);
  }

  @Test
  @DisplayName("non-standard sum still serves normalized false bucket 2")
  void nonStandardSumStillServesNormalizedFalseBucket2() throws Exception {
    Object actual =
        TestSetup.resolveCase(
            "feature-flag.weighted.non-standard",
            TestSetup.map("user", TestSetup.map("tracking_id", "7467ca21")));
    assertEquals(false, actual);
  }

  @Test
  @DisplayName("weighted value with hash property missing from context hashes empty string")
  void weightedValueWithHashPropertyMissingFromContextHashesEmptyString() throws Exception {
    Object actual =
        TestSetup.resolveCase(
            "feature-flag.weighted.missing-hash",
            TestSetup.map("user", TestSetup.map("key", "no-tracking-id-user")));
    assertEquals(2L, actual);
  }

  @Test
  @DisplayName("weighted value with no context hashes empty string")
  void weightedValueWithNoContextHashesEmptyString() throws Exception {
    Object actual = TestSetup.resolveCase("feature-flag.weighted.missing-hash", TestSetup.map());
    assertEquals(2L, actual);
  }

  @Test
  @DisplayName("weighted value with hash property empty string hashes empty string")
  void weightedValueWithHashPropertyEmptyStringHashesEmptyString() throws Exception {
    Object actual =
        TestSetup.resolveCase(
            "feature-flag.weighted.missing-hash",
            TestSetup.map(
                "user", TestSetup.map("key", "empty-tracking-id-user", "tracking_id", "")));
    assertEquals(2L, actual);
  }

  @Test
  @DisplayName(
      "weighted value with zero-weight first variant and hash property missing never serves zero-weight variant")
  void weightedValueWithZeroWeightFirstVariantAndHashPropertyMissingNeverServesZeroWeightVariant()
      throws Exception {
    Object actual =
        TestSetup.resolveCase(
            "feature-flag.weighted.zero-first",
            TestSetup.map("user", TestSetup.map("key", "no-tracking-id-user")));
    assertEquals(2L, actual);
  }

  @Test
  @DisplayName("weighted value with no hash property is random on every evaluation")
  void weightedValueWithNoHashPropertyIsRandomOnEveryEvaluation() throws Exception {
    java.util.Set<Object> seen = new java.util.HashSet<>();
    for (int i = 0; i < 200; i++) {
      seen.add(TestSetup.resolveCase("feature-flag.weighted.no-hash", TestSetup.map()));
    }
    assertEquals(java.util.Set.of(1L, 2L), seen, "values seen over 200 evaluations");
  }

  @Test
  @DisplayName("weighted value with no hash property is random on every evaluation with context")
  void weightedValueWithNoHashPropertyIsRandomOnEveryEvaluationWithContext() throws Exception {
    java.util.Set<Object> seen = new java.util.HashSet<>();
    for (int i = 0; i < 200; i++) {
      seen.add(
          TestSetup.resolveCase(
              "feature-flag.weighted.no-hash",
              TestSetup.map(
                  "user",
                  TestSetup.map(
                      "key", "same-user-every-time", "tracking_id", "same-tracking-id"))));
    }
    assertEquals(java.util.Set.of(1L, 2L), seen, "values seen over 200 evaluations");
  }
}
