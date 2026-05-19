package com.quonfig.sdk.telemetry;

import com.quonfig.sdk.eval.ContextSet;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Collects context shapes (named context → property name → field-type code) for telemetry.
 *
 * <p>Field-type codes match the rest of the SDK family: 1=int, 2=string, 4=double, 5=bool,
 * 10=string list / array.
 */
public final class ContextShapeCollector {
  static final int FIELD_TYPE_INT = 1;
  static final int FIELD_TYPE_STRING = 2;
  static final int FIELD_TYPE_DOUBLE = 4;
  static final int FIELD_TYPE_BOOL = 5;
  static final int FIELD_TYPE_ARRAY = 10;

  private final boolean enabled;
  private final int maxDataSize;
  private final Map<String, Map<String, Integer>> shapes = new LinkedHashMap<>();

  public ContextShapeCollector(ContextUploadMode mode) {
    this(mode, 10_000);
  }

  public ContextShapeCollector(ContextUploadMode mode, int maxDataSize) {
    this.enabled = mode != ContextUploadMode.NONE;
    this.maxDataSize = maxDataSize;
  }

  public boolean isEnabled() {
    return enabled;
  }

  public synchronized void push(ContextSet contexts) {
    if (!enabled || contexts == null) return;
    for (Map.Entry<String, Map<String, Object>> e : contexts.data().entrySet()) {
      String name = e.getKey();
      Map<String, Integer> shape = shapes.get(name);
      if (shape == null) {
        if (shapes.size() >= maxDataSize) continue;
        shape = new LinkedHashMap<>();
        shapes.put(name, shape);
      }
      for (Map.Entry<String, Object> p : e.getValue().entrySet()) {
        shape.putIfAbsent(p.getKey(), fieldTypeForValue(p.getValue()));
      }
    }
  }

  public synchronized Map<String, Object> drain() {
    if (shapes.isEmpty()) return null;
    List<Map<String, Object>> list = new ArrayList<>(shapes.size());
    for (Map.Entry<String, Map<String, Integer>> e : shapes.entrySet()) {
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("name", e.getKey());
      row.put("fieldTypes", new LinkedHashMap<>(e.getValue()));
      list.add(row);
    }
    Map<String, Object> envelope = new LinkedHashMap<>();
    envelope.put("shapes", list);
    Map<String, Object> event = new LinkedHashMap<>();
    event.put("contextShapes", envelope);

    shapes.clear();
    return event;
  }

  static int fieldTypeForValue(Object v) {
    if (v instanceof Boolean) return FIELD_TYPE_BOOL;
    if (v instanceof Long || v instanceof Integer || v instanceof Short || v instanceof Byte) {
      return FIELD_TYPE_INT;
    }
    if (v instanceof Double || v instanceof Float) return FIELD_TYPE_DOUBLE;
    if (v instanceof Iterable || (v != null && v.getClass().isArray())) return FIELD_TYPE_ARRAY;
    return FIELD_TYPE_STRING;
  }
}
