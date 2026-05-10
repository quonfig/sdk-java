// AUTO-GENERATED from integration-test-data/tests/eval/get.yaml. DO NOT EDIT.
// Regenerate with:
//   cd integration-test-data/generators && npm run generate -- --target=java
// Source: integration-test-data/generators/src/targets/java.ts

package com.quonfig.sdk.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class GetTest {

  @Test
  @DisplayName("get returns a found value for key")
  void getReturnsAFoundValueForKey() throws Exception {
    Object actual = TestSetup.resolveCase("my-test-key", TestSetup.map());
    assertEquals("my-test-value", actual);
  }

  @Test
  @DisplayName("get returns nil if value not found")
  void getReturnsNilIfValueNotFound() throws Exception {
    Object actual = TestSetup.resolveCase("my-missing-key", TestSetup.map());
    assertNull(actual);
  }

  @Test
  @DisplayName("get returns a default for a missing value if a default is given")
  void getReturnsADefaultForAMissingValueIfADefaultIsGiven() throws Exception {
    Object actual = TestSetup.getCase("my-missing-key", TestSetup.map(), "DEFAULT");
    assertEquals("DEFAULT", actual);
  }

  @Test
  @DisplayName("get ignores a provided default if the key is found")
  void getIgnoresAProvidedDefaultIfTheKeyIsFound() throws Exception {
    Object actual = TestSetup.getCase("my-test-key", TestSetup.map(), "DEFAULT");
    assertEquals("my-test-value", actual);
  }

  @Test
  @DisplayName("get can return a double")
  void getCanReturnADouble() throws Exception {
    Object actual = TestSetup.resolveCase("my-double-key", TestSetup.map());
    TestSetup.assertDoubleEquals(9.95d, actual);
  }

  @Test
  @DisplayName("get can return a string list")
  void getCanReturnAStringList() throws Exception {
    Object actual = TestSetup.resolveCase("my-string-list-key", TestSetup.map());
    assertEquals(TestSetup.list("a", "b", "c"), actual);
  }

  @Test
  @DisplayName("can return a value provided by an environment variable")
  void canReturnAValueProvidedByAnEnvironmentVariable() throws Exception {
    Object actual = TestSetup.resolveCase("prefab.secrets.encryption.key", TestSetup.map());
    assertEquals("c87ba22d8662282abe8a0e4651327b579cb64a454ab0f4c170b45b15f049a221", actual);
  }

  @Test
  @DisplayName("can return a value provided by an environment variable after type coercion")
  void canReturnAValueProvidedByAnEnvironmentVariableAfterTypeCoercion() throws Exception {
    Object actual = TestSetup.resolveCase("provided.a.number", TestSetup.map());
    assertEquals(1234L, actual);
  }

  @Test
  @DisplayName("can decrypt and return a secret value (with decryption key in in env var)")
  void canDecryptAndReturnASecretValueWithDecryptionKeyInInEnvVar() throws Exception {
    Object actual = TestSetup.resolveCase("a.secret.config", TestSetup.map());
    assertEquals("hello.world", actual);
  }

  @Test
  @DisplayName("duration 200 ms")
  void duration200Ms() throws Exception {
    Object actual = TestSetup.resolveCase("test.duration.PT0.2S", TestSetup.map());
    TestSetup.assertDurationMillis(actual, 200);
  }

  @Test
  @DisplayName("duration 90S")
  void duration90s() throws Exception {
    Object actual = TestSetup.resolveCase("test.duration.PT90S", TestSetup.map());
    TestSetup.assertDurationMillis(actual, 90000);
  }

  @Test
  @DisplayName("duration 1.5M")
  void duration15m() throws Exception {
    Object actual = TestSetup.resolveCase("test.duration.PT1.5M", TestSetup.map());
    TestSetup.assertDurationMillis(actual, 90000);
  }

  @Test
  @DisplayName("duration 0.5H")
  void duration05h() throws Exception {
    Object actual = TestSetup.resolveCase("test.duration.PT0.5H", TestSetup.map());
    TestSetup.assertDurationMillis(actual, 1800000);
  }

  @Test
  @DisplayName("duration test.duration.P1DT6H2M1.5S")
  void durationTestDurationP1dt6h2m15s() throws Exception {
    Object actual = TestSetup.resolveCase("test.duration.P1DT6H2M1.5S", TestSetup.map());
    TestSetup.assertDurationMillis(actual, 108121500);
  }

  @Test
  @DisplayName("json test")
  void jsonTest() throws Exception {
    Object actual = TestSetup.resolveCase("test.json", TestSetup.map());
    assertEquals(TestSetup.map("a", 1L, "b", "c"), actual);
  }

  @Test
  @DisplayName("get returns a native json object (not a stringified payload)")
  void getReturnsANativeJsonObjectNotAStringifiedPayload() throws Exception {
    Object actual = TestSetup.resolveCase("test.json", TestSetup.map());
    assertEquals(TestSetup.map("a", 1L, "b", "c"), actual);
  }

  @Test
  @DisplayName("list on left side test (1)")
  void listOnLeftSideTest1() throws Exception {
    Object actual =
        TestSetup.resolveCase(
            "left.hand.list.test",
            TestSetup.map(
                "user", TestSetup.map("name", "james", "aka", TestSetup.list("happy", "sleepy"))));
    assertEquals("correct", actual);
  }

  @Test
  @DisplayName("list on left side test (2)")
  void listOnLeftSideTest2() throws Exception {
    Object actual =
        TestSetup.resolveCase(
            "left.hand.list.test",
            TestSetup.map("user", TestSetup.map("name", "james", "aka", TestSetup.list("a", "b"))));
    assertEquals("default", actual);
  }

  @Test
  @DisplayName("list on left side test opposite (1)")
  void listOnLeftSideTestOpposite1() throws Exception {
    Object actual =
        TestSetup.resolveCase(
            "left.hand.test.opposite",
            TestSetup.map(
                "user", TestSetup.map("name", "james", "aka", TestSetup.list("happy", "sleepy"))));
    assertEquals("default", actual);
  }

  @Test
  @DisplayName("list on left side test (3)")
  void listOnLeftSideTest3() throws Exception {
    Object actual =
        TestSetup.resolveCase(
            "left.hand.test.opposite",
            TestSetup.map("user", TestSetup.map("name", "james", "aka", TestSetup.list("a", "b"))));
    assertEquals("correct", actual);
  }
}
