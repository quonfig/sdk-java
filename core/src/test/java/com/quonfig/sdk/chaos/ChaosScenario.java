package com.quonfig.sdk.chaos;

import java.util.List;
import java.util.Map;

/**
 * YAML-shape model for one chaos scenario file under {@code
 * integration-test-data/chaos/scenarios/*.yaml}. Mirrors the {@code chaosScenario} struct in
 * sdk-go's {@code chaos_helpers_test.go}; the schema authority is {@code
 * integration-test-data/chaos/schema/scenario.schema.json}.
 */
final class ChaosScenario {
  String function;
  List<Run> tests;

  static final class Run {
    String name;
    String description;
    Setup setup;
    List<Event> chaos;
    List<Expectation> expectations;
  }

  static final class Setup {
    String sdk;
    String sseEndpoint;
    String httpEndpoint;
    int wallClockSeconds;
    String userCallback;
    // Failover/ordering rigs (qfg-7h5d.1.10): topology selects the rig; upstreams pins each leg's
    // Meta.generation for the ordering rig.
    String topology;
    List<Upstream> upstreams;
  }

  /**
   * One upstream leg in the ordering rig — a role ("primary"/"secondary") at a pinned generation.
   */
  static final class Upstream {
    String role;
    int generation;
  }

  static final class Event {
    int atMs;
    Inject inject;
    String clear;
    Process process;
  }

  static final class Inject {
    String name;
    // Convenience aliases — null means "not set".
    Integer sseSilentStallAfterMs;
    Integer sseLatencyMs;
    Integer sseBandwidthKbps;
    Integer sseDownMs;
    Integer bothDownMs;
    Integer sseHalfOpenAfterBytes;
    Integer sseHttpStatus;
    // Failover-rig aliases (qfg-7h5d.1.10) — self-restoring faults on the primary HTTP leg, each
    // carrying its own duration in ms.
    Integer primaryRefusedMs;
    Integer primaryHangMs;
    Integer primaryLatencyMs;
    // Raw toxiproxy escape hatch.
    String proxy;
    Map<String, Object> toxic;
  }

  static final class Process {
    String action;
    int count;
    int intervalMs;
  }

  static final class Expectation {
    int withinMs;
    int mustHoldForMs;
    String assertExpr;
  }
}
