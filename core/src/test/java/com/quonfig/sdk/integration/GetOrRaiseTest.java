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
import com.quonfig.sdk.exceptions.QuonfigCoercionException;
import com.quonfig.sdk.exceptions.QuonfigDecryptionException;
import com.quonfig.sdk.exceptions.QuonfigEnvVarNotSetException;
import com.quonfig.sdk.exceptions.QuonfigInitException;
import com.quonfig.sdk.exceptions.QuonfigInitTimeoutException;
import com.quonfig.sdk.exceptions.QuonfigKeyNotFoundException;
import com.quonfig.sdk.exceptions.QuonfigTypeMismatchException;
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
            .initTimeout(java.time.Duration.ofMillis(0))
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
    assertEquals(QuonfigInitException.Reason.UNAVAILABLE, raised.reason());
    assertTrue(elapsedMs < 8000, "init raised after " + elapsedMs + "ms");
  }

  @Test
  @DisplayName(
      "init with on_init_failure fail raises the init timeout when every delivery leg hangs")
  void initWithOnInitFailureFailRaisesTheInitTimeoutWhenEveryDeliveryLegHangs() throws Exception {
    com.quonfig.sdk.Options o =
        com.quonfig.sdk.Options.builder()
            .sdkKey("itd-init-failure")
            .apiUrls(java.util.List.of("http://192.0.2.1:8001", "http://192.0.2.1:8002"))
            .streamUrls(java.util.List.of("http://192.0.2.1:8001", "http://192.0.2.1:8002"))
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
    assertEquals(QuonfigInitException.Reason.TIMEOUT, raised.reason());
    assertTrue(elapsedMs < 2000, "init raised after " + elapsedMs + "ms");
  }

  @Test
  @DisplayName("init with on_init_failure fail raises at once when every delivery leg answers 401")
  void initWithOnInitFailureFailRaisesAtOnceWhenEveryDeliveryLegAnswers401() throws Exception {
    java.util.List<com.sun.net.httpserver.HttpServer> legs = new java.util.ArrayList<>();
    java.util.List<String> legUrls = new java.util.ArrayList<>();
    for (int status : new int[] {401, 401}) {
      com.sun.net.httpserver.HttpServer leg =
          com.sun.net.httpserver.HttpServer.create(
              new java.net.InetSocketAddress("127.0.0.1", 0), 0);
      leg.createContext(
          "/",
          ex -> {
            ex.sendResponseHeaders(status, -1);
            ex.close();
          });
      leg.start();
      legs.add(leg);
      legUrls.add("http://127.0.0.1:" + leg.getAddress().getPort());
    }
    try {
      com.quonfig.sdk.Options o =
          com.quonfig.sdk.Options.builder()
              .sdkKey("itd-init-failure")
              .apiUrls(legUrls)
              .streamUrls(legUrls)
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
      assertEquals(QuonfigInitException.Reason.UNAUTHORIZED, raised.reason());
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
            .initTimeout(java.time.Duration.ofMillis(0))
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
    assertEquals(QuonfigInitException.Reason.UNAVAILABLE, raised.reason());
    assertTrue(elapsedMs < 8000, "init raised after " + elapsedMs + "ms");
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
  @DisplayName("init with on_init_failure fail raises at once when every delivery leg answers 404")
  void initWithOnInitFailureFailRaisesAtOnceWhenEveryDeliveryLegAnswers404() throws Exception {
    java.util.List<com.sun.net.httpserver.HttpServer> legs = new java.util.ArrayList<>();
    java.util.List<String> legUrls = new java.util.ArrayList<>();
    for (int status : new int[] {404, 404}) {
      com.sun.net.httpserver.HttpServer leg =
          com.sun.net.httpserver.HttpServer.create(
              new java.net.InetSocketAddress("127.0.0.1", 0), 0);
      leg.createContext(
          "/",
          ex -> {
            ex.sendResponseHeaders(status, -1);
            ex.close();
          });
      leg.start();
      legs.add(leg);
      legUrls.add("http://127.0.0.1:" + leg.getAddress().getPort());
    }
    try {
      com.quonfig.sdk.Options o =
          com.quonfig.sdk.Options.builder()
              .sdkKey("itd-init-failure")
              .apiUrls(legUrls)
              .streamUrls(legUrls)
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
      assertEquals(QuonfigInitException.Reason.UNAUTHORIZED, raised.reason());
      assertTrue(elapsedMs < 4000, "init raised after " + elapsedMs + "ms");
    } finally {
      for (com.sun.net.httpserver.HttpServer leg : legs) leg.stop(0);
    }
  }

  @Test
  @DisplayName("init with on_init_failure fail raises at once when every delivery leg answers 410")
  void initWithOnInitFailureFailRaisesAtOnceWhenEveryDeliveryLegAnswers410() throws Exception {
    java.util.List<com.sun.net.httpserver.HttpServer> legs = new java.util.ArrayList<>();
    java.util.List<String> legUrls = new java.util.ArrayList<>();
    for (int status : new int[] {410, 410}) {
      com.sun.net.httpserver.HttpServer leg =
          com.sun.net.httpserver.HttpServer.create(
              new java.net.InetSocketAddress("127.0.0.1", 0), 0);
      leg.createContext(
          "/",
          ex -> {
            ex.sendResponseHeaders(status, -1);
            ex.close();
          });
      leg.start();
      legs.add(leg);
      legUrls.add("http://127.0.0.1:" + leg.getAddress().getPort());
    }
    try {
      com.quonfig.sdk.Options o =
          com.quonfig.sdk.Options.builder()
              .sdkKey("itd-init-failure")
              .apiUrls(legUrls)
              .streamUrls(legUrls)
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
      assertEquals(QuonfigInitException.Reason.UNAUTHORIZED, raised.reason());
      assertTrue(elapsedMs < 4000, "init raised after " + elapsedMs + "ms");
    } finally {
      for (com.sun.net.httpserver.HttpServer leg : legs) leg.stop(0);
    }
  }

  @Test
  @DisplayName(
      "init with on_init_failure fail raises unavailable after one attempt when only the primary answers 401 and the secondary 503")
  void
      initWithOnInitFailureFailRaisesUnavailableAfterOneAttemptWhenOnlyThePrimaryAnswers401AndTheSecondary503()
          throws Exception {
    java.util.List<com.sun.net.httpserver.HttpServer> legs = new java.util.ArrayList<>();
    java.util.List<String> legUrls = new java.util.ArrayList<>();
    for (int status : new int[] {401, 503}) {
      com.sun.net.httpserver.HttpServer leg =
          com.sun.net.httpserver.HttpServer.create(
              new java.net.InetSocketAddress("127.0.0.1", 0), 0);
      leg.createContext(
          "/",
          ex -> {
            ex.sendResponseHeaders(status, -1);
            ex.close();
          });
      leg.start();
      legs.add(leg);
      legUrls.add("http://127.0.0.1:" + leg.getAddress().getPort());
    }
    try {
      com.quonfig.sdk.Options o =
          com.quonfig.sdk.Options.builder()
              .sdkKey("itd-init-failure")
              .apiUrls(legUrls)
              .streamUrls(legUrls)
              .initTimeout(java.time.Duration.ofMillis(0))
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
          "reason unavailable, not the init timeout");
      assertEquals(QuonfigInitException.Reason.UNAVAILABLE, raised.reason());
      assertTrue(elapsedMs < 5000, "init raised after " + elapsedMs + "ms");
    } finally {
      for (com.sun.net.httpserver.HttpServer leg : legs) leg.stop(0);
    }
  }

  @Test
  @DisplayName(
      "init with on_init_failure fail raises unavailable at the deadline when the primary answers 401 and the secondary hangs")
  void
      initWithOnInitFailureFailRaisesUnavailableAtTheDeadlineWhenThePrimaryAnswers401AndTheSecondaryHangs()
          throws Exception {
    java.util.List<com.sun.net.httpserver.HttpServer> legs = new java.util.ArrayList<>();
    java.util.List<String> legUrls = new java.util.ArrayList<>();
    String[] legFixed = {"", "http://192.0.2.1:8002"};
    int[] legStatus = {401, 0};
    for (int i = 0; i < legStatus.length; i++) {
      if (legStatus[i] == 0) {
        legUrls.add(legFixed[i]);
        continue;
      }
      final int status = legStatus[i];
      com.sun.net.httpserver.HttpServer leg =
          com.sun.net.httpserver.HttpServer.create(
              new java.net.InetSocketAddress("127.0.0.1", 0), 0);
      leg.createContext(
          "/",
          ex -> {
            ex.sendResponseHeaders(status, -1);
            ex.close();
          });
      leg.start();
      legs.add(leg);
      legUrls.add("http://127.0.0.1:" + leg.getAddress().getPort());
    }
    try {
      com.quonfig.sdk.Options o =
          com.quonfig.sdk.Options.builder()
              .sdkKey("itd-init-failure")
              .apiUrls(legUrls)
              .streamUrls(legUrls)
              .initTimeout(java.time.Duration.ofMillis(9000))
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
          "reason unavailable, not the init timeout");
      assertEquals(QuonfigInitException.Reason.UNAVAILABLE, raised.reason());
      assertTrue(elapsedMs < 12000, "init raised after " + elapsedMs + "ms");
    } finally {
      for (com.sun.net.httpserver.HttpServer leg : legs) leg.stop(0);
    }
  }

  @Test
  @DisplayName(
      "init with init_timeout_ms 0 and on_init_failure fail raises after one attempt when every delivery leg refuses the connection")
  void
      initWithInitTimeoutMs0AndOnInitFailureFailRaisesAfterOneAttemptWhenEveryDeliveryLegRefusesTheConnection()
          throws Exception {
    com.quonfig.sdk.Options o =
        com.quonfig.sdk.Options.builder()
            .sdkKey("itd-init-failure")
            .apiUrls(java.util.List.of("http://127.0.0.1:1", "http://127.0.0.1:2"))
            .streamUrls(java.util.List.of("http://127.0.0.1:1", "http://127.0.0.1:2"))
            .initTimeout(java.time.Duration.ofMillis(0))
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
    assertEquals(QuonfigInitException.Reason.UNAVAILABLE, raised.reason());
    assertTrue(elapsedMs < 8000, "init raised after " + elapsedMs + "ms");
  }

  @Test
  @DisplayName(
      "get_or_raise returns the default after init with init_timeout_ms 0 continues past refused delivery legs")
  void getOrRaiseReturnsTheDefaultAfterInitWithInitTimeoutMs0ContinuesPastRefusedDeliveryLegs()
      throws Exception {
    com.quonfig.sdk.Options o =
        com.quonfig.sdk.Options.builder()
            .sdkKey("itd-init-failure")
            .apiUrls(java.util.List.of("http://127.0.0.1:1", "http://127.0.0.1:2"))
            .streamUrls(java.util.List.of("http://127.0.0.1:1", "http://127.0.0.1:2"))
            .initTimeout(java.time.Duration.ofMillis(0))
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
      "init with on_init_failure fail raises the init timeout when every delivery leg hangs past its per-leg abort")
  void initWithOnInitFailureFailRaisesTheInitTimeoutWhenEveryDeliveryLegHangsPastItsPerLegAbort()
      throws Exception {
    com.quonfig.sdk.Options o =
        com.quonfig.sdk.Options.builder()
            .sdkKey("itd-init-failure")
            .apiUrls(java.util.List.of("http://192.0.2.1:8001", "http://192.0.2.1:8002"))
            .streamUrls(java.util.List.of("http://192.0.2.1:8001", "http://192.0.2.1:8002"))
            .initTimeout(java.time.Duration.ofMillis(10000))
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
    assertEquals(QuonfigInitException.Reason.TIMEOUT, raised.reason());
    assertTrue(elapsedMs < 14000, "init raised after " + elapsedMs + "ms");
  }

  @Test
  @DisplayName("init error never carries the userinfo of a delivery URL")
  void initErrorNeverCarriesTheUserinfoOfADeliveryUrl() throws Exception {
    com.quonfig.sdk.Options o =
        com.quonfig.sdk.Options.builder()
            .sdkKey("itd-init-failure")
            .apiUrls(
                java.util.List.of(
                    "http://qfguser:qfgsecret@127.0.0.1:1", "http://qfguser:qfgsecret@127.0.0.1:2"))
            .streamUrls(
                java.util.List.of(
                    "http://qfguser:qfgsecret@127.0.0.1:1", "http://qfguser:qfgsecret@127.0.0.1:2"))
            .initTimeout(java.time.Duration.ofMillis(0))
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
    assertEquals(QuonfigInitException.Reason.UNAVAILABLE, raised.reason());
    assertTrue(elapsedMs < 8000, "init raised after " + elapsedMs + "ms");
    java.util.Set<Throwable> seen =
        java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
    for (Throwable e = raised; e != null && seen.add(e); e = e.getCause()) {
      String m = String.valueOf(e.getMessage());
      assertFalse(m.contains("qfgsecret"), e.toString());
      assertFalse(m.contains("qfguser"), e.toString());
    }
  }

  @Test
  @DisplayName("init error never carries an upstream response body")
  void initErrorNeverCarriesAnUpstreamResponseBody() throws Exception {
    java.util.List<com.sun.net.httpserver.HttpServer> legs = new java.util.ArrayList<>();
    java.util.List<String> legUrls = new java.util.ArrayList<>();
    byte[] legBody =
        "upstream exploded: qfg-upstream-body-marker"
            .getBytes(java.nio.charset.StandardCharsets.UTF_8);
    for (int status : new int[] {503, 503}) {
      com.sun.net.httpserver.HttpServer leg =
          com.sun.net.httpserver.HttpServer.create(
              new java.net.InetSocketAddress("127.0.0.1", 0), 0);
      leg.createContext(
          "/",
          ex -> {
            ex.sendResponseHeaders(status, legBody.length);
            ex.getResponseBody().write(legBody);
            ex.close();
          });
      leg.start();
      legs.add(leg);
      legUrls.add("http://127.0.0.1:" + leg.getAddress().getPort());
    }
    try {
      com.quonfig.sdk.Options o =
          com.quonfig.sdk.Options.builder()
              .sdkKey("itd-init-failure")
              .apiUrls(legUrls)
              .streamUrls(legUrls)
              .initTimeout(java.time.Duration.ofMillis(0))
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
          "reason unavailable, not the init timeout");
      assertEquals(QuonfigInitException.Reason.UNAVAILABLE, raised.reason());
      assertTrue(elapsedMs < 5000, "init raised after " + elapsedMs + "ms");
      java.util.Set<Throwable> seen =
          java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
      for (Throwable e = raised; e != null && seen.add(e); e = e.getCause()) {
        String m = String.valueOf(e.getMessage());
        assertFalse(m.contains("qfg-upstream-body-marker"), e.toString());
      }
    } finally {
      for (com.sun.net.httpserver.HttpServer leg : legs) leg.stop(0);
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
        QuonfigCoercionException.class, () -> client.getLongOrThrow("provided.not.a.number"));
  }

  @Test
  @DisplayName("coercion error for an int env var names the variable and never its value")
  void coercionErrorForAnIntEnvVarNamesTheVariableAndNeverItsValue() throws Exception {
    TestSetup.withEnv(
        TestSetup.map("QUONFIG_ITD_SECRET_ENV", "postgres://admin:hunter2@db"),
        () -> {
          Quonfig client = TestSetup.client();
          QuonfigCoercionException raised =
              assertThrows(
                  QuonfigCoercionException.class,
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
          QuonfigCoercionException raised =
              assertThrows(
                  QuonfigCoercionException.class,
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
              QuonfigCoercionException.class,
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
              QuonfigCoercionException.class,
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
              QuonfigCoercionException.class,
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
              QuonfigCoercionException.class,
              () -> client.getDurationOrThrow("provided.duration.malformed.garbage"));
        });
  }

  @Test
  @DisplayName("raises an error if a stored duration 30s cannot be coerced")
  void raisesAnErrorIfAStoredDuration30sCannotBeCoerced() throws Exception {
    Quonfig client = TestSetup.client();
    assertThrows(
        QuonfigCoercionException.class,
        () -> client.getDurationOrThrow("test.duration.malformed.30s"));
  }

  @Test
  @DisplayName("raises an error if a stored duration PT0.5H cannot be coerced")
  void raisesAnErrorIfAStoredDurationPt05hCannotBeCoerced() throws Exception {
    Quonfig client = TestSetup.client();
    assertThrows(
        QuonfigCoercionException.class,
        () -> client.getDurationOrThrow("test.duration.malformed.PT0.5H"));
  }

  @Test
  @DisplayName("raises an error if a stored duration P1DT cannot be coerced")
  void raisesAnErrorIfAStoredDurationP1dtCannotBeCoerced() throws Exception {
    Quonfig client = TestSetup.client();
    assertThrows(
        QuonfigCoercionException.class,
        () -> client.getDurationOrThrow("test.duration.malformed.P1DT"));
  }

  @Test
  @DisplayName("raises an error if a stored duration garbage cannot be coerced")
  void raisesAnErrorIfAStoredDurationGarbageCannotBeCoerced() throws Exception {
    Quonfig client = TestSetup.client();
    assertThrows(
        QuonfigCoercionException.class,
        () -> client.getDurationOrThrow("test.duration.malformed.garbage"));
  }

  @Test
  @DisplayName("raises an error if a stored duration empty cannot be coerced")
  void raisesAnErrorIfAStoredDurationEmptyCannotBeCoerced() throws Exception {
    Quonfig client = TestSetup.client();
    assertThrows(
        QuonfigCoercionException.class,
        () -> client.getDurationOrThrow("test.duration.malformed.empty"));
  }

  @Test
  @DisplayName("STRING get_or_raise on stored int raises type_mismatch")
  void stringGetOrRaiseOnStoredIntRaisesTypeMismatch() throws Exception {
    Quonfig client = TestSetup.client();
    assertThrows(
        QuonfigTypeMismatchException.class, () -> client.getStringOrThrow("getter.contract.int"));
  }

  @Test
  @DisplayName("STRING get_or_raise on stored double raises type_mismatch")
  void stringGetOrRaiseOnStoredDoubleRaisesTypeMismatch() throws Exception {
    Quonfig client = TestSetup.client();
    assertThrows(
        QuonfigTypeMismatchException.class,
        () -> client.getStringOrThrow("getter.contract.double"));
  }

  @Test
  @DisplayName("STRING get_or_raise on stored bool raises type_mismatch")
  void stringGetOrRaiseOnStoredBoolRaisesTypeMismatch() throws Exception {
    Quonfig client = TestSetup.client();
    assertThrows(
        QuonfigTypeMismatchException.class, () -> client.getStringOrThrow("getter.contract.bool"));
  }

  @Test
  @DisplayName("STRING get_or_raise on stored string_list raises type_mismatch")
  void stringGetOrRaiseOnStoredStringListRaisesTypeMismatch() throws Exception {
    Quonfig client = TestSetup.client();
    assertThrows(
        QuonfigTypeMismatchException.class,
        () -> client.getStringOrThrow("getter.contract.string_list"));
  }

  @Test
  @DisplayName("STRING get_or_raise on stored json raises type_mismatch")
  void stringGetOrRaiseOnStoredJsonRaisesTypeMismatch() throws Exception {
    Quonfig client = TestSetup.client();
    assertThrows(
        QuonfigTypeMismatchException.class, () -> client.getStringOrThrow("getter.contract.json"));
  }

  @Test
  @DisplayName("STRING get_or_raise on stored duration raises type_mismatch")
  void stringGetOrRaiseOnStoredDurationRaisesTypeMismatch() throws Exception {
    Quonfig client = TestSetup.client();
    assertThrows(
        QuonfigTypeMismatchException.class,
        () -> client.getStringOrThrow("getter.contract.duration"));
  }

  @Test
  @DisplayName("INT get_or_raise on stored string raises type_mismatch")
  void intGetOrRaiseOnStoredStringRaisesTypeMismatch() throws Exception {
    Quonfig client = TestSetup.client();
    assertThrows(
        QuonfigTypeMismatchException.class, () -> client.getLongOrThrow("getter.contract.string"));
  }

  @Test
  @DisplayName("INT get_or_raise on stored double 3.9 raises type_mismatch")
  void intGetOrRaiseOnStoredDouble39RaisesTypeMismatch() throws Exception {
    Quonfig client = TestSetup.client();
    assertThrows(
        QuonfigTypeMismatchException.class, () -> client.getLongOrThrow("getter.contract.double"));
  }

  @Test
  @DisplayName("INT get_or_raise on stored bool raises type_mismatch")
  void intGetOrRaiseOnStoredBoolRaisesTypeMismatch() throws Exception {
    Quonfig client = TestSetup.client();
    assertThrows(
        QuonfigTypeMismatchException.class, () -> client.getLongOrThrow("getter.contract.bool"));
  }

  @Test
  @DisplayName("INT get_or_raise on stored string_list raises type_mismatch")
  void intGetOrRaiseOnStoredStringListRaisesTypeMismatch() throws Exception {
    Quonfig client = TestSetup.client();
    assertThrows(
        QuonfigTypeMismatchException.class,
        () -> client.getLongOrThrow("getter.contract.string_list"));
  }

  @Test
  @DisplayName("INT get_or_raise on stored json raises type_mismatch")
  void intGetOrRaiseOnStoredJsonRaisesTypeMismatch() throws Exception {
    Quonfig client = TestSetup.client();
    assertThrows(
        QuonfigTypeMismatchException.class, () -> client.getLongOrThrow("getter.contract.json"));
  }

  @Test
  @DisplayName("INT get_or_raise on stored duration raises type_mismatch")
  void intGetOrRaiseOnStoredDurationRaisesTypeMismatch() throws Exception {
    Quonfig client = TestSetup.client();
    assertThrows(
        QuonfigTypeMismatchException.class,
        () -> client.getLongOrThrow("getter.contract.duration"));
  }

  @Test
  @DisplayName("DOUBLE get_or_raise on stored string raises type_mismatch")
  void doubleGetOrRaiseOnStoredStringRaisesTypeMismatch() throws Exception {
    Quonfig client = TestSetup.client();
    assertThrows(
        QuonfigTypeMismatchException.class,
        () -> client.getDoubleOrThrow("getter.contract.string"));
  }

  @Test
  @DisplayName("DOUBLE get_or_raise on stored bool raises type_mismatch")
  void doubleGetOrRaiseOnStoredBoolRaisesTypeMismatch() throws Exception {
    Quonfig client = TestSetup.client();
    assertThrows(
        QuonfigTypeMismatchException.class, () -> client.getDoubleOrThrow("getter.contract.bool"));
  }

  @Test
  @DisplayName("DOUBLE get_or_raise on stored string_list raises type_mismatch")
  void doubleGetOrRaiseOnStoredStringListRaisesTypeMismatch() throws Exception {
    Quonfig client = TestSetup.client();
    assertThrows(
        QuonfigTypeMismatchException.class,
        () -> client.getDoubleOrThrow("getter.contract.string_list"));
  }

  @Test
  @DisplayName("DOUBLE get_or_raise on stored json raises type_mismatch")
  void doubleGetOrRaiseOnStoredJsonRaisesTypeMismatch() throws Exception {
    Quonfig client = TestSetup.client();
    assertThrows(
        QuonfigTypeMismatchException.class, () -> client.getDoubleOrThrow("getter.contract.json"));
  }

  @Test
  @DisplayName("DOUBLE get_or_raise on stored duration raises type_mismatch")
  void doubleGetOrRaiseOnStoredDurationRaisesTypeMismatch() throws Exception {
    Quonfig client = TestSetup.client();
    assertThrows(
        QuonfigTypeMismatchException.class,
        () -> client.getDoubleOrThrow("getter.contract.duration"));
  }

  @Test
  @DisplayName("BOOLEAN get_or_raise on stored string raises type_mismatch")
  void booleanGetOrRaiseOnStoredStringRaisesTypeMismatch() throws Exception {
    Quonfig client = TestSetup.client();
    assertThrows(
        QuonfigTypeMismatchException.class, () -> client.getBoolOrThrow("getter.contract.string"));
  }

  @Test
  @DisplayName("BOOLEAN get_or_raise on stored int raises type_mismatch")
  void booleanGetOrRaiseOnStoredIntRaisesTypeMismatch() throws Exception {
    Quonfig client = TestSetup.client();
    assertThrows(
        QuonfigTypeMismatchException.class, () -> client.getBoolOrThrow("getter.contract.int"));
  }

  @Test
  @DisplayName("BOOLEAN get_or_raise on stored double raises type_mismatch")
  void booleanGetOrRaiseOnStoredDoubleRaisesTypeMismatch() throws Exception {
    Quonfig client = TestSetup.client();
    assertThrows(
        QuonfigTypeMismatchException.class, () -> client.getBoolOrThrow("getter.contract.double"));
  }

  @Test
  @DisplayName("BOOLEAN get_or_raise on stored string_list raises type_mismatch")
  void booleanGetOrRaiseOnStoredStringListRaisesTypeMismatch() throws Exception {
    Quonfig client = TestSetup.client();
    assertThrows(
        QuonfigTypeMismatchException.class,
        () -> client.getBoolOrThrow("getter.contract.string_list"));
  }

  @Test
  @DisplayName("BOOLEAN get_or_raise on stored json raises type_mismatch")
  void booleanGetOrRaiseOnStoredJsonRaisesTypeMismatch() throws Exception {
    Quonfig client = TestSetup.client();
    assertThrows(
        QuonfigTypeMismatchException.class, () -> client.getBoolOrThrow("getter.contract.json"));
  }

  @Test
  @DisplayName("BOOLEAN get_or_raise on stored duration raises type_mismatch")
  void booleanGetOrRaiseOnStoredDurationRaisesTypeMismatch() throws Exception {
    Quonfig client = TestSetup.client();
    assertThrows(
        QuonfigTypeMismatchException.class,
        () -> client.getBoolOrThrow("getter.contract.duration"));
  }

  @Test
  @DisplayName("STRING_LIST get_or_raise on stored string raises type_mismatch")
  void stringListGetOrRaiseOnStoredStringRaisesTypeMismatch() throws Exception {
    Quonfig client = TestSetup.client();
    assertThrows(
        QuonfigTypeMismatchException.class,
        () -> client.getStringListOrThrow("getter.contract.string"));
  }

  @Test
  @DisplayName("STRING_LIST get_or_raise on stored int raises type_mismatch")
  void stringListGetOrRaiseOnStoredIntRaisesTypeMismatch() throws Exception {
    Quonfig client = TestSetup.client();
    assertThrows(
        QuonfigTypeMismatchException.class,
        () -> client.getStringListOrThrow("getter.contract.int"));
  }

  @Test
  @DisplayName("STRING_LIST get_or_raise on stored double raises type_mismatch")
  void stringListGetOrRaiseOnStoredDoubleRaisesTypeMismatch() throws Exception {
    Quonfig client = TestSetup.client();
    assertThrows(
        QuonfigTypeMismatchException.class,
        () -> client.getStringListOrThrow("getter.contract.double"));
  }

  @Test
  @DisplayName("STRING_LIST get_or_raise on stored bool raises type_mismatch")
  void stringListGetOrRaiseOnStoredBoolRaisesTypeMismatch() throws Exception {
    Quonfig client = TestSetup.client();
    assertThrows(
        QuonfigTypeMismatchException.class,
        () -> client.getStringListOrThrow("getter.contract.bool"));
  }

  @Test
  @DisplayName("STRING_LIST get_or_raise on stored json raises type_mismatch")
  void stringListGetOrRaiseOnStoredJsonRaisesTypeMismatch() throws Exception {
    Quonfig client = TestSetup.client();
    assertThrows(
        QuonfigTypeMismatchException.class,
        () -> client.getStringListOrThrow("getter.contract.json"));
  }

  @Test
  @DisplayName("STRING_LIST get_or_raise on stored duration raises type_mismatch")
  void stringListGetOrRaiseOnStoredDurationRaisesTypeMismatch() throws Exception {
    Quonfig client = TestSetup.client();
    assertThrows(
        QuonfigTypeMismatchException.class,
        () -> client.getStringListOrThrow("getter.contract.duration"));
  }

  @Test
  @DisplayName("JSON get_or_raise on stored string raises type_mismatch")
  void jsonGetOrRaiseOnStoredStringRaisesTypeMismatch() throws Exception {
    Quonfig client = TestSetup.client();
    assertThrows(
        QuonfigTypeMismatchException.class, () -> client.getJsonOrThrow("getter.contract.string"));
  }

  @Test
  @DisplayName("JSON get_or_raise on stored int raises type_mismatch")
  void jsonGetOrRaiseOnStoredIntRaisesTypeMismatch() throws Exception {
    Quonfig client = TestSetup.client();
    assertThrows(
        QuonfigTypeMismatchException.class, () -> client.getJsonOrThrow("getter.contract.int"));
  }

  @Test
  @DisplayName("JSON get_or_raise on stored double raises type_mismatch")
  void jsonGetOrRaiseOnStoredDoubleRaisesTypeMismatch() throws Exception {
    Quonfig client = TestSetup.client();
    assertThrows(
        QuonfigTypeMismatchException.class, () -> client.getJsonOrThrow("getter.contract.double"));
  }

  @Test
  @DisplayName("JSON get_or_raise on stored bool raises type_mismatch")
  void jsonGetOrRaiseOnStoredBoolRaisesTypeMismatch() throws Exception {
    Quonfig client = TestSetup.client();
    assertThrows(
        QuonfigTypeMismatchException.class, () -> client.getJsonOrThrow("getter.contract.bool"));
  }

  @Test
  @DisplayName("JSON get_or_raise on stored string_list raises type_mismatch")
  void jsonGetOrRaiseOnStoredStringListRaisesTypeMismatch() throws Exception {
    Quonfig client = TestSetup.client();
    assertThrows(
        QuonfigTypeMismatchException.class,
        () -> client.getJsonOrThrow("getter.contract.string_list"));
  }

  @Test
  @DisplayName("JSON get_or_raise on stored duration raises type_mismatch")
  void jsonGetOrRaiseOnStoredDurationRaisesTypeMismatch() throws Exception {
    Quonfig client = TestSetup.client();
    assertThrows(
        QuonfigTypeMismatchException.class,
        () -> client.getJsonOrThrow("getter.contract.duration"));
  }

  @Test
  @DisplayName("DURATION get_or_raise on stored string raises type_mismatch")
  void durationGetOrRaiseOnStoredStringRaisesTypeMismatch() throws Exception {
    Quonfig client = TestSetup.client();
    assertThrows(
        QuonfigTypeMismatchException.class,
        () -> client.getDurationOrThrow("getter.contract.string"));
  }

  @Test
  @DisplayName("DURATION get_or_raise on stored int raises type_mismatch")
  void durationGetOrRaiseOnStoredIntRaisesTypeMismatch() throws Exception {
    Quonfig client = TestSetup.client();
    assertThrows(
        QuonfigTypeMismatchException.class, () -> client.getDurationOrThrow("getter.contract.int"));
  }

  @Test
  @DisplayName("DURATION get_or_raise on stored double raises type_mismatch")
  void durationGetOrRaiseOnStoredDoubleRaisesTypeMismatch() throws Exception {
    Quonfig client = TestSetup.client();
    assertThrows(
        QuonfigTypeMismatchException.class,
        () -> client.getDurationOrThrow("getter.contract.double"));
  }

  @Test
  @DisplayName("DURATION get_or_raise on stored bool raises type_mismatch")
  void durationGetOrRaiseOnStoredBoolRaisesTypeMismatch() throws Exception {
    Quonfig client = TestSetup.client();
    assertThrows(
        QuonfigTypeMismatchException.class,
        () -> client.getDurationOrThrow("getter.contract.bool"));
  }

  @Test
  @DisplayName("DURATION get_or_raise on stored string_list raises type_mismatch")
  void durationGetOrRaiseOnStoredStringListRaisesTypeMismatch() throws Exception {
    Quonfig client = TestSetup.client();
    assertThrows(
        QuonfigTypeMismatchException.class,
        () -> client.getDurationOrThrow("getter.contract.string_list"));
  }

  @Test
  @DisplayName("DURATION get_or_raise on stored json raises type_mismatch")
  void durationGetOrRaiseOnStoredJsonRaisesTypeMismatch() throws Exception {
    Quonfig client = TestSetup.client();
    assertThrows(
        QuonfigTypeMismatchException.class,
        () -> client.getDurationOrThrow("getter.contract.json"));
  }

  @Test
  @DisplayName("BOOLEAN get_or_raise on stored string \"false\" raises type_mismatch")
  void booleanGetOrRaiseOnStoredStringFalseRaisesTypeMismatch() throws Exception {
    Quonfig client = TestSetup.client();
    assertThrows(
        QuonfigTypeMismatchException.class,
        () -> client.getBoolOrThrow("getter.contract.string.false"));
  }

  @Test
  @DisplayName("BOOLEAN get_or_raise on stored string \"true\" raises type_mismatch")
  void booleanGetOrRaiseOnStoredStringTrueRaisesTypeMismatch() throws Exception {
    Quonfig client = TestSetup.client();
    assertThrows(
        QuonfigTypeMismatchException.class,
        () -> client.getBoolOrThrow("getter.contract.string.true"));
  }

  @Test
  @DisplayName("INT get_or_raise on stored string \"3\" raises type_mismatch")
  void intGetOrRaiseOnStoredString3RaisesTypeMismatch() throws Exception {
    Quonfig client = TestSetup.client();
    assertThrows(
        QuonfigTypeMismatchException.class,
        () -> client.getLongOrThrow("getter.contract.string.number"));
  }

  @Test
  @DisplayName("DOUBLE get_or_raise on stored string \"3\" raises type_mismatch")
  void doubleGetOrRaiseOnStoredString3RaisesTypeMismatch() throws Exception {
    Quonfig client = TestSetup.client();
    assertThrows(
        QuonfigTypeMismatchException.class,
        () -> client.getDoubleOrThrow("getter.contract.string.number"));
  }

  @Test
  @DisplayName("DURATION get_or_raise on stored string \"PT1S\" raises type_mismatch")
  void durationGetOrRaiseOnStoredStringPt1sRaisesTypeMismatch() throws Exception {
    Quonfig client = TestSetup.client();
    assertThrows(
        QuonfigTypeMismatchException.class,
        () -> client.getDurationOrThrow("getter.contract.string.duration"));
  }

  @Test
  @DisplayName("DOUBLE get_or_raise on stored int 30 returns 30.0")
  void doubleGetOrRaiseOnStoredInt30Returns300() throws Exception {
    Quonfig client = TestSetup.client();
    assertEquals(30.0d, client.getDoubleOrThrow("getter.contract.int"));
  }

  @Test
  @DisplayName("INT get_or_raise on stored whole double 3.0 returns 3")
  void intGetOrRaiseOnStoredWholeDouble30Returns3() throws Exception {
    Quonfig client = TestSetup.client();
    assertEquals(3L, client.getLongOrThrow("getter.contract.double.whole"));
  }

  @Test
  @DisplayName("INT get_or_raise on stored double 1e300 raises type_mismatch")
  void intGetOrRaiseOnStoredDouble1e300RaisesTypeMismatch() throws Exception {
    Quonfig client = TestSetup.client();
    assertThrows(
        QuonfigTypeMismatchException.class,
        () -> client.getLongOrThrow("getter.contract.double.huge"));
  }

  @Test
  @DisplayName(
      "INT get_or_raise on stored double 2^53 (outside the safe int range) raises type_mismatch")
  void intGetOrRaiseOnStoredDouble253OutsideTheSafeIntRangeRaisesTypeMismatch() throws Exception {
    Quonfig client = TestSetup.client();
    assertThrows(
        QuonfigTypeMismatchException.class,
        () -> client.getLongOrThrow("getter.contract.double.unsafe"));
  }

  @Test
  @DisplayName(
      "INT get_or_raise on stored malformed int \"twelve\" raises unable_to_coerce_env_var")
  void intGetOrRaiseOnStoredMalformedIntTwelveRaisesUnableToCoerceEnvVar() throws Exception {
    Quonfig client = TestSetup.client();
    assertThrows(
        QuonfigCoercionException.class,
        () -> client.getLongOrThrow("getter.contract.int.malformed"));
  }

  @Test
  @DisplayName(
      "DOUBLE get_or_raise on stored malformed double \"abc\" raises unable_to_coerce_env_var")
  void doubleGetOrRaiseOnStoredMalformedDoubleAbcRaisesUnableToCoerceEnvVar() throws Exception {
    Quonfig client = TestSetup.client();
    assertThrows(
        QuonfigCoercionException.class,
        () -> client.getDoubleOrThrow("getter.contract.double.malformed"));
  }

  @Test
  @DisplayName(
      "INT get_or_raise on env-var-provided malformed int \"twelve\" raises unable_to_coerce_env_var")
  void intGetOrRaiseOnEnvVarProvidedMalformedIntTwelveRaisesUnableToCoerceEnvVar()
      throws Exception {
    TestSetup.withEnv(
        TestSetup.map("QUONFIG_ITD_GETTER_INT", "twelve"),
        () -> {
          Quonfig client = TestSetup.client();
          assertThrows(
              QuonfigCoercionException.class,
              () -> client.getLongOrThrow("getter.contract.provided.int.malformed"));
        });
  }

  @Test
  @DisplayName(
      "DOUBLE get_or_raise on env-var-provided malformed double \"abc\" raises unable_to_coerce_env_var")
  void doubleGetOrRaiseOnEnvVarProvidedMalformedDoubleAbcRaisesUnableToCoerceEnvVar()
      throws Exception {
    TestSetup.withEnv(
        TestSetup.map("QUONFIG_ITD_GETTER_DOUBLE", "abc"),
        () -> {
          Quonfig client = TestSetup.client();
          assertThrows(
              QuonfigCoercionException.class,
              () -> client.getDoubleOrThrow("getter.contract.provided.double.malformed"));
        });
  }

  @Test
  @DisplayName("BOOLEAN get_or_raise on a missing bool env var raises missing_env_var")
  void booleanGetOrRaiseOnAMissingBoolEnvVarRaisesMissingEnvVar() throws Exception {
    Quonfig client = TestSetup.client();
    assertThrows(
        QuonfigEnvVarNotSetException.class,
        () -> client.getBoolOrThrow("getter.contract.provided.bool.missing"));
  }
}
