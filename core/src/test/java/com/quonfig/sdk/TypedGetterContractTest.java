package com.quonfig.sdk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.quonfig.sdk.ApiUrlsFailoverWarnTest.RecordingLogger;
import com.quonfig.sdk.exceptions.QuonfigCoercionException;
import com.quonfig.sdk.exceptions.QuonfigEnvVarNotSetException;
import com.quonfig.sdk.exceptions.QuonfigKeyNotFoundException;
import com.quonfig.sdk.exceptions.QuonfigTypeMismatchException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The cross-SDK typed-getter contract (qfg-zvyy) through the public client, for the edges the
 * integration-test-data matrix does not reach: once-per-key logging, featureIsOn never throwing,
 * the int/double boundaries, and the qfg-koj9 exception hierarchy.
 */
class TypedGetterContractTest {

  @TempDir Path workspaceDir;

  private RecordingLogger logger;
  private final Map<String, String> env = new HashMap<>();

  @BeforeEach
  void writeWorkspace() throws Exception {
    Files.writeString(
        workspaceDir.resolve("quonfig.json"),
        "{\"workspace\":\"test-ws\",\"environments\":[\"production\"]}");
    Files.createDirectories(workspaceDir.resolve("configs"));
    Files.createDirectories(workspaceDir.resolve("feature-flags"));
    Files.createDirectories(workspaceDir.resolve("segments"));
    logger = new RecordingLogger();
  }

  private void write(String key, String valueType, String valueJson) throws Exception {
    writeRules(
        key,
        valueType,
        "[{\"criteria\":[{\"operator\":\"ALWAYS_TRUE\"}],\"value\":" + valueJson + "}]");
  }

  private void writeRules(String key, String valueType, String rulesJson) throws Exception {
    Files.writeString(
        workspaceDir.resolve("configs").resolve(key + ".json"),
        "{\"id\":\"id-"
            + key
            + "\",\"key\":\""
            + key
            + "\",\"type\":\"config\",\"valueType\":\""
            + valueType
            + "\",\"default\":{\"rules\":"
            + rulesJson
            + "}}");
  }

  private void writeStored(String key, String valueType, String valueLiteral) throws Exception {
    write(key, valueType, "{\"type\":\"" + valueType + "\",\"value\":" + valueLiteral + "}");
  }

  private void writeProvided(String key, String valueType, String envVar) throws Exception {
    write(
        key,
        valueType,
        "{\"type\":\"provided\",\"value\":{\"source\":\"ENV_VAR\",\"lookup\":\"" + envVar + "\"}}");
  }

  private Quonfig newClient() {
    return new Quonfig(
        Options.builder()
            .datadir(workspaceDir.toString())
            .environment("production")
            .logger(logger)
            .envLookup(k -> Optional.ofNullable(env.get(k)))
            .enableQuonfigUserContext(false)
            .disableTelemetry(true)
            .build());
  }

  private long warnCount(String key) {
    return logger.entries.stream()
        .filter(e -> e.level == org.slf4j.event.Level.WARN)
        .filter(e -> e.args.contains("\"" + key + "\"") || e.args.contains(key + ","))
        .count();
  }

  // ---- int <-> double ----

  @Test
  void doubleGetterReadsStoredInt() throws Exception {
    writeStored("i", "int", "30");
    try (Quonfig q = newClient()) {
      assertEquals(30.0, q.getDouble("i", 1.5));
      assertEquals(30.0, q.getDoubleOrThrow("i"));
      assertEquals(Reason.STATIC, q.getDoubleDetails("i", 1.5).reason());
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"3.0", "-3.0", "0.0", "9007199254740991.0", "-9007199254740991.0"})
  void intGetterReadsWholeSafeDouble(String stored) throws Exception {
    writeStored("d", "double", stored);
    try (Quonfig q = newClient()) {
      long expected = (long) Double.parseDouble(stored);
      assertEquals(expected, q.getLong("d", 7L));
      assertEquals(expected, q.getLongOrThrow("d"));
      assertEquals(0, warnCount("d"));
    }
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"3.9", "-0.5", "1e300", "9007199254740992.0", "-9007199254740992.0", "\"NaN\""})
  void intGetterNeverTruncatesADouble(String stored) throws Exception {
    writeStored("d", "double", stored);
    try (Quonfig q = newClient()) {
      assertEquals(7L, q.getLong("d", 7L));
      assertNull(q.getLong("d", null));
      EvaluationDetails<Long> d = q.getLongDetails("d", 7L);
      assertEquals(Reason.ERROR, d.reason());
      assertEquals(ErrorCode.TYPE_MISMATCH, d.errorCode());
      QuonfigTypeMismatchException e =
          assertThrows(QuonfigTypeMismatchException.class, () -> q.getLongOrThrow("d"));
      assertInstanceOf(IllegalArgumentException.class, e, "backward compatible with 2.1.0");
      assertEquals(1, warnCount("d"), "one WARN per key: " + logger.entries);
    }
  }

  // ---- malformed stored numbers (qfg-zqoh) ----

  @Test
  void malformedStoredInt_defaultWarnOnce_orThrowRaisesCoercion() throws Exception {
    writeStored("bad", "int", "\"twelve\"");
    try (Quonfig q = newClient()) {
      assertEquals(7L, q.getLong("bad", 7L));
      assertEquals(7L, q.getLong("bad", 7L));
      assertNull(q.getLong("bad", null));
      assertNull(q.getDouble("bad", null));
      EvaluationDetails<Long> d = q.getLongDetails("bad", 7L);
      assertEquals(Reason.ERROR, d.reason());
      assertEquals(ErrorCode.TYPE_MISMATCH, d.errorCode());
      QuonfigCoercionException e =
          assertThrows(QuonfigCoercionException.class, () -> q.getLongOrThrow("bad"));
      assertInstanceOf(QuonfigKeyNotFoundException.class, e, "backward compatible with 2.1.0");
      assertFalse(e.getMessage().contains("twelve"), "never echo the value: " + e.getMessage());
      assertEquals(1, warnCount("bad"), "one WARN per key: " + logger.entries);
    }
  }

  @Test
  void numericStringStoredIntStillReads() throws Exception {
    writeStored("i", "int", "\"12\"");
    writeStored("f", "double", "\"2.5\"");
    try (Quonfig q = newClient()) {
      assertEquals(12L, q.getLong("i", 7L));
      assertEquals(2.5, q.getDouble("f", 7.5));
    }
  }

  @Test
  void malformedEnvVarInt_orThrowRaisesCoercion() throws Exception {
    writeProvided("port", "int", "PORT");
    env.put("PORT", "abc");
    try (Quonfig q = newClient()) {
      assertEquals(80L, q.getLong("port", 80L));
      assertThrows(QuonfigCoercionException.class, () -> q.getLongOrThrow("port"));
      assertEquals(1, warnCount("port"));
    }
  }

  // ---- no string coercion; log once per key, never the value ----

  @Test
  void stringLookingLikeABoolIsNotABool_warnsOncePerKey_neverLogsValue() throws Exception {
    writeStored("s1", "string", "\"false\"");
    writeStored("s2", "string", "\"s3cr3t-value\"");
    try (Quonfig q = newClient()) {
      for (int i = 0; i < 3; i++) {
        assertEquals(Boolean.TRUE, q.getBool("s1", true));
        assertNull(q.getLong("s2", null));
      }
      assertEquals(ErrorCode.TYPE_MISMATCH, q.getBoolDetails("s1", true).errorCode());
      assertThrows(QuonfigTypeMismatchException.class, () -> q.getBoolOrThrow("s1"));
      assertEquals(1, warnCount("s1"), "one WARN per key: " + logger.entries);
      assertEquals(1, warnCount("s2"), "one WARN per key: " + logger.entries);
      assertTrue(
          logger.entries.stream().noneMatch(e -> e.args.contains("s3cr3t")),
          "a WARN must never echo the value: " + logger.entries);
    }
  }

  @Test
  void stringGetterDoesNotReadADuration() throws Exception {
    writeStored("dur", "duration", "\"PT30S\"");
    try (Quonfig q = newClient()) {
      assertEquals("DEFAULT", q.getString("dur", "DEFAULT"));
      assertThrows(QuonfigTypeMismatchException.class, () -> q.getStringOrThrow("dur"));
    }
  }

  // ---- resolution errors with a default ----

  @Test
  void missingEnvVar_defaultWithoutThrow_orThrowRaises() throws Exception {
    writeProvided("host", "string", "UNSET_HOST");
    try (Quonfig q = newClient()) {
      assertEquals("localhost", q.getString("host", "localhost"));
      assertNull(q.getString("host", null));
      assertThrows(QuonfigEnvVarNotSetException.class, () -> q.getStringOrThrow("host"));
      assertEquals(1, warnCount("host"));
    }
  }

  // ---- featureIsOn never throws ----

  @Test
  void featureIsOn_falseAndWarnOnce_forEveryNonTrueOutcome() throws Exception {
    writeStored("on", "bool", "true");
    writeStored("str-true", "string", "\"true\"");
    writeProvided("env-flag", "bool", "UNSET_FLAG");
    writeRules(
        "no-match",
        "bool",
        "[{\"criteria\":[{\"propertyName\":\"user.key\",\"operator\":\"PROP_IS_ONE_OF\","
            + "\"valueToMatch\":{\"type\":\"string_list\",\"value\":[\"nobody\"]}}],"
            + "\"value\":{\"type\":\"bool\",\"value\":true}}]");
    try (Quonfig q = newClient()) {
      assertTrue(q.featureIsOn("on", null));
      for (String key : new String[] {"missing", "str-true", "env-flag", "no-match"}) {
        for (int i = 0; i < 3; i++) {
          assertFalse(q.featureIsOn(key, null), key);
        }
        assertEquals(1, warnCount(key), key + ": one WARN per key: " + logger.entries);
      }
      assertEquals(0, warnCount("on"));
    }
  }

  @Test
  void featureIsOn_onClosedClient_returnsFalse() throws Exception {
    writeStored("on", "bool", "true");
    Quonfig q = newClient();
    q.close();
    assertFalse(q.featureIsOn("on", null));
  }
}
