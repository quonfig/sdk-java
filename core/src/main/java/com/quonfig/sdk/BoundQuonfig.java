package com.quonfig.sdk;

import com.quonfig.sdk.eval.ContextSet;
import java.time.Duration;
import java.util.List;
import org.slf4j.event.Level;

/**
 * A {@link Quonfig} client view with a fixed {@link ContextSet} pre-applied. Returned by {@link
 * Quonfig#withContext(ContextSet)}; mirrors {@code sdk-go} {@code ContextBoundClient}.
 *
 * <p>Every typed getter forwards to the underlying {@link Quonfig} with the bound context merged
 * onto any per-call context (per-call wins on key collision — same precedence as {@code sdk-go}).
 */
public final class BoundQuonfig {
  private final Quonfig client;
  private final ContextSet bound;

  BoundQuonfig(Quonfig client, ContextSet bound) {
    this.client = client;
    this.bound = bound;
  }

  public ContextSet boundContext() {
    return bound;
  }

  public String getString(String key, String def) {
    return client.getString(key, def, bound);
  }

  public String getString(String key, String def, ContextSet ctx) {
    return client.getString(key, def, Quonfig.merge(bound, ctx));
  }

  public Boolean getBool(String key, Boolean def) {
    return client.getBool(key, def, bound);
  }

  public Boolean getBool(String key, Boolean def, ContextSet ctx) {
    return client.getBool(key, def, Quonfig.merge(bound, ctx));
  }

  /**
   * @deprecated renamed for cross-SDK consistency — use {@link #getBool(String, Boolean)}.
   */
  @Deprecated
  public Boolean getBoolean(String key, Boolean def) {
    return getBool(key, def);
  }

  /**
   * @deprecated renamed for cross-SDK consistency — use {@link #getBool(String, Boolean,
   *     ContextSet)}.
   */
  @Deprecated
  public Boolean getBoolean(String key, Boolean def, ContextSet ctx) {
    return getBool(key, def, ctx);
  }

  /**
   * @deprecated misleading name — returns {@link Long}, not {@link Integer}. Use {@link
   *     #getLong(String, Long)}.
   */
  @Deprecated
  public Long getInt(String key, Long def) {
    return getLong(key, def);
  }

  /**
   * @deprecated misleading name — returns {@link Long}, not {@link Integer}. Use {@link
   *     #getLong(String, Long, ContextSet)}.
   */
  @Deprecated
  public Long getInt(String key, Long def, ContextSet ctx) {
    return getLong(key, def, ctx);
  }

  public Long getLong(String key, Long def) {
    return client.getLong(key, def, bound);
  }

  public Long getLong(String key, Long def, ContextSet ctx) {
    return client.getLong(key, def, Quonfig.merge(bound, ctx));
  }

  public Double getDouble(String key, Double def) {
    return client.getDouble(key, def, bound);
  }

  public Double getDouble(String key, Double def, ContextSet ctx) {
    return client.getDouble(key, def, Quonfig.merge(bound, ctx));
  }

  public List<String> getStringList(String key, List<String> def) {
    return client.getStringList(key, def, bound);
  }

  public List<String> getStringList(String key, List<String> def, ContextSet ctx) {
    return client.getStringList(key, def, Quonfig.merge(bound, ctx));
  }

  public Duration getDuration(String key, Duration def) {
    return client.getDuration(key, def, bound);
  }

  public Duration getDuration(String key, Duration def, ContextSet ctx) {
    return client.getDuration(key, def, Quonfig.merge(bound, ctx));
  }

  public Object getJson(String key, Object def) {
    return client.getJson(key, def, bound);
  }

  public Object getJson(String key, Object def, ContextSet ctx) {
    return client.getJson(key, def, Quonfig.merge(bound, ctx));
  }

  public EvaluationDetails<String> getStringDetails(String key, String def) {
    return client.getStringDetails(key, def, bound);
  }

  public EvaluationDetails<Boolean> getBoolDetails(String key, Boolean def) {
    return client.getBoolDetails(key, def, bound);
  }

  /**
   * @deprecated renamed for cross-SDK consistency — use {@link #getBoolDetails(String, Boolean)}.
   */
  @Deprecated
  public EvaluationDetails<Boolean> getBooleanDetails(String key, Boolean def) {
    return getBoolDetails(key, def);
  }

  /**
   * @deprecated misleading name — returns {@link Long}, not {@link Integer}. Use {@link
   *     #getLongDetails(String, Long)}.
   */
  @Deprecated
  public EvaluationDetails<Long> getIntDetails(String key, Long def) {
    return getLongDetails(key, def);
  }

  public EvaluationDetails<Long> getLongDetails(String key, Long def) {
    return client.getLongDetails(key, def, bound);
  }

  public EvaluationDetails<Double> getDoubleDetails(String key, Double def) {
    return client.getDoubleDetails(key, def, bound);
  }

  public EvaluationDetails<List<String>> getStringListDetails(String key, List<String> def) {
    return client.getStringListDetails(key, def, bound);
  }

  public EvaluationDetails<Duration> getDurationDetails(String key, Duration def) {
    return client.getDurationDetails(key, def, bound);
  }

  public EvaluationDetails<Object> getJsonDetails(String key, Object def) {
    return client.getJsonDetails(key, def, bound);
  }

  public boolean featureIsOn(String key) {
    return client.featureIsOn(key, bound);
  }

  /** See {@link Quonfig#shouldLog(String, Level)} — bound context is merged with the loggerPath. */
  public boolean shouldLog(String loggerPath, Level level) {
    return client.shouldLog(loggerPath, level, bound);
  }

  public boolean shouldLog(String loggerPath, Level level, ContextSet ctx) {
    return client.shouldLog(loggerPath, level, Quonfig.merge(bound, ctx));
  }
}
