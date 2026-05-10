package com.quonfig.sdk;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.quonfig.sdk.eval.ConfigRow;
import com.quonfig.sdk.eval.ConfigType;
import com.quonfig.sdk.eval.Criterion;
import com.quonfig.sdk.eval.Environment;
import com.quonfig.sdk.eval.ProvidedValue;
import com.quonfig.sdk.eval.Rule;
import com.quonfig.sdk.eval.RuleSet;
import com.quonfig.sdk.eval.Value;
import com.quonfig.sdk.eval.ValueType;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Reads a workspace directory tree (configs/, feature-flags/, segments/, log-levels/, schemas/)
 * into a list of {@link ConfigRow} objects suitable for an in-memory store.
 *
 * <p>Mirrors {@code sdk-go/workspace_loader.go}: walks each subdirectory, parses every {@code
 * *.json} file, skips dotfiles. Feature flags always materialize with {@code sendToClientSdk=true}.
 */
public final class DatadirLoader {

  private static final List<String> SUBDIRS =
      List.of("configs", "feature-flags", "segments", "log-levels", "schemas");
  private static final ObjectMapper MAPPER = new ObjectMapper();

  private DatadirLoader() {}

  public static List<ConfigRow> load(Path datadir) {
    List<ConfigRow> out = new ArrayList<>();
    for (String subdir : SUBDIRS) {
      Path p = datadir.resolve(subdir);
      if (!Files.isDirectory(p)) continue;
      try (Stream<Path> walk = Files.walk(p)) {
        walk.filter(Files::isRegularFile)
            .filter(f -> f.getFileName().toString().endsWith(".json"))
            .filter(f -> !f.getFileName().toString().startsWith("."))
            .forEach(
                f -> {
                  try {
                    out.add(parseConfigFile(f));
                  } catch (IOException e) {
                    throw new UncheckedIOException("parse " + f, e);
                  }
                });
      } catch (IOException e) {
        throw new UncheckedIOException("walk " + p, e);
      }
    }
    return out;
  }

  private static ConfigRow parseConfigFile(Path file) throws IOException {
    JsonNode root = MAPPER.readTree(Files.readAllBytes(file));
    String id = root.hasNonNull("id") ? root.get("id").asText() : root.get("key").asText();
    String key = root.get("key").asText();
    ConfigType type = parseConfigType(root.get("type").asText());
    ValueType valueType = parseValueType(root.get("valueType").asText());
    boolean sendToClientSdk =
        type == ConfigType.FEATURE_FLAG
            || (root.hasNonNull("sendToClientSdk") && root.get("sendToClientSdk").asBoolean());

    RuleSet defaultRules = parseRuleSet(root.path("default"), valueType);
    List<Environment> envs = new ArrayList<>();
    JsonNode envsNode = root.path("environments");
    if (envsNode.isArray()) {
      for (JsonNode env : envsNode) {
        if (!env.hasNonNull("id")) continue;
        envs.add(parseEnvironment(env, valueType));
      }
    }
    return new ConfigRow(id, key, type, valueType, sendToClientSdk, defaultRules, envs);
  }

  private static Environment parseEnvironment(JsonNode env, ValueType valueType) {
    String id = env.get("id").asText();
    List<Rule> rules = new ArrayList<>();
    JsonNode rulesNode = env.path("rules");
    if (rulesNode.isArray()) {
      for (JsonNode r : rulesNode) {
        rules.add(parseRule(r, valueType));
      }
    }
    return new Environment(id, rules);
  }

  private static RuleSet parseRuleSet(JsonNode node, ValueType valueType) {
    List<Rule> rules = new ArrayList<>();
    JsonNode rulesNode = node.path("rules");
    if (rulesNode.isArray()) {
      for (JsonNode r : rulesNode) {
        rules.add(parseRule(r, valueType));
      }
    }
    return new RuleSet(rules);
  }

  private static Rule parseRule(JsonNode r, ValueType valueType) {
    List<Criterion> criteria = new ArrayList<>();
    JsonNode crit = r.path("criteria");
    if (crit.isArray()) {
      for (JsonNode c : crit) {
        criteria.add(parseCriterion(c));
      }
    }
    Value value = parseValue(r.path("value"), valueType);
    return new Rule(criteria, value);
  }

  private static Criterion parseCriterion(JsonNode c) {
    String prop = c.hasNonNull("propertyName") ? c.get("propertyName").asText() : null;
    String op = c.get("operator").asText();
    Value match = c.hasNonNull("valueToMatch") ? parseValue(c.get("valueToMatch"), null) : null;
    return new Criterion(prop, op, match);
  }

  private static Value parseValue(JsonNode v, ValueType configValueType) {
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

  private static ConfigType parseConfigType(String s) {
    if (s == null) return ConfigType.CONFIG;
    switch (s) {
      case "feature_flag":
        return ConfigType.FEATURE_FLAG;
      case "segment":
        return ConfigType.SEGMENT;
      case "log_level":
        return ConfigType.LOG_LEVEL;
      case "schema":
        return ConfigType.SCHEMA;
      case "config":
      default:
        return ConfigType.CONFIG;
    }
  }

  private static ValueType parseValueType(String s) {
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
