package com.quonfig.sdk.eval;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Named-context bag for evaluation. A {@link ContextSet} holds one or more named contexts (e.g.
 * "user", "device") and supports dotted-property lookup (e.g. "user.email").
 *
 * <p>The magic property names {@code prefab.current-time}, {@code quonfig.current-time}, and {@code
 * reforge.current-time} resolve to the current wall-clock time in milliseconds since the epoch.
 */
public final class ContextSet {
  private final Map<String, Map<String, Object>> data = new LinkedHashMap<>();

  public ContextSet withNamedContext(String name, Map<String, Object> values) {
    data.put(name, new LinkedHashMap<>(values));
    return this;
  }

  public Map<String, Map<String, Object>> data() {
    return data;
  }

  public Lookup getContextValue(String propertyName) {
    if (propertyName == null) return Lookup.absent();

    if ("prefab.current-time".equals(propertyName)
        || "quonfig.current-time".equals(propertyName)
        || "reforge.current-time".equals(propertyName)) {
      return Lookup.present(System.currentTimeMillis());
    }

    int dot = propertyName.indexOf('.');
    String contextName;
    String key;
    if (dot < 0) {
      // Bare property name → look up in the unnamed ("") context. Mirrors
      // sdk-go's splitAtFirstDot, which treats `foo` as `("", "foo")`.
      contextName = "";
      key = propertyName;
    } else {
      contextName = propertyName.substring(0, dot);
      key = propertyName.substring(dot + 1);
    }
    Map<String, Object> nc = data.get(contextName);
    if (nc == null) return Lookup.absent();
    if (!nc.containsKey(key)) return Lookup.absent();
    return Lookup.present(nc.get(key));
  }

  /** Result of a context lookup: did the path exist, and what was the value? */
  public static final class Lookup {
    private static final Lookup ABSENT = new Lookup(false, null);

    private final boolean exists;
    private final Object value;

    private Lookup(boolean exists, Object value) {
      this.exists = exists;
      this.value = value;
    }

    public static Lookup present(Object value) {
      return new Lookup(true, value);
    }

    public static Lookup absent() {
      return ABSENT;
    }

    public boolean exists() {
      return exists;
    }

    public Object value() {
      return value;
    }
  }
}
