package com.quonfig.sdk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.quonfig.sdk.eval.Resolver;
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
                .enableQuonfigUserContext(false)
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
                .enableQuonfigUserContext(false)
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
                .enableQuonfigUserContext(false)
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
                .enableQuonfigUserContext(false)
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

  @Test
  void decryptWithOfEveryJsonTypeFailsThatReadOnly(@TempDir Path tmp) throws Exception {
    Path ws = workspace(tmp);
    Map<String, String> shapes = new java.util.LinkedHashMap<>();
    shapes.put("dw.string", "\"some.key\"");
    shapes.put("dw.number", "5");
    shapes.put("dw.zero", "0");
    shapes.put("dw.true", "true");
    shapes.put("dw.false", "false");
    shapes.put("dw.array", "[]");
    shapes.put("dw.object", "{}");
    shapes.put("dw.object.full", "{\"k\":\"v\"}");
    // None: JSON null and the empty string are "no decryptWith".
    shapes.put("dw.null", "null");
    shapes.put("dw.empty", "\"\"");
    for (Map.Entry<String, String> e : shapes.entrySet()) {
      Files.writeString(
          ws.resolve("configs/" + e.getKey() + ".json"),
          "{\"key\":\""
              + e.getKey()
              + "\",\"type\":\"config\",\"valueType\":\"string\",\"default\":{\"rules\":[{"
              + "\"criteria\":[{\"operator\":\"ALWAYS_TRUE\"}],\"value\":{\"type\":\"string\","
              + "\"value\":\"stored-text\",\"confidential\":true,\"decryptWith\":"
              + e.getValue()
              + "}}]}}");
    }
    Files.writeString(
        ws.resolve("configs/plain.json"),
        "{\"key\":\"plain\",\"type\":\"config\",\"valueType\":\"string\",\"default\":{\"rules\":[{"
            + "\"criteria\":[{\"operator\":\"ALWAYS_TRUE\"}],\"value\":{\"type\":\"string\","
            + "\"value\":\"ok\"}}]}}");
    List<String> read = Collections.synchronizedList(new ArrayList<>());
    try (Quonfig q =
        new Quonfig(
            Options.builder()
                .datadir(ws.toString())
                .environment("production")
                .envLookup(
                    k -> {
                      read.add(k);
                      return Optional.empty();
                    })
                .disableTelemetry(true)
                .enableQuonfigUserContext(false)
                .build())) {
      assertEquals("ok", q.getString("plain", null));
      for (String key : shapes.keySet()) {
        if (key.equals("dw.null") || key.equals("dw.empty")) {
          assertEquals("stored-text", q.getString(key, "d"), key);
          continue;
        }
        assertEquals("d", q.getString(key, "d"), key);
        QuonfigDecryptionException ex =
            assertThrows(QuonfigDecryptionException.class, () -> q.getStringOrThrow(key), key);
        assertTrue(ex.getMessage().contains("decryptWith"), ex.getMessage());
        assertNull(ex.errorClass());
        assertFalse(ex.getMessage().contains("stored-text"), ex.getMessage());
      }
    }
    // Options itself reads QUONFIG_BACKEND_SDK_KEY / QUONFIG_DOMAIN at build time; no keyset and
    // no other secret-related variable may be read for these values.
    List<String> keysetReads = new ArrayList<>();
    for (String k : read)
      if (k.startsWith("QUONFIG_KEYSET") || k.contains("ENCRYPTION")) keysetReads.add(k);
    assertEquals(List.of(), keysetReads);
  }

  @Test
  void errorClassIsExposedWithoutParsingText() {
    try (Quonfig q = corpusClient(keysetEnv, null)) {
      assertEquals(
          "key_not_held",
          assertThrows(
                  QuonfigDecryptionException.class,
                  () -> q.getStringOrThrow("qf1.secret.key.not.held"))
              .errorClass());
      assertEquals(
          "invalid_keyset_name",
          assertThrows(
                  QuonfigDecryptionException.class,
                  () -> q.getStringOrThrow("qf1.secret.keyset.name.invalid"))
              .errorClass());
      assertEquals(
          "keyset_missing",
          assertThrows(
                  QuonfigDecryptionException.class,
                  () -> q.getStringOrThrow("qf1.secret.keyset.missing"))
              .errorClass());
    }
  }

  @Test
  void invalidUtf8SecretNeverLeaksItsKnownPrefixThroughTheClient(@TempDir Path tmp)
      throws Exception {
    com.quonfig.sdk.eval.Qf1TestSeal.Key key = com.quonfig.sdk.eval.Qf1TestSeal.mint();
    String sentinel = "SENTINEL-" + java.util.UUID.randomUUID();
    byte[] sb = sentinel.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    byte[] plain = java.util.Arrays.copyOf(sb, sb.length + 3);
    plain[sb.length] = (byte) 0xed;
    plain[sb.length + 1] = (byte) 0xa0;
    plain[sb.length + 2] = (byte) 0x80;
    String ct = com.quonfig.sdk.eval.Qf1TestSeal.seal("QUONFIG_KEYSET_HYGIENE", key, plain);
    Path ws = workspace(tmp);
    Files.writeString(ws.resolve("secrets/h.json"), secretDoc("h", "secret", ct, true));
    CaptureLogger log = new CaptureLogger();
    QuonfigDecryptionException e;
    try (Quonfig q =
        new Quonfig(
            Options.builder()
                .datadir(ws.toString())
                .environment("production")
                .envLookup(
                    k ->
                        "QUONFIG_KEYSET_HYGIENE".equals(k)
                            ? Optional.of(key.privateEntry)
                            : Optional.empty())
                .disableTelemetry(true)
                .enableQuonfigUserContext(false)
                .logger(log)
                .build())) {
      assertEquals("d", q.getString("h", "d"));
      EvaluationDetails<String> d = q.getStringDetails("h", "d");
      assertFalse(String.valueOf(d.errorMessage()).contains("SENTINEL"), d.errorMessage());
      e = assertThrows(QuonfigDecryptionException.class, () -> q.getStringOrThrow("h"));
    }
    assertEquals("decrypt_failed", e.errorClass());
    java.io.StringWriter trace = new java.io.StringWriter();
    e.printStackTrace(new java.io.PrintWriter(trace));
    List<String> texts = new ArrayList<>(log.lines());
    texts.add(trace.toString());
    for (Throwable t = e; t != null; t = t.getCause()) {
      texts.add(String.valueOf(t.getMessage()));
      texts.add(t.toString());
      for (Throwable sup : t.getSuppressed()) texts.add(sup.toString());
    }
    for (String text : texts) {
      assertFalse(text.contains("SENTINEL"), text);
      assertFalse(text.contains(key.privateEntry.substring(9, 21)), text);
      assertFalse(text.contains(ct.substring(ct.length() - 20)), text);
    }
    assertFalse(log.lines().isEmpty());
  }

  private static Quonfig tempClient(Path ws, Resolver.EnvLookup env, CaptureLogger log) {
    Options.Builder b =
        Options.builder()
            .datadir(ws.toString())
            .environment("production")
            .envLookup(env)
            .disableTelemetry(true)
            .enableQuonfigUserContext(false);
    if (log != null) b.logger(log);
    return new Quonfig(b.build());
  }

  @Test
  void decryptWithBeatsTheSecretPathInDatadirAndOnTheWire(@TempDir Path tmp) throws Exception {
    JsonNode p = firstPositive();
    String ct = p.get("ciphertext").asText();
    String doc =
        secretDoc("both", "secret", ct, true)
            .replace("\"confidential\":true", "\"confidential\":true,\"decryptWith\":\"old.key\"");
    Path ws = workspace(tmp);
    Files.writeString(ws.resolve("secrets/both.json"), doc);
    try (Quonfig q = tempClient(ws, k -> Optional.ofNullable(keysetEnv.get(k)), null)) {
      assertEquals("d", q.getString("both", "d"));
      QuonfigDecryptionException e =
          assertThrows(QuonfigDecryptionException.class, () -> q.getStringOrThrow("both"));
      assertTrue(e.getMessage().contains("decryptWith"), e.getMessage());
    }
    ConfigEnvelope envelope =
        MAPPER.readValue(
            "{\"meta\":{\"version\":\"v1\",\"environment\":\"production\"},\"configs\":["
                + doc
                + "]}",
            ConfigEnvelope.class);
    try (Quonfig q =
        new Quonfig(
            Options.builder()
                .datafileEnvelope(envelope)
                .envLookup(k -> Optional.ofNullable(keysetEnv.get(k)))
                .disableTelemetry(true)
                .enableQuonfigUserContext(false)
                .build())) {
      QuonfigDecryptionException e =
          assertThrows(QuonfigDecryptionException.class, () -> q.getStringOrThrow("both"));
      assertTrue(e.getMessage().contains("decryptWith"), e.getMessage());
    }
  }

  @Test
  void tamperedTagLeaksNothingThroughTheClientOrItsLogs(@TempDir Path tmp) throws Exception {
    com.quonfig.sdk.eval.Qf1TestSeal.Key key = com.quonfig.sdk.eval.Qf1TestSeal.mint();
    String sentinel = "SENTINEL-" + java.util.UUID.randomUUID();
    String ct =
        com.quonfig.sdk.eval.Qf1TestSeal.tamperTag(
            com.quonfig.sdk.eval.Qf1TestSeal.seal(
                "QUONFIG_KEYSET_T",
                key,
                sentinel.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    Path ws = workspace(tmp);
    Files.writeString(ws.resolve("secrets/t.json"), secretDoc("t", "secret", ct, true));
    CaptureLogger log = new CaptureLogger();
    QuonfigDecryptionException e;
    try (Quonfig q = tempClient(ws, k -> Optional.of(key.privateEntry), log)) {
      assertEquals("d", q.getString("t", "d"));
      e = assertThrows(QuonfigDecryptionException.class, () -> q.getStringOrThrow("t"));
    }
    assertEquals("decrypt_failed", e.errorClass());
    java.io.StringWriter trace = new java.io.StringWriter();
    e.printStackTrace(new java.io.PrintWriter(trace));
    List<String> texts = new ArrayList<>(log.lines());
    texts.add(trace.toString());
    for (String text : texts) {
      assertFalse(text.contains("SENTINEL"), text);
      assertFalse(text.contains(key.privateEntry.substring(9, 21)), text);
    }
  }

  @Test
  void junkInSecretsIsTreatedExactlyAsInConfigs(@TempDir Path tmp) throws Exception {
    JsonNode p = firstPositive();
    Path ws = workspace(tmp);
    Files.writeString(
        ws.resolve("secrets/s.json"), secretDoc("s", "secret", p.get("ciphertext").asText(), true));
    // Ignored in every directory on main: non-.json files, dotfiles, directories (empty or not).
    Files.writeString(ws.resolve("secrets/README.md"), "not json, has a secret-looking qf1-priv-x");
    Files.writeString(ws.resolve("secrets/.hidden.json"), "{ not json");
    Files.createDirectories(ws.resolve("secrets/nested/empty"));
    try (Quonfig q = tempClient(ws, k -> Optional.ofNullable(keysetEnv.get(k)), null)) {
      assertEquals(p.get("plaintext").asText(), q.getString("s", null));
    }
    // A malformed *.json, or one with no "key", fails datadir init in configs/ on main; secrets/ is
    // neither stricter nor more lenient. The error names the path, never the file's content.
    for (String junk :
        List.of(
            "{ not json qf1-priv-content", "{\"type\":\"secret\",\"note\":\"qf1-priv-content\"}")) {
      for (String dir : List.of("configs", "secrets")) {
        Path w = Files.createTempDirectory(tmp, "junk");
        workspace(w);
        Files.writeString(w.resolve(dir + "/junk.json"), junk);
        RuntimeException ex =
            assertThrows(
                RuntimeException.class, () -> tempClient(w, k -> Optional.empty(), null).close());
        for (Throwable t = ex; t != null; t = t.getCause()) {
          assertFalse(String.valueOf(t.getMessage()).contains("qf1-priv-content"), t.toString());
        }
      }
    }
  }

  @Test
  void aRuntimeWithoutX25519FailsOnlySecretReads() throws Exception {
    java.lang.reflect.Field f =
        Class.forName("com.quonfig.sdk.eval.Qf1Reader").getDeclaredField("keyAgreementAlgorithm");
    f.setAccessible(true);
    Object saved = f.get(null);
    try (Quonfig q = corpusClient(keysetEnv, null)) {
      f.set(null, "X25519-NOT-PROVIDED");
      assertEquals("my-test-value", q.getString("my-test-key", null));
      assertEquals("d", q.getString("qf1.secret.short", "d"));
      QuonfigDecryptionException e =
          assertThrows(
              QuonfigDecryptionException.class, () -> q.getStringOrThrow("qf1.secret.short"));
      assertTrue(e.getMessage().contains("FIPS"), e.getMessage());
      assertFalse(e.getMessage().contains("not a qf1 private key"), e.getMessage());
      f.set(null, saved);
      assertEquals("hello", q.getString("qf1.secret.short", null));
    } finally {
      f.set(null, saved);
    }
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
