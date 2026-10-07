package com.quonfig.sdk.log4j2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.quonfig.sdk.Options;
import com.quonfig.sdk.Quonfig;
import com.sun.net.httpserver.HttpServer;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.Filter.Result;
import org.junit.jupiter.api.Test;

/**
 * qfg-goi1.2.16 item 3: with a real {@link Quonfig} whose init is still in flight, the filter must
 * answer NEUTRAL at once instead of blocking every logging thread on the init future. After init
 * completes, the configured level (WARN for {@code a.b}) applies.
 */
class QuonfigLog4j2FilterPreInitTest {

  @Test
  void decide_isNeutralWithoutBlocking_whileInitInFlight() throws Exception {
    String envelope =
        "{\"configs\":[{\"id\":\"ll\",\"key\":\"a.b\",\"type\":\"log_level\","
            + "\"valueType\":\"log_level\",\"default\":{\"rules\":[{\"criteria\":[],"
            + "\"value\":{\"type\":\"log_level\",\"value\":\"WARN\"}}]}}],"
            + "\"meta\":{\"version\":\"v1\",\"environment\":\"production\",\"generation\":1}}";
    HttpServer api = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    api.setExecutor(Executors.newCachedThreadPool());
    api.createContext(
        "/api/v2/configs",
        ex -> {
          try {
            Thread.sleep(3000); // slow delivery: init is in flight for ~3s
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
          byte[] b = envelope.getBytes(StandardCharsets.UTF_8);
          ex.sendResponseHeaders(200, b.length);
          try (OutputStream out = ex.getResponseBody()) {
            out.write(b);
          }
        });
    api.start();
    String base = "http://127.0.0.1:" + api.getAddress().getPort();
    Options o =
        Options.builder()
            .sdkKey("test-sdk")
            .envLookup(k -> Optional.empty())
            .enableQuonfigUserContext(false)
            .apiUrls(List.of(base))
            .streamUrls(List.of(base))
            .disableTelemetry(true)
            .fallbackPollEnabled(false)
            .configFetchTimeout(Duration.ofSeconds(8))
            .configFetchHedgeAbort(Duration.ofSeconds(8))
            .initTimeout(Duration.ofSeconds(10))
            .build();
    try (Quonfig q = new Quonfig(o)) {
      QuonfigLog4j2Filter filter = new QuonfigLog4j2Filter(q);

      long start = System.nanoTime();
      Result early = filter.decide("a.b", Level.INFO);
      long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
      assertTrue(elapsedMs < 500, "decide must not block on init; took " + elapsedMs + "ms");
      assertEquals(Result.NEUTRAL, early);

      q.initFuture().get(10, TimeUnit.SECONDS);
      assertEquals(Result.DENY, filter.decide("a.b", Level.INFO));
    } finally {
      api.stop(0);
    }
  }
}
