package com.quonfig.sdk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * End-to-end wiring check for the SSE-pinned-to-primary invariant (chaos scenario f05, bead
 * qfg-41nh.7): with {@code streamUrls = [failing-primary, live-secondary]}, the live SSE stream
 * must keep retrying the primary leg forever and NEVER dial the secondary — stream failover is an
 * HTTP-poll-only property. {@link Quonfig#sseFailedOverToSecondary()} must stay {@code false}.
 *
 * <p>Mirrors sdk-go's semantics (startSSE pins to {@code streamURLFor(0)}).
 */
class QuonfigSsePinTest {

  private final List<HttpServer> servers = new ArrayList<>();

  @AfterEach
  void stopServers() {
    for (HttpServer s : servers) s.stop(0);
    servers.clear();
  }

  private HttpServer startServer() throws IOException {
    HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    s.setExecutor(Executors.newCachedThreadPool());
    s.start();
    servers.add(s);
    return s;
  }

  private static String base(HttpServer s) {
    return "http://127.0.0.1:" + s.getAddress().getPort();
  }

  @Test
  void sseNeverDialsSecondaryStreamLeg_whenPrimaryStreamIsDown() throws Exception {
    AtomicInteger primaryStreamHits = new AtomicInteger();
    AtomicInteger secondaryStreamHits = new AtomicInteger();

    // api-delivery stub: serves the initial config fetch so the client becomes ready
    // and starts SSE.
    HttpServer api = startServer();
    api.createContext(
        "/api/v2/configs",
        (HttpExchange ex) -> {
          byte[] body =
              ("{\"configs\":[],\"meta\":{\"version\":\"v1\",\"environment\":\"production\","
                      + "\"workspaceId\":\"ws\",\"generation\":1}}")
                  .getBytes(StandardCharsets.UTF_8);
          ex.getResponseHeaders().add("ETag", "\"v1\"");
          ex.sendResponseHeaders(200, body.length);
          try (OutputStream out = ex.getResponseBody()) {
            out.write(body);
          }
        });

    // Primary stream leg: always 503 — the SSE loop must back off and retry THIS leg forever.
    HttpServer primaryStream = startServer();
    primaryStream.createContext(
        "/api/v2/sse/config",
        (HttpExchange ex) -> {
          primaryStreamHits.incrementAndGet();
          ex.sendResponseHeaders(503, -1);
          ex.close();
        });

    // Secondary stream leg: live and eager to serve — but it must never be dialed.
    HttpServer secondaryStream = startServer();
    secondaryStream.createContext(
        "/api/v2/sse/config",
        (HttpExchange ex) -> {
          secondaryStreamHits.incrementAndGet();
          ex.getResponseHeaders().set("Content-Type", "text/event-stream");
          ex.sendResponseHeaders(200, 0);
          OutputStream out = ex.getResponseBody();
          try {
            out.write(
                ("id: vS\ndata: {\"meta\":{\"version\":\"vS\",\"environment\":\"production\","
                        + "\"workspaceId\":\"ws\",\"generation\":99},\"configs\":[]}\n\n")
                    .getBytes(StandardCharsets.UTF_8));
            out.flush();
            Thread.sleep(2000);
          } catch (IOException | InterruptedException ignored) {
            Thread.currentThread().interrupt();
          } finally {
            ex.close();
          }
        });

    Options o =
        Options.builder()
            .sdkKey("test-sdk")
            .apiUrls(List.of(base(api)))
            .streamUrls(List.of(base(primaryStream), base(secondaryStream)))
            .telemetryUrl(base(api))
            .environment("production")
            .initTimeout(Duration.ofSeconds(5))
            .disableTelemetry(true)
            .fallbackPollEnabled(false)
            .build();

    Quonfig q = new Quonfig(o);
    try {
      q.initFuture().get(5, TimeUnit.SECONDS);

      // Give the SSE loop several reconnect rounds against the 503ing primary. The old
      // (buggy) behavior walked the stream list within the FIRST round, so the secondary
      // would be dialed almost immediately.
      long deadline = System.currentTimeMillis() + 2000;
      while (primaryStreamHits.get() < 2 && System.currentTimeMillis() < deadline) {
        Thread.sleep(20);
      }

      assertTrue(
          primaryStreamHits.get() >= 2,
          "SSE loop must keep retrying the primary stream leg; hits=" + primaryStreamHits.get());
      assertEquals(
          0,
          secondaryStreamHits.get(),
          "SSE must never dial the secondary stream leg — failover is HTTP-only (f05)");
      assertFalse(
          q.sseFailedOverToSecondary(),
          "sseFailedOverToSecondary() must be false — the stream never leaves primary");
    } finally {
      q.close();
    }
  }
}
