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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * qfg-9dxb.3: delivery payloads must be real envelopes, and an unversioned install must never lower
 * the held generation.
 *
 * <ul>
 *   <li>Fix B — a 200 whose body is not a delivery envelope (no {@code meta} object with a
 *       non-empty {@code version}) is a leg error: it never installs (so it cannot wipe the held
 *       keys), and the hedge / sequential failover moves on to the next leg.
 *   <li>Fix A — an unversioned envelope (generation absent or {@code <= 0}) never changes the held
 *       generation. Since qfg-9dxb.9 it installs only while the held generation is still 0; once a
 *       real generation is held it is a silent no-op.
 * </ul>
 */
final class EnvelopeValidationTest {

  private static final Duration INIT_TIMEOUT = Duration.ofSeconds(8);

  private final List<Upstream> upstreams = new ArrayList<>();

  @AfterEach
  void stopUpstreams() {
    for (Upstream u : upstreams) u.stop();
    upstreams.clear();
  }

  @Test
  void emptyObject200_onEstablishedClient_doesNotWipeKeysOrLowerGeneration() throws Exception {
    assertJunk200KeepsHeldState("{}");
  }

  @Test
  void errorObject200_onEstablishedClient_doesNotWipeKeysOrLowerGeneration() throws Exception {
    assertJunk200KeepsHeldState("{\"error\":\"x\"}");
  }

  @Test
  void emptyVersion200_onEstablishedClient_isRejected() throws Exception {
    assertJunk200KeepsHeldState(
        "{\"configs\":[],\"meta\":{\"version\":\"\",\"environment\":\"production\"}}");
  }

  private void assertJunk200KeepsHeldState(String junk) throws Exception {
    Upstream primary = upstream(envelope("greeting", "hello", 42));
    Quonfig client = client(List.of(primary.url()));
    try {
      client.initFuture().get(8, TimeUnit.SECONDS);
      assertEquals(42, client.heldGeneration());
      assertEquals("hello", client.getString("greeting", "<missing>"));

      primary.body.set(junk);
      client.refresh();

      assertEquals(
          "hello", client.getString("greeting", "<missing>"), "a junk 200 must not wipe held keys");
      assertEquals(42, client.heldGeneration(), "a junk 200 must not lower the held generation");
      assertEquals(1, client.configInstallCount(), "a junk 200 must not install");
    } finally {
      client.close();
    }
  }

  /** Sequential failover (refresh): a junk primary 200 is a leg error, so the secondary serves. */
  @Test
  void junkPrimary200_refreshFailsOverToSecondary() throws Exception {
    Upstream primary = upstream(envelope("greeting", "hello", 42));
    Upstream secondary = upstream(envelope("greeting", "from-secondary", 43));
    Quonfig client = client(List.of(primary.url(), secondary.url()));
    try {
      client.initFuture().get(8, TimeUnit.SECONDS);
      assertEquals(42, client.heldGeneration());

      primary.body.set("{}");
      client.refresh();

      assertEquals(43, client.heldGeneration(), "refresh must fail over past the junk primary");
      assertEquals("from-secondary", client.getString("greeting", "<missing>"));
      assertEquals("secondary", client.resolvedFrom());
    } finally {
      client.close();
    }
  }

  /** Hedged init: a junk primary 200 is a fast leg error, so the secondary hedge fires. */
  @Test
  void junkPrimary200_initHedgesToSecondary() throws Exception {
    Upstream primary = upstream("{\"error\":\"x\"}");
    Upstream secondary = upstream(envelope("greeting", "from-secondary", 7));
    Quonfig client = client(List.of(primary.url(), secondary.url()));
    try {
      client.initFuture().get(8, TimeUnit.SECONDS);
      assertEquals(7, client.heldGeneration());
      assertEquals("from-secondary", client.getString("greeting", "<missing>"));
      assertEquals("secondary", client.resolvedFrom());
    } finally {
      client.close();
    }
  }

  /**
   * A {@code qfg serve} payload carries version + environment but no generation. It is a valid
   * envelope, but once the client holds a real generation it no longer installs (qfg-9dxb.9); the
   * held generation is unchanged.
   */
  @Test
  void qfgServePayload_onClientHoldingRealGeneration_isNoOp() throws Exception {
    Upstream primary = upstream(envelope("greeting", "hello", 42));
    Quonfig client = client(List.of(primary.url()));
    try {
      client.initFuture().get(8, TimeUnit.SECONDS);
      assertEquals(42, client.heldGeneration());

      primary.body.set(
          "{\"configs\":["
              + configJson("greeting", "from-serve")
              + "],\"meta\":{\"version\":\"local-abc\",\"environment\":\"production\"}}");
      client.refresh();

      assertEquals(
          "hello",
          client.getString("greeting", "<missing>"),
          "an unversioned payload must not install over a held real generation");
      assertEquals(1, client.configInstallCount());
      assertEquals(42, client.heldGeneration(), "an unversioned payload must not change held");
    } finally {
      client.close();
    }
  }

  /** A qfg serve payload also seeds a fresh client (held stays 0). */
  @Test
  void qfgServePayload_seedsFreshClient() throws Exception {
    Upstream primary =
        upstream(
            "{\"configs\":["
                + configJson("greeting", "from-serve")
                + "],\"meta\":{\"version\":\"local-abc\",\"environment\":\"production\"}}");
    Quonfig client = client(List.of(primary.url()));
    try {
      client.initFuture().get(8, TimeUnit.SECONDS);
      assertTrue(client.ready());
      assertEquals("from-serve", client.getString("greeting", "<missing>"));
      assertEquals(0, client.heldGeneration());
    } finally {
      client.close();
    }
  }

  /**
   * qfg-9dxb.9: once the client holds a real generation, a gen-0 payload (today only a
   * damaged-store server whose rev-count failed) must not install. Before, it installed OLD content
   * and — because held stays at N (9dxb.3) — the healthy gen-N re-delivery was then rejected as
   * same-generation, sticking the client on OLD until gen N+1.
   */
  @Test
  void gen0_afterHeldPositive_doesNotInstall_andGenNRedeliveryKeepsNew() throws Exception {
    Upstream primary = upstream(envelope("greeting", "NEW", 42));
    Quonfig client = client(List.of(primary.url()));
    try {
      client.initFuture().get(8, TimeUnit.SECONDS);
      assertEquals(42, client.heldGeneration());
      assertEquals("NEW", client.getString("greeting", "<missing>"));

      primary.body.set(envelope("greeting", "OLD", 0));
      client.refresh();
      assertEquals(
          "NEW",
          client.getString("greeting", "<missing>"),
          "a gen-0 payload must not install over a held positive generation");
      assertEquals(42, client.heldGeneration());
      assertEquals(1, client.configInstallCount(), "a rejected gen-0 payload must not install");

      primary.body.set(envelope("greeting", "NEW", 42));
      client.refresh();
      assertEquals(
          "NEW",
          client.getString("greeting", "<missing>"),
          "gen-N re-delivery after a gen-0 payload must leave the client on NEW");
      assertEquals(42, client.heldGeneration());
    } finally {
      client.close();
    }
  }

  /** qfg-9dxb.9: a client that has only ever seen gen 0 keeps installing each gen-0 payload. */
  @Test
  void gen0Only_client_installsEveryGen0Payload() throws Exception {
    Upstream primary = upstream(envelope("greeting", "a", 0));
    Quonfig client = client(List.of(primary.url()));
    try {
      client.initFuture().get(8, TimeUnit.SECONDS);
      assertEquals("a", client.getString("greeting", "<missing>"));
      assertEquals(0, client.heldGeneration());

      primary.body.set(envelope("greeting", "b", 0));
      client.refresh();
      assertEquals("b", client.getString("greeting", "<missing>"));

      primary.body.set(envelope("greeting", "c", 0));
      client.refresh();
      assertEquals("c", client.getString("greeting", "<missing>"));
      assertEquals(3, client.configInstallCount(), "every gen-0 payload installs while held == 0");
      assertEquals(0, client.heldGeneration());
    } finally {
      client.close();
    }
  }

  // ---- helpers ----

  private Quonfig client(List<String> apiUrls) throws IOException {
    return new Quonfig(
        Options.builder()
            .sdkKey("test-key")
            .apiUrls(apiUrls)
            .streamUrls(List.of("http://127.0.0.1:" + closedPort()))
            .disableTelemetry(true)
            .fallbackPollEnabled(false)
            .initTimeout(INIT_TIMEOUT)
            .build());
  }

  private static String configJson(String key, String value) {
    return "{\"id\":\"c-"
        + key
        + "\",\"key\":\""
        + key
        + "\",\"type\":\"config\",\"valueType\":\"string\","
        + "\"default\":{\"rules\":[{\"criteria\":[],"
        + "\"value\":{\"type\":\"string\",\"value\":\""
        + value
        + "\"}}]}}";
  }

  private static String envelope(String key, String value, int generation) {
    return "{\"configs\":["
        + configJson(key, value)
        + "],\"meta\":{\"version\":\"gen-"
        + generation
        + "\",\"environment\":\"production\",\"workspaceId\":\"ws\",\"generation\":"
        + generation
        + "}}";
  }

  private static int closedPort() throws IOException {
    try (ServerSocket s = new ServerSocket(0)) {
      return s.getLocalPort();
    }
  }

  private Upstream upstream(String initialBody) throws IOException {
    Upstream u = new Upstream(initialBody);
    upstreams.add(u);
    return u;
  }

  /** A minimal api-delivery stand-in whose 200 body can be swapped mid-test. */
  private static final class Upstream {
    final AtomicReference<String> body;
    private final HttpServer server;

    Upstream(String initialBody) throws IOException {
      this.body = new AtomicReference<>(initialBody);
      this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      server.setExecutor(
          Executors.newCachedThreadPool(
              r -> {
                Thread t = new Thread(r, "envelope-validation-upstream");
                t.setDaemon(true);
                return t;
              }));
      server.createContext(
          "/api/v2/configs",
          (HttpExchange ex) -> {
            byte[] b = body.get().getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, b.length);
            try (OutputStream out = ex.getResponseBody()) {
              out.write(b);
            }
          });
      server.createContext(
          "/",
          (HttpExchange ex) -> {
            ex.sendResponseHeaders(404, -1);
            ex.close();
          });
      server.start();
    }

    String url() {
      return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    void stop() {
      server.stop(0);
    }
  }
}
