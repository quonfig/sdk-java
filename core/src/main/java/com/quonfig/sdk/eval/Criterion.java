package com.quonfig.sdk.eval;

import java.util.Objects;

/** A single condition inside a {@link Rule}. */
public final class Criterion {
  private final String propertyName;
  private final String operator;
  private final Value valueToMatch;

  public Criterion(String propertyName, String operator, Value valueToMatch) {
    this.propertyName = propertyName;
    this.operator = Objects.requireNonNull(operator, "operator");
    this.valueToMatch = valueToMatch;
  }

  public String propertyName() {
    return propertyName;
  }

  public String operator() {
    return operator;
  }

  public Value valueToMatch() {
    return valueToMatch;
  }
}
