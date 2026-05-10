package com.quonfig.sdk.eval;

import java.util.Objects;

/**
 * Resolves a {@link ValueType#WEIGHTED_VALUES} {@link Value} into a concrete sub-value plus the
 * index of the weighted entry that won. The actual MurmurHash3-bucketed implementation lives in
 * qfg-oi0j.5; the {@link Evaluator} just calls this hook when it sees a weighted-values match.
 */
@FunctionalInterface
public interface WeightedValueResolver {
  Resolved resolve(String configKey, Value weightedValuesValue, ContextSet contexts);

  /** ({@link Value}, weighted-entry index) pair. */
  final class Resolved {
    private final Value value;
    private final int index;

    public Resolved(Value value, int index) {
      this.value = Objects.requireNonNull(value, "value");
      this.index = index;
    }

    public Value value() {
      return value;
    }

    public int index() {
      return index;
    }
  }
}
