package com.quonfig.sdk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * End-to-end wiring check for the Layer 2 fallback poller in {@link Quonfig#startSse()} (qfg-
 * 47c2.21). Spins up two local HTTP servers:
 *
 * <ul>
 *   <li>an api-delivery stub on the {@code /api/v2/configs} GET path that counts requests and
 *       returns a tiny valid envelope, and
 *   <li>a separate "SSE" endpoint that always returns 503 — so the SSE loop never establishes and
 *       the fallback poller is forced to engage after the threshold.
 * </ul>
 *
 * <p>The test asserts that the fallback poller actually fires HTTP fetches against the configs
 * endpoint after the threshold elapses, that the {@code onFallbackPollerStateChange} callback fires
 * with {@code true}, and that the SDK-level fetch path goes through (config update listeners
 * trigger).
 */
class QuonfigFallbackPollWiringTest {

  private final List<HttpServer> servers = new ArrayList<>();

  @AfterEach
  void stopServers() {
    for (HttpServer s : servers) s.stop(0);
    servers.clear();
  }

  private HttpServer startServer(HttpHandler getHandler, HttpHandler sseHandler)
      throws IOException {
    HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    s.createContext("/api/v2/configs", getHandler);
    s.createContext("/api/v2/sse/config", sseHandler);
    s.start();
    servers.add(s);
    return s;
  }

  @Test
  void fallbackPollerEngagesAndFetches_whenSseFailsToEstablish() throws Exception {
    AtomicInteger configsHits = new AtomicInteger();
    AtomicInteger configUpdates = new AtomicInteger();
    BlockingQueue<Boolean> fallbackStates = new LinkedBlockingQueue<>();

    HttpHandler getHandler =
        (HttpExchange ex) -> {
          configsHits.incrementAndGet();
          byte[] body =
              ("{\"configs\":[],\"meta\":{\"version\":\"v"
                      + configsHits.get()
                      + "\",\"environment\":\"production\",\"workspaceId\":\"ws\"}}")
                  .getBytes(StandardCharsets.UTF_8);
          ex.getResponseHeaders().add("ETag", "\"v" + configsHits.get() + "\"");
          ex.sendResponseHeaders(200, body.length);
          try (OutputStream out = ex.getResponseBody()) {
            out.write(body);
          }
        };

    // SSE handler always returns 503 — SseClient treats this as a non-2xx and reconnects in a
    // backoff loop. The state callback only fires on transitions; with a 503 there is no
    // setConnected(true) edge, so the fallback poller stays armed by the initial
    // setSseConnected(false) we issue in startSse().
    HttpHandler sseHandler =
        (HttpExchange ex) -> {
          ex.sendResponseHeaders(503, -1);
          ex.close();
        };

    HttpServer server = startServer(getHandler, sseHandler);
    String base = "http://127.0.0.1:" + server.getAddress().getPort();
    Options o =
        Options.builder()
            .sdkKey("test-sdk")
            .apiUrls(List.of(base))
            .streamUrls(List.of(base))
            .telemetryUrl(base)
            .environment("production")
            .initTimeout(Duration.ofSeconds(5))
            .disableTelemetry(true)
            // Engage Layer 2 almost immediately so the test doesn't burn 120s; production
            // default is 120s. Interval kept short so we observe at least one fetch.
            .fallbackPollThreshold(Duration.ofMillis(150))
            .fallbackPollIntervalMs(100L)
            .onFallbackPollerStateChange(fallbackStates::offer)
            .onConfigUpdate(configUpdates::incrementAndGet)
            .build();

    Quonfig q = new Quonfig(o);
    try {
      q.initFuture().get(5, TimeUnit.SECONDS);
      // First GET is the initial fetch in runInit() — discount it.
      int afterInit = configsHits.get();
      int updatesAfterInit = configUpdates.get();

      // Wait for fallback to engage. With threshold=150ms, the SSE 503 → callback false-edge
      // arrives after the first reconnect attempt; meanwhile setSseConnected(false) was called
      // explicitly in startSse(), so the timer is already armed.
      Boolean engaged = fallbackStates.poll(3, TimeUnit.SECONDS);
      assertEquals(
          Boolean.TRUE,
          engaged,
          "fallback poller must engage when SSE can't establish — got " + engaged);

      // After engagement, the poller does one immediate fetch + ticks every interval. Wait for
      // at least one additional configs hit beyond what init did.
      long deadline = System.currentTimeMillis() + 3000;
      while (configsHits.get() <= afterInit && System.currentTimeMillis() < deadline) {
        Thread.sleep(20);
      }
      assertTrue(
          configsHits.get() > afterInit,
          "fallback poller must hit /api/v2/configs after engaging; init="
              + afterInit
              + " final="
              + configsHits.get());
      assertTrue(
          configUpdates.get() > updatesAfterInit,
          "fallback poll fetch must fire onConfigUpdate; init="
              + updatesAfterInit
              + " final="
              + configUpdates.get());
    } finally {
      q.close();
    }
  }

  @Test
  void fallbackPoller_disabled_doesNotFetch() throws Exception {
    AtomicInteger configsHits = new AtomicInteger();
    BlockingQueue<Boolean> fallbackStates = new LinkedBlockingQueue<>();

    HttpHandler getHandler =
        (HttpExchange ex) -> {
          configsHits.incrementAndGet();
          byte[] body =
              ("{\"configs\":[],\"meta\":{\"version\":\"v1\",\"environment\":\"production\","
                      + "\"workspaceId\":\"ws\"}}")
                  .getBytes(StandardCharsets.UTF_8);
          ex.getResponseHeaders().add("ETag", "\"v1\"");
          ex.sendResponseHeaders(200, body.length);
          try (OutputStream out = ex.getResponseBody()) {
            out.write(body);
          }
        };

    HttpHandler sseHandler =
        (HttpExchange ex) -> {
          ex.sendResponseHeaders(503, -1);
          ex.close();
        };

    HttpServer server = startServer(getHandler, sseHandler);
    String base = "http://127.0.0.1:" + server.getAddress().getPort();
    Options o =
        Options.builder()
            .sdkKey("test-sdk")
            .apiUrls(List.of(base))
            .streamUrls(List.of(base))
            .telemetryUrl(base)
            .environment("production")
            .initTimeout(Duration.ofSeconds(5))
            .disableTelemetry(true)
            .fallbackPollEnabled(false)
            .fallbackPollThreshold(Duration.ofMillis(100))
            .onFallbackPollerStateChange(fallbackStates::offer)
            .build();

    Quonfig q = new Quonfig(o);
    try {
      q.initFuture().get(5, TimeUnit.SECONDS);
      int afterInit = configsHits.get();
      // Wait well past the threshold; verify no additional fetches and no engage callback.
      Thread.sleep(500);
      assertEquals(
          afterInit,
          configsHits.get(),
          "fallback disabled must not generate additional HTTP fetches");
      assertEquals(0, fallbackStates.size(), "fallback disabled must not fire engage callback");
    } finally {
      q.close();
    }
  }
}
