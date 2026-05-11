package com.quonfig.sdk.supervisor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.quonfig.sdk.ConnectionState;
import com.quonfig.sdk.supervisor.Supervisor.WorkerSpec;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * Tier 1 unit tests for the {@link Supervisor} abstraction (qfg-47c2.18 / B3 contract; Java idiom
 * of sdk-go's {@code supervisor_test.go}). The Supervisor owns one or more long-running workers
 * and:
 *
 * <ul>
 *   <li>recovers from RuntimeException / Error at the worker boundary,
 *   <li>restarts the worker with exponential backoff (500ms → 30s cap),
 *   <li>bumps {@code quonfig_sdk_worker_restart_total{layer="<n>"}} per restart,
 *   <li>stops cleanly within 5s on {@link Supervisor#stop()},
 *   <li>exposes {@link Supervisor#connectionState()} and {@link Supervisor#lastSuccessfulRefresh()}
 *       for callers.
 * </ul>
 *
 * <p>These tests inject sub-millisecond backoff bounds so the suite completes in well under a
 * second; the actual 500ms→30s exponential formula is verified by {@link
 * #exponentialBackoffFormula()}.
 */
class SupervisorTest {

  // Test 1 — Supervisor restarts a worker that throws within 1000ms.
  @Test
  void restartsThrownWorker() throws InterruptedException {
    AtomicInteger calls = new AtomicInteger();
    CountDownLatch restarted = new CountDownLatch(1);

    WorkerSpec w =
        new WorkerSpec(
            "1",
            ctx -> {
              int n = calls.incrementAndGet();
              if (n == 1) {
                throw new RuntimeException("boom");
              }
              restarted.countDown();
              ctx.awaitStop();
            });

    Supervisor s =
        Supervisor.builder()
            .initialDelay(Duration.ofMillis(1))
            .maxDelay(Duration.ofMillis(5))
            .workers(List.of(w))
            .build();
    s.start();
    try {
      if (!restarted.await(1, TimeUnit.SECONDS)) {
        fail("supervisor did not restart thrown worker within 1s; calls=" + calls.get());
      }
    } finally {
      s.stop();
    }
  }

  // Test 2 — Exponential backoff (500ms → 1s → 2s → ... → 30s cap).
  @Test
  void exponentialBackoffFormula() {
    Supervisor s =
        Supervisor.builder()
            .initialDelay(Duration.ofMillis(500))
            .maxDelay(Duration.ofSeconds(30))
            .build();
    Duration[] want = {
      Duration.ofMillis(500),
      Duration.ofSeconds(1),
      Duration.ofSeconds(2),
      Duration.ofSeconds(4),
      Duration.ofSeconds(8),
      Duration.ofSeconds(16),
      Duration.ofSeconds(30), // 32s exceeds cap → 30s
      Duration.ofSeconds(30), // cap holds
      Duration.ofSeconds(30),
    };
    for (int i = 0; i < want.length; i++) {
      Duration got = s.backoffFor(i);
      assertEquals(want[i], got, "backoffFor(" + i + ")");
    }
  }

  // Test 3 — Clean shutdown within 5s on stop().
  @Test
  void stopJoinsWithinDeadline() throws InterruptedException {
    AtomicBoolean running = new AtomicBoolean();
    WorkerSpec w =
        new WorkerSpec(
            "1",
            ctx -> {
              running.set(true);
              ctx.awaitStop();
            });

    Supervisor s =
        Supervisor.builder()
            .initialDelay(Duration.ofMillis(1))
            .maxDelay(Duration.ofMillis(5))
            .workers(List.of(w))
            .build();
    s.start();

    long deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(500);
    while (!running.get() && System.nanoTime() < deadlineNanos) {
      Thread.sleep(1);
    }
    if (!running.get()) {
      fail("worker never started");
    }

    long stopStart = System.nanoTime();
    CountDownLatch stopDone = new CountDownLatch(1);
    Thread stopper =
        new Thread(
            () -> {
              s.stop();
              stopDone.countDown();
            },
            "test-supervisor-stopper");
    stopper.start();

    if (!stopDone.await(5500, TimeUnit.MILLISECONDS)) {
      fail("stop() did not return within 5.5s");
    }
    long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - stopStart);
    assertTrue(elapsedMs <= 5000, "stop took " + elapsedMs + "ms, want <=5000");
  }

  // Test 4 — worker_restart_total{layer="1"} increments per restart.
  @Test
  void workerRestartTotalIncrements() throws InterruptedException {
    AtomicInteger calls = new AtomicInteger();
    final int wantRestarts = 3;
    CountDownLatch done = new CountDownLatch(1);

    WorkerSpec w =
        new WorkerSpec(
            "1",
            ctx -> {
              int n = calls.incrementAndGet();
              if (n <= wantRestarts) {
                throw new RuntimeException("boom");
              }
              done.countDown();
              ctx.awaitStop();
            });

    Supervisor s =
        Supervisor.builder()
            .initialDelay(Duration.ofMillis(1))
            .maxDelay(Duration.ofMillis(5))
            .workers(List.of(w))
            .build();
    s.start();
    try {
      if (!done.await(2, TimeUnit.SECONDS)) {
        fail("worker never reached steady state; calls=" + calls.get());
      }
      assertEquals(wantRestarts, s.workerRestartTotal("1"));
      assertEquals(0, s.workerRestartTotal("2"), "untouched layer should be zero");
    } finally {
      s.stop();
    }
  }

  // Test 5 — Panic-in-callback recovery. The Java equivalent: a worker throws an unchecked
  // exception that simulates a user OnEnvelope handler exploding past the in-worker guard. The
  // supervisor must catch it as a last line of defense, restart the worker, and bump the restart
  // counter.
  @Test
  void recoversFromOnEnvelopeStyleThrow() throws InterruptedException {
    AtomicInteger phase = new AtomicInteger(0); // 0 = pre-throw, 1 = post-restart
    CountDownLatch resumed = new CountDownLatch(1);

    WorkerSpec w =
        new WorkerSpec(
            "1",
            ctx -> {
              if (phase.get() == 0) {
                phase.set(1);
                // Simulate the worst case: the OnEnvelope callback throws and the in-worker
                // try/catch around it is absent or bypassed. The supervisor catches it.
                throw new RuntimeException("user OnEnvelope handler exploded");
              }
              resumed.countDown();
              ctx.awaitStop();
            });

    Supervisor s =
        Supervisor.builder()
            .initialDelay(Duration.ofMillis(1))
            .maxDelay(Duration.ofMillis(5))
            .workers(List.of(w))
            .build();
    s.start();
    try {
      if (!resumed.await(1, TimeUnit.SECONDS)) {
        fail("supervisor did not recover from OnEnvelope-style throw; phase=" + phase.get());
      }
      assertTrue(s.workerRestartTotal("1") >= 1);
    } finally {
      s.stop();
    }
  }

  // Test 6 — connectionState() transitions through documented values as the worker reports state
  // changes; lastSuccessfulRefresh() advances when the worker records an install. The supervisor
  // itself does not own the transport — it provides the surface that workers report into.
  @Test
  void connectionStateAndLastRefresh() throws InterruptedException {
    CountDownLatch gate = new CountDownLatch(1);
    WorkerSpec w =
        new WorkerSpec(
            "1",
            ctx -> {
              // Worker contract: report state transitions through the supervisor. The supervisor
              // is the source of truth for connectionState().
              gate.await();
              ctx.awaitStop();
            });

    Supervisor s =
        Supervisor.builder()
            .initialDelay(Duration.ofMillis(1))
            .maxDelay(Duration.ofMillis(5))
            .workers(List.of(w))
            .build();

    // Before start: initializing, null refresh.
    assertEquals(ConnectionState.INITIALIZING, s.connectionState());
    assertNull(s.lastSuccessfulRefresh(), "pre-start lastSuccessfulRefresh should be null");

    s.start();
    try {
      // Drive state transitions the way a worker would.
      s.setConnectionState(ConnectionState.CONNECTED);
      assertEquals(ConnectionState.CONNECTED, s.connectionState());

      Instant before = Instant.now();
      s.recordSuccessfulRefresh();
      Instant got = s.lastSuccessfulRefresh();
      assertNotNull(got);
      assertFalse(got.isBefore(before), "lastSuccessfulRefresh = " + got + ", want >= " + before);
      assertFalse(
          got.isAfter(Instant.now().plusSeconds(1)),
          "lastSuccessfulRefresh = " + got + ", want <= now+1s");

      s.setConnectionState(ConnectionState.DISCONNECTED);
      assertEquals(ConnectionState.DISCONNECTED, s.connectionState());

      s.setConnectionState(ConnectionState.FALLING_BACK);
      assertEquals(ConnectionState.FALLING_BACK, s.connectionState());
    } finally {
      gate.countDown();
      s.stop();
    }
  }

  // Sanity check — clean exit (worker returns after stop) does not count as a restart.
  @Test
  void cleanShutdownDoesNotCountAsRestart() {
    WorkerSpec w = new WorkerSpec("1", ctx -> ctx.awaitStop());

    Supervisor s =
        Supervisor.builder()
            .initialDelay(Duration.ofMillis(1))
            .maxDelay(Duration.ofMillis(5))
            .workers(List.of(w))
            .build();
    s.start();
    try {
      Thread.sleep(20);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    s.stop();
    assertEquals(0, s.workerRestartTotal("1"));
  }
}
