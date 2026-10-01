// AUTO-GENERATED from integration-test-data/tests/eval/enabled_with_contexts.yaml. DO NOT EDIT.
// Regenerate with:
//   cd integration-test-data/generators && npm run generate -- --target=java
// Source: integration-test-data/generators/src/targets/java.ts

package com.quonfig.sdk.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.quonfig.sdk.BoundQuonfig;
import com.quonfig.sdk.Quonfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class EnabledWithContextsTest {

  @Test
  @DisplayName("returns true from global context")
  void returnsTrueFromGlobalContext() throws Exception {
    Quonfig client = TestSetup.client();
    BoundQuonfig scoped =
        client.withContext(
            TestSetup.ctx(
                TestSetup.map(
                    "",
                    TestSetup.map("domain", "prefab.cloud"),
                    "user",
                    TestSetup.map("key", "michael"))));
    assertEquals(true, scoped.featureIsOn("feature-flag.in-seg.segment-and"));
  }

  @Test
  @DisplayName("returns false due to local context override")
  void returnsFalseDueToLocalContextOverride() throws Exception {
    Quonfig client = TestSetup.client();
    BoundQuonfig scoped =
        client.withContext(
            TestSetup.ctx(
                TestSetup.map(
                    "",
                    TestSetup.map("domain", "prefab.cloud"),
                    "user",
                    TestSetup.map("key", "michael"))));
    assertEquals(
        false,
        Boolean.TRUE.equals(
            scoped.getBool(
                "feature-flag.in-seg.segment-and",
                Boolean.FALSE,
                TestSetup.ctx(TestSetup.map("user", TestSetup.map("key", "james"))))));
  }

  @Test
  @DisplayName("returns false for untouched scope context")
  void returnsFalseForUntouchedScopeContext() throws Exception {
    Quonfig client = TestSetup.client();
    BoundQuonfig scoped =
        client.withContext(
            TestSetup.ctx(
                TestSetup.map(
                    "",
                    TestSetup.map("domain", "example.com"),
                    "user",
                    TestSetup.map("key", "nobody"))));
    assertEquals(false, scoped.featureIsOn("feature-flag.in-seg.segment-and"));
  }

  @Test
  @DisplayName("returns false due to partial scope context override of user.key")
  void returnsFalseDueToPartialScopeContextOverrideOfUserKey() throws Exception {
    Quonfig client = TestSetup.client();
    BoundQuonfig scoped =
        client.withContext(
            TestSetup.ctx(
                TestSetup.map(
                    "",
                    TestSetup.map("domain", "example.com"),
                    "user",
                    TestSetup.map("key", "nobody"))));
    assertEquals(
        false,
        Boolean.TRUE.equals(
            scoped.getBool(
                "feature-flag.in-seg.segment-and",
                Boolean.FALSE,
                TestSetup.ctx(TestSetup.map("user", TestSetup.map("key", "michael"))))));
  }

  @Test
  @DisplayName("returns false due to partial scope context override of domain")
  void returnsFalseDueToPartialScopeContextOverrideOfDomain() throws Exception {
    Quonfig client = TestSetup.client();
    BoundQuonfig scoped =
        client.withContext(
            TestSetup.ctx(
                TestSetup.map(
                    "",
                    TestSetup.map("domain", "example.com"),
                    "user",
                    TestSetup.map("key", "nobody"))));
    assertEquals(
        false,
        Boolean.TRUE.equals(
            scoped.getBool(
                "feature-flag.in-seg.segment-and",
                Boolean.FALSE,
                TestSetup.ctx(TestSetup.map("", TestSetup.map("domain", "prefab.cloud"))))));
  }

  @Test
  @DisplayName("returns true due to local override of domain when scope user.key already matches")
  void returnsTrueDueToLocalOverrideOfDomainWhenScopeUserKeyAlreadyMatches() throws Exception {
    Quonfig client = TestSetup.client();
    BoundQuonfig scoped =
        client.withContext(
            TestSetup.ctx(
                TestSetup.map(
                    "",
                    TestSetup.map("domain", "example.com"),
                    "user",
                    TestSetup.map("key", "michael"))));
    assertEquals(
        true,
        Boolean.TRUE.equals(
            scoped.getBool(
                "feature-flag.in-seg.segment-and",
                Boolean.FALSE,
                TestSetup.ctx(TestSetup.map("", TestSetup.map("domain", "prefab.cloud"))))));
  }

  @Test
  @DisplayName("returns true due to full scope context override of user.key and domain")
  void returnsTrueDueToFullScopeContextOverrideOfUserKeyAndDomain() throws Exception {
    Quonfig client = TestSetup.client();
    BoundQuonfig scoped =
        client.withContext(
            TestSetup.ctx(
                TestSetup.map(
                    "",
                    TestSetup.map("domain", "example.com"),
                    "user",
                    TestSetup.map("key", "nobody"))));
    assertEquals(
        true,
        Boolean.TRUE.equals(
            scoped.getBool(
                "feature-flag.in-seg.segment-and",
                Boolean.FALSE,
                TestSetup.ctx(
                    TestSetup.map(
                        "user",
                        TestSetup.map("key", "michael"),
                        "",
                        TestSetup.map("domain", "prefab.cloud"))))));
  }

  @Test
  @DisplayName("returns false for rule with different case on context property name")
  void returnsFalseForRuleWithDifferentCaseOnContextPropertyName() throws Exception {
    Quonfig client = TestSetup.client();
    assertEquals(
        false,
        client.featureIsOn(
            "mixed.case.property.name",
            TestSetup.ctx(TestSetup.map("user", TestSetup.map("IsHuman", "verified")))));
  }

  @Test
  @DisplayName("returns true for matching case on context property name")
  void returnsTrueForMatchingCaseOnContextPropertyName() throws Exception {
    Quonfig client = TestSetup.client();
    assertEquals(
        true,
        client.featureIsOn(
            "mixed.case.property.name",
            TestSetup.ctx(TestSetup.map("user", TestSetup.map("isHuman", "verified")))));
  }
}
