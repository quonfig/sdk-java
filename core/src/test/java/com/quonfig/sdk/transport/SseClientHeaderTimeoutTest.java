package com.quonfig.sdk.transport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * qfg-goi1.2.16 item 4: the SSE request bounds the response-header phase. A server that accepts the
 * TCP connection and never sends headers used to pin the SSE thread forever (the read watchdog only
 * starts after headers), so SSE never came back. With a header timeout the loop abandons the hung
 * attempt and reconnects. The timeout must NOT cut a healthy stream that lives longer than it: the
 * body is governed by the read watchdog.
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
            // Keepalive every 100ms for 1.5s: five times the 300ms header timeout.
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
}
