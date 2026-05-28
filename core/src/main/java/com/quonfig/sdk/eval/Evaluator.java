package com.quonfig.sdk.eval;

import java.util.List;

/**
 * The main evaluation engine. Applies a {@link ConfigRow}'s rules against a {@link ContextSet} and
 * returns an {@link EvaluationMatch} describing the outcome.
 *
 * <p>Evaluation order:
 *
 * <ol>
 *   <li>If {@code envId} matches one of the config's environments, evaluate that environment's
 *       rules first (top-to-bottom, first match wins).
 *   <li>If no environment-specific match, fall through to the default rules.
 *   <li>For each rule, all criteria must match (AND logic).
 *   <li>If the matched value is {@link ValueType#WEIGHTED_VALUES}, route through the {@link
 *       WeightedValueResolver} to pick a concrete entry.
 * </ol>
 */
public final class Evaluator {

  private final ConfigStore configStore;
  private final WeightedValueResolver weightedResolver;

  public Evaluator(ConfigStore configStore) {
    this(configStore, null);
  }

  public Evaluator(ConfigStore configStore, WeightedValueResolver weightedResolver) {
    this.configStore = configStore;
    this.weightedResolver = weightedResolver;
  }

  public EvaluationMatch evaluate(ConfigRow config, String envId, ContextSet contexts) {
    if (contexts == null) contexts = new ContextSet();

    if (envId != null && !envId.isEmpty()) {
      Environment env = config.findEnvironment(envId);
      if (env != null) {
        EvaluationMatch m = evaluateRules(config, env.rules(), contexts);
        if (m != null) return m;
      }
    }

    EvaluationMatch m = evaluateRules(config, config.defaultRules().rules(), contexts);
    if (m != null) return m;

    return EvaluationMatch.noMatch();
  }

  private EvaluationMatch evaluateRules(ConfigRow config, List<Rule> rules, ContextSet contexts) {
    for (int i = 0; i < rules.size(); i++) {
      Rule rule = rules.get(i);
      if (allCriteriaMatch(config, rule.criteria(), contexts)) {
        Value v = rule.value();
        int weightedIndex = -1;

        if (v.type() == ValueType.WEIGHTED_VALUES && weightedResolver != null) {
          WeightedValueResolver.Resolved r = weightedResolver.resolve(config.key(), v, contexts);
          if (r != null) {
            v = r.value();
            weightedIndex = r.index();
          }
        }

        // Canonical reason (mirrors sdk-go runtime_eval.go hasTargetingRules +
        // integration-test-data
        // telemetry.yaml): a match is STATIC only when the config has NO real targeting anywhere —
        // i.e. every rule's criteria are absent or ALWAYS_TRUE — and the first rule won. Otherwise,
        // including a catch-all fallthrough inside a config that does have targeting rules, the
        // reason is TARGETING_MATCH. SPLIT is layered on top of this at the public API boundary
        // when
        // a weighted bucket was resolved (see Quonfig#typedDetails, weightedValueIndex >= 0).
        // qfg-q7yz.
        EvaluationMatch.Reason reason =
            (i == 0 && !hasTargetingRules(config))
                ? EvaluationMatch.Reason.STATIC
                : EvaluationMatch.Reason.TARGETING_MATCH;
        return EvaluationMatch.matched(v, i, weightedIndex, reason);
      }
    }
    return null;
  }

  /**
   * True if the config has any rule (in the default rule set or any environment-specific rule set)
   * whose criteria include a non-{@code ALWAYS_TRUE} operator. Mirrors sdk-go's {@code
   * hasTargetingRules}: a config that only matches via empty/ALWAYS_TRUE criteria is "static", so
   * its match reports STATIC rather than TARGETING_MATCH.
   */
  private static boolean hasTargetingRules(ConfigRow config) {
    if (anyNonTrivial(config.defaultRules().rules())) return true;
    for (Environment env : config.environments()) {
      if (anyNonTrivial(env.rules())) return true;
    }
    return false;
  }

  private static boolean anyNonTrivial(List<Rule> rules) {
    for (Rule r : rules) {
      for (Criterion c : r.criteria()) {
        if (!Operators.ALWAYS_TRUE.equals(c.operator())) return true;
      }
    }
    return false;
  }

  private boolean allCriteriaMatch(
      ConfigRow config, List<Criterion> criteria, ContextSet contexts) {
    for (Criterion c : criteria) {
      if (!evaluateOne(config, c, contexts)) return false;
    }
    return true;
  }

  private boolean evaluateOne(ConfigRow config, Criterion criterion, ContextSet contexts) {
    ContextSet.Lookup lookup = contexts.getContextValue(criterion.propertyName());

    SegmentResolver segmentResolver =
        segKey -> {
          if (configStore == null) return SegmentResolver.Result.notFound();
          ConfigRow seg = configStore.getConfig(segKey);
          if (seg == null) return SegmentResolver.Result.notFound();
          EvaluationMatch m = evaluate(seg, "", contexts);
          if (!m.isMatch() || m.value() == null) return SegmentResolver.Result.notFound();
          Object raw = m.value().value();
          boolean v = raw instanceof Boolean && (Boolean) raw;
          return SegmentResolver.Result.found(v);
        };

    return Operators.evaluateCriterion(lookup.value(), lookup.exists(), criterion, segmentResolver);
  }
}
