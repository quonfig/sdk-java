// AUTO-GENERATED from integration-test-data/tests/eval/dev_overrides.yaml. DO NOT EDIT.
// Regenerate with:
//   cd integration-test-data/generators && npm run generate -- --target=java
// Source: integration-test-data/generators/src/targets/java.ts

package com.quonfig.sdk.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class DevOverridesTest {

  @Test
  @DisplayName("override fires when quonfig-user.email matches")
  void overrideFiresWhenQuonfigUserEmailMatches() throws Exception {
    Object actual =
        TestSetup.enabledCase(
            "feature-flag.dev-override",
            TestSetup.map("quonfig-user", TestSetup.map("email", "bob@foo.com")));
    assertEquals(true, actual);
  }

  @Test
  @DisplayName("override does not fire when attribute absent (prod simulation)")
  void overrideDoesNotFireWhenAttributeAbsentProdSimulation() throws Exception {
    Object actual =
        TestSetup.enabledCase(
            "feature-flag.dev-override",
            TestSetup.map("user", TestSetup.map("email", "bob@foo.com")));
    assertEquals(false, actual);
  }

  @Test
  @DisplayName("override matches any email in IS_ONE_OF list")
  void overrideMatchesAnyEmailInIsOneOfList() throws Exception {
    Object actual =
        TestSetup.enabledCase(
            "feature-flag.dev-override.multi-email",
            TestSetup.map("quonfig-user", TestSetup.map("email", "alice@foo.com")));
    assertEquals(true, actual);
  }

  @Test
  @DisplayName("override beats customer rule by priority")
  void overrideBeatsCustomerRuleByPriority() throws Exception {
    Object actual =
        TestSetup.enabledCase(
            "feature-flag.dev-override.priority",
            TestSetup.map(
                "quonfig-user",
                TestSetup.map("email", "bob@foo.com"),
                "user",
                TestSetup.map("country", "DE")));
    assertEquals(true, actual);
  }
}
