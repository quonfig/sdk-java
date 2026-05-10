package com.quonfig.sdk.wire;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Round-trip Jackson tests for {@link ConfigEnvelope} against the cross-SDK corpus in {@code
 * integration-test-data/data/integration-tests/configs/*.json}. Each file is itself a single config
 * payload; we wrap them all into one envelope, serialize, parse, and assert no information loss on
 * the per-config nodes.
 */
class ConfigEnvelopeRoundTripTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Test
  void integrationCorpus_roundTripsThroughJackson() throws Exception {
    Path corpus = locateCorpus();
    List<JsonNode> configs = new ArrayList<>();
    try (Stream<Path> walk = Files.walk(corpus)) {
      walk.filter(Files::isRegularFile)
          .filter(p -> p.getFileName().toString().endsWith(".json"))
          .filter(p -> !p.getFileName().toString().startsWith("."))
          .forEach(
              p -> {
                try {
                  configs.add(MAPPER.readTree(Files.readAllBytes(p)));
                } catch (Exception e) {
                  throw new RuntimeException("read " + p, e);
                }
              });
    }
    assertTrue(configs.size() >= 10, "corpus should not be empty: " + configs.size());

    Meta meta = new Meta("v1", "production", "ws-test");
    ConfigEnvelope envelope = new ConfigEnvelope(configs, meta);

    String json = MAPPER.writeValueAsString(envelope);
    ConfigEnvelope back = MAPPER.readValue(json, ConfigEnvelope.class);

    assertEquals(configs.size(), back.configs().size());
    assertEquals("v1", back.meta().version());
    assertEquals("production", back.meta().environment());
    assertEquals("ws-test", back.meta().workspaceId());

    // Information preservation: each per-config node compares semantically equal to the original.
    for (int i = 0; i < configs.size(); i++) {
      JsonNode original = configs.get(i);
      JsonNode actual = back.configs().get(i);
      assertEquals(
          original,
          actual,
          () ->
              "envelope round-trip lost info on config[" + original.path("key").asText("?") + "]");
    }
  }

  @Test
  void emptyEnvelope_roundTrips() throws Exception {
    ConfigEnvelope empty = new ConfigEnvelope(null, null);
    String json = MAPPER.writeValueAsString(empty);
    ConfigEnvelope back = MAPPER.readValue(json, ConfigEnvelope.class);
    assertNotNull(back.configs());
    assertTrue(back.configs().isEmpty());
  }

  @Test
  void metaWorkspaceId_omittedWhenNull() throws Exception {
    Meta m = new Meta("v1", "staging", null);
    ConfigEnvelope env = new ConfigEnvelope(List.of(), m);
    String json = MAPPER.writeValueAsString(env);
    JsonNode tree = MAPPER.readTree(json);
    JsonNode metaNode = tree.path("meta");
    assertEquals("v1", metaNode.path("version").asText());
    assertEquals("staging", metaNode.path("environment").asText());
    assertTrue(
        metaNode.path("workspaceId").isMissingNode(), "workspaceId should be omitted: " + json);
  }

  @Test
  void singleConfigJsonNode_unchangedAfterRoundTrip() throws Exception {
    ObjectNode cfg = JsonNodeFactory.instance.objectNode();
    cfg.put("id", "id-1");
    cfg.put("key", "my.key");
    cfg.put("type", "config");
    cfg.put("valueType", "string");
    cfg.put("sendToClientSdk", false);

    ObjectNode rule = JsonNodeFactory.instance.objectNode();
    rule.putArray("criteria");
    ObjectNode value = JsonNodeFactory.instance.objectNode();
    value.put("type", "string");
    value.put("value", "hello");
    rule.set("value", value);

    ObjectNode defaults = JsonNodeFactory.instance.objectNode();
    defaults.putArray("rules").add(rule);
    cfg.set("default", defaults);

    ConfigEnvelope env = new ConfigEnvelope(List.of(cfg), new Meta("v", "production", "w"));
    String json = MAPPER.writeValueAsString(env);
    ConfigEnvelope back = MAPPER.readValue(json, ConfigEnvelope.class);
    assertEquals(cfg, back.configs().get(0));
  }

  private static Path locateCorpus() {
    String userDir = System.getProperty("user.dir");
    Path candidate =
        Paths.get(userDir, "..", "integration-test-data", "data", "integration-tests", "configs")
            .normalize();
    if (Files.isDirectory(candidate)) return candidate;
    return Paths.get(userDir, "integration-test-data", "data", "integration-tests", "configs")
        .normalize();
  }
}
