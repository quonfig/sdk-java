package com.quonfig.sdk.eval;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.PrivateKey;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import java.util.stream.Stream;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

/**
 * Drives {@link Qf1Reader} with every reader-relevant case of the qf1 known-answer vectors (spec
 * 14), read from the sibling integration-test-data checkout CI uses ({@code
 * ../integration-test-data/tests/secrets/qf1-vectors.json}). No key material is written in this
 * file: every private key entry comes from the vectors file at run time.
 */
class Qf1VectorsTest {

  private static final HexFormat HEX = HexFormat.of();
  private static JsonNode vectors;

  @BeforeAll
  static void load() throws Exception {
    Path p =
        Paths.get(
                System.getProperty("user.dir"),
                "..",
                "integration-test-data",
                "tests",
                "secrets",
                "qf1-vectors.json")
            .normalize();
    assertTrue(Files.isRegularFile(p), "qf1 vectors not found at " + p);
    vectors = new ObjectMapper().readTree(Files.readAllBytes(p));
    assertEquals("qf1", vectors.get("format").asText());
  }

  private static Resolver.EnvLookup envOf(JsonNode env) {
    return name -> {
      JsonNode v = env == null ? null : env.get(name);
      return v == null || v.isNull() ? Optional.empty() : Optional.of(v.asText());
    };
  }

  private static byte[] hex(JsonNode n) {
    return HEX.parseHex(n.asText());
  }

  private static List<JsonNode> list(String member) {
    List<JsonNode> out = new ArrayList<>();
    vectors.get(member).forEach(out::add);
    return out;
  }

  // ----- sanity: constants and RFC checks -----

  @Test
  void constantsMatchTheSpec() {
    JsonNode c = vectors.get("constants");
    assertEquals(c.get("hkdfInfoHex").asText(), HEX.formatHex(Qf1Reader.HKDF_INFO));
    assertEquals(c.get("keysetNamePattern").asText(), Qf1Reader.KEYSET_NAME_PATTERN);
    assertEquals(44, c.get("okmLength").asInt());
  }

  @Test
  void rfc7748Section61() {
    JsonNode r = vectors.get("rfcChecks").get("rfc7748_6_1");
    byte[] base = new byte[32];
    base[0] = 9;
    PrivateKey alice = Qf1Reader.privateKey(hex(r.get("alicePrivateHex")));
    PrivateKey bob = Qf1Reader.privateKey(hex(r.get("bobPrivateHex")));
    assertEquals(r.get("alicePublicHex").asText(), HEX.formatHex(Qf1Reader.x25519(alice, base)));
    assertEquals(r.get("bobPublicHex").asText(), HEX.formatHex(Qf1Reader.x25519(bob, base)));
    assertEquals(
        r.get("sharedSecretHex").asText(),
        HEX.formatHex(Qf1Reader.x25519(alice, hex(r.get("bobPublicHex")))));
  }

  @Test
  void rfc5869A1() {
    JsonNode r = vectors.get("rfcChecks").get("rfc5869_A_1");
    byte[] prk = Qf1Reader.hkdfExtract(hex(r.get("saltHex")), hex(r.get("ikmHex")));
    assertEquals(r.get("prkHex").asText(), HEX.formatHex(prk));
    byte[] okm = Qf1Reader.hkdfExpand(prk, hex(r.get("infoHex")), r.get("length").asInt());
    assertEquals(r.get("okmHex").asText(), HEX.formatHex(okm));
  }

  // ----- positive: every intermediate, then the decrypt -----

  @TestFactory
  Stream<DynamicTest> positiveIntermediates() {
    return list("positive").stream()
        .map(
            p ->
                DynamicTest.dynamicTest(
                    p.get("name").asText(),
                    () -> {
                      byte[] base = new byte[32];
                      base[0] = 9;
                      byte[] recipientRaw =
                          Qf1Reader.parsePrivateKeyEntry(
                              p.get("recipientPrivateKeyEntry").asText());
                      PrivateKey recipient = Qf1Reader.privateKey(recipientRaw);
                      byte[] pubR = Qf1Reader.x25519(recipient, base);
                      assertEquals(p.get("recipientPublicKeyHex").asText(), HEX.formatHex(pubR));
                      assertEquals(p.get("fingerprint").asText(), Qf1Reader.fingerprint(pubR));

                      PrivateKey eph = Qf1Reader.privateKey(hex(p.get("ephemeralPrivateKeyHex")));
                      byte[] e = Qf1Reader.x25519(eph, base);
                      assertEquals(p.get("ephemeralPublicKeyHex").asText(), HEX.formatHex(e));

                      // Both sides agree on S.
                      byte[] sWriter = Qf1Reader.x25519(eph, pubR);
                      byte[] sReader = Qf1Reader.x25519(recipient, e);
                      assertEquals(p.get("sharedSecretHex").asText(), HEX.formatHex(sWriter));
                      assertArrayEquals(sWriter, sReader);

                      byte[] salt = new byte[64];
                      System.arraycopy(e, 0, salt, 0, 32);
                      System.arraycopy(pubR, 0, salt, 32, 32);
                      assertEquals(p.get("hkdfSaltHex").asText(), HEX.formatHex(salt));
                      assertEquals(
                          p.get("hkdfInfoHex").asText(), HEX.formatHex(Qf1Reader.HKDF_INFO));
                      byte[] prk = Qf1Reader.hkdfExtract(salt, sReader);
                      assertEquals(p.get("prkHex").asText(), HEX.formatHex(prk));
                      byte[] okm = Qf1Reader.deriveOkm(sReader, e, pubR);
                      assertEquals(p.get("okmHex").asText(), HEX.formatHex(okm));
                      assertEquals(p.get("aesKeyHex").asText(), HEX.formatHex(okm, 0, 32));
                      assertEquals(p.get("nonceHex").asText(), HEX.formatHex(okm, 32, 44));

                      Qf1Reader.Ciphertext ct = Qf1Reader.parse(p.get("ciphertext").asText());
                      assertEquals(p.get("keyset").asText(), ct.keyset);
                      assertEquals(p.get("fingerprint").asText(), ct.fingerprint);
                      assertEquals(
                          p.get("aadAscii").asText(),
                          new String(ct.aad, StandardCharsets.US_ASCII));
                      assertEquals(p.get("aadHex").asText(), HEX.formatHex(ct.aad));
                      assertEquals(
                          p.get("ephemeralPublicKeyHex").asText(), HEX.formatHex(ct.ephemeral));
                      assertEquals(p.get("ciphertextAndTagHex").asText(), HEX.formatHex(ct.sealed));

                      // Re-seal with the derived key and nonce: byte-for-byte the stored blob.
                      Cipher gcm = Cipher.getInstance("AES/GCM/NoPadding");
                      gcm.init(
                          Cipher.ENCRYPT_MODE,
                          new SecretKeySpec(okm, 0, 32, "AES"),
                          new GCMParameterSpec(128, okm, 32, 12));
                      gcm.updateAAD(ct.aad);
                      byte[] sealed = gcm.doFinal(hex(p.get("plaintextHex")));
                      assertEquals(p.get("ciphertextAndTagHex").asText(), HEX.formatHex(sealed));
                      assertEquals(
                          p.get("blobHex").asText(), HEX.formatHex(e) + HEX.formatHex(sealed));
                      assertEquals(
                          p.get("plaintextHex").asText(),
                          HEX.formatHex(
                              p.get("plaintext").asText().getBytes(StandardCharsets.UTF_8)));
                    }));
  }

  @TestFactory
  Stream<DynamicTest> positiveDecrypts() {
    Qf1Reader reader = new Qf1Reader(envOf(vectors.get("positiveEnv")));
    return list("positive").stream()
        .map(
            p ->
                DynamicTest.dynamicTest(
                    p.get("name").asText(),
                    () -> {
                      String out = reader.decrypt(p.get("ciphertext").asText());
                      assertEquals(p.get("plaintext").asText(), out);
                      assertEquals(
                          p.get("plaintextHex").asText(),
                          HEX.formatHex(out.getBytes(StandardCharsets.UTF_8)));
                    }));
  }

  @TestFactory
  Stream<DynamicTest> decryptOnly() {
    List<JsonNode> cases = list("decryptOnly");
    assertFalse(cases.isEmpty());
    return cases.stream()
        .map(
            d ->
                DynamicTest.dynamicTest(
                    d.get("name").asText(),
                    () -> {
                      String out =
                          new Qf1Reader(envOf(d.get("env"))).decrypt(d.get("ciphertext").asText());
                      assertEquals(d.get("plaintext").asText(), out);
                      assertEquals(
                          d.get("plaintextHex").asText(),
                          HEX.formatHex(out.getBytes(StandardCharsets.UTF_8)));
                    }));
  }

  // ----- negative: the exact class, under the spec's reader order -----

  @TestFactory
  Stream<DynamicTest> negatives() {
    List<JsonNode> cases = list("negative");
    assertEquals(58, cases.size(), "vector count changed: review new cases");
    return cases.stream()
        .map(
            n ->
                DynamicTest.dynamicTest(
                    n.get("name").asText()
                        + (n.get("mustReject").asBoolean() ? " [mustReject]" : ""),
                    () -> {
                      Qf1Reader reader = new Qf1Reader(envOf(n.get("env")));
                      Qf1Reader.Qf1Exception e =
                          assertThrows(
                              Qf1Reader.Qf1Exception.class,
                              () -> reader.decrypt(n.get("ciphertext").asText()));
                      String expected = n.get("expectedError").asText();
                      assertEquals(expected, e.errorClass().wireName(), e.getMessage());
                      assertTrue(
                          e.getMessage().startsWith("qf1 " + expected + ": "), e.getMessage());
                      assertNoLeak(e, n.get("env"), n.get("ciphertext").asText());
                    }));
  }

  @Test
  void mustRejectCasesArePresent() {
    long n = list("negative").stream().filter(c -> c.get("mustReject").asBoolean()).count();
    assertEquals(34, n);
  }

  // ----- key entries and env values -----

  @TestFactory
  Stream<DynamicTest> keyEntryCases() {
    List<DynamicTest> out = new ArrayList<>();
    for (JsonNode c : list("keyEntryCases")) {
      String kind = c.get("kind").asText();
      if ("publicKeyEntry".equals(kind)) continue; // readers never parse public entries (10.1)
      out.add(
          DynamicTest.dynamicTest(
              kind + ": " + c.get("name").asText(),
              () -> {
                String input = c.get("input").asText();
                if ("privateKeyEntry".equals(kind)) {
                  if (c.has("expectedHex")) {
                    assertEquals(
                        c.get("expectedHex").asText(),
                        HEX.formatHex(Qf1Reader.parsePrivateKeyEntry(input)));
                  } else {
                    Qf1Reader.Qf1Exception e =
                        assertThrows(
                            Qf1Reader.Qf1Exception.class,
                            () -> Qf1Reader.parsePrivateKeyEntry(input));
                    assertEquals(c.get("expectedError").asText(), e.errorClass().wireName());
                    assertFalse(e.getMessage().contains(input));
                  }
                } else if ("envValue".equals(kind)) {
                  String ks = c.get("keyset").asText();
                  if (c.has("expectedFingerprints")) {
                    Qf1Reader.Keyset parsed = Qf1Reader.parseKeyset(ks, input);
                    TreeSet<String> want = new TreeSet<>();
                    c.get("expectedFingerprints").forEach(f -> want.add(f.asText()));
                    assertEquals(new ArrayList<>(want), parsed.fingerprints());
                  } else {
                    Qf1Reader.Qf1Exception e =
                        assertThrows(
                            Qf1Reader.Qf1Exception.class, () -> Qf1Reader.parseKeyset(ks, input));
                    assertEquals(c.get("expectedError").asText(), e.errorClass().wireName());
                    assertTrue(e.getMessage().contains(ks), e.getMessage());
                    assertNoEnvLeak(e.getMessage(), input);
                  }
                } else {
                  fail("unknown keyEntryCases kind " + kind);
                }
              }));
    }
    assertEquals(18, out.size());
    return out.stream();
  }

  // ----- error text (9.2) -----

  @Test
  void keyNotHeldNamesTheKeysetAndFingerprintsHeldAndNeeded() {
    JsonNode n = negativeNamed("fingerprint-not-held");
    Qf1Reader.Qf1Exception e =
        assertThrows(
            Qf1Reader.Qf1Exception.class,
            () -> new Qf1Reader(envOf(n.get("env"))).decrypt(n.get("ciphertext").asText()));
    Qf1Reader.Ciphertext ct = parseOrFail(n.get("ciphertext").asText());
    assertTrue(e.getMessage().contains(ct.keyset), e.getMessage());
    assertTrue(e.getMessage().contains("fingerprint " + ct.fingerprint), e.getMessage());
    assertTrue(e.getMessage().matches(".*fingerprints \\[[0-9a-f]{8}( [0-9a-f]{8})*\\]$"));
  }

  @Test
  void keysetInvalidNamesTheEntryByPositionOnly() throws Exception {
    // The second non-empty piece is bad; the empty pieces around it do not count (9.2).
    String good = firstPrivateEntryOf("QUONFIG_KEYSET_TEST");
    Qf1Reader.Qf1Exception e =
        assertThrows(
            Qf1Reader.Qf1Exception.class,
            () -> Qf1Reader.parseKeyset("QUONFIG_KEYSET_TEST", " ,," + good + ", ,not-a-key,"));
    assertEquals(
        "qf1 keyset_invalid: QUONFIG_KEYSET_TEST entry 2 is not a qf1 private key", e.getMessage());
  }

  @Test
  void keysetMissingNamesKeysetAndFingerprint() {
    JsonNode n = negativeNamed("keyset-missing");
    Qf1Reader.Qf1Exception e =
        assertThrows(
            Qf1Reader.Qf1Exception.class,
            () -> new Qf1Reader(envOf(n.get("env"))).decrypt(n.get("ciphertext").asText()));
    Qf1Reader.Ciphertext ct = parseOrFail(n.get("ciphertext").asText());
    assertEquals(
        "qf1 keyset_missing: value is encrypted to "
            + ct.keyset
            + " fingerprint "
            + ct.fingerprint
            + "; this runtime has no "
            + ct.keyset,
        e.getMessage());
  }

  @Test
  void invalidKeysetNameIsQuotedTruncatedAndEscaped() {
    assertEquals("\"HOME\\u000aQUONFIG_KEYSET\"", Qf1Reader.quoteName("HOME\nQUONFIG_KEYSET"));
    String longName = "Q".repeat(100);
    assertEquals("\"" + "Q".repeat(64) + "\"", Qf1Reader.quoteName(longName));
  }

  @Test
  void invalidKeysetNameNeverReadsTheEnv() {
    List<String> read = new ArrayList<>();
    Qf1Reader reader =
        new Qf1Reader(
            name -> {
              read.add(name);
              return Optional.empty();
            });
    for (JsonNode n : list("negative")) {
      if (!"invalid_keyset_name".equals(n.get("expectedError").asText())) continue;
      assertThrows(
          Qf1Reader.Qf1Exception.class, () -> reader.decrypt(n.get("ciphertext").asText()));
    }
    assertEquals(List.of(), read);
  }

  @Test
  void onlyTheNamedKeysetIsRead() throws Exception {
    JsonNode p = list("positive").get(0);
    List<String> read = new ArrayList<>();
    JsonNode env = vectors.get("positiveEnv");
    Qf1Reader reader =
        new Qf1Reader(
            name -> {
              read.add(name);
              return envOf(env).lookup(name);
            });
    reader.decrypt(p.get("ciphertext").asText());
    assertEquals(List.of(p.get("keyset").asText()), read);
  }

  @Test
  void cacheFollowsTheEnvValue() throws Exception {
    // A shared client may first read a secret with the keyset unset, then with it set, then with
    // another value: the result must follow the current value (never a stale cached failure).
    JsonNode p = list("positive").get(0);
    String ks = p.get("keyset").asText();
    String good = vectors.get("positiveEnv").get(ks).asText();
    Map<String, String> env = new java.util.HashMap<>();
    Qf1Reader reader = new Qf1Reader(name -> Optional.ofNullable(env.get(name)));
    String ct = p.get("ciphertext").asText();
    assertEquals(
        Qf1Reader.ErrorClass.KEYSET_MISSING,
        assertThrows(Qf1Reader.Qf1Exception.class, () -> reader.decrypt(ct)).errorClass());
    env.put(ks, "garbage");
    assertEquals(
        Qf1Reader.ErrorClass.KEYSET_INVALID,
        assertThrows(Qf1Reader.Qf1Exception.class, () -> reader.decrypt(ct)).errorClass());
    env.put(ks, good);
    assertEquals(p.get("plaintext").asText(), reader.decrypt(ct));
    assertEquals(p.get("plaintext").asText(), reader.decrypt(ct));
    env.remove(ks);
    assertEquals(
        Qf1Reader.ErrorClass.KEYSET_MISSING,
        assertThrows(Qf1Reader.Qf1Exception.class, () -> reader.decrypt(ct)).errorClass());
  }

  @Test
  void strictUtf8KeepsABomAndRejectsReplacementCandidates() throws Exception {
    assertEquals(
        "﻿x", Qf1Reader.decodeUtf8Strict(new byte[] {(byte) 0xef, (byte) 0xbb, (byte) 0xbf, 'x'}));
    assertThrows(
        java.nio.charset.CharacterCodingException.class,
        () -> Qf1Reader.decodeUtf8Strict(new byte[] {(byte) 0xed, (byte) 0xa0, (byte) 0x80}));
    assertThrows(
        java.nio.charset.CharacterCodingException.class,
        () -> Qf1Reader.decodeUtf8Strict(new byte[] {(byte) 0xc0, (byte) 0x80}));
  }

  @Test
  void base64urlIsStrict() {
    assertNotNull(Qf1Reader.decodeBase64url("AAAA"));
    assertNull(Qf1Reader.decodeBase64url(""));
    assertNull(Qf1Reader.decodeBase64url("AAA="));
    assertNull(Qf1Reader.decodeBase64url("AA+A"));
    assertNull(Qf1Reader.decodeBase64url("AAAAA"));
    assertNull(Qf1Reader.decodeBase64url("AB")); // non-zero trailing bits
    assertNull(Qf1Reader.decodeBase64url("AAAA\n"));
  }

  // ----- helpers -----

  private static JsonNode negativeNamed(String name) {
    for (JsonNode n : list("negative")) {
      if (name.equals(n.get("name").asText())) return n;
    }
    throw new AssertionError("no negative vector " + name);
  }

  private static Qf1Reader.Ciphertext parseOrFail(String v) {
    try {
      return Qf1Reader.parse(v);
    } catch (Qf1Reader.Qf1Exception e) {
      throw new AssertionError(e.getMessage());
    }
  }

  private static String firstPrivateEntryOf(String keyset) {
    for (JsonNode ks : list("keysets")) {
      if (keyset.equals(ks.get("name").asText())) {
        return ks.get("keys").get(0).get("privateKeyEntry").asText();
      }
    }
    throw new AssertionError("no keyset " + keyset);
  }

  /** The error carries no cause, no suppressed exceptions, and no env, key or payload text. */
  static void assertNoLeak(Throwable e, JsonNode env, String ciphertext) {
    assertNull(e.getCause());
    assertEquals(0, e.getSuppressed().length);
    String text = e.getMessage() + "\n" + e;
    assertFalse(text.contains("qf1-priv-"), text);
    if (env != null) {
      Iterator<Map.Entry<String, JsonNode>> it = env.fields();
      while (it.hasNext()) assertNoEnvLeak(text, it.next().getValue().asText());
    }
    String[] parts = ciphertext.split(":", -1);
    String payload = parts[parts.length - 1];
    if (payload.length() >= 16) assertFalse(text.contains(payload.substring(0, 16)), text);
  }

  /** No env entry body (or a 12-character slice of one) appears in {@code text}. */
  static void assertNoEnvLeak(String text, String envValue) {
    for (String piece : envValue.split(",", -1)) {
      String p = piece.replaceAll("^[ \\t\\r\\n]+|[ \\t\\r\\n]+$", "");
      int i = p.indexOf("qf1-priv-");
      String body = i >= 0 ? p.substring(i + 9) : p;
      if (body.length() >= 12) assertFalse(text.contains(body.substring(0, 12)), text);
    }
  }
}
