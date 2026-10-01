// AUTO-GENERATED from integration-test-data/tests/eval/get_or_raise.yaml. DO NOT EDIT.
// Regenerate with:
//   cd integration-test-data/generators && npm run generate -- --target=java
// Source: integration-test-data/generators/src/targets/java.ts

package com.quonfig.sdk.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.quonfig.sdk.Quonfig;
import com.quonfig.sdk.exceptions.QuonfigDecryptionException;
import com.quonfig.sdk.exceptions.QuonfigEnvVarNotSetException;
import com.quonfig.sdk.exceptions.QuonfigInitTimeoutException;
import com.quonfig.sdk.exceptions.QuonfigKeyNotFoundException;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class GetOrRaiseTest {

  @Test
  @DisplayName("get_or_raise can raise an error if value not found")
  void getOrRaiseCanRaiseAnErrorIfValueNotFound() throws Exception {
    Quonfig client = TestSetup.client();
    assertThrows(
        QuonfigKeyNotFoundException.class, () -> client.getStringOrThrow("my-missing-key"));
  }

  @Test
  @DisplayName("get_or_raise returns a default value instead of raising")
  void getOrRaiseReturnsADefaultValueInsteadOfRaising() throws Exception {
    Quonfig client = TestSetup.client();
    assertEquals("DEFAULT", client.getString("my-missing-key", "DEFAULT"));
  }

  @Test
  @Disabled(
      "unsupported by sdk-java: sdk-java has no on_init_failure option: a client that misses initTimeout always throws QuonfigInitTimeoutException from get*OrThrow, never the :return-mode missing_default error")
  @DisplayName("get_or_raise raises the correct error if it doesn't raise on init timeout")
  void getOrRaiseRaisesTheCorrectErrorIfItDoesnTRaiseOnInitTimeout() throws Exception {
    try (Quonfig client = TestSetup.httpClient("https://app.staging-prefab.cloud", 0.01d)) {
      assertThrows(QuonfigKeyNotFoundException.class, () -> client.getStringOrThrow("any-key"));
    }
  }

  @Test
  @DisplayName("get_or_raise can raise an error if the client does not initialize in time")
  void getOrRaiseCanRaiseAnErrorIfTheClientDoesNotInitializeInTime() throws Exception {
    try (Quonfig client = TestSetup.httpClient("https://app.staging-prefab.cloud", 0.01d)) {
      assertThrows(QuonfigInitTimeoutException.class, () -> client.getStringOrThrow("any-key"));
    }
  }

  @Test
  @DisplayName("raises an error if a config is provided by a missing environment variable")
  void raisesAnErrorIfAConfigIsProvidedByAMissingEnvironmentVariable() throws Exception {
    Quonfig client = TestSetup.client();
    assertThrows(
        QuonfigEnvVarNotSetException.class,
        () -> client.getStringOrThrow("provided.by.missing.env.var"));
  }

  @Test
  @DisplayName("raises an error if an env-var-provided config cannot be coerced to configured type")
  void raisesAnErrorIfAnEnvVarProvidedConfigCannotBeCoercedToConfiguredType() throws Exception {
    Quonfig client = TestSetup.client();
    assertThrows(
        QuonfigKeyNotFoundException.class, () -> client.getLongOrThrow("provided.not.a.number"));
  }

  @Test
  @DisplayName("raises an error for decryption failure")
  void raisesAnErrorForDecryptionFailure() throws Exception {
    Quonfig client = TestSetup.client();
    assertThrows(
        QuonfigDecryptionException.class, () -> client.getStringOrThrow("a.broken.secret.config"));
  }

  @Test
  @DisplayName("raises an error if an env-var-provided duration 30s cannot be coerced")
  void raisesAnErrorIfAnEnvVarProvidedDuration30sCannotBeCoerced() throws Exception {
    TestSetup.withEnv(
        TestSetup.map("QUONFIG_ITD_DURATION_30S", "30s"),
        () -> {
          Quonfig client = TestSetup.client();
          assertThrows(
              QuonfigKeyNotFoundException.class,
              () -> client.getDurationOrThrow("provided.duration.malformed.30s"));
        });
  }

  @Test
  @DisplayName("raises an error if an env-var-provided duration PT0.5H cannot be coerced")
  void raisesAnErrorIfAnEnvVarProvidedDurationPt05hCannotBeCoerced() throws Exception {
    TestSetup.withEnv(
        TestSetup.map("QUONFIG_ITD_DURATION_PT0_5H", "PT0.5H"),
        () -> {
          Quonfig client = TestSetup.client();
          assertThrows(
              QuonfigKeyNotFoundException.class,
              () -> client.getDurationOrThrow("provided.duration.malformed.PT0.5H"));
        });
  }

  @Test
  @DisplayName("raises an error if an env-var-provided duration P1DT cannot be coerced")
  void raisesAnErrorIfAnEnvVarProvidedDurationP1dtCannotBeCoerced() throws Exception {
    TestSetup.withEnv(
        TestSetup.map("QUONFIG_ITD_DURATION_P1DT", "P1DT"),
        () -> {
          Quonfig client = TestSetup.client();
          assertThrows(
              QuonfigKeyNotFoundException.class,
              () -> client.getDurationOrThrow("provided.duration.malformed.P1DT"));
        });
  }

  @Test
  @DisplayName("raises an error if an env-var-provided duration garbage cannot be coerced")
  void raisesAnErrorIfAnEnvVarProvidedDurationGarbageCannotBeCoerced() throws Exception {
    TestSetup.withEnv(
        TestSetup.map("QUONFIG_ITD_DURATION_GARBAGE", "garbage"),
        () -> {
          Quonfig client = TestSetup.client();
          assertThrows(
              QuonfigKeyNotFoundException.class,
              () -> client.getDurationOrThrow("provided.duration.malformed.garbage"));
        });
  }

  @Test
  @DisplayName("raises an error if a stored duration 30s cannot be coerced")
  void raisesAnErrorIfAStoredDuration30sCannotBeCoerced() throws Exception {
    Quonfig client = TestSetup.client();
    assertThrows(
        QuonfigKeyNotFoundException.class,
        () -> client.getDurationOrThrow("test.duration.malformed.30s"));
  }

  @Test
  @DisplayName("raises an error if a stored duration PT0.5H cannot be coerced")
  void raisesAnErrorIfAStoredDurationPt05hCannotBeCoerced() throws Exception {
    Quonfig client = TestSetup.client();
    assertThrows(
        QuonfigKeyNotFoundException.class,
        () -> client.getDurationOrThrow("test.duration.malformed.PT0.5H"));
  }

  @Test
  @DisplayName("raises an error if a stored duration P1DT cannot be coerced")
  void raisesAnErrorIfAStoredDurationP1dtCannotBeCoerced() throws Exception {
    Quonfig client = TestSetup.client();
    assertThrows(
        QuonfigKeyNotFoundException.class,
        () -> client.getDurationOrThrow("test.duration.malformed.P1DT"));
  }

  @Test
  @DisplayName("raises an error if a stored duration garbage cannot be coerced")
  void raisesAnErrorIfAStoredDurationGarbageCannotBeCoerced() throws Exception {
    Quonfig client = TestSetup.client();
    assertThrows(
        QuonfigKeyNotFoundException.class,
        () -> client.getDurationOrThrow("test.duration.malformed.garbage"));
  }

  @Test
  @DisplayName("raises an error if a stored duration empty cannot be coerced")
  void raisesAnErrorIfAStoredDurationEmptyCannotBeCoerced() throws Exception {
    Quonfig client = TestSetup.client();
    assertThrows(
        QuonfigKeyNotFoundException.class,
        () -> client.getDurationOrThrow("test.duration.malformed.empty"));
  }
}
