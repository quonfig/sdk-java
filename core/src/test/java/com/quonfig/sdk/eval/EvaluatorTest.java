package com.quonfig.sdk.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class EvaluatorTest {

  // Tiny in-memory ConfigStore for segment tests.
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

  private static Rule rule(Value value, Criterion... criteria) {
    return new Rule(Arrays.asList(criteria), value);
  }

  private static Rule alwaysTrueRule(Value value) {
    return rule(value, new Criterion(null, Operators.ALWAYS_TRUE, null));
  }

  private static Rule emptyRule(Value value) {
    return new Rule(Collections.emptyList(), value);
  }

  private static ConfigRow flag(String key, ValueType vt, Rule... rules) {
    return new ConfigRow(
        key,
        key,
        ConfigType.FEATURE_FLAG,
        vt,
        false,
        new RuleSet(Arrays.asList(rules)),
        Collections.emptyList());
  }

  // ----- Default rules: STATIC reason for first rule with no criteria -----

  @Test
  void evaluate_returnsStaticReason_whenFirstRuleHasNoCriteria() {
    ConfigRow cfg = flag("flag.x", ValueType.BOOL, emptyRule(new Value(ValueType.BOOL, true)));
    Evaluator ev = new Evaluator(new MapStore());
    EvaluationMatch m = ev.evaluate(cfg, "", new ContextSet());
    assertTrue(m.isMatch());
    assertEquals(true, m.value().value());
    assertEquals(0, m.ruleIndex());
    assertEquals(EvaluationMatch.Reason.STATIC, m.reason());
  }

  @Test
  void evaluate_returnsStaticReason_whenOnlyCriterionIsAlwaysTrue() {
    // A config whose only rule criterion is ALWAYS_TRUE has no real targeting — it
    // matches everyone unconditionally — so the canonical reason is STATIC, matching
    // sdk-go's hasTargetingRules() and integration-test-data telemetry.yaml
    // ("reason is STATIC for feature flag with only ALWAYS_TRUE rules" -> reason 1). qfg-q7yz.
    ConfigRow cfg =
        flag("flag.always", ValueType.BOOL, alwaysTrueRule(new Value(ValueType.BOOL, true)));
    Evaluator ev = new Evaluator(new MapStore());
    EvaluationMatch m = ev.evaluate(cfg, "", new ContextSet());
    assertTrue(m.isMatch());
    assertEquals(true, m.value().value());
    assertEquals(0, m.ruleIndex());
    assertEquals(EvaluationMatch.Reason.STATIC, m.reason());
  }

  // ----- TARGETING_MATCH reason when criteria match -----

  @Test
  void evaluate_targetingMatch_whenCriteriaMatch() {
    Criterion c =
        new Criterion(
            "user.email",
            Operators.PROP_ENDS_WITH_ONE_OF,
            new Value(ValueType.STRING_LIST, List.of("@quonfig.com")));
    ConfigRow cfg =
        flag(
            "flag.internal",
            ValueType.BOOL,
            rule(new Value(ValueType.BOOL, true), c),
            emptyRule(new Value(ValueType.BOOL, false)));

    Evaluator ev = new Evaluator(new MapStore());
    Map<String, Object> u = new HashMap<>();
    u.put("email", "alice@quonfig.com");
    ContextSet ctx = new ContextSet().withNamedContext("user", u);

    EvaluationMatch m = ev.evaluate(cfg, "", ctx);
    assertTrue(m.isMatch());
    assertEquals(true, m.value().value());
    assertEquals(0, m.ruleIndex());
    assertEquals(EvaluationMatch.Reason.TARGETING_MATCH, m.reason());
  }

  @Test
  void evaluate_fallsThroughToFallbackRule_whenFirstDoesNotMatch() {
    Criterion c =
        new Criterion(
            "user.email",
            Operators.PROP_ENDS_WITH_ONE_OF,
            new Value(ValueType.STRING_LIST, List.of("@quonfig.com")));
    ConfigRow cfg =
        flag(
            "flag.internal",
            ValueType.BOOL,
            rule(new Value(ValueType.BOOL, true), c),
            emptyRule(new Value(ValueType.BOOL, false)));

    Evaluator ev = new Evaluator(new MapStore());
    Map<String, Object> u = new HashMap<>();
    u.put("email", "outsider@example.com");
    ContextSet ctx = new ContextSet().withNamedContext("user", u);

    EvaluationMatch m = ev.evaluate(cfg, "", ctx);
    assertTrue(m.isMatch());
    assertEquals(false, m.value().value());
    assertEquals(1, m.ruleIndex(), "second rule (index 1) wins");
    // The config HAS a targeting rule (the first, PROP_ENDS_WITH_ONE_OF), so any match —
    // even falling through to a catch-all rule — is TARGETING_MATCH, not STATIC. This is
    // the canonical sdk-go behaviour (hasTargetingRules() scans the whole config) and is
    // exactly what integration-test-data telemetry.yaml asserts ("reason is TARGETING_MATCH
    // when config has targeting rules but evaluation falls through" -> reason 2). qfg-q7yz.
    assertEquals(
        EvaluationMatch.Reason.TARGETING_MATCH,
        m.reason(),
        "fallthrough in a targeting config is TARGETING_MATCH");
  }

  // ----- AND logic: ALL criteria must match -----

  @Test
  void evaluate_andLogic_acrossMultipleCriteria() {
    Criterion c1 =
        new Criterion(
            "user.email",
            Operators.PROP_ENDS_WITH_ONE_OF,
            new Value(ValueType.STRING_LIST, List.of("@quonfig.com")));
    Criterion c2 =
        new Criterion(
            "user.role",
            Operators.PROP_IS_ONE_OF,
            new Value(ValueType.STRING_LIST, List.of("admin")));
    ConfigRow cfg =
        flag(
            "flag.admin",
            ValueType.BOOL,
            rule(new Value(ValueType.BOOL, true), c1, c2),
            emptyRule(new Value(ValueType.BOOL, false)));

    Evaluator ev = new Evaluator(new MapStore());

    Map<String, Object> bothMatch = new HashMap<>();
    bothMatch.put("email", "alice@quonfig.com");
    bothMatch.put("role", "admin");
    EvaluationMatch good =
        ev.evaluate(cfg, "", new ContextSet().withNamedContext("user", bothMatch));
    assertEquals(true, good.value().value());
    assertEquals(EvaluationMatch.Reason.TARGETING_MATCH, good.reason());

    Map<String, Object> partial = new HashMap<>();
    partial.put("email", "alice@quonfig.com");
    partial.put("role", "user");
    EvaluationMatch onlyOne =
        ev.evaluate(cfg, "", new ContextSet().withNamedContext("user", partial));
    assertEquals(false, onlyOne.value().value(), "AND requires both: only one match falls through");
  }

  // ----- Environment precedence -----

  @Test
  void environmentRules_takePrecedenceOverDefault() {
    Rule envRule = emptyRule(new Value(ValueType.STRING, "from-env"));
    Rule defaultRule = emptyRule(new Value(ValueType.STRING, "from-default"));
    Environment env = new Environment("env-1", List.of(envRule));

    ConfigRow cfg =
        new ConfigRow(
            "k.flag",
            "k.flag",
            ConfigType.CONFIG,
            ValueType.STRING,
            false,
            new RuleSet(List.of(defaultRule)),
            List.of(env));

    Evaluator ev = new Evaluator(new MapStore());
    EvaluationMatch envMatch = ev.evaluate(cfg, "env-1", new ContextSet());
    assertEquals("from-env", envMatch.value().value());

    EvaluationMatch defMatch = ev.evaluate(cfg, "other-env", new ContextSet());
    assertEquals("from-default", defMatch.value().value());

    EvaluationMatch noEnv = ev.evaluate(cfg, "", new ContextSet());
    assertEquals("from-default", noEnv.value().value());
  }

  @Test
  void environmentRules_fallThroughToDefault_whenNoEnvRuleMatches() {
    Criterion neverMatches =
        new Criterion(
            "user.email",
            Operators.PROP_IS_ONE_OF,
            new Value(ValueType.STRING_LIST, List.of("nobody@nowhere")));
    Rule envRule = rule(new Value(ValueType.STRING, "from-env"), neverMatches);
    Rule defaultRule = emptyRule(new Value(ValueType.STRING, "from-default"));
    Environment env = new Environment("env-1", List.of(envRule));

    ConfigRow cfg =
        new ConfigRow(
            "k.flag",
            "k.flag",
            ConfigType.CONFIG,
            ValueType.STRING,
            false,
            new RuleSet(List.of(defaultRule)),
            List.of(env));

    Evaluator ev = new Evaluator(new MapStore());
    EvaluationMatch m = ev.evaluate(cfg, "env-1", new ContextSet());
    assertEquals("from-default", m.value().value(), "env had no match → default rules apply");
  }

  // ----- IN_SEG / NOT_IN_SEG via segment recursion -----

  @Test
  void inSeg_resolvesAnotherConfigAsBoolean() {
    // Segment config: returns true if user.email ends with @quonfig.com
    Criterion segCriterion =
        new Criterion(
            "user.email",
            Operators.PROP_ENDS_WITH_ONE_OF,
            new Value(ValueType.STRING_LIST, List.of("@quonfig.com")));
    ConfigRow segment =
        new ConfigRow(
            "seg.internal-emails",
            "seg.internal-emails",
            ConfigType.SEGMENT,
            ValueType.BOOL,
            false,
            new RuleSet(
                List.of(
                    rule(new Value(ValueType.BOOL, true), segCriterion),
                    emptyRule(new Value(ValueType.BOOL, false)))),
            Collections.emptyList());

    // Config that uses IN_SEG to gate on the segment
    Criterion inSeg =
        new Criterion(null, Operators.IN_SEG, new Value(ValueType.STRING, "seg.internal-emails"));
    ConfigRow flag =
        flag(
            "flag.gated",
            ValueType.BOOL,
            rule(new Value(ValueType.BOOL, true), inSeg),
            emptyRule(new Value(ValueType.BOOL, false)));

    MapStore store = new MapStore().put(segment).put(flag);
    Evaluator ev = new Evaluator(store);

    Map<String, Object> insiderMap = new HashMap<>();
    insiderMap.put("email", "alice@quonfig.com");
    EvaluationMatch insider =
        ev.evaluate(flag, "", new ContextSet().withNamedContext("user", insiderMap));
    assertEquals(true, insider.value().value());

    Map<String, Object> outsiderMap = new HashMap<>();
    outsiderMap.put("email", "bob@example.com");
    EvaluationMatch outsider =
        ev.evaluate(flag, "", new ContextSet().withNamedContext("user", outsiderMap));
    assertEquals(false, outsider.value().value(), "outsider falls through to fallback rule");
  }

  @Test
  void inSeg_segmentMissing_inSegReturnsFalse_andEvalFallsThrough() {
    Criterion inSeg =
        new Criterion(null, Operators.IN_SEG, new Value(ValueType.STRING, "seg.does-not-exist"));
    ConfigRow flag =
        flag(
            "flag.gated",
            ValueType.BOOL,
            rule(new Value(ValueType.BOOL, true), inSeg),
            emptyRule(new Value(ValueType.BOOL, false)));
    Evaluator ev = new Evaluator(new MapStore());
    EvaluationMatch m = ev.evaluate(flag, "", new ContextSet());
    assertEquals(false, m.value().value());
  }

  // ----- DEFAULT reason: empty rule set in both env and default -----

  @Test
  void evaluate_returnsNoMatch_whenAllRulesEmpty() {
    ConfigRow cfg =
        new ConfigRow(
            "k.empty",
            "k.empty",
            ConfigType.CONFIG,
            ValueType.STRING,
            false,
            new RuleSet(Collections.emptyList()),
            Collections.emptyList());
    Evaluator ev = new Evaluator(new MapStore());
    EvaluationMatch m = ev.evaluate(cfg, "", new ContextSet());
    assertFalse(m.isMatch());
    assertEquals(EvaluationMatch.Reason.DEFAULT, m.reason());
  }

  // ----- WeightedValueResolver hook -----

  @Test
  void weightedValue_isResolvedThroughResolver() {
    // Provide a fake resolver that always returns first weighted entry, index 0.
    Value heads = new Value(ValueType.STRING, "heads");
    Value tails = new Value(ValueType.STRING, "tails");
    Map<String, Object> weighted = new LinkedHashMap<>();
    // Use a representation the resolver can interpret. The resolver hook is opaque
    // to the evaluator — we just hand it the wrapping Value and let it return a
    // (resolved Value, index) tuple.
    weighted.put("weightedValues", Arrays.asList(heads, tails));
    Value wv = new Value(ValueType.WEIGHTED_VALUES, weighted);

    ConfigRow cfg = flag("flag.coin", ValueType.STRING, emptyRule(wv));
    WeightedValueResolver resolver =
        (configKey, value, ctx) -> new WeightedValueResolver.Resolved(tails, 1);

    Evaluator ev = new Evaluator(new MapStore(), resolver);
    EvaluationMatch m = ev.evaluate(cfg, "", new ContextSet());
    assertTrue(m.isMatch());
    assertNotNull(m.value());
    assertEquals("tails", m.value().value());
    assertEquals(1, m.weightedValueIndex());
  }

  @Test
  void weightedValue_withoutResolver_returnsRawWeightedValue_andIndexNegativeOne() {
    Map<String, Object> weighted = new LinkedHashMap<>();
    weighted.put("weightedValues", Arrays.asList(new Value(ValueType.STRING, "x")));
    Value wv = new Value(ValueType.WEIGHTED_VALUES, weighted);
    ConfigRow cfg = flag("flag.coin", ValueType.STRING, emptyRule(wv));

    Evaluator ev = new Evaluator(new MapStore());
    EvaluationMatch m = ev.evaluate(cfg, "", new ContextSet());
    assertTrue(m.isMatch());
    // No resolver: leave wrapped value alone, index = -1 to signal unresolved.
    assertEquals(ValueType.WEIGHTED_VALUES, m.value().type());
    assertEquals(-1, m.weightedValueIndex());
  }
}
