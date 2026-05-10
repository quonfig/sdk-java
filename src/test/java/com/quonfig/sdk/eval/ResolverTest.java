package com.quonfig.sdk.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ResolverTest {

  // The same fixture used by sdk-go's integration suite — a known-good AES-GCM
  // pairing that any compliant resolver must decrypt to "hello.world".
  // Source: integration-test-data/data/integration-tests/configs/a.secret.config.json
  // and the matching PREFAB_INTEGRATION_TEST_ENCRYPTION_KEY in .env.
  private static final String FIXTURE_KEY_HEX =
      "c87ba22d8662282abe8a0e4651327b579cb64a454ab0f4c170b45b15f049a221";
  private static final String FIXTURE_CIPHERTEXT =
      "875247386844c18c58a97c--b307b97a8288ac9da3ce0cf2--7ab0c32e044869e355586ed653a435de";
  private static final String FIXTURE_PLAINTEXT = "hello.world";

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
  void resolve_providedEnvVar_coercesDurationIso8601() {
    Value provided = new Value(ValueType.PROVIDED, new ProvidedValue("ENV_VAR", "MY_DURATION"));
    ConfigRow cfg = rowNoRules("k", ValueType.DURATION);
    Resolver r = new Resolver(new MapStore(), null, key -> Optional.of("PT90S"));
    Value out = r.resolve(provided, cfg, "", new ContextSet());
    assertEquals(ValueType.DURATION, out.type());
    assertEquals(Duration.ofSeconds(90), out.value());
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

  // ----- AES-GCM decryption -----

  @Test
  void resolve_aesGcmDecrypt_withKeyFromEnvVarConfig() {
    // The encryption key config is itself a PROVIDED value, so the resolver must
    // recurse to materialize the hex key string.
    ConfigRow keyCfg =
        row(
            "prefab.secrets.encryption.key",
            ValueType.STRING,
            new Value(ValueType.PROVIDED, new ProvidedValue("ENV_VAR", "ENCRYPTION_KEY_HEX")));
    MapStore store = new MapStore().put(keyCfg);

    Value secretVal =
        new Value(ValueType.STRING, FIXTURE_CIPHERTEXT, true, "prefab.secrets.encryption.key");
    ConfigRow secretCfg = rowNoRules("a.secret.config", ValueType.STRING);

    Evaluator ev = new Evaluator(store);
    Resolver r =
        new Resolver(
            store,
            ev,
            key ->
                "ENCRYPTION_KEY_HEX".equals(key) ? Optional.of(FIXTURE_KEY_HEX) : Optional.empty());

    Value out = r.resolve(secretVal, secretCfg, "", new ContextSet());
    assertEquals(ValueType.STRING, out.type());
    assertEquals(FIXTURE_PLAINTEXT, out.value());
    assertTrue(out.confidential());
  }

  @Test
  void resolve_aesGcmDecrypt_throwsWhenKeyConfigMissing() {
    Value secretVal = new Value(ValueType.STRING, FIXTURE_CIPHERTEXT, true, "missing.key");
    ConfigRow secretCfg = rowNoRules("a.secret.config", ValueType.STRING);
    Evaluator ev = new Evaluator(new MapStore());
    Resolver r = new Resolver(new MapStore(), ev, key -> Optional.empty());
    ResolverException ex =
        assertThrows(
            ResolverException.class, () -> r.resolve(secretVal, secretCfg, "", new ContextSet()));
    assertEquals(ResolverException.Kind.UNABLE_TO_DECRYPT, ex.kind());
  }

  @Test
  void resolve_aesGcmDecrypt_throwsOnMalformedCiphertext() {
    ConfigRow keyCfg = row("k.key", ValueType.STRING, new Value(ValueType.STRING, FIXTURE_KEY_HEX));
    MapStore store = new MapStore().put(keyCfg);
    Value bad = new Value(ValueType.STRING, "not-the-right-format", true, "k.key");
    ConfigRow cfg = rowNoRules("k", ValueType.STRING);
    Evaluator ev = new Evaluator(store);
    Resolver r = new Resolver(store, ev, key -> Optional.empty());
    ResolverException ex =
        assertThrows(ResolverException.class, () -> r.resolve(bad, cfg, "", new ContextSet()));
    assertEquals(ResolverException.Kind.UNABLE_TO_DECRYPT, ex.kind());
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
