package com.quonfig.sdk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.quonfig.sdk.eval.ConfigRow;
import com.quonfig.sdk.eval.Environment;
import org.junit.jupiter.api.Test;

/**
 * Regression for qfg-xpln.1: api-delivery's HTTP {@code /api/v2/configs} and SSE serialize each
 * config row scoped to ONE environment using a SINGULAR {@code environment} object (not the plural
 * {@code environments} array used by datadir/workspace files). {@link
 * DatadirLoader#parseConfigNode} is the shared HTTP+SSE+datadir parser, so it must read the
 * singular form too — otherwise every per-environment override is silently dropped in delivery
 * mode.
 */
class DatadirLoaderSingularEnvironmentTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Test
  void parseConfigNode_readsSingularEnvironmentObject() throws Exception {
    // Exactly the delivery shape: SINGULAR `environment` object, no plural `environments` array.
    String json =
        "{"
            + "\"id\":\"c1\",\"key\":\"my.flag\",\"type\":\"feature_flag\",\"valueType\":\"bool\","
            + "\"default\":{\"rules\":[{\"criteria\":[],"
            + "\"value\":{\"type\":\"bool\",\"value\":true}}]},"
            + "\"environment\":{\"id\":\"development\",\"rules\":[{\"criteria\":[],"
            + "\"value\":{\"type\":\"bool\",\"value\":false}}]}"
            + "}";
    JsonNode node = MAPPER.readTree(json);

    ConfigRow row = DatadirLoader.parseConfigNode(node);

    assertEquals(
        1, row.environments().size(), "singular environment object must populate env list");
    Environment env = row.environments().get(0);
    assertEquals("development", env.id());
    assertNotNull(env.rules());
    assertEquals(1, env.rules().size(), "environment rule must be parsed");
    assertEquals(Boolean.FALSE, env.rules().get(0).value().value());
  }
}
