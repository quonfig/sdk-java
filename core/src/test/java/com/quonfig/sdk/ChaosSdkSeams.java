package com.quonfig.sdk;

import java.time.Duration;
import java.util.function.BiFunction;

/**
 * TEST-ONLY bridge from the chaos harness ({@code com.quonfig.sdk.chaos}) to the SDK's
 * package-private test seams (qfg-6vno.3). It lives in test sources, so it never ships: the seams
 * stay package-private in the main jar and no customer can call them. Every value the chaos probe
 * asserts on comes through here from the SDK itself, never from the probe's own bookkeeping.
 */
public final class ChaosSdkSeams {
  private ChaosSdkSeams() {}

  /** Installs {@link Options.Builder#waitHookForTest} (maps each background wait). */
  public static Options.Builder waitHook(
      Options.Builder b, BiFunction<String, Duration, Duration> hook) {
    return b.waitHookForTest(hook);
  }

  /** {@code quonfig_sdk_worker_restart_total{layer}} as the SDK counts it ("1" or "2"). */
  public static long workerRestartTotal(Quonfig client, String layer) {
    return client.workerRestartTotalForTest(layer);
  }

  /** {@code quonfig_sse_connect_attempts_total}: every SSE dial, failed ones included. */
  public static long sseConnectAttempts(Quonfig client) {
    return client.sseConnectAttemptsForTest();
  }

  /** Whether the client's Layer 2 fallback poller is engaged right now. */
  public static boolean fallbackPollerActive(Quonfig client) {
    return client.fallbackPollerActiveForTest();
  }
}
