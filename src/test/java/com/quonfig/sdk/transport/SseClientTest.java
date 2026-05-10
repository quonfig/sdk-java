package com.quonfig.sdk.transport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.quonfig.sdk.wire.ConfigEnvelope;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
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
import org.junit.jupiter.api.Test;

/**
 * Verifies the SSE transport against an in-process com.sun.net.httpserver. SSE behavior — chunked
 * writes, abrupt disconnects, keepalive comments, basic auth — is exactly what the JDK stub server
 * exercises faithfully, so we drive the real wire format here rather than mock the parser.
 */
class SseClientTest {

  private final List<HttpServer> servers = new ArrayList<>();
  private SseClient client;

  @AfterEach
  void tearDown() {
    if (client != null) {
      client.stop();
      client = null;
    }
    for (HttpServer s : servers) {
      s.stop(0);
    }
    servers.clear();
  }

  private HttpServer start(HttpHandler handler) throws IOException {
    HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    s.createContext("/api/v2/sse/config", handler);
    s.start();
    servers.add(s);
    return s;
  }

  private static URI baseUri(HttpServer s) {
    return URI.create("http://127.0.0.1:" + s.getAddress().getPort());
  }

  /** Writes id: <version>\ndata: <json>\n\n — matches api-delivery output. */
  private static void writeFrame(OutputStream out, String version, String json) throws IOException {
    String frame = "id: " + version + "\ndata: " + json + "\n\n";
    out.write(frame.getBytes(StandardCharsets.UTF_8));
    out.flush();
  }

  /**
   * The full wire contract: GET to <streamUrl>/api/v2/sse/config with Basic 1:&lt;sdkKey&gt;, data:
   * payloads parsed to typed ConfigEnvelope, two events received before deliberate disconnect, a
   * third after reconnect — and onConnectionStateChange fires true.
   */
  @Test
  void receivesEnvelopesAndReconnects() throws Exception {
    AtomicInteger attempts = new AtomicInteger(0);
    BlockingQueue<ConfigEnvelope> received = new LinkedBlockingQueue<>();
    BlockingQueue<Boolean> states = new LinkedBlockingQueue<>();
    String[] capturedAuth = new String[1];

    HttpHandler handler =
        (HttpExchange ex) -> {
          int n = attempts.incrementAndGet();
          if (n == 1) {
            capturedAuth[0] = ex.getRequestHeaders().getFirst("Authorization");
          }
          ex.getResponseHeaders().set("Content-Type", "text/event-stream");
          ex.sendResponseHeaders(200, 0);
          OutputStream out = ex.getResponseBody();
          try {
            switch (n) {
              case 1:
                writeFrame(
                    out,
                    "v1",
                    "{\"meta\":{\"version\":\"v1\",\"environment\":\"prod\"},\"configs\":[]}");
                writeFrame(
                    out,
                    "v2",
                    "{\"meta\":{\"version\":\"v2\",\"environment\":\"prod\"},\"configs\":[]}");
                break;
              case 2:
                writeFrame(
                    out,
                    "v3",
                    "{\"meta\":{\"version\":\"v3\",\"environment\":\"prod\"},\"configs\":[]}");
                Thread.sleep(2000);
                break;
              default:
                Thread.sleep(2000);
                break;
            }
          } catch (IOException | InterruptedException ignored) {
            Thread.currentThread().interrupt();
          } finally {
            ex.close();
          }
        };
    HttpServer s = start(handler);

    client =
        SseClient.builder()
            .streamUrls(List.of(baseUri(s)))
            .sdkKey("test-key")
            .initialDelay(Duration.ofMillis(5))
            .maxDelay(Duration.ofMillis(50))
            .build();
    client.onEnvelope(received::add);
    client.onConnectionStateChange(states::add);
    client.start();

    List<ConfigEnvelope> got = new ArrayList<>();
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (got.size() < 3 && System.nanoTime() < deadline) {
      ConfigEnvelope env = received.poll(200, TimeUnit.MILLISECONDS);
      if (env != null) got.add(env);
    }
    assertEquals(3, got.size(), "expected 3 envelopes; attempts=" + attempts.get());
    assertEquals("v1", got.get(0).meta().version());
    assertEquals("v2", got.get(1).meta().version());
    assertEquals("v3", got.get(2).meta().version());

    String expected =
        "Basic "
            + Base64.getEncoder().encodeToString("1:test-key".getBytes(StandardCharsets.UTF_8));
    assertEquals(expected, capturedAuth[0], "expected Basic auth with username=1, password=sdkKey");

    assertTrue(attempts.get() >= 2, "expected reconnect, got " + attempts.get() + " attempts");

    boolean sawConnected = false;
    while (!states.isEmpty()) {
      if (Boolean.TRUE.equals(states.poll())) sawConnected = true;
    }
    assertTrue(sawConnected, "expected at least one onConnectionStateChange(true)");
  }

  /** ":keepalive" comments must not trigger the envelope handler. */
  @Test
  void ignoresKeepaliveComments() throws Exception {
    BlockingQueue<ConfigEnvelope> received = new LinkedBlockingQueue<>();
    AtomicInteger callbacks = new AtomicInteger(0);

    HttpServer s =
        start(
            (HttpExchange ex) -> {
              ex.getResponseHeaders().set("Content-Type", "text/event-stream");
              ex.sendResponseHeaders(200, 0);
              OutputStream out = ex.getResponseBody();
              try {
                out.write(": keepalive\n\n".getBytes(StandardCharsets.UTF_8));
                out.flush();
                out.write(": another keepalive\n\n".getBytes(StandardCharsets.UTF_8));
                out.flush();
                writeFrame(out, "v1", "{\"meta\":{\"version\":\"v1\"},\"configs\":[]}");
                out.write(": keepalive\n\n".getBytes(StandardCharsets.UTF_8));
                out.flush();
                Thread.sleep(2000);
              } catch (IOException | InterruptedException ignored) {
                Thread.currentThread().interrupt();
              } finally {
                ex.close();
              }
            });

    client =
        SseClient.builder()
            .streamUrls(List.of(baseUri(s)))
            .sdkKey("test-key")
            .initialDelay(Duration.ofMillis(5))
            .maxDelay(Duration.ofMillis(50))
            .build();
    client.onEnvelope(
        env -> {
          callbacks.incrementAndGet();
          received.add(env);
        });
    client.start();

    ConfigEnvelope env = received.poll(3, TimeUnit.SECONDS);
    assertNotNull(env, "expected at least one envelope past the keepalive comments");
    assertEquals("v1", env.meta().version());

    Thread.sleep(150);
    assertEquals(1, callbacks.get(), "keepalive comments must not trigger envelope callback");
  }

  /** Primary URL refuses every connection; secondary serves an event. */
  @Test
  void failsOverFromPrimaryToSecondary() throws Exception {
    BlockingQueue<ConfigEnvelope> received = new LinkedBlockingQueue<>();
    AtomicInteger primaryHits = new AtomicInteger(0);
    AtomicInteger secondaryHits = new AtomicInteger(0);

    HttpServer primary =
        start(
            (HttpExchange ex) -> {
              primaryHits.incrementAndGet();
              ex.sendResponseHeaders(503, -1);
              ex.close();
            });
    HttpServer secondary =
        start(
            (HttpExchange ex) -> {
              secondaryHits.incrementAndGet();
              ex.getResponseHeaders().set("Content-Type", "text/event-stream");
              ex.sendResponseHeaders(200, 0);
              OutputStream out = ex.getResponseBody();
              try {
                writeFrame(out, "vS", "{\"meta\":{\"version\":\"vS\"},\"configs\":[]}");
                Thread.sleep(2000);
              } catch (IOException | InterruptedException ignored) {
                Thread.currentThread().interrupt();
              } finally {
                ex.close();
              }
            });

    client =
        SseClient.builder()
            .streamUrls(List.of(baseUri(primary), baseUri(secondary)))
            .sdkKey("test-key")
            .initialDelay(Duration.ofMillis(5))
            .maxDelay(Duration.ofMillis(50))
            .build();
    client.onEnvelope(received::add);
    client.start();

    ConfigEnvelope env = received.poll(5, TimeUnit.SECONDS);
    assertNotNull(env, "expected envelope from secondary; primary=" + primaryHits.get());
    assertEquals("vS", env.meta().version());
    assertTrue(primaryHits.get() >= 1, "expected primary to be tried first");
    assertTrue(secondaryHits.get() >= 1, "expected secondary fallback");
  }

  /** stop() must unwind a connected reader within a couple of seconds. */
  @Test
  void stopUnblocksWhileConnected() throws Exception {
    HttpServer s =
        start(
            (HttpExchange ex) -> {
              ex.getResponseHeaders().set("Content-Type", "text/event-stream");
              ex.sendResponseHeaders(200, 0);
              try {
                Thread.sleep(10_000);
              } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
              } finally {
                ex.close();
              }
            });

    client =
        SseClient.builder()
            .streamUrls(List.of(baseUri(s)))
            .sdkKey("test-key")
            .initialDelay(Duration.ofMillis(5))
            .build();
    client.onEnvelope(env -> {});
    client.start();

    Thread.sleep(150);

    long t0 = System.nanoTime();
    client.stop();
    long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);
    assertTrue(elapsedMs < 3000, "stop() blocked for " + elapsedMs + "ms");
  }
}
