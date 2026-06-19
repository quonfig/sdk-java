package com.quonfig.sdk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * In-JVM red→green coverage for the parallel-failover hedge (qfg-7h5d.1.14.7), mirroring the sdk-go
 * pilot's {@code quonfig_hedge_test.go}. Each upstream counts the requests it receives, so these
 * tests can prove the cold-standby contract ("a fast primary never contacts the secondary") that
 * the chaos rig — which has no server-side request counter — cannot.
 *
 * <p>The three scenarios mirror the chaos ordering corpus:
 *
 * <ul>
 *   <li>{@code fastPrimaryNeverContactsSecondary} (o01) — both legs healthy and fast, secondary
 *       newer. The fast primary answers well inside the hedge delay, so the secondary is NEVER
 *       contacted; the client holds the primary's lower generation and resolvedFrom stays primary.
 *   <li>{@code secondaryNewerWins} (o05) — the primary is SLOW and serves the OLDER generation; the
 *       secondary is fast and serves the NEWER. The hedge fires the secondary once the hedge delay
 *       elapses, installs the newer payload, and the late older primary is dropped by reject-older.
 *   <li>{@code healsForwardToSlowNewerPrimary} (o03) — the primary is SLOW and serves the NEWER
 *       generation; the secondary is fast and serves the OLDER. The hedge seeds readiness off the
 *       secondary, then heals forward to the primary when it lands.
 * </ul>
 *
 * <p>On the pre-hedge sequential transport the secondary is never contacted while a slow primary
 * still answers inside its per-URL timeout, so {@code secondaryNewerWins} holds the slow primary's
 * older generation (RED) and {@code healsForwardToSlowNewerPrimary} never contacts the secondary
 * (RED). The hedge fires the secondary in parallel (GREEN).
 */
final class HedgeTest {

  private static final Duration INIT_TIMEOUT = Duration.ofSeconds(8);

  /**
   * o01 cold standby: a fast primary answers inside the hedge delay so the secondary is never
   * contacted. The client holds the primary's (lower) generation 41; the secondary's newer 42 must
   * not be installed; resolvedFrom stays primary; configInstallCount stays 1.
   */
  @Test
  void fastPrimaryNeverContactsSecondary() throws Exception {
    Upstream primary = Upstream.serving(41, Duration.ZERO);
    Upstream secondary = Upstream.serving(42, Duration.ZERO);
    int deadStream = closedPort();
    try {
      Quonfig client = newHedgeClient(primary, secondary, deadStream);
      try {
        client.initFuture().get();
        // Give a (would-be) hedge ample time to fire and a secondary install to land if the
        // contract were broken.
        Thread.sleep(500);
        assertEquals(
            41, client.heldGeneration(), "fast primary wins; secondary's 42 must not be installed");
        assertEquals("primary", client.resolvedFrom());
        assertEquals(1, client.configInstallCount(), "exactly one install (the primary's)");
        assertEquals(
            0,
            secondary.hits(),
            "cold standby — a fast primary must never trigger the hedge (secondary contacted "
                + secondary.hits()
                + " times)");
        assertTrue(primary.hits() > 0, "primary was never contacted");
      } finally {
        client.close();
      }
    } finally {
      primary.stop();
      secondary.stop();
    }
  }

  /**
   * o05 secondary-newer-wins: a SLOW primary serves the OLDER 41; a fast secondary serves the NEWER
   * 42. The hedge fires the secondary at the hedge delay, installs 42, and when the slow primary's
   * older 41 lands late the reject-older guard drops it.
   */
  @Test
  void secondaryNewerWins() throws Exception {
    Upstream primary = Upstream.serving(41, Duration.ofMillis(2500));
    Upstream secondary = Upstream.serving(42, Duration.ZERO);
    int deadStream = closedPort();
    try {
      Quonfig client = newHedgeClient(primary, secondary, deadStream);
      try {
        client.initFuture().get();
        // The hedge must have fired the secondary against the slow primary and installed 42.
        assertTrue(
            awaitTrue(() -> client.heldGeneration() == 42, 5000),
            "held generation should reach 42 (last="
                + client.heldGeneration()
                + ", secondaryHits="
                + secondary.hits()
                + ") — the hedge did not fire against the slow primary");
        assertTrue(
            secondary.hits() > 0,
            "secondary was never contacted — the hedge did not fire against the slow primary");

        // The slow primary's older 41 lands late and on every subsequent refresh; reject-older must
        // keep the client on 42.
        for (int i = 0; i < 3; i++) {
          client.refresh();
        }
        assertEquals(
            42,
            client.heldGeneration(),
            "reject-older must drop the slow 41 and keep the client on 42");
      } finally {
        client.close();
      }
    } finally {
      primary.stop();
      secondary.stop();
    }
  }

  /**
   * o03 heal-forward: a SLOW primary serves the NEWER 42; a fast secondary serves the OLDER 41. The
   * hedge seeds readiness off the secondary's 41 then heals forward to the primary's 42 when it
   * lands.
   */
  @Test
  void healsForwardToSlowNewerPrimary() throws Exception {
    Upstream primary = Upstream.serving(42, Duration.ofMillis(2500));
    Upstream secondary = Upstream.serving(41, Duration.ZERO);
    int deadStream = closedPort();
    try {
      Quonfig client = newHedgeClient(primary, secondary, deadStream);
      try {
        client.initFuture().get();
        assertTrue(
            secondary.hits() > 0,
            "secondary was never contacted — the hedge did not fire against the slow primary");
        // Heal forward to the slow primary's newer 42.
        assertTrue(
            awaitTrue(() -> client.heldGeneration() == 42, 5000),
            "should heal forward to the slow primary's 42 (last=" + client.heldGeneration() + ")");
      } finally {
        client.close();
      }
    } finally {
      primary.stop();
      secondary.stop();
    }
  }

  // ---- helpers ----

  private static Quonfig newHedgeClient(Upstream primary, Upstream secondary, int deadStream) {
    return new Quonfig(
        Options.builder()
            .sdkKey("test-backend-key")
            .apiUrls(List.of(primary.url(), secondary.url()))
            .streamUrls(List.of("http://127.0.0.1:" + deadStream))
            .disableTelemetry(true)
            .fallbackPollEnabled(false)
            .initTimeout(INIT_TIMEOUT)
            // Default hedge delay (~2s) and abort (~6s): hedgeDelay < 2.5s slow primary <
            // hedgeAbort.
            .build());
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
                Thread t = new Thread(r, "hedge-upstream");
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
