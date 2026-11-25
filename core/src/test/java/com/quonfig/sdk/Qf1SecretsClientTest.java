package com.quonfig.sdk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.quonfig.sdk.exceptions.QuonfigDecryptionException;
import com.quonfig.sdk.telemetry.TelemetrySender;
import com.quonfig.sdk.wire.ConfigEnvelope;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Marker;
import org.slf4j.event.Level;
import org.slf4j.helpers.LegacyAbstractLogger;
import org.slf4j.helpers.MessageFormatter;

/**
 * qf1 secrets through the public client (qfg-8seuf.12): secrets/ loads last and wins, a missing
 * keyset fails only that read, parallel first reads agree, a leftover {@code decryptWith} value
 * fails (spec 13.3 R1), telemetry reports {@code SECRET} redacted over the stored ciphertext, and
 * no failing path leaks key material, the payload or the plaintext. Keys come from the vectors file
 * in the sibling integration-test-data checkout, never from the machine's env.
 */
class Qf1SecretsClientTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static JsonNode vectors;
  private static Map<String, String> keysetEnv;

  /** Corpus secrets that must fail, and the corpus value that still carries decryptWith. */
  private static final List<String> FAILING_SECRETS =
      List.of(
          "qf1.secret.cross.keyset",
          "qf1.secret.key.not.held",
          "qf1.secret.keyset.missing",
          "qf1.secret.keyset.name.invalid",
          "qf1.secret.low.order.ephemeral",
          "qf1.secret.tampered.header",
          "qf1.secret.trailing.colon",
          "qf1.legacy.decrypt.with");

  @BeforeAll
  static void loadVectors() throws Exception {
    Path p = itdRoot().resolve("tests/secrets/qf1-vectors.json");
    vectors = MAPPER.readTree(Files.readAllBytes(p));
    keysetEnv = new HashMap<>();
    Iterator<Map.Entry<String, JsonNode>> it = vectors.get("positiveEnv").fields();
    while (it.hasNext()) {
      Map.Entry<String, JsonNode> e = it.next();
      keysetEnv.put(e.getKey(), e.getValue().asText());
    }
    // The corpus also sets the invalid name QUONFIG_KEYSETX to a real key: it must never be read.
    keysetEnv.put("QUONFIG_KEYSETX", keysetEnv.get("QUONFIG_KEYSET_TEST"));
  }

  private static Path itdRoot() {
    return Paths.get(System.getProperty("user.dir"), "..", "integration-test-data").normalize();
  }

  private static String corpus() {
    return itdRoot().resolve("data/integration-tests").toString();
  }

  private static Quonfig corpusClient(Map<String, String> env, CaptureLogger log) {
    Options.Builder b =
        Options.builder()
            .datadir(corpus())
            .environment("Production")
            .envLookup(k -> Optional.ofNullable(env.get(k)))
            .enableQuonfigUserContext(false)
            .disableTelemetry(true);
    if (log != null) b.logger(log);
    return new Quonfig(b.build());
  }

  // ---------------------------------------------------------------------------------------------

  @Test
  void bootsWithNoKeysetAndFailsOnlyTheSecretRead() {
    List<String> read = Collections.synchronizedList(new ArrayList<>());
    Map<String, String> env = new HashMap<>();
    try (Quonfig q =
        new Quonfig(
            Options.builder()
                .datadir(corpus())
                .environment("Production")
                .envLookup(
                    k -> {
                      read.add(k);
                      return Optional.ofNullable(env.get(k));
                    })
                .enableQuonfigUserContext(false)
                .disableTelemetry(true)
                .build())) {
      assertEquals("my-test-value", q.getString("my-test-key", null));
      assertEquals("fallback", q.getString("qf1.secret.short", "fallback"));
      QuonfigDecryptionException e =
          assertThrows(
              QuonfigDecryptionException.class, () -> q.getStringOrThrow("qf1.secret.short"));
      assertTrue(e.getMessage().contains("qf1 keyset_missing"), e.getMessage());
      assertTrue(e.getMessage().contains("QUONFIG_KEYSET_TEST"), e.getMessage());
      EvaluationDetails<String> d = q.getStringDetails("qf1.secret.short", "fallback");
      assertEquals(Reason.ERROR, d.reason());
      assertEquals(ErrorCode.GENERAL, d.errorCode());
      // Non-secrets keep serving after the failure.
      assertEquals("my-test-value", q.getString("my-test-key", null));
    }
    assertFalse(read.contains("QUONFIG_KEYSETX"));
  }

  @Test
  void decryptsCorpusSecretsAndTheSecretShadowsItsConfigsTwin() {
    try (Quonfig q = corpusClient(keysetEnv, null)) {
      assertEquals("hello", q.getString("qf1.secret.short", null));
      assertEquals("", q.getString("qf1.secret.empty", null));
      assertEquals("hello", q.getString("qf1.secret.shadowed", null));
      assertTrue(q.getString("qf1.secret.leading.bom", "").startsWith("﻿"));
    }
  }

  @Test
  void parallelFirstReadsAllAgree() throws Exception {
    List<String> keys =
        List.of(
            "qf1.secret.short",
            "qf1.secret.multibyte",
            "qf1.secret.default.keyset",
            "qf1.secret.older.key",
            "qf1.secret.json.special");
    Map<String, String> expected = new HashMap<>();
    try (Quonfig ref = corpusClient(keysetEnv, null)) {
      for (String k : keys) expected.put(k, ref.getString(k, null));
    }
    for (String k : keys) assertNotNull(expected.get(k), k);

    for (int round = 0; round < 5; round++) {
      try (Quonfig q = corpusClient(keysetEnv, null)) {
        int threads = 32;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> results = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
          String k = keys.get(i % keys.size());
          Callable<Boolean> c =
              () -> {
                start.await();
                return expected.get(k).equals(q.getStringOrThrow(k))
                    && "fallback".equals(q.getString("qf1.secret.key.not.held", "fallback"));
              };
          results.add(pool.submit(c));
        }
        start.countDown();
        for (Future<Boolean> f : results) assertTrue(f.get(30, TimeUnit.SECONDS));
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));
      }
    }
  }

  @Test
  void leftoverDecryptWithValueFailsWithTheDecryptionError() {
    try (Quonfig q = corpusClient(keysetEnv, null)) {
      assertNull(q.getString("qf1.legacy.decrypt.with", null));
      QuonfigDecryptionException e =
          assertThrows(
              QuonfigDecryptionException.class,
              () -> q.getStringOrThrow("qf1.legacy.decrypt.with"));
      assertTrue(e.getMessage().contains("decryptWith"), e.getMessage());
    }
  }

  @Test
  void noFailingPathLeaksKeysPayloadOrPlaintext() {
    CaptureLogger log = new CaptureLogger();
    try (Quonfig q = corpusClient(keysetEnv, log)) {
      for (String key : FAILING_SECRETS) {
        String stored = storedValue(key);
        assertEquals("d", q.getString(key, "d"), key);
        EvaluationDetails<String> d = q.getStringDetails(key, "d");
        assertEquals(Reason.ERROR, d.reason(), key);
        assertNoLeak(d.errorMessage(), stored);
        QuonfigDecryptionException e =
            assertThrows(QuonfigDecryptionException.class, () -> q.getStringOrThrow(key), key);
        for (Throwable t = e; t != null; t = t.getCause()) {
          assertNoLeak(t.getMessage(), stored);
          assertNoLeak(t.toString(), stored);
          for (Throwable s : t.getSuppressed()) assertNoLeak(s.toString(), stored);
        }
        assertTrue(
            e.getMessage().contains("qf1 ") || e.getMessage().contains("decryptWith"),
            e.getMessage());
      }
      // The same holds without any keyset at all.
    }
    for (String line : log.lines()) assertNoLeak(line, null);
    assertFalse(log.lines().isEmpty());
  }

  @Test
  void typedGettersNeverEchoASecretsPlaintext() {
    CaptureLogger log = new CaptureLogger();
    try (Quonfig q = corpusClient(keysetEnv, log)) {
      String key = "qf1.secret.json.special";
      String plain = q.getString(key, null);
      assertNotNull(plain);
      assertTrue(plain.length() >= 8);
      assertEquals(7L, q.getLong(key, 7L));
      assertEquals(1.5, q.getDouble(key, 1.5));
      assertEquals(Boolean.TRUE, q.getBool(key, true));
      assertEquals(List.of("d"), q.getStringList(key, List.of("d")));
      assertEquals(Duration.ofSeconds(3), q.getDuration(key, Duration.ofSeconds(3)));
      assertEquals(Map.of("d", 1), q.getJson(key, Map.of("d", 1)));
      assertFalse(q.featureIsOn(key, new com.quonfig.sdk.eval.ContextSet()));
      List<Throwable> thrown = new ArrayList<>();
      thrown.add(assertThrows(RuntimeException.class, () -> q.getLongOrThrow(key)));
      thrown.add(assertThrows(RuntimeException.class, () -> q.getDoubleOrThrow(key)));
      thrown.add(assertThrows(RuntimeException.class, () -> q.getBoolOrThrow(key)));
      thrown.add(assertThrows(RuntimeException.class, () -> q.getStringListOrThrow(key)));
      thrown.add(assertThrows(RuntimeException.class, () -> q.getDurationOrThrow(key)));
      thrown.add(assertThrows(RuntimeException.class, () -> q.getJsonOrThrow(key)));
      for (Throwable t : thrown) {
        for (Throwable c = t; c != null; c = c.getCause()) {
          assertFalse(String.valueOf(c.getMessage()).contains(plain), c.toString());
          assertFalse(c.toString().contains(plain), c.toString());
        }
      }
      for (EvaluationDetails<?> d :
          List.of(
              q.getLongDetails(key, 7L),
              q.getDoubleDetails(key, 1.5),
              q.getBoolDetails(key, true),
              q.getDurationDetails(key, Duration.ZERO))) {
        assertEquals(Reason.ERROR, d.reason());
        assertFalse(String.valueOf(d.errorMessage()).contains(plain), d.errorMessage());
      }
      for (String line : log.lines()) assertFalse(line.contains(plain), line);
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Temp workspaces: directory decides the type; telemetry; wire type
  // ---------------------------------------------------------------------------------------------

  private static String secretDoc(String key, String type, String ciphertext, boolean conf) {
    return "{\"id\":\"id-"
        + key
        + "\",\"key\":\""
        + key
        + "\",\"type\":\""
        + type
        + "\",\"valueType\":\"string\",\"default\":{\"rules\":[{\"criteria\":[{\"operator\":"
        + "\"ALWAYS_TRUE\"}],\"value\":{\"type\":\"string\",\"value\":\""
        + ciphertext
        + "\""
        + (conf ? ",\"confidential\":true" : "")
        + "}}]}}";
  }

  private static JsonNode firstPositive() {
    return vectors.get("positive").get(0);
  }

  private static Path workspace(Path dir) throws Exception {
    Files.writeString(
        dir.resolve("quonfig.json"), "{\"workspace\":\"ws\",\"environments\":[\"production\"]}");
    Files.createDirectories(dir.resolve("configs"));
    Files.createDirectories(dir.resolve("secrets"));
    return dir;
  }

  @Test
  void theDirectoryDecidesASecretAndSecretsLoadLast(@TempDir Path tmp) throws Exception {
    JsonNode p = firstPositive();
    Path ws = workspace(tmp);
    // A configs/ twin that sorts after and a secrets/ doc whose type field says "config": the
    // secrets/ copy wins and is decrypted because of its directory.
    Files.writeString(
        ws.resolve("configs/s.json"), secretDoc("s", "config", "plain-config-copy", false));
    Files.writeString(
        ws.resolve("secrets/s.json"), secretDoc("s", "config", p.get("ciphertext").asText(), true));
    try (Quonfig q =
        new Quonfig(
            Options.builder()
                .datadir(ws.toString())
                .environment("production")
                .envLookup(k -> Optional.ofNullable(keysetEnv.get(k)))
                .disableTelemetry(true)
                .build())) {
      assertEquals(p.get("plaintext").asText(), q.getString("s", null));
      assertEquals("SECRET", q.getStringDetails("s", null).metadata().get("configType"));
    }
  }

  @Test
  void wireSecretEntryIsDecrypted() throws Exception {
    JsonNode p = firstPositive();
    String env =
        "{\"meta\":{\"version\":\"v1\",\"environment\":\"production\"},\"configs\":["
            + secretDoc("w", "secret", p.get("ciphertext").asText(), true)
            + ","
            + secretDoc("c", "config", p.get("ciphertext").asText(), true)
            + "]}";
    ConfigEnvelope envelope = MAPPER.readValue(env, ConfigEnvelope.class);
    try (Quonfig q =
        new Quonfig(
            Options.builder()
                .datafileEnvelope(envelope)
                .envLookup(k -> Optional.ofNullable(keysetEnv.get(k)))
                .disableTelemetry(true)
                .build())) {
      assertEquals(p.get("plaintext").asText(), q.getString("w", null));
      // A qf1 string outside the secret family is not decrypted: the type decides.
      assertEquals(p.get("ciphertext").asText(), q.getString("c", null));
    }
  }

  static final class CapturingSender implements TelemetrySender {
    final List<Map<String, Object>> sent = Collections.synchronizedList(new ArrayList<>());

    @Override
    public void send(Map<String, Object> payload) {
      sent.add(payload);
    }
  }

  @Test
  void telemetryReportsSecretRedactedOverTheStoredValueEvenWithoutConfidential(@TempDir Path tmp)
      throws Exception {
    JsonNode p = firstPositive();
    Path ws = workspace(tmp);
    String ct = p.get("ciphertext").asText();
    Files.writeString(ws.resolve("secrets/s.json"), secretDoc("s", "secret", ct, false));
    CapturingSender sender = new CapturingSender();
    try (Quonfig q =
        new Quonfig(
            Options.builder()
                .datadir(ws.toString())
                .environment("production")
                .envLookup(k -> Optional.ofNullable(keysetEnv.get(k)))
                .telemetrySender(sender)
                .telemetryInitialDelay(Duration.ofSeconds(60))
                .telemetryFlushInterval(Duration.ofSeconds(60))
                .telemetryMaxInterval(Duration.ofSeconds(600))
                .build())) {
      assertEquals(p.get("plaintext").asText(), q.getString("s", null));
      q.flush();
    }
    String json = MAPPER.writeValueAsString(sender.sent);
    assertTrue(json.contains("\"type\":\"SECRET\""), json);
    String md5 =
        java.util.HexFormat.of()
            .formatHex(
                java.security.MessageDigest.getInstance("MD5")
                    .digest(ct.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    assertTrue(json.contains("*****" + md5.substring(0, 5)), json);
    assertFalse(json.contains(p.get("plaintext").asText()), json);
  }

  // ---------------------------------------------------------------------------------------------

  private static String storedValue(String key) {
    for (String dir : List.of("secrets", "configs")) {
      Path f = Paths.get(corpus(), dir, key + ".json");
      if (Files.isRegularFile(f)) {
        try {
          return MAPPER
              .readTree(Files.readAllBytes(f))
              .path("default")
              .path("rules")
              .get(0)
              .path("value")
              .path("value")
              .asText();
        } catch (Exception e) {
          throw new AssertionError(e);
        }
      }
    }
    throw new AssertionError("no corpus doc " + key);
  }

  /** No private entry, env value slice, or payload slice in {@code text}. */
  private static void assertNoLeak(String text, String stored) {
    if (text == null) return;
    assertFalse(text.contains("qf1-priv-"), text);
    for (String v : keysetEnv.values()) {
      for (String piece : v.split(",", -1)) {
        String s = piece.replaceAll("^[ \\t\\r\\n]+|[ \\t\\r\\n]+$", "");
        if (s.startsWith("qf1-priv-")) {
          assertFalse(text.contains(s.substring(9, 21)), text);
        }
      }
    }
    if (stored != null) {
      String[] parts = stored.split(":", -1);
      String payload =
          stored.startsWith("qf1:") ? parts[parts.length > 3 ? 3 : parts.length - 1] : stored;
      if (payload.length() >= 16) assertFalse(text.contains(payload.substring(0, 16)), text);
    }
  }

  /** Records every log line, all levels enabled. */
  static final class CaptureLogger extends LegacyAbstractLogger {
    private final List<String> lines = Collections.synchronizedList(new ArrayList<>());

    CaptureLogger() {
      this.name = "capture";
    }

    List<String> lines() {
      synchronized (lines) {
        return new ArrayList<>(lines);
      }
    }

    @Override
    protected String getFullyQualifiedCallerName() {
      return null;
    }

    @Override
    protected void handleNormalizedLoggingCall(
        Level level, Marker marker, String msg, Object[] args, Throwable t) {
      String line = MessageFormatter.arrayFormat(msg, args, t).getMessage();
      if (t != null) line = line + " " + t;
      lines.add(level + " " + line);
    }

    @Override
    public boolean isTraceEnabled() {
      return true;
    }

    @Override
    public boolean isDebugEnabled() {
      return true;
    }

    @Override
    public boolean isInfoEnabled() {
      return true;
    }

    @Override
    public boolean isWarnEnabled() {
      return true;
    }

    @Override
    public boolean isErrorEnabled() {
      return true;
    }
  }
}
