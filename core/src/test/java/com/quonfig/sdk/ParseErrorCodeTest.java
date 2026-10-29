package com.quonfig.sdk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.quonfig.sdk.ApiUrlsFailoverWarnTest.RecordingLogger;
import com.quonfig.sdk.eval.ContextSet;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * A value of the requested type that does not parse (a malformed ENV_VAR value, or a malformed
 * stored int, double or duration) is {@link ErrorCode#PARSE_ERROR} on the details API (qfg-wg1n). A
 * config whose type does not match the getter stays {@link ErrorCode#TYPE_MISMATCH}.
 */
class ParseErrorCodeTest {

  @TempDir Path workspaceDir;

  private final Map<String, String> env = new HashMap<>();

  @BeforeEach
  void writeWorkspace() throws Exception {
    Files.writeString(
        workspaceDir.resolve("quonfig.json"),
        "{\"workspace\":\"test-ws\",\"environments\":[\"production\"]}");
    Files.createDirectories(workspaceDir.resolve("configs"));
    Files.createDirectories(workspaceDir.resolve("feature-flags"));
    Files.createDirectories(workspaceDir.resolve("segments"));
  }

  private void write(String key, String valueType, String valueJson) throws Exception {
    Files.writeString(
        workspaceDir.resolve("configs").resolve(key + ".json"),
        "{\"id\":\"id-"
            + key
            + "\",\"key\":\""
            + key
            + "\",\"type\":\"config\",\"valueType\":\""
            + valueType
            + "\",\"default\":{\"rules\":[{\"criteria\":[{\"operator\":\"ALWAYS_TRUE\"}],"
            + "\"value\":"
            + valueJson
            + "}]}}");
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
            .logger(new RecordingLogger())
            .envLookup(k -> Optional.ofNullable(env.get(k)))
            .enableQuonfigUserContext(false)
            .disableTelemetry(true)
            .build());
  }

  private static EvaluationDetails<?> typedDetails(Quonfig q, String key, String valueType) {
    switch (valueType) {
      case "int":
        return q.getLongDetails(key, 7L, null);
      case "double":
        return q.getDoubleDetails(key, 7.5, null);
      case "bool":
        return q.getBoolDetails(key, true, null);
      case "duration":
        return q.getDurationDetails(key, Duration.ofSeconds(7), null);
      case "json":
        return q.getJsonDetails(key, "fallback", null);
      default:
        throw new IllegalArgumentException(valueType);
    }
  }

  private static Object defaultFor(String valueType) {
    switch (valueType) {
      case "int":
        return 7L;
      case "double":
        return 7.5;
      case "bool":
        return true;
      case "duration":
        return Duration.ofSeconds(7);
      default:
        return "fallback";
    }
  }

  // ---- malformed ENV_VAR value ----

  @ParameterizedTest
  @CsvSource({"int,abc", "double,abc", "bool,maybe", "duration,30s", "json,'{not json'"})
  void malformedEnvVar_typedDetails_isParseError(String valueType, String envValue)
      throws Exception {
    writeProvided("p", valueType, "P_VAR");
    env.put("P_VAR", envValue);
    try (Quonfig q = newClient()) {
      EvaluationDetails<?> d = typedDetails(q, "p", valueType);
      assertEquals(Reason.ERROR, d.reason(), "details: " + d);
      assertEquals(ErrorCode.PARSE_ERROR, d.errorCode(), "details: " + d);
      assertEquals(defaultFor(valueType), d.value());
    }
  }

  @ParameterizedTest
  @CsvSource({"int,abc", "double,abc", "bool,maybe", "duration,30s", "json,'{not json'"})
  void malformedEnvVar_evaluateDetails_isParseError(String valueType, String envValue)
      throws Exception {
    writeProvided("p", valueType, "P_VAR");
    env.put("P_VAR", envValue);
    try (Quonfig q = newClient()) {
      EvaluationDetails<Object> d = q.evaluateDetails("p");
      assertNull(d.value(), "details: " + d);
      assertEquals(Reason.ERROR, d.reason());
      assertEquals(ErrorCode.PARSE_ERROR, d.errorCode());
    }
  }

  @Test
  void malformedEnvVar_boundClient_isParseError() throws Exception {
    writeProvided("port", "int", "PORT");
    env.put("PORT", "abc");
    try (Quonfig q = newClient()) {
      BoundQuonfig b = q.withContext(new ContextSet());
      assertEquals(ErrorCode.PARSE_ERROR, b.getLongDetails("port", 80L).errorCode());
      assertEquals(ErrorCode.PARSE_ERROR, b.evaluateDetails("port").errorCode());
    }
  }

  // ---- malformed stored value ----

  @ParameterizedTest
  @CsvSource({"int,'\"twelve\"'", "double,'\"abc\"'", "duration,'\"30s\"'"})
  void malformedStored_typedDetails_isParseError(String valueType, String literal)
      throws Exception {
    writeStored("bad", valueType, literal);
    try (Quonfig q = newClient()) {
      EvaluationDetails<?> d = typedDetails(q, "bad", valueType);
      assertEquals(Reason.ERROR, d.reason(), "details: " + d);
      assertEquals(ErrorCode.PARSE_ERROR, d.errorCode(), "details: " + d);
      assertEquals(defaultFor(valueType), d.value());
    }
  }

  @Test
  void malformedStoredInt_readAsDouble_isParseError() throws Exception {
    writeStored("bad", "int", "\"twelve\"");
    try (Quonfig q = newClient()) {
      assertEquals(ErrorCode.PARSE_ERROR, q.getDoubleDetails("bad", 7.5, null).errorCode());
    }
  }

  @Test
  void malformedStored_evaluateDetails_returnsRawValue() throws Exception {
    // The untyped read does no parse validation (qfg-177f): no error for a malformed stored value.
    writeStored("bad", "int", "\"twelve\"");
    try (Quonfig q = newClient()) {
      EvaluationDetails<Object> d = q.evaluateDetails("bad");
      assertEquals("twelve", d.value(), "details: " + d);
      assertEquals(Reason.STATIC, d.reason());
      assertNull(d.errorCode());
    }
  }

  // ---- a real type mismatch is unchanged ----

  @Test
  void wrongValueType_staysTypeMismatch() throws Exception {
    writeStored("n", "int", "42");
    writeStored("s", "string", "\"false\"");
    writeStored("frac", "double", "3.9");
    writeStored("tags", "string_list", "[\"a\"]");
    try (Quonfig q = newClient()) {
      assertEquals(ErrorCode.TYPE_MISMATCH, q.getStringDetails("n", "x", null).errorCode());
      assertEquals(ErrorCode.TYPE_MISMATCH, q.getBoolDetails("s", true, null).errorCode());
      assertEquals(ErrorCode.TYPE_MISMATCH, q.getLongDetails("frac", 7L, null).errorCode());
      assertEquals(
          ErrorCode.TYPE_MISMATCH,
          q.getStringListDetails("n", List.of("x"), null).errorCode(),
          "int read as string_list");
      assertEquals(ErrorCode.TYPE_MISMATCH, q.getDurationDetails("tags", null, null).errorCode());
    }
  }
}
