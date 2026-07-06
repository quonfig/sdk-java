package com.quonfig.sdk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.quonfig.sdk.telemetry.ContextUploadMode;
import com.quonfig.sdk.telemetry.TelemetrySender;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * Client-level coverage that the failover-observability counters (qfg-41nh.18) are wired at the
 * real call sites and land on the telemetry wire in the exact JSON api-telemetry parses. Drives a
 * real hedge + secondary install and a real reject-older guard drop against in-JVM upstreams, then
 * flushes and asserts the emitted {@code failover} event. Mirrors the sdk-go client-level
 * failover-telemetry tests.
 */
final class QuonfigFailoverTelemetryTest {

  private static final Duration INIT_TIMEOUT = Duration.ofSeconds(8);

  /** A capturing telemetry sender that records every posted envelope. */
  static final class CapturingSender implements TelemetrySender {
    final List<Map<String, Object>> sent = new ArrayList<>();

    @Override
    public synchronized void send(Map<String, Object> payload) {
      sent.add(payload);
    }
  }

  /**
   * Deterministic: a fast primary (gen 42) wins init so resolvedFrom is primary and the
   * cold-standby secondary is never contacted (no hedge). The primary then goes away and a manual
   * refresh fails over to the OLDER secondary (gen 41), which the reject-older guard drops. The
   * flush must carry {@code hedgeFired=0, guardRejected=1, resolvedFromPrimary=1,
   * resolvedFromSecondary=0}.
   */
  @Test
  void resolvedFromPrimaryAndGuardRejectedLandOnWire() throws Exception {
    Upstream primary = Upstream.serving(42, Duration.ZERO);
    Upstream secondary = Upstream.serving(41, Duration.ZERO);
    int deadStream = closedPort();
    CapturingSender sender = new CapturingSender();
    try {
      Quonfig client = newTelemetryClient(primary, secondary, deadStream, sender);
      try {
        client.initFuture().get();
        assertEquals(42, client.heldGeneration(), "fast primary wins init");
        assertEquals("primary", client.resolvedFrom());
        assertEquals(1, client.configInstallCount());

        // Primary goes away; a manual refresh fails over to the older secondary (gen 41). Settle so
        // the stopped primary truly refuses and the refresh exercises the failover path.
        primary.stop();
        Thread.sleep(200);
        client.refresh();
        assertEquals(42, client.heldGeneration(), "reject-older guard must keep gen 42");

        client.flush();
        Map<String, Object> f = failoverEvent(sender);
        assertNotNull(f, "a failover event must ride the flush");
        assertEquals(0L, num(f, "hedgeFired"), "fast primary — no hedge fired");
        assertEquals(
            1L, num(f, "guardRejected"), "the failed-over older secondary was guard-dropped");
        assertEquals(1L, num(f, "resolvedFromPrimary"), "init resolved from the primary");
        assertEquals(0L, num(f, "resolvedFromSecondary"));
        assertEquals(0L, num(f, "resolvedFromLkg"));
        assertNotNull(f.get("start"));
        assertNotNull(f.get("end"));
      } finally {
        client.close();
      }
    } finally {
      primary.stop();
      secondary.stop();
    }
  }

  /**
   * The parallel hedge: a SLOW primary serves the OLDER 41; a fast secondary serves the NEWER 42.
   * The hedge fires the secondary at the hedge delay and installs 42 (resolvedFromSecondary), while
   * the late older primary is dropped by reject-older. The flush must show the hedge fired and the
   * held config resolved from the secondary leg.
   */
  @Test
  void hedgeFiredAndResolvedFromSecondaryLandOnWire() throws Exception {
    Upstream primary = Upstream.serving(41, Duration.ofMillis(2500));
    Upstream secondary = Upstream.serving(42, Duration.ZERO);
    int deadStream = closedPort();
    CapturingSender sender = new CapturingSender();
    try {
      Quonfig client = newTelemetryClient(primary, secondary, deadStream, sender);
      try {
        client.initFuture().get();
        assertTrue(
            awaitTrue(() -> client.heldGeneration() == 42, 6000),
            "the hedge should install the fast secondary's newer 42");
        assertTrue(
            awaitTrue(() -> secondary.hits() > 0, 2000), "the hedge must contact the secondary");
        // Settle past the slow primary's 2500ms response so the init thread finishes joining the
        // legs and records hedge-fired before we flush.
        Thread.sleep(2000);

        client.flush();
        Map<String, Object> f = failoverEvent(sender);
        assertNotNull(f, "a failover event must ride the flush");
        assertEquals(1L, num(f, "hedgeFired"), "the hedge fired its secondary leg this cycle");
        assertEquals(
            1L, num(f, "resolvedFromSecondary"), "the held config resolved from the secondary");
        assertEquals(
            0L, num(f, "resolvedFromPrimary"), "the primary's older payload never installed");
      } finally {
        client.close();
      }
    } finally {
      primary.stop();
      secondary.stop();
    }
  }

  // ---- helpers ----

  private static Quonfig newTelemetryClient(
      Upstream primary, Upstream secondary, int deadStream, CapturingSender sender) {
    return new Quonfig(
        Options.builder()
            .sdkKey("test-backend-key")
            .apiUrls(List.of(primary.url(), secondary.url()))
            .streamUrls(List.of("http://127.0.0.1:" + deadStream))
            .fallbackPollEnabled(false)
            .initTimeout(INIT_TIMEOUT)
            .telemetrySender(sender)
            // Long intervals so the periodic scheduler never fires — we drive flush() manually.
            .telemetryInitialDelay(Duration.ofSeconds(60))
            .telemetryFlushInterval(Duration.ofSeconds(60))
            .telemetryMaxInterval(Duration.ofSeconds(600))
            // Keep eval/context collectors empty so the failover event is the only one on the wire.
            .collectEvaluationSummaries(false)
            .contextUploadMode(ContextUploadMode.NONE)
            .build());
  }

  /** Finds the single {@code failover} event in the most recent flushed envelope, or null. */
  private static Map<String, Object> failoverEvent(CapturingSender sender) {
    for (Map<String, Object> envelope : sender.sent) {
      @SuppressWarnings("unchecked")
      List<Map<String, Object>> events = (List<Map<String, Object>>) envelope.get("events");
      if (events == null) continue;
      for (Map<String, Object> e : events) {
        if (e.containsKey("failover")) {
          @SuppressWarnings("unchecked")
          Map<String, Object> f = (Map<String, Object>) e.get("failover");
          return f;
        }
      }
    }
    return null;
  }

  private static long num(Map<String, Object> f, String key) {
    return ((Number) f.get(key)).longValue();
  }

  private interface BoolSupplier {
    boolean get();
  }

  private static boolean awaitTrue(BoolSupplier cond, long timeoutMs) throws InterruptedException {
    long deadline = System.currentTimeMillis() + timeoutMs;
    while (System.currentTimeMillis() < deadline) {
      if (cond.get()) return true;
      Thread.sleep(20);
    }
    return cond.get();
  }

  private static int closedPort() throws IOException {
    try (ServerSocket s = new ServerSocket(0)) {
      return s.getLocalPort();
    }
  }

  /**
   * A minimal api-delivery stand-in: serves a fixed-generation envelope after an optional delay.
   */
  private static final class Upstream {
    private final HttpServer server;
    private final AtomicInteger hits = new AtomicInteger();
    private final AtomicBoolean stopped = new AtomicBoolean(false);

    private Upstream(HttpServer server) {
      this.server = server;
    }

    static Upstream serving(int generation, Duration delay) throws IOException {
      HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      Upstream u = new Upstream(server);
      server.createContext("/", exchange -> u.handle(exchange, generation, delay));
      server.setExecutor(
          Executors.newCachedThreadPool(
              r -> {
                Thread t = new Thread(r, "failover-telemetry-upstream");
                t.setDaemon(true);
                return t;
              }));
      server.start();
      return u;
    }

    private void handle(HttpExchange exchange, int generation, Duration delay) throws IOException {
      String path = exchange.getRequestURI().getPath();
      if (path.startsWith("/api/v2/configs")) {
        hits.incrementAndGet();
        if (!delay.isZero()) {
          try {
            Thread.sleep(delay.toMillis());
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
        }
        byte[] body =
            ("{\"configs\":[],\"meta\":{\"version\":\"gen-"
                    + generation
                    + "\",\"environment\":\"production\",\"generation\":"
                    + generation
                    + "}}")
                .getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.getResponseHeaders().add("ETag", "\"gen-" + generation + "\"");
        exchange.sendResponseHeaders(200, body.length);
        try (OutputStream os = exchange.getResponseBody()) {
          os.write(body);
        }
      } else {
        exchange.sendResponseHeaders(404, -1);
        exchange.close();
      }
    }

    int hits() {
      return hits.get();
    }

    String url() {
      return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    void stop() {
      if (stopped.compareAndSet(false, true)) {
        server.stop(0);
      }
    }
  }
}
