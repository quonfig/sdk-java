package com.quonfig.sdk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.quonfig.sdk.eval.ContextSet;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Drives a {@link Quonfig} client off an on-disk datadir workspace built per-test. Covers the
 * public API surface defined in qfg-mol-1q2: typed getters, EvaluationDetails<T> across all Reason
 * kinds, BoundQuonfig, keys(), featureIsOn(), env-var fallbacks, URL derivation.
 */
class QuonfigDatadirTest {

  @TempDir Path workspaceDir;

  @BeforeEach
  void writeWorkspaceManifest() throws Exception {
    Files.writeString(
        workspaceDir.resolve("quonfig.json"),
        "{\"workspace\":\"test-ws\",\"environments\":[\"production\",\"staging\"]}");
    Files.createDirectories(workspaceDir.resolve("configs"));
    Files.createDirectories(workspaceDir.resolve("feature-flags"));
    Files.createDirectories(workspaceDir.resolve("segments"));
  }

  private Quonfig newClient(String environment) {
    return new Quonfig(
        Options.builder().datadir(workspaceDir.toString()).environment(environment).build());
  }

  private void writeConfig(String subdir, String key, String json) throws Exception {
    Files.writeString(workspaceDir.resolve(subdir).resolve(key + ".json"), json);
  }

  @AfterEach
  void noop() {}

  @Test
  void getString_returnsValueFromStaticRule() throws Exception {
    writeConfig(
        "configs",
        "greeting",
        "{\"key\":\"greeting\",\"type\":\"config\",\"valueType\":\"string\","
            + "\"default\":{\"rules\":[{\"criteria\":[],\"value\":{\"type\":\"string\",\"value\":\"hello\"}}]}}");
    try (Quonfig q = newClient("production")) {
      assertEquals("hello", q.getString("greeting", "fallback"));
    }
  }

  @Test
  void getString_returnsDefault_whenKeyMissing() throws Exception {
    try (Quonfig q = newClient("production")) {
      assertEquals("fallback", q.getString("nope", "fallback"));
    }
  }

  @Test
  void getStringDetails_staticRule_populatesAllMetadata() throws Exception {
    writeConfig(
        "configs",
        "greeting",
        "{\"id\":\"cfg-1\",\"key\":\"greeting\",\"type\":\"config\",\"valueType\":\"string\","
            + "\"default\":{\"rules\":[{\"criteria\":[],\"value\":{\"type\":\"string\",\"value\":\"hello\"}}]}}");
    try (Quonfig q = newClient("production")) {
      EvaluationDetails<String> d = q.getStringDetails("greeting", "fallback");
      assertEquals("hello", d.value());
      assertEquals(Reason.STATIC, d.reason());
      assertEquals("static", d.variant());
      assertNull(d.errorCode());
      assertNull(d.errorMessage());
      assertNull(d.variantIndex());
      Map<String, Object> meta = d.metadata();
      assertEquals("cfg-1", meta.get("configId"));
      assertEquals("greeting", meta.get("configKey"));
      assertEquals("CONFIG", meta.get("configType"));
      // Per qfg-ypcu spec, ruleIndex is omitted unless reason is TARGETING_MATCH or SPLIT.
      assertFalse(meta.containsKey("ruleIndex"));
      assertFalse(meta.containsKey("weightedValueIndex"));
      assertEquals("production", meta.get("environment"));
    }
  }

  @Test
  void getStringDetails_targetingMatch_setsTargetingReasonAndRuleIndex() throws Exception {
    writeConfig(
        "configs",
        "tier",
        "{\"id\":\"cfg-tier\",\"key\":\"tier\",\"type\":\"config\",\"valueType\":\"string\","
            + "\"default\":{\"rules\":["
            + "{\"criteria\":[{\"propertyName\":\"user.plan\",\"operator\":\"PROP_IS_ONE_OF\","
            + "  \"valueToMatch\":{\"type\":\"stringList\",\"value\":[\"pro\"]}}],"
            + " \"value\":{\"type\":\"string\",\"value\":\"premium\"}},"
            + "{\"criteria\":[],\"value\":{\"type\":\"string\",\"value\":\"basic\"}}"
            + "]}}");
    try (Quonfig q = newClient("production")) {
      ContextSet ctx = new ContextSet().withNamedContext("user", Map.of("plan", "pro"));
      EvaluationDetails<String> d = q.getStringDetails("tier", "fallback", ctx);
      assertEquals("premium", d.value());
      assertEquals(Reason.TARGETING_MATCH, d.reason());
      assertEquals("targeting:0", d.variant());
      assertEquals(0, d.metadata().get("ruleIndex"));
    }
  }

  @Test
  void getStringDetails_default_setsDefaultReason_whenNoRuleMatches() throws Exception {
    writeConfig(
        "configs",
        "tier",
        "{\"id\":\"cfg-tier\",\"key\":\"tier\",\"type\":\"config\",\"valueType\":\"string\","
            + "\"default\":{\"rules\":["
            + "{\"criteria\":[{\"propertyName\":\"user.plan\",\"operator\":\"PROP_IS_ONE_OF\","
            + "  \"valueToMatch\":{\"type\":\"stringList\",\"value\":[\"pro\"]}}],"
            + " \"value\":{\"type\":\"string\",\"value\":\"premium\"}}"
            + "]}}");
    try (Quonfig q = newClient("production")) {
      EvaluationDetails<String> d = q.getStringDetails("tier", "anonymous");
      assertEquals("anonymous", d.value());
      assertEquals(Reason.DEFAULT, d.reason());
      assertEquals("default", d.variant());
    }
  }

  @Test
  void getStringDetails_error_setsErrorReasonAndCode_whenKeyMissing() throws Exception {
    try (Quonfig q = newClient("production")) {
      EvaluationDetails<String> d = q.getStringDetails("missing", "fallback");
      assertEquals("fallback", d.value());
      assertEquals(Reason.ERROR, d.reason());
      assertEquals("default", d.variant());
      assertEquals(ErrorCode.FLAG_NOT_FOUND, d.errorCode());
      assertNotNull(d.errorMessage());
    }
  }

  @Test
  void getStringDetails_error_setsTypeMismatch_whenValueTypeWrong() throws Exception {
    writeConfig(
        "configs",
        "n",
        "{\"id\":\"cfg-n\",\"key\":\"n\",\"type\":\"config\",\"valueType\":\"int\","
            + "\"default\":{\"rules\":[{\"criteria\":[],\"value\":{\"type\":\"int\",\"value\":42}}]}}");
    try (Quonfig q = newClient("production")) {
      EvaluationDetails<String> d = q.getStringDetails("n", "fallback");
      assertEquals("fallback", d.value());
      assertEquals(Reason.ERROR, d.reason());
      assertEquals(ErrorCode.TYPE_MISMATCH, d.errorCode());
    }
  }

  @Test
  void getBoolean_andDetails() throws Exception {
    writeConfig(
        "feature-flags",
        "flag",
        "{\"id\":\"f1\",\"key\":\"flag\",\"type\":\"feature_flag\",\"valueType\":\"bool\","
            + "\"default\":{\"rules\":[{\"criteria\":[],"
            + "\"value\":{\"type\":\"bool\",\"value\":true}}]}}");
    try (Quonfig q = newClient("production")) {
      assertTrue(q.getBoolean("flag", false));
      EvaluationDetails<Boolean> d = q.getBooleanDetails("flag", false);
      assertTrue(d.value());
      assertEquals(Reason.STATIC, d.reason());
      assertEquals("FEATURE_FLAG", d.metadata().get("configType"));
    }
  }

  @Test
  void getInt_returnsLong() throws Exception {
    writeConfig(
        "configs",
        "n",
        "{\"id\":\"n1\",\"key\":\"n\",\"type\":\"config\",\"valueType\":\"int\","
            + "\"default\":{\"rules\":[{\"criteria\":[],\"value\":{\"type\":\"int\",\"value\":7}}]}}");
    try (Quonfig q = newClient("production")) {
      assertEquals(7L, q.getInt("n", 0L));
      EvaluationDetails<Long> d = q.getIntDetails("n", 0L);
      assertEquals(7L, d.value());
      assertEquals(Reason.STATIC, d.reason());
    }
  }

  @Test
  void getLong_returnsLong() throws Exception {
    writeConfig(
        "configs",
        "n",
        "{\"id\":\"n1\",\"key\":\"n\",\"type\":\"config\",\"valueType\":\"int\","
            + "\"default\":{\"rules\":[{\"criteria\":[],\"value\":{\"type\":\"int\",\"value\":7}}]}}");
    try (Quonfig q = newClient("production")) {
      assertEquals(7L, q.getLong("n", 0L));
      EvaluationDetails<Long> d = q.getLongDetails("n", 0L);
      assertEquals(7L, d.value());
      assertEquals(Reason.STATIC, d.reason());
    }
  }

  @Test
  void getLong_withContext_returnsLong() throws Exception {
    writeConfig(
        "configs",
        "n",
        "{\"id\":\"n1\",\"key\":\"n\",\"type\":\"config\",\"valueType\":\"int\","
            + "\"default\":{\"rules\":[{\"criteria\":[],\"value\":{\"type\":\"int\",\"value\":42}}]}}");
    try (Quonfig q = newClient("production")) {
      ContextSet ctx = new ContextSet();
      assertEquals(42L, q.getLong("n", 0L, ctx));
      EvaluationDetails<Long> d = q.getLongDetails("n", 0L, ctx);
      assertEquals(42L, d.value());
      assertEquals(Reason.STATIC, d.reason());
    }
  }

  @Test
  void boundQuonfig_getLong_returnsLong() throws Exception {
    writeConfig(
        "configs",
        "n",
        "{\"id\":\"n1\",\"key\":\"n\",\"type\":\"config\",\"valueType\":\"int\","
            + "\"default\":{\"rules\":[{\"criteria\":[],\"value\":{\"type\":\"int\",\"value\":99}}]}}");
    try (Quonfig q = newClient("production")) {
      BoundQuonfig b = q.withContext(new ContextSet());
      assertEquals(99L, b.getLong("n", 0L));
      EvaluationDetails<Long> d = b.getLongDetails("n", 0L);
      assertEquals(99L, d.value());
      assertEquals(Reason.STATIC, d.reason());
    }
  }

  @Test
  void getDouble() throws Exception {
    writeConfig(
        "configs",
        "p",
        "{\"id\":\"p1\",\"key\":\"p\",\"type\":\"config\",\"valueType\":\"double\","
            + "\"default\":{\"rules\":[{\"criteria\":[],\"value\":{\"type\":\"double\",\"value\":3.14}}]}}");
    try (Quonfig q = newClient("production")) {
      assertEquals(3.14, q.getDouble("p", 0.0));
    }
  }

  @Test
  void getStringList() throws Exception {
    writeConfig(
        "configs",
        "tags",
        "{\"id\":\"t1\",\"key\":\"tags\",\"type\":\"config\",\"valueType\":\"stringList\","
            + "\"default\":{\"rules\":[{\"criteria\":[],"
            + "\"value\":{\"type\":\"stringList\",\"value\":[\"a\",\"b\"]}}]}}");
    try (Quonfig q = newClient("production")) {
      assertEquals(List.of("a", "b"), q.getStringList("tags", List.of()));
    }
  }

  @Test
  void getJson_returnsParsedObject() throws Exception {
    writeConfig(
        "configs",
        "obj",
        "{\"id\":\"o1\",\"key\":\"obj\",\"type\":\"config\",\"valueType\":\"json\","
            + "\"default\":{\"rules\":[{\"criteria\":[],"
            + "\"value\":{\"type\":\"json\",\"value\":{\"a\":1,\"b\":\"two\"}}}]}}");
    try (Quonfig q = newClient("production")) {
      Object json = q.getJson("obj", null);
      assertTrue(json instanceof Map);
    }
  }

  @Test
  void keys_returnsAllConfigKeys() throws Exception {
    writeConfig(
        "configs",
        "a",
        "{\"id\":\"a1\",\"key\":\"a\",\"type\":\"config\",\"valueType\":\"string\","
            + "\"default\":{\"rules\":[{\"criteria\":[],\"value\":{\"type\":\"string\",\"value\":\"x\"}}]}}");
    writeConfig(
        "feature-flags",
        "b",
        "{\"id\":\"b1\",\"key\":\"b\",\"type\":\"feature_flag\",\"valueType\":\"bool\","
            + "\"default\":{\"rules\":[{\"criteria\":[],\"value\":{\"type\":\"bool\",\"value\":true}}]}}");
    try (Quonfig q = newClient("production")) {
      assertTrue(q.keys().contains("a"));
      assertTrue(q.keys().contains("b"));
    }
  }

  @Test
  void featureIsOn_returnsTrueForBoolFlag() throws Exception {
    writeConfig(
        "feature-flags",
        "enabled",
        "{\"id\":\"e1\",\"key\":\"enabled\",\"type\":\"feature_flag\",\"valueType\":\"bool\","
            + "\"default\":{\"rules\":[{\"criteria\":[],\"value\":{\"type\":\"bool\",\"value\":true}}]}}");
    try (Quonfig q = newClient("production")) {
      assertTrue(q.featureIsOn("enabled", new ContextSet()));
    }
  }

  @Test
  void featureIsOn_returnsFalseForMissingFlag() throws Exception {
    try (Quonfig q = newClient("production")) {
      assertFalse(q.featureIsOn("absent", new ContextSet()));
    }
  }

  @Test
  void withContext_returnsBoundQuonfig_appliedToTargetingRules() throws Exception {
    writeConfig(
        "configs",
        "tier",
        "{\"id\":\"tier-cfg\",\"key\":\"tier\",\"type\":\"config\",\"valueType\":\"string\","
            + "\"default\":{\"rules\":["
            + "{\"criteria\":[{\"propertyName\":\"user.plan\",\"operator\":\"PROP_IS_ONE_OF\","
            + "  \"valueToMatch\":{\"type\":\"stringList\",\"value\":[\"pro\"]}}],"
            + " \"value\":{\"type\":\"string\",\"value\":\"premium\"}},"
            + "{\"criteria\":[],\"value\":{\"type\":\"string\",\"value\":\"basic\"}}"
            + "]}}");
    try (Quonfig q = newClient("production")) {
      BoundQuonfig bound =
          q.withContext(new ContextSet().withNamedContext("user", Map.of("plan", "pro")));
      assertEquals("premium", bound.getString("tier", "fallback"));
      EvaluationDetails<String> d = bound.getStringDetails("tier", "fallback");
      assertEquals(Reason.TARGETING_MATCH, d.reason());
    }
  }

  @Test
  void environmentRule_overridesDefaultRule() throws Exception {
    writeConfig(
        "configs",
        "msg",
        "{\"id\":\"m1\",\"key\":\"msg\",\"type\":\"config\",\"valueType\":\"string\","
            + "\"default\":{\"rules\":[{\"criteria\":[],\"value\":{\"type\":\"string\",\"value\":\"dev-msg\"}}]},"
            + "\"environments\":[{\"id\":\"production\",\"rules\":["
            + "  {\"criteria\":[],\"value\":{\"type\":\"string\",\"value\":\"prod-msg\"}}]}]}");
    try (Quonfig q = newClient("production")) {
      assertEquals("prod-msg", q.getString("msg", "fallback"));
      EvaluationDetails<String> d = q.getStringDetails("msg", "fallback");
      assertEquals("production", d.metadata().get("environment"));
    }
    try (Quonfig q = newClient("staging")) {
      assertEquals("dev-msg", q.getString("msg", "fallback"));
    }
  }

  @Test
  void getStringDetails_split_setsSplitReasonAndVariantIndex() throws Exception {
    writeConfig(
        "configs",
        "ab",
        "{\"id\":\"ab1\",\"key\":\"ab\",\"type\":\"config\",\"valueType\":\"string\","
            + "\"default\":{\"rules\":[{\"criteria\":[],"
            + "\"value\":{\"type\":\"weightedValues\",\"value\":{\"weightedValues\":["
            + "{\"weight\":50,\"value\":{\"type\":\"string\",\"value\":\"a\"}},"
            + "{\"weight\":50,\"value\":{\"type\":\"string\",\"value\":\"b\"}}"
            + "]}}}]}}");
    com.quonfig.sdk.eval.WeightedValueResolver fake =
        (configKey, weightedValuesValue, contexts) ->
            new com.quonfig.sdk.eval.WeightedValueResolver.Resolved(
                new com.quonfig.sdk.eval.Value(com.quonfig.sdk.eval.ValueType.STRING, "b"), 1);
    try (Quonfig q =
        new Quonfig(
            Options.builder()
                .datadir(workspaceDir.toString())
                .environment("production")
                .weightedValueResolver(fake)
                .build())) {
      EvaluationDetails<String> d = q.getStringDetails("ab", "fallback");
      assertEquals("b", d.value());
      assertEquals(Reason.SPLIT, d.reason());
      assertEquals("split:1", d.variant());
      assertEquals(Integer.valueOf(1), d.variantIndex());
      assertEquals(1, d.metadata().get("weightedValueIndex"));
    }
  }

  @Test
  void datadir_required_throwsWhenMissing() {
    assertThrows(
        IllegalStateException.class,
        () -> new Quonfig(Options.builder().environment("production").build()));
  }
}
