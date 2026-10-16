package com.quonfig.sdk.transport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * First-frame rule at the SSE-client level (supervisor contract Test 2 "Reset" amendment at
 * integration-test-data v2026.10.16.4; bead qfg-goi1.10.42): a session that delivered a frame
 * resets the reconnect backoff, a 200 that ends with no frame does not.
 */
class SseClientFirstFrameTest {

  static final String ENVELOPE =
      "{\"configs\":[],\"meta\":{\"version\":\"gen-2\",\"environment\":\"Production\","
          + "\"generation\":2}}";

  private final List<HttpServer> servers = new ArrayList<>();
  private final CountDownLatch release = new CountDownLatch(1);
  private SseClient client;

  @AfterEach
  void tearDown() {
    release.countDown();
    if (client != null) client.stop();
    for (HttpServer s : servers) s.stop(0);
  }

  @Test
  void backoffResetRequiresFirstFrame() throws Exception {
    AtomicInteger conns = new AtomicInteger();
    HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    s.createContext(
        "/api/v2/sse/config",
        (HttpExchange ex) -> {
          int n = conns.incrementAndGet();
          try {
            if (n == 1 || n == 3) {
              ex.sendResponseHeaders(503, -1);
              return;
            }
            ex.getResponseHeaders().set("Content-Type", "text/event-stream");
            ex.sendResponseHeaders(200, 0);
            OutputStream out = ex.getResponseBody();
            out.flush();
            if (n == 2) {
              out.write(("data: " + ENVELOPE + "\n\n").getBytes(StandardCharsets.UTF_8));
              out.flush();
            } else if (n >= 5) {
              release.await();
            }
            // n == 4: 200 + headers, then close with no frame.
          } catch (IOException | InterruptedException ignored) {
            // client went away
          } finally {
            ex.close();
          }
        });
    s.setExecutor(Executors.newCachedThreadPool());
    s.start();
    servers.add(s);

    List<Duration> sleeps = new ArrayList<>();
    client =
        SseClient.builder()
            .streamUrls(List.of(URI.create("http://127.0.0.1:" + s.getAddress().getPort())))
            .sdkKey("test-key")
            .waitHook(
                (kind, d) -> {
                  synchronized (sleeps) {
                    sleeps.add(d);
                  }
                  return Duration.ofMillis(1);
                })
            .build();
    client.onEnvelope(env -> {});
    client.start();

    long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
    while (conns.get() < 5 && System.nanoTime() < deadline) Thread.sleep(2);
    if (conns.get() < 5) fail("client did not reach the fifth attempt (got " + conns.get() + ")");

    List<Duration> got;
    synchronized (sleeps) {
      got = new ArrayList<>(sleeps);
    }
    assertTrue(got.size() >= 4, "recorded sleeps " + got);
    // Base delays: 500ms (fail), 500ms (reset by the delivered frame), 500ms (fail), then 1s:
    // the frameless 200 must not reset. Requested sleeps are in [base/2, base].
    assertTrue(
        got.get(1).compareTo(Duration.ofMillis(500)) <= 0,
        "sleep after a session with a frame = " + got.get(1) + ", want <= 500ms (reset); " + got);
    assertTrue(
        got.get(3).compareTo(Duration.ofMillis(500)) >= 0,
        "sleep after a frameless 200 = "
            + got.get(3)
            + ", want >= 500ms (grown, not reset); "
            + got);
  }

  /**
   * Frame classification: parseStream reports "frame seen", and fires exactly one connected edge,
   * iff the stream holds a valid frame.
   */
  @ParameterizedTest(name = "{0}")
  @CsvSource(
      delimiter = '|',
      value = {
        "empty body (headers only)|EMPTY|false",
        "keepalive cut off by EOF|: keepalive\\n|false",
        "complete keepalive|: keepalive\\n\\n|true",
        "bare comment|:\\n\\n|true",
        "malformed data block|data: {not json\\n\\n|false",
        "non-envelope JSON|data: {\"hello\":\"world\"}\\n\\n|false",
        "envelope cut off by EOF|data: ENV\\n|false",
        "id-only block|id: gen-2\\n\\n|false",
        "bare blank line|\\n|false",
        "valid envelope|id: gen-2\\ndata: ENV\\n\\n|true",
        "garbage then keepalive|data: nope\\n\\n: keepalive\\n\\n|true",
      })
  void parseStreamFirstFrameClassification(String name, String stream, boolean want)
      throws IOException {
    String body =
        stream.equals("EMPTY") ? "" : stream.replace("\\n", "\n").replace("ENV", ENVELOPE);
    List<Boolean> edges = new ArrayList<>();
    List<Object> delivered = new ArrayList<>();
    SseClient c =
        SseClient.builder()
            .streamUrls(List.of(URI.create("http://127.0.0.1:1")))
            .sdkKey("test-key")
            .build();
    c.onConnectionStateChange(
        v -> {
          edges.add(v);
          // The edge fires before the frame's envelope is handed to the handler.
          assertTrue(delivered.isEmpty(), "envelope delivered before the connected edge");
        });
    c.onEnvelope(delivered::add);
    try {
      boolean got = c.parseStream(new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
      assertEquals(want, got, "frameSeen");
      assertEquals(want ? List.of(true) : List.of(), edges, "connected edges");
    } finally {
      c.stop();
    }
  }
}
