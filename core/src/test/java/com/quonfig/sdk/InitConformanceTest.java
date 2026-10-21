package com.quonfig.sdk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.quonfig.sdk.ApiUrlsFailoverWarnTest.RecordingLogger;
import com.quonfig.sdk.exceptions.QuonfigInitException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.event.Level;

/**
 * Cross-SDK init-failure conformance (qfg-9p4i.17.3; design doc decisions 6-9, Jeff 2026-10-21):
 * the auth fast-fail set is 401/403/404/410 from every leg, {@code initTimeout <= 0} runs exactly
 * one attempt before applying {@code onInitFailure}, init log lines and the init error never carry
 * URL userinfo or response bodies, the update channel starts at the init decision, and init lines
 * carry an {@code event=quonfig.init.<name>} key-value.
 */
class InitConformanceTest {

  private static final String ENVELOPE =
      "{\"configs\":[{\"id\":\"c1\",\"key\":\"my.flag\",\"type\":\"feature_flag\","
          + "\"valueType\":\"bool\",\"default\":{\"rules\":[{\"criteria\":[],"
          + "\"value\":{\"type\":\"bool\",\"value\":true}}]}}],"
          + "\"meta\":{\"version\":\"v3\",\"environment\":\"production\",\"workspaceId\":\"ws\","
          + "\"generation\":3}}";

  private static final String BODY_SECRET = "UPSTREAM-BODY-SECRET";

  private final List<HttpServer> servers = new ArrayList<>();
  private final List<Quonfig> clients = new ArrayList<>();

  @AfterEach
  void cleanup() {
    for (Quonfig q : clients) {
      if (q != null) q.close();
    }
    blackhole.countDown();
    for (HttpServer s : servers) s.stop(0);
  }

  /** Released at teardown; a blackholed request blocks on it and never answers before. */
  private final java.util.concurrent.CountDownLatch blackhole =
      new java.util.concurrent.CountDownLatch(1);

  /**
   * Config endpoint whose first {@code fastHits} requests answer 503 at once and every later one
   * hangs without answering (a black hole). {@code fastHits} 0 hangs every request.
   */
  private HttpServer fastThenBlackhole(int fastHits) throws IOException {
    AtomicInteger hits = new AtomicInteger();
    HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    s.setExecutor(Executors.newCachedThreadPool());
    s.createContext(
        "/api/v2/configs",
        (HttpExchange ex) -> {
          if (hits.incrementAndGet() > fastHits) {
            try {
              blackhole.await();
            } catch (InterruptedException ignored) {
              // teardown
            }
            ex.close();
            return;
          }
          ex.sendResponseHeaders(503, -1);
          ex.close();
        });
    s.createContext(
        "/api/v2/sse/config",
        (HttpExchange ex) -> {
          ex.sendResponseHeaders(503, -1);
          ex.close();
        });
    s.start();
    servers.add(s);
    return s;
  }

  /**
   * Config endpoint answering {@code status} (0 = the envelope; an error status carries a body that
   * must never reach a log line). The SSE endpoint counts connects and either streams the envelope
   * ({@code sseServes}) or answers 503.
   */
  private HttpServer server(
      AtomicInteger status, AtomicInteger hits, AtomicInteger sseHits, AtomicBoolean sseServes)
      throws IOException {
    HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    s.setExecutor(Executors.newCachedThreadPool());
    s.createContext(
        "/api/v2/configs",
        (HttpExchange ex) -> {
          hits.incrementAndGet();
          int st = status.get();
          byte[] b =
              (st != 0 ? "{\"error\":\"" + BODY_SECRET + "\"}" : ENVELOPE)
                  .getBytes(StandardCharsets.UTF_8);
          ex.getResponseHeaders().add("Content-Type", "application/json");
          ex.sendResponseHeaders(st != 0 ? st : 200, b.length);
          try (OutputStream out = ex.getResponseBody()) {
            out.write(b);
          }
        });
    s.createContext(
        "/api/v2/sse/config",
        (HttpExchange ex) -> {
          sseHits.incrementAndGet();
          if (sseServes == null || !sseServes.get()) {
            ex.sendResponseHeaders(503, -1);
            ex.close();
            return;
          }
          ex.getResponseHeaders().add("Content-Type", "text/event-stream");
          ex.sendResponseHeaders(200, 0);
          OutputStream out = ex.getResponseBody();
          out.write(("data: " + ENVELOPE + "\n\n").getBytes(StandardCharsets.UTF_8));
          out.flush();
          try {
            Thread.sleep(3000);
          } catch (InterruptedException ignored) {
            // test teardown
          }
          out.close();
        });
    s.start();
    servers.add(s);
    return s;
  }

  private HttpServer server(AtomicInteger status) throws IOException {
    return server(status, new AtomicInteger(), new AtomicInteger(), null);
  }

  private static String base(HttpServer s) {
    return "http://127.0.0.1:" + s.getAddress().getPort();
  }

  private static Options.Builder options(
      String apiBase, String streamBase, RecordingLogger logger, Duration timeout) {
    return Options.builder()
        .sdkKey("test-sdk")
        .apiUrls(List.of(apiBase, apiBase))
        .streamUrls(List.of(streamBase))
        .telemetryUrl(streamBase)
        .initTimeout(timeout)
        .configFetchHedgeAbort(Duration.ofMillis(800))
        .fallbackPollEnabled(false)
        .disableTelemetry(true)
        .logger(logger)
        .waitHookForTest((kind, d) -> "init-retry".equals(kind) ? Duration.ofMillis(50) : d);
  }

  private static Options.Builder options(HttpServer s, RecordingLogger logger, Duration timeout) {
    return options(base(s), base(s), logger, timeout);
  }

  private static long msSince(long startNanos) {
    return (System.nanoTime() - startNanos) / 1_000_000L;
  }

  private static void eventually(java.util.function.BooleanSupplier c, String what)
      throws InterruptedException {
    long deadline = System.nanoTime() + 5_000_000_000L;
    while (System.nanoTime() < deadline) {
      if (c.getAsBoolean()) return;
      Thread.sleep(10);
    }
    throw new AssertionError("timed out waiting for: " + what);
  }

  private static boolean logged(RecordingLogger l, Level level, String fragment) {
    return l.entries.stream().anyMatch(e -> e.level == level && e.format.contains(fragment));
  }

  // --- D1: auth class is {401, 403, 404, 410} from every leg -------------------------------

  private void assertAuthFastFail(int status) throws Exception {
    RecordingLogger logger = new RecordingLogger();
    Options o =
        options(server(new AtomicInteger(status)), logger, Duration.ofSeconds(8))
            .onInitFailure(OnInitFailure.FAIL)
            .build();
    long start = System.nanoTime();
    QuonfigInitException e = assertThrows(QuonfigInitException.class, () -> new Quonfig(o));
    clients.add(e.client());
    assertTrue(
        msSince(start) < 3000, status + " on every leg must fail without waiting the timeout");
    assertEquals(QuonfigInitException.Reason.UNAUTHORIZED, e.reason(), "status " + status);
  }

  @Test
  void d1_404OnEveryLeg_isUnauthorized_atOnce() throws Exception {
    assertAuthFastFail(404);
  }

  @Test
  void d1_410OnEveryLeg_isUnauthorized_atOnce() throws Exception {
    assertAuthFastFail(410);
  }

  @Test
  void d1_authClassOnPrimaryOnly_isNotUnauthorized() throws Exception {
    HttpServer primary = server(new AtomicInteger(404));
    HttpServer secondary = server(new AtomicInteger(503));
    RecordingLogger logger = new RecordingLogger();
    Options o =
        options(base(primary), base(primary), logger, Duration.ofMillis(1000))
            .apiUrls(List.of(base(primary), base(secondary)))
            .onInitFailure(OnInitFailure.FAIL)
            .build();
    QuonfigInitException e = assertThrows(QuonfigInitException.class, () -> new Quonfig(o));
    clients.add(e.client());
    assertEquals(QuonfigInitException.Reason.UNAVAILABLE, e.reason());
  }

  // --- D3: initTimeout <= 0 runs exactly one attempt, then applies onInitFailure -----------

  @Test
  void d3_zeroTimeout_fail_runsOneAttemptThenThrowsUnavailable() throws Exception {
    AtomicInteger hits = new AtomicInteger();
    RecordingLogger logger = new RecordingLogger();
    Options o =
        options(
                server(new AtomicInteger(503), hits, new AtomicInteger(), null),
                logger,
                Duration.ZERO)
            .onInitFailure(OnInitFailure.FAIL)
            .build();
    QuonfigInitException e = assertThrows(QuonfigInitException.class, () -> new Quonfig(o));
    clients.add(e.client());
    assertTrue(hits.get() >= 1, "one attempt must run before deciding; hits=" + hits.get());
    assertEquals(
        QuonfigInitException.Reason.UNAVAILABLE,
        e.reason(),
        "an attempt completed with HTTP 503, so the reason is unavailable: " + e.getMessage());
    assertFalse(e.getMessage().contains("no attempt finished"), e.getMessage());
    assertFalse(
        logged(logger, Level.WARN, "initTimeout (0s) <= configFetchHedgeAbort"),
        "initTimeout <= 0 is a documented mode, not a mis-tuning: " + logger.entries);
  }

  @Test
  void d3_negativeTimeout_continue_startsOnDefaults_andRetriesHeal() throws Exception {
    AtomicInteger status = new AtomicInteger(503);
    AtomicInteger hits = new AtomicInteger();
    RecordingLogger logger = new RecordingLogger();
    Options o =
        options(server(status, hits, new AtomicInteger(), null), logger, Duration.ofSeconds(-1))
            .onInitFailure(OnInitFailure.CONTINUE)
            .build();
    Quonfig q = new Quonfig(o);
    clients.add(q);
    assertTrue(hits.get() >= 1, "one attempt must run before deciding");
    assertFalse(q.isReady());
    status.set(0);
    assertTrue(
        q.awaitReady(Duration.ofSeconds(5)),
        "background retries must not be clipped by a <= 0 initTimeout");
    assertTrue(q.getBool("my.flag", false));
  }

  @Test
  void d3_zeroTimeout_healthyBoot_isReady() throws Exception {
    RecordingLogger logger = new RecordingLogger();
    Quonfig q = new Quonfig(options(server(new AtomicInteger(0)), logger, Duration.ZERO).build());
    clients.add(q);
    assertTrue(q.isReady());
    assertTrue(q.getBool("my.flag", false));
  }

  @Test
  void d3_zeroTimeout_wait_keepsRetryingUntilAConfigLoads() throws Exception {
    AtomicInteger status = new AtomicInteger(503);
    RecordingLogger logger = new RecordingLogger();
    Options o =
        options(server(status), logger, Duration.ZERO).onInitFailure(OnInitFailure.WAIT).build();
    Thread restore =
        new Thread(
            () -> {
              try {
                Thread.sleep(1000);
              } catch (InterruptedException ignored) {
                return;
              }
              status.set(0);
            });
    restore.start();
    Quonfig q = new Quonfig(o);
    clients.add(q);
    assertTrue(q.isReady());
  }

  // --- D4: no URL userinfo or upstream bodies in init lines or the init error ---------------

  private static void assertClean(RecordingLogger logger, QuonfigInitException e) {
    for (RecordingLogger.Entry en : logger.entries) {
      String line = en.format + " " + en.args;
      if (!line.contains("init")) continue;
      assertFalse(line.contains("s3cret"), "userinfo leaked into a log line: " + line);
      assertFalse(line.contains(BODY_SECRET), "response body leaked into a log line: " + line);
      assertFalse(line.contains("/api/v2/configs"), "request URL leaked into a log line: " + line);
    }
    if (e != null) {
      assertFalse(e.getMessage().contains("s3cret"), e.getMessage());
      assertFalse(e.getMessage().contains(BODY_SECRET), e.getMessage());
      assertFalse(e.getMessage().contains("/api/v2/configs"), e.getMessage());
      for (Throwable c = e.getCause(); c != null; c = c.getCause()) {
        assertFalse(String.valueOf(c.getMessage()).contains("s3cret"), c.toString());
      }
    }
  }

  @Test
  void d4_authFastFail_errorAndLogs_carryHostAndStatusOnly() throws Exception {
    HttpServer s = server(new AtomicInteger(401));
    String withCreds = "http://user:s3cret@127.0.0.1:" + s.getAddress().getPort();
    RecordingLogger logger = new RecordingLogger();
    Options o =
        options(withCreds, base(s), logger, Duration.ofSeconds(8))
            .onInitFailure(OnInitFailure.FAIL)
            .build();
    QuonfigInitException e = assertThrows(QuonfigInitException.class, () -> new Quonfig(o));
    clients.add(e.client());
    assertEquals(QuonfigInitException.Reason.UNAUTHORIZED, e.reason());
    assertTrue(e.getMessage().contains("HTTP 401"), e.getMessage());
    assertTrue(e.getMessage().contains("127.0.0.1:" + s.getAddress().getPort()), e.getMessage());
    assertClean(logger, e);
  }

  @Test
  void d4_timeoutFail_errorAndLogs_carryHostAndStatusOnly() throws Exception {
    HttpServer s = server(new AtomicInteger(503));
    String withCreds = "http://user:s3cret@127.0.0.1:" + s.getAddress().getPort();
    RecordingLogger logger = new RecordingLogger();
    Options o =
        options(withCreds, base(s), logger, Duration.ofMillis(1000))
            .onInitFailure(OnInitFailure.FAIL)
            .build();
    QuonfigInitException e = assertThrows(QuonfigInitException.class, () -> new Quonfig(o));
    clients.add(e.client());
    assertTrue(e.getMessage().contains("HTTP 503"), e.getMessage());
    assertClean(logger, e);
  }

  // --- I6: the update channel starts at the init decision, not only at heal -----------------

  @Test
  void i6_continue_startsSseAtTheDecision_andAnSseSnapshotHealsPromptly() throws Exception {
    AtomicInteger sseHits = new AtomicInteger();
    AtomicBoolean sseServes = new AtomicBoolean(false);
    HttpServer s = server(new AtomicInteger(503), new AtomicInteger(), sseHits, sseServes);
    RecordingLogger logger = new RecordingLogger();
    Options o =
        options(s, logger, Duration.ofMillis(500))
            .onInitFailure(OnInitFailure.CONTINUE)
            // Background retry backs off for a long time, so a heal can only come from SSE.
            .waitHookForTest((kind, d) -> "init-retry".equals(kind) ? Duration.ofSeconds(30) : d)
            .build();
    sseServes.set(true);
    Quonfig q = new Quonfig(o);
    clients.add(q);
    eventually(() -> sseHits.get() > 0, "SSE connects right after the CONTINUE decision");
    assertTrue(q.awaitReady(Duration.ofSeconds(5)), "an SSE snapshot heals the client");
    assertTrue(q.getBool("my.flag", false));
    eventually(() -> logged(logger, Level.WARN, "[quonfig] init recovered"), "recovery line");
  }

  @Test
  void i6_caughtFail_startsSseAtTheDecision() throws Exception {
    AtomicInteger sseHits = new AtomicInteger();
    HttpServer s = server(new AtomicInteger(503), new AtomicInteger(), sseHits, null);
    RecordingLogger logger = new RecordingLogger();
    Options o =
        options(s, logger, Duration.ofMillis(500))
            .onInitFailure(OnInitFailure.FAIL)
            .waitHookForTest((kind, d) -> "init-retry".equals(kind) ? Duration.ofSeconds(30) : d)
            .build();
    QuonfigInitException e = assertThrows(QuonfigInitException.class, () -> new Quonfig(o));
    assertNotNull(e.client());
    clients.add(e.client());
    eventually(() -> sseHits.get() > 0, "SSE connects right after the FAIL decision");
  }

  // --- I7: init lines carry event=quonfig.init.<name> ---------------------------------------

  @Test
  void i7_initLines_carryEventKeyValue() throws Exception {
    RecordingLogger logger = new RecordingLogger();
    Options o =
        options(server(new AtomicInteger(503)), logger, Duration.ofMillis(500))
            .onInitFailure(OnInitFailure.FAIL)
            .build();
    QuonfigInitException e = assertThrows(QuonfigInitException.class, () -> new Quonfig(o));
    clients.add(e.client());
    assertTrue(logged(logger, Level.DEBUG, "event=quonfig.init.start"), "" + logger.entries);
    assertTrue(logged(logger, Level.WARN, "event=quonfig.init.retrying"), "" + logger.entries);
    assertTrue(logged(logger, Level.ERROR, "event=quonfig.init.failed"), "" + logger.entries);
  }

  @Test
  void i7_unauthorizedAndContinueLines_carryEventKeyValue() throws Exception {
    RecordingLogger logger = new RecordingLogger();
    Options o =
        options(server(new AtomicInteger(403)), logger, Duration.ofSeconds(5))
            .onInitFailure(OnInitFailure.CONTINUE)
            .build();
    Quonfig q = new Quonfig(o);
    clients.add(q);
    assertTrue(logged(logger, Level.ERROR, "event=quonfig.init.unauthorized"), "" + logger.entries);

    RecordingLogger logger2 = new RecordingLogger();
    Options o2 =
        options(server(new AtomicInteger(503)), logger2, Duration.ofMillis(300))
            .onInitFailure(OnInitFailure.CONTINUE)
            .build();
    Quonfig q2 = new Quonfig(o2);
    clients.add(q2);
    assertTrue(logged(logger2, Level.ERROR, "event=quonfig.init.continue"), "" + logger2.entries);
  }

  /**
   * A logger linked against SLF4J 1.x: {@code atLevel} does not exist at runtime. Apps that pin
   * slf4j-api 1.7 (e.g. through the Spring Boot 2 BOM) resolve it over our 2.0 dependency, and the
   * fluent call then throws a LinkageError.
   */
  static final class Slf4j1Logger extends RecordingLogger {
    @Override
    public org.slf4j.spi.LoggingEventBuilder atLevel(Level level) {
      throw new NoSuchMethodError("org.slf4j.Logger.atLevel(Lorg/slf4j/event/Level;)");
    }
  }

  @Test
  void i7_slf4j1Classpath_initStillWorks_andLinesCarryTheEvent() throws Exception {
    RecordingLogger logger = new Slf4j1Logger();
    Options o =
        options(server(new AtomicInteger(503)), logger, Duration.ofMillis(300))
            .onInitFailure(OnInitFailure.CONTINUE)
            .build();
    Quonfig q = new Quonfig(o);
    clients.add(q);
    assertFalse(q.isReady());
    assertTrue(logged(logger, Level.DEBUG, "event=quonfig.init.start"), "" + logger.entries);
    assertTrue(logged(logger, Level.ERROR, "event=quonfig.init.continue"), "" + logger.entries);
  }

  // --- D10: the deadline cuts an attempt still in flight: that attempt is the last one -------

  @Test
  void d10_deadlineCutsAHungAttempt_afterAFastFailure_isTimeout() throws Exception {
    // Attempt 1: both legs 503 at once. Attempt 2 (the background retry, 50ms later) hangs on
    // every leg, and initTimeout cuts it. The cut attempt is the last attempt, none of its legs
    // answered, so it hung: timeout, even though attempt 1 failed fast (decision 10).
    HttpServer s = fastThenBlackhole(2);
    RecordingLogger logger = new RecordingLogger();
    Options o =
        options(s, logger, Duration.ofMillis(1500)).onInitFailure(OnInitFailure.FAIL).build();
    QuonfigInitException e = assertThrows(QuonfigInitException.class, () -> new Quonfig(o));
    clients.add(e.client());
    assertEquals(QuonfigInitException.Reason.TIMEOUT, e.reason(), e.getMessage());
  }

  @Test
  void d10_deadlineDuringBackoff_afterAFastFailure_isUnavailable() throws Exception {
    // Every attempt 503s at once and the retry backoff outlasts initTimeout: the deadline lands
    // between attempts, so the last attempt is the completed fast failure: unavailable.
    RecordingLogger logger = new RecordingLogger();
    Options o =
        options(server(new AtomicInteger(503)), logger, Duration.ofMillis(1000))
            .waitHookForTest((kind, d) -> "init-retry".equals(kind) ? Duration.ofSeconds(30) : d)
            .onInitFailure(OnInitFailure.FAIL)
            .build();
    QuonfigInitException e = assertThrows(QuonfigInitException.class, () -> new Quonfig(o));
    clients.add(e.client());
    assertEquals(QuonfigInitException.Reason.UNAVAILABLE, e.reason(), e.getMessage());
  }

  @Test
  void d10_deadlineCutsAnAttempt_oneLegFastOneHung_isUnavailable() throws Exception {
    // The first attempt is still in flight at the deadline: the primary answered 503 at once and
    // the secondary hangs (its per-leg abort outlasts initTimeout). One leg answered, so the
    // attempt did not hang: unavailable.
    HttpServer primary = server(new AtomicInteger(503));
    HttpServer secondary = fastThenBlackhole(0);
    RecordingLogger logger = new RecordingLogger();
    Options o =
        options(base(primary), base(primary), logger, Duration.ofMillis(1000))
            .apiUrls(List.of(base(primary), base(secondary)))
            .configFetchHedgeAbort(Duration.ofSeconds(10))
            .onInitFailure(OnInitFailure.FAIL)
            .build();
    QuonfigInitException e = assertThrows(QuonfigInitException.class, () -> new Quonfig(o));
    clients.add(e.client());
    assertEquals(QuonfigInitException.Reason.UNAVAILABLE, e.reason(), e.getMessage());
  }
}
