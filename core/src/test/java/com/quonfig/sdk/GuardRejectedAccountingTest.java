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
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Accounting rule for the reject-older install guard (qfg-rr5b / qfg-q18o, cross-SDK decision
 * 2026-09-11). The guard's DROP behavior is unchanged — only what gets counted changed.
 *
 * <p>{@code guardRejected} feeds the {@code sdk_failover} alerting signal, where it means "a leg
 * tried to move us backwards". Two normal server behaviors re-deliver the envelope the client
 * already holds at the SAME generation — api-delivery's SSE {@code sendInitialConfig} resend on
 * every connect, and a config poll whose per-leg ETag slot is empty (a fresh transport, a
 * reconnect, the fallback poller's engage-time fetch). Counting those made the field read as
 * failover noise on a perfectly healthy client.
 *
 * <ul>
 *   <li>A STRICTLY older payload ({@code incoming < held}) is the thing worth alerting on and is
 *       counted.
 *   <li>An EQUAL-generation re-delivery is a silent no-op: still not installed, still advances
 *       liveness exactly where it did before, but NOT counted.
 *   <li>The unversioned carve-out ({@code generation <= 0}) is untouched — it installs, so it was
 *       never counted.
 * </ul>
 *
 * <p>No wire, ClickHouse, or dashboard change: the field simply becomes accurate.
 */
final class GuardRejectedAccountingTest {

  private static final Duration INIT_TIMEOUT = Duration.ofSeconds(8);

  private final List<Upstream> upstreams = new ArrayList<>();

  @AfterEach
  void stopUpstreams() {
    for (Upstream u : upstreams) u.stop();
    upstreams.clear();
  }

  /**
   * HTTP path (qfg-rr5b): an established client re-fetches the SAME generation it already holds —
   * exactly what a cold-ETag poll or the fallback poller's engage fetch produces. The envelope must
   * still be dropped and liveness must still stamp, but nothing may be counted as guardRejected.
   */
  @Test
  void equalGenerationHttpRedelivery_isNotCountedAsGuardRejected() throws Exception {
    Upstream primary = serving(42);
    Upstream secondary = serving(42);
    CapturingSender sender = new CapturingSender();

    Quonfig client = client(primary, secondary, closedPort(), sender);
    try {
      client.initFuture().get(8, TimeUnit.SECONDS);
      assertEquals(42, client.heldGeneration(), "fast primary wins init at gen 42");
      assertEquals(1, client.configInstallCount());
      Instant afterInit = client.lastSuccessfulRefresh();
      assertNotNull(afterInit, "the init install must stamp liveness");

      // Two same-generation re-deliveries through the HTTP fetch path.
      Thread.sleep(30);
      client.refresh();
      Thread.sleep(30);
      client.refresh();

      // Unchanged behavior: not installed, no flap.
      assertEquals(42, client.heldGeneration(), "a same-generation envelope must not re-install");
      assertEquals(
          1, client.configInstallCount(), "a same-generation envelope must not advance installs");
      // Unchanged behavior: an answered fetch is still a successful refresh (qfg-41nh.15).
      Instant afterRedelivery = client.lastSuccessfulRefresh();
      assertTrue(
          afterRedelivery.isAfter(afterInit),
          "an answered same-generation fetch must still stamp liveness; init="
              + afterInit
              + " afterRedelivery="
              + afterRedelivery);

      client.flush();
      Map<String, Object> f = failoverEvent(sender);
      assertNotNull(f, "the init's resolvedFromPrimary must put a failover event on the wire");
      assertEquals(
          0L,
          num(f, "guardRejected"),
          "an EQUAL-generation re-delivery is a silent no-op and must NOT be counted as "
              + "guardRejected (qfg-rr5b)");
      assertEquals(1L, num(f, "resolvedFromPrimary"), "init resolved from the primary");
    } finally {
      client.close();
    }
  }

  /**
   * SSE path (qfg-rr5b): api-delivery re-sends the current envelope on every SSE connect regardless
   * of {@code Last-Event-Id}, so a healthy client gets one same-generation snapshot per reconnect.
   * The stream then pushes a genuinely newer generation, which installing PROVES the earlier
   * same-generation event went through the same {@code onEnvelope} -> {@code installDelivery} path.
   */
  @Test
  void equalGenerationSseRedelivery_isNotCountedAsGuardRejected() throws Exception {
    // The initial HTTP fetch installs gen 42; the stream then resends 42 (no-op) and pushes 43.
    Upstream primary = serving(42);
    Upstream secondary = serving(42);
    Upstream stream = streaming(42, 43);
    CapturingSender sender = new CapturingSender();

    Quonfig client = client(primary, secondary, stream, sender);
    try {
      client.initFuture().get(8, TimeUnit.SECONDS);
      assertEquals(42, client.heldGeneration(), "the initial HTTP fetch installs gen 42");
      assertEquals(1, client.configInstallCount());

      // The stream's same-generation resend is a no-op; its follow-up 43 installs. Awaiting 43
      // proves the resend was delivered to and processed by the SSE install path first.
      assertTrue(
          awaitTrue(() -> client.heldGeneration() == 43, 6000),
          "the stream's newer generation must install (held=" + client.heldGeneration() + ")");
      assertEquals(2, client.configInstallCount(), "only the newer SSE envelope installed");

      client.flush();
      Map<String, Object> f = failoverEvent(sender);
      assertNotNull(f, "the init's resolvedFromPrimary must put a failover event on the wire");
      assertEquals(
          0L,
          num(f, "guardRejected"),
          "the SSE connect-time resend of the held generation must NOT be counted as "
              + "guardRejected (qfg-rr5b)");
    } finally {
      client.close();
    }
  }

  /**
   * The rule's other half: a STRICTLY older payload is the real "a leg tried to move us backwards"
   * event and MUST still be counted. Pins the narrowing so it cannot drift into counting nothing.
   */
  @Test
  void strictlyOlderRedelivery_isCountedAsGuardRejected() throws Exception {
    Upstream primary = serving(42);
    Upstream secondary = serving(41);
    CapturingSender sender = new CapturingSender();

    Quonfig client = client(primary, secondary, closedPort(), sender);
    try {
      client.initFuture().get(8, TimeUnit.SECONDS);
      assertEquals(42, client.heldGeneration(), "fast primary wins init at gen 42");

      // Primary goes away; the refresh fails over to the OLDER secondary (gen 41). Settle so the
      // pooled keep-alive socket is torn down and the refresh really exercises the failover leg.
      primary.stop();
      Thread.sleep(200);
      client.refresh();
      assertEquals(42, client.heldGeneration(), "reject-older guard must keep gen 42");
      assertEquals(1, client.configInstallCount(), "the older payload must not install");

      client.flush();
      Map<String, Object> f = failoverEvent(sender);
      assertNotNull(f, "a failover event must ride the flush");
      assertEquals(
          1L,
          num(f, "guardRejected"),
          "a STRICTLY older payload must still be counted as guardRejected (qfg-rr5b)");
    } finally {
      client.close();
    }
  }

  /**
   * Carve-out pin (qfg-7h5d.1.18): an UNVERSIONED snapshot (generation absent or {@code <= 0})
   * carries no ordering information, so the guard never treats it as older — it installs, and
   * therefore is not counted. Untouched by qfg-rr5b; pinned here so the narrowing can't disturb it.
   */
  @Test
  void unversionedSnapshot_installsAndIsNotCountedAsGuardRejected() throws Exception {
    Upstream primary = serving(42);
    Upstream secondary = serving(0);
    CapturingSender sender = new CapturingSender();

    Quonfig client = client(primary, secondary, closedPort(), sender);
    try {
      client.initFuture().get(8, TimeUnit.SECONDS);
      assertEquals(42, client.heldGeneration(), "fast primary wins init at gen 42");

      primary.stop();
      Thread.sleep(200);
      client.refresh();
      assertEquals(0, client.heldGeneration(), "gen-0 carve-out: unversioned snapshot installs");
      assertEquals(2, client.configInstallCount(), "the carve-out install advances the count");

      client.flush();
      Map<String, Object> f = failoverEvent(sender);
      assertNotNull(f, "a failover event must ride the flush");
      assertEquals(
          0L,
          num(f, "guardRejected"),
          "an unversioned snapshot installs, so it is never a guard rejection");
    } finally {
      client.close();
    }
  }

  // ---- helpers ----

  /** A capturing telemetry sender that records every posted envelope. */
  private static final class CapturingSender implements TelemetrySender {
    private final List<Map<String, Object>> sent = new ArrayList<>();

    @Override
    public synchronized void send(Map<String, Object> payload) {
      sent.add(payload);
    }

    private synchronized List<Map<String, Object>> snapshot() {
      return new ArrayList<>(sent);
    }
  }

  private Quonfig client(
      Upstream primary, Upstream secondary, int deadStreamPort, CapturingSender sender) {
    return client(primary, secondary, "http://127.0.0.1:" + deadStreamPort, sender);
  }

  private Quonfig client(
      Upstream primary, Upstream secondary, Upstream stream, CapturingSender sender) {
    return client(primary, secondary, stream.url(), sender);
  }

  private Quonfig client(
      Upstream primary, Upstream secondary, String streamUrl, CapturingSender sender) {
    return new Quonfig(
        Options.builder()
            .sdkKey("test-backend-key")
            .apiUrls(List.of(primary.url(), secondary.url()))
            .streamUrls(List.of(streamUrl))
            .fallbackPollEnabled(false)
            .initTimeout(INIT_TIMEOUT)
            .telemetrySender(sender)
            // Long intervals so the periodic scheduler never fires — flush() is driven manually.
            .telemetryInitialDelay(Duration.ofSeconds(60))
            .telemetryFlushInterval(Duration.ofSeconds(60))
            .telemetryMaxInterval(Duration.ofSeconds(600))
            // Keep eval/context collectors empty so the failover event is the only one on the wire.
            .collectEvaluationSummaries(false)
            .contextUploadMode(ContextUploadMode.NONE)
            .build());
  }

  /** Finds the single {@code failover} event across every flushed envelope, or null. */
  private static Map<String, Object> failoverEvent(CapturingSender sender) {
    for (Map<String, Object> envelope : sender.snapshot()) {
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

  private static String envelopeJson(int generation) {
    return "{\"configs\":[],\"meta\":{\"version\":\"gen-"
        + generation
        + "\",\"environment\":\"production\",\"workspaceId\":\"ws\",\"generation\":"
        + generation
        + "}}";
  }

  private Upstream serving(int generation) throws IOException {
    Upstream u = Upstream.configs(generation);
    upstreams.add(u);
    return u;
  }

  /**
   * A stream leg that mimics api-delivery's connect-time behavior: it re-sends the envelope the
   * client already holds ({@code resendGeneration}), then pushes a genuinely newer one.
   */
  private Upstream streaming(int resendGeneration, int thenGeneration) throws IOException {
    Upstream u = Upstream.sse(resendGeneration, thenGeneration);
    upstreams.add(u);
    return u;
  }

  /** A minimal api-delivery stand-in. */
  private static final class Upstream {
    private final HttpServer server;
    private final AtomicInteger hits = new AtomicInteger();
    private final AtomicBoolean stopped = new AtomicBoolean(false);

    private Upstream(HttpServer server) {
      this.server = server;
    }

    private static HttpServer start() throws IOException {
      HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      server.setExecutor(
          Executors.newCachedThreadPool(
              r -> {
                Thread t = new Thread(r, "guard-accounting-upstream");
                t.setDaemon(true);
                return t;
              }));
      return server;
    }

    /**
     * Serves {@code GET /api/v2/configs} at a fixed generation; 404s everything else (incl. SSE).
     */
    static Upstream configs(int generation) throws IOException {
      HttpServer server = start();
      Upstream u = new Upstream(server);
      server.createContext(
          "/api/v2/configs",
          (HttpExchange ex) -> {
            u.hits.incrementAndGet();
            byte[] body = envelopeJson(generation).getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.getResponseHeaders().add("ETag", "\"gen-" + generation + "\"");
            ex.sendResponseHeaders(200, body.length);
            try (OutputStream out = ex.getResponseBody()) {
              out.write(body);
            }
          });
      server.createContext(
          "/",
          (HttpExchange ex) -> {
            ex.sendResponseHeaders(404, -1);
            ex.close();
          });
      server.start();
      return u;
    }

    /** Serves a live {@code text/event-stream} with a resend then a newer generation. */
    static Upstream sse(int resendGeneration, int thenGeneration) throws IOException {
      HttpServer server = start();
      Upstream u = new Upstream(server);
      server.createContext(
          "/api/v2/sse/config",
          (HttpExchange ex) -> {
            u.hits.incrementAndGet();
            ex.getResponseHeaders().set("Content-Type", "text/event-stream");
            ex.sendResponseHeaders(200, 0);
            OutputStream out = ex.getResponseBody();
            try {
              // Connect-time resend of the generation the client already holds.
              out.write(sseFrame(resendGeneration));
              out.flush();
              Thread.sleep(250);
              // A genuinely newer generation — installing it proves the resend was processed.
              out.write(sseFrame(thenGeneration));
              out.flush();
              Thread.sleep(5000);
            } catch (IOException | InterruptedException ignored) {
              Thread.currentThread().interrupt();
            } finally {
              ex.close();
            }
          });
      server.start();
      return u;
    }

    private static byte[] sseFrame(int generation) {
      return ("id: gen-" + generation + "\ndata: " + envelopeJson(generation) + "\n\n")
          .getBytes(StandardCharsets.UTF_8);
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
