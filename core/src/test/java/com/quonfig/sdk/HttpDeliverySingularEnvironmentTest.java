package com.quonfig.sdk;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * End-to-end regression for qfg-xpln.1. api-delivery's HTTP {@code /api/v2/configs} serializes each
 * config row scoped to ONE environment using a SINGULAR {@code environment} object plus {@code
 * meta.environment} naming the active env. The consumer pins NO environment — server SDK-key
 * scoping already selected it, and {@code meta.environment} is authoritative.
 *
 * <p>The envelope here has {@code default}=true and {@code environment}(development)=false with
 * {@code meta.environment}="development". A correct client must resolve FALSE (the env override).
 * Before the fix, {@link DatadirLoader#parseConfigNode} ignored the singular block (and the HTTP
 * path never applied {@code meta.environment} to the evaluation environment), so it served TRUE.
 */
class HttpDeliverySingularEnvironmentTest {

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
  void singularEnvironmentOverride_winsOverDefault_noEnvironmentPinned() throws Exception {
    // Delivery wire shape: SINGULAR `environment` object, meta.environment names the active env.
    String envelope =
        "{\"configs\":[{"
            + "\"id\":\"c1\",\"key\":\"my.flag\",\"type\":\"feature_flag\",\"valueType\":\"bool\","
            + "\"default\":{\"rules\":[{\"criteria\":[],"
            + "\"value\":{\"type\":\"bool\",\"value\":true}}]},"
            + "\"environment\":{\"id\":\"development\",\"rules\":[{\"criteria\":[],"
            + "\"value\":{\"type\":\"bool\",\"value\":false}}]}"
            + "}],"
            + "\"meta\":{\"version\":\"v1\",\"environment\":\"development\",\"workspaceId\":\"ws\"}}";

    HttpHandler getHandler =
        (HttpExchange ex) -> {
          byte[] body = envelope.getBytes(StandardCharsets.UTF_8);
          ex.getResponseHeaders().add("Content-Type", "application/json");
          ex.getResponseHeaders().add("ETag", "\"v1\"");
          ex.sendResponseHeaders(200, body.length);
          try (OutputStream out = ex.getResponseBody()) {
            out.write(body);
          }
        };
    // Keep the SSE connection open without sending frames so the initial HTTP install stands.
    HttpHandler sseHandler =
        (HttpExchange ex) -> {
          ex.getResponseHeaders().add("Content-Type", "text/event-stream");
          ex.sendResponseHeaders(200, 0);
          try (OutputStream out = ex.getResponseBody()) {
            out.write(":ok\n\n".getBytes(StandardCharsets.UTF_8));
            out.flush();
            for (int i = 0; i < 100; i++) {
              try {
                Thread.sleep(50);
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
            // NO .environment(...) — server SDK-key scoping + meta.environment select it.
            .initTimeout(Duration.ofSeconds(5))
            .disableTelemetry(true)
            .build();

    try (Quonfig q = new Quonfig(o)) {
      q.initFuture().get(5, TimeUnit.SECONDS);
      Boolean v = q.getBool("my.flag", Boolean.TRUE);
      assertEquals(
          Boolean.FALSE,
          v,
          "development environment override (false) must win over default (true)");
    }
  }
}
