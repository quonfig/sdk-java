package com.quonfig.sdk.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ResolverTest {

  // An OLD-format (decryptWith) AES-GCM pairing that the old path decrypted to "hello.world".
  // The SDK no longer reads that format; the tests below prove it fails instead (qf1 R1).
  private static final String FIXTURE_KEY_HEX =
      "c87ba22d8662282abe8a0e4651327b579cb64a454ab0f4c170b45b15f049a221";
  private static final String FIXTURE_CIPHERTEXT =
      "875247386844c18c58a97c--b307b97a8288ac9da3ce0cf2--7ab0c32e044869e355586ed653a435de";

  private static class MapStore implements ConfigStore {
    final Map<String, ConfigRow> byKey = new LinkedHashMap<>();

    MapStore put(ConfigRow c) {
      byKey.put(c.key(), c);
      return this;
    }

    @Override
    public ConfigRow getConfig(String key) {
      return byKey.get(key);
    }
  }

  private static ConfigRow row(String key, ValueType vt, Value v) {
    Rule r = new Rule(Collections.emptyList(), v);
    return new ConfigRow(
        key, key, ConfigType.CONFIG, vt, false, new RuleSet(List.of(r)), Collections.emptyList());
  }

  private static ConfigRow rowNoRules(String key, ValueType vt) {
    return new ConfigRow(
        key,
        key,
        ConfigType.CONFIG,
        vt,
        false,
        new RuleSet(Collections.emptyList()),
        Collections.emptyList());
  }

  // ----- Pass-through: non-provided, non-confidential values are returned unchanged -----

  @Test
  void resolve_passesThroughPlainStringValue() {
    Value val = new Value(ValueType.STRING, "plain");
    ConfigRow cfg = rowNoRules("k", ValueType.STRING);
    Resolver r = new Resolver(new MapStore(), null, key -> Optional.empty());
    Value out = r.resolve(val, cfg, "", new ContextSet());
    assertEquals(val, out);
  }

  @Test
  void resolve_passesThroughNull() {
    Resolver r = new Resolver(new MapStore(), null, key -> Optional.empty());
    assertNull(r.resolve(null, rowNoRules("k", ValueType.STRING), "", new ContextSet()));
  }

  // ----- ENV_VAR provided values: lookup + type coercion -----

  @Test
  void resolve_providedEnvVar_coercesString() {
    Value provided = new Value(ValueType.PROVIDED, new ProvidedValue("ENV_VAR", "MY_STR"));
    ConfigRow cfg = rowNoRules("k", ValueType.STRING);
    Resolver r =
        new Resolver(
            new MapStore(),
            null,
            key -> "MY_STR".equals(key) ? Optional.of("hello") : Optional.empty());
    Value out = r.resolve(provided, cfg, "", new ContextSet());
    assertEquals(ValueType.STRING, out.type());
    assertEquals("hello", out.value());
  }

  @Test
  void resolve_providedEnvVar_coercesInt() {
    Value provided = new Value(ValueType.PROVIDED, new ProvidedValue("ENV_VAR", "MY_INT"));
    ConfigRow cfg = rowNoRules("k", ValueType.INT);
    Resolver r =
        new Resolver(
            new MapStore(),
            null,
            key -> "MY_INT".equals(key) ? Optional.of("42") : Optional.empty());
    Value out = r.resolve(provided, cfg, "", new ContextSet());
    assertEquals(ValueType.INT, out.type());
    assertEquals(42L, out.value());
  }

  @Test
  void resolve_providedEnvVar_coercesDouble() {
    Value provided = new Value(ValueType.PROVIDED, new ProvidedValue("ENV_VAR", "MY_DOUBLE"));
    ConfigRow cfg = rowNoRules("k", ValueType.DOUBLE);
    Resolver r =
        new Resolver(
            new MapStore(),
            null,
            key -> "MY_DOUBLE".equals(key) ? Optional.of("3.14") : Optional.empty());
    Value out = r.resolve(provided, cfg, "", new ContextSet());
    assertEquals(ValueType.DOUBLE, out.type());
    assertEquals(3.14, out.value());
  }

  @Test
  void resolve_providedEnvVar_coercesBool() {
    Value provided = new Value(ValueType.PROVIDED, new ProvidedValue("ENV_VAR", "MY_BOOL"));
    ConfigRow cfg = rowNoRules("k", ValueType.BOOL);
    Resolver r =
        new Resolver(
            new MapStore(),
            null,
            key -> "MY_BOOL".equals(key) ? Optional.of("true") : Optional.empty());
    Value out = r.resolve(provided, cfg, "", new ContextSet());
    assertEquals(ValueType.BOOL, out.type());
    assertEquals(true, out.value());
  }

  @Test
  void resolve_providedEnvVar_coercesBoolUppercaseTrue() {
    Value provided = new Value(ValueType.PROVIDED, new ProvidedValue("ENV_VAR", "MY_BOOL"));
    ConfigRow cfg = rowNoRules("k", ValueType.BOOL);
    Resolver r = new Resolver(new MapStore(), null, key -> Optional.of("TRUE"));
    Value out = r.resolve(provided, cfg, "", new ContextSet());
    assertEquals(ValueType.BOOL, out.type());
    assertEquals(true, out.value());
  }

  @Test
  void resolve_providedEnvVar_throwsUnableToCoerceWhenBoolGarbage() {
    Value provided = new Value(ValueType.PROVIDED, new ProvidedValue("ENV_VAR", "MY_BOOL"));
    ConfigRow cfg = rowNoRules("k", ValueType.BOOL);
    Resolver r = new Resolver(new MapStore(), null, key -> Optional.of("definitely-not-a-bool"));
    ResolverException ex =
        assertThrows(ResolverException.class, () -> r.resolve(provided, cfg, "", new ContextSet()));
    assertEquals(ResolverException.Kind.UNABLE_TO_COERCE, ex.kind());
  }

  @Test
  void resolve_providedEnvVar_coercesStringList() {
    Value provided = new Value(ValueType.PROVIDED, new ProvidedValue("ENV_VAR", "MY_LIST"));
    ConfigRow cfg = rowNoRules("k", ValueType.STRING_LIST);
    Resolver r = new Resolver(new MapStore(), null, key -> Optional.of("a, b ,c"));
    Value out = r.resolve(provided, cfg, "", new ContextSet());
    assertEquals(ValueType.STRING_LIST, out.type());
    assertInstanceOf(List.class, out.value());
    assertEquals(List.of("a", "b", "c"), out.value());
  }

  @Test
  void resolve_providedEnvVar_coercesJsonObject() {
    Value provided = new Value(ValueType.PROVIDED, new ProvidedValue("ENV_VAR", "MY_JSON"));
    ConfigRow cfg = rowNoRules("k", ValueType.JSON);
    Resolver r =
        new Resolver(new MapStore(), null, key -> Optional.of("{\"a\":1,\"b\":[true,\"x\"]}"));
    Value out = r.resolve(provided, cfg, "", new ContextSet());
    assertEquals(ValueType.JSON, out.type());
    assertInstanceOf(Map.class, out.value());
    @SuppressWarnings("unchecked")
    Map<String, Object> m = (Map<String, Object>) out.value();
    assertEquals(1, ((Number) m.get("a")).intValue());
    assertInstanceOf(List.class, m.get("b"));
  }

  @Test
  void resolve_providedEnvVar_throwsUnableToCoerceWhenJsonInvalid() {
    Value provided = new Value(ValueType.PROVIDED, new ProvidedValue("ENV_VAR", "BAD_JSON"));
    ConfigRow cfg = rowNoRules("k", ValueType.JSON);
    Resolver r = new Resolver(new MapStore(), null, key -> Optional.of("{not-json"));
    ResolverException ex =
        assertThrows(ResolverException.class, () -> r.resolve(provided, cfg, "", new ContextSet()));
    assertEquals(ResolverException.Kind.UNABLE_TO_COERCE, ex.kind());
  }

  @Test
  void resolve_providedEnvVar_validatesDurationAndKeepsIsoText() {
    Value provided = new Value(ValueType.PROVIDED, new ProvidedValue("ENV_VAR", "MY_DURATION"));
    ConfigRow cfg = rowNoRules("k", ValueType.DURATION);
    Resolver r = new Resolver(new MapStore(), null, key -> Optional.of("PT90S"));
    Value out = r.resolve(provided, cfg, "", new ContextSet());
    assertEquals(ValueType.DURATION, out.type());
    // Validated, but kept as the ISO text a stored duration carries (qfg-177f, sdk-go parity).
    assertEquals("PT90S", out.value());
  }

  @Test
  void resolve_providedEnvVar_throwsUnableToCoerceWhenDurationInvalid() {
    Value provided = new Value(ValueType.PROVIDED, new ProvidedValue("ENV_VAR", "BAD_DURATION"));
    ConfigRow cfg = rowNoRules("k", ValueType.DURATION);
    Resolver r = new Resolver(new MapStore(), null, key -> Optional.of("ninety seconds"));
    ResolverException ex =
        assertThrows(ResolverException.class, () -> r.resolve(provided, cfg, "", new ContextSet()));
    assertEquals(ResolverException.Kind.UNABLE_TO_COERCE, ex.kind());
  }

  @Test
  void resolve_providedEnvVar_throwsMissingEnvVar() {
    Value provided = new Value(ValueType.PROVIDED, new ProvidedValue("ENV_VAR", "ABSENT"));
    ConfigRow cfg = rowNoRules("k", ValueType.STRING);
    Resolver r = new Resolver(new MapStore(), null, key -> Optional.empty());
    ResolverException ex =
        assertThrows(ResolverException.class, () -> r.resolve(provided, cfg, "", new ContextSet()));
    assertEquals(ResolverException.Kind.MISSING_ENV_VAR, ex.kind());
    assertTrue(ex.getMessage().contains("ABSENT"));
  }

  @Test
  void resolve_providedEnvVar_throwsUnableToCoerceWhenIntInvalid() {
    Value provided = new Value(ValueType.PROVIDED, new ProvidedValue("ENV_VAR", "BAD_INT"));
    ConfigRow cfg = rowNoRules("k", ValueType.INT);
    Resolver r = new Resolver(new MapStore(), null, key -> Optional.of("not-a-number"));
    ResolverException ex =
        assertThrows(ResolverException.class, () -> r.resolve(provided, cfg, "", new ContextSet()));
    assertEquals(ResolverException.Kind.UNABLE_TO_COERCE, ex.kind());
  }

  // qfg-goi1.2.32: the coercion error names the env var, type and key, never the value.
  @Test
  void resolve_providedEnvVar_coerceErrorDoesNotLeakEnvValue() {
    Value provided = new Value(ValueType.PROVIDED, new ProvidedValue("ENV_VAR", "DATABASE_URL"));
    ConfigRow cfg = rowNoRules("db.pool.size", ValueType.INT);
    Resolver r =
        new Resolver(new MapStore(), null, key -> Optional.of("postgres://admin:hunter2@db"));
    ResolverException ex =
        assertThrows(ResolverException.class, () -> r.resolve(provided, cfg, "", new ContextSet()));
    assertEquals(ResolverException.Kind.UNABLE_TO_COERCE, ex.kind());
    assertEquals(
        "environment variable \"DATABASE_URL\" cannot be converted to int for config"
            + " \"db.pool.size\"",
        ex.getMessage());
    for (Throwable t = ex; t != null; t = t.getCause()) {
      assertFalse(String.valueOf(t.getMessage()).contains("hunter2"), "leaked in " + t);
    }
  }

  @Test
  void resolve_providedEnvVar_coerceErrorUsesConfigFileTypeNames() {
    Value provided = new Value(ValueType.PROVIDED, new ProvidedValue("ENV_VAR", "V"));
    Resolver r = new Resolver(new MapStore(), null, key -> Optional.of("s3cret{"));
    for (Object[] c :
        new Object[][] {
          {ValueType.DOUBLE, "double"},
          {ValueType.BOOL, "bool"},
          {ValueType.JSON, "json"},
          {ValueType.DURATION, "duration"}
        }) {
      ConfigRow cfg = rowNoRules("k", (ValueType) c[0]);
      ResolverException ex =
          assertThrows(
              ResolverException.class, () -> r.resolve(provided, cfg, "", new ContextSet()));
      assertEquals(
          "environment variable \"V\" cannot be converted to " + c[1] + " for config \"k\"",
          ex.getMessage());
      for (Throwable t = ex; t != null; t = t.getCause()) {
        assertFalse(String.valueOf(t.getMessage()).contains("s3cret"), "leaked in " + t);
      }
    }
  }

  // ----- qf1 spec 13.3 R1: a value that still carries decryptWith always fails -----

  @Test
  void resolve_decryptWith_failsEvenWhenTheOldKeyWouldDecryptIt() {
    // The old path would have decrypted this to "hello.world"; the SDK must not read it any more,
    // and must not return the old ciphertext as a value either.
    ConfigRow keyCfg =
        row(
            "prefab.secrets.encryption.key",
            ValueType.STRING,
            new Value(ValueType.PROVIDED, new ProvidedValue("ENV_VAR", "ENCRYPTION_KEY_HEX")));
    MapStore store = new MapStore().put(keyCfg);
    Value secretVal =
        new Value(ValueType.STRING, FIXTURE_CIPHERTEXT, true, "prefab.secrets.encryption.key");
    ConfigRow secretCfg = rowNoRules("a.secret.config", ValueType.STRING);
    Resolver r =
        new Resolver(
            store,
            new Evaluator(store),
            key ->
                "ENCRYPTION_KEY_HEX".equals(key) ? Optional.of(FIXTURE_KEY_HEX) : Optional.empty());

    ResolverException ex =
        assertThrows(
            ResolverException.class, () -> r.resolve(secretVal, secretCfg, "", new ContextSet()));
    assertEquals(ResolverException.Kind.UNABLE_TO_DECRYPT, ex.kind());
    assertTrue(ex.getMessage().contains("decryptWith"), ex.getMessage());
    assertFalse(ex.getMessage().contains(FIXTURE_CIPHERTEXT), ex.getMessage());
    assertNull(ex.getCause());
  }

  @Test
  void resolve_decryptWith_failsWithoutConfidentialAndWithoutAnyLookup() {
    Value v = new Value(ValueType.STRING, FIXTURE_CIPHERTEXT, false, "k.self");
    Resolver r =
        new Resolver(
            new MapStore(),
            new Evaluator(new MapStore()),
            key -> {
              throw new AssertionError("no env var may be read, got " + key);
            });
    ResolverException ex =
        assertThrows(
            ResolverException.class,
            () -> r.resolve(v, rowNoRules("a.secret", ValueType.STRING), "", new ContextSet()));
    assertEquals(ResolverException.Kind.UNABLE_TO_DECRYPT, ex.kind());
  }

  @Test
  void resolve_secretWhoseValueIsNotAString_failsAsMalformed() {
    ConfigRow cfg =
        new ConfigRow(
            "s",
            "s",
            ConfigType.SECRET,
            ValueType.STRING,
            false,
            new RuleSet(Collections.emptyList()),
            Collections.emptyList());
    Resolver r = new Resolver(null, null, key -> Optional.empty());
    ResolverException ex =
        assertThrows(
            ResolverException.class,
            () -> r.resolve(new Value(ValueType.INT, 7L), cfg, "", new ContextSet()));
    assertEquals(ResolverException.Kind.UNABLE_TO_DECRYPT, ex.kind());
    assertTrue(ex.getMessage().startsWith("qf1 malformed: "), ex.getMessage());
  }

  @Test
  void resolve_qf1StringOutsideTheSecretFamily_isReturnedUnchanged() {
    String looksLikeQf1 = "qf1:QUONFIG_KEYSET:00000000:AAAA";
    Value v = new Value(ValueType.STRING, looksLikeQf1, true, null);
    Resolver r = new Resolver(null, null, key -> Optional.empty());
    assertEquals(
        v, r.resolve(v, rowNoRules("plain.config", ValueType.STRING), "", new ContextSet()));
  }

  // ----- ReportableValueFor: telemetry redaction -----

  @Test
  void reportableValueFor_returnsEmptyForPlainValue() {
    Value plain = new Value(ValueType.STRING, "ok");
    assertTrue(Resolver.reportableValueFor(plain).isEmpty());
  }

  @Test
  void reportableValueFor_returnsRedactedFormForConfidential() {
    // md5("875247386844c18c58a97c--b307b97a8288ac9da3ce0cf2--7ab0c32e044869e355586ed653a435de")
    // = "936c9..." — sdk-go and sdk-node both expect "*****936c9".
    Value secret =
        new Value(ValueType.STRING, FIXTURE_CIPHERTEXT, true, "prefab.secrets.encryption.key");
    Optional<String> out = Resolver.reportableValueFor(secret);
    assertTrue(out.isPresent());
    assertEquals("*****936c9", out.get());
  }

  @Test
  void reportableValueFor_handlesPlainConfidentialNoDecryptWith() {
    Value secret = new Value(ValueType.STRING, "raw-secret", true, null);
    Optional<String> out = Resolver.reportableValueFor(secret);
    assertTrue(out.isPresent());
    assertTrue(out.get().startsWith("*****"));
    assertEquals(10, out.get().length());
  }
}
