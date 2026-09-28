package com.quonfig.sdk.eval;

/**
 * Outcome of evaluating a {@link ConfigRow} against a {@link ContextSet}.
 *
 * <p>Fields:
 *
 * <ul>
 *   <li>{@link #isMatch()} — whether any rule matched
 *   <li>{@link #value()} — the resolved value (post-weighted-value resolution if applicable); null
 *       when no rule matched
 *   <li>{@link #ruleIndex()} — index of the winning rule in the active rule set, or -1 if no match
 *   <li>{@link #weightedValueIndex()} — index of the resolved entry within a weighted-values
 *       bucket, or -1 if the matched value is not weighted (or no resolver was supplied)
 *   <li>{@link #reason()} — coarse classification: STATIC / TARGETING_MATCH / DEFAULT
 * </ul>
 */
public final class EvaluationMatch {

  /** Why this match was produced. */
  public enum Reason {
    /** First rule with no criteria — no targeting needed. */
    STATIC,
    /** A rule's criteria all matched the context. */
    TARGETING_MATCH,
    /** No rule matched; caller falls back to its own default. */
    DEFAULT;
  }

  private final boolean isMatch;
  private final Value value;
  private final int ruleIndex;
  private final int weightedValueIndex;
  private final Reason reason;
  private final String missingHashProperty;

  private EvaluationMatch(
      boolean isMatch,
      Value value,
      int ruleIndex,
      int weightedValueIndex,
      Reason reason,
      String missingHashProperty) {
    this.isMatch = isMatch;
    this.value = value;
    this.ruleIndex = ruleIndex;
    this.weightedValueIndex = weightedValueIndex;
    this.reason = reason;
    this.missingHashProperty = missingHashProperty;
  }

  static EvaluationMatch matched(
      Value value, int ruleIndex, int weightedValueIndex, Reason reason) {
    return matched(value, ruleIndex, weightedValueIndex, reason, null);
  }

  static EvaluationMatch matched(
      Value value,
      int ruleIndex,
      int weightedValueIndex,
      Reason reason,
      String missingHashProperty) {
    return new EvaluationMatch(
        true, value, ruleIndex, weightedValueIndex, reason, missingHashProperty);
  }

  static EvaluationMatch noMatch() {
    return new EvaluationMatch(false, null, -1, -1, Reason.DEFAULT, null);
  }

  public boolean isMatch() {
    return isMatch;
  }

  public Value value() {
    return value;
  }

  public int ruleIndex() {
    return ruleIndex;
  }

  public int weightedValueIndex() {
    return weightedValueIndex;
  }

  public Reason reason() {
    return reason;
  }

  /**
   * The weighted rollout's {@code hashByPropertyName} when it was missing from the context and the
   * first variant was served as a fallback (qfg-9dxb.8); null otherwise.
   */
  public String missingHashProperty() {
    return missingHashProperty;
  }
}
