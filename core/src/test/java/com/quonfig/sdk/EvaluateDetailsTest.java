package com.quonfig.sdk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.quonfig.sdk.eval.ContextSet;
import com.quonfig.sdk.eval.ValueType;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The untyped details read (qfg-177f, sdk-go {@code Client.EvaluateDetails} parity): the resolved
 * value in its stored / wire form, with no type check, no coercion and no parse validation, and the
 * same reason / variant / metadata as the typed {@code get*Details} getters.
 */
class EvaluateDetailsTest {

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

  private void write(String key, String valueType, String rulesJson) throws Exception {
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

  private void writeStatic(String key, String valueType, String valueJson) throws Exception {
    write(
        key,
        valueType,
        "[{\"criteria\":[{\"operator\":\"ALWAYS_TRUE\"}],\"value\":" + valueJson + "}]");
  }

  private void writeStored(String key, String valueType, String literal) throws Exception {
    writeStatic(key, valueType, "{\"type\":\"" + valueType + "\",\"value\":" + literal + "}");
  }

  private Quonfig newClient() {
    return new Quonfig(
        Options.builder()
            .datadir(workspaceDir.toString())
            .environment("production")
            .envLookup(k -> Optional.ofNullable(env.get(k)))
            .enableQuonfigUserContext(false)
            .disableTelemetry(true)
            .build());
  }

  @ParameterizedTest
  @ValueSource(strings = {"PT90S", "P1DT6H2M1.5S"})
  void staticDuration_returnsStoredIsoTextUnchanged(String iso) throws Exception {
    writeStored("d", "duration", "\"" + iso + "\"");
    try (Quonfig q = newClient()) {
      EvaluationDetails<Object> d = q.evaluateDetails("d");
      assertEquals(iso, d.value(), "details: " + d);
      assertEquals(Reason.STATIC, d.reason());
      assertEquals("static", d.variant());
      assertNull(d.errorCode());
      assertEquals(ValueType.DURATION, d.valueType());
      assertEquals("CONFIG", d.metadata().get("configType"));
      assertEquals("d", d.metadata().get("configKey"));
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"30s", "garbage"})
  void malformedStoredDuration_returnedRawWithSuccessReason(String raw) throws Exception {
    writeStored("bad", "duration", "\"" + raw + "\"");
    try (Quonfig q = newClient()) {
      EvaluationDetails<Object> d = q.evaluateDetails("bad");
      assertEquals(raw, d.value(), "details: " + d);
      assertEquals(Reason.STATIC, d.reason());
      assertNull(d.errorCode());
    }
  }

  @Test
  void otherTypes_returnWireForm() throws Exception {
    writeStored("s", "string", "\"hello\"");
    writeStored("i", "int", "42");
    writeStored("f", "double", "1.5");
    writeStored("b", "bool", "true");
    writeStored("l", "string_list", "[\"a\",\"b\"]");
    writeStored("j", "json", "{\"a\":1,\"b\":[\"c\"]}");
    try (Quonfig q = newClient()) {
      assertEquals("hello", q.evaluateDetails("s").value());
      assertEquals(42L, q.evaluateDetails("i").value());
      assertEquals(1.5, q.evaluateDetails("f").value());
      assertEquals(Boolean.TRUE, q.evaluateDetails("b").value());
      assertEquals(List.of("a", "b"), q.evaluateDetails("l").value());
      assertEquals(ValueType.STRING_LIST, q.evaluateDetails("l").valueType());
      assertEquals(Map.of("a", 1L, "b", List.of("c")), q.evaluateDetails("j").value());
      assertEquals(q.getJsonDetails("j", null).value(), q.evaluateDetails("j").value());
    }
  }

  @Test
  void targetingMatch_reasonAndVariant_withContextAndBoundClient() throws Exception {
    write(
        "t",
        "duration",
        "[{\"criteria\":[{\"propertyName\":\"user.plan\",\"operator\":\"PROP_IS_ONE_OF\","
            + "\"valueToMatch\":{\"type\":\"string_list\",\"value\":[\"pro\"]}}],"
            + "\"value\":{\"type\":\"duration\",\"value\":\"PT90S\"}},"
            + "{\"criteria\":[{\"operator\":\"ALWAYS_TRUE\"}],"
            + "\"value\":{\"type\":\"duration\",\"value\":\"PT5S\"}}]");
    ContextSet pro = new ContextSet().withNamedContext("user", Map.of("plan", "pro"));
    try (Quonfig q = newClient()) {
      EvaluationDetails<Object> d = q.evaluateDetails("t", pro);
      assertEquals("PT90S", d.value(), "details: " + d);
      assertEquals(Reason.TARGETING_MATCH, d.reason());
      assertEquals("targeting:0", d.variant());
      assertEquals(0, d.metadata().get("ruleIndex"));

      EvaluationDetails<Object> bound = q.withContext(pro).evaluateDetails("t");
      assertEquals("PT90S", bound.value());
      assertEquals(Reason.TARGETING_MATCH, bound.reason());

      EvaluationDetails<Object> fallthrough = q.evaluateDetails("t");
      assertEquals("PT5S", fallthrough.value());
      assertEquals("targeting:1", fallthrough.variant());

      // Per-call context overrides the bound one, as for the typed getters.
      ContextSet free = new ContextSet().withNamedContext("user", Map.of("plan", "free"));
      assertEquals("PT5S", q.withContext(pro).evaluateDetails("t", free).value());
    }
  }

  @Test
  void weightedSplit_reasonVariantAndIndex() throws Exception {
    writeStatic(
        "w",
        "duration",
        "{\"type\":\"weighted_values\",\"value\":{\"weightedValues\":["
            + "{\"weight\":100,\"value\":{\"type\":\"duration\",\"value\":\"PT90S\"}}],"
            + "\"hashByPropertyName\":\"user.key\"}}");
    try (Quonfig q = newClient()) {
      EvaluationDetails<Object> d =
          q.evaluateDetails("w", new ContextSet().withNamedContext("user", Map.of("key", "u1")));
      assertEquals("PT90S", d.value(), "details: " + d);
      assertEquals(Reason.SPLIT, d.reason());
      assertEquals("split:0", d.variant());
      assertEquals(0, d.variantIndex());
      assertEquals(0, d.metadata().get("weightedValueIndex"));
    }
  }

  @Test
  void envVarProvidedDuration_returnsEnvTextUnchanged() throws Exception {
    writeStatic(
        "p",
        "duration",
        "{\"type\":\"provided\",\"value\":{\"source\":\"ENV_VAR\",\"lookup\":\"DUR\"}}");
    env.put("DUR", "PT90S");
    try (Quonfig q = newClient()) {
      EvaluationDetails<Object> d = q.evaluateDetails("p");
      assertEquals("PT90S", d.value(), "details: " + d);
      assertEquals(Reason.STATIC, d.reason());
      assertEquals(ValueType.DURATION, d.valueType());
      // The typed getter is unchanged: still a rounded java.time.Duration.
      assertEquals(90_000L, q.getDuration("p", null).toMillis());
    }
  }

  @Test
  void malformedEnvVarDuration_failsAtResolution_likeSdkGo() throws Exception {
    writeStatic(
        "p",
        "duration",
        "{\"type\":\"provided\",\"value\":{\"source\":\"ENV_VAR\",\"lookup\":\"DUR\"}}");
    env.put("DUR", "30s");
    try (Quonfig q = newClient()) {
      EvaluationDetails<Object> d = q.evaluateDetails("p");
      assertNull(d.value(), "details: " + d);
      assertEquals(Reason.ERROR, d.reason());
      assertEquals(ErrorCode.PARSE_ERROR, d.errorCode());
      assertEquals(ValueType.DURATION, d.valueType());
    }
  }

  @Test
  void missingKey_nullValue_sameReasonAndErrorCodeAsTypedGetters() throws Exception {
    writeStored("present", "string", "\"x\"");
    try (Quonfig q = newClient()) {
      EvaluationDetails<Object> d = q.evaluateDetails("nope");
      EvaluationDetails<String> typed = q.getStringDetails("nope", null);
      assertNull(d.value());
      assertEquals(typed.reason(), d.reason());
      assertEquals(Reason.ERROR, d.reason());
      assertEquals(ErrorCode.FLAG_NOT_FOUND, d.errorCode());
      assertEquals(typed.errorCode(), d.errorCode());
      assertEquals("default", d.variant());
      assertNull(d.valueType());
      assertNull(typed.valueType(), "typed getters never set valueType");
      assertNull(q.withContext(new ContextSet()).evaluateDetails("nope").value());
    }
  }

  @Test
  void closedClient_neverThrows() throws Exception {
    writeStored("s", "string", "\"x\"");
    Quonfig q = newClient();
    q.close();
    EvaluationDetails<Object> d = q.evaluateDetails("s");
    assertNull(d.value());
    assertEquals(Reason.ERROR, d.reason());
    assertEquals(ErrorCode.GENERAL, d.errorCode());
  }
}
