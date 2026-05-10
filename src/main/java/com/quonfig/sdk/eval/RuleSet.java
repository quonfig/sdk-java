package com.quonfig.sdk.eval;

import java.util.List;
import java.util.Objects;

/** An ordered list of {@link Rule}s; first match wins. */
public final class RuleSet {
  private final List<Rule> rules;

  public RuleSet(List<Rule> rules) {
    this.rules = List.copyOf(Objects.requireNonNull(rules, "rules"));
  }

  public List<Rule> rules() {
    return rules;
  }
}
