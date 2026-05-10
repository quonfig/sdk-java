// AUTO-GENERATED from integration-test-data/tests/eval/get_or_raise.yaml. DO NOT EDIT.
// Regenerate with:
//   cd integration-test-data/generators && npm run generate -- --target=java
// Source: integration-test-data/generators/src/targets/java.ts

package com.quonfig.sdk.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.quonfig.sdk.exceptions.QuonfigDecryptionException;
import com.quonfig.sdk.exceptions.QuonfigEnvVarNotSetException;
import com.quonfig.sdk.exceptions.QuonfigKeyNotFoundException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class GetOrRaiseTest {

  @Test
  @DisplayName("get_or_raise can raise an error if value not found")
  void getOrRaiseCanRaiseAnErrorIfValueNotFound() throws Exception {
    assertThrows(
        QuonfigKeyNotFoundException.class,
        () -> TestSetup.runRaiseCase("my-missing-key", TestSetup.map(), "missing_default"));
  }

  @Test
  @DisplayName("get_or_raise returns a default value instead of raising")
  void getOrRaiseReturnsADefaultValueInsteadOfRaising() throws Exception {
    Object actual = TestSetup.getCase("my-missing-key", TestSetup.map(), "DEFAULT");
    assertEquals("DEFAULT", actual);
  }

  @Test
  @DisplayName("get_or_raise raises the correct error if it doesn't raise on init timeout")
  void getOrRaiseRaisesTheCorrectErrorIfItDoesnTRaiseOnInitTimeout() throws Exception {
    TestSetup.assertClientConstructionRaises(
        "any-key",
        0.01d,
        "https://app.staging-prefab.cloud",
        "return",
        "get_or_raise",
        QuonfigKeyNotFoundException.class);
  }

  @Test
  @DisplayName("get_or_raise can raise an error if the client does not initialize in time")
  void getOrRaiseCanRaiseAnErrorIfTheClientDoesNotInitializeInTime() throws Exception {
    TestSetup.assertInitializationTimeoutError(
        "any-key", 0.01d, "https://app.staging-prefab.cloud", "raise");
  }

  @Test
  @DisplayName("raises an error if a config is provided by a missing environment variable")
  void raisesAnErrorIfAConfigIsProvidedByAMissingEnvironmentVariable() throws Exception {
    assertThrows(
        QuonfigEnvVarNotSetException.class,
        () ->
            TestSetup.runRaiseCase(
                "provided.by.missing.env.var", TestSetup.map(), "missing_env_var"));
  }

  @Test
  @DisplayName("raises an error if an env-var-provided config cannot be coerced to configured type")
  void raisesAnErrorIfAnEnvVarProvidedConfigCannotBeCoercedToConfiguredType() throws Exception {
    assertThrows(
        QuonfigKeyNotFoundException.class,
        () ->
            TestSetup.runRaiseCase(
                "provided.not.a.number", TestSetup.map(), "unable_to_coerce_env_var"));
  }

  @Test
  @DisplayName("raises an error for decryption failure")
  void raisesAnErrorForDecryptionFailure() throws Exception {
    assertThrows(
        QuonfigDecryptionException.class,
        () ->
            TestSetup.runRaiseCase("a.broken.secret.config", TestSetup.map(), "unable_to_decrypt"));
  }
}
