package com.quonfig.sdk.telemetry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.quonfig.sdk.eval.ContextSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ContextShapeCollectorTest {

  @Test
  void recordsEachNamedContextWithFieldTypeCodes() {
    ContextShapeCollector c = new ContextShapeCollector(ContextUploadMode.SHAPES_ONLY);

    Map<String, Object> userProps = new HashMap<>();
    userProps.put("plan", "pro");
    userProps.put("age", 38);
    userProps.put("verified", true);
    userProps.put("tags", List.of("a", "b"));
    userProps.put("score", 3.14);

    ContextSet ctx = new ContextSet().withNamedContext("user", userProps);
    c.push(ctx);

    Map<String, Object> event = c.drain();
    assertNotNull(event);
    @SuppressWarnings("unchecked")
    Map<String, Object> shapes = (Map<String, Object>) event.get("contextShapes");
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> shapeList = (List<Map<String, Object>>) shapes.get("shapes");
    assertEquals(1, shapeList.size());
    assertEquals("user", shapeList.get(0).get("name"));
    @SuppressWarnings("unchecked")
    Map<String, Number> fieldTypes = (Map<String, Number>) shapeList.get(0).get("fieldTypes");
    assertEquals(2, fieldTypes.get("plan").intValue()); // string
    assertEquals(1, fieldTypes.get("age").intValue()); // int
    assertEquals(5, fieldTypes.get("verified").intValue()); // bool
    assertEquals(10, fieldTypes.get("tags").intValue()); // string list
    assertEquals(4, fieldTypes.get("score").intValue()); // double
  }

  @Test
  void mergesPropertiesAcrossPushesForSameNamedContext() {
    ContextShapeCollector c = new ContextShapeCollector(ContextUploadMode.SHAPES_ONLY);

    c.push(new ContextSet().withNamedContext("user", Map.of("plan", "pro")));
    c.push(new ContextSet().withNamedContext("user", Map.of("age", 38)));

    Map<String, Object> event = c.drain();
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> shapeList =
        (List<Map<String, Object>>)
            ((Map<String, Object>) event.get("contextShapes")).get("shapes");
    @SuppressWarnings("unchecked")
    Map<String, Number> fieldTypes = (Map<String, Number>) shapeList.get(0).get("fieldTypes");
    assertEquals(2, fieldTypes.size());
    assertEquals(2, fieldTypes.get("plan").intValue());
    assertEquals(1, fieldTypes.get("age").intValue());
  }

  @Test
  void modeNoneDisablesPush() {
    ContextShapeCollector c = new ContextShapeCollector(ContextUploadMode.NONE);
    c.push(new ContextSet().withNamedContext("user", Map.of("plan", "pro")));
    assertNull(c.drain());
  }

  @Test
  void modePeriodicExampleAlsoEnabled() {
    ContextShapeCollector c = new ContextShapeCollector(ContextUploadMode.PERIODIC_EXAMPLE);
    c.push(new ContextSet().withNamedContext("user", Map.of("plan", "pro")));
    assertNotNull(c.drain());
  }

  @Test
  void drainResetsState() {
    ContextShapeCollector c = new ContextShapeCollector(ContextUploadMode.SHAPES_ONLY);
    c.push(new ContextSet().withNamedContext("user", Map.of("plan", "pro")));
    assertNotNull(c.drain());
    assertNull(c.drain());
  }
}
