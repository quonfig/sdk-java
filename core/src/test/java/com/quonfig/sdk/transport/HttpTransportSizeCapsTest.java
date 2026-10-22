package com.quonfig.sdk.transport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.event.Level;

/**
 * Supervisor contract "Rule — size caps", Test 10 cases 3, 4 and 5 (HTTP side), qfg-6vno.20.2. A
 * 200 config body over 16 MiB is a failed leg exactly like a 5xx (with or without {@code
 * Content-Length}); a non-2xx body is read at most 1 KiB and never reaches an error message.
 */
class HttpTransportSizeCapsTest {

  private static final int MIB = 1024 * 1024;
  private static final int BODY_CAP = 16 * MIB;

  private final List<HttpServer> servers = new ArrayList<>();

  @AfterEach
  void tearDown() {
    for (HttpServer s : servers) s.stop(0);
    servers.clear();
  }

  private HttpServer start(HttpHandler handler) throws IOException {
    HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    s.createContext("/", handler);
    s.setExecutor(Executors.newCachedThreadPool());
    s.start();
    servers.add(s);
    return s;
  }

  private static URI uri(HttpServer s, String path) {
    return URI.create("http://127.0.0.1:" + s.getAddress().getPort() + path);
  }

  /** Serves {@code size} bytes with {@code status}, chunked when {@code chunked}. */
  private static HttpHandler bodyOf(int status, int size, boolean chunked, String marker) {
    return ex -> {
      byte[] body = new byte[size];
      Arrays.fill(body, (byte) 'x');
      if (marker != null) {
        byte[] m = marker.getBytes(StandardCharsets.UTF_8);
        System.arraycopy(m, 0, body, 0, m.length);
        System.arraycopy(m, 0, body, size - m.length, m.length);
      }
      ex.getResponseHeaders().add("ETag", "\"big\"");
      ex.sendResponseHeaders(status, chunked ? 0 : size);
      try (OutputStream os = ex.getResponseBody()) {
        os.write(body);
      } catch (IOException ignored) {
        // The client hung up at the cap.
      }
    };
  }

  private static HttpHandler small() {
    return ex -> {
      byte[] body = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
      ex.sendResponseHeaders(200, body.length);
      try (OutputStream os = ex.getResponseBody()) {
        os.write(body);
      }
    };
  }

  private static HttpTransport transport(RecordingLogger log, URI... legs) {
    return HttpTransport.builder()
        .urls(List.of(legs))
        .sdkKey("k")
        .timeout(Duration.ofSeconds(20))
        .configFetchTimeout(Duration.ofSeconds(20))
        .logger(log)
        .build();
  }

  @Test
  void capsAreTheContractConstants() {
    assertEquals(16_777_216, HttpTransport.MAX_CONFIG_BODY_BYTES);
    assertEquals(1024, HttpTransport.MAX_BODY_EXCERPT);
  }

  private void assertOversizedLegFails(boolean chunked) throws Exception {
    HttpServer s = start(bodyOf(200, BODY_CAP + 1, chunked, null));
    RecordingLogger log = new RecordingLogger();
    HttpTransport t = transport(log, uri(s, ""));

    ExecutionException ex =
        assertThrows(
            ExecutionException.class,
            () -> t.get(uri(s, "/api/v2/configs"), null).get(20, TimeUnit.SECONDS));
    HttpTransportException hte = assertInstanceOf(HttpTransportException.class, ex.getCause());
    assertTrue(hte.getMessage().contains("16777216"), "message: " + hte.getMessage());
    assertEquals(-1, t.lastResolvedIndex(), "an oversized body never resolves a leg");
    assertEquals(
        1, log.matching(Level.WARN, "16777216").size(), "one WARN naming the cap: " + log.entries);

    // A repeat in the same episode logs at DEBUG, not WARN.
    assertThrows(
        ExecutionException.class,
        () -> t.get(uri(s, "/api/v2/configs"), null).get(20, TimeUnit.SECONDS));
    assertEquals(1, log.matching(Level.WARN, "16777216").size(), "repeats: " + log.entries);
    assertTrue(log.matching(Level.DEBUG, "16777216").size() >= 1, "repeats: " + log.entries);
  }

  /** Test 10 case 3, with Content-Length: fails before reading the body. */
  @Test
  void oversizedBodyWithContentLengthIsAFailedLeg() throws Exception {
    assertOversizedLegFails(false);
  }

  /** Test 10 case 3, chunked (no Content-Length): counts while reading and stops at cap + 1. */
  @Test
  void oversizedChunkedBodyIsAFailedLeg() throws Exception {
    assertOversizedLegFails(true);
  }

  /** An oversized leg fails over to the next leg exactly like a 5xx. */
  @Test
  void oversizedPrimaryFailsOverToSecondary() throws Exception {
    HttpServer primary = start(bodyOf(200, BODY_CAP + 1, true, null));
    HttpServer secondary = start(small());
    RecordingLogger log = new RecordingLogger();
    HttpTransport t = transport(log, uri(primary, ""), uri(secondary, ""));

    HttpResponse<String> resp =
        t.get(uri(primary, "/api/v2/configs"), null).get(20, TimeUnit.SECONDS);
    assertEquals("{\"ok\":true}", resp.body());
    assertEquals(1, t.lastResolvedIndex());
  }

  /** The per-leg hedge primitive applies the same cap. */
  @Test
  void getFromOversizedBodyFails() throws Exception {
    HttpServer s = start(bodyOf(200, BODY_CAP + 1, true, null));
    HttpTransport t = transport(new RecordingLogger(), uri(s, ""));
    ExecutionException ex =
        assertThrows(
            ExecutionException.class,
            () -> t.getFrom(0, uri(s, "/api/v2/configs"), null).get(20, TimeUnit.SECONDS));
    assertTrue(String.valueOf(ex.getCause()).contains("16777216"), "cause: " + ex.getCause());
  }

  /** Test 10 case 5 (HTTP): a 12 MiB body is under the cap and is returned whole. */
  @Test
  void twelveMebibyteBodyIsReturned() throws Exception {
    HttpServer s = start(bodyOf(200, 12 * MIB, true, null));
    RecordingLogger log = new RecordingLogger();
    HttpTransport t = transport(log, uri(s, ""));
    HttpResponse<String> resp = t.get(uri(s, "/api/v2/configs"), null).get(20, TimeUnit.SECONDS);
    assertEquals(12 * MIB, resp.body().length());
    assertEquals(0, log.matching(Level.WARN, "16777216").size(), "WARNs: " + log.entries);
  }

  /**
   * Test 10 case 4: a 502 with a 2 MiB body carrying a marker. The error message never carries the
   * marker, and the exposed excerpt is at most 1 KiB (the trailing marker is never read).
   */
  @Test
  void non2xxBodyIsReadAtMostOneKibAndNeverInTheMessage() throws Exception {
    String marker = "UPSTREAM-SECRET-MARKER-6vno20";
    for (boolean chunked : new boolean[] {false, true}) {
      HttpServer s = start(bodyOf(502, 2 * MIB, chunked, marker));
      RecordingLogger log = new RecordingLogger();
      HttpTransport t = transport(log, uri(s, ""));
      ExecutionException ex =
          assertThrows(
              ExecutionException.class,
              () -> t.get(uri(s, "/api/v2/configs"), null).get(20, TimeUnit.SECONDS));
      HttpTransportException hte = assertInstanceOf(HttpTransportException.class, ex.getCause());
      assertEquals(502, hte.statusCode());
      assertFalse(hte.getMessage().contains(marker), "message: " + hte.getMessage());
      assertTrue(
          hte.bodyExcerpt().getBytes(StandardCharsets.UTF_8).length <= 1024,
          "excerpt bytes: " + hte.bodyExcerpt().length());
      for (RecordingLogger.Entry e : log.entries) {
        assertFalse(e.toString().contains(marker), "log line carries the marker: " + e);
      }

      ExecutionException ex2 =
          assertThrows(
              ExecutionException.class,
              () -> t.getFrom(0, uri(s, "/api/v2/configs"), null).get(20, TimeUnit.SECONDS));
      HttpTransportException hte2 = assertInstanceOf(HttpTransportException.class, ex2.getCause());
      assertFalse(hte2.getMessage().contains(marker), "message: " + hte2.getMessage());
      assertTrue(hte2.bodyExcerpt().getBytes(StandardCharsets.UTF_8).length <= 1024);
    }
  }
}
