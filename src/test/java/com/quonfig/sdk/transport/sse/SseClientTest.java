package com.quonfig.sdk.transport.sse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests the SSE transport against a real in-process HTTP server. We use the JDK's built-in
 * com.sun.net.httpserver.HttpServer rather than mocks because SSE behavior (chunked writes, abrupt
 * disconnects, keepalive comments) is what we actually need to verify.
 *
 * <p>Mirrors the contract validated by sdk-go/sse_client_test.go.
 */
class SseClientTest {

  private HttpServer server;
  private SseClient client;

  @BeforeEach
  void setUp() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
  }

  @AfterEach
  void tearDown() {
    if (client != null) {
      client.stop();
    }
    if (server != null) {
      server.stop(0);
    }
  }

  private String urlFor(String path) {
    return "http://127.0.0.1:" + server.getAddress().getPort() + path;
  }

  /** Writes an SSE frame: id: <version>\ndata: <json>\n\n. Matches api-delivery output. */
  private static void writeFrame(OutputStream out, String version, String json) throws IOException {
    String frame = "id: " + version + "\ndata: " + json + "\n\n";
    out.write(frame.getBytes(StandardCharsets.UTF_8));
    out.flush();
  }

  @Test
  void receivesEventsAndReconnects() throws Exception {
    AtomicInteger attempts = new AtomicInteger(0);
    BlockingQueue<JsonNode> received = new LinkedBlockingQueue<>();

    HttpHandler handler =
        (HttpExchange ex) -> {
          // Verify auth + accept + version headers on every connection.
          String auth = ex.getRequestHeaders().getFirst("Authorization");
          String expected = "Basic " + Base64.getEncoder().encodeToString("1:test-key".getBytes());
          if (auth == null || !auth.equals(expected)) {
            ex.sendResponseHeaders(401, -1);
            ex.close();
            return;
          }
          String accept = ex.getRequestHeaders().getFirst("Accept");
          if (!"text/event-stream".equals(accept)) {
            ex.sendResponseHeaders(400, -1);
            ex.close();
            return;
          }
          String ver = ex.getRequestHeaders().getFirst("X-Quonfig-SDK-Version");
          if (ver == null || !ver.startsWith("java-")) {
            ex.sendResponseHeaders(400, -1);
            ex.close();
            return;
          }

          ex.getResponseHeaders().set("Content-Type", "text/event-stream");
          ex.getResponseHeaders().set("Cache-Control", "no-cache");
          ex.sendResponseHeaders(200, 0);
          OutputStream out = ex.getResponseBody();

          int n = attempts.incrementAndGet();
          try {
            switch (n) {
              case 1:
                writeFrame(out, "v1", "{\"meta\":{\"version\":\"v1\"},\"value\":\"one\"}");
                writeFrame(out, "v2", "{\"meta\":{\"version\":\"v2\"},\"value\":\"two\"}");
                break;
              case 2:
                writeFrame(out, "v3", "{\"meta\":{\"version\":\"v3\"},\"value\":\"three\"}");
                break;
              default:
                // Hold open until client disconnects.
                try {
                  Thread.sleep(2000);
                } catch (InterruptedException ignored) {
                  Thread.currentThread().interrupt();
                }
                break;
            }
          } catch (IOException ignored) {
            // Client disconnected mid-write.
          } finally {
            ex.close();
          }
        };

    server.createContext("/api/v2/sse/config", handler);
    server.start();

    BlockingQueue<Boolean> states = new LinkedBlockingQueue<>();

    client =
        SseClient.builder()
            .url(urlFor("/api/v2/sse/config"))
            .apiKey("test-key")
            .userAgent("java-0.0.1")
            .onEnvelope(received::add)
            .onStateChange(states::add)
            .initialDelay(Duration.ofMillis(5))
            .maxDelay(Duration.ofMillis(50))
            .build();
    client.start();

    List<JsonNode> got = new ArrayList<>();
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (got.size() < 3 && System.nanoTime() < deadline) {
      JsonNode env = received.poll(200, TimeUnit.MILLISECONDS);
      if (env != null) got.add(env);
    }
    assertEquals(3, got.size(), "expected 3 envelopes; attempts=" + attempts.get());
    assertEquals("one", got.get(0).get("value").asText());
    assertEquals("two", got.get(1).get("value").asText());
    assertEquals("three", got.get(2).get("value").asText());

    assertTrue(attempts.get() >= 2, "expected reconnect, got " + attempts.get() + " attempts");

    boolean sawConnected = false;
    while (!states.isEmpty()) {
      if (Boolean.TRUE.equals(states.poll())) sawConnected = true;
    }
    assertTrue(sawConnected, "expected at least one onStateChange(true)");
  }

  @Test
  void ignoresKeepaliveComments() throws Exception {
    BlockingQueue<JsonNode> received = new LinkedBlockingQueue<>();
    AtomicInteger callbacks = new AtomicInteger(0);

    server.createContext(
        "/api/v2/sse/config",
        (HttpExchange ex) -> {
          ex.getResponseHeaders().set("Content-Type", "text/event-stream");
          ex.sendResponseHeaders(200, 0);
          OutputStream out = ex.getResponseBody();
          try {
            out.write(": keepalive\n\n".getBytes(StandardCharsets.UTF_8));
            out.flush();
            out.write(": another keepalive\n\n".getBytes(StandardCharsets.UTF_8));
            out.flush();
            writeFrame(out, "v1", "{\"meta\":{\"version\":\"v1\"},\"value\":\"real\"}");
            out.write(": keepalive\n\n".getBytes(StandardCharsets.UTF_8));
            out.flush();
            // Hold open.
            Thread.sleep(2000);
          } catch (IOException | InterruptedException ignored) {
            Thread.currentThread().interrupt();
          } finally {
            ex.close();
          }
        });
    server.start();

    client =
        SseClient.builder()
            .url(urlFor("/api/v2/sse/config"))
            .apiKey("test-key")
            .onEnvelope(
                env -> {
                  callbacks.incrementAndGet();
                  received.add(env);
                })
            .initialDelay(Duration.ofMillis(5))
            .maxDelay(Duration.ofMillis(50))
            .build();
    client.start();

    JsonNode env = received.poll(3, TimeUnit.SECONDS);
    assertNotNull(env, "expected at least one envelope past the keepalive comments");
    assertEquals("real", env.get("value").asText());

    // Give time for any spurious callbacks to fire.
    Thread.sleep(150);
    assertEquals(1, callbacks.get(), "keepalive comments must not trigger envelope callback");
  }

  @Test
  void stopUnblocksWhileConnected() throws Exception {
    server.createContext(
        "/api/v2/sse/config",
        (HttpExchange ex) -> {
          ex.getResponseHeaders().set("Content-Type", "text/event-stream");
          ex.sendResponseHeaders(200, 0);
          // Hold open until client disconnects.
          try {
            Thread.sleep(10_000);
          } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
          } finally {
            ex.close();
          }
        });
    server.start();

    client =
        SseClient.builder()
            .url(urlFor("/api/v2/sse/config"))
            .apiKey("test-key")
            .onEnvelope(env -> {})
            .initialDelay(Duration.ofMillis(5))
            .build();
    client.start();

    // Let the client establish the connection.
    Thread.sleep(150);

    long t0 = System.nanoTime();
    client.stop();
    long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);
    assertTrue(elapsedMs < 3000, "stop() blocked for " + elapsedMs + "ms");
  }
}
