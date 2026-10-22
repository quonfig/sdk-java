package com.quonfig.sdk.chaos;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.quonfig.sdk.ChaosSdkSeams;
import com.quonfig.sdk.Options;
import com.quonfig.sdk.Quonfig;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * qfg-6vno.3: every value the chaos harness asserts on must come from the SDK, never from the
 * probe's own bookkeeping. Each test forces the real condition against a live client (a local stub
 * api-delivery, no docker) and checks the probe reports what the SDK did. Before the fix the probe
 * answered with constants or its own model: processStillAlive() was always true,
 * worker_restart_total{layer=2} was always 0, connectionState() invented 'reconnecting', and
 * sse_connect_attempts_total counted only successful connects.
 */
class ChaosProbeSdkSourcedTest {

  private static final Set<String> CONTRACT_STATES =
      Set.of("initializing", "connected", "disconnected", "falling_back");

  private HttpServer server;
  private Quonfig client;

  @AfterEach
  void tearDown() {
    if (client != null) client.close();
    if (server != null) server.stop(0);
  }

  /** A stub api-delivery: one generation-7 envelope on /configs; {@code sse} serves the stream. */
  private String startStub(com.sun.net.httpserver.HttpHandler sse) throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/api/v2/configs",
        (HttpExchange ex) -> {
          byte[] body =
              ("{\"configs\":[],\"meta\":{\"version\":\"v7\",\"environment\":\"production\","
                      + "\"workspaceId\":\"ws\",\"generation\":7}}")
                  .getBytes(StandardCharsets.UTF_8);
          ex.sendResponseHeaders(200, body.length);
          try (OutputStream out = ex.getResponseBody()) {
            out.write(body);
          }
        });
    server.createContext("/api/v2/sse/config", sse);
    server.start();
    return "http://127.0.0.1:" + server.getAddress().getPort();
  }

  private static Options.Builder options(String base) {
    return Options.builder()
        .sdkKey("test-sdk")
        .apiUrls(List.of(base))
        .streamUrls(List.of(base))
        .telemetryUrl(base)
        .environment("production")
        .initTimeout(Duration.ofSeconds(5))
        .disableTelemetry(true);
  }

  private static void refuse(HttpExchange ex) throws java.io.IOException {
    ex.sendResponseHeaders(503, -1);
    ex.close();
  }

  @Test
  void connectionState_isTheClientsOwnState_neverAnInventedValue() throws Exception {
    // Each stream delivers one keepalive frame (connected at the first frame) and then closes,
    // so the client cycles connected -> disconnected -> connected for real.
    String base =
        startStub(
            ex -> {
              ex.getResponseHeaders().add("Content-Type", "text/event-stream");
              ex.sendResponseHeaders(200, 0);
              try (OutputStream out = ex.getResponseBody()) {
                out.write(": keepalive\n\n".getBytes(StandardCharsets.UTF_8));
                out.flush();
              }
            });
    ChaosProbe probe = new ChaosProbe();
    client = ChaosTest.newProbedClient(options(base), probe, false);
    Set<String> seen = new LinkedHashSet<>();
    long until = System.currentTimeMillis() + 3000;
    while (System.currentTimeMillis() < until) {
      seen.add(probe.connectionState());
      Thread.sleep(5);
    }
    for (String s : seen) {
      assertTrue(
          CONTRACT_STATES.contains(s),
          "probe reported '" + s + "', not a connectionState() value; saw " + seen);
    }
    assertTrue(seen.contains("disconnected"), "the drops must show as disconnected; saw " + seen);
  }

  @Test
  void sseConnectAttempts_countsEveryDial_includingFailedOnes() throws Exception {
    String base = startStub(ChaosProbeSdkSourcedTest::refuse);
    ChaosProbe probe = new ChaosProbe();
    client = ChaosTest.newProbedClient(options(base), probe, false);
    long until = System.currentTimeMillis() + 4000;
    while (probe.sdkMetric("quonfig_sse_connect_attempts_total", null) < 2
        && System.currentTimeMillis() < until) {
      Thread.sleep(20);
    }
    assertTrue(
        probe.sdkMetric("quonfig_sse_connect_attempts_total", null) >= 2,
        "every refused SSE dial is an attempt; got "
            + probe.sdkMetric("quonfig_sse_connect_attempts_total", null));
    assertEquals(
        0.0,
        probe.sdkMetric("quonfig_sdk_worker_restart_total", "1"),
        "a dial that never connected is not a Layer 1 restart");
  }

  @Test
  void layer2Restarts_comeFromTheSupervisor() throws Exception {
    String base = startStub(ChaosProbeSdkSourcedTest::refuse);
    ChaosProbe probe = new ChaosProbe();
    // Force the real condition: the Layer 2 worker crashes as soon as it engages, so the
    // supervisor restarts it.
    Options.Builder ob =
        ChaosSdkSeams.waitHook(
            options(base).fallbackPollIntervalMs(1000),
            (kind, requested) -> {
              if ("engage".equals(kind)) return Duration.ZERO;
              if ("poll".equals(kind)) throw new IllegalStateException("forced layer-2 crash");
              return null;
            });
    client = ChaosTest.newProbedClient(ob, probe, false);
    long until = System.currentTimeMillis() + 4000;
    while (probe.sdkMetric("quonfig_sdk_worker_restart_total", "2") < 1
        && System.currentTimeMillis() < until) {
      Thread.sleep(20);
    }
    assertTrue(
        probe.sdkMetric("quonfig_sdk_worker_restart_total", "2") >= 1,
        "the supervisor restarted the crashed Layer 2 worker; the probe must see it");
  }

  @Test
  void processStillAlive_goesFalseWhenAnSdkThreadDiesUncaught() throws Exception {
    ChaosProbe probe = new ChaosProbe();
    try {
      Thread t =
          new Thread(
              () -> {
                throw new IllegalStateException("simulated SDK worker death");
              },
              "quonfig-sse");
      t.start();
      t.join(2000);
      assertFalse(
          probe.processStillAlive(), "an SDK thread died uncaught; processStillAlive must say so");
    } finally {
      probe.close();
    }
  }

  @Test
  void unknownInjectShape_failsTheScenario() {
    assertThrows(
        IllegalArgumentException.class,
        () -> ChaosTest.applyInject(null, new ChaosScenario.Inject()));
  }

  @Test
  void unknownProcessAction_failsTheScenario() {
    ChaosScenario.Process p = new ChaosScenario.Process();
    p.action = "kill_everything";
    assertThrows(
        IllegalArgumentException.class,
        () -> ChaosTest.applyProcess(null, p, new java.util.ArrayList<>()));
  }
}
