package com.quonfig.sdk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.hash.Hashing;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class SmokeTest {

  @Test
  void buildToolchainIsJava17() {
    assertEquals("17", System.getProperty("java.specification.version"));
  }

  @Test
  void jacksonOnClasspath() throws Exception {
    JsonNode node = new ObjectMapper().readTree("{\"k\":1}");
    assertEquals(1, node.get("k").asInt());
  }

  @Test
  void guavaMurmur3OnClasspath() {
    long h = Hashing.murmur3_128().hashBytes("quonfig".getBytes()).asLong();
    assertTrue(h != 0L);
  }

  @Test
  void slf4jOnClasspath() {
    Logger log = LoggerFactory.getLogger(SmokeTest.class);
    assertNotNull(log);
  }
}
