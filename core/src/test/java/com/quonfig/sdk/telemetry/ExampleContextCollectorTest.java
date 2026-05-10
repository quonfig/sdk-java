package com.quonfig.sdk.telemetry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.quonfig.sdk.eval.ContextSet;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ExampleContextCollectorTest {

  @Test
  void recordsContextWithKey() {
    ExampleContextCollector c = new ExampleContextCollector(ContextUploadMode.PERIODIC_EXAMPLE);

    ContextSet ctx =
        new ContextSet().withNamedContext("user", Map.of("key", "michael:1234", "plan", "pro"));
    c.push(ctx);

    Map<String, Object> event = c.drain();
    assertNotNull(event);
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> examples =
        (List<Map<String, Object>>)
            ((Map<String, Object>) event.get("exampleContexts")).get("examples");
    assertEquals(1, examples.size());
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> contexts =
        (List<Map<String, Object>>)
            ((Map<String, Object>) examples.get(0).get("contextSet")).get("contexts");
    assertEquals(1, contexts.size());
    assertEquals("user", contexts.get(0).get("type"));
    @SuppressWarnings("unchecked")
    Map<String, Object> values = (Map<String, Object>) contexts.get(0).get("values");
    assertEquals("michael:1234", values.get("key"));
    assertEquals("pro", values.get("plan"));
  }

  @Test
  void dedupesByGroupedKeyWithinRateLimit() {
    ExampleContextCollector c =
        new ExampleContextCollector(ContextUploadMode.PERIODIC_EXAMPLE, 10_000, 60_000L);

    c.push(new ContextSet().withNamedContext("user", Map.of("key", "abc", "plan", "pro")));
    c.push(new ContextSet().withNamedContext("user", Map.of("key", "abc", "plan", "free")));
    c.push(new ContextSet().withNamedContext("user", Map.of("key", "xyz")));

    Map<String, Object> event = c.drain();
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> examples =
        (List<Map<String, Object>>)
            ((Map<String, Object>) event.get("exampleContexts")).get("examples");
    assertEquals(2, examples.size()); // abc, xyz
  }

  @Test
  void skipsContextWithNoIdentifiers() {
    ExampleContextCollector c = new ExampleContextCollector(ContextUploadMode.PERIODIC_EXAMPLE);
    c.push(new ContextSet().withNamedContext("user", Map.of("plan", "pro")));
    assertNull(c.drain());
  }

  @Test
  void usesTrackingIdAsFallbackForGroupedKey() {
    ExampleContextCollector c = new ExampleContextCollector(ContextUploadMode.PERIODIC_EXAMPLE);

    c.push(new ContextSet().withNamedContext("user", Map.of("trackingId", "t-1", "plan", "pro")));
    Map<String, Object> event = c.drain();
    assertNotNull(event);
  }

  @Test
  void modeNoneDisablesPush() {
    ExampleContextCollector c = new ExampleContextCollector(ContextUploadMode.NONE);
    c.push(new ContextSet().withNamedContext("user", Map.of("key", "abc")));
    assertNull(c.drain());
  }

  @Test
  void modeShapesDisablesExampleCollector() {
    ExampleContextCollector c = new ExampleContextCollector(ContextUploadMode.SHAPES);
    c.push(new ContextSet().withNamedContext("user", Map.of("key", "abc")));
    assertNull(c.drain());
  }

  @Test
  void timestampIsRecentEpochMillis() {
    ExampleContextCollector c = new ExampleContextCollector(ContextUploadMode.PERIODIC_EXAMPLE);
    long before = System.currentTimeMillis();
    c.push(new ContextSet().withNamedContext("user", Map.of("key", "abc")));
    long after = System.currentTimeMillis();

    Map<String, Object> event = c.drain();
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> examples =
        (List<Map<String, Object>>)
            ((Map<String, Object>) event.get("exampleContexts")).get("examples");
    long ts = ((Number) examples.get(0).get("timestamp")).longValue();
    assertTrue(ts >= before && ts <= after);
  }

  @Test
  void drainResetsState() {
    ExampleContextCollector c = new ExampleContextCollector(ContextUploadMode.PERIODIC_EXAMPLE);
    c.push(new ContextSet().withNamedContext("user", Map.of("key", "abc")));
    assertNotNull(c.drain());
    assertNull(c.drain());
  }
}
