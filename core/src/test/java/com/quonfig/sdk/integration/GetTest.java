// AUTO-GENERATED from integration-test-data/tests/eval/get.yaml. DO NOT EDIT.
// Regenerate with:
//   cd integration-test-data/generators && npm run generate -- --target=java
// Source: integration-test-data/generators/src/targets/java.ts

package com.quonfig.sdk.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.quonfig.sdk.Quonfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class GetTest {

  @Test
  @DisplayName("get returns a found value for key")
  void getReturnsAFoundValueForKey() throws Exception {
    Quonfig client = TestSetup.client();
    assertEquals("my-test-value", client.getString("my-test-key", null));
  }

  @Test
  @DisplayName("get returns nil if value not found")
  void getReturnsNilIfValueNotFound() throws Exception {
    Quonfig client = TestSetup.client();
    assertNull(client.getString("my-missing-key", null));
  }

  @Test
  @DisplayName("get returns a default for a missing value if a default is given")
  void getReturnsADefaultForAMissingValueIfADefaultIsGiven() throws Exception {
    Quonfig client = TestSetup.client();
    assertEquals("DEFAULT", client.getString("my-missing-key", "DEFAULT"));
  }

  @Test
  @DisplayName("get ignores a provided default if the key is found")
  void getIgnoresAProvidedDefaultIfTheKeyIsFound() throws Exception {
    Quonfig client = TestSetup.client();
    assertEquals("my-test-value", client.getString("my-test-key", "DEFAULT"));
  }

  @Test
  @DisplayName("get can return a double")
  void getCanReturnADouble() throws Exception {
    Quonfig client = TestSetup.client();
    assertEquals(9.95d, client.getDouble("my-double-key", null));
  }

  @Test
  @DisplayName("get can return a string list")
  void getCanReturnAStringList() throws Exception {
    Quonfig client = TestSetup.client();
    assertEquals(TestSetup.list("a", "b", "c"), client.getStringList("my-string-list-key", null));
  }

  @Test
  @DisplayName("can return a value provided by an environment variable")
  void canReturnAValueProvidedByAnEnvironmentVariable() throws Exception {
    Quonfig client = TestSetup.client();
    assertEquals(
        "c87ba22d8662282abe8a0e4651327b579cb64a454ab0f4c170b45b15f049a221",
        client.getString("prefab.secrets.encryption.key", null));
  }

  @Test
  @DisplayName("can return a value provided by an environment variable after type coercion")
  void canReturnAValueProvidedByAnEnvironmentVariableAfterTypeCoercion() throws Exception {
    Quonfig client = TestSetup.client();
    assertEquals(1234L, client.getLong("provided.a.number", null));
  }

  @Test
  @DisplayName("can decrypt and return a secret value (with decryption key in in env var)")
  void canDecryptAndReturnASecretValueWithDecryptionKeyInInEnvVar() throws Exception {
    Quonfig client = TestSetup.client();
    assertEquals("hello.world", client.getString("a.secret.config", null));
  }

  @Test
  @DisplayName("decrypts a secret whose key config has an environment rule")
  void decryptsASecretWhoseKeyConfigHasAnEnvironmentRule() throws Exception {
    Quonfig client = TestSetup.client();
    assertEquals("hello.world", client.getString("env.scoped.secret", null));
  }

  @Test
  @DisplayName("duration 200 ms")
  void duration200Ms() throws Exception {
    Quonfig client = TestSetup.client();
    java.time.Duration actual = client.getDuration("test.duration.PT0.2S", null);
    assertNotNull(actual, "getDuration returned null");
    assertEquals(200L, actual.toMillis());
    assertEquals(200L, client.getDurationDetails("test.duration.PT0.2S", null).value().toMillis());
  }

  @Test
  @DisplayName("duration 90S")
  void duration90s() throws Exception {
    Quonfig client = TestSetup.client();
    java.time.Duration actual = client.getDuration("test.duration.PT90S", null);
    assertNotNull(actual, "getDuration returned null");
    assertEquals(90000L, actual.toMillis());
    assertEquals(90000L, client.getDurationDetails("test.duration.PT90S", null).value().toMillis());
  }

  @Test
  @DisplayName("duration 30M")
  void duration30m() throws Exception {
    Quonfig client = TestSetup.client();
    java.time.Duration actual = client.getDuration("test.duration.PT30M", null);
    assertNotNull(actual, "getDuration returned null");
    assertEquals(1800000L, actual.toMillis());
    assertEquals(
        1800000L, client.getDurationDetails("test.duration.PT30M", null).value().toMillis());
  }

  @Test
  @DisplayName("duration test.duration.P1DT6H2M1.5S")
  void durationTestDurationP1dt6h2m15s() throws Exception {
    Quonfig client = TestSetup.client();
    java.time.Duration actual = client.getDuration("test.duration.P1DT6H2M1.5S", null);
    assertNotNull(actual, "getDuration returned null");
    assertEquals(108121500L, actual.toMillis());
    assertEquals(
        108121500L,
        client.getDurationDetails("test.duration.P1DT6H2M1.5S", null).value().toMillis());
  }

  @Test
  @DisplayName("duration zero PT0S")
  void durationZeroPt0s() throws Exception {
    Quonfig client = TestSetup.client();
    java.time.Duration actual = client.getDuration("test.duration.PT0S", null);
    assertNotNull(actual, "getDuration returned null");
    assertEquals(0L, actual.toMillis());
    assertEquals(0L, client.getDurationDetails("test.duration.PT0S", null).value().toMillis());
  }

  @Test
  @DisplayName("duration zero P0D")
  void durationZeroP0d() throws Exception {
    Quonfig client = TestSetup.client();
    java.time.Duration actual = client.getDuration("test.duration.P0D", null);
    assertNotNull(actual, "getDuration returned null");
    assertEquals(0L, actual.toMillis());
    assertEquals(0L, client.getDurationDetails("test.duration.P0D", null).value().toMillis());
  }

  @Test
  @DisplayName("duration days only P2D")
  void durationDaysOnlyP2d() throws Exception {
    Quonfig client = TestSetup.client();
    java.time.Duration actual = client.getDuration("test.duration.P2D", null);
    assertNotNull(actual, "getDuration returned null");
    assertEquals(172800000L, actual.toMillis());
    assertEquals(
        172800000L, client.getDurationDetails("test.duration.P2D", null).value().toMillis());
  }

  @Test
  @DisplayName("duration hours only PT1H")
  void durationHoursOnlyPt1h() throws Exception {
    Quonfig client = TestSetup.client();
    java.time.Duration actual = client.getDuration("test.duration.PT1H", null);
    assertNotNull(actual, "getDuration returned null");
    assertEquals(3600000L, actual.toMillis());
    assertEquals(
        3600000L, client.getDurationDetails("test.duration.PT1H", null).value().toMillis());
  }

  @Test
  @DisplayName("duration minutes only PT1M")
  void durationMinutesOnlyPt1m() throws Exception {
    Quonfig client = TestSetup.client();
    java.time.Duration actual = client.getDuration("test.duration.PT1M", null);
    assertNotNull(actual, "getDuration returned null");
    assertEquals(60000L, actual.toMillis());
    assertEquals(60000L, client.getDurationDetails("test.duration.PT1M", null).value().toMillis());
  }

  @Test
  @DisplayName("duration seconds only PT1S")
  void durationSecondsOnlyPt1s() throws Exception {
    Quonfig client = TestSetup.client();
    java.time.Duration actual = client.getDuration("test.duration.PT1S", null);
    assertNotNull(actual, "getDuration returned null");
    assertEquals(1000L, actual.toMillis());
    assertEquals(1000L, client.getDurationDetails("test.duration.PT1S", null).value().toMillis());
  }

  @Test
  @DisplayName("duration leading zero PT05S")
  void durationLeadingZeroPt05s() throws Exception {
    Quonfig client = TestSetup.client();
    java.time.Duration actual = client.getDuration("test.duration.PT05S", null);
    assertNotNull(actual, "getDuration returned null");
    assertEquals(5000L, actual.toMillis());
    assertEquals(5000L, client.getDurationDetails("test.duration.PT05S", null).value().toMillis());
  }

  @Test
  @DisplayName("duration hours and minutes PT1H30M")
  void durationHoursAndMinutesPt1h30m() throws Exception {
    Quonfig client = TestSetup.client();
    java.time.Duration actual = client.getDuration("test.duration.PT1H30M", null);
    assertNotNull(actual, "getDuration returned null");
    assertEquals(5400000L, actual.toMillis());
    assertEquals(
        5400000L, client.getDurationDetails("test.duration.PT1H30M", null).value().toMillis());
  }

  @Test
  @DisplayName("duration days and hours P1DT2H")
  void durationDaysAndHoursP1dt2h() throws Exception {
    Quonfig client = TestSetup.client();
    java.time.Duration actual = client.getDuration("test.duration.P1DT2H", null);
    assertNotNull(actual, "getDuration returned null");
    assertEquals(93600000L, actual.toMillis());
    assertEquals(
        93600000L, client.getDurationDetails("test.duration.P1DT2H", null).value().toMillis());
  }

  @Test
  @DisplayName("duration one millisecond PT0.001S")
  void durationOneMillisecondPt0001s() throws Exception {
    Quonfig client = TestSetup.client();
    java.time.Duration actual = client.getDuration("test.duration.PT0.001S", null);
    assertNotNull(actual, "getDuration returned null");
    assertEquals(1L, actual.toMillis());
    assertEquals(1L, client.getDurationDetails("test.duration.PT0.001S", null).value().toMillis());
  }

  @Test
  @DisplayName("duration magnitude ceiling P36500D")
  void durationMagnitudeCeilingP36500d() throws Exception {
    Quonfig client = TestSetup.client();
    java.time.Duration actual = client.getDuration("test.duration.P36500D", null);
    assertNotNull(actual, "getDuration returned null");
    assertEquals(3153600000000L, actual.toMillis());
    assertEquals(
        3153600000000L,
        client.getDurationDetails("test.duration.P36500D", null).value().toMillis());
  }

  @Test
  @DisplayName("duration rounding PT2.01S")
  void durationRoundingPt201s() throws Exception {
    Quonfig client = TestSetup.client();
    java.time.Duration actual = client.getDuration("test.duration.PT2.01S", null);
    assertNotNull(actual, "getDuration returned null");
    assertEquals(2010L, actual.toMillis());
    assertEquals(
        2010L, client.getDurationDetails("test.duration.PT2.01S", null).value().toMillis());
  }

  @Test
  @DisplayName("duration rounding PT1.005S")
  void durationRoundingPt1005s() throws Exception {
    Quonfig client = TestSetup.client();
    java.time.Duration actual = client.getDuration("test.duration.PT1.005S", null);
    assertNotNull(actual, "getDuration returned null");
    assertEquals(1005L, actual.toMillis());
    assertEquals(
        1005L, client.getDurationDetails("test.duration.PT1.005S", null).value().toMillis());
  }

  @Test
  @DisplayName("duration rounding half up PT0.0005S")
  void durationRoundingHalfUpPt00005s() throws Exception {
    Quonfig client = TestSetup.client();
    java.time.Duration actual = client.getDuration("test.duration.PT0.0005S", null);
    assertNotNull(actual, "getDuration returned null");
    assertEquals(1L, actual.toMillis());
    assertEquals(1L, client.getDurationDetails("test.duration.PT0.0005S", null).value().toMillis());
  }

  @Test
  @DisplayName("duration rounding down PT0.0004S")
  void durationRoundingDownPt00004s() throws Exception {
    Quonfig client = TestSetup.client();
    java.time.Duration actual = client.getDuration("test.duration.PT0.0004S", null);
    assertNotNull(actual, "getDuration returned null");
    assertEquals(0L, actual.toMillis());
    assertEquals(0L, client.getDurationDetails("test.duration.PT0.0004S", null).value().toMillis());
  }

  @Test
  @DisplayName("json test")
  void jsonTest() throws Exception {
    Quonfig client = TestSetup.client();
    assertEquals(TestSetup.map("a", 1L, "b", "c"), client.getJson("test.json", null));
  }

  @Test
  @DisplayName("get returns a native json object (not a stringified payload)")
  void getReturnsANativeJsonObjectNotAStringifiedPayload() throws Exception {
    Quonfig client = TestSetup.client();
    assertEquals(TestSetup.map("a", 1L, "b", "c"), client.getJson("test.json", null));
  }

  @Test
  @DisplayName("list on left side test (1)")
  void listOnLeftSideTest1() throws Exception {
    Quonfig client = TestSetup.client();
    assertEquals(
        "correct",
        client.getString(
            "left.hand.list.test",
            null,
            TestSetup.ctx(
                TestSetup.map(
                    "user",
                    TestSetup.map("name", "james", "aka", TestSetup.list("happy", "sleepy"))))));
  }

  @Test
  @DisplayName("list on left side test (2)")
  void listOnLeftSideTest2() throws Exception {
    Quonfig client = TestSetup.client();
    assertEquals(
        "default",
        client.getString(
            "left.hand.list.test",
            null,
            TestSetup.ctx(
                TestSetup.map(
                    "user", TestSetup.map("name", "james", "aka", TestSetup.list("a", "b"))))));
  }

  @Test
  @DisplayName("list on left side test opposite (1)")
  void listOnLeftSideTestOpposite1() throws Exception {
    Quonfig client = TestSetup.client();
    assertEquals(
        "default",
        client.getString(
            "left.hand.test.opposite",
            null,
            TestSetup.ctx(
                TestSetup.map(
                    "user",
                    TestSetup.map("name", "james", "aka", TestSetup.list("happy", "sleepy"))))));
  }

  @Test
  @DisplayName("list on left side test (3)")
  void listOnLeftSideTest3() throws Exception {
    Quonfig client = TestSetup.client();
    assertEquals(
        "correct",
        client.getString(
            "left.hand.test.opposite",
            null,
            TestSetup.ctx(
                TestSetup.map(
                    "user", TestSetup.map("name", "james", "aka", TestSetup.list("a", "b"))))));
  }

  @Test
  @DisplayName("env-var-provided duration PT1.5S via get")
  void envVarProvidedDurationPt15sViaGet() throws Exception {
    TestSetup.withEnv(
        TestSetup.map("QUONFIG_ITD_DURATION_PT1_5S", "PT1.5S"),
        () -> {
          Quonfig client = TestSetup.client();
          java.time.Duration actual = client.getDuration("provided.duration.PT1.5S", null);
          assertNotNull(actual, "getDuration returned null");
          assertEquals(1500L, actual.toMillis());
          assertEquals(
              1500L,
              client.getDurationDetails("provided.duration.PT1.5S", null).value().toMillis());
        });
  }

  @Test
  @DisplayName("stored malformed duration 30s returns the default")
  void storedMalformedDuration30sReturnsTheDefault() throws Exception {
    Quonfig client = TestSetup.client();
    java.time.Duration actual =
        client.getDuration("test.duration.malformed.30s", java.time.Duration.ofMillis(7000L));
    assertNotNull(actual, "getDuration returned null");
    assertEquals(7000L, actual.toMillis());
    com.quonfig.sdk.EvaluationDetails<java.time.Duration> details =
        client.getDurationDetails(
            "test.duration.malformed.30s", java.time.Duration.ofMillis(7000L));
    assertEquals(7000L, details.value().toMillis());
    assertEquals(com.quonfig.sdk.Reason.ERROR, details.reason());
  }

  @Test
  @DisplayName("stored malformed duration 30s with no default returns nil")
  void storedMalformedDuration30sWithNoDefaultReturnsNil() throws Exception {
    Quonfig client = TestSetup.client();
    assertNull(client.getDuration("test.duration.malformed.30s", null));
  }

  @Test
  @DisplayName("stored malformed duration PT0.5H returns the default")
  void storedMalformedDurationPt05hReturnsTheDefault() throws Exception {
    Quonfig client = TestSetup.client();
    java.time.Duration actual =
        client.getDuration("test.duration.malformed.PT0.5H", java.time.Duration.ofMillis(7000L));
    assertNotNull(actual, "getDuration returned null");
    assertEquals(7000L, actual.toMillis());
    com.quonfig.sdk.EvaluationDetails<java.time.Duration> details =
        client.getDurationDetails(
            "test.duration.malformed.PT0.5H", java.time.Duration.ofMillis(7000L));
    assertEquals(7000L, details.value().toMillis());
    assertEquals(com.quonfig.sdk.Reason.ERROR, details.reason());
  }

  @Test
  @DisplayName("stored malformed duration PT0.5H with no default returns nil")
  void storedMalformedDurationPt05hWithNoDefaultReturnsNil() throws Exception {
    Quonfig client = TestSetup.client();
    assertNull(client.getDuration("test.duration.malformed.PT0.5H", null));
  }

  @Test
  @DisplayName("stored malformed duration P1DT returns the default")
  void storedMalformedDurationP1dtReturnsTheDefault() throws Exception {
    Quonfig client = TestSetup.client();
    java.time.Duration actual =
        client.getDuration("test.duration.malformed.P1DT", java.time.Duration.ofMillis(7000L));
    assertNotNull(actual, "getDuration returned null");
    assertEquals(7000L, actual.toMillis());
    com.quonfig.sdk.EvaluationDetails<java.time.Duration> details =
        client.getDurationDetails(
            "test.duration.malformed.P1DT", java.time.Duration.ofMillis(7000L));
    assertEquals(7000L, details.value().toMillis());
    assertEquals(com.quonfig.sdk.Reason.ERROR, details.reason());
  }

  @Test
  @DisplayName("stored malformed duration P1DT with no default returns nil")
  void storedMalformedDurationP1dtWithNoDefaultReturnsNil() throws Exception {
    Quonfig client = TestSetup.client();
    assertNull(client.getDuration("test.duration.malformed.P1DT", null));
  }

  @Test
  @DisplayName("stored malformed duration garbage returns the default")
  void storedMalformedDurationGarbageReturnsTheDefault() throws Exception {
    Quonfig client = TestSetup.client();
    java.time.Duration actual =
        client.getDuration("test.duration.malformed.garbage", java.time.Duration.ofMillis(7000L));
    assertNotNull(actual, "getDuration returned null");
    assertEquals(7000L, actual.toMillis());
    com.quonfig.sdk.EvaluationDetails<java.time.Duration> details =
        client.getDurationDetails(
            "test.duration.malformed.garbage", java.time.Duration.ofMillis(7000L));
    assertEquals(7000L, details.value().toMillis());
    assertEquals(com.quonfig.sdk.Reason.ERROR, details.reason());
  }

  @Test
  @DisplayName("stored malformed duration garbage with no default returns nil")
  void storedMalformedDurationGarbageWithNoDefaultReturnsNil() throws Exception {
    Quonfig client = TestSetup.client();
    assertNull(client.getDuration("test.duration.malformed.garbage", null));
  }

  @Test
  @DisplayName("stored malformed duration empty returns the default")
  void storedMalformedDurationEmptyReturnsTheDefault() throws Exception {
    Quonfig client = TestSetup.client();
    java.time.Duration actual =
        client.getDuration("test.duration.malformed.empty", java.time.Duration.ofMillis(7000L));
    assertNotNull(actual, "getDuration returned null");
    assertEquals(7000L, actual.toMillis());
    com.quonfig.sdk.EvaluationDetails<java.time.Duration> details =
        client.getDurationDetails(
            "test.duration.malformed.empty", java.time.Duration.ofMillis(7000L));
    assertEquals(7000L, details.value().toMillis());
    assertEquals(com.quonfig.sdk.Reason.ERROR, details.reason());
  }

  @Test
  @DisplayName("stored malformed duration empty with no default returns nil")
  void storedMalformedDurationEmptyWithNoDefaultReturnsNil() throws Exception {
    Quonfig client = TestSetup.client();
    assertNull(client.getDuration("test.duration.malformed.empty", null));
  }

  @Test
  @DisplayName("env-var-provided malformed duration 30s returns the default")
  void envVarProvidedMalformedDuration30sReturnsTheDefault() throws Exception {
    TestSetup.withEnv(
        TestSetup.map("QUONFIG_ITD_DURATION_30S", "30s"),
        () -> {
          Quonfig client = TestSetup.client();
          java.time.Duration actual =
              client.getDuration(
                  "provided.duration.malformed.30s", java.time.Duration.ofMillis(7000L));
          assertNotNull(actual, "getDuration returned null");
          assertEquals(7000L, actual.toMillis());
          com.quonfig.sdk.EvaluationDetails<java.time.Duration> details =
              client.getDurationDetails(
                  "provided.duration.malformed.30s", java.time.Duration.ofMillis(7000L));
          assertEquals(7000L, details.value().toMillis());
          assertEquals(com.quonfig.sdk.Reason.ERROR, details.reason());
        });
  }

  @Test
  @DisplayName("env-var-provided malformed duration 30s with no default returns nil")
  void envVarProvidedMalformedDuration30sWithNoDefaultReturnsNil() throws Exception {
    TestSetup.withEnv(
        TestSetup.map("QUONFIG_ITD_DURATION_30S", "30s"),
        () -> {
          Quonfig client = TestSetup.client();
          assertNull(client.getDuration("provided.duration.malformed.30s", null));
        });
  }

  @Test
  @DisplayName("env-var-provided malformed duration PT0.5H returns the default")
  void envVarProvidedMalformedDurationPt05hReturnsTheDefault() throws Exception {
    TestSetup.withEnv(
        TestSetup.map("QUONFIG_ITD_DURATION_PT0_5H", "PT0.5H"),
        () -> {
          Quonfig client = TestSetup.client();
          java.time.Duration actual =
              client.getDuration(
                  "provided.duration.malformed.PT0.5H", java.time.Duration.ofMillis(7000L));
          assertNotNull(actual, "getDuration returned null");
          assertEquals(7000L, actual.toMillis());
          com.quonfig.sdk.EvaluationDetails<java.time.Duration> details =
              client.getDurationDetails(
                  "provided.duration.malformed.PT0.5H", java.time.Duration.ofMillis(7000L));
          assertEquals(7000L, details.value().toMillis());
          assertEquals(com.quonfig.sdk.Reason.ERROR, details.reason());
        });
  }

  @Test
  @DisplayName("env-var-provided malformed duration PT0.5H with no default returns nil")
  void envVarProvidedMalformedDurationPt05hWithNoDefaultReturnsNil() throws Exception {
    TestSetup.withEnv(
        TestSetup.map("QUONFIG_ITD_DURATION_PT0_5H", "PT0.5H"),
        () -> {
          Quonfig client = TestSetup.client();
          assertNull(client.getDuration("provided.duration.malformed.PT0.5H", null));
        });
  }

  @Test
  @DisplayName("env-var-provided malformed duration P1DT returns the default")
  void envVarProvidedMalformedDurationP1dtReturnsTheDefault() throws Exception {
    TestSetup.withEnv(
        TestSetup.map("QUONFIG_ITD_DURATION_P1DT", "P1DT"),
        () -> {
          Quonfig client = TestSetup.client();
          java.time.Duration actual =
              client.getDuration(
                  "provided.duration.malformed.P1DT", java.time.Duration.ofMillis(7000L));
          assertNotNull(actual, "getDuration returned null");
          assertEquals(7000L, actual.toMillis());
          com.quonfig.sdk.EvaluationDetails<java.time.Duration> details =
              client.getDurationDetails(
                  "provided.duration.malformed.P1DT", java.time.Duration.ofMillis(7000L));
          assertEquals(7000L, details.value().toMillis());
          assertEquals(com.quonfig.sdk.Reason.ERROR, details.reason());
        });
  }

  @Test
  @DisplayName("env-var-provided malformed duration P1DT with no default returns nil")
  void envVarProvidedMalformedDurationP1dtWithNoDefaultReturnsNil() throws Exception {
    TestSetup.withEnv(
        TestSetup.map("QUONFIG_ITD_DURATION_P1DT", "P1DT"),
        () -> {
          Quonfig client = TestSetup.client();
          assertNull(client.getDuration("provided.duration.malformed.P1DT", null));
        });
  }

  @Test
  @DisplayName("env-var-provided malformed duration garbage returns the default")
  void envVarProvidedMalformedDurationGarbageReturnsTheDefault() throws Exception {
    TestSetup.withEnv(
        TestSetup.map("QUONFIG_ITD_DURATION_GARBAGE", "garbage"),
        () -> {
          Quonfig client = TestSetup.client();
          java.time.Duration actual =
              client.getDuration(
                  "provided.duration.malformed.garbage", java.time.Duration.ofMillis(7000L));
          assertNotNull(actual, "getDuration returned null");
          assertEquals(7000L, actual.toMillis());
          com.quonfig.sdk.EvaluationDetails<java.time.Duration> details =
              client.getDurationDetails(
                  "provided.duration.malformed.garbage", java.time.Duration.ofMillis(7000L));
          assertEquals(7000L, details.value().toMillis());
          assertEquals(com.quonfig.sdk.Reason.ERROR, details.reason());
        });
  }

  @Test
  @DisplayName("env-var-provided malformed duration garbage with no default returns nil")
  void envVarProvidedMalformedDurationGarbageWithNoDefaultReturnsNil() throws Exception {
    TestSetup.withEnv(
        TestSetup.map("QUONFIG_ITD_DURATION_GARBAGE", "garbage"),
        () -> {
          Quonfig client = TestSetup.client();
          assertNull(client.getDuration("provided.duration.malformed.garbage", null));
        });
  }

  @Test
  @DisplayName("STRING getter on stored int returns the default")
  void stringGetterOnStoredIntReturnsTheDefault() throws Exception {
    Quonfig client = TestSetup.client();
    assertEquals("DEFAULT", client.getString("getter.contract.int", "DEFAULT"));
  }

  @Test
  @DisplayName("STRING getter on stored int with no default returns nil")
  void stringGetterOnStoredIntWithNoDefaultReturnsNil() throws Exception {
    Quonfig client = TestSetup.client();
    assertNull(client.getString("getter.contract.int", null));
  }

  @Test
  @DisplayName("STRING getter on stored double returns the default")
  void stringGetterOnStoredDoubleReturnsTheDefault() throws Exception {
    Quonfig client = TestSetup.client();
    assertEquals("DEFAULT", client.getString("getter.contract.double", "DEFAULT"));
  }

  @Test
  @DisplayName("STRING getter on stored double with no default returns nil")
  void stringGetterOnStoredDoubleWithNoDefaultReturnsNil() throws Exception {
    Quonfig client = TestSetup.client();
    assertNull(client.getString("getter.contract.double", null));
  }

  @Test
  @DisplayName("STRING getter on stored bool returns the default")
  void stringGetterOnStoredBoolReturnsTheDefault() throws Exception {
    Quonfig client = TestSetup.client();
    assertEquals("DEFAULT", client.getString("getter.contract.bool", "DEFAULT"));
  }

  @Test
  @DisplayName("STRING getter on stored bool with no default returns nil")
  void stringGetterOnStoredBoolWithNoDefaultReturnsNil() throws Exception {
    Quonfig client = TestSetup.client();
    assertNull(client.getString("getter.contract.bool", null));
  }

  @Test
  @DisplayName("STRING getter on stored string_list returns the default")
  void stringGetterOnStoredStringListReturnsTheDefault() throws Exception {
    Quonfig client = TestSetup.client();
    assertEquals("DEFAULT", client.getString("getter.contract.string_list", "DEFAULT"));
  }

  @Test
  @DisplayName("STRING getter on stored string_list with no default returns nil")
  void stringGetterOnStoredStringListWithNoDefaultReturnsNil() throws Exception {
    Quonfig client = TestSetup.client();
    assertNull(client.getString("getter.contract.string_list", null));
  }

  @Test
  @DisplayName("STRING getter on stored json returns the default")
  void stringGetterOnStoredJsonReturnsTheDefault() throws Exception {
    Quonfig client = TestSetup.client();
    assertEquals("DEFAULT", client.getString("getter.contract.json", "DEFAULT"));
  }

  @Test
  @DisplayName("STRING getter on stored json with no default returns nil")
  void stringGetterOnStoredJsonWithNoDefaultReturnsNil() throws Exception {
    Quonfig client = TestSetup.client();
    assertNull(client.getString("getter.contract.json", null));
  }

  @Test
  @DisplayName("STRING getter on stored duration returns the default")
  void stringGetterOnStoredDurationReturnsTheDefault() throws Exception {
    Quonfig client = TestSetup.client();
    assertEquals("DEFAULT", client.getString("getter.contract.duration", "DEFAULT"));
  }

  @Test
  @DisplayName("STRING getter on stored duration with no default returns nil")
  void stringGetterOnStoredDurationWithNoDefaultReturnsNil() throws Exception {
    Quonfig client = TestSetup.client();
    assertNull(client.getString("getter.contract.duration", null));
  }

  @Test
  @DisplayName("INT getter on stored string returns the default")
  void intGetterOnStoredStringReturnsTheDefault() throws Exception {
    Quonfig client = TestSetup.client();
    assertEquals(7L, client.getLong("getter.contract.string", 7L));
  }

  @Test
  @DisplayName("INT getter on stored string with no default returns nil")
  void intGetterOnStoredStringWithNoDefaultReturnsNil() throws Exception {
    Quonfig client = TestSetup.client();
    assertNull(client.getLong("getter.contract.string", null));
  }

  @Test
  @DisplayName("INT getter on stored double 3.9 returns the default")
  void intGetterOnStoredDouble39ReturnsTheDefault() throws Exception {
    Quonfig client = TestSetup.client();
    assertEquals(7L, client.getLong("getter.contract.double", 7L));
  }

  @Test
  @DisplayName("INT getter on stored double 3.9 with no default returns nil")
  void intGetterOnStoredDouble39WithNoDefaultReturnsNil() throws Exception {
    Quonfig client = TestSetup.client();
    assertNull(client.getLong("getter.contract.double", null));
  }

  @Test
  @DisplayName("INT getter on stored bool returns the default")
  void intGetterOnStoredBoolReturnsTheDefault() throws Exception {
    Quonfig client = TestSetup.client();
    assertEquals(7L, client.getLong("getter.contract.bool", 7L));
  }

  @Test
  @DisplayName("INT getter on stored bool with no default returns nil")
  void intGetterOnStoredBoolWithNoDefaultReturnsNil() throws Exception {
    Quonfig client = TestSetup.client();
    assertNull(client.getLong("getter.contract.bool", null));
  }

  @Test
  @DisplayName("INT getter on stored string_list returns the default")
  void intGetterOnStoredStringListReturnsTheDefault() throws Exception {
    Quonfig client = TestSetup.client();
    assertEquals(7L, client.getLong("getter.contract.string_list", 7L));
  }

  @Test
  @DisplayName("INT getter on stored string_list with no default returns nil")
  void intGetterOnStoredStringListWithNoDefaultReturnsNil() throws Exception {
    Quonfig client = TestSetup.client();
    assertNull(client.getLong("getter.contract.string_list", null));
  }

  @Test
  @DisplayName("INT getter on stored json returns the default")
  void intGetterOnStoredJsonReturnsTheDefault() throws Exception {
    Quonfig client = TestSetup.client();
    assertEquals(7L, client.getLong("getter.contract.json", 7L));
  }

  @Test
  @DisplayName("INT getter on stored json with no default returns nil")
  void intGetterOnStoredJsonWithNoDefaultReturnsNil() throws Exception {
    Quonfig client = TestSetup.client();
    assertNull(client.getLong("getter.contract.json", null));
  }

  @Test
  @DisplayName("INT getter on stored duration returns the default")
  void intGetterOnStoredDurationReturnsTheDefault() throws Exception {
    Quonfig client = TestSetup.client();
    assertEquals(7L, client.getLong("getter.contract.duration", 7L));
  }

  @Test
  @DisplayName("INT getter on stored duration with no default returns nil")
  void intGetterOnStoredDurationWithNoDefaultReturnsNil() throws Exception {
    Quonfig client = TestSetup.client();
    assertNull(client.getLong("getter.contract.duration", null));
  }

  @Test
  @DisplayName("DOUBLE getter on stored string returns the default")
  void doubleGetterOnStoredStringReturnsTheDefault() throws Exception {
    Quonfig client = TestSetup.client();
    assertEquals(7.5d, client.getDouble("getter.contract.string", 7.5d));
  }

  @Test
  @DisplayName("DOUBLE getter on stored string with no default returns nil")
  void doubleGetterOnStoredStringWithNoDefaultReturnsNil() throws Exception {
    Quonfig client = TestSetup.client();
    assertNull(client.getDouble("getter.contract.string", null));
  }

  @Test
  @DisplayName("DOUBLE getter on stored bool returns the default")
  void doubleGetterOnStoredBoolReturnsTheDefault() throws Exception {
    Quonfig client = TestSetup.client();
    assertEquals(7.5d, client.getDouble("getter.contract.bool", 7.5d));
  }

  @Test
  @DisplayName("DOUBLE getter on stored bool with no default returns nil")
  void doubleGetterOnStoredBoolWithNoDefaultReturnsNil() throws Exception {
    Quonfig client = TestSetup.client();
    assertNull(client.getDouble("getter.contract.bool", null));
  }

  @Test
  @DisplayName("DOUBLE getter on stored string_list returns the default")
  void doubleGetterOnStoredStringListReturnsTheDefault() throws Exception {
    Quonfig client = TestSetup.client();
    assertEquals(7.5d, client.getDouble("getter.contract.string_list", 7.5d));
  }

  @Test
  @DisplayName("DOUBLE getter on stored string_list with no default returns nil")
  void doubleGetterOnStoredStringListWithNoDefaultReturnsNil() throws Exception {
    Quonfig client = TestSetup.client();
    assertNull(client.getDouble("getter.contract.string_list", null));
  }

  @Test
  @DisplayName("DOUBLE getter on stored json returns the default")
  void doubleGetterOnStoredJsonReturnsTheDefault() throws Exception {
    Quonfig client = TestSetup.client();
    assertEquals(7.5d, client.getDouble("getter.contract.json", 7.5d));
  }

  @Test
  @DisplayName("DOUBLE getter on stored json with no default returns nil")
  void doubleGetterOnStoredJsonWithNoDefaultReturnsNil() throws Exception {
    Quonfig client = TestSetup.client();
    assertNull(client.getDouble("getter.contract.json", null));
  }

  @Test
  @DisplayName("DOUBLE getter on stored duration returns the default")
  void doubleGetterOnStoredDurationReturnsTheDefault() throws Exception {
    Quonfig client = TestSetup.client();
    assertEquals(7.5d, client.getDouble("getter.contract.duration", 7.5d));
  }

  @Test
  @DisplayName("DOUBLE getter on stored duration with no default returns nil")
  void doubleGetterOnStoredDurationWithNoDefaultReturnsNil() throws Exception {
    Quonfig client = TestSetup.client();
    assertNull(client.getDouble("getter.contract.duration", null));
  }

  @Test
  @DisplayName("BOOLEAN getter on stored string returns the default")
  void booleanGetterOnStoredStringReturnsTheDefault() throws Exception {
    Quonfig client = TestSetup.client();
    assertEquals(false, client.getBool("getter.contract.string", Boolean.FALSE));
  }

  @Test
  @DisplayName("BOOLEAN getter on stored string with no default returns nil")
  void booleanGetterOnStoredStringWithNoDefaultReturnsNil() throws Exception {
    Quonfig client = TestSetup.client();
    assertNull(client.getBool("getter.contract.string", null));
  }

  @Test
  @DisplayName("BOOLEAN getter on stored int returns the default")
  void booleanGetterOnStoredIntReturnsTheDefault() throws Exception {
    Quonfig client = TestSetup.client();
    assertEquals(false, client.getBool("getter.contract.int", Boolean.FALSE));
  }

  @Test
  @DisplayName("BOOLEAN getter on stored int with no default returns nil")
  void booleanGetterOnStoredIntWithNoDefaultReturnsNil() throws Exception {
    Quonfig client = TestSetup.client();
    assertNull(client.getBool("getter.contract.int", null));
  }

  @Test
  @DisplayName("BOOLEAN getter on stored double returns the default")
  void booleanGetterOnStoredDoubleReturnsTheDefault() throws Exception {
    Quonfig client = TestSetup.client();
    assertEquals(false, client.getBool("getter.contract.double", Boolean.FALSE));
  }

  @Test
  @DisplayName("BOOLEAN getter on stored double with no default returns nil")
  void booleanGetterOnStoredDoubleWithNoDefaultReturnsNil() throws Exception {
    Quonfig client = TestSetup.client();
    assertNull(client.getBool("getter.contract.double", null));
  }

  @Test
  @DisplayName("BOOLEAN getter on stored string_list returns the default")
  void booleanGetterOnStoredStringListReturnsTheDefault() throws Exception {
    Quonfig client = TestSetup.client();
    assertEquals(false, client.getBool("getter.contract.string_list", Boolean.FALSE));
  }

  @Test
  @DisplayName("BOOLEAN getter on stored string_list with no default returns nil")
  void booleanGetterOnStoredStringListWithNoDefaultReturnsNil() throws Exception {
    Quonfig client = TestSetup.client();
    assertNull(client.getBool("getter.contract.string_list", null));
  }

  @Test
  @DisplayName("BOOLEAN getter on stored json returns the default")
  void booleanGetterOnStoredJsonReturnsTheDefault() throws Exception {
    Quonfig client = TestSetup.client();
    assertEquals(false, client.getBool("getter.contract.json", Boolean.FALSE));
  }

  @Test
  @DisplayName("BOOLEAN getter on stored json with no default returns nil")
  void booleanGetterOnStoredJsonWithNoDefaultReturnsNil() throws Exception {
    Quonfig client = TestSetup.client();
    assertNull(client.getBool("getter.contract.json", null));
  }

  @Test
  @DisplayName("BOOLEAN getter on stored duration returns the default")
  void booleanGetterOnStoredDurationReturnsTheDefault() throws Exception {
    Quonfig client = TestSetup.client();
    assertEquals(false, client.getBool("getter.contract.duration", Boolean.FALSE));
  }

  @Test
  @DisplayName("BOOLEAN getter on stored duration with no default returns nil")
  void booleanGetterOnStoredDurationWithNoDefaultReturnsNil() throws Exception {
    Quonfig client = TestSetup.client();
    assertNull(client.getBool("getter.contract.duration", null));
  }

  @Test
  @DisplayName("STRING_LIST getter on stored string returns the default")
  void stringListGetterOnStoredStringReturnsTheDefault() throws Exception {
    Quonfig client = TestSetup.client();
    assertEquals(
        TestSetup.list("DEFAULT"),
        client.getStringList("getter.contract.string", java.util.List.of("DEFAULT")));
  }

  @Test
  @DisplayName("STRING_LIST getter on stored string with no default returns nil")
  void stringListGetterOnStoredStringWithNoDefaultReturnsNil() throws Exception {
    Quonfig client = TestSetup.client();
    assertNull(client.getStringList("getter.contract.string", null));
  }

  @Test
  @DisplayName("STRING_LIST getter on stored int returns the default")
  void stringListGetterOnStoredIntReturnsTheDefault() throws Exception {
    Quonfig client = TestSetup.client();
    assertEquals(
        TestSetup.list("DEFAULT"),
        client.getStringList("getter.contract.int", java.util.List.of("DEFAULT")));
  }

  @Test
  @DisplayName("STRING_LIST getter on stored int with no default returns nil")
  void stringListGetterOnStoredIntWithNoDefaultReturnsNil() throws Exception {
    Quonfig client = TestSetup.client();
    assertNull(client.getStringList("getter.contract.int", null));
  }

  @Test
  @DisplayName("STRING_LIST getter on stored double returns the default")
  void stringListGetterOnStoredDoubleReturnsTheDefault() throws Exception {
    Quonfig client = TestSetup.client();
    assertEquals(
        TestSetup.list("DEFAULT"),
        client.getStringList("getter.contract.double", java.util.List.of("DEFAULT")));
  }

  @Test
  @DisplayName("STRING_LIST getter on stored double with no default returns nil")
  void stringListGetterOnStoredDoubleWithNoDefaultReturnsNil() throws Exception {
    Quonfig client = TestSetup.client();
    assertNull(client.getStringList("getter.contract.double", null));
  }

  @Test
  @DisplayName("STRING_LIST getter on stored bool returns the default")
  void stringListGetterOnStoredBoolReturnsTheDefault() throws Exception {
    Quonfig client = TestSetup.client();
    assertEquals(
        TestSetup.list("DEFAULT"),
        client.getStringList("getter.contract.bool", java.util.List.of("DEFAULT")));
  }

  @Test
  @DisplayName("STRING_LIST getter on stored bool with no default returns nil")
  void stringListGetterOnStoredBoolWithNoDefaultReturnsNil() throws Exception {
    Quonfig client = TestSetup.client();
    assertNull(client.getStringList("getter.contract.bool", null));
  }

  @Test
  @DisplayName("STRING_LIST getter on stored json returns the default")
  void stringListGetterOnStoredJsonReturnsTheDefault() throws Exception {
    Quonfig client = TestSetup.client();
    assertEquals(
        TestSetup.list("DEFAULT"),
        client.getStringList("getter.contract.json", java.util.List.of("DEFAULT")));
  }

  @Test
  @DisplayName("STRING_LIST getter on stored json with no default returns nil")
  void stringListGetterOnStoredJsonWithNoDefaultReturnsNil() throws Exception {
    Quonfig client = TestSetup.client();
    assertNull(client.getStringList("getter.contract.json", null));
  }

  @Test
  @DisplayName("STRING_LIST getter on stored duration returns the default")
  void stringListGetterOnStoredDurationReturnsTheDefault() throws Exception {
    Quonfig client = TestSetup.client();
    assertEquals(
        TestSetup.list("DEFAULT"),
        client.getStringList("getter.contract.duration", java.util.List.of("DEFAULT")));
  }

  @Test
  @DisplayName("STRING_LIST getter on stored duration with no default returns nil")
  void stringListGetterOnStoredDurationWithNoDefaultReturnsNil() throws Exception {
    Quonfig client = TestSetup.client();
    assertNull(client.getStringList("getter.contract.duration", null));
  }

  @Test
  @DisplayName("JSON getter on stored string returns the default")
  void jsonGetterOnStoredStringReturnsTheDefault() throws Exception {
    Quonfig client = TestSetup.client();
    assertEquals(
        TestSetup.map("default", true),
        client.getJson("getter.contract.string", TestSetup.map("default", true)));
  }

  @Test
  @DisplayName("JSON getter on stored string with no default returns nil")
  void jsonGetterOnStoredStringWithNoDefaultReturnsNil() throws Exception {
    Quonfig client = TestSetup.client();
    assertNull(client.getJson("getter.contract.string", null));
  }

  @Test
  @DisplayName("JSON getter on stored int returns the default")
  void jsonGetterOnStoredIntReturnsTheDefault() throws Exception {
    Quonfig client = TestSetup.client();
    assertEquals(
        TestSetup.map("default", true),
        client.getJson("getter.contract.int", TestSetup.map("default", true)));
  }

  @Test
  @DisplayName("JSON getter on stored int with no default returns nil")
  void jsonGetterOnStoredIntWithNoDefaultReturnsNil() throws Exception {
    Quonfig client = TestSetup.client();
    assertNull(client.getJson("getter.contract.int", null));
  }

  @Test
  @DisplayName("JSON getter on stored double returns the default")
  void jsonGetterOnStoredDoubleReturnsTheDefault() throws Exception {
    Quonfig client = TestSetup.client();
    assertEquals(
        TestSetup.map("default", true),
        client.getJson("getter.contract.double", TestSetup.map("default", true)));
  }

  @Test
  @DisplayName("JSON getter on stored double with no default returns nil")
  void jsonGetterOnStoredDoubleWithNoDefaultReturnsNil() throws Exception {
    Quonfig client = TestSetup.client();
    assertNull(client.getJson("getter.contract.double", null));
  }

  @Test
  @DisplayName("JSON getter on stored bool returns the default")
  void jsonGetterOnStoredBoolReturnsTheDefault() throws Exception {
    Quonfig client = TestSetup.client();
    assertEquals(
        TestSetup.map("default", true),
        client.getJson("getter.contract.bool", TestSetup.map("default", true)));
  }

  @Test
  @DisplayName("JSON getter on stored bool with no default returns nil")
  void jsonGetterOnStoredBoolWithNoDefaultReturnsNil() throws Exception {
    Quonfig client = TestSetup.client();
    assertNull(client.getJson("getter.contract.bool", null));
  }

  @Test
  @DisplayName("JSON getter on stored string_list returns the default")
  void jsonGetterOnStoredStringListReturnsTheDefault() throws Exception {
    Quonfig client = TestSetup.client();
    assertEquals(
        TestSetup.map("default", true),
        client.getJson("getter.contract.string_list", TestSetup.map("default", true)));
  }

  @Test
  @DisplayName("JSON getter on stored string_list with no default returns nil")
  void jsonGetterOnStoredStringListWithNoDefaultReturnsNil() throws Exception {
    Quonfig client = TestSetup.client();
    assertNull(client.getJson("getter.contract.string_list", null));
  }

  @Test
  @DisplayName("JSON getter on stored duration returns the default")
  void jsonGetterOnStoredDurationReturnsTheDefault() throws Exception {
    Quonfig client = TestSetup.client();
    assertEquals(
        TestSetup.map("default", true),
        client.getJson("getter.contract.duration", TestSetup.map("default", true)));
  }

  @Test
  @DisplayName("JSON getter on stored duration with no default returns nil")
  void jsonGetterOnStoredDurationWithNoDefaultReturnsNil() throws Exception {
    Quonfig client = TestSetup.client();
    assertNull(client.getJson("getter.contract.duration", null));
  }

  @Test
  @DisplayName("DURATION getter on stored string returns the default")
  void durationGetterOnStoredStringReturnsTheDefault() throws Exception {
    Quonfig client = TestSetup.client();
    java.time.Duration actual =
        client.getDuration("getter.contract.string", java.time.Duration.ofMillis(7000L));
    assertNotNull(actual, "getDuration returned null");
    assertEquals(7000L, actual.toMillis());
    com.quonfig.sdk.EvaluationDetails<java.time.Duration> details =
        client.getDurationDetails("getter.contract.string", java.time.Duration.ofMillis(7000L));
    assertEquals(7000L, details.value().toMillis());
    assertEquals(com.quonfig.sdk.Reason.ERROR, details.reason());
  }

  @Test
  @DisplayName("DURATION getter on stored string with no default returns nil")
  void durationGetterOnStoredStringWithNoDefaultReturnsNil() throws Exception {
    Quonfig client = TestSetup.client();
    assertNull(client.getDuration("getter.contract.string", null));
  }

  @Test
  @DisplayName("DURATION getter on stored int returns the default")
  void durationGetterOnStoredIntReturnsTheDefault() throws Exception {
    Quonfig client = TestSetup.client();
    java.time.Duration actual =
        client.getDuration("getter.contract.int", java.time.Duration.ofMillis(7000L));
    assertNotNull(actual, "getDuration returned null");
    assertEquals(7000L, actual.toMillis());
    com.quonfig.sdk.EvaluationDetails<java.time.Duration> details =
        client.getDurationDetails("getter.contract.int", java.time.Duration.ofMillis(7000L));
    assertEquals(7000L, details.value().toMillis());
    assertEquals(com.quonfig.sdk.Reason.ERROR, details.reason());
  }

  @Test
  @DisplayName("DURATION getter on stored int with no default returns nil")
  void durationGetterOnStoredIntWithNoDefaultReturnsNil() throws Exception {
    Quonfig client = TestSetup.client();
    assertNull(client.getDuration("getter.contract.int", null));
  }

  @Test
  @DisplayName("DURATION getter on stored double returns the default")
  void durationGetterOnStoredDoubleReturnsTheDefault() throws Exception {
    Quonfig client = TestSetup.client();
    java.time.Duration actual =
        client.getDuration("getter.contract.double", java.time.Duration.ofMillis(7000L));
    assertNotNull(actual, "getDuration returned null");
    assertEquals(7000L, actual.toMillis());
    com.quonfig.sdk.EvaluationDetails<java.time.Duration> details =
        client.getDurationDetails("getter.contract.double", java.time.Duration.ofMillis(7000L));
    assertEquals(7000L, details.value().toMillis());
    assertEquals(com.quonfig.sdk.Reason.ERROR, details.reason());
  }

  @Test
  @DisplayName("DURATION getter on stored double with no default returns nil")
  void durationGetterOnStoredDoubleWithNoDefaultReturnsNil() throws Exception {
    Quonfig client = TestSetup.client();
    assertNull(client.getDuration("getter.contract.double", null));
  }

  @Test
  @DisplayName("DURATION getter on stored bool returns the default")
  void durationGetterOnStoredBoolReturnsTheDefault() throws Exception {
    Quonfig client = TestSetup.client();
    java.time.Duration actual =
        client.getDuration("getter.contract.bool", java.time.Duration.ofMillis(7000L));
    assertNotNull(actual, "getDuration returned null");
    assertEquals(7000L, actual.toMillis());
    com.quonfig.sdk.EvaluationDetails<java.time.Duration> details =
        client.getDurationDetails("getter.contract.bool", java.time.Duration.ofMillis(7000L));
    assertEquals(7000L, details.value().toMillis());
    assertEquals(com.quonfig.sdk.Reason.ERROR, details.reason());
  }

  @Test
  @DisplayName("DURATION getter on stored bool with no default returns nil")
  void durationGetterOnStoredBoolWithNoDefaultReturnsNil() throws Exception {
    Quonfig client = TestSetup.client();
    assertNull(client.getDuration("getter.contract.bool", null));
  }

  @Test
  @DisplayName("DURATION getter on stored string_list returns the default")
  void durationGetterOnStoredStringListReturnsTheDefault() throws Exception {
    Quonfig client = TestSetup.client();
    java.time.Duration actual =
        client.getDuration("getter.contract.string_list", java.time.Duration.ofMillis(7000L));
    assertNotNull(actual, "getDuration returned null");
    assertEquals(7000L, actual.toMillis());
    com.quonfig.sdk.EvaluationDetails<java.time.Duration> details =
        client.getDurationDetails(
            "getter.contract.string_list", java.time.Duration.ofMillis(7000L));
    assertEquals(7000L, details.value().toMillis());
    assertEquals(com.quonfig.sdk.Reason.ERROR, details.reason());
  }

  @Test
  @DisplayName("DURATION getter on stored string_list with no default returns nil")
  void durationGetterOnStoredStringListWithNoDefaultReturnsNil() throws Exception {
    Quonfig client = TestSetup.client();
    assertNull(client.getDuration("getter.contract.string_list", null));
  }

  @Test
  @DisplayName("DURATION getter on stored json returns the default")
  void durationGetterOnStoredJsonReturnsTheDefault() throws Exception {
    Quonfig client = TestSetup.client();
    java.time.Duration actual =
        client.getDuration("getter.contract.json", java.time.Duration.ofMillis(7000L));
    assertNotNull(actual, "getDuration returned null");
    assertEquals(7000L, actual.toMillis());
    com.quonfig.sdk.EvaluationDetails<java.time.Duration> details =
        client.getDurationDetails("getter.contract.json", java.time.Duration.ofMillis(7000L));
    assertEquals(7000L, details.value().toMillis());
    assertEquals(com.quonfig.sdk.Reason.ERROR, details.reason());
  }

  @Test
  @DisplayName("DURATION getter on stored json with no default returns nil")
  void durationGetterOnStoredJsonWithNoDefaultReturnsNil() throws Exception {
    Quonfig client = TestSetup.client();
    assertNull(client.getDuration("getter.contract.json", null));
  }

  @Test
  @DisplayName("BOOLEAN getter on stored string \"false\" returns the default")
  void booleanGetterOnStoredStringFalseReturnsTheDefault() throws Exception {
    Quonfig client = TestSetup.client();
    assertEquals(false, client.getBool("getter.contract.string.false", Boolean.FALSE));
  }

  @Test
  @DisplayName("BOOLEAN getter on stored string \"false\" with no default returns nil")
  void booleanGetterOnStoredStringFalseWithNoDefaultReturnsNil() throws Exception {
    Quonfig client = TestSetup.client();
    assertNull(client.getBool("getter.contract.string.false", null));
  }

  @Test
  @DisplayName("BOOLEAN getter on stored string \"false\" returns a true default")
  void booleanGetterOnStoredStringFalseReturnsATrueDefault() throws Exception {
    Quonfig client = TestSetup.client();
    assertEquals(true, client.getBool("getter.contract.string.false", Boolean.TRUE));
  }

  @Test
  @DisplayName("BOOLEAN getter on stored string \"true\" returns the default")
  void booleanGetterOnStoredStringTrueReturnsTheDefault() throws Exception {
    Quonfig client = TestSetup.client();
    assertEquals(false, client.getBool("getter.contract.string.true", Boolean.FALSE));
  }

  @Test
  @DisplayName("BOOLEAN getter on stored string \"true\" with no default returns nil")
  void booleanGetterOnStoredStringTrueWithNoDefaultReturnsNil() throws Exception {
    Quonfig client = TestSetup.client();
    assertNull(client.getBool("getter.contract.string.true", null));
  }

  @Test
  @DisplayName("INT getter on stored string \"3\" returns the default")
  void intGetterOnStoredString3ReturnsTheDefault() throws Exception {
    Quonfig client = TestSetup.client();
    assertEquals(7L, client.getLong("getter.contract.string.number", 7L));
  }

  @Test
  @DisplayName("INT getter on stored string \"3\" with no default returns nil")
  void intGetterOnStoredString3WithNoDefaultReturnsNil() throws Exception {
    Quonfig client = TestSetup.client();
    assertNull(client.getLong("getter.contract.string.number", null));
  }

  @Test
  @DisplayName("DOUBLE getter on stored string \"3\" returns the default")
  void doubleGetterOnStoredString3ReturnsTheDefault() throws Exception {
    Quonfig client = TestSetup.client();
    assertEquals(7.5d, client.getDouble("getter.contract.string.number", 7.5d));
  }

  @Test
  @DisplayName("DOUBLE getter on stored string \"3\" with no default returns nil")
  void doubleGetterOnStoredString3WithNoDefaultReturnsNil() throws Exception {
    Quonfig client = TestSetup.client();
    assertNull(client.getDouble("getter.contract.string.number", null));
  }

  @Test
  @DisplayName("DURATION getter on stored string \"PT1S\" returns the default")
  void durationGetterOnStoredStringPt1sReturnsTheDefault() throws Exception {
    Quonfig client = TestSetup.client();
    java.time.Duration actual =
        client.getDuration("getter.contract.string.duration", java.time.Duration.ofMillis(7000L));
    assertNotNull(actual, "getDuration returned null");
    assertEquals(7000L, actual.toMillis());
    com.quonfig.sdk.EvaluationDetails<java.time.Duration> details =
        client.getDurationDetails(
            "getter.contract.string.duration", java.time.Duration.ofMillis(7000L));
    assertEquals(7000L, details.value().toMillis());
    assertEquals(com.quonfig.sdk.Reason.ERROR, details.reason());
  }

  @Test
  @DisplayName("DURATION getter on stored string \"PT1S\" with no default returns nil")
  void durationGetterOnStoredStringPt1sWithNoDefaultReturnsNil() throws Exception {
    Quonfig client = TestSetup.client();
    assertNull(client.getDuration("getter.contract.string.duration", null));
  }

  @Test
  @DisplayName("DOUBLE get on stored int 30 returns 30.0")
  void doubleGetOnStoredInt30Returns300() throws Exception {
    Quonfig client = TestSetup.client();
    assertEquals(30.0d, client.getDouble("getter.contract.int", null));
  }

  @Test
  @DisplayName("INT get on stored whole double 3.0 returns 3")
  void intGetOnStoredWholeDouble30Returns3() throws Exception {
    Quonfig client = TestSetup.client();
    assertEquals(3L, client.getLong("getter.contract.double.whole", null));
  }

  @Test
  @DisplayName("DOUBLE getter on stored int 30 ignores the default")
  void doubleGetterOnStoredInt30IgnoresTheDefault() throws Exception {
    Quonfig client = TestSetup.client();
    assertEquals(30.0d, client.getDouble("getter.contract.int", 7.5d));
  }

  @Test
  @DisplayName("INT getter on stored whole double 3.0 ignores the default")
  void intGetterOnStoredWholeDouble30IgnoresTheDefault() throws Exception {
    Quonfig client = TestSetup.client();
    assertEquals(3L, client.getLong("getter.contract.double.whole", 7L));
  }

  @Test
  @DisplayName("INT getter on stored double 1e300 returns the default")
  void intGetterOnStoredDouble1e300ReturnsTheDefault() throws Exception {
    Quonfig client = TestSetup.client();
    assertEquals(7L, client.getLong("getter.contract.double.huge", 7L));
  }

  @Test
  @DisplayName("INT getter on stored double 1e300 with no default returns nil")
  void intGetterOnStoredDouble1e300WithNoDefaultReturnsNil() throws Exception {
    Quonfig client = TestSetup.client();
    assertNull(client.getLong("getter.contract.double.huge", null));
  }

  @Test
  @DisplayName("INT getter on stored double 2^53 (outside the safe int range) returns the default")
  void intGetterOnStoredDouble253OutsideTheSafeIntRangeReturnsTheDefault() throws Exception {
    Quonfig client = TestSetup.client();
    assertEquals(7L, client.getLong("getter.contract.double.unsafe", 7L));
  }

  @Test
  @DisplayName(
      "INT getter on stored double 2^53 (outside the safe int range) with no default returns nil")
  void intGetterOnStoredDouble253OutsideTheSafeIntRangeWithNoDefaultReturnsNil() throws Exception {
    Quonfig client = TestSetup.client();
    assertNull(client.getLong("getter.contract.double.unsafe", null));
  }

  @Test
  @DisplayName("INT getter on stored malformed int \"twelve\" returns the default")
  void intGetterOnStoredMalformedIntTwelveReturnsTheDefault() throws Exception {
    Quonfig client = TestSetup.client();
    assertEquals(7L, client.getLong("getter.contract.int.malformed", 7L));
  }

  @Test
  @DisplayName("INT getter on stored malformed int \"twelve\" with no default returns nil")
  void intGetterOnStoredMalformedIntTwelveWithNoDefaultReturnsNil() throws Exception {
    Quonfig client = TestSetup.client();
    assertNull(client.getLong("getter.contract.int.malformed", null));
  }

  @Test
  @DisplayName("DOUBLE getter on stored malformed double \"abc\" returns the default")
  void doubleGetterOnStoredMalformedDoubleAbcReturnsTheDefault() throws Exception {
    Quonfig client = TestSetup.client();
    assertEquals(7.5d, client.getDouble("getter.contract.double.malformed", 7.5d));
  }

  @Test
  @DisplayName("DOUBLE getter on stored malformed double \"abc\" with no default returns nil")
  void doubleGetterOnStoredMalformedDoubleAbcWithNoDefaultReturnsNil() throws Exception {
    Quonfig client = TestSetup.client();
    assertNull(client.getDouble("getter.contract.double.malformed", null));
  }

  @Test
  @DisplayName("INT getter on env-var-provided malformed int \"twelve\" returns the default")
  void intGetterOnEnvVarProvidedMalformedIntTwelveReturnsTheDefault() throws Exception {
    TestSetup.withEnv(
        TestSetup.map("QUONFIG_ITD_GETTER_INT", "twelve"),
        () -> {
          Quonfig client = TestSetup.client();
          assertEquals(7L, client.getLong("getter.contract.provided.int.malformed", 7L));
        });
  }

  @Test
  @DisplayName(
      "INT getter on env-var-provided malformed int \"twelve\" with no default returns nil")
  void intGetterOnEnvVarProvidedMalformedIntTwelveWithNoDefaultReturnsNil() throws Exception {
    TestSetup.withEnv(
        TestSetup.map("QUONFIG_ITD_GETTER_INT", "twelve"),
        () -> {
          Quonfig client = TestSetup.client();
          assertNull(client.getLong("getter.contract.provided.int.malformed", null));
        });
  }

  @Test
  @DisplayName("DOUBLE getter on env-var-provided malformed double \"abc\" returns the default")
  void doubleGetterOnEnvVarProvidedMalformedDoubleAbcReturnsTheDefault() throws Exception {
    TestSetup.withEnv(
        TestSetup.map("QUONFIG_ITD_GETTER_DOUBLE", "abc"),
        () -> {
          Quonfig client = TestSetup.client();
          assertEquals(7.5d, client.getDouble("getter.contract.provided.double.malformed", 7.5d));
        });
  }

  @Test
  @DisplayName(
      "DOUBLE getter on env-var-provided malformed double \"abc\" with no default returns nil")
  void doubleGetterOnEnvVarProvidedMalformedDoubleAbcWithNoDefaultReturnsNil() throws Exception {
    TestSetup.withEnv(
        TestSetup.map("QUONFIG_ITD_GETTER_DOUBLE", "abc"),
        () -> {
          Quonfig client = TestSetup.client();
          assertNull(client.getDouble("getter.contract.provided.double.malformed", null));
        });
  }

  @Test
  @DisplayName("STRING getter on a missing env var returns the default")
  void stringGetterOnAMissingEnvVarReturnsTheDefault() throws Exception {
    Quonfig client = TestSetup.client();
    assertEquals("DEFAULT", client.getString("provided.by.missing.env.var", "DEFAULT"));
  }

  @Test
  @DisplayName("STRING getter on a missing env var with no default returns nil")
  void stringGetterOnAMissingEnvVarWithNoDefaultReturnsNil() throws Exception {
    Quonfig client = TestSetup.client();
    assertNull(client.getString("provided.by.missing.env.var", null));
  }

  @Test
  @DisplayName("STRING getter on a decryption failure returns the default")
  void stringGetterOnADecryptionFailureReturnsTheDefault() throws Exception {
    Quonfig client = TestSetup.client();
    assertEquals("DEFAULT", client.getString("a.broken.secret.config", "DEFAULT"));
  }

  @Test
  @DisplayName("STRING getter on a decryption failure with no default returns nil")
  void stringGetterOnADecryptionFailureWithNoDefaultReturnsNil() throws Exception {
    Quonfig client = TestSetup.client();
    assertNull(client.getString("a.broken.secret.config", null));
  }

  @Test
  @DisplayName("BOOLEAN getter on a missing bool env var returns the default")
  void booleanGetterOnAMissingBoolEnvVarReturnsTheDefault() throws Exception {
    Quonfig client = TestSetup.client();
    assertEquals(true, client.getBool("getter.contract.provided.bool.missing", Boolean.TRUE));
  }

  @Test
  @DisplayName("BOOLEAN getter on a missing bool env var with no default returns nil")
  void booleanGetterOnAMissingBoolEnvVarWithNoDefaultReturnsNil() throws Exception {
    Quonfig client = TestSetup.client();
    assertNull(client.getBool("getter.contract.provided.bool.missing", null));
  }

  @Test
  @DisplayName("BOOLEAN getter on env-var-provided bool \"false\" returns false")
  void booleanGetterOnEnvVarProvidedBoolFalseReturnsFalse() throws Exception {
    TestSetup.withEnv(
        TestSetup.map("QUONFIG_ITD_GETTER_BOOL", "false"),
        () -> {
          Quonfig client = TestSetup.client();
          assertEquals(false, client.getBool("getter.contract.provided.bool", null));
        });
  }
}
