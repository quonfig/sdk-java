package com.quonfig.sdk.transport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * qfg-goi1.2.16 item 4: the SSE request bounds the response-header phase. A server that accepts the
 * TCP connection and never sends headers used to pin the SSE thread forever (the read watchdog only
 * starts after headers), so SSE never came back. With a header timeout the loop abandons the hung
 * attempt and reconnects. The timeout must NOT cut a healthy stream that lives longer than it: the
 * body is governed by the read watchdog. And it must not be the read watchdog's window (qfg-rriw):
 * latency delays the headers without widening the gaps between stream bytes.
 */
class SseClientHeaderTimeoutTest {

  private SseClient client;
  private ServerSocket hung;
  private HttpServer server;
  private final List<Socket> accepted = new CopyOnWriteArrayList<>();

  @AfterEach
  void tearDown() throws IOException {
    if (client != null) client.stop();
    if (hung != null) hung.close();
    for (Socket s : accepted) s.close();
    if (server != null) server.stop(0);
  }

  @Test
  void headerHang_abandonsAttemptAndReconnects() throws Exception {
    AtomicInteger connects = new AtomicInteger();
    hung = new ServerSocket(0, 50, java.net.InetAddress.getLoopbackAddress());
    Thread acceptor =
        new Thread(
            () -> {
              try {
                while (!hung.isClosed()) {
                  Socket s = hung.accept(); // accept, read nothing, answer nothing
                  accepted.add(s);
                  connects.incrementAndGet();
                }
              } catch (IOException ignored) {
                // closed in tearDown
              }
            });
    acceptor.setDaemon(true);
    acceptor.start();

    client =
        SseClient.builder()
            .streamUrls(List.of(URI.create("http://127.0.0.1:" + hung.getLocalPort())))
            .sdkKey("k")
            .initialDelay(Duration.ofMillis(50))
            .maxDelay(Duration.ofMillis(100))
            .headerTimeout(Duration.ofMillis(300))
            .build();
    client.start();

    long deadline = System.currentTimeMillis() + 3000;
    while (connects.get() < 2 && System.currentTimeMillis() < deadline) Thread.sleep(20);
    assertTrue(
        connects.get() >= 2,
        "a header hang must be abandoned and retried; connects=" + connects.get());
  }

  @Test
  void healthyStream_outlivesHeaderTimeout() throws Exception {
    AtomicInteger connects = new AtomicInteger();
    AtomicBoolean droppedEarly = new AtomicBoolean();
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.setExecutor(Executors.newCachedThreadPool());
    server.createContext(
        "/api/v2/sse/config",
        (HttpExchange ex) -> {
          connects.incrementAndGet();
          ex.getResponseHeaders().set("Content-Type", "text/event-stream");
          ex.sendResponseHeaders(200, 0);
          OutputStream out = ex.getResponseBody();
          try {
            // Keepalive every 100ms for 1.5s: five times the 300ms window.
            for (int i = 0; i < 15; i++) {
              out.write(":keepalive\n\n".getBytes(StandardCharsets.UTF_8));
              out.flush();
              Thread.sleep(100);
            }
          } catch (IOException e) {
            droppedEarly.set(true); // the client cut the stream
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          } finally {
            ex.close();
          }
        });
    server.start();

    AtomicInteger disconnects = new AtomicInteger();
    client =
        SseClient.builder()
            .streamUrls(List.of(URI.create("http://127.0.0.1:" + server.getAddress().getPort())))
            .sdkKey("k")
            .readWatchdog(Duration.ofMillis(300))
            .headerTimeout(Duration.ofMillis(300))
            .build();
    client.onConnectionStateChange(
        connected -> {
          if (!connected) disconnects.incrementAndGet();
        });
    client.start();

    Thread.sleep(1300);
    assertEquals(1, connects.get(), "a healthy stream must not be reconnected");
    assertEquals(0, disconnects.get(), "a healthy stream must stay connected");
    assertFalse(droppedEarly.get(), "the client must not cut a healthy stream");
  }

  /**
   * qfg-rriw: the header budget must not shrink with the read watchdog. Path latency adds to the
   * time-to-headers but not to the gaps between stream bytes, so a slow-but-live stream that the
   * watchdog tolerates must still connect. Before the fix the header wait was the watchdog window,
   * so headers arriving later than it looped the client forever (chaos 03-latency: 5s latency vs
   * the harness's 5s watchdog). Mirrors sdk-go
   * TestSSEClientConnectsWhenHeadersArriveAfterReadTimeout.
   */
  @Test
  void slowHeaders_beyondReadWatchdog_stillConnect() throws Exception {
    AtomicInteger connects = new AtomicInteger();
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.setExecutor(Executors.newCachedThreadPool());
    server.createContext(
        "/api/v2/sse/config",
        (HttpExchange ex) -> {
          connects.incrementAndGet();
          try {
            Thread.sleep(600); // the latency: headers come two watchdog windows after the request
            ex.getResponseHeaders().set("Content-Type", "text/event-stream");
            ex.sendResponseHeaders(200, 0);
            OutputStream out = ex.getResponseBody();
            for (int i = 0; i < 40; i++) { // keepalives well inside the 300ms watchdog
              out.write(":keepalive\n\n".getBytes(StandardCharsets.UTF_8));
              out.flush();
              Thread.sleep(75);
            }
          } catch (IOException ignored) {
            // the client gave up on this attempt
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          } finally {
            ex.close();
          }
        });
    server.start();

    AtomicBoolean connected = new AtomicBoolean();
    client =
        SseClient.builder()
            .streamUrls(List.of(URI.create("http://127.0.0.1:" + server.getAddress().getPort())))
            .sdkKey("k")
            .initialDelay(Duration.ofMillis(10))
            .maxDelay(Duration.ofMillis(20))
            .readWatchdog(Duration.ofMillis(300))
            .build();
    client.onConnectionStateChange(connected::set);
    client.start();

    long deadline = System.currentTimeMillis() + 3000;
    while (!connected.get() && System.currentTimeMillis() < deadline) Thread.sleep(20);
    assertTrue(
        connected.get(),
        "headers 600ms after the request (> 300ms read watchdog) must not time out while the"
            + " stream keepalives every 75ms; connects="
            + connects.get());
  }

  /**
   * The header bound must not be {@link HttpRequest.Builder#timeout}: from JDK 26 (JDK-8208693)
   * that timeout also covers reading the response body, so a healthy SSE stream would be cut and
   * reconnected every window. The healthy-stream test above cannot see that on the JDK 17
   * toolchain, so pin the mechanism here: the SSE request carries no request timeout.
   */
  @Test
  void sseRequest_carriesNoRequestTimeout_soNewerJdksNeverCutTheBody() throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.setExecutor(Executors.newCachedThreadPool());
    server.createContext(
        "/api/v2/sse/config",
        (HttpExchange ex) -> {
          ex.getResponseHeaders().set("Content-Type", "text/event-stream");
          ex.sendResponseHeaders(200, 0);
          ex.close();
        });
    server.start();

    RecordingHttpClient recording =
        new RecordingHttpClient(
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build());
    client =
        SseClient.builder()
            .streamUrls(List.of(URI.create("http://127.0.0.1:" + server.getAddress().getPort())))
            .sdkKey("k")
            .readWatchdog(Duration.ofMillis(300))
            .httpClient(recording)
            .build();
    client.start();

    long deadline = System.currentTimeMillis() + 3000;
    while (recording.requests.isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(20);
    assertFalse(recording.requests.isEmpty(), "the SSE client never sent a request");
    for (HttpRequest r : recording.requests) {
      assertEquals(
          Optional.empty(),
          r.timeout(),
          "SSE request must not set HttpRequest.timeout (JDK 26+ applies it to the body)");
    }
  }

  /** Delegating HttpClient that records every request the SSE client sends. */
  private static final class RecordingHttpClient extends HttpClient {
    final List<HttpRequest> requests = new CopyOnWriteArrayList<>();
    private final HttpClient d;

    RecordingHttpClient(HttpClient d) {
      this.d = d;
    }

    @Override
    public Optional<CookieHandler> cookieHandler() {
      return d.cookieHandler();
    }

    @Override
    public Optional<Duration> connectTimeout() {
      return d.connectTimeout();
    }

    @Override
    public Redirect followRedirects() {
      return d.followRedirects();
    }

    @Override
    public Optional<ProxySelector> proxy() {
      return d.proxy();
    }

    @Override
    public SSLContext sslContext() {
      return d.sslContext();
    }

    @Override
    public SSLParameters sslParameters() {
      return d.sslParameters();
    }

    @Override
    public Optional<Authenticator> authenticator() {
      return d.authenticator();
    }

    @Override
    public Version version() {
      return d.version();
    }

    @Override
    public Optional<Executor> executor() {
      return d.executor();
    }

    @Override
    public <T> HttpResponse<T> send(HttpRequest req, HttpResponse.BodyHandler<T> h)
        throws IOException, InterruptedException {
      requests.add(req);
      return d.send(req, h);
    }

    @Override
    public <T> CompletableFuture<HttpResponse<T>> sendAsync(
        HttpRequest req, HttpResponse.BodyHandler<T> h) {
      requests.add(req);
      return d.sendAsync(req, h);
    }

    @Override
    public <T> CompletableFuture<HttpResponse<T>> sendAsync(
        HttpRequest req, HttpResponse.BodyHandler<T> h, HttpResponse.PushPromiseHandler<T> p) {
      requests.add(req);
      return d.sendAsync(req, h, p);
    }
  }
}
