// AUTO-GENERATED from integration-test-data/tests/eval/enabled_with_contexts.yaml. DO NOT EDIT.
// Regenerate with:
//   cd integration-test-data/generators && npm run generate -- --target=java
// Source: integration-test-data/generators/src/targets/java.ts

package com.quonfig.sdk.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class EnabledWithContextsTest {

  @Test
  @DisplayName("returns true from global context")
  void returnsTrueFromGlobalContext() throws Exception {
    Object actual =
        TestSetup.enabledCase(
            "feature-flag.in-seg.segment-and",
            TestSetup.map(
                "",
                TestSetup.map("domain", "prefab.cloud"),
                "user",
                TestSetup.map("key", "michael")));
    assertEquals(true, actual);
  }

  @Test
  @DisplayName("returns false due to local context override")
  void returnsFalseDueToLocalContextOverride() throws Exception {
    Object actual =
        TestSetup.enabledCase(
            "feature-flag.in-seg.segment-and",
            TestSetup.map(
                "",
                TestSetup.map("domain", "prefab.cloud"),
                "user",
                TestSetup.map("key", "james")));
    assertEquals(false, actual);
  }

  @Test
  @DisplayName("returns false for untouched scope context")
  void returnsFalseForUntouchedScopeContext() throws Exception {
    Object actual =
        TestSetup.enabledCase(
            "feature-flag.in-seg.segment-and",
            TestSetup.map(
                "",
                TestSetup.map("domain", "example.com"),
                "user",
                TestSetup.map("key", "nobody")));
    assertEquals(false, actual);
  }

  @Test
  @DisplayName("returns false due to partial scope context override of user.key")
  void returnsFalseDueToPartialScopeContextOverrideOfUserKey() throws Exception {
    Object actual =
        TestSetup.enabledCase(
            "feature-flag.in-seg.segment-and",
            TestSetup.map(
                "",
                TestSetup.map("domain", "example.com"),
                "user",
                TestSetup.map("key", "michael")));
    assertEquals(false, actual);
  }

  @Test
  @DisplayName("returns false due to partial scope context override of domain")
  void returnsFalseDueToPartialScopeContextOverrideOfDomain() throws Exception {
    Object actual =
        TestSetup.enabledCase(
            "feature-flag.in-seg.segment-and",
            TestSetup.map(
                "",
                TestSetup.map("domain", "example.com", "key", "prefab.cloud"),
                "user",
                TestSetup.map("key", "nobody")));
    assertEquals(false, actual);
  }

  @Test
  @DisplayName("returns true due to full scope context override of user.key and domain")
  void returnsTrueDueToFullScopeContextOverrideOfUserKeyAndDomain() throws Exception {
    Object actual =
        TestSetup.enabledCase(
            "feature-flag.in-seg.segment-and",
            TestSetup.map(
                "",
                TestSetup.map("domain", "prefab.cloud"),
                "user",
                TestSetup.map("key", "michael")));
    assertEquals(true, actual);
  }

  @Test
  @DisplayName("returns false for rule with different case on context property name")
  void returnsFalseForRuleWithDifferentCaseOnContextPropertyName() throws Exception {
    Object actual =
        TestSetup.enabledCase(
            "mixed.case.property.name",
            TestSetup.map("user", TestSetup.map("IsHuman", "verified")));
    assertEquals(false, actual);
  }

  @Test
  @DisplayName("returns true for matching case on context property name")
  void returnsTrueForMatchingCaseOnContextPropertyName() throws Exception {
    Object actual =
        TestSetup.enabledCase(
            "mixed.case.property.name",
            TestSetup.map("user", TestSetup.map("isHuman", "verified")));
    assertEquals(true, actual);
  }
}
