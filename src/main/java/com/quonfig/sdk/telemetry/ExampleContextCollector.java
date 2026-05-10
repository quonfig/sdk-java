package com.quonfig.sdk.telemetry;

import com.quonfig.sdk.eval.ContextSet;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Collects sampled full-context examples (with values) for telemetry drill-down.
 *
 * <p>Only enabled when {@link ContextUploadMode#PERIODIC_EXAMPLE}. Within the rate-limit window,
 * the same grouped key (named contexts × {@code key}/{@code trackingId}) is recorded only once.
 */
public final class ExampleContextCollector {
  private static final long DEFAULT_RATE_LIMIT_MS = 60L * 60L * 1000L; // 1 hour

  private final boolean enabled;
  private final int maxDataSize;
  private final long rateLimitMs;
  private final List<long[]> timestamps = new ArrayList<>(); // [timestamp]
  private final List<ContextSet> data = new ArrayList<>();
  private final Map<String, Long> seen = new LinkedHashMap<>();

  public ExampleContextCollector(ContextUploadMode mode) {
    this(mode, 10_000, DEFAULT_RATE_LIMIT_MS);
  }

  public ExampleContextCollector(ContextUploadMode mode, int maxDataSize, long rateLimitMs) {
    this.enabled = mode == ContextUploadMode.PERIODIC_EXAMPLE;
    this.maxDataSize = maxDataSize;
    this.rateLimitMs = rateLimitMs;
  }

  public boolean isEnabled() {
    return enabled;
  }

  public synchronized void push(ContextSet contexts) {
    if (!enabled || contexts == null) return;
    if (data.size() >= maxDataSize) return;

    String key = groupedKey(contexts);
    if (key.isEmpty()) return;

    long now = System.currentTimeMillis();
    Long lastSeen = seen.get(key);
    if (lastSeen != null && now - lastSeen < rateLimitMs) return;

    timestamps.add(new long[] {now});
    data.add(contexts);
    seen.put(key, now);
  }

  public synchronized Map<String, Object> drain() {
    if (data.isEmpty()) return null;

    List<Map<String, Object>> examples = new ArrayList<>(data.size());
    for (int i = 0; i < data.size(); i++) {
      ContextSet ctx = data.get(i);
      long ts = timestamps.get(i)[0];

      List<Map<String, Object>> contexts = new ArrayList<>(ctx.data().size());
      for (Map.Entry<String, Map<String, Object>> e : ctx.data().entrySet()) {
        Map<String, Object> values = new LinkedHashMap<>(e.getValue());
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("type", e.getKey());
        entry.put("values", values);
        contexts.add(entry);
      }

      Map<String, Object> contextSet = new LinkedHashMap<>();
      contextSet.put("contexts", contexts);

      Map<String, Object> example = new LinkedHashMap<>();
      example.put("timestamp", ts);
      example.put("contextSet", contextSet);
      examples.add(example);
    }

    Map<String, Object> envelope = new LinkedHashMap<>();
    envelope.put("examples", examples);

    Map<String, Object> event = new LinkedHashMap<>();
    event.put("exampleContexts", envelope);

    data.clear();
    timestamps.clear();
    pruneCache();
    return event;
  }

  private String groupedKey(ContextSet contexts) {
    TreeSet<String> parts = new TreeSet<>();
    for (Map<String, Object> ctx : contexts.data().values()) {
      Object id = ctx.get("key");
      if (id == null) id = ctx.get("trackingId");
      if (id == null) continue;
      String s = id instanceof String ? (String) id : String.valueOf(id);
      if (!s.isEmpty()) parts.add(s);
    }
    return String.join("|", parts);
  }

  private void pruneCache() {
    long now = System.currentTimeMillis();
    Iterator<Map.Entry<String, Long>> it = seen.entrySet().iterator();
    while (it.hasNext()) {
      Map.Entry<String, Long> e = it.next();
      if (now - e.getValue() > rateLimitMs) it.remove();
    }
  }
}
