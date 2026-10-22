package com.quonfig.sdk.transport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.quonfig.sdk.wire.ConfigEnvelope;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.event.Level;

/**
 * Supervisor contract "Rule — size caps" and Test 10 cases 1, 2 and 5 (SSE side) against a local
 * server (qfg-6vno.20.2, ITD v2026.10.22.2). One SSE event is capped at 4 MiB: an event over the
 * cap is not a frame, the connection is a failed attempt (backoff grows), a later connection that
 * delivers only that event again never reports connected (a keepalive alone does not clear it), and
 * one WARN names the cap per oversized episode.
 */
class SseClientSizeCapsTest {

  private static final int CAP = 4 * 1024 * 1024;
  private static final int MIB = 1024 * 1024;

  private final List<HttpServer> servers = new ArrayList<>();
  private SseClient client;

  @AfterEach
  void tearDown() {
    if (client != null) {
      client.stop();
      client = null;
    }
    for (HttpServer s : servers) s.stop(0);
    servers.clear();
  }

  private HttpServer start(HttpHandler handler) throws IOException {
    HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    s.createContext("/api/v2/sse/config", handler);
    s.setExecutor(Executors.newCachedThreadPool());
    s.start();
    servers.add(s);
    return s;
  }

  private static URI baseUri(HttpServer s) {
    return URI.create("http://127.0.0.1:" + s.getAddress().getPort());
  }

  /** A valid delivery envelope whose JSON is exactly {@code totalBytes} long. */
  static String paddedEnvelope(String version, int totalBytes) {
    String head = "{\"meta\":{\"version\":\"" + version + "\"},\"configs\":[],\"pad\":\"";
    String tail = "\"}";
    int pad = totalBytes - head.length() - tail.length();
    char[] fill = new char[pad];
    Arrays.fill(fill, 'x');
    return head + new String(fill) + tail;
  }

  private static void write(OutputStream out, String s) throws IOException {
    out.write(s.getBytes(StandardCharsets.UTF_8));
    out.flush();
  }

  private static void holdOpen(long ms) {
    try {
      Thread.sleep(ms);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  private SseClient.Builder builder(HttpServer s, RecordingLogger log, List<Duration> sleeps) {
    return SseClient.builder()
        .streamUrls(List.of(baseUri(s)))
        .sdkKey("test-key")
        .initialDelay(Duration.ofMillis(20))
        .maxDelay(Duration.ofSeconds(5))
        .logger(log)
        .waitHook(
            (kind, d) -> {
              sleeps.add(d);
              return d;
            });
  }

  private static void awaitAtLeast(AtomicInteger n, int want, long timeoutMs)
      throws InterruptedException {
    long deadline = System.currentTimeMillis() + timeoutMs;
    while (n.get() < want && System.currentTimeMillis() < deadline) Thread.sleep(10);
  }

  /**
   * Test 10 case 1 plus the keepalive gate: every connection sends a keepalive, then {@code id: g2}
   * + an envelope of 4 MiB + 1 byte, then holds open. Only the first connection (before any
   * oversized event) may report connected; every later one is a failed attempt with growing
   * backoff, nothing is delivered, and exactly one WARN carries 4194304.
   */
  @Test
  void oversizedEventIsAFailedAttemptAndKeepaliveDoesNotClearTheGate() throws Exception {
    String big = paddedEnvelope("g2", CAP + 1);
    AtomicInteger connects = new AtomicInteger();
    HttpServer s =
        start(
            (HttpExchange ex) -> {
              connects.incrementAndGet();
              ex.getResponseHeaders().set("Content-Type", "text/event-stream");
              ex.sendResponseHeaders(200, 0);
              OutputStream out = ex.getResponseBody();
              try {
                write(out, ": keepalive\n\n");
                write(out, "id: g2\ndata: " + big + "\n\n");
                holdOpen(10_000);
              } catch (IOException ignored) {
                // The client hung up at the cap.
              } finally {
                ex.close();
              }
            });

    RecordingLogger log = new RecordingLogger();
    List<Duration> sleeps = new CopyOnWriteArrayList<>();
    List<Boolean> edges = new CopyOnWriteArrayList<>();
    AtomicInteger delivered = new AtomicInteger();
    client = builder(s, log, sleeps).build();
    client.onEnvelope(env -> delivered.incrementAndGet());
    client.onConnectionStateChange(edges::add);
    client.start();

    awaitAtLeast(connects, 4, 10_000);
    assertTrue(connects.get() >= 4, "expected >= 4 SSE connects, got " + connects.get());
    assertEquals(0, delivered.get(), "an oversized event must never be delivered");
    // The first connection's keepalive (no oversized episode yet) is a frame; after the oversized
    // event no connection may report connected again, keepalive or not.
    assertEquals(List.of(true, false), edges, "connection edges: " + edges);
    // Failed attempts: each requested sleep is in [delay/2, delay] of a doubling delay, so the
    // sequence never shrinks once the oversized event starts every connection.
    assertTrue(sleeps.size() >= 3, "sleeps: " + sleeps);
    for (int i = 2; i < sleeps.size(); i++) {
      assertTrue(
          sleeps.get(i).compareTo(sleeps.get(i - 1)) >= 0, "backoff must keep growing: " + sleeps);
    }
    assertTrue(
        sleeps.get(sleeps.size() - 1).compareTo(sleeps.get(1)) > 0, "backoff must grow: " + sleeps);
    assertEquals(
        1,
        log.matching(Level.WARN, "4194304").size(),
        "exactly one WARN naming the cap per episode, saw: " + log.entries);
    assertTrue(
        log.matching(Level.DEBUG, "4194304").size() >= 1,
        "repeats log at DEBUG, saw: " + log.entries);
  }

  /** Strict Test 10 case 1: no keepalive at all, so the client never reports connected. */
  @Test
  void oversizedEventNeverReportsConnected() throws Exception {
    String big = paddedEnvelope("g2", CAP + 1);
    AtomicInteger connects = new AtomicInteger();
    HttpServer s =
        start(
            (HttpExchange ex) -> {
              connects.incrementAndGet();
              ex.sendResponseHeaders(200, 0);
              OutputStream out = ex.getResponseBody();
              try {
                write(out, "id: g2\ndata: " + big + "\n\n");
                holdOpen(10_000);
              } catch (IOException ignored) {
                // hung up at the cap
              } finally {
                ex.close();
              }
            });
    RecordingLogger log = new RecordingLogger();
    List<Boolean> edges = new CopyOnWriteArrayList<>();
    client = builder(s, log, new CopyOnWriteArrayList<>()).build();
    client.onConnectionStateChange(edges::add);
    client.start();

    awaitAtLeast(connects, 2, 10_000);
    assertTrue(connects.get() >= 2, "expected >= 2 SSE connects, got " + connects.get());
    assertEquals(List.of(), edges, "never connected: " + edges);
    assertEquals(1, log.matching(Level.WARN, "4194304").size(), "WARNs: " + log.entries);
  }

  /** Multi-line data: lines each under the cap whose joined payload is over it are oversized. */
  @Test
  void multiLineDataSumOverTheCapIsOversized() throws Exception {
    char[] half = new char[CAP / 2 + 16];
    Arrays.fill(half, 'y');
    String line = new String(half);
    AtomicInteger connects = new AtomicInteger();
    HttpServer s =
        start(
            (HttpExchange ex) -> {
              connects.incrementAndGet();
              ex.sendResponseHeaders(200, 0);
              OutputStream out = ex.getResponseBody();
              try {
                write(out, "data: " + line + "\ndata: " + line + "\n\n");
                holdOpen(10_000);
              } catch (IOException ignored) {
                // hung up at the cap
              } finally {
                ex.close();
              }
            });
    RecordingLogger log = new RecordingLogger();
    client = builder(s, log, new CopyOnWriteArrayList<>()).build();
    client.start();

    awaitAtLeast(connects, 2, 10_000);
    assertTrue(connects.get() >= 2, "the connection must end at the cap, got " + connects.get());
    assertEquals(1, log.matching(Level.WARN, "4194304").size(), "WARNs: " + log.entries);
  }

  /**
   * Test 10 case 2: {@code data: } then 8 MiB with no newline, stream held open. A reader without a
   * cap waits for a line end forever (the read watchdog is 90 s); the capped reader ends the
   * connection at 4 MiB and retries with backoff.
   */
  @Test
  void unterminatedHugeLineEndsTheConnectionAtTheCap() throws Exception {
    byte[] chunk = new byte[64 * 1024];
    Arrays.fill(chunk, (byte) 'z');
    AtomicInteger connects = new AtomicInteger();
    HttpServer s =
        start(
            (HttpExchange ex) -> {
              connects.incrementAndGet();
              ex.sendResponseHeaders(200, 0);
              OutputStream out = ex.getResponseBody();
              try {
                write(out, "data: ");
                for (int i = 0; i < (8 * MIB) / chunk.length; i++) out.write(chunk);
                out.flush();
                holdOpen(30_000);
              } catch (IOException ignored) {
                // hung up at the cap
              } finally {
                ex.close();
              }
            });
    RecordingLogger log = new RecordingLogger();
    List<Boolean> edges = new CopyOnWriteArrayList<>();
    client = builder(s, log, new CopyOnWriteArrayList<>()).build();
    client.onConnectionStateChange(edges::add);
    client.start();

    awaitAtLeast(connects, 2, 10_000);
    assertTrue(connects.get() >= 2, "expected a retry after the cap, got " + connects.get());
    assertEquals(List.of(), edges);
    assertEquals(1, log.matching(Level.WARN, "4194304").size(), "WARNs: " + log.entries);
  }

  /**
   * The episode ends at the next delivered envelope: oversized, then a good envelope (connected,
   * delivered), then oversized again logs a second WARN.
   */
  @Test
  void envelopeEndsTheEpisode() throws Exception {
    String big = paddedEnvelope("g2", CAP + 1);
    AtomicInteger connects = new AtomicInteger();
    HttpServer s =
        start(
            (HttpExchange ex) -> {
              int n = connects.incrementAndGet();
              ex.sendResponseHeaders(200, 0);
              OutputStream out = ex.getResponseBody();
              try {
                if (n == 3) {
                  write(out, "id: g3\ndata: {\"meta\":{\"version\":\"g3\"},\"configs\":[]}\n\n");
                  write(out, "id: g4\ndata: " + big + "\n\n");
                } else {
                  write(out, "id: g2\ndata: " + big + "\n\n");
                }
                holdOpen(10_000);
              } catch (IOException ignored) {
                // hung up at the cap
              } finally {
                ex.close();
              }
            });
    RecordingLogger log = new RecordingLogger();
    BlockingQueue<ConfigEnvelope> got = new LinkedBlockingQueue<>();
    List<Boolean> edges = new CopyOnWriteArrayList<>();
    client = builder(s, log, new CopyOnWriteArrayList<>()).build();
    client.onEnvelope(got::add);
    client.onConnectionStateChange(edges::add);
    client.start();

    ConfigEnvelope env = got.poll(10, TimeUnit.SECONDS);
    assertNotNull(env, "the good envelope on connection 3 must be delivered");
    assertEquals("g3", env.meta().version());
    awaitAtLeast(connects, 4, 10_000);
    assertEquals(List.of(true, false), edges, "edges: " + edges);
    assertEquals(
        2,
        log.matching(Level.WARN, "4194304").size(),
        "one WARN per episode (two episodes), saw: " + log.entries);
  }

  /** Test 10 case 5 (SSE): a 3 MiB envelope is under the cap and is delivered. */
  @Test
  void threeMebibyteEnvelopeIsDelivered() throws Exception {
    String env3 = paddedEnvelope("g5", 3 * MIB);
    HttpServer s =
        start(
            (HttpExchange ex) -> {
              ex.sendResponseHeaders(200, 0);
              OutputStream out = ex.getResponseBody();
              try {
                write(out, "id: g5\ndata: " + env3 + "\n\n");
                holdOpen(5_000);
              } catch (IOException ignored) {
                // closed by stop()
              } finally {
                ex.close();
              }
            });
    RecordingLogger log = new RecordingLogger();
    BlockingQueue<ConfigEnvelope> got = new LinkedBlockingQueue<>();
    BlockingQueue<Boolean> edges = new LinkedBlockingQueue<>();
    client = builder(s, log, new CopyOnWriteArrayList<>()).build();
    client.onEnvelope(got::add);
    client.onConnectionStateChange(edges::add);
    client.start();

    ConfigEnvelope env = got.poll(10, TimeUnit.SECONDS);
    assertNotNull(env, "a 3 MiB envelope must be delivered");
    assertEquals("g5", env.meta().version());
    assertEquals(Boolean.TRUE, edges.poll(1, TimeUnit.SECONDS));
    assertEquals(0, log.matching(Level.WARN, "4194304").size(), "WARNs: " + log.entries);
  }
}
