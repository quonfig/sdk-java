package com.quonfig.sdk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * {@link Quonfig#lastSuccessfulRefresh()} liveness semantics (qfg-41nh.15; sdk-go qfg-41nh.11
 * parity, sdk-net qfg-41nh.8).
 *
 * <p>An ANSWERED config fetch is a successful refresh whether or not it installs: a healthy client
 * long-parked on same-generation answers (or 304s) is alive, and under-reporting that liveness was
 * the root mechanism behind the sdk-go qfg-sc90 chaos red. Liveness ({@code
 * lastSuccessfulRefresh()}) and config freshness ({@link Quonfig#heldGeneration()}) are separate
 * signals.
 *
 * <ul>
 *   <li>{@code refresh()} answered with a 200 whose envelope the reject-older guard drops
 *       (same/older generation) MUST stamp.
 *   <li>{@code refresh()} answered with a 304 MUST stamp (defensive: the sequential paths send no
 *       ETag today, but the transport accepts 304 as success).
 *   <li>A transport error MUST NOT stamp.
 *   <li>The Layer 2 fallback poller's answered-but-not-installed fetches MUST stamp (chaos scenario
 *       05's recency assert is genuinely satisfiable during an SSE outage).
 * </ul>
 */
class RefreshLivenessTest {

  private final List<HttpServer> servers = new ArrayList<>();

  @AfterEach
  void stopServers() {
    for (HttpServer s : servers) s.stop(0);
    servers.clear();
  }

  private HttpServer startServer(HttpHandler getHandler) throws IOException {
    HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    s.createContext("/api/v2/configs", getHandler);
    // SSE always 503s so no SSE install can advance the stamp behind the test's back.
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

  private static void respond(HttpExchange ex, int generation) throws IOException {
    byte[] body =
        ("{\"configs\":[],\"meta\":{\"version\":\"v"
                + generation
                + "\",\"environment\":\"production\",\"workspaceId\":\"ws\",\"generation\":"
                + generation
                + "}}")
            .getBytes(StandardCharsets.UTF_8);
    ex.getResponseHeaders().add("ETag", "\"v" + generation + "\"");
    ex.sendResponseHeaders(200, body.length);
    try (OutputStream out = ex.getResponseBody()) {
      out.write(body);
    }
  }

  private static Options.Builder optionsFor(String base) {
    return Options.builder()
        .sdkKey("test-sdk")
        .apiUrls(List.of(base))
        .streamUrls(List.of(base))
        .telemetryUrl(base)
        .environment("production")
        .initTimeout(Duration.ofSeconds(5))
        .disableTelemetry(true);
  }

  @Test
  void refresh_sameGeneration200_stampsLastSuccessfulRefresh() throws Exception {
    // Fixed generation: the init fetch installs gen=42; every later answer is a guard no-op.
    HttpServer server = startServer((HttpExchange ex) -> respond(ex, 42));
    String base = "http://127.0.0.1:" + server.getAddress().getPort();

    try (Quonfig q = new Quonfig(optionsFor(base).build())) {
      q.initFuture().get(5, TimeUnit.SECONDS);
      Instant s1 = q.lastSuccessfulRefresh();
      assertNotNull(s1, "init install must stamp");
      int installsAfterInit = q.configInstallCount();

      Thread.sleep(30);
      q.refresh();

      Instant s2 = q.lastSuccessfulRefresh();
      assertTrue(
          s2.isAfter(s1),
          "an answered same-generation 200 is a successful refresh and must stamp; init="
              + s1
              + " afterRefresh="
              + s2);
      assertEquals(
          installsAfterInit,
          q.configInstallCount(),
          "same-generation answer must NOT install (reject-older guard no-op)");
    }
  }

  @Test
  void refresh_answered304_stampsLastSuccessfulRefresh() throws Exception {
    AtomicInteger hits = new AtomicInteger();
    HttpServer server =
        startServer(
            (HttpExchange ex) -> {
              if (hits.incrementAndGet() == 1) {
                respond(ex, 7);
              } else {
                ex.sendResponseHeaders(304, -1);
                ex.close();
              }
            });
    String base = "http://127.0.0.1:" + server.getAddress().getPort();

    try (Quonfig q = new Quonfig(optionsFor(base).build())) {
      q.initFuture().get(5, TimeUnit.SECONDS);
      Instant s1 = q.lastSuccessfulRefresh();
      assertNotNull(s1, "init install must stamp");

      Thread.sleep(30);
      q.refresh();

      Instant s2 = q.lastSuccessfulRefresh();
      assertTrue(
          s2.isAfter(s1),
          "an answered 304 is a successful refresh and must stamp; init="
              + s1
              + " afterRefresh="
              + s2);
    }
  }

  @Test
  void refresh_serverError_doesNotStamp() throws Exception {
    AtomicBoolean fail = new AtomicBoolean(false);
    HttpServer server =
        startServer(
            (HttpExchange ex) -> {
              if (fail.get()) {
                ex.sendResponseHeaders(500, -1);
                ex.close();
              } else {
                respond(ex, 7);
              }
            });
    String base = "http://127.0.0.1:" + server.getAddress().getPort();

    try (Quonfig q = new Quonfig(optionsFor(base).build())) {
      q.initFuture().get(5, TimeUnit.SECONDS);
      Instant s1 = q.lastSuccessfulRefresh();
      assertNotNull(s1, "init install must stamp");

      fail.set(true);
      Thread.sleep(30);
      q.refresh();

      assertEquals(
          s1, q.lastSuccessfulRefresh(), "an errored fetch is NOT a refresh and must not stamp");
    }
  }

  @Test
  void fallbackPoll_sameGeneration200_stampsLastSuccessfulRefresh() throws Exception {
    // SSE never establishes (503) so the Layer 2 poller engages after the compressed
    // threshold; the server pins generation=1, so every poll is answered-but-not-installed.
    AtomicInteger configsHits = new AtomicInteger();
    AtomicInteger configUpdates = new AtomicInteger();
    BlockingQueue<Boolean> fallbackStates = new LinkedBlockingQueue<>();

    HttpServer server =
        startServer(
            (HttpExchange ex) -> {
              configsHits.incrementAndGet();
              respond(ex, 1);
            });
    String base = "http://127.0.0.1:" + server.getAddress().getPort();

    Options o =
        optionsFor(base)
            .fallbackPollThreshold(Duration.ofMillis(150))
            .fallbackPollIntervalMs(100L)
            .onFallbackPollerStateChange(fallbackStates::offer)
            .onConfigUpdate(configUpdates::incrementAndGet)
            .build();

    try (Quonfig q = new Quonfig(o)) {
      q.initFuture().get(5, TimeUnit.SECONDS);
      Instant s1 = q.lastSuccessfulRefresh();
      assertNotNull(s1, "init install must stamp");
      // The init hedge leg completes initFuture BEFORE firing its own onConfigUpdate — wait for
      // that install callback to settle so the baseline below isn't racing it.
      long initCallbackDeadline = System.currentTimeMillis() + 5_000;
      while (configUpdates.get() < 1 && System.currentTimeMillis() < initCallbackDeadline) {
        Thread.sleep(10);
      }
      assertEquals(1, configUpdates.get(), "init install must fire onConfigUpdate exactly once");
      int updatesAfterInit = configUpdates.get();

      Boolean engaged = fallbackStates.poll(30, TimeUnit.SECONDS);
      assertEquals(Boolean.TRUE, engaged, "fallback poller must engage when SSE can't establish");

      // Wait for an answered (same-generation, guard no-op) fallback fetch to advance the stamp.
      long deadline = System.currentTimeMillis() + 30_000;
      Instant s2 = q.lastSuccessfulRefresh();
      while (!s2.isAfter(s1) && System.currentTimeMillis() < deadline) {
        Thread.sleep(20);
        s2 = q.lastSuccessfulRefresh();
      }
      assertTrue(
          s2.isAfter(s1),
          "answered fallback polls must stamp even when the guard installs nothing; init="
              + s1
              + " last="
              + s2
              + " configsHits="
              + configsHits.get());
      assertEquals(
          updatesAfterInit,
          configUpdates.get(),
          "same-generation fallback polls must NOT fire onConfigUpdate");
    }
  }
}
