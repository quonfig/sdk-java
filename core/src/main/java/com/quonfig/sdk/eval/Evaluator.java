package com.quonfig.sdk.eval;

import java.util.ArrayList;
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
    return evaluate(config, envId, contexts, null);
  }

  /**
   * {@link #evaluate(ConfigRow, String, ContextSet)} plus {@code segPath}: the keys of the configs
   * currently being evaluated above this one through IN_SEG / NOT_IN_SEG. A segment reference back
   * onto the path is a cycle and is treated like a missing segment (IN_SEG false, NOT_IN_SEG true)
   * instead of recursing until StackOverflowError (qfg-9dxb.7, mirrors sdk-go qfg-9dxb.4). It is a
   * path, not a global visited set, so a diamond still resolves. {@code null} means empty.
   */
  private EvaluationMatch evaluate(
      ConfigRow config, String envId, ContextSet contexts, List<String> segPath) {
    if (contexts == null) contexts = new ContextSet();

    if (envId != null && !envId.isEmpty()) {
      Environment env = config.findEnvironment(envId);
      if (env != null) {
        EvaluationMatch m = evaluateRules(config, env.rules(), contexts, segPath);
        if (m != null) return m;
      }
    }

    EvaluationMatch m = evaluateRules(config, config.defaultRules().rules(), contexts, segPath);
    if (m != null) return m;

    return EvaluationMatch.noMatch();
  }

  private EvaluationMatch evaluateRules(
      ConfigRow config, List<Rule> rules, ContextSet contexts, List<String> segPath) {
    for (int i = 0; i < rules.size(); i++) {
      Rule rule = rules.get(i);
      if (allCriteriaMatch(config, rule.criteria(), contexts, segPath)) {
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
      ConfigRow config, List<Criterion> criteria, ContextSet contexts, List<String> segPath) {
    for (Criterion c : criteria) {
      if (!evaluateOne(config, c, contexts, segPath)) return false;
    }
    return true;
  }

  private boolean evaluateOne(
      ConfigRow config, Criterion criterion, ContextSet contexts, List<String> segPath) {
    ContextSet.Lookup lookup = contexts.getContextValue(criterion.propertyName());

    SegmentResolver segmentResolver =
        segKey -> {
          if (configStore == null) return SegmentResolver.Result.notFound();
          // A reference back onto the current evaluation path is a cycle: treat it like a
          // missing segment rather than recursing forever (qfg-9dxb.7).
          if (segKey.equals(config.key()) || (segPath != null && segPath.contains(segKey))) {
            return SegmentResolver.Result.notFound();
          }
          ConfigRow seg = configStore.getConfig(segKey);
          if (seg == null) return SegmentResolver.Result.notFound();
          List<String> childPath = new ArrayList<>(segPath == null ? 1 : segPath.size() + 1);
          if (segPath != null) childPath.addAll(segPath);
          childPath.add(config.key());
          EvaluationMatch m = evaluate(seg, "", contexts, childPath);
          if (!m.isMatch() || m.value() == null) return SegmentResolver.Result.notFound();
          Object raw = m.value().value();
          boolean v = raw instanceof Boolean && (Boolean) raw;
          return SegmentResolver.Result.found(v);
        };

    return Operators.evaluateCriterion(lookup.value(), lookup.exists(), criterion, segmentResolver);
  }
}
