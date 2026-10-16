package com.quonfig.sdk.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * The one typed decoder for a {@code {type, value, confidential?, decryptWith?}} value blob. Used
 * for top-level rule values by {@link com.quonfig.sdk.DatadirLoader} and for weighted rollout
 * variants by {@link Murmur3WeightedValueResolver}, so a variant keeps its json, duration,
 * log_level or provided type exactly like a top-level value (qfg-lrlh; sdk-go parses both through
 * {@code Value.UnmarshalJSON} in evalcore/types.go). Internal: not part of the public API.
 */
public final class ValueDecoder {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private ValueDecoder() {}

  /**
   * Decodes an already-parsed blob (the plain Java maps/lists a weighted_values payload carries).
   * Returns {@code null} when {@code raw} is not an object.
   */
  public static Value decode(Object raw) {
    if (!(raw instanceof Map)) return null;
    return decode(MAPPER.valueToTree(raw), null);
  }

  public static Value decode(JsonNode v, ValueType configValueType) {
    if (v == null || v.isMissingNode() || v.isNull()) {
      return new Value(configValueType != null ? configValueType : ValueType.STRING, null);
    }
    String typeStr = v.hasNonNull("type") ? v.get("type").asText() : null;
    ValueType vt = typeStr != null ? parseValueType(typeStr) : configValueType;
    if (vt == null) vt = ValueType.STRING;

    JsonNode raw = v.path("value");
    Object payload;
    switch (vt) {
      case BOOL:
        payload = raw.asBoolean();
        break;
      case INT:
        payload = raw.asLong();
        break;
      case DOUBLE:
        payload = raw.asDouble();
        break;
      case STRING:
      case LOG_LEVEL:
      case DURATION:
        payload = raw.asText();
        break;
      case STRING_LIST:
        payload = jsonToStringList(raw);
        break;
      case JSON:
        payload = jsonToObject(raw);
        break;
      case PROVIDED:
        payload = parseProvided(raw);
        break;
      case WEIGHTED_VALUES:
      case SCHEMA:
      default:
        payload = jsonToObject(raw);
        break;
    }

    boolean confidential = v.hasNonNull("confidential") && v.get("confidential").asBoolean();
    String decryptWith = v.hasNonNull("decryptWith") ? v.get("decryptWith").asText() : null;
    return new Value(vt, payload, confidential, decryptWith);
  }

  private static ProvidedValue parseProvided(JsonNode raw) {
    String source = raw.hasNonNull("source") ? raw.get("source").asText() : null;
    String lookup = raw.hasNonNull("lookup") ? raw.get("lookup").asText() : null;
    return new ProvidedValue(source, lookup);
  }

  private static List<String> jsonToStringList(JsonNode raw) {
    List<String> out = new ArrayList<>();
    if (raw.isArray()) {
      for (JsonNode item : raw) out.add(item.asText());
    }
    return out;
  }

  private static Object jsonToObject(JsonNode raw) {
    if (raw.isNull() || raw.isMissingNode()) return null;
    if (raw.isBoolean()) return raw.asBoolean();
    if (raw.isInt() || raw.isLong()) return raw.asLong();
    if (raw.isFloatingPointNumber()) return raw.asDouble();
    if (raw.isTextual()) return raw.asText();
    if (raw.isArray()) {
      List<Object> list = new ArrayList<>();
      for (JsonNode item : raw) list.add(jsonToObject(item));
      return list;
    }
    if (raw.isObject()) {
      java.util.LinkedHashMap<String, Object> map = new java.util.LinkedHashMap<>();
      Iterator<Map.Entry<String, JsonNode>> it = raw.fields();
      while (it.hasNext()) {
        Map.Entry<String, JsonNode> e = it.next();
        map.put(e.getKey(), jsonToObject(e.getValue()));
      }
      return map;
    }
    return null;
  }

  public static ValueType parseValueType(String s) {
    if (s == null) return ValueType.STRING;
    // Workspace JSON uses snake_case (`string_list`, `weighted_values`); legacy Prefab payloads
    // and a handful of round-tripped configs use camelCase. Accept both so the same SDK code
    // serves the integration-test-data corpus and customer-authored datadirs alike.
    switch (s) {
      case "bool":
        return ValueType.BOOL;
      case "int":
        return ValueType.INT;
      case "double":
        return ValueType.DOUBLE;
      case "string":
        return ValueType.STRING;
      case "stringList":
      case "string_list":
        return ValueType.STRING_LIST;
      case "logLevel":
      case "log_level":
        return ValueType.LOG_LEVEL;
      case "duration":
        return ValueType.DURATION;
      case "json":
      // IN_INT_RANGE criteria carry {"type": "int_range", "value": {"start", "end"}}; keep the
      // object as a native Map so Operators.extractIntRange can read its bounds.
      case "int_range":
      case "intRange":
        return ValueType.JSON;
      case "weightedValues":
      case "weighted_values":
        return ValueType.WEIGHTED_VALUES;
      case "schema":
        return ValueType.SCHEMA;
      case "provided":
        return ValueType.PROVIDED;
      default:
        return ValueType.STRING;
    }
  }
}
