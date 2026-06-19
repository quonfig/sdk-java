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
import org.junit.jupiter.api.Test;

/**
 * In-JVM red→green coverage for the two reliability fixes landed in qfg-7h5d.1.10, mirroring the
 * sdk-go pilot's f02 (per-URL config-fetch timeout) and o02 (reject-older install guard). Uses the
 * JDK's {@link HttpServer} as primary/secondary upstreams so the mechanics are exercised end-to-end
 * without docker/toxiproxy (the full chaos corpus runs in the failover chaos CI job).
 *
 * <p>Both assertions are mechanism-specific revert tests: delete the fix and the test goes red.
 *
 * <ul>
 *   <li>{@code perUrlTimeout_failsOverPastHungPrimary} — without the per-URL deadline the hung
 *       primary blocks on the init budget and the client never resolves off the secondary in time.
 *   <li>{@code rejectOlder_doesNotRegressEstablishedClient} — without the guard a failover fetch of
 *       the older secondary regresses the held generation.
 * </ul>
 */
final class FailoverGuardTest {

  /** Per-URL timeout (f02): a hung primary must not starve the secondary past the init budget. */
  @Test
  void perUrlTimeout_failsOverPastHungPrimary() throws Exception {
    Upstream primary = Upstream.hung();
    Upstream secondary = Upstream.serving(7);
    int deadStream = closedPort();
    try {
      Quonfig client =
          new Quonfig(
              Options.builder()
                  .sdkKey("test-key")
                  .apiUrls(List.of(primary.url(), secondary.url()))
                  .streamUrls(List.of("http://127.0.0.1:" + deadStream))
                  .disableTelemetry(true)
                  .fallbackPollEnabled(false)
                  .initTimeout(Duration.ofSeconds(8))
                  // default configFetchTimeout (~3s) — leave unset to prove the default fails over
                  .build());
      try {
        long start = System.currentTimeMillis();
        boolean resolved =
            awaitTrue(() -> client.ready() && "secondary".equals(client.resolvedFrom()), 5000);
        long elapsed = System.currentTimeMillis() - start;
        assertTrue(
            resolved,
            "client should resolve off the secondary within 5s despite the hung primary "
                + "(ready="
                + client.ready()
                + ", resolvedFrom="
                + client.resolvedFrom()
                + ")");
        assertTrue(elapsed < 5000, "failover should be fast, took " + elapsed + "ms");
        assertEquals(7, client.heldGeneration());
      } finally {
        client.close();
      }
    } finally {
      primary.stop();
      secondary.stop();
    }
  }

  /** Reject-older guard (o02): an established client must never regress to an older secondary. */
  @Test
  void rejectOlder_doesNotRegressEstablishedClient() throws Exception {
    Upstream primary = Upstream.serving(42);
    Upstream secondary = Upstream.serving(41);
    int deadStream = closedPort();
    try {
      Quonfig client =
          new Quonfig(
              Options.builder()
                  .sdkKey("test-key")
                  .apiUrls(List.of(primary.url(), secondary.url()))
                  .streamUrls(List.of("http://127.0.0.1:" + deadStream))
                  .disableTelemetry(true)
                  .fallbackPollEnabled(false)
                  .initTimeout(Duration.ofSeconds(8))
                  .build());
      try {
        // Establish on the primary at generation 42.
        client.initFuture().get();
        assertEquals(42, client.heldGeneration(), "should establish on primary gen 42");
        assertEquals("primary", client.resolvedFrom());
        assertEquals(1, client.configInstallCount());

        // Primary goes away; a manual refresh fails over to the OLDER secondary (gen 41). Settle
        // briefly so the stopped primary truly refuses (the pooled keep-alive connection is torn
        // down) and the refresh exercises the failover path rather than reusing the live socket.
        primary.stop();
        Thread.sleep(200);
        client.refresh();

        // The guard must drop gen 41 — the established client never regresses.
        assertEquals(42, client.heldGeneration(), "reject-older guard must keep gen 42");
        assertEquals(1, client.configInstallCount(), "rejected install must not advance the count");
      } finally {
        client.close();
      }
    } finally {
      primary.stop();
      secondary.stop();
    }
  }

  // ---- helpers ----

  private interface BoolSupplier {
    boolean get();
  }

  private static boolean awaitTrue(BoolSupplier cond, long timeoutMs) throws InterruptedException {
    long deadline = System.currentTimeMillis() + timeoutMs;
    while (System.currentTimeMillis() < deadline) {
      if (cond.get()) return true;
      Thread.sleep(50);
    }
    return cond.get();
  }

  private static int closedPort() throws IOException {
    try (ServerSocket s = new ServerSocket(0)) {
      return s.getLocalPort();
    }
  }

  /** A minimal api-delivery stand-in: serves a fixed-generation envelope, or hangs forever. */
  private static final class Upstream {
    private final HttpServer server;

    private Upstream(HttpServer server) {
      this.server = server;
    }

    static Upstream serving(int generation) throws IOException {
      return start(generation, false);
    }

    static Upstream hung() throws IOException {
      return start(0, true);
    }

    private static Upstream start(int generation, boolean hang) throws IOException {
      HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      server.createContext("/", exchange -> handle(exchange, generation, hang));
      server.setExecutor(
          Executors.newCachedThreadPool(
              r -> {
                Thread t = new Thread(r, "test-upstream");
                t.setDaemon(true);
                return t;
              }));
      server.start();
      return new Upstream(server);
    }

    private static void handle(HttpExchange exchange, int generation, boolean hang)
        throws IOException {
      if (hang) {
        // Accept the request but never respond — the SDK's per-URL timeout must abort it.
        try {
          Thread.sleep(30_000);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
        return;
      }
      String path = exchange.getRequestURI().getPath();
      if (path.startsWith("/api/v2/configs")) {
        byte[] body =
            ("{\"configs\":[],\"meta\":{\"version\":\"gen-"
                    + generation
                    + "\",\"environment\":\"production\",\"generation\":"
                    + generation
                    + "}}")
                .getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, body.length);
        try (OutputStream os = exchange.getResponseBody()) {
          os.write(body);
        }
      } else {
        // Everything else (e.g. the SSE path) gets a clean 404 so the SSE loop backs off quietly.
        exchange.sendResponseHeaders(404, -1);
        exchange.close();
      }
    }

    String url() {
      return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private final AtomicBoolean stopped = new AtomicBoolean(false);

    void stop() {
      if (stopped.compareAndSet(false, true)) {
        server.stop(0);
      }
    }
  }
}
