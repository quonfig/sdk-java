// AUTO-GENERATED from integration-test-data/tests/eval/get_feature_flag.yaml. DO NOT EDIT.
// Regenerate with:
//   cd integration-test-data/generators && npm run generate -- --target=java
// Source: integration-test-data/generators/src/targets/java.ts

package com.quonfig.sdk.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class GetFeatureFlagTest {

  @Test
  @DisplayName("get returns the underlying value for a feature flag")
  void getReturnsTheUnderlyingValueForAFeatureFlag() throws Exception {
    Object actual = TestSetup.resolveCase("feature-flag.integer", TestSetup.map());
    assertEquals(3L, actual);
  }

  @Test
  @DisplayName(
      "get returns the underlying value for a feature flag that matches the highest precedent rule")
  void getReturnsTheUnderlyingValueForAFeatureFlagThatMatchesTheHighestPrecedentRule()
      throws Exception {
    Object actual =
        TestSetup.resolveCase(
            "feature-flag.integer", TestSetup.map("user", TestSetup.map("key", "michael")));
    assertEquals(5L, actual);
  }
}
