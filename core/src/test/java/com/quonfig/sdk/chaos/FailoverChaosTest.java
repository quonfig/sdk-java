package com.quonfig.sdk.chaos;

import com.quonfig.sdk.Options;
import com.quonfig.sdk.Quonfig;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicContainer;
import org.junit.jupiter.api.DynamicNode;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * Failover + canonical-ordering chaos runner for sdk-java (bead qfg-7h5d.1.10), mirroring sdk-go's
 * {@code failover_chaos_test.go} pilot. Consumes the two shared corpus rigs:
 *
 * <ul>
 *   <li>{@code scenarios-failover/} (f01-f05) — ONE fixture upstream behind the primary ({@code
 *       http}) + {@code secondary} proxies. Faults hit the primary leg only; the SDK must fail the
 *       HTTP config fetch over to the secondary and keep serving, fast. SSE is asserted NOT to
 *       repoint (f05).
 *   <li>{@code scenarios-ordering/} (o01-o04) — TWO fixture upstreams pinned to divergent {@code
 *       Meta.generation}s. The SDK must end up holding the higher generation and an established
 *       client must never regress. A background {@link Quonfig#refresh()} loop models ongoing
 *       polling so the reject-older guard is exercised on the failover/refresh install path.
 * </ul>
 *
 * <p>Only toxiproxy needs to be running (boot it with {@code
 * integration-test-data/chaos/start-chaos.sh}). Each scenario spawns its own api-delivery fixture
 * upstream(s) and repoints the seeded {@code http}/{@code secondary}/{@code sse} proxies at them —
 * spawning here (rather than via the launcher) lets the ordering rig pin a different generation per
 * scenario. Run via {@code scripts/run-failover-chaos.sh}.
 *
 * <p>Gated on {@code CHAOS_RUN=1} so the default {@code ./gradlew test} never depends on docker +
 * toxiproxy + a built api-delivery.
 *
 * <p>RED baseline this bead captured before the two fixes landed: f02 (primary hang) failed — no
 * per-URL config-fetch timeout, so a hung primary starved the secondary until initTimeout; o02
 * (secondary older) failed — installs were unconditional, so a failover fetch of the older
 * secondary regressed the held generation. Both green after qfg-7h5d.1.10.
 */
@EnabledIfEnvironmentVariable(named = "CHAOS_RUN", matches = "1")
final class FailoverChaosTest {

  // Host ports the launcher maps the seeded proxies to (docker-compose.yml / start-chaos.sh).
  private static final int RIG_SSE_PORT = 18550; // 'sse' proxy — live stream (primary leg only)
  private static final int RIG_PRIMARY_PORT = 18551; // 'http' proxy — primary HTTP leg
  private static final int RIG_SECONDARY_PORT = 18552; // 'secondary' proxy — secondary HTTP leg
  private static final Duration RIG_INIT_TIMEOUT = Duration.ofSeconds(8);
  private static final Duration RIG_CONFIG_FETCH_TIMEOUT = Duration.ofSeconds(3);
  private static final String FIXTURE_SDK_KEY = "test-backend-key";

  /** Failover rig (f01-f05): one upstream behind primary+secondary proxies; faults hit primary. */
  @TestFactory
  Collection<DynamicNode> failoverScenarios() throws Exception {
    ToxiproxyClient tp = dialToxiproxy();
    Path binary = ApiDelivery.binary();
    List<DynamicNode> nodes = new ArrayList<>();
    for (Path file : listScenarios("scenarios-failover")) {
      String base = file.getFileName().toString().replaceFirst("\\.yaml$", "");
      if (skipped(base)) continue;
      ChaosScenario scenario = ChaosYamlLoader.load(file);
      List<DynamicTest> runs = new ArrayList<>();
      for (ChaosScenario.Run run : scenario.tests) {
        runs.add(
            DynamicTest.dynamicTest(
                safeName(run.name),
                () -> {
                  // One upstream; both HTTP legs and the SSE leg point at it (identical content
                  // proves failover routing, not divergent data — that's the ordering rig).
                  int port = freePort();
                  ApiDelivery up = ApiDelivery.spawn(binary, port, 0);
                  try {
                    repointProxies(tp, port, port);
                    runScenario(tp, run, false);
                  } finally {
                    up.stop();
                  }
                }));
      }
      nodes.add(DynamicContainer.dynamicContainer(base, runs));
    }
    return nodes;
  }

  /**
   * Ordering rig (o01-o04): two upstreams at divergent generations; a refresh loop drives polls.
   */
  @TestFactory
  Collection<DynamicNode> orderingScenarios() throws Exception {
    ToxiproxyClient tp = dialToxiproxy();
    Path binary = ApiDelivery.binary();
    List<DynamicNode> nodes = new ArrayList<>();
    for (Path file : listScenarios("scenarios-ordering")) {
      String base = file.getFileName().toString().replaceFirst("\\.yaml$", "");
      if (skipped(base)) continue;
      ChaosScenario scenario = ChaosYamlLoader.load(file);
      List<DynamicTest> runs = new ArrayList<>();
      for (ChaosScenario.Run run : scenario.tests) {
        runs.add(
            DynamicTest.dynamicTest(
                safeName(run.name),
                () -> {
                  int[] gens = upstreamGenerations(run.setup);
                  // Distinct ports per scenario so a prior scenario's upstream can't collide.
                  int primaryPort = freePort();
                  int secondaryPort = freePort();
                  ApiDelivery primary = ApiDelivery.spawn(binary, primaryPort, gens[0]);
                  ApiDelivery secondary = ApiDelivery.spawn(binary, secondaryPort, gens[1]);
                  try {
                    repointProxies(tp, primaryPort, secondaryPort);
                    runScenario(tp, run, true);
                  } finally {
                    primary.stop();
                    secondary.stop();
                  }
                }));
      }
      nodes.add(DynamicContainer.dynamicContainer(base, runs));
    }
    return nodes;
  }

  // ---- scenario execution ----

  /**
   * Stands up a fresh SDK client pointed at [primary, secondary], schedules the scenario's chaos
   * events against the primary leg, optionally drives a {@link Quonfig#refresh()} loop, then
   * evaluates every expectation on a poll timer.
   */
  private void runScenario(ToxiproxyClient tp, ChaosScenario.Run run, boolean driveRefresh)
      throws Exception {
    // Start from a clean proxy state — no leftover toxics, all legs enabled.
    for (String p : List.of("http", "secondary", "sse")) {
      tp.clearToxics(p);
      tp.setEnabled(p, true);
    }

    boolean sseEnabled =
        run.setup != null
            && run.setup.sseEndpoint != null
            && !run.setup.sseEndpoint.equals("disabled");

    String primaryUrl = "http://127.0.0.1:" + RIG_PRIMARY_PORT;
    String secondaryUrl = "http://127.0.0.1:" + RIG_SECONDARY_PORT;
    // SSE is HTTP-only-failover-exempt: when the scenario enables it, point the single stream leg
    // at
    // the sse proxy (primary upstream). When disabled, point it at a closed port so the SSE loop
    // backs off quietly and the HTTP path is the only installer — matching sse_endpoint: disabled.
    String streamUrl =
        sseEnabled ? "http://127.0.0.1:" + RIG_SSE_PORT : "http://127.0.0.1:" + freePort();

    Quonfig client =
        new Quonfig(
            Options.builder()
                .sdkKey(FIXTURE_SDK_KEY)
                .apiUrls(List.of(primaryUrl, secondaryUrl))
                .streamUrls(List.of(streamUrl))
                .disableTelemetry(true)
                .initTimeout(RIG_INIT_TIMEOUT)
                .configFetchTimeout(RIG_CONFIG_FETCH_TIMEOUT)
                // Ordering drives polling explicitly via the refresh loop below; failover needs no
                // background poll. Keep the fallback poller off so install accounting (o04's
                // configInstallCount) is deterministic.
                .fallbackPollEnabled(false)
                .sseReadWatchdog(Duration.ofSeconds(5))
                .build());

    long baselineMs = System.currentTimeMillis();
    List<Thread> bg = new ArrayList<>();

    // Schedule chaos events. The failover-rig aliases are self-restoring (each carries its own
    // duration), so there are no `clear` events to track.
    if (run.chaos != null) {
      for (ChaosScenario.Event ev : run.chaos) {
        if (ev.inject == null) continue;
        long fireAt = baselineMs + ev.atMs;
        Thread t =
            new Thread(
                () -> {
                  sleepUntil(fireAt);
                  try {
                    applyFailoverInject(tp, ev.inject);
                    System.err.printf("[%6dms] inject %s%n", ev.atMs, describeInject(ev.inject));
                  } catch (Exception e) {
                    System.err.printf("[%6dms] inject failed: %s%n", ev.atMs, e.getMessage());
                  }
                },
                "chaos-event-" + ev.atMs);
        t.setDaemon(true);
        t.start();
        bg.add(t);
      }
    }

    // Ordering rig: model ongoing config polling. Each refresh re-runs the [primary, secondary]
    // failover fetch; without the reject-older guard a failover to an older secondary regresses the
    // held generation (o02 red).
    if (driveRefresh) {
      Thread t =
          new Thread(
              () -> {
                while (!Thread.currentThread().isInterrupted()) {
                  try {
                    Thread.sleep(750);
                  } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                  }
                  client.refresh();
                }
              },
              "ordering-refresh");
      t.setDaemon(true);
      t.start();
      bg.add(t);
    }

    try {
      evalExpectations(run, baselineMs, client);
    } finally {
      for (Thread t : bg) t.interrupt();
      client.close();
    }
  }

  /** Polls each expectation until it passes (and holds for must_hold_for_ms) or its deadline. */
  private void evalExpectations(ChaosScenario.Run run, long baselineMs, Quonfig client) {
    int wallClockS =
        run.setup != null && run.setup.wallClockSeconds > 0 ? run.setup.wallClockSeconds : 30;
    long deadline = baselineMs + wallClockS * 1000L;

    List<ExpState> states = new ArrayList<>();
    if (run.expectations != null) {
      for (ChaosScenario.Expectation e : run.expectations) states.add(new ExpState(e));
    }

    while (System.currentTimeMillis() < deadline) {
      long now = System.currentTimeMillis();
      long elapsed = now - baselineMs;
      boolean allTerminal = true;
      for (ExpState s : states) {
        if (s.passed || s.failed) continue;
        Result r = evalExpr(s.exp.assertExpr, client);
        s.lastReason = r.reason;
        if (r.ok) {
          if (s.heldSinceMs == 0) s.heldSinceMs = now;
          if (s.exp.mustHoldForMs <= 0 || (now - s.heldSinceMs) >= s.exp.mustHoldForMs) {
            s.passed = true;
          } else {
            allTerminal = false;
          }
        } else {
          s.heldSinceMs = 0;
          allTerminal = false;
        }
        if (!s.passed && elapsed > s.exp.withinMs) s.failed = true;
      }
      if (allTerminal) break;
      try {
        Thread.sleep(200);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        break;
      }
    }

    List<String> failures = new ArrayList<>();
    for (int i = 0; i < states.size(); i++) {
      ExpState s = states.get(i);
      if (!s.passed) s.failed = true;
      if (s.passed) {
        System.err.printf(
            "PASS  exp[%d] within=%dms hold=%dms: %s%n",
            i, s.exp.withinMs, s.exp.mustHoldForMs, s.exp.assertExpr);
      } else {
        String msg =
            String.format(
                "FAIL  exp[%d] within=%dms hold=%dms: %s — last reason: %s",
                i, s.exp.withinMs, s.exp.mustHoldForMs, s.exp.assertExpr, s.lastReason);
        System.err.println(msg);
        failures.add(msg);
      }
    }
    System.err.printf(
        "scenario summary: ready=%b resolvedFrom=%s heldGeneration=%d installs=%d sseFailedOverToSecondary=%b%n",
        client.ready(),
        client.resolvedFrom(),
        client.heldGeneration(),
        client.configInstallCount(),
        client.sseFailedOverToSecondary());

    if (!failures.isEmpty()) {
      throw new AssertionError(
          String.format(
              "scenario \"%s\" — %d/%d expectations failed:%n%s",
              run.name, failures.size(), states.size(), String.join("\n", failures)));
    }
  }

  // ---- chaos injection (self-restoring failover-rig aliases) ----

  private void applyFailoverInject(ToxiproxyClient tp, ChaosScenario.Inject inj) throws Exception {
    String name = (inj.name == null || inj.name.isEmpty()) ? "primary_fault" : inj.name;
    if (inj.primaryRefusedMs != null) {
      // Disable the primary proxy so its listen port refuses connections.
      tp.setEnabled("http", false);
      restoreAfter(inj.primaryRefusedMs, () -> tp.setEnabled("http", true));
    } else if (inj.primaryHangMs != null) {
      // 'timeout' toxic: accept the TCP connection but never deliver the response, so the fetch
      // blocks (the hang that starves the secondary until initTimeout without a per-URL deadline).
      tp.addToxic(
          "http", name, "timeout", "downstream", java.util.Map.of("timeout", inj.primaryHangMs));
      restoreAfter(inj.primaryHangMs, () -> tp.removeToxic("http", name));
    } else if (inj.primaryLatencyMs != null) {
      tp.addToxic(
          "http", name, "latency", "downstream", java.util.Map.of("latency", inj.primaryLatencyMs));
      restoreAfter(inj.primaryLatencyMs, () -> tp.removeToxic("http", name));
    } else if (inj.sseDownMs != null) {
      // Take the SSE leg down while both HTTP legs stay up (f05).
      tp.setEnabled("sse", false);
      restoreAfter(inj.sseDownMs, () -> tp.setEnabled("sse", true));
    } else {
      System.err.println("applyFailoverInject: unhandled inject shape — no-op");
    }
  }

  private interface ProxyAction {
    void run() throws Exception;
  }

  private static void restoreAfter(int ms, ProxyAction action) {
    Thread t =
        new Thread(
            () -> {
              try {
                Thread.sleep(ms);
                action.run();
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              } catch (Exception e) {
                System.err.println("restore failed: " + e.getMessage());
              }
            },
            "chaos-restore");
    t.setDaemon(true);
    t.start();
  }

  private static String describeInject(ChaosScenario.Inject inj) {
    if (inj.primaryRefusedMs != null) return "primary_refused_ms=" + inj.primaryRefusedMs;
    if (inj.primaryHangMs != null) return "primary_hang_ms=" + inj.primaryHangMs;
    if (inj.primaryLatencyMs != null) return "primary_latency_ms=" + inj.primaryLatencyMs;
    if (inj.sseDownMs != null) return "sse_down_ms=" + inj.sseDownMs;
    return "?";
  }

  // ---- expression evaluator (failover/ordering vocabulary) ----

  private static final Pattern RE_READY =
      Pattern.compile("^client\\.ready\\(\\)\\s*==\\s*(true|false)$");
  private static final Pattern RE_RESOLVED =
      Pattern.compile("^client\\.resolvedFrom\\(\\)\\s*(==|!=)\\s*'([^']+)'$");
  private static final Pattern RE_HELD_GEN =
      Pattern.compile("^client\\.heldGeneration\\(\\)\\s*(>=|<=|==|!=|<|>)\\s*(-?\\d+)$");
  private static final Pattern RE_INSTALL_CNT =
      Pattern.compile("^client\\.configInstallCount\\(\\)\\s*(>=|<=|==|!=|<|>)\\s*(-?\\d+)$");
  private static final Pattern RE_SSE_FAILOVER =
      Pattern.compile("^client\\.sseFailedOverToSecondary\\(\\)\\s*==\\s*(true|false)$");

  private static final class Result {
    final boolean ok;
    final String reason;

    Result(boolean ok, String reason) {
      this.ok = ok;
      this.reason = reason;
    }
  }

  private static Result evalExpr(String expr, Quonfig client) {
    String e = expr == null ? "" : expr.trim();
    if (e.isEmpty()) return new Result(true, "");
    if (e.contains(" AND ")) {
      for (String part : e.split(" AND ")) {
        Result r = evalExpr(part.trim(), client);
        if (!r.ok) return new Result(false, "AND: " + r.reason);
      }
      return new Result(true, "");
    }
    if (e.contains(" OR ")) {
      List<String> reasons = new ArrayList<>();
      for (String part : e.split(" OR ")) {
        Result r = evalExpr(part.trim(), client);
        if (r.ok) return new Result(true, "");
        reasons.add(r.reason);
      }
      return new Result(false, "OR: " + String.join(" | ", reasons));
    }
    return evalLeaf(e, client);
  }

  private static Result evalLeaf(String expr, Quonfig client) {
    Matcher m;
    if ((m = RE_READY.matcher(expr)).matches()) {
      boolean want = Boolean.parseBoolean(m.group(1));
      boolean got = client.ready();
      return new Result(got == want, "ready=" + got + " want " + want);
    }
    if ((m = RE_RESOLVED.matcher(expr)).matches()) {
      String got = client.resolvedFrom();
      String want = m.group(2);
      boolean ok = "==".equals(m.group(1)) ? got.equals(want) : !got.equals(want);
      return new Result(ok, "resolvedFrom=" + got + " " + m.group(1) + " " + want);
    }
    if ((m = RE_HELD_GEN.matcher(expr)).matches()) {
      long got = client.heldGeneration();
      long want = Long.parseLong(m.group(2));
      return new Result(
          cmp(m.group(1), got, want), "heldGeneration=" + got + " " + m.group(1) + " " + want);
    }
    if ((m = RE_INSTALL_CNT.matcher(expr)).matches()) {
      long got = client.configInstallCount();
      long want = Long.parseLong(m.group(2));
      return new Result(
          cmp(m.group(1), got, want), "configInstallCount=" + got + " " + m.group(1) + " " + want);
    }
    if ((m = RE_SSE_FAILOVER.matcher(expr)).matches()) {
      boolean want = Boolean.parseBoolean(m.group(1));
      boolean got = client.sseFailedOverToSecondary();
      return new Result(got == want, "sseFailedOverToSecondary=" + got + " want " + want);
    }
    return new Result(false, "unrecognized expression: " + expr);
  }

  private static boolean cmp(String op, long a, long b) {
    switch (op) {
      case "==":
        return a == b;
      case "!=":
        return a != b;
      case "<":
        return a < b;
      case "<=":
        return a <= b;
      case ">":
        return a > b;
      case ">=":
        return a >= b;
      default:
        return false;
    }
  }

  // ---- rig plumbing ----

  private ToxiproxyClient dialToxiproxy() {
    String url = envOr("TOXIPROXY_URL", "http://127.0.0.1:8474");
    ToxiproxyClient tp = new ToxiproxyClient(url);
    try {
      tp.ping();
    } catch (Exception e) {
      throw new IllegalStateException(
          "toxiproxy not reachable at "
              + url
              + ": "
              + e.getMessage()
              + " — run integration-test-data/chaos/start-chaos.sh first",
          e);
    }
    return tp;
  }

  /** Repoints the seeded proxies at the spawned upstream(s). SSE always tracks the primary. */
  private void repointProxies(ToxiproxyClient tp, int primaryPort, int secondaryPort)
      throws Exception {
    String host = envOr("CHAOS_UPSTREAM_HOST", "host.docker.internal");
    tp.upsertProxy("http", "0.0.0.0:" + RIG_PRIMARY_PORT, host + ":" + primaryPort);
    tp.upsertProxy("secondary", "0.0.0.0:" + RIG_SECONDARY_PORT, host + ":" + secondaryPort);
    tp.upsertProxy("sse", "0.0.0.0:" + RIG_SSE_PORT, host + ":" + primaryPort);
  }

  private static int[] upstreamGenerations(ChaosScenario.Setup setup) {
    int primary = 0;
    int secondary = 0;
    if (setup != null && setup.upstreams != null) {
      for (ChaosScenario.Upstream u : setup.upstreams) {
        if ("primary".equals(u.role)) primary = u.generation;
        else if ("secondary".equals(u.role)) secondary = u.generation;
      }
    }
    return new int[] {primary, secondary};
  }

  private static void sleepUntil(long whenMs) {
    long d = whenMs - System.currentTimeMillis();
    if (d > 0) {
      try {
        Thread.sleep(d);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
  }

  private static int freePort() throws IOException {
    try (ServerSocket s = new ServerSocket(0)) {
      return s.getLocalPort();
    }
  }

  private static String envOr(String key, String def) {
    String v = System.getenv(key);
    return v == null || v.isEmpty() ? def : v;
  }

  /**
   * Whether a scenario base name (e.g. {@code o01-secondary-newer}) is excluded via the {@code
   * CHAOS_SKIP} env var (comma-separated substrings). The CI job skips {@code o01}, which needs
   * cross-leg max-wins (qfg-7h5d.1.14) — out of the §5f reject-older scope.
   */
  private static boolean skipped(String base) {
    String skip = System.getenv("CHAOS_SKIP");
    if (skip == null || skip.isEmpty()) return false;
    for (String tok : skip.split(",")) {
      String t = tok.trim();
      if (!t.isEmpty() && base.contains(t)) return true;
    }
    return false;
  }

  private static String safeName(String s) {
    if (s == null) return "";
    return s.replace(' ', '_').replace('/', '_').replace('(', '_').replace(')', '_');
  }

  private static List<Path> listScenarios(String dirName) throws IOException {
    Path dir = chaosDir(dirName);
    List<Path> out = new ArrayList<>();
    try (Stream<Path> s = Files.list(dir)) {
      s.filter(p -> p.toString().endsWith(".yaml")).sorted().forEach(out::add);
    }
    if (out.isEmpty()) throw new IllegalStateException("no scenarios in " + dir);
    return out;
  }

  private static Path chaosDir(String dirName) {
    Path root = repoRoot();
    return root.resolve("integration-test-data").resolve("chaos").resolve(dirName);
  }

  /**
   * Walks up from the test working dir until it finds the repo root (has integration-test-data).
   */
  static Path repoRoot() {
    Path p = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
    for (int i = 0; i < 6 && p != null; i++) {
      if (Files.isDirectory(p.resolve("integration-test-data").resolve("chaos"))
          && Files.isDirectory(p.resolve("api-delivery"))) {
        return p;
      }
      p = p.getParent();
    }
    throw new IllegalStateException(
        "repo root not found from "
            + System.getProperty("user.dir")
            + " — expected a parent dir containing integration-test-data/chaos and api-delivery");
  }

  // ---- api-delivery fixture upstream ----

  /** Builds and spawns the api-delivery server binary in fixture mode at a pinned generation. */
  private static final class ApiDelivery {
    private static volatile Path cachedBinary;

    private final Process process;

    private ApiDelivery(Process process) {
      this.process = process;
    }

    /** Builds the api-delivery binary once (GOWORK=off, like run-chaos.sh) and caches the path. */
    static synchronized Path binary() throws Exception {
      if (cachedBinary != null) return cachedBinary;
      Path root = repoRoot();
      Path serverDir = root.resolve("api-delivery");
      Path out = Files.createTempFile("api-delivery-chaos", "");
      Files.deleteIfExists(out);
      ProcessBuilder pb =
          new ProcessBuilder("go", "build", "-o", out.toString(), "./cmd/server")
              .directory(serverDir.toFile())
              .redirectErrorStream(true);
      pb.environment().put("GOWORK", "off");
      Process p = pb.start();
      String log = new String(p.getInputStream().readAllBytes());
      int code = p.waitFor();
      if (code != 0) {
        throw new IllegalStateException("build api-delivery failed (exit " + code + "):\n" + log);
      }
      cachedBinary = out;
      return out;
    }

    static ApiDelivery spawn(Path binary, int port, int generation) throws Exception {
      Path root = repoRoot();
      Path fixtureDir =
          root.resolve("integration-test-data").resolve("data").resolve("integration-tests");
      Path keys = root.resolve("api-delivery").resolve("testdata").resolve("fixture-sdk-keys.json");
      if (!Files.isDirectory(fixtureDir)) {
        throw new IllegalStateException("fixtures not found at " + fixtureDir);
      }
      if (!Files.exists(keys)) {
        throw new IllegalStateException("fixture SDK keys not found at " + keys);
      }
      ProcessBuilder pb = new ProcessBuilder(binary.toString()).redirectErrorStream(true);
      pb.environment().put("PORT", String.valueOf(port));
      pb.environment().put("FIXTURE_DIR", fixtureDir.toString());
      pb.environment().put("SDK_KEYS_FILE", keys.toString());
      pb.environment().put("QUONFIG_ENVIRONMENT", "development");
      pb.environment().put("SSE_HEARTBEAT_INTERVAL", "1s");
      pb.environment().put("FIXTURE_GENERATION", String.valueOf(generation));
      pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
      Process p = pb.start();
      ApiDelivery d = new ApiDelivery(p);
      long deadline = System.currentTimeMillis() + 15_000;
      while (System.currentTimeMillis() < deadline) {
        if (!p.isAlive()) {
          throw new IllegalStateException("api-delivery (gen=" + generation + ") exited early");
        }
        if (dialOk(port)) {
          Thread.sleep(100);
          return d;
        }
        Thread.sleep(50);
      }
      d.stop();
      throw new IllegalStateException(
          "api-delivery (gen=" + generation + ") did not listen on :" + port + " within 15s");
    }

    private static boolean dialOk(int port) {
      try (Socket s = new Socket("127.0.0.1", port)) {
        return s.isConnected();
      } catch (IOException e) {
        return false;
      }
    }

    void stop() {
      process.destroy();
      try {
        if (!process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) {
          process.destroyForcibly();
        }
      } catch (InterruptedException e) {
        process.destroyForcibly();
        Thread.currentThread().interrupt();
      }
    }
  }

  private static final class ExpState {
    final ChaosScenario.Expectation exp;
    long heldSinceMs;
    boolean passed;
    boolean failed;
    String lastReason = "";

    ExpState(ChaosScenario.Expectation exp) {
      this.exp = exp;
    }
  }
}
