// AUTO-GENERATED from integration-test-data/tests/eval/get_or_raise.yaml. DO NOT EDIT.
// Regenerate with:
//   cd integration-test-data/generators && npm run generate -- --target=java
// Source: integration-test-data/generators/src/targets/java.ts

package com.quonfig.sdk.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.quonfig.sdk.Quonfig;
import com.quonfig.sdk.exceptions.QuonfigDecryptionException;
import com.quonfig.sdk.exceptions.QuonfigEnvVarNotSetException;
import com.quonfig.sdk.exceptions.QuonfigInitException;
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
  @DisplayName("init fails by default when every delivery leg refuses the connection")
  void initFailsByDefaultWhenEveryDeliveryLegRefusesTheConnection() throws Exception {
    com.quonfig.sdk.Options o =
        com.quonfig.sdk.Options.builder()
            .sdkKey("itd-init-failure")
            .apiUrls(java.util.List.of("http://127.0.0.1:1", "http://127.0.0.1:2"))
            .streamUrls(java.util.List.of("http://127.0.0.1:1", "http://127.0.0.1:2"))
            .initTimeout(java.time.Duration.ofMillis(500))
            .fallbackPollEnabled(false)
            .enableQuonfigUserContext(false)
            .disableTelemetry(true)
            .build();
    long start = System.nanoTime();
    QuonfigInitException raised =
        assertThrows(QuonfigInitException.class, () -> new Quonfig(o).close());
    long elapsedMs = (System.nanoTime() - start) / 1_000_000L;
    assertFalse(
        raised instanceof QuonfigInitTimeoutException, "reason unavailable, not the init timeout");
    assertTrue(elapsedMs < 5000, "init raised after " + elapsedMs + "ms");
  }

  @Test
  @DisplayName(
      "init with on_init_failure fail raises when every delivery leg refuses the connection")
  void initWithOnInitFailureFailRaisesWhenEveryDeliveryLegRefusesTheConnection() throws Exception {
    com.quonfig.sdk.Options o =
        com.quonfig.sdk.Options.builder()
            .sdkKey("itd-init-failure")
            .apiUrls(java.util.List.of("http://127.0.0.1:1", "http://127.0.0.1:2"))
            .streamUrls(java.util.List.of("http://127.0.0.1:1", "http://127.0.0.1:2"))
            .initTimeout(java.time.Duration.ofMillis(500))
            .fallbackPollEnabled(false)
            .enableQuonfigUserContext(false)
            .disableTelemetry(true)
            .onInitFailure(com.quonfig.sdk.OnInitFailure.FAIL)
            .build();
    long start = System.nanoTime();
    QuonfigInitException raised =
        assertThrows(QuonfigInitException.class, () -> new Quonfig(o).close());
    long elapsedMs = (System.nanoTime() - start) / 1_000_000L;
    assertFalse(
        raised instanceof QuonfigInitTimeoutException, "reason unavailable, not the init timeout");
    assertTrue(elapsedMs < 5000, "init raised after " + elapsedMs + "ms");
  }

  @Test
  @DisplayName(
      "init with on_init_failure fail raises the init timeout when every delivery leg hangs")
  void initWithOnInitFailureFailRaisesTheInitTimeoutWhenEveryDeliveryLegHangs() throws Exception {
    com.quonfig.sdk.Options o =
        com.quonfig.sdk.Options.builder()
            .sdkKey("itd-init-failure")
            .apiUrls(java.util.List.of("http://192.0.2.1:1", "http://192.0.2.1:2"))
            .streamUrls(java.util.List.of("http://192.0.2.1:1", "http://192.0.2.1:2"))
            .initTimeout(java.time.Duration.ofMillis(200))
            .fallbackPollEnabled(false)
            .enableQuonfigUserContext(false)
            .disableTelemetry(true)
            .onInitFailure(com.quonfig.sdk.OnInitFailure.FAIL)
            .build();
    long start = System.nanoTime();
    QuonfigInitException raised =
        assertThrows(QuonfigInitException.class, () -> new Quonfig(o).close());
    long elapsedMs = (System.nanoTime() - start) / 1_000_000L;
    assertTrue(raised instanceof QuonfigInitTimeoutException, raised.toString());
    assertTrue(elapsedMs < 2000, "init raised after " + elapsedMs + "ms");
  }

  @Test
  @DisplayName("init with on_init_failure fail raises at once when every delivery leg answers 401")
  void initWithOnInitFailureFailRaisesAtOnceWhenEveryDeliveryLegAnswers401() throws Exception {
    java.util.List<com.sun.net.httpserver.HttpServer> legs = new java.util.ArrayList<>();
    for (int i = 0; i < 2; i++) {
      com.sun.net.httpserver.HttpServer leg =
          com.sun.net.httpserver.HttpServer.create(
              new java.net.InetSocketAddress("127.0.0.1", 0), 0);
      leg.createContext(
          "/",
          ex -> {
            ex.sendResponseHeaders(401, -1);
            ex.close();
          });
      leg.start();
      legs.add(leg);
    }
    try {
      com.quonfig.sdk.Options o =
          com.quonfig.sdk.Options.builder()
              .sdkKey("itd-init-failure")
              .apiUrls(
                  java.util.List.of(
                      "http://127.0.0.1:" + legs.get(0).getAddress().getPort(),
                      "http://127.0.0.1:" + legs.get(1).getAddress().getPort()))
              .streamUrls(
                  java.util.List.of(
                      "http://127.0.0.1:" + legs.get(0).getAddress().getPort(),
                      "http://127.0.0.1:" + legs.get(1).getAddress().getPort()))
              .initTimeout(java.time.Duration.ofMillis(8000))
              .fallbackPollEnabled(false)
              .enableQuonfigUserContext(false)
              .disableTelemetry(true)
              .onInitFailure(com.quonfig.sdk.OnInitFailure.FAIL)
              .build();
      long start = System.nanoTime();
      QuonfigInitException raised =
          assertThrows(QuonfigInitException.class, () -> new Quonfig(o).close());
      long elapsedMs = (System.nanoTime() - start) / 1_000_000L;
      assertFalse(
          raised instanceof QuonfigInitTimeoutException,
          "reason unauthorized, not the init timeout");
      assertTrue(elapsedMs < 4000, "init raised after " + elapsedMs + "ms");
    } finally {
      for (com.sun.net.httpserver.HttpServer leg : legs) leg.stop(0);
    }
  }

  @Test
  @DisplayName("get_or_raise returns the default after init continues past refused delivery legs")
  void getOrRaiseReturnsTheDefaultAfterInitContinuesPastRefusedDeliveryLegs() throws Exception {
    com.quonfig.sdk.Options o =
        com.quonfig.sdk.Options.builder()
            .sdkKey("itd-init-failure")
            .apiUrls(java.util.List.of("http://127.0.0.1:1", "http://127.0.0.1:2"))
            .streamUrls(java.util.List.of("http://127.0.0.1:1", "http://127.0.0.1:2"))
            .initTimeout(java.time.Duration.ofMillis(500))
            .fallbackPollEnabled(false)
            .enableQuonfigUserContext(false)
            .disableTelemetry(true)
            .onInitFailure(com.quonfig.sdk.OnInitFailure.CONTINUE)
            .build();
    try (Quonfig client = new Quonfig(o)) {
      assertEquals("DEFAULT", client.getString("any-key", "DEFAULT"));
    }
  }

  @Test
  @DisplayName(
      "get_or_raise raises missing_default after init continues past refused delivery legs")
  void getOrRaiseRaisesMissingDefaultAfterInitContinuesPastRefusedDeliveryLegs() throws Exception {
    com.quonfig.sdk.Options o =
        com.quonfig.sdk.Options.builder()
            .sdkKey("itd-init-failure")
            .apiUrls(java.util.List.of("http://127.0.0.1:1", "http://127.0.0.1:2"))
            .streamUrls(java.util.List.of("http://127.0.0.1:1", "http://127.0.0.1:2"))
            .initTimeout(java.time.Duration.ofMillis(500))
            .fallbackPollEnabled(false)
            .enableQuonfigUserContext(false)
            .disableTelemetry(true)
            .onInitFailure(com.quonfig.sdk.OnInitFailure.CONTINUE)
            .build();
    try (Quonfig client = new Quonfig(o)) {
      assertThrows(QuonfigKeyNotFoundException.class, () -> client.getStringOrThrow("any-key"));
    }
  }

  @Test
  @Disabled(
      "unsupported by sdk-java: sdk-java has no legacy on_init_failure spellings to keep as aliases (init-failure design B.2: \"java: none\")")
  @DisplayName(
      "deprecated on_init_failure raise behaves as fail when every delivery leg refuses the connection")
  void deprecatedOnInitFailureRaiseBehavesAsFailWhenEveryDeliveryLegRefusesTheConnection()
      throws Exception {
    com.quonfig.sdk.Options o =
        com.quonfig.sdk.Options.builder()
            .sdkKey("itd-init-failure")
            .apiUrls(java.util.List.of("http://127.0.0.1:1", "http://127.0.0.1:2"))
            .streamUrls(java.util.List.of("http://127.0.0.1:1", "http://127.0.0.1:2"))
            .initTimeout(java.time.Duration.ofMillis(500))
            .fallbackPollEnabled(false)
            .enableQuonfigUserContext(false)
            .disableTelemetry(true)
            .onInitFailure(com.quonfig.sdk.OnInitFailure.FAIL)
            .build();
    long start = System.nanoTime();
    QuonfigInitException raised =
        assertThrows(QuonfigInitException.class, () -> new Quonfig(o).close());
    long elapsedMs = (System.nanoTime() - start) / 1_000_000L;
    assertFalse(
        raised instanceof QuonfigInitTimeoutException, "reason unavailable, not the init timeout");
    assertTrue(elapsedMs < 5000, "init raised after " + elapsedMs + "ms");
  }

  @Test
  @Disabled(
      "unsupported by sdk-java: sdk-java has no legacy on_init_failure spellings to keep as aliases (init-failure design B.2: \"java: none\")")
  @DisplayName(
      "deprecated on_init_failure return behaves as continue when every delivery leg refuses the connection")
  void deprecatedOnInitFailureReturnBehavesAsContinueWhenEveryDeliveryLegRefusesTheConnection()
      throws Exception {
    com.quonfig.sdk.Options o =
        com.quonfig.sdk.Options.builder()
            .sdkKey("itd-init-failure")
            .apiUrls(java.util.List.of("http://127.0.0.1:1", "http://127.0.0.1:2"))
            .streamUrls(java.util.List.of("http://127.0.0.1:1", "http://127.0.0.1:2"))
            .initTimeout(java.time.Duration.ofMillis(500))
            .fallbackPollEnabled(false)
            .enableQuonfigUserContext(false)
            .disableTelemetry(true)
            .onInitFailure(com.quonfig.sdk.OnInitFailure.CONTINUE)
            .build();
    try (Quonfig client = new Quonfig(o)) {
      assertThrows(QuonfigKeyNotFoundException.class, () -> client.getStringOrThrow("any-key"));
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
  @DisplayName("coercion error for an int env var names the variable and never its value")
  void coercionErrorForAnIntEnvVarNamesTheVariableAndNeverItsValue() throws Exception {
    TestSetup.withEnv(
        TestSetup.map("QUONFIG_ITD_SECRET_ENV", "postgres://admin:hunter2@db"),
        () -> {
          Quonfig client = TestSetup.client();
          QuonfigKeyNotFoundException raised =
              assertThrows(
                  QuonfigKeyNotFoundException.class,
                  () -> client.getLongOrThrow("provided.secret.not.an.int"));
          String raisedMessage = String.valueOf(raised.getMessage());
          assertTrue(
              raisedMessage.contains(
                  "environment variable \"QUONFIG_ITD_SECRET_ENV\" cannot be converted to int for config \"provided.secret.not.an.int\""),
              () ->
                  "message lacks "
                      + "environment variable \"QUONFIG_ITD_SECRET_ENV\" cannot be converted to int for config \"provided.secret.not.an.int\""
                      + ": "
                      + raisedMessage);
          assertFalse(
              raisedMessage.contains("hunter2"),
              () -> "message contains " + "hunter2" + ": " + raisedMessage);
          assertFalse(
              raisedMessage.contains("postgres://"),
              () -> "message contains " + "postgres://" + ": " + raisedMessage);
        });
  }

  @Test
  @DisplayName("coercion error for a double env var names the variable and never its value")
  void coercionErrorForADoubleEnvVarNamesTheVariableAndNeverItsValue() throws Exception {
    TestSetup.withEnv(
        TestSetup.map("QUONFIG_ITD_SECRET_ENV", "postgres://admin:hunter2@db"),
        () -> {
          Quonfig client = TestSetup.client();
          QuonfigKeyNotFoundException raised =
              assertThrows(
                  QuonfigKeyNotFoundException.class,
                  () -> client.getDoubleOrThrow("provided.secret.not.a.double"));
          String raisedMessage = String.valueOf(raised.getMessage());
          assertTrue(
              raisedMessage.contains(
                  "environment variable \"QUONFIG_ITD_SECRET_ENV\" cannot be converted to double for config \"provided.secret.not.a.double\""),
              () ->
                  "message lacks "
                      + "environment variable \"QUONFIG_ITD_SECRET_ENV\" cannot be converted to double for config \"provided.secret.not.a.double\""
                      + ": "
                      + raisedMessage);
          assertFalse(
              raisedMessage.contains("hunter2"),
              () -> "message contains " + "hunter2" + ": " + raisedMessage);
          assertFalse(
              raisedMessage.contains("postgres://"),
              () -> "message contains " + "postgres://" + ": " + raisedMessage);
        });
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
