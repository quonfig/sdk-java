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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Drives the Quonfig client in HTTP+SSE mode (the third construction mode beyond datadir/datafile)
 * to verify the lifecycle requirements from qfg-mol-1q2:
 *
 * <ul>
 *   <li>Init timeout: a 10ms timeout against an unreachable URL surfaces as ERROR on every typed
 *       getter (caller's default value still returned).
 *   <li>close() cleanly tears down the background SSE thread within a bounded time.
 * </ul>
 */
class QuonfigLifecycleTest {

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
  void initTimeout_unreachableUrl_getterReturnsDefaultWithErrorReason() {
    // Port 1 is reserved (TCPMUX) and reliably unreachable for HTTP from a test
    // host; with a 10ms init timeout the background fetch can't complete before
    // the getter trips the timeout.
    Options o =
        Options.builder()
            .sdkKey("test-sdk")
            .apiUrls(List.of("http://127.0.0.1:1"))
            .telemetryUrl("http://127.0.0.1:1")
            .environment("production")
            .initTimeout(Duration.ofMillis(10))
            .disableTelemetry(true)
            .build();
    try (Quonfig q = new Quonfig(o)) {
      EvaluationDetails<String> d = q.getStringDetails("any-key", "fallback");
      assertEquals("fallback", d.value());
      assertEquals(Reason.ERROR, d.reason());
      assertNotNull(d.errorCode(), "errorCode must be set on init-timeout ERROR");
      assertNotNull(d.errorMessage(), "errorMessage must accompany errorCode");
      // Plain getter returns the caller's default rather than throwing.
      assertEquals("fallback", q.getString("any-key", "fallback"));
    }
  }

  @Test
  void close_shutsDownSseThread_withinBoundedTime() throws Exception {
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
            // Keepalive comment to confirm the connection is "live" — then
            // hold the response open until the client closes its side.
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
            // expected when the client disconnects
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
      q.initFuture().get(5, TimeUnit.SECONDS);
      Boolean connected = sseStates.poll(5, TimeUnit.SECONDS);
      assertTrue(Boolean.TRUE.equals(connected), "SSE should connect to the local server");
    } finally {
      long start = System.nanoTime();
      q.close();
      long elapsedMs = (System.nanoTime() - start) / 1_000_000L;
      // SseClient.stop() bounds itself at 5s; close() shouldn't exceed that.
      assertTrue(elapsedMs < 6_000, "close() must shut SSE down quickly; took " + elapsedMs + "ms");
    }
  }
}
