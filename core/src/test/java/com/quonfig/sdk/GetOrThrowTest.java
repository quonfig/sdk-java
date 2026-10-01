package com.quonfig.sdk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.quonfig.sdk.eval.ContextSet;
import com.quonfig.sdk.exceptions.QuonfigDecryptionException;
import com.quonfig.sdk.exceptions.QuonfigEnvVarNotSetException;
import com.quonfig.sdk.exceptions.QuonfigKeyNotFoundException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The public get_or_raise equivalent (qfg-2agi.27): the {@code get*OrThrow} family throws the
 * {@code com.quonfig.sdk.exceptions} class for each ITD {@code get_or_raise.yaml} error key, driven
 * through the public client against the shared integration-test-data corpus.
 */
class GetOrThrowTest {

  private final Map<String, String> env = new HashMap<>();
  private Quonfig client;

  @BeforeEach
  void setUp() {
    env.put(
        "PREFAB_INTEGRATION_TEST_ENCRYPTION_KEY",
        "c87ba22d8662282abe8a0e4651327b579cb64a454ab0f4c170b45b15f049a221");
    env.put("NOT_A_NUMBER", "not_a_number");
    env.put("QUONFIG_ITD_DURATION_30S", "30s");
    env.put("QUONFIG_ITD_DURATION_PT0_5H", "PT0.5H");
    env.put("QUONFIG_ITD_DURATION_P1DT", "P1DT");
    env.put("QUONFIG_ITD_DURATION_GARBAGE", "garbage");
    client =
        new Quonfig(
            Options.builder()
                .datadir(corpus())
                .environment("Production")
                .envLookup(k -> Optional.ofNullable(env.get(k)))
                .enableQuonfigUserContext(false)
                .disableTelemetry(true)
                .build());
  }

  @AfterEach
  void tearDown() {
    client.close();
  }

  private static String corpus() {
    Path p =
        Paths.get(System.getProperty("user.dir"), "..", "integration-test-data", "data")
            .resolve("integration-tests")
            .normalize();
    if (!Files.isDirectory(p)) {
      p =
          Paths.get(System.getProperty("user.dir"), "..", "..", "integration-test-data", "data")
              .resolve("integration-tests")
              .normalize();
    }
    return p.toString();
  }

  @Test
  void returnsTheValueWhenPresent() {
    assertEquals("my-test-value", client.getStringOrThrow("my-test-key"));
    assertEquals(Duration.ofMillis(90000), client.getDurationOrThrow("test.duration.PT90S"));
    assertEquals(
        "my-test-value", client.withContext(new ContextSet()).getStringOrThrow("my-test-key"));
  }

  @Test
  void missingKeyThrowsKeyNotFound() {
    QuonfigKeyNotFoundException e =
        assertThrows(
            QuonfigKeyNotFoundException.class, () -> client.getStringOrThrow("my-missing-key"));
    assertEquals("No value found for key 'my-missing-key'", e.getMessage());
    assertThrows(
        QuonfigKeyNotFoundException.class,
        () -> client.withContext(new ContextSet()).getStringOrThrow("my-missing-key"));
  }

  @Test
  void missingEnvVarThrowsEnvVarNotSet() {
    assertThrows(
        QuonfigEnvVarNotSetException.class,
        () -> client.getStringOrThrow("provided.by.missing.env.var"));
  }

  @Test
  void uncoercibleEnvVarThrowsKeyNotFound() {
    assertThrows(
        QuonfigKeyNotFoundException.class, () -> client.getLongOrThrow("provided.not.a.number"));
  }

  @Test
  void decryptionFailureThrowsDecryption() {
    assertThrows(
        QuonfigDecryptionException.class, () -> client.getStringOrThrow("a.broken.secret.config"));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "provided.duration.malformed.30s",
        "provided.duration.malformed.PT0.5H",
        "provided.duration.malformed.P1DT",
        "provided.duration.malformed.garbage",
        "test.duration.malformed.30s",
        "test.duration.malformed.PT0.5H",
        "test.duration.malformed.P1DT",
        "test.duration.malformed.garbage",
        "test.duration.malformed.empty",
      })
  void malformedDurationThrowsTheCoercionError(String key) {
    assertThrows(QuonfigKeyNotFoundException.class, () -> client.getDurationOrThrow(key));
  }

  @Test
  void typeMismatchThrowsIllegalArgument() {
    assertThrows(IllegalArgumentException.class, () -> client.getLongOrThrow("my-test-key"));
  }
}
