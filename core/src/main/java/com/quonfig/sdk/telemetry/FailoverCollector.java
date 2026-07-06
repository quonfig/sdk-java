package com.quonfig.sdk.telemetry;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Accumulates failover-behavior counters over a flush window: how many times the config-fetch hedge
 * fired its secondary leg, how many installs the reject-older ordering guard dropped, and which
 * upstream leg resolved each successful HTTP install. Every counter is additive and carries no user
 * data.
 *
 * <p>Mirrors the sdk-go {@code FailoverAggregator} (qfg-41nh.18). The collector is independently
 * thread-safe (a single monitor) and is written directly from the failover call sites rather than
 * through a queue — the call rate is per-config-refresh, not per-evaluation, so a plain
 * synchronized block has negligible overhead. {@link #drain()} returns {@code null} when every
 * counter is zero, so a healthy steady-state client emits no failover event at all.
 */
public final class FailoverCollector {

  private long hedgeFired;
  private long guardRejected;
  private long resolvedFromPrimary;
  private long resolvedFromSecondary;
  // Reserved for last-known-good resolution; backends emit 0 (kept on the wire for forward compat).
  private long resolvedFromLkg;
  private Long startAt;

  /**
   * Counts one config-fetch cycle whose hedge fired the secondary leg (the primary was slow or
   * errored).
   */
  public synchronized void recordHedgeFired() {
    ensureStart();
    hedgeFired++;
  }

  /**
   * Counts one install dropped by the reject-older ordering guard (an equal-or-older snapshot on
   * any install path, HTTP or SSE).
   */
  public synchronized void recordGuardRejected() {
    ensureStart();
    guardRejected++;
  }

  /**
   * Counts one successful HTTP install by the leg that served it: {@code sourceIndex} 0 is the
   * primary, any index &gt; 0 is a failover/secondary leg. A negative index (SSE/datadir install
   * with no HTTP leg) is ignored.
   */
  public synchronized void recordResolvedFrom(int sourceIndex) {
    if (sourceIndex < 0) return;
    ensureStart();
    if (sourceIndex == 0) {
      resolvedFromPrimary++;
    } else {
      resolvedFromSecondary++;
    }
  }

  private void ensureStart() {
    if (startAt == null) startAt = System.currentTimeMillis();
  }

  /**
   * Returns the window's counters as a {@code {"failover": {...}}} telemetry event and resets
   * state. Returns {@code null} if no failover activity occurred (every counter zero), so a healthy
   * steady-state client emits no failover event. {@code start}/{@code end} are unix millis,
   * matching the eval-summary window convention; the counters serialize with the exact camelCase
   * keys the api-telemetry schema parses.
   */
  public synchronized Map<String, Object> drain() {
    if (hedgeFired == 0
        && guardRejected == 0
        && resolvedFromPrimary == 0
        && resolvedFromSecondary == 0
        && resolvedFromLkg == 0) {
      return null;
    }

    long end = System.currentTimeMillis();
    long start = startAt != null ? startAt : end;

    Map<String, Object> failover = new LinkedHashMap<>();
    failover.put("start", start);
    failover.put("end", end);
    failover.put("hedgeFired", hedgeFired);
    failover.put("guardRejected", guardRejected);
    failover.put("resolvedFromPrimary", resolvedFromPrimary);
    failover.put("resolvedFromSecondary", resolvedFromSecondary);
    failover.put("resolvedFromLkg", resolvedFromLkg);

    Map<String, Object> event = new LinkedHashMap<>();
    event.put("failover", failover);

    hedgeFired = 0;
    guardRejected = 0;
    resolvedFromPrimary = 0;
    resolvedFromSecondary = 0;
    resolvedFromLkg = 0;
    startAt = null;
    return event;
  }
}
