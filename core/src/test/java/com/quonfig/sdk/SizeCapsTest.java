package com.quonfig.sdk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.quonfig.sdk.transport.RecordingLogger;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Supervisor contract Test 10 at the client level (qfg-6vno.20.2, ITD v2026.10.22.2): an oversized
 * SSE event leaves the client not connected and hands freshness to the fallback poller (case 1); an
 * oversized config body installs nothing, stores no ETag and does not advance {@link
 * Quonfig#lastSuccessfulRefresh()} (case 3); a non-2xx body never reaches a log line (case 4).
 */
class SizeCapsTest {

  private static final int MIB = 1024 * 1024;

  private final List<HttpServer> servers = new ArrayList<>();

  @AfterEach
  void stopServers() {
    for (HttpServer s : servers) s.stop(0);
    servers.clear();
  }

  private HttpServer startServer(HttpHandler configs, HttpHandler sse) throws IOException {
    HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    s.createContext("/api/v2/configs", configs);
    s.createContext("/api/v2/sse/config", sse);
    s.setExecutor(Executors.newCachedThreadPool());
    s.start();
    servers.add(s);
    return s;
  }

  private static byte[] envelope(int generation, String value) {
    return ("{\"configs\":[{\"id\":\"1\",\"key\":\"size.caps.key\",\"type\":\"config\","
            + "\"valueType\":\"string\",\"sendToClientSdk\":false,\"default\":{\"rules\":[{"
            + "\"criteria\":[{\"operator\":\"ALWAYS_TRUE\"}],\"value\":{\"type\":\"string\","
            + "\"value\":\""
            + value
            + "\"}}]}}],\"meta\":{\"version\":\"v"
            + generation
            + "\",\"environment\":\"production\",\"workspaceId\":\"ws\",\"generation\":"
            + generation
            + "}}")
        .getBytes(StandardCharsets.UTF_8);
  }

  /** A valid envelope (generation {@code gen}) padded with an unknown field to {@code size}. */
  private static byte[] paddedEnvelope(int gen, String value, int size) {
    String env = new String(envelope(gen, value), StandardCharsets.UTF_8);
    String head = env.substring(0, env.length() - 1) + ",\"pad\":\"";
    String tail = "\"}";
    int pad = size - head.length() - tail.length();
    return (head + new String(filled(pad), StandardCharsets.US_ASCII) + tail)
        .getBytes(StandardCharsets.UTF_8);
  }

  private static void send(HttpExchange ex, int status, byte[] body, String etag)
      throws IOException {
    if (etag != null) ex.getResponseHeaders().add("ETag", etag);
    ex.sendResponseHeaders(status, 0);
    try (OutputStream out = ex.getResponseBody()) {
      out.write(body);
    } catch (IOException ignored) {
      // The client hung up at the cap.
    }
  }

  private static byte[] filled(int size) {
    byte[] b = new byte[size];
    Arrays.fill(b, (byte) 'x');
    return b;
  }

  private static HttpHandler sse503() {
    return ex -> {
      ex.sendResponseHeaders(503, -1);
      ex.close();
    };
  }

  /** Test 10 case 1 through the client: never connected, the fallback poller fetches. */
  @Test
  void oversizedSseEventLeavesClientDisconnectedAndFallbackPollerFetches() throws Exception {
    AtomicInteger configsHits = new AtomicInteger();
    AtomicInteger sseConnects = new AtomicInteger();
    String pad = new String(filled(4 * MIB + 1), StandardCharsets.US_ASCII);
    String big =
        "{\"configs\":[],\"meta\":{\"version\":\"v9\",\"environment\":\"production\","
            + "\"workspaceId\":\"ws\",\"generation\":9},\"pad\":\""
            + pad
            + "\"}";
    HttpHandler configs =
        ex -> {
          int hit = configsHits.incrementAndGet();
          send(ex, 200, envelope(hit, "v" + hit), null);
        };
    HttpHandler sse =
        ex -> {
          sseConnects.incrementAndGet();
          ex.sendResponseHeaders(200, 0);
          OutputStream out = ex.getResponseBody();
          try {
            out.write(("id: g2\ndata: " + big + "\n\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
            Thread.sleep(10_000);
          } catch (IOException | InterruptedException ignored) {
            // The client hung up at the cap.
          } finally {
            ex.close();
          }
        };
    HttpServer server = startServer(configs, sse);
    String base = "http://127.0.0.1:" + server.getAddress().getPort();
    List<ConnectionState> states = new CopyOnWriteArrayList<>();
    List<Boolean> sseEdges = new CopyOnWriteArrayList<>();
    Options o =
        Options.builder()
            .sdkKey("test-sdk")
            .apiUrls(List.of(base))
            .streamUrls(List.of(base))
            .telemetryUrl(base)
            .environment("production")
            .initTimeout(Duration.ofSeconds(5))
            .disableTelemetry(true)
            .fallbackPollThreshold(Duration.ofMillis(150))
            .fallbackPollIntervalMs(100L)
            .onSseConnectionStateChange(sseEdges::add)
            .build();

    try (Quonfig q = new Quonfig(o)) {
      int afterInit = configsHits.get();
      long deadline = System.currentTimeMillis() + 10_000;
      while ((sseConnects.get() < 2 || configsHits.get() <= afterInit)
          && System.currentTimeMillis() < deadline) {
        states.add(q.connectionState());
        Thread.sleep(20);
      }
      assertTrue(sseConnects.get() >= 2, "SSE connects: " + sseConnects.get());
      assertTrue(
          configsHits.get() > afterInit,
          "the fallback poller must fetch /api/v2/configs; init="
              + afterInit
              + " final="
              + configsHits.get());
      assertFalse(states.contains(ConnectionState.CONNECTED), "states: " + states);
      assertFalse(sseEdges.contains(Boolean.TRUE), "SSE edges: " + sseEdges);
    }
  }

  /**
   * Test 10 case 3 through the client: a 16 MiB + 1 body on a refresh installs nothing, does not
   * advance lastSuccessfulRefresh(), and the next request sends no If-None-Match from it.
   */
  @Test
  void oversizedConfigBodyInstallsNothingAndStoresNoEtag() throws Exception {
    AtomicInteger hits = new AtomicInteger();
    List<String> ifNoneMatch = new CopyOnWriteArrayList<>();
    HttpHandler configs =
        ex -> {
          int hit = hits.incrementAndGet();
          ifNoneMatch.add(String.valueOf(ex.getRequestHeaders().getFirst("If-None-Match")));
          if (hit == 2) {
            send(ex, 200, paddedEnvelope(2, "v2", 16 * MIB + 1), "\"big\"");
          } else {
            send(ex, 200, envelope(hit, "v" + hit), "\"e" + hit + "\"");
          }
        };
    HttpServer server = startServer(configs, sse503());
    String base = "http://127.0.0.1:" + server.getAddress().getPort();
    Options o =
        Options.builder()
            .sdkKey("test-sdk")
            .apiUrls(List.of(base))
            .streamUrls(List.of(base))
            .telemetryUrl(base)
            .environment("production")
            .initTimeout(Duration.ofSeconds(5))
            .configFetchTimeout(Duration.ofSeconds(20))
            .disableTelemetry(true)
            .fallbackPollEnabled(false)
            .build();

    try (Quonfig q = new Quonfig(o)) {
      q.initFuture().get(5, TimeUnit.SECONDS);
      assertEquals("v1", q.getString("size.caps.key", "default"));
      Instant before = q.lastSuccessfulRefresh();

      q.refresh(); // hit 2: oversized
      assertEquals(2, hits.get());
      assertEquals(
          "v1", q.getString("size.caps.key", "default"), "an oversized envelope never installs");
      assertEquals(before, q.lastSuccessfulRefresh(), "an oversized body is not a refresh");

      q.refresh(); // hit 3
      assertEquals(3, hits.get());
      assertFalse(ifNoneMatch.get(2).contains("big"), "no ETag from the oversized body");
      assertEquals("v3", q.getString("size.caps.key", "default"));
    }
  }

  /** Test 10 case 4 through the client: a 502 body's marker never reaches a log line. */
  @Test
  void non2xxBodyMarkerNeverLogged() throws Exception {
    String marker = "UPSTREAM-SECRET-MARKER-6vno20";
    byte[] body = filled(2 * MIB);
    byte[] m = marker.getBytes(StandardCharsets.UTF_8);
    System.arraycopy(m, 0, body, 0, m.length);
    System.arraycopy(m, 0, body, body.length - m.length, m.length);
    HttpHandler configs = ex -> send(ex, 502, body, null);
    HttpServer server = startServer(configs, sse503());
    String base = "http://127.0.0.1:" + server.getAddress().getPort();
    RecordingLogger log = new RecordingLogger();
    Options o =
        Options.builder()
            .sdkKey("test-sdk")
            .apiUrls(List.of(base))
            .streamUrls(List.of(base))
            .telemetryUrl(base)
            .environment("production")
            .logger(log)
            .onInitFailure(OnInitFailure.CONTINUE)
            .initTimeout(Duration.ofMillis(1500))
            .disableTelemetry(true)
            .fallbackPollEnabled(false)
            .build();

    try (Quonfig q = new Quonfig(o)) {
      q.refresh();
    }
    assertFalse(log.entries.isEmpty(), "init failure must log something");
    for (RecordingLogger.Entry e : log.entries) {
      assertFalse(e.toString().contains(marker), "log line carries the marker: " + e);
      if (e.throwable != null) {
        for (Throwable t = e.throwable; t != null; t = t.getCause()) {
          assertNull(
              t.getMessage() != null && t.getMessage().contains(marker) ? t : null,
              "logged throwable carries the marker: " + t);
        }
      }
    }
  }
}
