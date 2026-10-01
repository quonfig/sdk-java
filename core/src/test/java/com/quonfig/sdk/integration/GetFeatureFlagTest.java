// AUTO-GENERATED from integration-test-data/tests/eval/get_feature_flag.yaml. DO NOT EDIT.
// Regenerate with:
//   cd integration-test-data/generators && npm run generate -- --target=java
// Source: integration-test-data/generators/src/targets/java.ts

package com.quonfig.sdk.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.quonfig.sdk.Quonfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class GetFeatureFlagTest {

  @Test
  @DisplayName("get returns the underlying value for a feature flag")
  void getReturnsTheUnderlyingValueForAFeatureFlag() throws Exception {
    Quonfig client = TestSetup.client();
    assertEquals(3L, client.getLong("feature-flag.integer", null));
  }

  @Test
  @DisplayName(
      "get returns the underlying value for a feature flag that matches the highest precedent rule")
  void getReturnsTheUnderlyingValueForAFeatureFlagThatMatchesTheHighestPrecedentRule()
      throws Exception {
    Quonfig client = TestSetup.client();
    assertEquals(
        5L,
        client.getLong(
            "feature-flag.integer",
            null,
            TestSetup.ctx(TestSetup.map("user", TestSetup.map("key", "michael")))));
  }
}
