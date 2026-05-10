package com.quonfig.sdk.eval;

import java.util.List;
import java.util.Objects;

/** A set of {@link Criterion}s (AND logic) and the {@link Value} produced when they all match. */
public final class Rule {
  private final List<Criterion> criteria;
  private final Value value;

  public Rule(List<Criterion> criteria, Value value) {
    this.criteria = List.copyOf(Objects.requireNonNull(criteria, "criteria"));
    this.value = Objects.requireNonNull(value, "value");
  }

  public List<Criterion> criteria() {
    return criteria;
  }

  public Value value() {
    return value;
  }
}
