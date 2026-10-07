package com.quonfig.sdk;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * qfg-goi1.2.16 item 5: {@code close()} reads {@code sseClient} and {@code supervisor} once. If it
 * runs after {@code startSse()} has passed its entry {@code closed} check but before those fields
 * are assigned, it used to see nulls, and the SSE reconnect loop and the supervisor's fallback
 * poller then started anyway and ran forever against a closed client. {@code startSse()} now
 * re-checks {@code closed} after assigning them and stops both.
 *
 * <p>The window is a few microseconds, so the test closes the client from inside it through the
 * package-private seam, then watches the stub servers: a leaked SSE loop dials the stream, and a
 * leaked fallback poller (tiny threshold and interval here) polls the configs endpoint.
 */
class CloseDuringInitTest {

  private HttpServer server;
  private final CountDownLatch release = new CountDownLatch(1);

  @AfterEach
  void tearDown() {
    release.countDown();
    if (server != null) server.stop(0);
  }

  private final AtomicInteger configHits = new AtomicInteger();
  private final AtomicInteger streamHits = new AtomicInteger();

  /** Stub serving a delayed configs fetch and a held-open SSE stream; returns its base URL. */
  private String startStub() throws Exception {
    String envelope =
        "{\"configs\":[],\"meta\":{\"version\":\"v1\",\"environment\":\"production\","
            + "\"workspaceId\":\"ws\",\"generation\":1}}";
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.setExecutor(Executors.newCachedThreadPool());
    server.createContext(
        "/api/v2/configs",
        (HttpExchange ex) -> {
          configHits.incrementAndGet();
          try {
            Thread.sleep(300); // lets the test install the seam before init reaches startSse()
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
          byte[] b = envelope.getBytes(StandardCharsets.UTF_8);
          ex.sendResponseHeaders(200, b.length);
          try (OutputStream out = ex.getResponseBody()) {
            out.write(b);
          }
        });
    server.createContext(
        "/api/v2/sse/config",
        (HttpExchange ex) -> {
          streamHits.incrementAndGet();
          ex.getResponseHeaders().set("Content-Type", "text/event-stream");
          ex.sendResponseHeaders(200, 0);
          try {
            release.await(10, TimeUnit.SECONDS);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          } finally {
            ex.close();
          }
        });
    server.start();
    return "http://127.0.0.1:" + server.getAddress().getPort();
  }

  private static Options options(String apiBase, String streamBase) {
    return Options.builder()
        .sdkKey("test-sdk")
        .envLookup(k -> Optional.empty())
        .enableQuonfigUserContext(false)
        .apiUrls(List.of(apiBase))
        .streamUrls(List.of(streamBase))
        .disableTelemetry(true)
        .fallbackPollEnabled(true)
        .fallbackPollIntervalMs(50)
        .fallbackPollThreshold(Duration.ofMillis(50))
        .initTimeout(Duration.ofSeconds(8))
        .build();
  }

  /** A leaked SSE reconnect loop would dial the stream stub. */
  @Test
  void closeInsideStartSseWindow_leavesNoSseLoop() throws Exception {
    String base = startStub();
    Quonfig q = new Quonfig(options(base, base));
    q.startSseHookForTest = q::close;
    awaitInitSettled(q);
    Thread.sleep(1200);
    assertEquals(0, streamHits.get(), "a closed client must not dial the SSE stream");
  }

  /**
   * With the stream refused (port 1), a leaked supervisor's fallback poller engages after the 50ms
   * threshold and polls the configs endpoint.
   */
  @Test
  void closeInsideStartSseWindow_leavesNoFallbackPoller() throws Exception {
    String base = startStub();
    Quonfig q = new Quonfig(options(base, "http://127.0.0.1:1"));
    q.startSseHookForTest = q::close;
    awaitInitSettled(q);
    int afterInit = configHits.get();
    Thread.sleep(1200);
    assertEquals(afterInit, configHits.get(), "a closed client must not run the fallback poller");
  }

  private static void awaitInitSettled(Quonfig q) throws InterruptedException {
    try {
      q.initFuture().get(5, TimeUnit.SECONDS);
    } catch (Exception ignored) {
      // closed mid-init; only the leftovers matter here
    }
    Thread.sleep(100); // let startSse() finish on the init thread
  }
}
