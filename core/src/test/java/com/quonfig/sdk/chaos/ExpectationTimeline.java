package com.quonfig.sdk.chaos;

import java.util.function.Supplier;

/**
 * Pure per-tick step for one chaos expectation, shared by the default suite ({@link ChaosTest}) and
 * the failover/ordering suite ({@link FailoverChaosTest}). Implements the "Timeline rules (every
 * harness)" in {@code integration-test-data/chaos/README.md} (qfg-goi1.7): extracted from the two
 * poll loops so the timing rules are unit-testable without the rig.
 *
 * <p>All times are milliseconds elapsed since the scenario's t=0 baseline.
 */
final class ExpectationTimeline {

  private ExpectationTimeline() {}

  /** One sample of an expectation's assertion. */
  enum Sample {
    TRUE,
    FALSE,
    /** The whole expression is not observable from this rig (e.g. {@code server_metric}). */
    SKIPPED
  }

  /** Mutable per-expectation timeline state. */
  static final class State {
    final ChaosScenario.Expectation exp;

    /** Elapsed ms at which the current hold started; -1 when no hold is running. */
    long heldSinceMs = -1;

    /** Elapsed ms at which the hold that produced PASS started; -1 when never hit. */
    long hitAtMs = -1;

    /** Elapsed ms at which the expectation PASSED; -1 until then. */
    long passedAtMs = -1;

    /** True once the assertion has been sampled at least once. */
    boolean evaluated;

    boolean passed;
    boolean failed;
    boolean skipped;

    State(ChaosScenario.Expectation exp) {
      this.exp = exp;
    }

    boolean terminal() {
      return passed || failed || skipped;
    }
  }

  /**
   * Advances {@code s} by one tick at {@code elapsedMs}. {@code sample} is invoked at most once,
   * and only when the expectation is actually evaluated this tick.
   *
   * <ul>
   *   <li>Rule 1: before {@code after_ms} the expectation is not evaluated at all: no PASS, no hold
   *       start, not terminal. {@code within_ms} is unchanged.
   *   <li>The first true sample starts the hold, a false sample resets it; PASS once the hold
   *       completes, FAIL past {@code within_ms} (rule 3 holds are plain long holds).
   *   <li>A whole-expression skip (e.g. {@code server_metric}) is SKIPPED (shape-based).
   * </ul>
   */
  static void step(State s, long elapsedMs, Supplier<Sample> sample) {
    if (s.terminal()) return;
    if (elapsedMs < s.exp.afterMs) return;
    Sample r = sample.get();
    s.evaluated = true;
    if (r == Sample.SKIPPED) {
      s.skipped = true;
      return;
    }
    if (r == Sample.TRUE) {
      if (s.heldSinceMs < 0) {
        s.heldSinceMs = elapsedMs;
        s.hitAtMs = elapsedMs;
      }
      long holdFor = s.exp.mustHoldForMs;
      if (holdFor <= 0 || (elapsedMs - s.heldSinceMs) >= holdFor) {
        s.passed = true;
        s.passedAtMs = elapsedMs;
        return;
      }
    } else {
      s.heldSinceMs = -1;
    }
    if (elapsedMs > s.exp.withinMs) s.failed = true;
  }
}
