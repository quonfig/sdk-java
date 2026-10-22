package com.quonfig.sdk.chaos;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.quonfig.sdk.ConnectionState;
import java.time.Instant;
import java.util.List;
import java.util.regex.Pattern;
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

    FakeView view = new FakeView();
    probe.bindView(view);
    ExpressionEvaluator.Result notYet = eval.evaluate(expr);
    assertFalse(notYet.passed, "initializing client must fail the observable leaf");

    view.state = ConnectionState.CONNECTED;
    ExpressionEvaluator.Result ok = eval.evaluate(expr);
    assertTrue(ok.passed, "connected client should pass: " + ok.reason);
    assertFalse(ok.skipped, "an observable leaf means the expression is not skipped");
    assertTrue(ok.reason.contains("SKIPPED"), "skipped leaf should be visible: " + ok.reason);
    assertEquals(List.of(SERVER_LAG), ok.skippedLeaves);
  }

  @Test
  void unknownSdkMetricFailsLoudly() {
    ChaosProbe probe = new ChaosProbe();
    probe.bindView(new FakeView());
    IllegalArgumentException ex =
        assertThrows(
            IllegalArgumentException.class, () -> probe.sdkMetric("quonfig_no_such_metric", null));
    assertTrue(ex.getMessage().contains("quonfig_no_such_metric"), ex.getMessage());
  }

  @Test
  void unknownSdkMetricInAnExpressionDoesNotPass() {
    ChaosProbe probe = new ChaosProbe();
    probe.bindView(new FakeView());
    ExpressionEvaluator eval = new ExpressionEvaluator(probe);
    assertThrows(
        IllegalArgumentException.class,
        () -> eval.evaluate("client.sdkMetric('quonfig_no_such_metric') == 0"));
  }

  @Test
  void sdkLogWithoutTheIFlagIsCaseSensitive() {
    // f01/f04 (ITD v2026.10.21.1): /\[quonfig\] init/ carries no i flag.
    ChaosProbe probe = new ChaosProbe();
    ExpressionEvaluator eval = new ExpressionEvaluator(probe);
    String expr = "client.sdkLog('warn', /\\[quonfig\\] init/) == 0";
    ExpressionEvaluator.Result clean = eval.evaluate(expr);
    assertTrue(clean.passed, "no init warn logged yet: " + clean.reason);
    probe.log("warn", "[QUONFIG] INIT shouting");
    assertTrue(eval.evaluate(expr).passed, "no i flag: a different-case line must not match");
    probe.log("warn", "[quonfig] init: still waiting for config");
    assertFalse(eval.evaluate(expr).passed, "a matching warn line must count");
    assertTrue(eval.evaluate("client.sdkLog('error', /\\[quonfig\\] init/) == 0").passed);
    assertEquals(2, probe.sdkLogMatches("warn", Pattern.compile("init", Pattern.CASE_INSENSITIVE)));
  }

  @Test
  void clientLeavesFailWhileNoClientObjectExists() {
    // The probe never answers for an SDK it does not have (00: the init call is still pending).
    ExpressionEvaluator eval = new ExpressionEvaluator(new ChaosProbe());
    for (String expr :
        List.of(
            "client.connectionState() == 'initializing'",
            "client.ready() == false",
            "client.sdkMetric('quonfig_sdk_worker_restart_total', layer='1') == 0",
            "client.getString('k', 'D') == 'D'")) {
      ExpressionEvaluator.Result r = eval.evaluate(expr);
      assertFalse(r.passed, expr + " must fail with no client: " + r.reason);
      assertTrue(r.reason.contains("no client object"), r.reason);
    }
  }

  @Test
  void initFailureVocabulary() {
    ChaosProbe probe = new ChaosProbe();
    ExpressionEvaluator eval = new ExpressionEvaluator(probe);
    assertFalse(
        eval.evaluate("client.initState() == 'pending'").passed,
        "initState outside an init_failure scenario must fail, not pass");
    probe.setInitState("pending");
    assertTrue(eval.evaluate("client.initState() == 'pending'").passed);
    probe.setInitState("returned");
    FakeView view = new FakeView();
    probe.bindView(view);
    String expr =
        "client.initState() == 'returned' AND client.getString('config.with.only.default.env.row',"
            + " 'CODE-DEFAULT') == 'CODE-DEFAULT'";
    assertTrue(eval.evaluate(expr).passed, eval.evaluate(expr).reason);
    view.strings = java.util.Map.of("config.with.only.default.env.row", "hello from no env row");
    assertFalse(eval.evaluate(expr).passed, "the live value must replace the default");
    view.ready = true;
    String heal =
        "client.ready() == true AND client.readyEventCount() >= 1 AND"
            + " client.getString('config.with.only.default.env.row', 'CODE-DEFAULT') =="
            + " 'hello from no env row'";
    assertFalse(eval.evaluate(heal).passed, "no ready event yet");
    probe.onReadyEvent();
    assertTrue(eval.evaluate(heal).passed, eval.evaluate(heal).reason);
  }

  /** A hand-set SDK view: lets the evaluator tests drive each value without a live client. */
  static final class FakeView implements ChaosProbe.SdkView {
    ConnectionState state = ConnectionState.INITIALIZING;
    boolean ready;
    java.util.Map<String, String> strings = java.util.Map.of();

    @Override
    public ConnectionState connectionState() {
      return state;
    }

    @Override
    public boolean fallbackPollerActive() {
      return false;
    }

    @Override
    public long workerRestartTotal(String layer) {
      return 0;
    }

    @Override
    public long sseConnectAttempts() {
      return 0;
    }

    @Override
    public Instant lastSuccessfulRefresh() {
      return null;
    }

    @Override
    public boolean ready() {
      return ready;
    }

    @Override
    public String getString(String key, String def) {
      return strings.getOrDefault(key, def);
    }
  }
}
