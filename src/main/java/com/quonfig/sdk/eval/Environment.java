package com.quonfig.sdk.eval;

import java.util.List;
import java.util.Objects;

/** Environment-specific rule overrides. */
public final class Environment {
  private final String id;
  private final List<Rule> rules;

  public Environment(String id, List<Rule> rules) {
    this.id = Objects.requireNonNull(id, "id");
    this.rules = List.copyOf(Objects.requireNonNull(rules, "rules"));
  }

  public String id() {
    return id;
  }

  public List<Rule> rules() {
    return rules;
  }
}
