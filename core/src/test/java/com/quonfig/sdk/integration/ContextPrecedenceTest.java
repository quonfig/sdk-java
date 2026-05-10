// AUTO-GENERATED from integration-test-data/tests/eval/context_precedence.yaml. DO NOT EDIT.
// Regenerate with:
//   cd integration-test-data/generators && npm run generate -- --target=java
// Source: integration-test-data/generators/src/targets/java.ts

package com.quonfig.sdk.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ContextPrecedenceTest {

  @Test
  @DisplayName("returns the correct `flag` value using the global context (1)")
  void returnsTheCorrectFlagValueUsingTheGlobalContext1() throws Exception {
    Object actual =
        TestSetup.enabledCase(
            "mixed.case.property.name",
            TestSetup.map("user", TestSetup.map("isHuman", "verified")));
    assertEquals(true, actual);
  }

  @Test
  @DisplayName("returns the correct `flag` value using the global context (2)")
  void returnsTheCorrectFlagValueUsingTheGlobalContext2() throws Exception {
    Object actual =
        TestSetup.enabledCase(
            "mixed.case.property.name", TestSetup.map("user", TestSetup.map("isHuman", "?")));
    assertEquals(false, actual);
  }

  @Test
  @DisplayName("returns the correct `flag` value when local context clobbers global context (1)")
  void returnsTheCorrectFlagValueWhenLocalContextClobbersGlobalContext1() throws Exception {
    Object actual =
        TestSetup.enabledCase(
            "mixed.case.property.name",
            TestSetup.map("user", TestSetup.map("isHuman", "verified")));
    assertEquals(true, actual);
  }

  @Test
  @DisplayName("returns the correct `flag` value when local context clobbers global context (2)")
  void returnsTheCorrectFlagValueWhenLocalContextClobbersGlobalContext2() throws Exception {
    Object actual =
        TestSetup.enabledCase(
            "mixed.case.property.name", TestSetup.map("user", TestSetup.map("isHuman", "?")));
    assertEquals(false, actual);
  }

  @Test
  @DisplayName("returns the correct `flag` value when block context clobbers global context (1)")
  void returnsTheCorrectFlagValueWhenBlockContextClobbersGlobalContext1() throws Exception {
    Object actual =
        TestSetup.enabledCase(
            "mixed.case.property.name", TestSetup.map("user", TestSetup.map("isHuman", "?")));
    assertEquals(false, actual);
  }

  @Test
  @DisplayName("returns the correct `flag` value when block context clobbers global context (2)")
  void returnsTheCorrectFlagValueWhenBlockContextClobbersGlobalContext2() throws Exception {
    Object actual =
        TestSetup.enabledCase(
            "mixed.case.property.name",
            TestSetup.map("user", TestSetup.map("isHuman", "verified")));
    assertEquals(true, actual);
  }

  @Test
  @DisplayName("returns the correct `flag` value when local context clobbers block context (1)")
  void returnsTheCorrectFlagValueWhenLocalContextClobbersBlockContext1() throws Exception {
    Object actual =
        TestSetup.enabledCase(
            "mixed.case.property.name", TestSetup.map("user", TestSetup.map("isHuman", "?")));
    assertEquals(false, actual);
  }

  @Test
  @DisplayName("returns the correct `flag` value when local context clobbers block context (2)")
  void returnsTheCorrectFlagValueWhenLocalContextClobbersBlockContext2() throws Exception {
    Object actual =
        TestSetup.enabledCase(
            "mixed.case.property.name",
            TestSetup.map("user", TestSetup.map("isHuman", "verified")));
    assertEquals(true, actual);
  }

  @Test
  @DisplayName("returns the correct `get` value using the global context (1)")
  void returnsTheCorrectGetValueUsingTheGlobalContext1() throws Exception {
    Object actual =
        TestSetup.resolveCase(
            "basic.rule.config",
            TestSetup.map("user", TestSetup.map("email", "test@prefab.cloud")));
    assertEquals("override", actual);
  }

  @Test
  @DisplayName("returns the correct `get` value using the global context (2)")
  void returnsTheCorrectGetValueUsingTheGlobalContext2() throws Exception {
    Object actual =
        TestSetup.resolveCase(
            "basic.rule.config", TestSetup.map("user", TestSetup.map("email", "test@example.com")));
    assertEquals("default", actual);
  }

  @Test
  @DisplayName("returns the correct `get` value when local context clobbers global context (1)")
  void returnsTheCorrectGetValueWhenLocalContextClobbersGlobalContext1() throws Exception {
    Object actual =
        TestSetup.resolveCase(
            "basic.rule.config",
            TestSetup.map("user", TestSetup.map("email", "test@prefab.cloud")));
    assertEquals("override", actual);
  }

  @Test
  @DisplayName("returns the correct `get` value when local context clobbers global context (2)")
  void returnsTheCorrectGetValueWhenLocalContextClobbersGlobalContext2() throws Exception {
    Object actual =
        TestSetup.resolveCase(
            "basic.rule.config", TestSetup.map("user", TestSetup.map("email", "test@example.com")));
    assertEquals("default", actual);
  }

  @Test
  @DisplayName("returns the correct `get` value when block context clobbers global context (1)")
  void returnsTheCorrectGetValueWhenBlockContextClobbersGlobalContext1() throws Exception {
    Object actual =
        TestSetup.resolveCase(
            "basic.rule.config", TestSetup.map("user", TestSetup.map("email", "test@example.com")));
    assertEquals("default", actual);
  }

  @Test
  @DisplayName("returns the correct `get` value when block context clobbers global context (2)")
  void returnsTheCorrectGetValueWhenBlockContextClobbersGlobalContext2() throws Exception {
    Object actual =
        TestSetup.resolveCase(
            "basic.rule.config",
            TestSetup.map("user", TestSetup.map("email", "test@prefab.cloud")));
    assertEquals("override", actual);
  }

  @Test
  @DisplayName("returns the correct `get` value when local context clobbers block context (1)")
  void returnsTheCorrectGetValueWhenLocalContextClobbersBlockContext1() throws Exception {
    Object actual =
        TestSetup.resolveCase(
            "basic.rule.config", TestSetup.map("user", TestSetup.map("email", "test@example.com")));
    assertEquals("default", actual);
  }

  @Test
  @DisplayName("returns the correct `get` value when local context clobbers block context (2)")
  void returnsTheCorrectGetValueWhenLocalContextClobbersBlockContext2() throws Exception {
    Object actual =
        TestSetup.resolveCase(
            "basic.rule.config",
            TestSetup.map("user", TestSetup.map("email", "test@prefab.cloud")));
    assertEquals("override", actual);
  }
}
