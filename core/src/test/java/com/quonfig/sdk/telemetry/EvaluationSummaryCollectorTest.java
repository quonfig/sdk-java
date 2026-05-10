package com.quonfig.sdk.telemetry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class EvaluationSummaryCollectorTest {

  private static EvaluationStat stat(
      String configId,
      String configKey,
      String configType,
      int ruleIndex,
      int weightedValueIndex,
      Object selectedValue,
      String reportableValue,
      int reason) {
    return new EvaluationStat(
        configId,
        configKey,
        configType,
        ruleIndex,
        weightedValueIndex,
        selectedValue,
        reportableValue,
        reason);
  }

  @Test
  void aggregatesIdenticalEvaluationsIntoOneCounterWithCount() {
    EvaluationSummaryCollector c = new EvaluationSummaryCollector(true);

    for (int i = 0; i < 100; i++) {
      c.push(stat("cfg-1", "greeting", "CONFIG", 0, -1, "hello", null, 1));
    }

    Map<String, Object> event = c.drain();
    assertNotNull(event);
    @SuppressWarnings("unchecked")
    Map<String, Object> summaries = (Map<String, Object>) event.get("summaries");
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> rows = (List<Map<String, Object>>) summaries.get("summaries");
    assertEquals(1, rows.size());
    Map<String, Object> row = rows.get(0);
    assertEquals("greeting", row.get("key"));
    assertEquals("CONFIG", row.get("type"));
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> counters = (List<Map<String, Object>>) row.get("counters");
    assertEquals(1, counters.size());
    assertEquals(100L, ((Number) counters.get(0).get("count")).longValue());
    assertEquals(1, ((Number) counters.get(0).get("reason")).intValue());
  }

  @Test
  void distinctRuleIndexesProduceDistinctCounters() {
    EvaluationSummaryCollector c = new EvaluationSummaryCollector(true);

    c.push(stat("cfg-1", "greeting", "CONFIG", 0, -1, "hello", null, 1));
    c.push(stat("cfg-1", "greeting", "CONFIG", 1, -1, "hi", null, 2));

    Map<String, Object> event = c.drain();
    @SuppressWarnings("unchecked")
    Map<String, Object> summaries = (Map<String, Object>) event.get("summaries");
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> rows = (List<Map<String, Object>>) summaries.get("summaries");
    assertEquals(1, rows.size()); // same key, but two counters
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> counters = (List<Map<String, Object>>) rows.get(0).get("counters");
    assertEquals(2, counters.size());
  }

  @Test
  void wrapsBooleanSelectedValueAsBoolKey() {
    EvaluationSummaryCollector c = new EvaluationSummaryCollector(true);
    c.push(stat("flag-1", "flag", "FEATURE_FLAG", 0, -1, Boolean.TRUE, null, 1));

    Map<String, Object> event = c.drain();
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> rows =
        (List<Map<String, Object>>) ((Map<String, Object>) event.get("summaries")).get("summaries");
    @SuppressWarnings("unchecked")
    Map<String, Object> selectedValue =
        (Map<String, Object>)
            ((List<Map<String, Object>>) rows.get(0).get("counters")).get(0).get("selectedValue");
    assertTrue(selectedValue.containsKey("bool"));
    assertEquals(Boolean.TRUE, selectedValue.get("bool"));
  }

  @Test
  void wrapsLongAsIntAndDoubleAsDouble() {
    EvaluationSummaryCollector c = new EvaluationSummaryCollector(true);
    c.push(stat("c-int", "int", "CONFIG", 0, -1, 7L, null, 1));
    c.push(stat("c-dbl", "dbl", "CONFIG", 0, -1, 3.14, null, 1));

    Map<String, Object> event = c.drain();
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> rows =
        (List<Map<String, Object>>) ((Map<String, Object>) event.get("summaries")).get("summaries");

    boolean sawInt = false;
    boolean sawDouble = false;
    for (Map<String, Object> row : rows) {
      @SuppressWarnings("unchecked")
      List<Map<String, Object>> counters = (List<Map<String, Object>>) row.get("counters");
      for (Map<String, Object> ctr : counters) {
        @SuppressWarnings("unchecked")
        Map<String, Object> sv = (Map<String, Object>) ctr.get("selectedValue");
        if (sv.containsKey("int")) sawInt = true;
        if (sv.containsKey("double")) sawDouble = true;
      }
    }
    assertTrue(sawInt);
    assertTrue(sawDouble);
  }

  @Test
  void redactedValueAlwaysWrappedAsStringNotOriginalType() {
    EvaluationSummaryCollector c = new EvaluationSummaryCollector(true);
    // Confidential int value — wire shape must be {string: "*****abc12"}
    c.push(stat("cfg-secret", "secret", "CONFIG", 0, -1, 42L, "*****abc12", 1));

    Map<String, Object> event = c.drain();
    @SuppressWarnings("unchecked")
    Map<String, Object> selectedValue =
        (Map<String, Object>)
            ((List<Map<String, Object>>)
                    ((List<Map<String, Object>>)
                            ((Map<String, Object>) event.get("summaries")).get("summaries"))
                        .get(0)
                        .get("counters"))
                .get(0)
                .get("selectedValue");
    assertFalse(selectedValue.containsKey("int"));
    assertEquals("*****abc12", selectedValue.get("string"));
  }

  @Test
  void includesWeightedValueIndexOnlyWhenNonNegative() {
    EvaluationSummaryCollector c = new EvaluationSummaryCollector(true);
    c.push(stat("cfg-1", "k", "CONFIG", 0, 2, "b", null, 3));
    c.push(stat("cfg-2", "k2", "CONFIG", 0, -1, "x", null, 1));

    Map<String, Object> event = c.drain();
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> rows =
        (List<Map<String, Object>>) ((Map<String, Object>) event.get("summaries")).get("summaries");

    Map<String, Object> withWeighted = null;
    Map<String, Object> withoutWeighted = null;
    for (Map<String, Object> row : rows) {
      @SuppressWarnings("unchecked")
      Map<String, Object> ctr = ((List<Map<String, Object>>) row.get("counters")).get(0);
      if (((Number) ctr.get("conditionalValueIndex")).intValue() == 0
          && ctr.get("configId").equals("cfg-1")) {
        withWeighted = ctr;
      }
      if (ctr.get("configId").equals("cfg-2")) withoutWeighted = ctr;
    }
    assertNotNull(withWeighted);
    assertNotNull(withoutWeighted);
    assertEquals(2, ((Number) withWeighted.get("weightedValueIndex")).intValue());
    assertNull(withoutWeighted.get("weightedValueIndex"));
  }

  @Test
  void drainReturnsNullWhenEmpty() {
    EvaluationSummaryCollector c = new EvaluationSummaryCollector(true);
    assertNull(c.drain());
  }

  @Test
  void drainResetsState() {
    EvaluationSummaryCollector c = new EvaluationSummaryCollector(true);
    c.push(stat("c", "k", "CONFIG", 0, -1, "v", null, 1));
    assertNotNull(c.drain());
    assertNull(c.drain());
  }

  @Test
  void disabledCollectorIgnoresPush() {
    EvaluationSummaryCollector c = new EvaluationSummaryCollector(false);
    c.push(stat("c", "k", "CONFIG", 0, -1, "v", null, 1));
    assertNull(c.drain());
  }

  @Test
  void summariesEnvelopeHasStartAndEndTimestamps() {
    EvaluationSummaryCollector c = new EvaluationSummaryCollector(true);
    long before = System.currentTimeMillis();
    c.push(stat("c", "k", "CONFIG", 0, -1, "v", null, 1));
    Map<String, Object> event = c.drain();
    long after = System.currentTimeMillis();
    @SuppressWarnings("unchecked")
    Map<String, Object> summaries = (Map<String, Object>) event.get("summaries");
    long start = ((Number) summaries.get("start")).longValue();
    long end = ((Number) summaries.get("end")).longValue();
    assertTrue(start >= before);
    assertTrue(end <= after);
    assertTrue(end >= start);
  }
}
