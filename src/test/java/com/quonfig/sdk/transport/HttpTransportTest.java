package com.quonfig.sdk.transport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Verifies the HTTP transport against an in-process com.sun.net.httpserver. Mirrors the contract
 * proven by sdk-go/runtime_transport_test.go.
 */
class HttpTransportTest {

  private final List<HttpServer> servers = new ArrayList<>();

  @AfterEach
  void tearDown() {
    for (HttpServer s : servers) {
      s.stop(0);
    }
    servers.clear();
  }

  private HttpServer start(HttpHandler handler) throws IOException {
    HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    s.createContext("/", handler);
    s.start();
    servers.add(s);
    return s;
  }

  private static URI uri(HttpServer s, String path) {
    return URI.create("http://127.0.0.1:" + s.getAddress().getPort() + path);
  }

  private static String basicHeader(String key) {
    return "Basic "
        + Base64.getEncoder().encodeToString(("1:" + key).getBytes(StandardCharsets.UTF_8));
  }

  /**
   * GET sends Authorization: Basic 1:&lt;sdkKey&gt;, X-Quonfig-SDK-Version: java-&lt;version&gt;,
   * and If-None-Match when an etag is supplied. The response 200 body is returned.
   */
  @Test
  void getSendsAuthAndVersionAndEtagHeaders() throws Exception {
    AtomicInteger calls = new AtomicInteger();
    String[] capturedAuth = new String[1];
    String[] capturedVersion = new String[1];
    String[] capturedIfNoneMatch = new String[1];

    HttpServer s =
        start(
            ex -> {
              calls.incrementAndGet();
              capturedAuth[0] = ex.getRequestHeaders().getFirst("Authorization");
              capturedVersion[0] = ex.getRequestHeaders().getFirst("X-Quonfig-SDK-Version");
              capturedIfNoneMatch[0] = ex.getRequestHeaders().getFirst("If-None-Match");
              byte[] body = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
              ex.getResponseHeaders().add("Content-Type", "application/json");
              ex.sendResponseHeaders(200, body.length);
              try (OutputStream os = ex.getResponseBody()) {
                os.write(body);
              }
            });

    HttpTransport t = HttpTransport.builder().urls(List.of(uri(s, ""))).sdkKey("test-key").build();

    HttpResponse<String> resp = t.get(uri(s, "/api/v2/configs"), "W/\"abc-123\"").get();

    assertEquals(1, calls.get());
    assertEquals(200, resp.statusCode());
    assertEquals("{\"ok\":true}", resp.body());
    assertEquals(basicHeader("test-key"), capturedAuth[0]);
    assertNotNull(capturedVersion[0]);
    assertTrue(
        capturedVersion[0].startsWith("java-"),
        "expected java- prefix, got: " + capturedVersion[0]);
    assertEquals("W/\"abc-123\"", capturedIfNoneMatch[0]);
  }

  /** First GET (no etag) must NOT send If-None-Match. */
  @Test
  void getOmitsIfNoneMatchWhenEtagNull() throws Exception {
    String[] capturedIfNoneMatch = new String[1];
    HttpServer s =
        start(
            ex -> {
              capturedIfNoneMatch[0] = ex.getRequestHeaders().getFirst("If-None-Match");
              ex.sendResponseHeaders(200, -1);
              ex.close();
            });

    HttpTransport t = HttpTransport.builder().urls(List.of(uri(s, ""))).sdkKey("k").build();

    t.get(uri(s, "/api/v2/configs"), null).get();
    assertNull(capturedIfNoneMatch[0]);
  }

  /** 304 Not Modified is returned successfully (NOT thrown as an exception). */
  @Test
  void get304IsReturnedNotThrown() throws Exception {
    HttpServer s =
        start(
            ex -> {
              ex.sendResponseHeaders(304, -1);
              ex.close();
            });

    HttpTransport t = HttpTransport.builder().urls(List.of(uri(s, ""))).sdkKey("k").build();

    HttpResponse<String> resp = t.get(uri(s, "/api/v2/configs"), "etag").get();
    assertEquals(304, resp.statusCode());
  }

  /** POST sends the body, auth header, and version header; returns 2xx response. */
  @Test
  void postSendsBodyAndHeaders() throws Exception {
    String[] capturedBody = new String[1];
    String[] capturedAuth = new String[1];
    HttpServer s =
        start(
            ex -> {
              capturedAuth[0] = ex.getRequestHeaders().getFirst("Authorization");
              capturedBody[0] =
                  new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
              byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
              ex.sendResponseHeaders(202, body.length);
              try (OutputStream os = ex.getResponseBody()) {
                os.write(body);
              }
            });

    HttpTransport t = HttpTransport.builder().urls(List.of(uri(s, ""))).sdkKey("test-key").build();

    HttpResponse<String> resp = t.post(uri(s, "/api/v1/telemetry/"), "{\"hello\":\"world\"}").get();

    assertEquals(202, resp.statusCode());
    assertEquals("{\"hello\":\"world\"}", capturedBody[0]);
    assertEquals(basicHeader("test-key"), capturedAuth[0]);
  }

  /**
   * Failover: when the primary URL returns 5xx, the transport tries the secondary in baseURLs
   * order. The successful response from the secondary is returned.
   */
  @Test
  void failoverFromPrimary5xxToSecondary200() throws Exception {
    AtomicInteger primaryCalls = new AtomicInteger();
    AtomicInteger secondaryCalls = new AtomicInteger();

    HttpServer primary =
        start(
            ex -> {
              primaryCalls.incrementAndGet();
              ex.sendResponseHeaders(502, -1);
              ex.close();
            });
    HttpServer secondary =
        start(
            ex -> {
              secondaryCalls.incrementAndGet();
              byte[] body = "{\"from\":\"secondary\"}".getBytes(StandardCharsets.UTF_8);
              ex.sendResponseHeaders(200, body.length);
              try (OutputStream os = ex.getResponseBody()) {
                os.write(body);
              }
            });

    HttpTransport t =
        HttpTransport.builder()
            .urls(List.of(uri(primary, ""), uri(secondary, "")))
            .sdkKey("k")
            .build();

    // Path-only URL — transport must rebase against each base URL on retry.
    HttpResponse<String> resp = t.get(URI.create("/api/v2/configs"), null).get();

    assertEquals(200, resp.statusCode());
    assertEquals("{\"from\":\"secondary\"}", resp.body());
    assertEquals(1, primaryCalls.get());
    assertEquals(1, secondaryCalls.get());
  }

  /** When both URLs fail, the future completes exceptionally with HttpTransportException. */
  @Test
  void allUrlsFailRaisesHttpTransportException() throws Exception {
    HttpServer s =
        start(
            ex -> {
              byte[] body = "boom".getBytes(StandardCharsets.UTF_8);
              ex.sendResponseHeaders(500, body.length);
              try (OutputStream os = ex.getResponseBody()) {
                os.write(body);
              }
            });

    HttpTransport t =
        HttpTransport.builder().urls(List.of(uri(s, ""), uri(s, ""))).sdkKey("k").build();

    CompletableFuture<HttpResponse<String>> fut = t.get(URI.create("/api/v2/configs"), null);
    ExecutionException ex = assertThrows(ExecutionException.class, fut::get);
    assertTrue(ex.getCause() instanceof HttpTransportException, "cause: " + ex.getCause());
    HttpTransportException hte = (HttpTransportException) ex.getCause();
    assertEquals(500, hte.statusCode());
    assertTrue(hte.bodyExcerpt().contains("boom"), "body excerpt: " + hte.bodyExcerpt());
  }

  /** HttpTransportException body excerpt is capped to a few KB. */
  @Test
  void bodyExcerptIsCapped() throws Exception {
    int big = 32 * 1024; // 32KB
    byte[] huge = new byte[big];
    for (int i = 0; i < big; i++) huge[i] = 'X';

    HttpServer s =
        start(
            ex -> {
              ex.sendResponseHeaders(500, huge.length);
              try (OutputStream os = ex.getResponseBody()) {
                os.write(huge);
              }
            });

    HttpTransport t = HttpTransport.builder().urls(List.of(uri(s, ""))).sdkKey("k").build();

    CompletableFuture<HttpResponse<String>> fut = t.get(URI.create("/api/v2/configs"), null);
    ExecutionException ex = assertThrows(ExecutionException.class, fut::get);
    HttpTransportException hte = (HttpTransportException) ex.getCause();
    assertTrue(
        hte.bodyExcerpt().length() < big,
        "expected excerpt smaller than full body, got " + hte.bodyExcerpt().length());
    assertTrue(
        hte.bodyExcerpt().length() <= 8192,
        "expected excerpt <= 8192 chars, got " + hte.bodyExcerpt().length());
  }

  /** First successful response stops trying additional URLs. */
  @Test
  void firstSuccessShortCircuitsRemainingUrls() throws Exception {
    AtomicInteger primaryCalls = new AtomicInteger();
    AtomicInteger secondaryCalls = new AtomicInteger();

    HttpServer primary =
        start(
            ex -> {
              primaryCalls.incrementAndGet();
              byte[] body = "ok".getBytes(StandardCharsets.UTF_8);
              ex.sendResponseHeaders(200, body.length);
              try (OutputStream os = ex.getResponseBody()) {
                os.write(body);
              }
            });
    HttpServer secondary =
        start(
            ex -> {
              secondaryCalls.incrementAndGet();
              ex.sendResponseHeaders(500, -1);
              ex.close();
            });

    HttpTransport t =
        HttpTransport.builder()
            .urls(List.of(uri(primary, ""), uri(secondary, "")))
            .sdkKey("k")
            .build();

    t.get(URI.create("/api/v2/configs"), null).get();
    assertEquals(1, primaryCalls.get());
    assertEquals(0, secondaryCalls.get(), "secondary must not be called when primary succeeds");
  }
}
