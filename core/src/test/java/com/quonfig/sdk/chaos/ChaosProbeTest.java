package com.quonfig.sdk.chaos;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.quonfig.sdk.Options;
import com.quonfig.sdk.Quonfig;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * The probe's {@code lastSuccessfulRefresh} must track the client's own liveness stamp, which
 * advances on every ANSWERED fetch including a 304 (qfg-41nh.15). Stamping from onConfigUpdate
 * froze it at the t=0 install, so 05 exp[2] could not see a fallback poll (qfg-goi1.7 plan sec 2).
 */
class ChaosProbeTest {

  @Test
  void lastSuccessfulRefresh_movesAfter304OnlyRefresh() throws Exception {
    AtomicInteger hits = new AtomicInteger();
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/api/v2/configs",
        (HttpExchange ex) -> {
          if (hits.incrementAndGet() == 1) {
            byte[] body =
                ("{\"configs\":[],\"meta\":{\"version\":\"v7\",\"environment\":\"production\","
                        + "\"workspaceId\":\"ws\",\"generation\":7}}")
                    .getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, body.length);
            try (OutputStream out = ex.getResponseBody()) {
              out.write(body);
            }
          } else {
            ex.sendResponseHeaders(304, -1);
            ex.close();
          }
        });
    server.createContext(
        "/api/v2/sse/config",
        (HttpExchange ex) -> {
          ex.sendResponseHeaders(503, -1);
          ex.close();
        });
    server.start();
    String base = "http://127.0.0.1:" + server.getAddress().getPort();
    ChaosProbe probe = new ChaosProbe();
    try (Quonfig q =
        ChaosTest.newProbedClient(
            Options.builder()
                .sdkKey("test-sdk")
                .apiUrls(List.of(base))
                .streamUrls(List.of(base))
                .telemetryUrl(base)
                .environment("production")
                .initTimeout(Duration.ofSeconds(5))
                .disableTelemetry(true),
            probe,
            false)) {
      q.initFuture().get(5, TimeUnit.SECONDS);
      // Let the init install's callbacks settle so the baseline is the install stamp, not 0.
      long settleUntil = System.currentTimeMillis() + 2000;
      while (probe.lastSuccessfulRefreshMs() == 0 && System.currentTimeMillis() < settleUntil) {
        Thread.sleep(10);
      }
      long afterInit = probe.lastSuccessfulRefreshMs();
      assertTrue(afterInit > 0, "the init install must stamp the probe");
      Thread.sleep(50);
      q.refresh();
      long afterRefresh = probe.lastSuccessfulRefreshMs();
      assertTrue(
          afterRefresh > afterInit,
          "probe freshness must move after a 304-only refresh; init="
              + afterInit
              + " afterRefresh="
              + afterRefresh);
    } finally {
      server.stop(0);
    }
  }
}
