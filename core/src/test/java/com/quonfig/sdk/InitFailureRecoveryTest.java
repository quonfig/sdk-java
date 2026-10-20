package com.quonfig.sdk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.quonfig.sdk.ApiUrlsFailoverWarnTest.RecordingLogger;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.event.Level;

/**
 * A total init failure must not leave a permanently dead client (qfg-9p4i.4; init-failure design
 * 2026-10-05, decision 4 and section E step 1). Every leg failing at boot used to complete the init
 * future exceptionally and stop there: no retry, no SSE, no log line, and every getter returned the
 * caller's default forever, even after {@code refresh()} installed a config.
 *
 * <p>Now the client keeps retrying in the background, logs the failure at WARN or above (section
 * C), and the getters serve the real config once it arrives.
 */
class InitFailureRecoveryTest {

  private final List<HttpServer> servers = new ArrayList<>();

  @AfterEach
  void stopServers() {
    for (HttpServer s : servers) s.stop(0);
    servers.clear();
  }

  private static final String ENVELOPE =
      "{\"configs\":[{\"id\":\"c1\",\"key\":\"my.flag\",\"type\":\"feature_flag\","
          + "\"valueType\":\"bool\",\"default\":{\"rules\":[{\"criteria\":[],"
          + "\"value\":{\"type\":\"bool\",\"value\":true}}]}}],"
          + "\"meta\":{\"version\":\"v3\",\"environment\":\"production\",\"workspaceId\":\"ws\","
          + "\"generation\":3}}";

  /** Config endpoint that answers 503 until {@code up} flips, then the envelope. SSE always 503. */
  private HttpServer server(AtomicBoolean up, AtomicInteger configHits) throws IOException {
    HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    s.setExecutor(Executors.newCachedThreadPool());
    s.createContext(
        "/api/v2/configs",
        (HttpExchange ex) -> {
          configHits.incrementAndGet();
          if (!up.get()) {
            ex.sendResponseHeaders(503, -1);
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

  private static Options options(HttpServer s, RecordingLogger logger) {
    String base = "http://127.0.0.1:" + s.getAddress().getPort();
    return Options.builder()
        .sdkKey("test-sdk")
        .apiUrls(List.of(base, base))
        .streamUrls(List.of(base))
        .telemetryUrl(base)
        // 2.0.0 (qfg-9p4i.9): the constructor blocks until initTimeout and FAIL is the default.
        // CONTINUE returns a client on code defaults, the shape these recovery tests exercise.
        .onInitFailure(OnInitFailure.CONTINUE)
        .initTimeout(Duration.ofMillis(500))
        .disableTelemetry(true)
        .logger(logger)
        // Shrink the background init-retry backoff so the test runs in milliseconds.
        .waitHookForTest((kind, d) -> "init-retry".equals(kind) ? Duration.ofMillis(20) : d)
        .build();
  }

  private static void await(BooleanSupplier cond, String what) throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (System.nanoTime() < deadline) {
      if (cond.getAsBoolean()) return;
      Thread.sleep(10);
    }
    throw new AssertionError("timed out waiting for: " + what);
  }

  @Test
  void totalInitFailure_keepsRetrying_andGettersServeConfigOnceItArrives() throws Exception {
    AtomicBoolean up = new AtomicBoolean(false);
    AtomicInteger hits = new AtomicInteger();
    RecordingLogger logger = new RecordingLogger();
    HttpServer s = server(up, hits);

    try (Quonfig q = new Quonfig(options(s, logger))) {
      assertThrows(ExecutionException.class, () -> q.initFuture().get(5, TimeUnit.SECONDS));
      EvaluationDetails<Boolean> before = q.getBoolDetails("my.flag", false);
      assertEquals(false, before.value());
      assertEquals(Reason.ERROR, before.reason());
      assertFalse(q.ready());

      int hitsAtFailure = hits.get();
      await(() -> hits.get() > hitsAtFailure + 2, "background retries after the failed init");

      up.set(true);
      await(() -> q.getBool("my.flag", false), "getter to serve the real value after recovery");
      assertTrue(q.ready());
      assertNotEquals(Reason.ERROR, q.getBoolDetails("my.flag", false).reason());
      assertTrue(q.initFuture().isDone() && !q.initFuture().isCompletedExceptionally());
    }

    assertTrue(
        logger.entries.stream()
            .anyMatch(
                e ->
                    (e.level == Level.WARN || e.level == Level.ERROR)
                        && e.format.contains("[quonfig] init")
                        && e.args.contains("503")),
        "init failure must log at WARN+ with the leg errors; got " + logger.entries);
    assertTrue(
        logger.entries.stream()
            .anyMatch(e -> e.level == Level.WARN && e.format.contains("init recovered")),
        "recovery must log at WARN (same level that saw the failure); got " + logger.entries);
  }

  @Test
  void refreshAfterTotalInitFailure_makesGettersServeTheInstalledConfig() throws Exception {
    AtomicBoolean up = new AtomicBoolean(false);
    AtomicInteger hits = new AtomicInteger();
    HttpServer s = server(up, hits);

    try (Quonfig q = new Quonfig(options(s, new RecordingLogger()))) {
      assertThrows(ExecutionException.class, () -> q.initFuture().get(5, TimeUnit.SECONDS));
      up.set(true);
      q.refresh();
      assertTrue(q.ready());
      assertTrue(q.getBool("my.flag", false), "refresh() installed a config; getters must use it");
    }
  }

  @Test
  void close_stopsTheBackgroundInitRetry() throws Exception {
    AtomicBoolean up = new AtomicBoolean(false);
    AtomicInteger hits = new AtomicInteger();
    HttpServer s = server(up, hits);

    Quonfig q = new Quonfig(options(s, new RecordingLogger()));
    assertThrows(ExecutionException.class, () -> q.initFuture().get(5, TimeUnit.SECONDS));
    q.close();
    Thread.sleep(100);
    int afterClose = hits.get();
    Thread.sleep(300);
    assertEquals(afterClose, hits.get(), "no config fetches after close()");
  }
}
