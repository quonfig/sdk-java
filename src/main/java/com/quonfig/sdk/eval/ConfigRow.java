package com.quonfig.sdk.eval;

import java.util.List;
import java.util.Objects;

/**
 * The fully-resolved row that the {@link Evaluator} operates on: a config or feature flag's key,
 * type, default rules, and per-environment overrides.
 *
 * <p>This is intentionally minimal — wire-format domain types live in {@code com.quonfig.sdk}
 * (qfg-oi0j.2).
 */
public final class ConfigRow {
  private final String id;
  private final String key;
  private final ConfigType type;
  private final ValueType valueType;
  private final boolean sendToClientSdk;
  private final RuleSet defaultRules;
  private final List<Environment> environments;

  public ConfigRow(
      String id,
      String key,
      ConfigType type,
      ValueType valueType,
      boolean sendToClientSdk,
      RuleSet defaultRules,
      List<Environment> environments) {
    this.id = Objects.requireNonNull(id, "id");
    this.key = Objects.requireNonNull(key, "key");
    this.type = Objects.requireNonNull(type, "type");
    this.valueType = Objects.requireNonNull(valueType, "valueType");
    this.sendToClientSdk = sendToClientSdk;
    this.defaultRules = Objects.requireNonNull(defaultRules, "defaultRules");
    this.environments = List.copyOf(Objects.requireNonNull(environments, "environments"));
  }

  public String id() {
    return id;
  }

  public String key() {
    return key;
  }

  public ConfigType type() {
    return type;
  }

  public ValueType valueType() {
    return valueType;
  }

  public boolean sendToClientSdk() {
    return sendToClientSdk;
  }

  public RuleSet defaultRules() {
    return defaultRules;
  }

  public List<Environment> environments() {
    return environments;
  }

  /** Returns the environment whose id matches, or null. */
  public Environment findEnvironment(String envId) {
    if (envId == null || envId.isEmpty()) return null;
    for (Environment e : environments) {
      if (envId.equals(e.id())) return e;
    }
    return null;
  }
}
