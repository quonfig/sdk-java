package com.quonfig.sdk;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.quonfig.sdk.eval.ConfigRow;
import com.quonfig.sdk.eval.ConfigType;
import com.quonfig.sdk.eval.Criterion;
import com.quonfig.sdk.eval.Environment;
import com.quonfig.sdk.eval.Rule;
import com.quonfig.sdk.eval.RuleSet;
import com.quonfig.sdk.eval.Value;
import com.quonfig.sdk.eval.ValueDecoder;
import com.quonfig.sdk.eval.ValueType;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Reads a workspace directory tree (configs/, feature-flags/, segments/, log-levels/) into a list
 * of {@link ConfigRow} objects suitable for an in-memory store.
 *
 * <p>Mirrors {@code api-delivery/internal/config/loader.go}: {@code schemas/} is intentionally
 * excluded — those files are raw JSON Schema documents, not configs, and SDKs do not consume them
 * (qfg-uzsl). Walks each subdirectory, parses every {@code *.json} file, skips dotfiles. Feature
 * flags always materialize with {@code sendToClientSdk=true}.
 */
public final class DatadirLoader {

  private static final List<String> SUBDIRS =
      List.of("configs", "feature-flags", "segments", "log-levels");
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

  /**
   * Fails datadir init when {@code environment} is not one of the environments declared in the
   * workspace's {@code quonfig.json}. A workspace whose manifest is absent or declares no
   * environments accepts any name (sdk-go {@code resolveWorkspaceEnvironment} parity).
   */
  static void requireKnownEnvironment(Path datadir, String environment) {
    List<String> known = declaredEnvironments(datadir.resolve("quonfig.json"));
    if (!known.isEmpty() && !known.contains(environment)) {
      throw new IllegalStateException(
          "environment \""
              + environment
              + "\" not found in workspace "
              + datadir
              + "; available environments: "
              + String.join(", ", known));
    }
  }

  private static List<String> declaredEnvironments(Path manifest) {
    if (!Files.isRegularFile(manifest)) return List.of();
    JsonNode envs;
    try {
      envs = MAPPER.readTree(Files.readAllBytes(manifest)).path("environments");
    } catch (IOException e) {
      throw new UncheckedIOException("parse " + manifest, e);
    }
    if (!envs.isArray()) return List.of();
    List<String> out = new ArrayList<>();
    for (JsonNode e : envs) {
      String name = e.isObject() ? e.path("id").asText("") : e.asText("");
      if (!name.trim().isEmpty()) out.add(name.trim());
    }
    return out;
  }

  private static ConfigRow parseConfigFile(Path file) throws IOException {
    JsonNode root = MAPPER.readTree(Files.readAllBytes(file));
    return parseConfigNode(root);
  }

  /**
   * Parses a single per-config JSON node into a {@link ConfigRow}. Same shape that {@link
   * com.quonfig.sdk.wire.ConfigEnvelope#configs()} carries from api-delivery, so this is the bridge
   * from an HTTP/SSE envelope into the in-memory store.
   */
  public static ConfigRow parseConfigNode(JsonNode root) {
    String key = requiredText(root, "key", null);
    String id = root.hasNonNull("id") ? root.get("id").asText() : key;
    ConfigType type = parseConfigType(requiredText(root, "type", key));
    ValueType valueType = ValueDecoder.parseValueType(requiredText(root, "valueType", key));
    boolean sendToClientSdk =
        type == ConfigType.FEATURE_FLAG
            || (root.hasNonNull("sendToClientSdk") && root.get("sendToClientSdk").asBoolean());

    RuleSet defaultRules = parseRuleSet(root.path("default"), valueType, key);
    List<Environment> envs = new ArrayList<>();
    JsonNode envsNode = root.path("environments");
    if (envsNode.isArray()) {
      for (JsonNode env : envsNode) {
        if (!env.hasNonNull("id")) continue;
        envs.add(parseEnvironment(env, valueType, key));
      }
    }
    // Delivery (HTTP /api/v2/configs + SSE) scopes each row to ONE environment and serializes it
    // as a SINGULAR `environment` object alongside `meta.environment`, whereas datadir/workspace
    // files carry the PLURAL `environments` array. parseConfigNode is the shared parser for both,
    // so read the singular form too — otherwise per-environment overrides are silently dropped in
    // delivery mode and the evaluator falls back to `default` (qfg-xpln.1). Mirrors sdk-go (single
    // `environment` wire field) and sdk-net's ConfigRowParser (commit 3d49607).
    JsonNode singleEnv = root.path("environment");
    if (singleEnv.isObject() && singleEnv.hasNonNull("id")) {
      envs.add(parseEnvironment(singleEnv, valueType, key));
    }
    return new ConfigRow(id, key, type, valueType, sendToClientSdk, defaultRules, envs);
  }

  /**
   * Reads a required string field, failing with an error that names the field and the config key
   * instead of a bare {@link NullPointerException}. Delivery installs skip such a row with a WARN
   * that carries this message (qfg-goi1.2.16); a datadir load or reload reports it as-is.
   */
  private static String requiredText(JsonNode node, String field, String key) {
    // Only an ABSENT field fails, exactly where the old unguarded get(...).asText() threw; an
    // explicit JSON null still reads as "null", as before.
    JsonNode v = node.get(field);
    if (v == null) {
      throw new IllegalArgumentException(
          key == null
              ? "config is missing required field \"" + field + "\""
              : "config \"" + key + "\" is missing required field \"" + field + "\"");
    }
    return v.asText();
  }

  private static Environment parseEnvironment(JsonNode env, ValueType valueType, String key) {
    String id = env.get("id").asText();
    List<Rule> rules = new ArrayList<>();
    JsonNode rulesNode = env.path("rules");
    if (rulesNode.isArray()) {
      for (JsonNode r : rulesNode) {
        rules.add(parseRule(r, valueType, key));
      }
    }
    return new Environment(id, rules);
  }

  private static RuleSet parseRuleSet(JsonNode node, ValueType valueType, String key) {
    List<Rule> rules = new ArrayList<>();
    JsonNode rulesNode = node.path("rules");
    if (rulesNode.isArray()) {
      for (JsonNode r : rulesNode) {
        rules.add(parseRule(r, valueType, key));
      }
    }
    return new RuleSet(rules);
  }

  private static Rule parseRule(JsonNode r, ValueType valueType, String key) {
    List<Criterion> criteria = new ArrayList<>();
    JsonNode crit = r.path("criteria");
    if (crit.isArray()) {
      for (JsonNode c : crit) {
        criteria.add(parseCriterion(c, key));
      }
    }
    Value value = ValueDecoder.decode(r.path("value"), valueType);
    return new Rule(criteria, value);
  }

  private static Criterion parseCriterion(JsonNode c, String key) {
    String prop = c.hasNonNull("propertyName") ? c.get("propertyName").asText() : null;
    String op = requiredText(c, "operator", key);
    Value match =
        c.hasNonNull("valueToMatch") ? ValueDecoder.decode(c.get("valueToMatch"), null) : null;
    return new Criterion(prop, op, match);
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
}
