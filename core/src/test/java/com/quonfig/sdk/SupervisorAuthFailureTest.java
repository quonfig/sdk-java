package com.quonfig.sdk;

import static org.junit.jupiter.api.Assertions.fail;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import java.util.function.Predicate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.Marker;
import org.slf4j.event.Level;
import org.slf4j.helpers.AbstractLogger;
import org.slf4j.helpers.MessageFormatter;

/**
 * Supervisor contract Test 8 — auth-class response (integration-test-data
 * chaos/supervisor-test-contract.md at v2026.10.16; plan
 * project/plans/2026-10-07-sdk-auth-failure-rule.md R1-R6; bead qfg-goi1.10.27). Tests 1-6 live in
 * {@link com.quonfig.sdk.supervisor.SupervisorTest}; Test 8 sits in this package because it drives
 * the whole client through the package-private {@link Options.Builder#waitHookForTest} seam.
 *
 * <p>The rig follows the contract's fixture rule: the supervisor, the SSE client, the
 * fallback-poller schedule and the auth-failed state machine are all real. Only these are faked:
 *
 * <ul>
 *   <li>the SSE worker's server: a local server whose status per connect attempt the test scripts;
 *   <li>the config fetch legs: one local server per leg, each a per-leg function returning (status,
 *       body) for a given fetch cycle;
 *   <li>the clock: every background wait the client requests goes through the wait hook, which
 *       records the requested duration and waits 1/{@value #COMPRESSION} of it, so the test asserts
 *       the requested values (anti-vacuity rule) without sleeping minutes.
 * </ul>
 *
 * <p>Everything lands in one ordered event log (waits, server hits, log records), so the assertions
 * cut it at the entry ERROR and the exit INFO. Java has no SSE-disabled polling mode, so the
 * contract's polling-mode clause ({@code max(T, poll interval)}) does not apply here.
 */
class SupervisorAuthFailureTest {

  static final String KEY = "t8-secret-sdk-key";
  static final long COMPRESSION = 2000;
  static final Duration PROBE_MIN = Duration.ofSeconds(150);
  static final Duration PROBE_MAX = Duration.ofSeconds(300);
  static final Duration THRESHOLD = Duration.ofSeconds(120);
  static final Duration INTERVAL = Duration.ofSeconds(60);

  /** One ordered rig event. */
  static final class Event {
    final String kind; // "wait:<kind>", "hit:sse", "hit:0", "hit:1", "log"
    final Duration d;
    final Level level;
    final String msg;
    final ConnectionState state;
    final boolean active;

    Event(String kind, Duration d, Level level, String msg, ConnectionState state, boolean active) {
      this.kind = kind;
      this.d = d;
      this.level = level;
      this.msg = msg;
      this.state = state;
      this.active = active;
    }

    @Override
    public String toString() {
      if (kind.equals("log")) return "log " + level + " " + msg;
      if (kind.startsWith("wait:")) return kind + " " + d;
      return kind + " state=" + state + " active=" + active;
    }
  }

  /** One leg's scripted answer for one fetch cycle. */
  static final class Resp {
    final int status; // 200 sends a valid envelope at gen; 304 sends Not Modified
    final int gen;
    final long delayMs; // sleep before answering (timeout sub-tests)
    final boolean exit; // mark the rig "exited" when served (SSE then answers sseAfter)

    Resp(int status, int gen, long delayMs, boolean exit) {
      this.status = status;
      this.gen = gen;
      this.delayMs = delayMs;
      this.exit = exit;
    }

    static Resp of(int status) {
      return new Resp(status, 0, 0, false);
    }

    static Resp ok(int gen) {
      return new Resp(200, gen, 0, false);
    }
  }

  private final List<Rig> rigs = new ArrayList<>();

  @AfterEach
  void tearDown() {
    for (Rig r : rigs) r.close();
    rigs.clear();
  }

  final class Rig implements AutoCloseable {
    final List<Event> events = new ArrayList<>();
    final AtomicBoolean exited = new AtomicBoolean();
    final AtomicInteger leg0Calls = new AtomicInteger();
    final List<HttpServer> servers = new ArrayList<>();
    final ExecutorService exec = Executors.newCachedThreadPool();
    volatile Quonfig client;
    int sseStatus;
    int sseAfter; // 0 = same as sseStatus
    // leg.apply(leg, cycle): cycle counts primary-leg requests, so cycle 1 is the init fetch.
    BiFunction<Integer, Integer, Resp> leg;

    Rig(int sseStatus) {
      this.sseStatus = sseStatus;
      rigs.add(this);
    }

    void record(Event e) {
      synchronized (events) {
        events.add(e);
      }
    }

    List<Event> snapshot() {
      synchronized (events) {
        return new ArrayList<>(events);
      }
    }

    Duration waitHook(String kind, Duration d) {
      record(new Event("wait:" + kind, d, null, null, null, false));
      return d.dividedBy(COMPRESSION);
    }

    void recordHit(String kind) {
      Quonfig q = client;
      ConnectionState st = q != null ? q.connectionState() : null;
      boolean active = q != null && q.fallbackPollerActiveForTest();
      record(new Event(kind, null, null, null, st, active));
    }

    private HttpServer server(String path, com.sun.net.httpserver.HttpHandler h)
        throws IOException {
      HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      s.createContext(path, h);
      s.setExecutor(exec);
      s.start();
      servers.add(s);
      return s;
    }

    private void sseHandler(HttpExchange ex) throws IOException {
      recordHit("hit:sse");
      int status = exited.get() && sseAfter != 0 ? sseAfter : sseStatus;
      ex.sendResponseHeaders(status, -1);
      ex.close();
    }

    private void legHandler(int legIndex, HttpExchange ex) throws IOException {
      int cycle = legIndex == 0 ? leg0Calls.incrementAndGet() : leg0Calls.get();
      recordHit("hit:" + legIndex);
      Resp resp = leg.apply(legIndex, cycle);
      if (resp.delayMs > 0) {
        try {
          Thread.sleep(resp.delayMs);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
      }
      if (resp.exit) exited.set(true);
      try {
        if (resp.status == 200) {
          byte[] body =
              ("{\"configs\":[],\"meta\":{\"version\":\"gen-"
                      + resp.gen
                      + "\",\"environment\":\"Production\",\"generation\":"
                      + resp.gen
                      + "}}")
                  .getBytes(StandardCharsets.UTF_8);
          ex.getResponseHeaders().add("Content-Type", "application/json");
          ex.sendResponseHeaders(200, body.length);
          try (OutputStream out = ex.getResponseBody()) {
            out.write(body);
          }
        } else {
          ex.sendResponseHeaders(resp.status, -1);
        }
      } catch (IOException ignored) {
        // The client gave up on this leg (timeout sub-test).
      } finally {
        ex.close();
      }
    }

    Quonfig start(int legs, Duration configFetchTimeout) throws IOException {
      HttpServer sse = server("/api/v2/sse/config", this::sseHandler);
      String sseBase = "http://127.0.0.1:" + sse.getAddress().getPort();
      List<String> urls = new ArrayList<>();
      for (int i = 0; i < legs; i++) {
        final int li = i;
        HttpServer s = server("/api/v2/configs", ex -> legHandler(li, ex));
        urls.add("http://127.0.0.1:" + s.getAddress().getPort());
      }
      Options.Builder b =
          Options.builder()
              .sdkKey(KEY)
              .apiUrls(urls)
              .streamUrls(List.of(sseBase))
              .telemetryUrl(sseBase)
              .disableTelemetry(true)
              .initTimeout(Duration.ofSeconds(8))
              .logger(new RigLogger(this))
              .waitHookForTest(this::waitHook);
      if (configFetchTimeout != null) b.configFetchTimeout(configFetchTimeout);
      Quonfig q = new Quonfig(b.build());
      client = q;
      return q;
    }

    Quonfig start(int legs) throws IOException {
      return start(legs, null);
    }

    /** Polls cond for up to 20s of wall clock. */
    List<Event> await(String what, Predicate<List<Event>> cond) {
      long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
      while (System.nanoTime() < deadline) {
        List<Event> ev = snapshot();
        if (cond.test(ev)) return ev;
        try {
          Thread.sleep(2);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          break;
        }
      }
      List<Event> ev = snapshot();
      fail("timed out waiting for " + what + "; events:\n" + dump(ev));
      return ev;
    }

    @Override
    public void close() {
      Quonfig q = client;
      if (q != null) q.close();
      for (HttpServer s : servers) s.stop(0);
      exec.shutdownNow();
    }
  }

  /** SLF4J logger that appends every record (formatted) to the rig's event log. */
  static final class RigLogger extends AbstractLogger {
    private final transient Rig rig;

    RigLogger(Rig rig) {
      this.rig = rig;
    }

    @Override
    protected String getFullyQualifiedCallerName() {
      return RigLogger.class.getName();
    }

    @Override
    protected void handleNormalizedLoggingCall(
        Level level, Marker marker, String pattern, Object[] args, Throwable t) {
      String msg = MessageFormatter.basicArrayFormat(pattern, args);
      rig.record(new Event("log", null, level, msg, null, false));
    }

    @Override
    public boolean isTraceEnabled() {
      return true;
    }

    @Override
    public boolean isTraceEnabled(Marker marker) {
      return true;
    }

    @Override
    public boolean isDebugEnabled() {
      return true;
    }

    @Override
    public boolean isDebugEnabled(Marker marker) {
      return true;
    }

    @Override
    public boolean isInfoEnabled() {
      return true;
    }

    @Override
    public boolean isInfoEnabled(Marker marker) {
      return true;
    }

    @Override
    public boolean isWarnEnabled() {
      return true;
    }

    @Override
    public boolean isWarnEnabled(Marker marker) {
      return true;
    }

    @Override
    public boolean isErrorEnabled() {
      return true;
    }

    @Override
    public boolean isErrorEnabled(Marker marker) {
      return true;
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Event-log helpers
  // ---------------------------------------------------------------------------------------------

  static int count(List<Event> ev, String kind) {
    int n = 0;
    for (Event e : ev) if (e.kind.equals(kind)) n++;
    return n;
  }

  /** Index of the nth (1-based) event of kind at or after from, or -1. */
  static int index(List<Event> ev, int from, String kind, int nth) {
    if (from < 0) return -1;
    for (int i = from; i < ev.size(); i++) {
      if (ev.get(i).kind.equals(kind) && --nth == 0) return i;
    }
    return -1;
  }

  /** Index of the first log record at level at or after from, or -1. */
  static int logIndex(List<Event> ev, int from, Level level) {
    if (from < 0) return -1;
    for (int i = from; i < ev.size(); i++) {
      Event e = ev.get(i);
      if (e.kind.equals("log") && e.level == level) return i;
    }
    return -1;
  }

  static List<String> logs(List<Event> ev, Level level) {
    List<String> out = new ArrayList<>();
    for (Event e : ev) if (e.kind.equals("log") && e.level == level) out.add(e.msg);
    return out;
  }

  static List<Duration> waits(List<Event> ev, String kind) {
    List<Duration> out = new ArrayList<>();
    for (Event e : ev) if (e.kind.equals("wait:" + kind)) out.add(e.d);
    return out;
  }

  static String dump(List<Event> ev) {
    StringBuilder b = new StringBuilder();
    for (int i = 0; i < ev.size(); i++) {
      b.append(String.format("%4d %s%n", i, ev.get(i)));
    }
    return b.toString();
  }

  /**
   * The entering cycle's primary hit: the first primary-leg request after SSE started (cycle 1 is
   * the init fetch, which runs before SSE).
   */
  static int enteringCycle(List<Event> ev) {
    int firstSse = index(ev, 0, "hit:sse", 1);
    if (firstSse < 0) fail("SSE never attempted; events:\n" + dump(ev));
    int i = index(ev, firstSse, "hit:0", 1);
    if (i < 0) fail("no fetch cycle after SSE started; events:\n" + dump(ev));
    return i;
  }

  /**
   * Asserts the SSE reconnect sleeps follow the supervisor Test 2 ladder from a fresh start: the
   * kth sleep is the jittered value of base_k = min(500ms * 2^k, 30s), i.e. within [base_k/2,
   * base_k].
   */
  static void checkSseLadder(List<Duration> sleeps, String label, List<String> errors) {
    Duration base = Duration.ofMillis(500);
    for (int k = 0; k < sleeps.size(); k++) {
      Duration w = sleeps.get(k);
      if (w.compareTo(base.dividedBy(2)) < 0 || w.compareTo(base) > 0) {
        errors.add(
            label
                + ": SSE sleep #"
                + (k + 1)
                + " = "
                + w
                + ", want within ["
                + base.dividedBy(2)
                + ", "
                + base
                + "] (normal ladder)");
      }
      base = base.multipliedBy(2);
      if (base.compareTo(Duration.ofSeconds(30)) > 0) base = Duration.ofSeconds(30);
    }
  }

  static boolean inProbeRange(Duration w) {
    return w.compareTo(PROBE_MIN) >= 0 && w.compareTo(PROBE_MAX) <= 0;
  }

  static void assertNoErrors(List<String> errors, List<Event> ev) {
    if (!errors.isEmpty()) {
      fail(String.join("\n", errors) + "\nevents:\n" + dump(ev));
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Test 8 (a)-(c): SSE gets auth-class, then the fallback poller's cycle gets auth-class on every
  // leg -> auth-failed: 6 probes each requested at a wait in [150s, 300s], SSE and the poller off,
  // disconnected, one ERROR, no WARN.
  // ---------------------------------------------------------------------------------------------
  @ParameterizedTest
  @ValueSource(ints = {401, 403, 404, 410})
  void authClassEntersAuthFailedAndProbesSlowly(int status) throws Exception {
    Rig r = new Rig(status);
    r.leg = (leg, cycle) -> cycle == 1 ? Resp.ok(1) : Resp.of(status);
    r.start(2);
    // Entering cycle (2) + 6 probes (3..8), then the 7th probe wait.
    List<Event> ev =
        r.await("6 probes", e -> r.leg0Calls.get() >= 8 && count(e, "wait:probe") >= 7);
    List<String> errors = new ArrayList<>();

    int enter = enteringCycle(ev);
    List<Event> pre = ev.subList(0, enter);
    // (a) before the entering cycle: SSE on its normal ladder, the poller engaged on its existing
    // 120s rule, no other fetch cycle.
    if (count(pre, "hit:sse") < 3) {
      errors.add("only " + count(pre, "hit:sse") + " SSE attempts before the entering cycle");
    }
    checkSseLadder(waits(pre, "sse"), "pre-entry", errors);
    if (!waits(pre, "engage").equals(List.of(THRESHOLD))) {
      errors.add(
          "engage waits before entry = " + waits(pre, "engage") + ", want [" + THRESHOLD + "]");
    }
    int n1 = count(ev.subList(index(ev, 0, "hit:sse", 1), enter), "hit:1");
    if (n1 != 0) errors.add(n1 + " fetch requests between SSE start and the entering cycle");

    int errIdx = logIndex(ev, enter, Level.ERROR);
    if (errIdx < 0) fail("no ERROR logged after the entering cycle; events:\n" + dump(ev));

    // (a) the next 6 probes: requested at waits in [150s, 300s] (no doubling, no stop).
    List<Duration> probes = waits(ev.subList(errIdx, ev.size()), "probe");
    if (probes.size() < 7) fail("probe waits after entry = " + probes + ", want >= 7");
    for (int i = 0; i < 7; i++) {
      if (!inProbeRange(probes.get(i))) {
        errors.add("probe wait #" + (i + 1) + " = " + probes.get(i) + ", want within [150s, 300s]");
      }
    }

    // (b) while auth-failed: no SSE attempt, the poller never engages or ticks, each probe is one
    // cycle across both legs, disconnected throughout.
    int last = index(ev, errIdx, "wait:probe", 7);
    List<Event> window = ev.subList(errIdx, last);
    for (String k : List.of("wait:sse", "wait:engage", "wait:poll")) {
      if (count(window, k) != 0)
        errors.add(count(window, k) + " " + k + " events while auth-failed");
    }
    // A server-side SSE hit can trail the client's stop by a moment, so count SSE hits from the
    // first probe on (6 probes of SSE silence is what a stopped stream means).
    int firstProbe = index(ev, errIdx, "hit:0", 1);
    if (count(ev.subList(firstProbe, last), "hit:sse") != 0) {
      errors.add(
          count(ev.subList(firstProbe, last), "hit:sse") + " SSE attempts while auth-failed");
    }
    for (int p = 1; p <= 6; p++) {
      int a = index(ev, errIdx, "wait:probe", p);
      int b = index(ev, errIdx, "wait:probe", p + 1);
      List<Event> seg = ev.subList(a, b);
      if (count(seg, "hit:0") != 1 || count(seg, "hit:1") != 1) {
        errors.add(
            "probe "
                + p
                + " asked primary "
                + count(seg, "hit:0")
                + " and secondary "
                + count(seg, "hit:1")
                + " times, want 1 and 1");
      }
    }
    for (Event e : window) {
      if ((e.kind.equals("hit:0") || e.kind.equals("hit:1"))
          && (e.state != ConnectionState.DISCONNECTED || e.active)) {
        errors.add(
            "during a probe: connectionState="
                + e.state
                + " fallbackPollerActive="
                + e.active
                + ", want DISCONNECTED/false");
      }
    }

    // (c) from entry through the 6 probes: one ERROR naming the status (never the key), no other
    // ERROR, no WARN.
    List<Event> counted = ev.subList(enter, last);
    List<String> errs = logs(counted, Level.ERROR);
    if (errs.size() != 1 || !errs.get(0).contains(String.valueOf(status))) {
      errors.add("ERROR lines from entry = " + errs + ", want exactly one naming " + status);
    }
    if (!logs(counted, Level.WARN).isEmpty()) {
      errors.add("WARN lines from entry = " + logs(counted, Level.WARN) + ", want none");
    }
    for (Event e : ev) {
      if (e.kind.equals("log") && e.msg.contains(KEY)) {
        errors.add("log line contains the SDK key: " + e.msg);
      }
    }
    assertNoErrors(errors, ev);
  }

  // ---------------------------------------------------------------------------------------------
  // Test 8 (d): a probe that gets a valid 200 on the secondary leg only, or a 304 on any leg,
  // exits: install, one INFO, SSE restarts from its base backoff, the poller returns to its normal
  // schedule.
  // ---------------------------------------------------------------------------------------------
  @ParameterizedTest
  @ValueSource(ints = {401, 403, 404, 410})
  void authFailedExitsOnFirstValidAnswer(int status) throws Exception {
    Object[][] cases = {
      {"secondary-200", Resp.of(status), new Resp(200, 2, 0, true), 2},
      {"primary-304", new Resp(304, 0, 0, true), Resp.of(status), 1},
      {"secondary-304", Resp.of(status), new Resp(304, 0, 0, true), 1},
    };
    for (Object[] tc : cases) {
      String name = (String) tc[0];
      Resp primary = (Resp) tc[1];
      Resp secondary = (Resp) tc[2];
      int wantGen = (Integer) tc[3];

      Rig r = new Rig(status);
      r.sseAfter = 503;
      // cycle 2 enters, cycle 3 is a rejected probe, cycle 4 exits.
      r.leg =
          (leg, cycle) -> {
            if (cycle == 1) return Resp.ok(1);
            if (cycle == 4) return leg == 0 ? primary : secondary;
            return Resp.of(status);
          };
      Quonfig c = r.start(2);
      List<Event> ev =
          r.await(
              name + ": exit and an SSE restart",
              e -> {
                int i = index(e, 0, "wait:probe", 2);
                if (!r.exited.get() || i < 0) return false;
                int h = index(e, i, "hit:sse", 1);
                return h >= 0
                    && index(e, h, "wait:sse", 1) >= 0
                    && index(e, i, "wait:engage", 1) >= 0;
              });
      List<String> errors = new ArrayList<>();
      if (c.heldGeneration() != wantGen) {
        errors.add(name + ": heldGeneration = " + c.heldGeneration() + ", want " + wantGen);
      }
      int enter = enteringCycle(ev);
      int errIdx = logIndex(ev, enter, Level.ERROR);
      int exitIdx = logIndex(ev, errIdx, Level.INFO);
      if (errIdx < 0 || exitIdx < 0) {
        fail(name + ": want an entry ERROR then an exit INFO; events:\n" + dump(ev));
      }
      int restart = index(ev, exitIdx, "hit:sse", 1);
      int firstProbe = index(ev, errIdx, "hit:0", 1);
      if (count(ev.subList(firstProbe, exitIdx), "hit:sse") != 0) {
        errors.add(name + ": SSE attempts while auth-failed");
      }
      if (count(ev.subList(errIdx, exitIdx), "wait:probe") != 2) {
        errors.add(
            name
                + ": "
                + count(ev.subList(errIdx, exitIdx), "wait:probe")
                + " probe waits while auth-failed, want 2 (cycles 3 and 4)");
      }
      List<String> infos = logs(ev.subList(enter, restart), Level.INFO);
      if (infos.size() != 1) {
        errors.add(name + ": INFO lines from entry to the SSE restart = " + infos + ", want one");
      }
      List<String> errs = logs(ev.subList(enter, restart), Level.ERROR);
      if (errs.size() != 1) {
        errors.add(name + ": ERROR lines from entry to exit = " + errs + ", want exactly one");
      }
      // SSE restarts from its normal base: the next ordinary SSE failure requests the Test 2 first
      // backoff.
      List<Duration> afterExit = waits(ev.subList(restart, ev.size()), "sse");
      if (afterExit.isEmpty()) fail(name + ": no SSE sleep after the restart");
      checkSseLadder(afterExit.subList(0, 1), name + " post-exit", errors);
      // The poller is back on its normal rule (engage after 120s).
      List<Duration> engageAfter = waits(ev.subList(exitIdx, ev.size()), "engage");
      if (!engageAfter.equals(List.of(THRESHOLD))) {
        errors.add(
            name + ": engage waits after exit = " + engageAfter + ", want [" + THRESHOLD + "]");
      }
      if (count(ev.subList(exitIdx, ev.size()), "wait:probe") != 0) {
        errors.add(name + ": probe waits after exit, want 0");
      }
      if (c.connectionState() == ConnectionState.FALLING_BACK && !c.fallbackPollerActiveForTest()) {
        errors.add(name + ": connectionState FALLING_BACK with the poller idle");
      }
      assertNoErrors(errors, ev);
      r.close();
    }
  }

  /**
   * Asserts a run never entered auth-failed: no ERROR, no probe, the SSE ladder continues across
   * the poller's cycles, and the poller keeps its normal interval (at least two cycles after
   * engaging).
   */
  static void checkNormalRegime(Rig r) {
    List<Event> ev = r.await("3 poller cycles", e -> r.leg0Calls.get() >= 4);
    List<String> errors = new ArrayList<>();
    if (!logs(ev, Level.ERROR).isEmpty()) {
      errors.add("ERROR lines = " + logs(ev, Level.ERROR) + ", want none");
    }
    if (count(ev, "wait:probe") != 0) {
      errors.add(count(ev, "wait:probe") + " probe waits, want 0 (never auth-failed)");
    }
    checkSseLadder(waits(ev, "sse"), "whole run", errors);
    int firstPoll = enteringCycle(ev);
    if (count(ev.subList(firstPoll, ev.size()), "hit:sse") == 0) {
      errors.add("SSE stopped after the poller's cycle; want the normal ladder to continue");
    }
    if (!waits(ev, "poll").equals(List.of(INTERVAL))) {
      errors.add("poll interval requested = " + waits(ev, "poll") + ", want [" + INTERVAL + "]");
    }
    for (Event e : ev.subList(firstPoll, ev.size())) {
      if (e.kind.equals("hit:0") && e.state != ConnectionState.FALLING_BACK) {
        errors.add("connectionState during a poll = " + e.state + ", want FALLING_BACK");
      }
    }
    assertNoErrors(errors, ev);
  }

  // ---------------------------------------------------------------------------------------------
  // Test 8 (e): SSE auth-class, then a poller cycle with a 200 on any leg: no auth-failed, no
  // ERROR, SSE and the poller stay on their normal schedule.
  // ---------------------------------------------------------------------------------------------
  @ParameterizedTest
  @ValueSource(ints = {401, 403, 404, 410})
  void transientPrimaryRejectionStaysNormal(int status) throws Exception {
    for (String name : List.of("secondary-200", "primary-200")) {
      Rig r = new Rig(status);
      r.leg =
          (leg, cycle) ->
              cycle == 1 || name.equals("primary-200") || leg == 1 ? Resp.ok(1) : Resp.of(status);
      r.start(2);
      checkNormalRegime(r);
      r.close();
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Test 8 (f): primary auth-class + secondary 503 or timeout enters; primary 503 + secondary
  // auth-class does not.
  // ---------------------------------------------------------------------------------------------
  @ParameterizedTest
  @ValueSource(ints = {401, 403, 404, 410})
  void mixedLegs(int status) throws Exception {
    for (String second : List.of("503", "timeout")) {
      Rig r = new Rig(status);
      r.leg =
          (leg, cycle) -> {
            if (cycle == 1) return Resp.ok(1);
            if (leg == 0) return Resp.of(status);
            if (second.equals("timeout")) return new Resp(200, 9, 400, false);
            return Resp.of(503);
          };
      r.start(2, Duration.ofMillis(100));
      List<Event> ev = r.await("a probe after entry", e -> count(e, "wait:probe") >= 2);
      List<String> errors = new ArrayList<>();
      List<String> errs = logs(ev, Level.ERROR);
      if (errs.size() != 1 || !errs.get(0).contains(String.valueOf(status))) {
        errors.add(
            "primary-auth+secondary-"
                + second
                + ": ERROR lines = "
                + errs
                + ", want one naming "
                + status);
      }
      List<Duration> probes = waits(ev, "probe");
      for (int i = 0; i < probes.size(); i++) {
        if (!inProbeRange(probes.get(i))) {
          errors.add(
              "probe wait #" + (i + 1) + " = " + probes.get(i) + ", want within [150s, 300s]");
        }
      }
      assertNoErrors(errors, ev);
      r.close();
    }

    Rig r = new Rig(status);
    r.leg =
        (leg, cycle) -> {
          if (cycle == 1) return Resp.ok(1);
          return leg == 0 ? Resp.of(503) : Resp.of(status);
        };
    r.start(2);
    checkNormalRegime(r);
  }

  // ---------------------------------------------------------------------------------------------
  // Test 8 (g): a 503 on every leg (and on SSE) stays on the normal ladder and never enters
  // auth-failed.
  // ---------------------------------------------------------------------------------------------
  @ParameterizedTest
  @ValueSource(ints = {503})
  void nonAuthStatusStaysOnLadder(int status) throws Exception {
    Rig r = new Rig(status);
    r.leg = (leg, cycle) -> cycle == 1 ? Resp.ok(1) : Resp.of(status);
    r.start(2);
    checkNormalRegime(r);
  }
}
