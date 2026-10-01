// AUTO-GENERATED from integration-test-data/tests/eval/dev_overrides.yaml. DO NOT EDIT.
// Regenerate with:
//   cd integration-test-data/generators && npm run generate -- --target=java
// Source: integration-test-data/generators/src/targets/java.ts

package com.quonfig.sdk.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.quonfig.sdk.BoundQuonfig;
import com.quonfig.sdk.Quonfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class DevOverridesTest {

  @Test
  @DisplayName("override fires when quonfig-user.email matches")
  void overrideFiresWhenQuonfigUserEmailMatches() throws Exception {
    Quonfig client = TestSetup.client();
    BoundQuonfig scoped =
        client.withContext(
            TestSetup.ctx(TestSetup.map("quonfig-user", TestSetup.map("email", "bob@foo.com"))));
    assertEquals(true, scoped.featureIsOn("feature-flag.dev-override"));
  }

  @Test
  @DisplayName("override does not fire when attribute absent (prod simulation)")
  void overrideDoesNotFireWhenAttributeAbsentProdSimulation() throws Exception {
    Quonfig client = TestSetup.client();
    BoundQuonfig scoped =
        client.withContext(
            TestSetup.ctx(TestSetup.map("user", TestSetup.map("email", "bob@foo.com"))));
    assertEquals(false, scoped.featureIsOn("feature-flag.dev-override"));
  }

  @Test
  @DisplayName("override matches any email in IS_ONE_OF list")
  void overrideMatchesAnyEmailInIsOneOfList() throws Exception {
    Quonfig client = TestSetup.client();
    BoundQuonfig scoped =
        client.withContext(
            TestSetup.ctx(TestSetup.map("quonfig-user", TestSetup.map("email", "alice@foo.com"))));
    assertEquals(true, scoped.featureIsOn("feature-flag.dev-override.multi-email"));
  }

  @Test
  @DisplayName("override beats customer rule by priority")
  void overrideBeatsCustomerRuleByPriority() throws Exception {
    Quonfig client = TestSetup.client();
    BoundQuonfig scoped =
        client.withContext(
            TestSetup.ctx(
                TestSetup.map(
                    "quonfig-user",
                    TestSetup.map("email", "bob@foo.com"),
                    "user",
                    TestSetup.map("country", "DE"))));
    assertEquals(true, scoped.featureIsOn("feature-flag.dev-override.priority"));
  }
}
