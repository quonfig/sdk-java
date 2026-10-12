package com.quonfig.sdk.telemetry;

import com.quonfig.sdk.eval.ContextSet;
import java.lang.reflect.Array;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.URI;
import java.time.ZoneId;
import java.time.temporal.TemporalAccessor;
import java.time.temporal.TemporalAmount;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;

/**
 * Collects sampled full-context examples (with values) for telemetry drill-down.
 *
 * <p>Only enabled when {@link ContextUploadMode#PERIODIC_EXAMPLE}. Within the rate-limit window,
 * the same grouped key (named contexts × {@code key}/{@code trackingId}) is recorded only once.
 */
public final class ExampleContextCollector {
  private static final long DEFAULT_RATE_LIMIT_MS = 60L * 60L * 1000L; // 1 hour

  /** Bound on the rate-limit map of recently seen keys (qfg-y8je.9). */
  static final int SEEN_CAP = 100_000;

  private volatile boolean enabled;
  private final int maxDataSize;
  private final long rateLimitMs;
  private final List<long[]> timestamps = new ArrayList<>(); // [timestamp]

  /** Recorded examples: JSON-safe copies taken at push time (see {@link #copyValue}). */
  private final List<Map<String, Map<String, Object>>> data = new ArrayList<>();

  /**
   * Nesting bound for {@link #copyValue}: a container deeper than this is dropped. Cycles are cut
   * separately, so this only guards against stack overflow on very deep acyclic values.
   */
  static final int MAX_COPY_DEPTH = 32;

  /** Marker for a value the copy drops. */
  private static final Object DROP = new Object();

  /**
   * Rate-limit map, kept in last-seen order (oldest first): a re-recorded key is removed and
   * re-inserted, so {@link #pruneCache()} can stop at the first unexpired entry instead of scanning
   * the whole map on the evaluation hot path.
   */
  private final Map<String, Long> seen = new LinkedHashMap<>();

  /** Test hook: total {@code seen} entries examined by {@link #pruneCache()}. */
  long pruneScanned;

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
    if (lastSeen == null && seen.size() >= SEEN_CAP) {
      pruneCache();
      if (seen.size() >= SEEN_CAP) return; // drop newest: memory stays bounded
    }

    timestamps.add(new long[] {now});
    data.add(snapshot(contexts));
    if (lastSeen != null) seen.remove(key); // move to the tail: keep last-seen order
    seen.put(key, now);
  }

  public synchronized Map<String, Object> drain() {
    if (data.isEmpty()) return null;

    List<Map<String, Object>> examples = new ArrayList<>(data.size());
    for (int i = 0; i < data.size(); i++) {
      Map<String, Map<String, Object>> ctx = data.get(i);
      long ts = timestamps.get(i)[0];

      List<Map<String, Object>> contexts = new ArrayList<>(ctx.size());
      for (Map.Entry<String, Map<String, Object>> e : ctx.entrySet()) {
        Map<String, Object> values = e.getValue();
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

  /** Stop recording and discard the window (telemetry disabled for the process). */
  synchronized void disable() {
    enabled = false;
    data.clear();
    timestamps.clear();
    seen.clear();
  }

  /**
   * Copies {@code contexts} into data that shares nothing with the caller and that a plain Jackson
   * {@code ObjectMapper} can serialize (qfg-goi1.2.38). The flush serializes on the telemetry
   * thread, later: holding the caller's objects meant a mutation after the evaluation changed the
   * recorded example or failed the flush, and one value Jackson cannot write by default (an {@code
   * Instant}) dropped the whole window. See {@link #copyValue} for what is kept and what is
   * dropped. A {@code key} or {@code trackingId} the copy would drop is rendered with {@code
   * String.valueOf}, as {@link #groupedKey} already does, so the example is never lost to its key.
   */
  static Map<String, Map<String, Object>> snapshot(ContextSet contexts) {
    Map<String, Map<String, Object>> out = new LinkedHashMap<>();
    for (Map.Entry<String, Map<String, Object>> nc : contexts.data().entrySet()) {
      Map<String, Object> values = new LinkedHashMap<>();
      Map<String, Object> src = nc.getValue();
      if (src != null) {
        for (Map.Entry<String, Object> e : src.entrySet()) {
          Object c = copyValue(e.getValue(), new IdentityHashMap<>(), 0);
          if (c == DROP && ("key".equals(e.getKey()) || "trackingId".equals(e.getKey()))) {
            c = String.valueOf(e.getValue());
          }
          if (c != DROP) values.put(e.getKey(), c);
        }
      }
      out.put(nc.getKey(), values);
    }
    return out;
  }

  /**
   * JSON-safe deep copy of one context value, or {@link #DROP}.
   *
   * <ul>
   *   <li>Kept as is: {@code null}, {@code String}, {@code Boolean}, the boxed integer and floating
   *       types, {@code BigInteger} and {@code BigDecimal} (all immutable).
   *   <li>Rendered to a string: {@code UUID}, {@code URI}, {@code Character} and other {@code
   *       CharSequence}s, enums (by {@code name()}), {@code java.time} values ({@code Instant},
   *       {@code LocalDate}, {@code OffsetDateTime}, {@code Duration}, {@code ZoneId}, ... by
   *       {@code toString()}, which is ISO-8601) and {@code java.util.Date} (as its {@code
   *       Instant}).
   *   <li>Copied: {@code Map}s (keys by {@code String.valueOf}), {@code Collection}s, object arrays
   *       and primitive arrays (to lists).
   *   <li>Dropped: every other type (application objects, {@code Optional}, atomics), a container
   *       that contains itself (cut at the edge that closes the cycle) and containers nested deeper
   *       than {@link #MAX_COPY_DEPTH}. A dropped entry is left out of its map or list.
   * </ul>
   */
  static Object copyValue(Object v, IdentityHashMap<Object, Boolean> path, int depth) {
    if (v == null
        || v instanceof String
        || v instanceof Boolean
        || v instanceof Long
        || v instanceof Integer
        || v instanceof Short
        || v instanceof Byte
        || v instanceof Double
        || v instanceof Float
        || v instanceof BigInteger
        || v instanceof BigDecimal) {
      return v;
    }
    if (v instanceof CharSequence
        || v instanceof Character
        || v instanceof UUID
        || v instanceof URI
        || v instanceof TemporalAccessor
        || v instanceof TemporalAmount
        || v instanceof ZoneId) {
      return v.toString();
    }
    if (v instanceof Enum) return ((Enum<?>) v).name();
    if (v instanceof Date) return ((Date) v).toInstant().toString();

    boolean container = v instanceof Map || v instanceof Collection || v.getClass().isArray();
    if (!container) return DROP;
    if (depth >= MAX_COPY_DEPTH || path.containsKey(v)) return DROP;
    path.put(v, Boolean.TRUE);
    try {
      if (v instanceof Map) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : ((Map<?, ?>) v).entrySet()) {
          Object c = copyValue(e.getValue(), path, depth + 1);
          if (c != DROP) out.put(String.valueOf(e.getKey()), c);
        }
        return out;
      }
      List<Object> out = new ArrayList<>();
      if (v instanceof Collection) {
        for (Object e : (Collection<?>) v) {
          Object c = copyValue(e, path, depth + 1);
          if (c != DROP) out.add(c);
        }
      } else {
        int n = Array.getLength(v);
        for (int i = 0; i < n; i++) {
          Object c = copyValue(Array.get(v, i), path, depth + 1);
          if (c != DROP) out.add(c);
        }
      }
      return out;
    } finally {
      path.remove(v);
    }
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

  /**
   * Evict expired keys from the head of the (last-seen ordered) map, stopping at the first
   * unexpired one. Cost is O(evicted + 1), so a full map of fresh keys rejects a new key without a
   * scan.
   */
  private void pruneCache() {
    long now = System.currentTimeMillis();
    Iterator<Map.Entry<String, Long>> it = seen.entrySet().iterator();
    while (it.hasNext()) {
      Map.Entry<String, Long> e = it.next();
      pruneScanned++;
      if (now - e.getValue() <= rateLimitMs) break;
      it.remove();
    }
  }

  int maxDataSize() {
    return maxDataSize;
  }
}
