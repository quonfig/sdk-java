package com.quonfig.sdk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Customer-visible health primitives on the {@link Quonfig} client (qfg-47c2.23). The supervisor's
 * Tier 1 test 6 covers raw enum transitions; this suite covers the client-level surface that
 * customers actually call: that state transitions through INITIALIZING → CONNECTED on SSE connect
 * and on static modes, and that {@link Quonfig#lastSuccessfulRefresh()} stamps a fresh wall-clock
 * time on each install.
 */
class QuonfigHealthPrimitivesTest {

  private final List<HttpServer> servers = new ArrayList<>();

  @AfterEach
  void stopServers() {
    for (HttpServer s : servers) s.stop(0);
    servers.clear();
  }

  private HttpServer start(HttpHandler getHandler, HttpHandler sseHandler) throws IOException {
    HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    s.createContext("/api/v2/configs", getHandler);
    s.createContext("/api/v2/sse/config", sseHandler);
    s.start();
    servers.add(s);
    return s;
  }

  @Test
  void datadirMode_reportsConnectedAndStampsRefreshOnConstruction(@TempDir Path tmp)
      throws IOException {
    // Empty workspace shape — DatadirLoader is happy as long as the dirs exist.
    Files.createDirectories(tmp.resolve("configs"));
    Files.createDirectories(tmp.resolve("feature-flags"));
    Files.createDirectories(tmp.resolve("segments"));
    Files.createDirectories(tmp.resolve("log-levels"));
    Files.createDirectories(tmp.resolve("schemas"));

    Instant before = Instant.now().minusSeconds(1);
    Options o =
        Options.builder()
            .datadir(tmp.toString())
            .environment("production")
            .disableTelemetry(true)
            .build();
    try (Quonfig q = new Quonfig(o)) {
      assertEquals(
          ConnectionState.CONNECTED,
          q.connectionState(),
          "datadir mode loads synchronously and serves rows; treat as CONNECTED");
      Instant stamp = q.lastSuccessfulRefresh();
      assertNotNull(stamp, "install must stamp lastSuccessfulRefresh");
      assertFalse(stamp.isBefore(before), "stamp = " + stamp + ", want >= " + before);
      assertFalse(
          stamp.isAfter(Instant.now().plusSeconds(1)), "stamp = " + stamp + ", want <= now+1s");
    }
  }

  @Test
  void httpMode_initializingBeforeFirstFetch_connectedAfterSse() throws Exception {
    BlockingQueue<Boolean> sseStates = new LinkedBlockingQueue<>();

    HttpHandler getHandler =
        (HttpExchange ex) -> {
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
          ex.getResponseHeaders().add("Content-Type", "text/event-stream");
          ex.sendResponseHeaders(200, 0);
          try (OutputStream out = ex.getResponseBody()) {
            out.write(":ok\n\n".getBytes(StandardCharsets.UTF_8));
            out.flush();
            for (int i = 0; i < 600; i++) {
              try {
                Thread.sleep(100);
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
              }
            }
          } catch (IOException ignored) {
            // expected when client disconnects
          }
        };

    HttpServer server = start(getHandler, sseHandler);
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
            .onSseConnectionStateChange(sseStates::offer)
            .build();

    Quonfig q = new Quonfig(o);
    try {
      // Wait for SSE to connect. Once it does, the supervisor must report CONNECTED.
      q.initFuture().get(5, TimeUnit.SECONDS);
      Boolean connected = sseStates.poll(5, TimeUnit.SECONDS);
      assertTrue(Boolean.TRUE.equals(connected), "SSE should connect to the local server");

      assertEquals(
          ConnectionState.CONNECTED,
          q.connectionState(),
          "after SSE connect callback fires, connectionState() must be CONNECTED");
      Instant stamp = q.lastSuccessfulRefresh();
      assertNotNull(stamp, "initial HTTP fetch installs an envelope; must stamp refresh");
    } finally {
      q.close();
    }
  }

  @Test
  void httpMode_unreachableBackend_staysInitializing() {
    // Init never completes; no install happens; lastSuccessfulRefresh stays null and the
    // state stays at the pre-supervisor default (INITIALIZING).
    Options o =
        Options.builder()
            .sdkKey("test-sdk")
            .apiUrls(List.of("http://127.0.0.1:1"))
            .telemetryUrl("http://127.0.0.1:1")
            .environment("production")
            .initTimeout(Duration.ofMillis(50))
            .disableTelemetry(true)
            .build();
    try (Quonfig q = new Quonfig(o)) {
      assertEquals(
          ConnectionState.INITIALIZING,
          q.connectionState(),
          "before any successful install, connectionState() must be INITIALIZING");
      assertNull(q.lastSuccessfulRefresh(), "no install yet — lastSuccessfulRefresh must be null");
    }
  }
}
