package com.quonfig.sdk.telemetry;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Aggregates evaluation observations into a flush-interval summary.
 *
 * <p>A single config evaluated 1000 times collapses into one counter row with {@code count=1000}.
 * Distinct (configId, ruleIndex, weightedValueIndex, selectedValue) tuples produce distinct
 * counters; counters are then grouped by (configKey, configType) into summary rows.
 */
public final class EvaluationSummaryCollector {
  private final boolean enabled;
  private final int maxDataSize;
  private final Map<SummaryKey, Map<CounterKey, CounterCell>> data = new LinkedHashMap<>();
  private Long startAt;

  public EvaluationSummaryCollector(boolean enabled) {
    this(enabled, 10_000);
  }

  public EvaluationSummaryCollector(boolean enabled, int maxDataSize) {
    this.enabled = enabled;
    this.maxDataSize = maxDataSize;
  }

  public boolean isEnabled() {
    return enabled;
  }

  public synchronized void push(EvaluationStat stat) {
    if (!enabled) return;
    if (stat == null || stat.selectedValue() == null) return;
    if ("LOG_LEVEL".equalsIgnoreCase(stat.configType())) return;
    if (data.size() >= maxDataSize && !data.containsKey(summaryKey(stat))) return;

    if (startAt == null) startAt = System.currentTimeMillis();

    boolean redacted = stat.reportableValue() != null;
    String wrapper = redacted ? "string" : wrapperKeyForValue(stat.selectedValue());
    Object payload = redacted ? stat.reportableValue() : stat.selectedValue();

    SummaryKey sk = summaryKey(stat);
    CounterKey ck =
        new CounterKey(
            stat.configId(), stat.ruleIndex(), wrapper, payload, stat.weightedValueIndex());

    Map<CounterKey, CounterCell> bucket = data.computeIfAbsent(sk, k -> new LinkedHashMap<>());
    CounterCell cell = bucket.get(ck);
    if (cell == null) {
      bucket.put(ck, new CounterCell(1L, stat.reason()));
    } else {
      cell.count++;
    }
  }

  public synchronized Map<String, Object> drain() {
    if (data.isEmpty()) return null;

    long end = System.currentTimeMillis();
    long start = startAt != null ? startAt : end;

    List<Map<String, Object>> summaries = new ArrayList<>(data.size());
    for (Map.Entry<SummaryKey, Map<CounterKey, CounterCell>> e : data.entrySet()) {
      List<Map<String, Object>> counters = new ArrayList<>(e.getValue().size());
      for (Map.Entry<CounterKey, CounterCell> ce : e.getValue().entrySet()) {
        Map<String, Object> counter = new LinkedHashMap<>();
        counter.put("configId", ce.getKey().configId);
        counter.put("conditionalValueIndex", ce.getKey().ruleIndex);
        counter.put("configRowIndex", 0);
        counter.put(
            "selectedValue",
            Collections.singletonMap(ce.getKey().wrapper, ce.getKey().selectedValue));
        counter.put("count", ce.getValue().count);
        counter.put("reason", ce.getValue().reason);
        if (ce.getKey().weightedValueIndex >= 0) {
          counter.put("weightedValueIndex", ce.getKey().weightedValueIndex);
        }
        counters.add(counter);
      }

      Map<String, Object> row = new LinkedHashMap<>();
      row.put("key", e.getKey().configKey);
      row.put("type", e.getKey().configType);
      row.put("counters", counters);
      summaries.add(row);
    }

    Map<String, Object> envelope = new LinkedHashMap<>();
    envelope.put("start", start);
    envelope.put("end", end);
    envelope.put("summaries", summaries);

    Map<String, Object> event = new LinkedHashMap<>();
    event.put("summaries", envelope);

    data.clear();
    startAt = null;
    return event;
  }

  private static SummaryKey summaryKey(EvaluationStat s) {
    return new SummaryKey(s.configKey(), s.configType());
  }

  static String wrapperKeyForValue(Object v) {
    if (v instanceof Boolean) return "bool";
    if (v instanceof Long || v instanceof Integer || v instanceof Short || v instanceof Byte) {
      return "int";
    }
    if (v instanceof Double || v instanceof Float) return "double";
    if (v instanceof List) return "stringList";
    return "string";
  }

  private static final class SummaryKey {
    final String configKey;
    final String configType;

    SummaryKey(String configKey, String configType) {
      this.configKey = configKey;
      this.configType = configType;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (!(o instanceof SummaryKey)) return false;
      SummaryKey k = (SummaryKey) o;
      return Objects.equals(configKey, k.configKey) && Objects.equals(configType, k.configType);
    }

    @Override
    public int hashCode() {
      return Objects.hash(configKey, configType);
    }
  }

  private static final class CounterKey {
    final String configId;
    final int ruleIndex;
    final String wrapper;
    final Object selectedValue;
    final int weightedValueIndex;

    CounterKey(
        String configId,
        int ruleIndex,
        String wrapper,
        Object selectedValue,
        int weightedValueIndex) {
      this.configId = configId;
      this.ruleIndex = ruleIndex;
      this.wrapper = wrapper;
      this.selectedValue = selectedValue;
      this.weightedValueIndex = weightedValueIndex;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (!(o instanceof CounterKey)) return false;
      CounterKey k = (CounterKey) o;
      return ruleIndex == k.ruleIndex
          && weightedValueIndex == k.weightedValueIndex
          && Objects.equals(configId, k.configId)
          && Objects.equals(wrapper, k.wrapper)
          && Objects.equals(selectedValue, k.selectedValue);
    }

    @Override
    public int hashCode() {
      return Objects.hash(configId, ruleIndex, wrapper, selectedValue, weightedValueIndex);
    }
  }

  private static final class CounterCell {
    long count;
    final int reason;

    CounterCell(long count, int reason) {
      this.count = count;
      this.reason = reason;
    }
  }
}
