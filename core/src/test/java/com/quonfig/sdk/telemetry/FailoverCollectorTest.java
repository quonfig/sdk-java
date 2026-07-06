package com.quonfig.sdk.telemetry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Unit coverage for {@link FailoverCollector}, mirroring sdk-go's failover_aggregator_test.go. */
class FailoverCollectorTest {

  @Test
  void drainReturnsNullWhenNoActivity() {
    assertNull(
        new FailoverCollector().drain(), "a healthy client with zero counters emits nothing");
  }

  @Test
  void recordsFoldIntoTheExactWireShape() {
    FailoverCollector c = new FailoverCollector();
    c.recordHedgeFired();
    c.recordGuardRejected();
    c.recordGuardRejected();
    c.recordResolvedFrom(0); // primary
    c.recordResolvedFrom(1); // secondary
    c.recordResolvedFrom(2); // any index > 0 counts as secondary
    c.recordResolvedFrom(-1); // SSE/datadir — ignored

    long before = System.currentTimeMillis();
    Map<String, Object> event = c.drain();
    long after = System.currentTimeMillis();

    // The event is wrapped in a single "failover" key (the api-telemetry event discriminator).
    assertEquals(Set.of("failover"), event.keySet());
    @SuppressWarnings("unchecked")
    Map<String, Object> f = (Map<String, Object>) event.get("failover");

    // Exact camelCase field set the api-telemetry Zod schema / ClickHouse MV parse.
    assertEquals(
        Set.of(
            "start",
            "end",
            "hedgeFired",
            "guardRejected",
            "resolvedFromPrimary",
            "resolvedFromSecondary",
            "resolvedFromLkg"),
        f.keySet());

    assertEquals(1L, ((Number) f.get("hedgeFired")).longValue());
    assertEquals(2L, ((Number) f.get("guardRejected")).longValue());
    assertEquals(1L, ((Number) f.get("resolvedFromPrimary")).longValue());
    assertEquals(2L, ((Number) f.get("resolvedFromSecondary")).longValue());
    assertEquals(0L, ((Number) f.get("resolvedFromLkg")).longValue());

    long start = ((Number) f.get("start")).longValue();
    long end = ((Number) f.get("end")).longValue();
    assertTrue(start >= before - 1000 && start <= end, "start stamped on first record");
    assertTrue(end <= after, "end stamped at drain");
  }

  @Test
  void negativeSourceIndexAloneEmitsNothing() {
    FailoverCollector c = new FailoverCollector();
    c.recordResolvedFrom(-1);
    assertNull(c.drain(), "an ignored SSE/datadir install must not open a window");
  }

  @Test
  void drainResetsSoASecondFlushIsEmpty() {
    FailoverCollector c = new FailoverCollector();
    c.recordHedgeFired();
    assertEquals(1L, ((Number) failover(c.drain()).get("hedgeFired")).longValue());
    assertNull(c.drain(), "counters reset after drain");
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> failover(Map<String, Object> event) {
    return (Map<String, Object>) event.get("failover");
  }
}
