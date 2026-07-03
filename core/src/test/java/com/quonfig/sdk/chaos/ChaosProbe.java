package com.quonfig.sdk.chaos;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

/**
 * Test-only probe over an sdk-java {@code Quonfig} client. Derives the observation surface the
 * chaos scenarios reference ({@code connectionState}, {@code worker_restart_total}, {@code
 * lastSuccessfulRefresh}, {@code fallbackPollerActive}) from existing SDK callbacks. The probe does
 * NOT add metrics to the SDK itself — the missing surface area is the *point* of this harness.
 * Scenarios that depend on signals sdk-java doesn't expose today are expected to fail (red
 * baseline).
 */
final class ChaosProbe {

  /** Connection state vocabulary from supervisor-test-contract.md. */
  enum State {
    INITIALIZING,
    CONNECTED,
    RECONNECTING,
    FALLING_BACK,
    DISCONNECTED;

    String text() {
      switch (this) {
        case INITIALIZING:
          return "initializing";
        case CONNECTED:
          return "connected";
        case RECONNECTING:
          return "reconnecting";
        case FALLING_BACK:
          return "falling_back";
        case DISCONNECTED:
        default:
          return "disconnected";
      }
    }
  }

  private final Object lock = new Object();
  private State state = State.INITIALIZING;
  private long lastRefreshMs; // wall-clock millis; 0 == never
  private long connectAttempts;
  private long restartLayer1;
  private long restartLayer2;
  private boolean fallbackActive;
  private final AtomicBoolean processCrashed = new AtomicBoolean(false);
  private final List<LogLine> logs = new ArrayList<>();

  String connectionState() {
    synchronized (lock) {
      return state.text();
    }
  }

  boolean fallbackPollerActive() {
    synchronized (lock) {
      return fallbackActive;
    }
  }

  long lastSuccessfulRefreshMs() {
    synchronized (lock) {
      return lastRefreshMs;
    }
  }

  boolean processStillAlive() {
    return !processCrashed.get();
  }

  void markProcessCrashed() {
    processCrashed.set(true);
  }

  double sdkMetric(String name, String layer) {
    synchronized (lock) {
      switch (name) {
        case "quonfig_sdk_worker_restart_total":
          if ("1".equals(layer)) return restartLayer1;
          if ("2".equals(layer)) return restartLayer2;
          return restartLayer1 + restartLayer2;
        case "quonfig_sse_connect_attempts_total":
          return connectAttempts;
        default:
          return 0;
      }
    }
  }

  /** Hook for the SDK's onSseConnectionStateChange callback. */
  void onSseState(boolean connected) {
    synchronized (lock) {
      if (connected) {
        // Transitioning into CONNECTED counts as a successful attempt.
        connectAttempts++;
        state = State.CONNECTED;
      } else {
        // Map false → reconnecting (sdk-java's SSE loop always retries until stop()). A drop
        // from CONNECTED counts as a Layer 1 worker restart: the read loop ended unexpectedly
        // and the reconnect path is starting a fresh attempt. Initial-connect failures
        // (INITIALIZING → RECONNECTING) are not counted as restarts.
        if (state == State.CONNECTED) {
          restartLayer1++;
        }
        // Preserve FALLING_BACK if the Layer 2 poller has already engaged. The SSE callback
        // can fire repeatedly during a long partition (retry/backoff), and we don't want each
        // attempt to flicker us between FALLING_BACK and RECONNECTING.
        if (!fallbackActive) {
          state = State.RECONNECTING;
        }
      }
    }
  }

  /**
   * Hook for the SDK's onFallbackPollerStateChange callback (qfg-47c2.21). Drives the probe's
   * {@code fallbackPollerActive} and FALLING_BACK state — both of which scenarios 5 and 6 assert.
   */
  void onFallbackState(boolean engaged) {
    synchronized (lock) {
      fallbackActive = engaged;
      if (engaged) {
        state = State.FALLING_BACK;
      } else if (state == State.FALLING_BACK) {
        // Disengaged but SSE hasn't fired a connect edge yet — treat as reconnecting until
        // the next SSE event lands.
        state = State.RECONNECTING;
      }
    }
  }

  /** Hook for the SDK's onConfigUpdate callback. */
  void onConfigUpdate() {
    synchronized (lock) {
      lastRefreshMs = System.currentTimeMillis();
    }
  }

  /**
   * Records a Layer 1 worker-restart-equivalent WITHOUT a connection-state edge. sdk-java catches
   * user-callback throws at the SDK boundary ({@code fireConfigUpdate} logs at ERROR and continues,
   * qfg-srj8) rather than crashing and restarting a worker, so the chaos metric counts each caught
   * throw as a recovery — scenario 10's {@code worker_restart_total >= 1} observes "the SDK
   * survived the throw", exactly like sdk-net's probe (qfg-41nh.15). Healthy scenarios never log
   * the signal, so 01/11's {@code == 0} asserts are unaffected.
   */
  void incRestartLayer1() {
    synchronized (lock) {
      restartLayer1++;
    }
  }

  void log(String level, String message) {
    synchronized (lock) {
      logs.add(new LogLine(level, message));
    }
  }

  int sdkLogMatches(String level, Pattern re) {
    synchronized (lock) {
      int n = 0;
      for (LogLine l : logs) {
        if (level != null && !level.isEmpty() && !level.equalsIgnoreCase(l.level)) continue;
        if (re == null || re.matcher(l.message).find()) n++;
      }
      return n;
    }
  }

  private static final class LogLine {
    final String level;
    final String message;

    LogLine(String level, String message) {
      this.level = level;
      this.message = message;
    }
  }
}
