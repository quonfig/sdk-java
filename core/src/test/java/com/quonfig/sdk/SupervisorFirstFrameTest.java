package com.quonfig.sdk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Supervisor contract Test 9 — a connection is {@code connected} at its first valid frame
 * (integration-test-data chaos/supervisor-test-contract.md at v2026.10.16.4; plan
 * project/plans/2026-10-12-sse-connected-first-frame.md; bead qfg-goi1.10.42, sdk-go canary
 * qfg-goi1.10.39).
 *
 * <p>Real client, real SSE client, real fallback poller, real 127.0.0.1 servers. The config path
 * answers generation 1 / "v1" on the first hit (the init fetch) and generation 2 / "v2" on every
 * later hit, so a fallback poll is observable through the value. The SSE path writes 200 + {@code
 * text/event-stream} and flushes, then runs the per-test body. Requested SSE sleeps are recorded
 * through the wait hook and passed through unchanged (not compressed): the assertions are about
 * real wall-clock behaviour.
 */
class SupervisorFirstFrameTest {

  static final String KEY = "ff.value";

  static String envelope(int gen, String val) {
    return "{\"configs\":[{\"key\":\""
        + KEY
        + "\",\"type\":\"config\",\"valueType\":\"string\",\"default\":{\"rules\":[{\"criteria\":"
        + "[{\"operator\":\"ALWAYS_TRUE\"}],\"value\":{\"type\":\"string\",\"value\":\""
        + val
        + "\"}}]}}],\"meta\":{\"version\":\"gen-"
        + gen
        + "\",\"environment\":\"Production\",\"generation\":"
        + gen
        + "}}";
  }

  /** Per-attempt SSE body, after headers were flushed. {@code n} is the 1-based attempt. */
  interface StreamBody {
    void write(int n, OutputStream out, CountDownLatch release) throws Exception;
  }

  private final List<Rig> rigs = new ArrayList<>();

  @AfterEach
  void tearDown() {
    for (Rig r : rigs) r.close();
    rigs.clear();
  }

  final class Rig implements AutoCloseable {
    final ExecutorService exec = Executors.newCachedThreadPool();
    final List<HttpServer> servers = new ArrayList<>();
    final AtomicInteger sseConns = new AtomicInteger();
    final AtomicInteger configHits = new AtomicInteger();
    final List<Long> connectedEdges = new ArrayList<>(); // nanoTime of each true edge
    final List<Duration> sleeps = new ArrayList<>();
    final CountDownLatch release = new CountDownLatch(1);
    volatile boolean sawConn;
    volatile long sawConnAt;
    volatile boolean sawPoller;
    volatile boolean sampling = true;
    final long start = System.nanoTime();
    final Quonfig client;
    final Thread sampler;

    Rig(Duration threshold, StreamBody body) throws IOException {
      rigs.add(this);
      HttpServer sse = server("/api/v2/sse/config", ex -> sseHandler(ex, body));
      HttpServer cfg = server("/api/v2/configs", this::configHandler);
      String sseBase = "http://127.0.0.1:" + sse.getAddress().getPort();
      String cfgBase = "http://127.0.0.1:" + cfg.getAddress().getPort();
      Options opts =
          Options.builder()
              .sdkKey("first-frame-key")
              .apiUrls(List.of(cfgBase))
              .streamUrls(List.of(sseBase))
              .telemetryUrl(cfgBase)
              .disableTelemetry(true)
              .initTimeout(Duration.ofSeconds(5))
              .fallbackPollIntervalMs(20)
              .fallbackPollThreshold(threshold)
              .onSseConnectionStateChange(
                  connected -> {
                    if (connected) {
                      synchronized (connectedEdges) {
                        connectedEdges.add(System.nanoTime());
                      }
                    }
                  })
              .waitHookForTest(
                  (kind, d) -> {
                    if ("sse".equals(kind)) {
                      synchronized (sleeps) {
                        sleeps.add(d);
                      }
                    }
                    return d;
                  })
              .build();
      client = new Quonfig(opts);
      sampler =
          new Thread(
              () -> {
                while (sampling) {
                  if (client.connectionState() == ConnectionState.CONNECTED && !sawConn) {
                    sawConnAt = System.nanoTime();
                    sawConn = true;
                  }
                  if (client.fallbackPollerActiveForTest()) sawPoller = true;
                  try {
                    Thread.sleep(1);
                  } catch (InterruptedException e) {
                    return;
                  }
                }
              },
              "first-frame-sampler");
      sampler.setDaemon(true);
      sampler.start();
    }

    private HttpServer server(String path, com.sun.net.httpserver.HttpHandler h)
        throws IOException {
      HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      s.createContext(path, h);
      s.setExecutor(exec);
      s.start();
      servers.add(s);
      return s;
    }

    private void sseHandler(HttpExchange ex, StreamBody body) throws IOException {
      int n = sseConns.incrementAndGet();
      ex.getResponseHeaders().set("Content-Type", "text/event-stream");
      ex.sendResponseHeaders(200, 0);
      OutputStream out = ex.getResponseBody();
      try {
        out.flush();
        body.write(n, out, release);
      } catch (Exception ignored) {
        // client went away
      } finally {
        ex.close();
      }
    }

    private void configHandler(HttpExchange ex) throws IOException {
      int n = configHits.incrementAndGet();
      byte[] b = (n == 1 ? envelope(1, "v1") : envelope(2, "v2")).getBytes(StandardCharsets.UTF_8);
      ex.getResponseHeaders().add("Content-Type", "application/json");
      ex.sendResponseHeaders(200, b.length);
      try (OutputStream out = ex.getResponseBody()) {
        out.write(b);
      } finally {
        ex.close();
      }
    }

    String value() {
      return client.getString(KEY, "<none>");
    }

    List<Long> edges() {
      synchronized (connectedEdges) {
        return new ArrayList<>(connectedEdges);
      }
    }

    List<Duration> sleeps() {
      synchronized (sleeps) {
        return new ArrayList<>(sleeps);
      }
    }

    void sleepUntil(Duration sinceStart) throws InterruptedException {
      long left = start + sinceStart.toNanos() - System.nanoTime();
      if (left > 0) TimeUnit.NANOSECONDS.sleep(left);
    }

    @Override
    public void close() {
      sampling = false;
      release.countDown();
      try {
        client.close();
      } catch (RuntimeException ignored) {
        // best effort
      }
      for (HttpServer s : servers) s.stop(0);
      exec.shutdownNow();
    }
  }

  static void waitFor(Duration max, BooleanSupplier cond, String msg) throws InterruptedException {
    long deadline = System.nanoTime() + max.toNanos();
    while (System.nanoTime() < deadline) {
      if (cond.getAsBoolean()) return;
      Thread.sleep(2);
    }
    if (!cond.getAsBoolean()) fail(msg);
  }

  /** 9a — headers only: every attempt is 200 + text/event-stream, flush, close. */
  @Test
  void test9aHeadersOnlyIsAFailedAttempt() throws Exception {
    // Threshold 1s > the longest reconnect sleep at the initial delay (500ms). If every
    // frameless 200 resets the backoff and cancels the engage timer, the stream is never down
    // that long, the poller never engages and config stays frozen at v1.
    Rig rig = new Rig(Duration.ofSeconds(1), (n, out, release) -> {});

    waitFor(
        Duration.ofSeconds(3),
        () -> rig.client.fallbackPollerActiveForTest() && "v2".equals(rig.value()),
        "fallback poller never delivered v2: frameless 200s kept cancelling the engage timer"
            + " (config frozen at "
            + rig.value()
            + ")");
    assertEquals(ConnectionState.FALLING_BACK, rig.client.connectionState());

    rig.sleepUntil(Duration.ofSeconds(3));
    int conns = rig.sseConns.get();
    assertTrue(
        conns <= 4,
        "SSE attempts in 3s = " + conns + ", want <= 4 (backoff reset on frameless 200)");
    List<Duration> sleeps = rig.sleeps();
    for (int i = 0; i < sleeps.size(); i++) {
      Duration floor = Duration.ofMillis(Math.min(250L << i, 15_000L));
      assertTrue(
          sleeps.get(i).compareTo(floor) >= 0,
          "requested SSE sleep #" + i + " = " + sleeps.get(i) + ", want >= " + floor);
    }
    assertEquals(0, rig.edges().size(), "connected edges; 200 headers alone are not a frame");
    assertFalse(rig.sawConn, "connectionState() was CONNECTED at some point after a frameless 200");
  }

  /** 9b — headers early, frame late: flush headers, wait 1s, `: keepalive`, hold open. */
  @Test
  void test9bConnectedAtFirstFrameNotHeaders() throws Exception {
    AtomicLong sentAt = new AtomicLong();
    Rig rig =
        new Rig(
            Duration.ofSeconds(10),
            (n, out, release) -> {
              if (release.await(1, TimeUnit.SECONDS)) return;
              sentAt.set(System.nanoTime());
              out.write(": keepalive\n\n".getBytes(StandardCharsets.UTF_8));
              out.flush();
              release.await();
            });

    waitFor(Duration.ofSeconds(4), () -> sentAt.get() != 0, "server never sent the keepalive");
    waitFor(
        Duration.ofSeconds(2),
        () -> rig.client.connectionState() == ConnectionState.CONNECTED,
        "connectionState() never became CONNECTED after the keepalive");
    Thread.sleep(500);

    List<Long> edges = rig.edges();
    assertEquals(1, edges.size(), "connected edges");
    long earlyMs = TimeUnit.NANOSECONDS.toMillis(sentAt.get() - edges.get(0));
    assertTrue(
        earlyMs <= 50,
        "connected edge reported "
            + earlyMs
            + "ms before the first frame was sent (want >= -50ms)");
    long stateEarlyMs = TimeUnit.NANOSECONDS.toMillis(sentAt.get() - rig.sawConnAt);
    assertTrue(
        stateEarlyMs <= 50,
        "connectionState() was CONNECTED " + stateEarlyMs + "ms before the first frame was sent");
    assertEquals(1, rig.sseConns.get(), "SSE attempts");
  }

  /** 9c — one keepalive then close, every attempt: connected each time, backoff stays initial. */
  @Test
  void test9cKeepaliveThenCloseResetsBackoff() throws Exception {
    Rig rig =
        new Rig(
            Duration.ofSeconds(1),
            (n, out, release) -> {
              out.write(": keepalive\n\n".getBytes(StandardCharsets.UTF_8));
              out.flush();
            });

    rig.sleepUntil(Duration.ofMillis(2500));
    int conns = rig.sseConns.get();
    int edges = rig.edges().size();
    assertTrue(conns >= 3, "SSE attempts in 2.5s = " + conns + ", want >= 3");
    assertTrue(
        edges >= conns - 1 && edges <= conns,
        "connected edges = " + edges + " for " + conns + " attempts, want one per attempt");
    List<Duration> sleeps = rig.sleeps();
    for (int i = 0; i < sleeps.size(); i++) {
      assertTrue(
          sleeps.get(i).compareTo(Duration.ofMillis(500)) <= 0,
          "requested SSE sleep #" + i + " = " + sleeps.get(i) + ", want <= 500ms");
    }
    assertFalse(rig.sawPoller, "fallback poller engaged although every attempt delivered a frame");
    assertEquals("v1", rig.value(), "no poll should have run");
  }

  /** 9d — control: 200 + snapshot envelope, hold open. */
  @Test
  void test9dSnapshotConnects() throws Exception {
    Rig rig =
        new Rig(
            Duration.ofSeconds(10),
            (n, out, release) -> {
              out.write(
                  ("id: gen-2\ndata: " + envelope(2, "v2") + "\n\n")
                      .getBytes(StandardCharsets.UTF_8));
              out.flush();
              release.await();
            });

    waitFor(
        Duration.ofSeconds(3),
        () -> rig.client.connectionState() == ConnectionState.CONNECTED && "v2".equals(rig.value()),
        "snapshot envelope did not connect and install v2");
    Thread.sleep(200);
    assertEquals(1, rig.edges().size(), "connected edges");
    assertFalse(rig.sawPoller, "fallback poller engaged on a healthy stream");
    assertEquals(1, rig.configHits.get(), "config fetches (init only)");
  }
}
