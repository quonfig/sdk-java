package com.quonfig.sdk.chaos;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the chaos-harness expression evaluator and probe (qfg-goi1.1.6). These run in the
 * default {@code ./gradlew test} (no docker, no CHAOS_RUN): they pin the harness's own honesty,
 * i.e. that an expression the rig cannot observe is reported as SKIPPED with a reason instead of
 * silently evaluating against a stubbed 0, and that an unknown metric name fails loudly.
 */
class ExpressionEvaluatorTest {

  private static final String SERVER_LAG = "server_metric('quonfig_subscriber_lag_seconds') == 0";

  @Test
  void serverMetricIsExplicitlySkippedWithReason() {
    ExpressionEvaluator.Result r = new ExpressionEvaluator(new ChaosProbe()).evaluate(SERVER_LAG);
    assertTrue(r.skipped, "server_metric leaf must be reported as skipped");
    assertTrue(r.reason.startsWith("SKIPPED"), "reason should start with SKIPPED: " + r.reason);
    assertTrue(r.reason.contains("OTLP"), "reason should name the OTLP-only export: " + r.reason);
    assertTrue(
        r.reason.contains("qfg-47c2.19"), "reason should name the staging drill: " + r.reason);
    assertTrue(
        r.reason.contains("QuonfigSubscriberLagHigh"), "reason should name the alert: " + r.reason);
  }

  @Test
  void serverMetricIsNotEvaluatedAgainstAStubbedZero() {
    // Before qfg-goi1.1.6 the leaf compared against a hardcoded 0, so `== 5` failed and `== 0`
    // passed for reasons unrelated to the server. A skipped leaf must say SKIPPED either way.
    ExpressionEvaluator.Result r =
        new ExpressionEvaluator(new ChaosProbe())
            .evaluate("server_metric('quonfig_subscriber_lag_seconds') == 5");
    assertTrue(r.reason.startsWith("SKIPPED"), "reason should start with SKIPPED: " + r.reason);
    assertFalse(r.reason.contains("=0 "), "must not report a stubbed 0: " + r.reason);
  }

  @Test
  void compoundAndStillEnforcesTheObservableLeaf() {
    // Scenario 02 ANDs connectionState with server_metric. The skipped leaf is neutral; the
    // connectionState leaf must still decide the outcome.
    String expr = "client.connectionState() == 'connected' AND " + SERVER_LAG;
    ChaosProbe probe = new ChaosProbe();
    ExpressionEvaluator eval = new ExpressionEvaluator(probe);

    ExpressionEvaluator.Result notYet = eval.evaluate(expr);
    assertFalse(notYet.passed, "initializing client must fail the observable leaf");

    probe.onSseState(true);
    ExpressionEvaluator.Result ok = eval.evaluate(expr);
    assertTrue(ok.passed, "connected client should pass: " + ok.reason);
    assertFalse(ok.skipped, "an observable leaf means the expression is not skipped");
    assertTrue(ok.reason.contains("SKIPPED"), "skipped leaf should be visible: " + ok.reason);
    assertEquals(List.of(SERVER_LAG), ok.skippedLeaves);
  }

  @Test
  void unknownSdkMetricFailsLoudly() {
    ChaosProbe probe = new ChaosProbe();
    IllegalArgumentException ex =
        assertThrows(
            IllegalArgumentException.class, () -> probe.sdkMetric("quonfig_no_such_metric", null));
    assertTrue(ex.getMessage().contains("quonfig_no_such_metric"), ex.getMessage());
  }

  @Test
  void unknownSdkMetricInAnExpressionDoesNotPass() {
    ExpressionEvaluator eval = new ExpressionEvaluator(new ChaosProbe());
    assertThrows(
        IllegalArgumentException.class,
        () -> eval.evaluate("client.sdkMetric('quonfig_no_such_metric') == 0"));
  }
}
