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
    assertEquals(
        7000L,
        client
            .getDurationDetails("test.duration.malformed.30s", java.time.Duration.ofMillis(7000L))
            .value()
            .toMillis());
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
    assertEquals(
        7000L,
        client
            .getDurationDetails(
                "test.duration.malformed.PT0.5H", java.time.Duration.ofMillis(7000L))
            .value()
            .toMillis());
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
    assertEquals(
        7000L,
        client
            .getDurationDetails("test.duration.malformed.P1DT", java.time.Duration.ofMillis(7000L))
            .value()
            .toMillis());
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
    assertEquals(
        7000L,
        client
            .getDurationDetails(
                "test.duration.malformed.garbage", java.time.Duration.ofMillis(7000L))
            .value()
            .toMillis());
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
    assertEquals(
        7000L,
        client
            .getDurationDetails("test.duration.malformed.empty", java.time.Duration.ofMillis(7000L))
            .value()
            .toMillis());
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
          assertEquals(
              7000L,
              client
                  .getDurationDetails(
                      "provided.duration.malformed.30s", java.time.Duration.ofMillis(7000L))
                  .value()
                  .toMillis());
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
          assertEquals(
              7000L,
              client
                  .getDurationDetails(
                      "provided.duration.malformed.PT0.5H", java.time.Duration.ofMillis(7000L))
                  .value()
                  .toMillis());
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
          assertEquals(
              7000L,
              client
                  .getDurationDetails(
                      "provided.duration.malformed.P1DT", java.time.Duration.ofMillis(7000L))
                  .value()
                  .toMillis());
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
          assertEquals(
              7000L,
              client
                  .getDurationDetails(
                      "provided.duration.malformed.garbage", java.time.Duration.ofMillis(7000L))
                  .value()
                  .toMillis());
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
}
