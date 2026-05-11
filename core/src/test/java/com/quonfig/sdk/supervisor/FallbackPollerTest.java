package com.quonfig.sdk.supervisor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * Tier 1 unit tests for the {@link FallbackPoller} (qfg-47c2.21 / Layer 2 parity with sdk-go's
 * {@code fallback_poller.go}). Behavior contract — the poller:
 *
 * <ul>
 *   <li>is idle while SSE is connected,
 *   <li>on SSE disconnect, starts a {@link FallbackPoller.Builder#threshold threshold} timer
 *       (cross- SDK default 120s),
 *   <li>if SSE reconnects before the threshold elapses the timer is cancelled and no fetch happens,
 *   <li>if the threshold elapses while still disconnected the poller engages: fires an immediate
 *       fetch and then ticks at {@link FallbackPoller.Builder#interval interval},
 *   <li>on SSE reconnect (or stop()) the poller disengages and goes back to idle.
 * </ul>
 *
 * <p>These tests use sub-millisecond threshold/interval bounds so the suite completes well under a
 * second; the actual 120s/60s defaults are validated in the cross-SDK chaos harness (scenarios
 * 05/06).
 */
class FallbackPollerTest {

  private static void waitFor(
      Duration timeout, java.util.function.BooleanSupplier predicate, String msg) {
    long deadlineNanos = System.nanoTime() + timeout.toNanos();
    while (System.nanoTime() < deadlineNanos) {
      if (predicate.getAsBoolean()) return;
      try {
        Thread.sleep(2);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        fail("interrupted");
      }
    }
    fail("waitFor timed out: " + msg);
  }

  // Test 1 — Poller stays idle while SSE is connected.
  @Test
  void idleWhileConnected() throws InterruptedException {
    AtomicInteger fetches = new AtomicInteger();
    FallbackPoller p =
        FallbackPoller.builder()
            .interval(Duration.ofMillis(10))
            .threshold(Duration.ofMillis(10))
            .fetch(fetches::incrementAndGet)
            .build();
    Supervisor s = supervise(p);
    try {
      p.setSseConnected(true);
      Thread.sleep(60);
      assertEquals(0, fetches.get(), "expected 0 fetches while connected");
      assertFalse(p.active(), "expected active=false while connected");
    } finally {
      s.stop();
    }
  }

  // Test 2 — After threshold elapses while disconnected, poller engages and fetches.
  @Test
  void engagesAfterThreshold() {
    AtomicInteger fetches = new AtomicInteger();
    AtomicInteger engageCount = new AtomicInteger();
    FallbackPoller p =
        FallbackPoller.builder()
            .interval(Duration.ofMillis(10))
            .threshold(Duration.ofMillis(20))
            .fetch(fetches::incrementAndGet)
            .onEngage(engageCount::incrementAndGet)
            .build();
    Supervisor s = supervise(p);
    try {
      p.setSseConnected(false);
      waitFor(
          Duration.ofSeconds(1),
          () -> fetches.get() >= 2,
          "poller never engaged and fetched after threshold");
      assertTrue(p.active(), "expected active=true while engaged");
      assertEquals(1, engageCount.get(), "expected exactly 1 engage callback");
    } finally {
      s.stop();
    }
  }

  // Test 3 — Reconnect before threshold elapses cancels engagement.
  @Test
  void reconnectBeforeThresholdCancelsEngagement() throws InterruptedException {
    AtomicInteger fetches = new AtomicInteger();
    FallbackPoller p =
        FallbackPoller.builder()
            .interval(Duration.ofMillis(5))
            .threshold(Duration.ofMillis(100))
            .fetch(fetches::incrementAndGet)
            .build();
    Supervisor s = supervise(p);
    try {
      p.setSseConnected(false);
      Thread.sleep(20); // well below 100ms threshold
      p.setSseConnected(true);
      Thread.sleep(150); // past original threshold
      assertEquals(0, fetches.get(), "expected 0 fetches when reconnect beats threshold");
      assertFalse(p.active(), "poller should never have engaged");
    } finally {
      s.stop();
    }
  }

  // Test 4 — Reconnect after engagement disengages and stops fetches.
  @Test
  void reconnectAfterEngagementDisengages() throws InterruptedException {
    AtomicInteger fetches = new AtomicInteger();
    AtomicInteger disengageCount = new AtomicInteger();
    FallbackPoller p =
        FallbackPoller.builder()
            .interval(Duration.ofMillis(5))
            .threshold(Duration.ofMillis(5))
            .fetch(fetches::incrementAndGet)
            .onDisengage(disengageCount::incrementAndGet)
            .build();
    Supervisor s = supervise(p);
    try {
      p.setSseConnected(false);
      waitFor(Duration.ofMillis(500), p::active, "poller never engaged");
      int atFromEngage = fetches.get();
      p.setSseConnected(true);
      waitFor(Duration.ofMillis(500), () -> !p.active(), "poller never disengaged after reconnect");
      assertEquals(1, disengageCount.get(), "expected exactly 1 disengage callback");
      Thread.sleep(50);
      // Allow one in-flight tick to race with disengage; anything beyond means we didn't stop.
      assertTrue(
          fetches.get() <= atFromEngage + 1,
          "fetches kept growing after disengage: had " + atFromEngage + ", now " + fetches.get());
    } finally {
      s.stop();
    }
  }

  // Test 5 — Fetch RuntimeException must not crash the poller; ticks keep firing.
  @Test
  void survivesFetchErrors() {
    AtomicInteger fetches = new AtomicInteger();
    FallbackPoller p =
        FallbackPoller.builder()
            .interval(Duration.ofMillis(5))
            .threshold(Duration.ofMillis(5))
            .fetch(
                () -> {
                  fetches.incrementAndGet();
                  throw new RuntimeException("simulated");
                })
            .build();
    Supervisor s = supervise(p);
    try {
      p.setSseConnected(false);
      waitFor(
          Duration.ofMillis(500), () -> fetches.get() >= 3, "poller stopped fetching after error");
    } finally {
      s.stop();
    }
  }

  private static Supervisor supervise(FallbackPoller p) {
    Supervisor s =
        Supervisor.builder()
            .initialDelay(Duration.ofMillis(1))
            .maxDelay(Duration.ofMillis(5))
            .workers(List.of(new Supervisor.WorkerSpec("2", p.worker())))
            .build();
    s.start();
    return s;
  }
}
