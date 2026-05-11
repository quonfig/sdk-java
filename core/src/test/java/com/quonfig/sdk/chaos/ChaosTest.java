package com.quonfig.sdk.chaos;

import com.quonfig.sdk.Options;
import com.quonfig.sdk.Quonfig;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * Cross-SDK chaos harness — sdk-java runner (bead qfg-47c2.5).
 *
 * <p>Wires sdk-java's test runner to {@code integration-test-data/chaos/}. The shared launcher
 * ({@code integration-test-data/chaos/start-chaos.sh}) must already have booted toxiproxy and a
 * locally-spawned api-delivery (FIXTURE_DIR mode). This test reconfigures the seeded SSE/HTTP
 * proxies to point at that api-delivery, then runs each scenario against a fresh sdk-java client.
 *
 * <p>Gated on {@code CHAOS_RUN=1} so the default {@code ./gradlew test} does not depend on docker +
 * toxiproxy + api-delivery being on the host.
 *
 * <p>Run via {@code scripts/run-chaos.sh}, which handles the docker boot + api-delivery build +
 * teardown.
 *
 * <p>Environment knobs:
 *
 * <ul>
 *   <li>{@code CHAOS_RUN=1} required, gates the test (JUnit otherwise reports skipped).
 *   <li>{@code TOXIPROXY_URL} admin API base, default {@code http://127.0.0.1:8474}.
 *   <li>{@code CHAOS_SSE_PORT} host SSE port, default {@code 18550}.
 *   <li>{@code CHAOS_HTTP_PORT} host HTTP port, default {@code 18551}.
 *   <li>{@code CHAOS_API_DELIVERY_URL} api-delivery base URL, default {@code
 *       http://127.0.0.1:6550}.
 *   <li>{@code CHAOS_UPSTREAM_HOST} hostname toxiproxy uses, default {@code host.docker.internal}.
 *   <li>{@code CHAOS_ONLY}, {@code CHAOS_SKIP} comma scenario lists (e.g. {@code "02,05,07,09"}).
 *   <li>{@code CHAOS_POLL_MS} expectation poll interval, default 250.
 *   <li>{@code CHAOS_WALL_CLOCK_CAP_S} hard cap on per-scenario wall-clock seconds. Default 0 (no
 *       cap). Useful for fast red baselines: set to {@code 30} to bound long scenarios.
 *   <li>{@code CHAOS_FIXTURE_SDK_KEY} backend SDK key matching api-delivery's fixture key file.
 *       Default {@code test-backend-key}.
 * </ul>
 */
@EnabledIfEnvironmentVariable(named = "CHAOS_RUN", matches = "1")
final class ChaosTest {

  @TestFactory
  Collection<DynamicTest> chaosScenarios() throws Exception {
    String toxiUrl = envOr("TOXIPROXY_URL", "http://127.0.0.1:8474");
    ToxiproxyClient tp = new ToxiproxyClient(toxiUrl);
    try {
      tp.ping();
    } catch (Exception e) {
      throw new IllegalStateException(
          "toxiproxy not reachable at "
              + toxiUrl
              + ": "
              + e.getMessage()
              + " — run integration-test-data/chaos/start-chaos.sh first",
          e);
    }

    String apiUrl = envOr("CHAOS_API_DELIVERY_URL", "http://127.0.0.1:6550");
    int sseProxyPort = Integer.parseInt(envOr("CHAOS_SSE_PORT", "18550"));
    int httpProxyPort = Integer.parseInt(envOr("CHAOS_HTTP_PORT", "18551"));
    String upstreamHost = envOr("CHAOS_UPSTREAM_HOST", "host.docker.internal");
    int upstreamPort = parsePortFromUrl(apiUrl);

    tp.upsertProxy("sse", "0.0.0.0:" + sseProxyPort, upstreamHost + ":" + upstreamPort);
    tp.upsertProxy("http", "0.0.0.0:" + httpProxyPort, upstreamHost + ":" + upstreamPort);

    Set<String> only = csvSet(System.getenv("CHAOS_ONLY"));
    Set<String> skip = csvSet(System.getenv("CHAOS_SKIP"));
    long pollMs = Long.parseLong(envOr("CHAOS_POLL_MS", "250"));
    int wallClockCapS = Integer.parseInt(envOr("CHAOS_WALL_CLOCK_CAP_S", "0"));

    List<Path> files = listScenarios();
    List<DynamicTest> tests = new ArrayList<>();
    for (Path file : files) {
      String fname = file.getFileName().toString();
      String num = scenarioNumber(fname);
      if (!only.isEmpty() && !only.contains(num)) continue;
      if (skip.contains(num)) continue;

      ChaosScenario scenario = ChaosYamlLoader.load(file);
      String displayBase = fname.replaceFirst("\\.yaml$", "");
      if (scenario.tests == null) continue;
      for (ChaosScenario.Run run : scenario.tests) {
        String testName = displayBase + " / " + safeName(run.name);
        tests.add(
            DynamicTest.dynamicTest(
                testName,
                () ->
                    runScenario(
                        tp,
                        run,
                        sseProxyPort,
                        httpProxyPort,
                        Duration.ofMillis(pollMs),
                        wallClockCapS)));
      }
    }
    if (tests.isEmpty()) {
      throw new IllegalStateException(
          "no chaos scenarios matched CHAOS_ONLY="
              + System.getenv("CHAOS_ONLY")
              + " / CHAOS_SKIP="
              + System.getenv("CHAOS_SKIP"));
    }
    return tests;
  }

  private void runScenario(
      ToxiproxyClient tp,
      ChaosScenario.Run run,
      int sseProxyPort,
      int httpProxyPort,
      Duration poll,
      int wallClockCapS)
      throws Exception {
    // Reset proxy state between scenarios.
    tp.clearToxics("sse");
    tp.clearToxics("http");
    tp.setEnabled("sse", true);
    tp.setEnabled("http", true);

    ChaosProbe probe = new ChaosProbe();
    ExpressionEvaluator eval = new ExpressionEvaluator(probe);

    String sdkKey = envOr("CHAOS_FIXTURE_SDK_KEY", "test-backend-key");
    String httpBase = "http://127.0.0.1:" + httpProxyPort;
    String sseBase = "http://127.0.0.1:" + sseProxyPort;

    Options.Builder ob =
        Options.builder()
            .sdkKey(sdkKey)
            .apiUrls(List.of(httpBase))
            .streamUrls(List.of(sseBase))
            .disableTelemetry(true)
            .initTimeout(Duration.ofSeconds(15))
            // Scenario 07's within_ms=15000 expects the SSE deadline-trip to fire well before
            // the 30s server heartbeat, so chaos uses a short watchdog. Production default
            // (90s) is unchanged. Mirrors sdk-go's withTestSSEReadTimeout pattern.
            .sseReadWatchdog(Duration.ofSeconds(5))
            .onSseConnectionStateChange(probe::onSseState);

    if ("throw".equals(run.setup != null ? run.setup.userCallback : null)) {
      // Scenario 10 — user callback throws. sdk-java's Quonfig.fireConfigUpdate catches
      // RuntimeException so the SDK keeps running; processCrashed stays false. The asserted
      // `worker_restart_total` increment relies on supervisor visibility we don't have today.
      ob =
          ob.onConfigUpdate(
              () -> {
                probe.onConfigUpdate();
                throw new RuntimeException("simulated user-callback panic for chaos scenario 10");
              });
    } else {
      ob = ob.onConfigUpdate(probe::onConfigUpdate);
    }

    Options options = ob.build();
    Quonfig client = null;
    try {
      client = new Quonfig(options);
    } catch (RuntimeException e) {
      System.err.println("client construction failed: " + e.getMessage());
      probe.log("error", e.getMessage());
    }
    Quonfig finalClient = client;

    // Schedule chaos events.
    long baselineMs = System.currentTimeMillis();
    Map<String, InjectionState> injections = new ConcurrentHashMap<>();
    List<Thread> chaosThreads = new ArrayList<>();
    if (run.chaos != null) {
      for (ChaosScenario.Event ev : run.chaos) {
        long fireAt = baselineMs + ev.atMs;
        Thread t =
            new Thread(
                () -> {
                  long delay = fireAt - System.currentTimeMillis();
                  if (delay > 0) {
                    try {
                      Thread.sleep(delay);
                    } catch (InterruptedException ignored) {
                      Thread.currentThread().interrupt();
                      return;
                    }
                  }
                  try {
                    if (ev.inject != null) {
                      InjectionState st = applyInject(tp, ev.inject);
                      if (ev.inject.name != null && !ev.inject.name.isEmpty() && st != null) {
                        injections.put(ev.inject.name, st);
                      }
                      System.err.printf("[%6dms] inject %s%n", ev.atMs, describeInject(ev.inject));
                    } else if (ev.clear != null && !ev.clear.isEmpty()) {
                      clearInject(tp, injections.remove(ev.clear));
                      System.err.printf("[%6dms] clear %s%n", ev.atMs, ev.clear);
                    } else if (ev.process != null) {
                      applyProcess(tp, ev.process);
                      System.err.printf(
                          "[%6dms] process %s/%d%n", ev.atMs, ev.process.action, ev.process.count);
                    }
                  } catch (Exception ex) {
                    System.err.printf("[%6dms] chaos event failed: %s%n", ev.atMs, ex.getMessage());
                  }
                },
                "chaos-event-" + ev.atMs);
        t.setDaemon(true);
        t.start();
        chaosThreads.add(t);
      }
    }

    int wallClockS =
        run.setup != null && run.setup.wallClockSeconds > 0 ? run.setup.wallClockSeconds : 30;
    if (wallClockCapS > 0 && wallClockS > wallClockCapS) {
      System.err.printf(
          "scenario \"%s\" wall_clock_seconds=%d capped to %d via CHAOS_WALL_CLOCK_CAP_S%n",
          run.name, wallClockS, wallClockCapS);
      wallClockS = wallClockCapS;
    }
    long deadline = baselineMs + wallClockS * 1000L;

    List<ExpState> states = new ArrayList<>();
    if (run.expectations != null) {
      for (int i = 0; i < run.expectations.size(); i++) {
        states.add(new ExpState(i, run.expectations.get(i)));
      }
    }

    try {
      while (System.currentTimeMillis() < deadline) {
        long now = System.currentTimeMillis();
        long elapsed = now - baselineMs;
        boolean allTerminal = true;
        for (ExpState s : states) {
          if (s.passed || s.failed) continue;
          ExpressionEvaluator.Result r = eval.evaluate(s.exp.assertExpr);
          s.lastReason = r.reason;
          if (r.passed) {
            if (s.heldSinceMs == 0) {
              s.heldSinceMs = now;
              s.hitAtMs = elapsed;
            }
            long holdFor = s.exp.mustHoldForMs;
            if (holdFor <= 0 || (now - s.heldSinceMs) >= holdFor) {
              s.passed = true;
            } else {
              allTerminal = false;
            }
          } else {
            s.heldSinceMs = 0;
            allTerminal = false;
          }
          if (!s.passed && elapsed > s.exp.withinMs) {
            s.failed = true;
          }
        }
        if (allTerminal) break;
        Thread.sleep(poll.toMillis());
      }
      // Anything still indeterminate is a fail.
      for (ExpState s : states) {
        if (!s.passed) s.failed = true;
      }
    } finally {
      // Wait for any in-flight chaos threads to finish (so subsequent scenarios start clean).
      for (Thread t : chaosThreads) {
        t.interrupt();
        try {
          t.join(500);
        } catch (InterruptedException ignored) {
          Thread.currentThread().interrupt();
        }
      }
      if (finalClient != null) finalClient.close();
    }

    int pass = 0;
    int fail = 0;
    List<String> failures = new ArrayList<>();
    for (ExpState s : states) {
      if (s.passed) {
        pass++;
        System.err.printf(
            "PASS  exp[%d] within=%dms hold=%dms: %s  (hit at %dms)%n",
            s.idx, s.exp.withinMs, s.exp.mustHoldForMs, s.exp.assertExpr, s.hitAtMs);
      } else {
        fail++;
        String msg =
            String.format(
                "FAIL  exp[%d] within=%dms hold=%dms: %s — last reason: %s",
                s.idx, s.exp.withinMs, s.exp.mustHoldForMs, s.exp.assertExpr, s.lastReason);
        System.err.println(msg);
        failures.add(msg);
      }
    }
    System.err.printf(
        "scenario summary: %d passed, %d failed (state=%s, restartL1=%.0f, fallback=%b, lastRefreshMs=%d)%n",
        pass,
        fail,
        probe.connectionState(),
        probe.sdkMetric("quonfig_sdk_worker_restart_total", "1"),
        probe.fallbackPollerActive(),
        probe.lastSuccessfulRefreshMs());

    if (!failures.isEmpty()) {
      throw new AssertionError(
          String.format(
              "scenario \"%s\" — %d/%d expectations failed:%n%s",
              run.name, fail, states.size(), String.join("\n", failures)));
    }
  }

  // ---- chaos injection translation ----

  private static InjectionState applyInject(ToxiproxyClient tp, ChaosScenario.Inject inj)
      throws Exception {
    String name = (inj.name == null || inj.name.isEmpty()) ? "anon" : inj.name;
    if (inj.sseSilentStallAfterMs != null) {
      Map<String, Object> attrs = new LinkedHashMap<>();
      attrs.put("timeout", inj.sseSilentStallAfterMs);
      tp.addToxic("sse", name, "timeout", "downstream", attrs);
      return new InjectionState("sse", name, Collections.emptyList());
    }
    if (inj.sseLatencyMs != null) {
      Map<String, Object> attrs = new LinkedHashMap<>();
      attrs.put("latency", inj.sseLatencyMs);
      tp.addToxic("sse", name, "latency", "downstream", attrs);
      return new InjectionState("sse", name, Collections.emptyList());
    }
    if (inj.sseBandwidthKbps != null) {
      Map<String, Object> attrs = new LinkedHashMap<>();
      attrs.put("rate", inj.sseBandwidthKbps);
      tp.addToxic("sse", name, "bandwidth", "downstream", attrs);
      return new InjectionState("sse", name, Collections.emptyList());
    }
    if (inj.sseDownMs != null) {
      tp.setEnabled("sse", false);
      return new InjectionState(null, null, List.of("sse"));
    }
    if (inj.bothDownMs != null) {
      tp.setEnabled("sse", false);
      tp.setEnabled("http", false);
      return new InjectionState(null, null, List.of("sse", "http"));
    }
    if (inj.sseHalfOpenAfterBytes != null) {
      Map<String, Object> attrs = new LinkedHashMap<>();
      attrs.put("bytes", inj.sseHalfOpenAfterBytes);
      tp.addToxic("sse", name, "limit_data", "downstream", attrs);
      return new InjectionState("sse", name, Collections.emptyList());
    }
    if (inj.sseHttpStatus != null) {
      // Toxiproxy is TCP-only; HTTP-status injection is not supported. Scenario 8 will not
      // exercise the 401 path here. Log and no-op.
      System.err.println(
          "inject: sse_http_status="
              + inj.sseHttpStatus
              + " — toxiproxy is TCP-only, not implemented (scenario will be partial/unsupported)");
      return new InjectionState(null, null, Collections.emptyList());
    }
    if (inj.proxy != null && !inj.proxy.isEmpty() && inj.toxic != null) {
      String type = String.valueOf(inj.toxic.get("type"));
      Object rawAttrs = inj.toxic.get("attributes");
      Map<String, Object> attrs =
          rawAttrs instanceof Map ? new LinkedHashMap<>((Map) rawAttrs) : new LinkedHashMap<>();
      tp.addToxic(inj.proxy, name, type, "downstream", attrs);
      return new InjectionState(inj.proxy, name, Collections.emptyList());
    }
    System.err.println("inject: unknown shape — no-op");
    return null;
  }

  private static void clearInject(ToxiproxyClient tp, InjectionState st) throws Exception {
    if (st == null) return;
    if (st.toxic != null && st.proxy != null) {
      tp.removeToxic(st.proxy, st.toxic);
    }
    for (String p : st.enable) {
      tp.setEnabled(p, true);
    }
  }

  private static void applyProcess(ToxiproxyClient tp, ChaosScenario.Process p) {
    if ("kill_sse_proxy".equals(p.action)) {
      int count = Math.max(1, p.count);
      long interval = p.intervalMs > 0 ? p.intervalMs : 1000L;
      Thread t =
          new Thread(
              () -> {
                try {
                  for (int i = 0; i < count; i++) {
                    tp.setEnabled("sse", false);
                    Thread.sleep(200);
                    tp.setEnabled("sse", true);
                    if (i < count - 1) Thread.sleep(Math.max(0, interval - 200));
                  }
                } catch (Exception ignored) {
                  // best-effort
                }
              },
              "chaos-kill-sse");
      t.setDaemon(true);
      t.start();
    } else {
      System.err.println("process: unknown action " + p.action + " — no-op");
    }
  }

  // ---- helpers ----

  private static String describeInject(ChaosScenario.Inject inj) {
    if (inj.sseSilentStallAfterMs != null)
      return "sse_silent_stall_after_ms=" + inj.sseSilentStallAfterMs;
    if (inj.sseLatencyMs != null) return "sse_latency_ms=" + inj.sseLatencyMs;
    if (inj.sseBandwidthKbps != null) return "sse_bandwidth_kbps=" + inj.sseBandwidthKbps;
    if (inj.sseDownMs != null) return "sse_down_ms=" + inj.sseDownMs;
    if (inj.bothDownMs != null) return "both_down_ms=" + inj.bothDownMs;
    if (inj.sseHalfOpenAfterBytes != null)
      return "sse_half_open_after_bytes=" + inj.sseHalfOpenAfterBytes;
    if (inj.sseHttpStatus != null) return "sse_http_status=" + inj.sseHttpStatus;
    return "?";
  }

  private static String envOr(String key, String def) {
    String v = System.getenv(key);
    return v == null || v.isEmpty() ? def : v;
  }

  private static Set<String> csvSet(String s) {
    if (s == null || s.isEmpty()) return Collections.emptySet();
    return Arrays.stream(s.split(","))
        .map(String::trim)
        .filter(x -> !x.isEmpty())
        .collect(Collectors.toCollection(HashSet::new));
  }

  private static int parsePortFromUrl(String url) {
    int colon = url.lastIndexOf(':');
    if (colon < 0) throw new IllegalArgumentException("no port in URL " + url);
    String rest = url.substring(colon + 1);
    int slash = rest.indexOf('/');
    if (slash >= 0) rest = rest.substring(0, slash);
    return Integer.parseInt(rest);
  }

  private static String scenarioNumber(String filename) {
    int dash = filename.indexOf('-');
    return dash > 0 ? filename.substring(0, dash) : filename;
  }

  private static String safeName(String s) {
    if (s == null) return "";
    return s.replace(' ', '_').replace('/', '_').replace('(', '_').replace(')', '_');
  }

  private static List<Path> listScenarios() throws IOException {
    Path dir = scenariosDir();
    List<Path> out = new ArrayList<>();
    try (Stream<Path> s = Files.list(dir)) {
      s.filter(p -> p.toString().endsWith(".yaml")).sorted().forEach(out::add);
    }
    return out;
  }

  private static Path scenariosDir() {
    String userDir = System.getProperty("user.dir");
    Path candidate =
        Paths.get(userDir, "..", "integration-test-data", "chaos", "scenarios").normalize();
    if (Files.isDirectory(candidate)) return candidate;
    Path alt = Paths.get(userDir, "integration-test-data", "chaos", "scenarios").normalize();
    if (Files.isDirectory(alt)) return alt;
    throw new IllegalStateException(
        "chaos scenarios directory not found relative to "
            + userDir
            + " — expected at ../integration-test-data/chaos/scenarios or integration-test-data/chaos/scenarios");
  }

  // ---- internal state types ----

  private static final class InjectionState {
    final String proxy;
    final String toxic;
    final List<String> enable;

    InjectionState(String proxy, String toxic, List<String> enable) {
      this.proxy = proxy;
      this.toxic = toxic;
      this.enable = enable;
    }
  }

  private static final class ExpState {
    final int idx;
    final ChaosScenario.Expectation exp;
    long hitAtMs;
    long heldSinceMs;
    boolean passed;
    boolean failed;
    String lastReason = "";

    ExpState(int idx, ChaosScenario.Expectation exp) {
      this.idx = idx;
      this.exp = exp;
    }
  }
}
