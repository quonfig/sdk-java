// AUTO-GENERATED from integration-test-data/tests/eval/context_precedence.yaml. DO NOT EDIT.
// Regenerate with:
//   cd integration-test-data/generators && npm run generate -- --target=java
// Source: integration-test-data/generators/src/targets/java.ts

package com.quonfig.sdk.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.quonfig.sdk.BoundQuonfig;
import com.quonfig.sdk.Quonfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ContextPrecedenceTest {

  @Test
  @DisplayName("returns the correct `flag` value using the global context (1)")
  void returnsTheCorrectFlagValueUsingTheGlobalContext1() throws Exception {
    try (Quonfig client =
        TestSetup.newClient(
            TestSetup.ctx(TestSetup.map("user", TestSetup.map("isHuman", "verified"))))) {
      assertEquals(true, client.featureIsOn("mixed.case.property.name", null));
    }
  }

  @Test
  @DisplayName("returns the correct `flag` value using the global context (2)")
  void returnsTheCorrectFlagValueUsingTheGlobalContext2() throws Exception {
    try (Quonfig client =
        TestSetup.newClient(TestSetup.ctx(TestSetup.map("user", TestSetup.map("isHuman", "?"))))) {
      assertEquals(false, client.featureIsOn("mixed.case.property.name", null));
    }
  }

  @Test
  @DisplayName("returns the correct `flag` value when local context clobbers global context (1)")
  void returnsTheCorrectFlagValueWhenLocalContextClobbersGlobalContext1() throws Exception {
    try (Quonfig client =
        TestSetup.newClient(TestSetup.ctx(TestSetup.map("user", TestSetup.map("isHuman", "?"))))) {
      assertEquals(
          true,
          client.featureIsOn(
              "mixed.case.property.name",
              TestSetup.ctx(TestSetup.map("user", TestSetup.map("isHuman", "verified")))));
    }
  }

  @Test
  @DisplayName("returns the correct `flag` value when local context clobbers global context (2)")
  void returnsTheCorrectFlagValueWhenLocalContextClobbersGlobalContext2() throws Exception {
    try (Quonfig client =
        TestSetup.newClient(
            TestSetup.ctx(TestSetup.map("user", TestSetup.map("isHuman", "verified"))))) {
      assertEquals(
          false,
          client.featureIsOn(
              "mixed.case.property.name",
              TestSetup.ctx(TestSetup.map("user", TestSetup.map("isHuman", "?")))));
    }
  }

  @Test
  @DisplayName("returns the correct `flag` value when block context clobbers global context (1)")
  void returnsTheCorrectFlagValueWhenBlockContextClobbersGlobalContext1() throws Exception {
    try (Quonfig client =
        TestSetup.newClient(
            TestSetup.ctx(TestSetup.map("user", TestSetup.map("isHuman", "verified"))))) {
      BoundQuonfig scoped =
          client.withContext(TestSetup.ctx(TestSetup.map("user", TestSetup.map("isHuman", "?"))));
      assertEquals(false, scoped.featureIsOn("mixed.case.property.name"));
    }
  }

  @Test
  @DisplayName("returns the correct `flag` value when block context clobbers global context (2)")
  void returnsTheCorrectFlagValueWhenBlockContextClobbersGlobalContext2() throws Exception {
    try (Quonfig client =
        TestSetup.newClient(TestSetup.ctx(TestSetup.map("user", TestSetup.map("isHuman", "?"))))) {
      BoundQuonfig scoped =
          client.withContext(
              TestSetup.ctx(TestSetup.map("user", TestSetup.map("isHuman", "verified"))));
      assertEquals(true, scoped.featureIsOn("mixed.case.property.name"));
    }
  }

  @Test
  @DisplayName("returns the correct `flag` value when local context clobbers block context (1)")
  void returnsTheCorrectFlagValueWhenLocalContextClobbersBlockContext1() throws Exception {
    Quonfig client = TestSetup.client();
    BoundQuonfig scoped =
        client.withContext(
            TestSetup.ctx(TestSetup.map("user", TestSetup.map("isHuman", "verified"))));
    assertEquals(
        false,
        Boolean.TRUE.equals(
            scoped.getBool(
                "mixed.case.property.name",
                Boolean.FALSE,
                TestSetup.ctx(TestSetup.map("user", TestSetup.map("isHuman", "?"))))));
  }

  @Test
  @DisplayName("returns the correct `flag` value when local context clobbers block context (2)")
  void returnsTheCorrectFlagValueWhenLocalContextClobbersBlockContext2() throws Exception {
    Quonfig client = TestSetup.client();
    BoundQuonfig scoped =
        client.withContext(TestSetup.ctx(TestSetup.map("user", TestSetup.map("isHuman", "?"))));
    assertEquals(
        true,
        Boolean.TRUE.equals(
            scoped.getBool(
                "mixed.case.property.name",
                Boolean.FALSE,
                TestSetup.ctx(TestSetup.map("user", TestSetup.map("isHuman", "verified"))))));
  }

  @Test
  @DisplayName("returns the correct `get` value using the global context (1)")
  void returnsTheCorrectGetValueUsingTheGlobalContext1() throws Exception {
    try (Quonfig client =
        TestSetup.newClient(
            TestSetup.ctx(TestSetup.map("user", TestSetup.map("email", "test@prefab.cloud"))))) {
      assertEquals("override", client.getString("basic.rule.config", null));
    }
  }

  @Test
  @DisplayName("returns the correct `get` value using the global context (2)")
  void returnsTheCorrectGetValueUsingTheGlobalContext2() throws Exception {
    try (Quonfig client =
        TestSetup.newClient(
            TestSetup.ctx(TestSetup.map("user", TestSetup.map("email", "test@example.com"))))) {
      assertEquals("default", client.getString("basic.rule.config", null));
    }
  }

  @Test
  @DisplayName("returns the correct `get` value when local context clobbers global context (1)")
  void returnsTheCorrectGetValueWhenLocalContextClobbersGlobalContext1() throws Exception {
    try (Quonfig client =
        TestSetup.newClient(
            TestSetup.ctx(TestSetup.map("user", TestSetup.map("email", "test@example.com"))))) {
      assertEquals(
          "override",
          client.getString(
              "basic.rule.config",
              null,
              TestSetup.ctx(TestSetup.map("user", TestSetup.map("email", "test@prefab.cloud")))));
    }
  }

  @Test
  @DisplayName("returns the correct `get` value when local context clobbers global context (2)")
  void returnsTheCorrectGetValueWhenLocalContextClobbersGlobalContext2() throws Exception {
    try (Quonfig client =
        TestSetup.newClient(
            TestSetup.ctx(TestSetup.map("user", TestSetup.map("email", "test@prefab.cloud"))))) {
      assertEquals(
          "default",
          client.getString(
              "basic.rule.config",
              null,
              TestSetup.ctx(TestSetup.map("user", TestSetup.map("email", "test@example.com")))));
    }
  }

  @Test
  @DisplayName("returns the correct `get` value when block context clobbers global context (1)")
  void returnsTheCorrectGetValueWhenBlockContextClobbersGlobalContext1() throws Exception {
    try (Quonfig client =
        TestSetup.newClient(
            TestSetup.ctx(TestSetup.map("user", TestSetup.map("email", "test@prefab.cloud"))))) {
      BoundQuonfig scoped =
          client.withContext(
              TestSetup.ctx(TestSetup.map("user", TestSetup.map("email", "test@example.com"))));
      assertEquals("default", scoped.getString("basic.rule.config", null));
    }
  }

  @Test
  @DisplayName("returns the correct `get` value when block context clobbers global context (2)")
  void returnsTheCorrectGetValueWhenBlockContextClobbersGlobalContext2() throws Exception {
    try (Quonfig client =
        TestSetup.newClient(
            TestSetup.ctx(TestSetup.map("user", TestSetup.map("email", "test@example.com"))))) {
      BoundQuonfig scoped =
          client.withContext(
              TestSetup.ctx(TestSetup.map("user", TestSetup.map("email", "test@prefab.cloud"))));
      assertEquals("override", scoped.getString("basic.rule.config", null));
    }
  }

  @Test
  @DisplayName("returns the correct `get` value when local context clobbers block context (1)")
  void returnsTheCorrectGetValueWhenLocalContextClobbersBlockContext1() throws Exception {
    Quonfig client = TestSetup.client();
    BoundQuonfig scoped =
        client.withContext(
            TestSetup.ctx(TestSetup.map("user", TestSetup.map("email", "test@prefab.cloud"))));
    assertEquals(
        "default",
        scoped.getString(
            "basic.rule.config",
            null,
            TestSetup.ctx(TestSetup.map("user", TestSetup.map("email", "test@example.com")))));
  }

  @Test
  @DisplayName("returns the correct `get` value when local context clobbers block context (2)")
  void returnsTheCorrectGetValueWhenLocalContextClobbersBlockContext2() throws Exception {
    Quonfig client = TestSetup.client();
    BoundQuonfig scoped =
        client.withContext(
            TestSetup.ctx(TestSetup.map("user", TestSetup.map("email", "test@example.com"))));
    assertEquals(
        "override",
        scoped.getString(
            "basic.rule.config",
            null,
            TestSetup.ctx(TestSetup.map("user", TestSetup.map("email", "test@prefab.cloud")))));
  }

  @Test
  @DisplayName(
      "returns the correct `get` value when local context replaces the whole global named context (disjoint attributes)")
  void
      returnsTheCorrectGetValueWhenLocalContextReplacesTheWholeGlobalNamedContextDisjointAttributes()
          throws Exception {
    try (Quonfig client =
        TestSetup.newClient(
            TestSetup.ctx(TestSetup.map("user", TestSetup.map("email", "test@prefab.cloud"))))) {
      assertEquals(
          "default",
          client.getString(
              "basic.rule.config",
              null,
              TestSetup.ctx(TestSetup.map("user", TestSetup.map("plan", "pro")))));
    }
  }

  @Test
  @DisplayName(
      "returns the correct `get` value when a named context the local context does not mention survives")
  void returnsTheCorrectGetValueWhenANamedContextTheLocalContextDoesNotMentionSurvives()
      throws Exception {
    try (Quonfig client =
        TestSetup.newClient(
            TestSetup.ctx(TestSetup.map("user", TestSetup.map("email", "test@prefab.cloud"))))) {
      assertEquals(
          "override",
          client.getString(
              "basic.rule.config",
              null,
              TestSetup.ctx(TestSetup.map("team", TestSetup.map("plan", "pro")))));
    }
  }
}
