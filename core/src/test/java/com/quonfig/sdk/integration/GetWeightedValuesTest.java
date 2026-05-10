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
}
