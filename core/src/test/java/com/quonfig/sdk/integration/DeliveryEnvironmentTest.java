// AUTO-GENERATED from integration-test-data/tests/eval/delivery_environment.yaml. DO NOT EDIT.
// Regenerate with:
//   cd integration-test-data/generators && npm run generate -- --target=java
// Source: integration-test-data/generators/src/targets/java.ts

package com.quonfig.sdk.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.quonfig.sdk.Options;
import com.quonfig.sdk.Quonfig;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class DeliveryEnvironmentTest {

  private final List<HttpServer> servers = new ArrayList<>();

  @AfterEach
  void stopServers() {
    for (HttpServer s : servers) s.stop(0);
    servers.clear();
  }

  // Stand up an in-process server returning the literal wire envelope on
  // /api/v2/configs (the shape api-delivery emits in SDK-key mode). The SSE
  // context stays open without frames so the initial HTTP install stands.
  private HttpServer startDeliveryServer(String envelope) throws IOException {
    HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
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
    s.createContext("/api/v2/configs", getHandler);
    s.createContext("/api/v2/sse/config", sseHandler);
    s.start();
    servers.add(s);
    return s;
  }

  @Test
  @DisplayName("singular environment override wins over default when env not pinned")
  void singularEnvironmentOverrideWinsOverDefaultWhenEnvNotPinned() throws Exception {
    String envelope =
        "{\"meta\":{\"version\":\"v1\",\"environment\":\"development\"},\"configs\":[{\"id\":\"c-env\",\"key\":\"flag.env-scoped\",\"type\":\"bool\",\"valueType\":\"bool\",\"sendToClientSdk\":false,\"default\":{\"rules\":[{\"criteria\":[{\"operator\":\"ALWAYS_TRUE\"}],\"value\":{\"type\":\"bool\",\"value\":true}}]},\"environment\":{\"id\":\"development\",\"rules\":[{\"criteria\":[{\"operator\":\"ALWAYS_TRUE\"}],\"value\":{\"type\":\"bool\",\"value\":false}}]}}]}";
    HttpServer server = startDeliveryServer(envelope);
    try {
      String base = "http://127.0.0.1:" + server.getAddress().getPort();
      Options o =
          Options.builder()
              .sdkKey("sdk-test")
              .apiUrls(java.util.List.of(base))
              .streamUrls(java.util.List.of(base))
              .telemetryUrl(base)
              .fallbackPollEnabled(false)
              .initTimeout(java.time.Duration.ofSeconds(5))
              .disableTelemetry(true)
              .build();
      try (Quonfig q = new Quonfig(o)) {
        q.initFuture().get(5, java.util.concurrent.TimeUnit.SECONDS);
        Boolean v = q.getBool("flag.env-scoped", Boolean.TRUE);
        assertEquals(
            Boolean.FALSE, v, "delivery-wire env override: expected false for flag.env-scoped");
      }
    } finally {
      server.stop(0);
    }
  }

  @Test
  @DisplayName(
      "explicit environment pin is ignored in delivery mode (meta.environment authoritative)")
  void explicitEnvironmentPinIsIgnoredInDeliveryModeMetaEnvironmentAuthoritative()
      throws Exception {
    String envelope =
        "{\"meta\":{\"version\":\"v1\",\"environment\":\"development\"},\"configs\":[{\"id\":\"c-env\",\"key\":\"flag.env-scoped\",\"type\":\"bool\",\"valueType\":\"bool\",\"sendToClientSdk\":false,\"default\":{\"rules\":[{\"criteria\":[{\"operator\":\"ALWAYS_TRUE\"}],\"value\":{\"type\":\"bool\",\"value\":true}}]},\"environment\":{\"id\":\"development\",\"rules\":[{\"criteria\":[{\"operator\":\"ALWAYS_TRUE\"}],\"value\":{\"type\":\"bool\",\"value\":false}}]}}]}";
    HttpServer server = startDeliveryServer(envelope);
    try {
      String base = "http://127.0.0.1:" + server.getAddress().getPort();
      Options o =
          Options.builder()
              .sdkKey("sdk-test")
              .apiUrls(java.util.List.of(base))
              .streamUrls(java.util.List.of(base))
              .telemetryUrl(base)
              .fallbackPollEnabled(false)
              .initTimeout(java.time.Duration.ofSeconds(5))
              .disableTelemetry(true)
              .environment("staging")
              .build();
      try (Quonfig q = new Quonfig(o)) {
        q.initFuture().get(5, java.util.concurrent.TimeUnit.SECONDS);
        Boolean v = q.getBool("flag.env-scoped", Boolean.TRUE);
        assertEquals(
            Boolean.FALSE, v, "delivery-wire env override: expected false for flag.env-scoped");
      }
    } finally {
      server.stop(0);
    }
  }

  @Test
  @DisplayName("config without environment block falls back to default in delivery mode")
  void configWithoutEnvironmentBlockFallsBackToDefaultInDeliveryMode() throws Exception {
    String envelope =
        "{\"meta\":{\"version\":\"v1\",\"environment\":\"development\"},\"configs\":[{\"id\":\"c-def\",\"key\":\"flag.default-only\",\"type\":\"bool\",\"valueType\":\"bool\",\"sendToClientSdk\":false,\"default\":{\"rules\":[{\"criteria\":[{\"operator\":\"ALWAYS_TRUE\"}],\"value\":{\"type\":\"bool\",\"value\":true}}]}}]}";
    HttpServer server = startDeliveryServer(envelope);
    try {
      String base = "http://127.0.0.1:" + server.getAddress().getPort();
      Options o =
          Options.builder()
              .sdkKey("sdk-test")
              .apiUrls(java.util.List.of(base))
              .streamUrls(java.util.List.of(base))
              .telemetryUrl(base)
              .fallbackPollEnabled(false)
              .initTimeout(java.time.Duration.ofSeconds(5))
              .disableTelemetry(true)
              .build();
      try (Quonfig q = new Quonfig(o)) {
        q.initFuture().get(5, java.util.concurrent.TimeUnit.SECONDS);
        Boolean v = q.getBool("flag.default-only", Boolean.FALSE);
        assertEquals(
            Boolean.TRUE, v, "delivery-wire env override: expected true for flag.default-only");
      }
    } finally {
      server.stop(0);
    }
  }
}
