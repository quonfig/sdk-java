package com.quonfig.sdk.chaos;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.quonfig.sdk.chaos.ExpectationTimeline.Sample;
import com.quonfig.sdk.chaos.ExpectationTimeline.State;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * Pins the chaos timeline rules (integration-test-data chaos/README.md "Timeline rules",
 * qfg-goi1.7) on the pure per-tick step shared by both sdk-java chaos loops. Runs in the default
 * test task.
 */
class ExpectationTimelineTest {

  private static ChaosScenario.Expectation exp(int afterMs, int withinMs, int holdMs) {
    ChaosScenario.Expectation e = new ChaosScenario.Expectation();
    e.afterMs = afterMs;
    e.withinMs = withinMs;
    e.mustHoldForMs = holdMs;
    e.assertExpr = "x";
    return e;
  }

  @Test
  void afterMs_trueAtTickZero_doesNotPassBeforeAfterMs() {
    State s = new State(exp(1000, 5000, 0));
    AtomicInteger evals = new AtomicInteger();
    for (long t = 0; t < 1000; t += 250) {
      ExpectationTimeline.step(
          s,
          t,
          () -> {
            evals.incrementAndGet();
            return Sample.TRUE;
          });
      assertFalse(s.passed, "must not PASS at elapsed " + t + " < after_ms 1000");
      assertFalse(s.terminal(), "must not be terminal before after_ms");
      assertEquals(-1, s.heldSinceMs, "no hold may start before after_ms");
    }
    assertEquals(0, evals.get(), "the assertion must not be evaluated before after_ms");

    ExpectationTimeline.step(s, 1000, () -> Sample.TRUE);
    assertTrue(s.passed, "true at after_ms with no hold passes");
    assertEquals(1000, s.hitAtMs);
  }

  @Test
  void afterMs_holdStartsAtOrAfterAfterMs_andFailsPastWithin() {
    // 05 exp[2] shape scaled down: after 1000, hold 2000, within 3500.
    State s = new State(exp(1000, 3500, 2000));
    for (long t = 0; t <= 2500; t += 250) ExpectationTimeline.step(s, t, () -> Sample.TRUE);
    assertFalse(s.passed, "hold started at 1000 has only run 1500ms");
    assertEquals(1000, s.heldSinceMs);
    ExpectationTimeline.step(s, 2750, () -> Sample.FALSE);
    assertEquals(-1, s.heldSinceMs, "a false sample resets the hold");
    for (long t = 3000; t <= 3500; t += 250) ExpectationTimeline.step(s, t, () -> Sample.TRUE);
    assertFalse(s.terminal(), "at within_ms exactly it is not yet failed");
    ExpectationTimeline.step(s, 3750, () -> Sample.TRUE);
    assertTrue(s.failed, "hold incomplete past within_ms must FAIL");
  }

  @Test
  void afterMs_holdCompletes_passesWithHitAtAfterMs() {
    State s = new State(exp(1000, 5000, 2000));
    for (long t = 0; t <= 3000 && !s.terminal(); t += 250)
      ExpectationTimeline.step(s, t, () -> Sample.TRUE);
    assertTrue(s.passed);
    assertEquals(1000, s.hitAtMs, "hold started at after_ms");
  }

  @Test
  void serverMetricWithAfterMs_stillReportsSkipped() {
    State s = new State(exp(1000, 5000, 0));
    ExpectationTimeline.step(s, 0, () -> Sample.SKIPPED);
    assertFalse(s.terminal());
    ExpectationTimeline.step(s, 1000, () -> Sample.SKIPPED);
    assertTrue(s.skipped, "a whole-expression server_metric row is SKIPPED, not PASS/FAIL");
    assertFalse(s.passed);
    assertFalse(s.failed);
  }

  @Test
  void noAfterMs_latchesAtFirstTrueAsBefore() {
    State s = new State(exp(0, 5000, 0));
    ExpectationTimeline.step(s, 0, () -> Sample.TRUE);
    assertTrue(s.passed);
    assertEquals(0, s.hitAtMs);
  }
}
