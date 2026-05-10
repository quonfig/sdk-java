package com.quonfig.sdk.eval;

import com.google.common.hash.Hashing;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Murmur3-bucketed implementation of {@link WeightedValueResolver}. Mirrors {@code
 * sdk-go/evalcore/weighted.go} and {@code sdk-node/src/weighted.ts} so the same {@code (configKey,
 * contextValue)} pair always picks the same variant across SDKs.
 *
 * <p>Algorithm: hash {@code configKey + propertyValue} with Murmur3-32 (seed 0), normalize the
 * resulting unsigned 32-bit int into a fraction in {@code [0, 1)}, then walk weights cumulatively
 * and return the first bucket whose running total is {@code >= fraction * totalWeight}.
 *
 * <p>If {@code hashByPropertyName} is empty/null or the named property is absent in the context, we
 * deterministically fall back to bucket 0 (rather than a wall-clock RNG) so behaviour stays
 * reproducible across runs and across SDKs running the same fixture corpus.
 */
public final class Murmur3WeightedValueResolver implements WeightedValueResolver {

  @Override
  @SuppressWarnings("unchecked")
  public Resolved resolve(String configKey, Value weightedValuesValue, ContextSet contexts) {
    Object payload = weightedValuesValue == null ? null : weightedValuesValue.value();
    if (!(payload instanceof Map)) return null;
    Map<?, ?> wvData = (Map<?, ?>) payload;
    Object weighted = wvData.get("weightedValues");
    if (!(weighted instanceof List) || ((List<?>) weighted).isEmpty()) return null;

    String hashByProperty =
        wvData.get("hashByPropertyName") instanceof String
            ? (String) wvData.get("hashByPropertyName")
            : null;
    double fraction = userFraction(configKey, hashByProperty, contexts);

    long total = 0;
    List<Map<String, Object>> entries = new ArrayList<>();
    for (Object e : (List<?>) weighted) {
      if (!(e instanceof Map)) continue;
      Map<String, Object> entry = (Map<String, Object>) e;
      Object w = entry.get("weight");
      long weight = w instanceof Number ? ((Number) w).longValue() : 0;
      total += weight;
      entries.add(entry);
    }
    if (total <= 0) return null;
    double threshold = fraction * (double) total;

    long running = 0;
    for (int i = 0; i < entries.size(); i++) {
      Map<String, Object> entry = entries.get(i);
      Object w = entry.get("weight");
      long weight = w instanceof Number ? ((Number) w).longValue() : 0;
      running += weight;
      if ((double) running >= threshold) {
        Value subValue = parseSubValue(entry.get("value"));
        if (subValue == null) return null;
        return new Resolved(subValue, i);
      }
    }
    Value first = parseSubValue(entries.get(0).get("value"));
    return first == null ? null : new Resolved(first, 0);
  }

  private static double userFraction(String configKey, String hashByProperty, ContextSet ctx) {
    if (hashByProperty == null || hashByProperty.isEmpty() || ctx == null) {
      return 0.0;
    }
    ContextSet.Lookup lookup = ctx.getContextValue(hashByProperty);
    if (!lookup.exists()) return 0.0;
    return murmur3HashZeroToOne(configKey + String.valueOf(lookup.value()));
  }

  /** Mirrors {@code float64(murmur3.Sum32(value)) / float64(math.MaxUint32)} from sdk-go. */
  private static double murmur3HashZeroToOne(String value) {
    int h = Hashing.murmur3_32_fixed().hashString(value, StandardCharsets.UTF_8).asInt();
    long unsigned = ((long) h) & 0xFFFFFFFFL;
    return (double) unsigned / (double) 0xFFFFFFFFL;
  }

  /** Parse a {@code {type: <vt>, value: <raw>}} blob into a typed {@link Value}. */
  private static Value parseSubValue(Object raw) {
    if (!(raw instanceof Map)) return null;
    Map<?, ?> m = (Map<?, ?>) raw;
    String typeStr = m.get("type") instanceof String ? (String) m.get("type") : "string";
    Object payload = m.get("value");
    switch (typeStr) {
      case "int":
        return new Value(
            ValueType.INT,
            payload instanceof Number
                ? ((Number) payload).longValue()
                : payload instanceof String ? Long.parseLong((String) payload) : 0L);
      case "double":
        return new Value(
            ValueType.DOUBLE,
            payload instanceof Number
                ? ((Number) payload).doubleValue()
                : payload instanceof String ? Double.parseDouble((String) payload) : 0.0d);
      case "bool":
        if (payload instanceof Boolean) return new Value(ValueType.BOOL, payload);
        return new Value(ValueType.BOOL, Boolean.parseBoolean(String.valueOf(payload)));
      case "string_list":
      case "stringList":
        if (payload instanceof List) return new Value(ValueType.STRING_LIST, payload);
        return new Value(ValueType.STRING_LIST, List.of());
      default:
        return new Value(ValueType.STRING, payload == null ? "" : String.valueOf(payload));
    }
  }
}
