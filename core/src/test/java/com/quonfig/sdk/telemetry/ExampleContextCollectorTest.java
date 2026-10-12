package com.quonfig.sdk.telemetry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.quonfig.sdk.eval.ContextSet;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
  void modeShapesOnlyDisablesExampleCollector() {
    ExampleContextCollector c = new ExampleContextCollector(ContextUploadMode.SHAPES_ONLY);
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

  @Test
  void fullSeenMapOfFreshKeysDoesNotScanPerPush() {
    // qfg-y8je.9: once the rate-limit map is at the cap with keys younger than the TTL, pushing an
    // unseen key must be O(1), not a full scan of the map on the evaluation hot path.
    ExampleContextCollector c =
        new ExampleContextCollector(
            ContextUploadMode.PERIODIC_EXAMPLE, Integer.MAX_VALUE, 60L * 60L * 1000L);
    for (int i = 0; i < ExampleContextCollector.SEEN_CAP; i++) {
      c.push(new ContextSet().withNamedContext("user", Map.of("key", "fill-" + i)));
    }
    long before = c.pruneScanned;
    int m = 1_000;
    for (int i = 0; i < m; i++) {
      c.push(new ContextSet().withNamedContext("user", Map.of("key", "new-" + i)));
    }
    long scanned = c.pruneScanned - before;
    assertTrue(scanned <= 2L * m, "scanned " + scanned + " entries for " + m + " pushes");

    // Drop-newest when full: none of the new keys were recorded.
    Map<String, Object> event = c.drain();
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> examples =
        (List<Map<String, Object>>)
            ((Map<String, Object>) event.get("exampleContexts")).get("examples");
    assertEquals(ExampleContextCollector.SEEN_CAP, examples.size());
  }

  @Test
  void expiredEntriesAreEvictedSoNewKeysRecordAgain() throws InterruptedException {
    ExampleContextCollector c =
        new ExampleContextCollector(ContextUploadMode.PERIODIC_EXAMPLE, Integer.MAX_VALUE, 50L);
    for (int i = 0; i < ExampleContextCollector.SEEN_CAP; i++) {
      c.push(new ContextSet().withNamedContext("user", Map.of("key", "fill-" + i)));
    }
    assertNotNull(c.drain());
    Thread.sleep(120);
    c.push(new ContextSet().withNamedContext("user", Map.of("key", "late")));
    Map<String, Object> event = c.drain();
    assertNotNull(event);
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> examples =
        (List<Map<String, Object>>)
            ((Map<String, Object>) event.get("exampleContexts")).get("examples");
    assertEquals(1, examples.size());
  }

  @Test
  void reSeenKeyAfterTtlIsRecordedAgainAndStaysBounded() throws InterruptedException {
    ExampleContextCollector c =
        new ExampleContextCollector(ContextUploadMode.PERIODIC_EXAMPLE, 10_000, 50L);
    c.push(new ContextSet().withNamedContext("user", Map.of("key", "abc")));
    assertNotNull(c.drain());
    Thread.sleep(120);
    c.push(new ContextSet().withNamedContext("user", Map.of("key", "abc")));
    assertNotNull(c.drain());
  }

  // ----- qfg-goi1.2.38: the recorded example is a JSON-safe copy taken at push time -----

  @SuppressWarnings("unchecked")
  private static Map<String, Object> onlyValues(Map<String, Object> event) {
    List<Map<String, Object>> examples =
        (List<Map<String, Object>>)
            ((Map<String, Object>) event.get("exampleContexts")).get("examples");
    assertEquals(1, examples.size());
    List<Map<String, Object>> contexts =
        (List<Map<String, Object>>)
            ((Map<String, Object>) examples.get(0).get("contextSet")).get("contexts");
    return (Map<String, Object>) contexts.get(0).get("values");
  }

  @Test
  void nestedValuesAreCopiedAtPushTime() throws Exception {
    ExampleContextCollector c = new ExampleContextCollector(ContextUploadMode.PERIODIC_EXAMPLE);
    Map<String, Object> nested = new HashMap<>();
    nested.put("tier", "gold");
    List<Object> tags = new ArrayList<>(List.of("a"));
    nested.put("tags", tags);
    Map<String, Object> user = new HashMap<>();
    user.put("key", "u-1");
    user.put("meta", nested);
    c.push(new ContextSet().withNamedContext("user", user));

    // The caller keeps mutating its own objects after the evaluation.
    nested.put("tier", "changed");
    nested.put("added", 1);
    tags.add("b");

    Map<String, Object> event = c.drain();
    @SuppressWarnings("unchecked")
    Map<String, Object> meta = (Map<String, Object>) onlyValues(event).get("meta");
    assertEquals("gold", meta.get("tier"));
    assertFalse(meta.containsKey("added"));
    assertEquals(List.of("a"), meta.get("tags"));
    new ObjectMapper().writeValueAsString(event);
  }

  @Test
  void valueTypesRenderToStringsAndTheExampleIsKept() throws Exception {
    ExampleContextCollector c = new ExampleContextCollector(ContextUploadMode.PERIODIC_EXAMPLE);
    UUID id = UUID.fromString("0b7e1c38-3f0a-4c3e-9a51-6d1b8a7f2c11");
    Instant at = Instant.parse("2026-10-12T10:00:00Z");
    Map<String, Object> user = new LinkedHashMap<>();
    user.put("key", id);
    user.put("signedUpAt", at);
    user.put("tags", List.of("x", "y"));
    user.put("scores", new int[] {1, 2});
    user.put("handle", new Object()); // an arbitrary object: dropped, the example stays
    c.push(new ContextSet().withNamedContext("user", user));

    Map<String, Object> event = c.drain();
    assertNotNull(event);
    Map<String, Object> values = onlyValues(event);
    assertEquals(id.toString(), values.get("key"));
    assertEquals("2026-10-12T10:00:00Z", values.get("signedUpAt"));
    assertEquals(List.of("x", "y"), values.get("tags"));
    assertEquals(List.of(1, 2), values.get("scores"));
    assertFalse(values.containsKey("handle"));
    // A plain ObjectMapper (no JSR-310 module) must serialize the window.
    String json = new ObjectMapper().writeValueAsString(event);
    assertTrue(json.contains("2026-10-12T10:00:00Z"), json);
  }

  @Test
  void cyclicAndOverDeepValuesAreCutNotOverflowed() throws Exception {
    ExampleContextCollector c = new ExampleContextCollector(ContextUploadMode.PERIODIC_EXAMPLE);
    Map<String, Object> loop = new HashMap<>();
    loop.put("name", "self");
    loop.put("self", loop);
    List<Object> deep = new ArrayList<>();
    List<Object> cur = deep;
    for (int i = 0; i < 10_000; i++) {
      List<Object> next = new ArrayList<>();
      cur.add(next);
      cur = next;
    }
    Map<String, Object> user = new HashMap<>();
    user.put("key", "u-1");
    user.put("loop", loop);
    user.put("deep", deep);
    c.push(new ContextSet().withNamedContext("user", user));

    Map<String, Object> event = c.drain();
    @SuppressWarnings("unchecked")
    Map<String, Object> copied = (Map<String, Object>) onlyValues(event).get("loop");
    assertEquals("self", copied.get("name"));
    assertFalse(copied.containsKey("self"));
    new ObjectMapper().writeValueAsString(event);
  }
}
