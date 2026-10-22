package com.quonfig.sdk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.quonfig.sdk.ApiUrlsFailoverWarnTest.RecordingLogger;
import com.quonfig.sdk.exceptions.QuonfigInitException;
import com.quonfig.sdk.exceptions.QuonfigInitTimeoutException;
import com.quonfig.sdk.exceptions.QuonfigKeyNotFoundException;
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
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.event.Level;

/**
 * sdk-java 2.0.0 init-failure contract (qfg-9p4i.9; project/plans/2026-10-05-init-failure-design.md
 * decisions 1/4, sections A.3, B and C): {@code onInitFailure} defaults to FAIL, the constructor
 * blocks and retries until {@code initTimeout}, FAIL throws {@link QuonfigInitException} (at once
 * on 401/403 from every leg), WAIT blocks until a config loads, CONTINUE returns on code defaults,
 * getters never block, and a client that survives keeps retrying and heals.
 */
class InitFailureModeTest {

  private static final String ENVELOPE =
      "{\"configs\":[{\"id\":\"c1\",\"key\":\"my.flag\",\"type\":\"feature_flag\","
          + "\"valueType\":\"bool\",\"default\":{\"rules\":[{\"criteria\":[],"
          + "\"value\":{\"type\":\"bool\",\"value\":true}}]}}],"
          + "\"meta\":{\"version\":\"v3\",\"environment\":\"production\",\"workspaceId\":\"ws\","
          + "\"generation\":3}}";

  private final List<HttpServer> servers = new ArrayList<>();
  private final List<Quonfig> clients = new ArrayList<>();

  @AfterEach
  void cleanup() {
    for (Quonfig q : clients) q.close();
    for (HttpServer s : servers) s.stop(0);
  }

  /** Config endpoint answering {@code status} (0 = the envelope). SSE always 503. */
  private HttpServer server(AtomicInteger status, AtomicInteger hits) throws IOException {
    HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    s.setExecutor(Executors.newCachedThreadPool());
    s.createContext(
        "/api/v2/configs",
        (HttpExchange ex) -> {
          hits.incrementAndGet();
          int st = status.get();
          if (st != 0) {
            ex.sendResponseHeaders(st, -1);
            ex.close();
            return;
          }
          byte[] b = ENVELOPE.getBytes(StandardCharsets.UTF_8);
          ex.getResponseHeaders().add("Content-Type", "application/json");
          ex.sendResponseHeaders(200, b.length);
          try (OutputStream out = ex.getResponseBody()) {
            out.write(b);
          }
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

  private static Options.Builder options(HttpServer s, RecordingLogger logger, Duration timeout) {
    String base = "http://127.0.0.1:" + s.getAddress().getPort();
    return Options.builder()
        .sdkKey("test-sdk")
        .apiUrls(List.of(base, base))
        .streamUrls(List.of(base))
        .telemetryUrl(base)
        .initTimeout(timeout)
        .fallbackPollEnabled(false)
        .disableTelemetry(true)
        .logger(logger)
        // Shrink the retry backoff so the tests run in well under a second per retry.
        .waitHookForTest((kind, d) -> "init-retry".equals(kind) ? Duration.ofMillis(50) : d);
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

  @Test
  void defaultIsFail_constructorRetriesUntilInitTimeout_thenThrows_andTheCaughtClientHeals()
      throws Exception {
    AtomicInteger status = new AtomicInteger(503);
    AtomicInteger hits = new AtomicInteger();
    RecordingLogger logger = new RecordingLogger();
    Options o = options(server(status, hits), logger, Duration.ofMillis(1000)).build();
    assertEquals(OnInitFailure.FAIL, o.onInitFailure());

    long start = System.nanoTime();
    QuonfigInitException e = assertThrows(QuonfigInitException.class, () -> new Quonfig(o));
    long elapsed = msSince(start);
    assertNotNull(e.client(), "the failed constructor's client must be reachable");
    clients.add(e.client());

    assertFalse(e instanceof QuonfigInitTimeoutException, e.toString());
    assertEquals(QuonfigInitException.Reason.UNAVAILABLE, e.reason());
    assertTrue(elapsed >= 900, "FAIL must block until initTimeout, raised after " + elapsed);
    assertTrue(elapsed < 4000, "raised after " + elapsed);
    assertTrue(hits.get() > 1, "must retry inside the init window; hits=" + hits.get());
    assertTrue(
        logged(logger, Level.WARN, "[quonfig] init: config not loaded yet"),
        "first failed attempt logs WARN; got " + logger.entries);
    assertTrue(
        logged(logger, Level.ERROR, "onInitFailure=fail: raising QuonfigInitException"),
        "timeout under fail logs ERROR; got " + logger.entries);

    Quonfig q = e.client();
    assertFalse(q.isReady());
    status.set(0);
    assertTrue(q.awaitReady(Duration.ofSeconds(5)), "caught client must keep retrying and heal");
    assertTrue(q.getBool("my.flag", false));
    eventually(() -> logged(logger, Level.WARN, "[quonfig] init recovered"), "recovery WARN line");
  }

  @Test
  void fail_onEveryLegRejectingTheKey_throwsAtOnce() throws Exception {
    AtomicInteger status = new AtomicInteger(401);
    RecordingLogger logger = new RecordingLogger();
    Options o =
        options(server(status, new AtomicInteger()), logger, Duration.ofSeconds(8))
            .onInitFailure(OnInitFailure.FAIL)
            .build();
    long start = System.nanoTime();
    QuonfigInitException e = assertThrows(QuonfigInitException.class, () -> new Quonfig(o));
    clients.add(e.client());
    assertTrue(msSince(start) < 3000, "401 on every leg must fail without waiting the timeout");
    assertEquals(QuonfigInitException.Reason.UNAUTHORIZED, e.reason());
    assertTrue(logged(logger, Level.ERROR, "SDK key rejected"), "" + logger.entries);
  }

  @Test
  void continue_returnsAtInitTimeout_gettersDoNotBlock_andTheClientHeals() throws Exception {
    AtomicInteger status = new AtomicInteger(503);
    RecordingLogger logger = new RecordingLogger();
    AtomicInteger updates = new AtomicInteger();
    Options o =
        options(server(status, new AtomicInteger()), logger, Duration.ofMillis(500))
            .onInitFailure(OnInitFailure.CONTINUE)
            .onConfigUpdate(updates::incrementAndGet)
            .build();
    long start = System.nanoTime();
    Quonfig q = new Quonfig(o);
    clients.add(q);
    long elapsed = msSince(start);
    assertTrue(elapsed >= 400 && elapsed < 3000, "continue returns at initTimeout: " + elapsed);
    assertFalse(q.isReady());

    long getStart = System.nanoTime();
    EvaluationDetails<Boolean> d = q.getBoolDetails("my.flag", false);
    assertTrue(msSince(getStart) < 200, "getters must not block on init");
    assertEquals(false, d.value());
    assertEquals(Reason.ERROR, d.reason());
    assertEquals(ErrorCode.PROVIDER_NOT_READY, d.errorCode());
    assertThrows(QuonfigKeyNotFoundException.class, () -> q.getBoolOrThrow("my.flag"));
    assertTrue(
        logged(logger, Level.ERROR, "onInitFailure=continue: STARTING ON CODE DEFAULTS"),
        "" + logger.entries);

    status.set(0);
    assertTrue(q.awaitReady(Duration.ofSeconds(5)));
    assertTrue(q.getBool("my.flag", false));
    eventually(() -> updates.get() >= 1, "heal fires the config-update (ready) callback");
  }

  @Test
  void wait_blocksPastInitTimeout_untilAConfigLoads() throws Exception {
    AtomicInteger status = new AtomicInteger(503);
    RecordingLogger logger = new RecordingLogger();
    Options o =
        options(server(status, new AtomicInteger()), logger, Duration.ofMillis(300))
            .onInitFailure(OnInitFailure.WAIT)
            .build();
    Thread restore =
        new Thread(
            () -> {
              try {
                Thread.sleep(1500);
              } catch (InterruptedException ignored) {
                return;
              }
              status.set(0);
            });
    restore.start();
    long start = System.nanoTime();
    Quonfig q = new Quonfig(o);
    clients.add(q);
    long elapsed = msSince(start);
    assertTrue(elapsed >= 1400, "wait must keep blocking past initTimeout: " + elapsed);
    assertTrue(q.isReady());
    assertTrue(q.getBool("my.flag", false));
    assertTrue(logged(logger, Level.WARN, "onInitFailure=wait"), "" + logger.entries);
  }

  @Test
  void healthyBoot_constructorReturnsReady_andLogsNothingAboveDebug() throws Exception {
    AtomicInteger status = new AtomicInteger(0);
    RecordingLogger logger = new RecordingLogger();
    Quonfig q =
        new Quonfig(
            options(server(status, new AtomicInteger()), logger, Duration.ofSeconds(5)).build());
    clients.add(q);
    assertTrue(q.isReady(), "the constructor returns only once the first config has loaded");
    assertTrue(q.getBool("my.flag", false));
    assertTrue(q.awaitReady(Duration.ZERO));
    assertFalse(
        logger.entries.stream()
            .anyMatch(
                e ->
                    (e.level == Level.WARN || e.level == Level.ERROR)
                        && e.format.contains("[quonfig] init")),
        "a healthy boot never logs init above DEBUG; got " + logger.entries);
  }

  /**
   * qfg-6vno.11: init sends no If-None-Match, so a 304 to the first fetch is a protocol violation
   * (a misbehaving proxy). It must be a leg error while nothing is installed — not a "ready" client
   * with no store whose getters NPE.
   */
  @Test
  void continue_firstFetch304_isALegError_gettersReturnDefaultNotNpe_andHeals() throws Exception {
    AtomicInteger status = new AtomicInteger(304);
    RecordingLogger logger = new RecordingLogger();
    Options o =
        options(server(status, new AtomicInteger()), logger, Duration.ofMillis(500))
            .onInitFailure(OnInitFailure.CONTINUE)
            .build();
    Quonfig q = new Quonfig(o);
    clients.add(q);
    assertFalse(q.isReady(), "a 304 with nothing installed must not latch readiness");
    assertFalse(
        q.initFuture().isDone() && !q.initFuture().isCompletedExceptionally(),
        "a 304 with nothing installed must not complete initFuture successfully");

    assertEquals(false, q.getBool("my.flag", false));
    EvaluationDetails<Boolean> d = q.getBoolDetails("my.flag", false);
    assertEquals(false, d.value());
    assertEquals(ErrorCode.PROVIDER_NOT_READY, d.errorCode());

    status.set(0);
    assertTrue(q.awaitReady(Duration.ofSeconds(5)), "client keeps retrying and heals");
    assertTrue(q.getBool("my.flag", false));
  }

  @Test
  void fail_firstFetch304OnEveryLeg_throwsQuonfigInitException() throws Exception {
    AtomicInteger status = new AtomicInteger(304);
    RecordingLogger logger = new RecordingLogger();
    Options o =
        options(server(status, new AtomicInteger()), logger, Duration.ofMillis(500))
            .onInitFailure(OnInitFailure.FAIL)
            .build();
    QuonfigInitException e = assertThrows(QuonfigInitException.class, () -> new Quonfig(o));
    clients.add(e.client());
    assertFalse(e.client().isReady());
  }
}
