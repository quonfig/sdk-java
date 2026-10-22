package com.quonfig.sdk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.quonfig.sdk.transport.RecordingLogger;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.event.Level;

/**
 * Supervisor contract Test 5, callback-throw isolation (qfg-6vno.28, ITD v2026.10.22.2). A user
 * callback that throws is caught, counted in {@code quonfig_sdk_callback_errors_total{callback}},
 * logged at ERROR with its stack, and is NOT a worker restart; later envelopes are still installed
 * and the callback runs again.
 */
class CallbackErrorsTest {

  private static final Pattern NAMES_CALLBACK =
      Pattern.compile("callback|onConfigUpdate", Pattern.CASE_INSENSITIVE);

  private final List<HttpServer> servers = new ArrayList<>();

  @AfterEach
  void stopServers() {
    for (HttpServer s : servers) s.stop(0);
    servers.clear();
  }

  private static String envelope(int generation) {
    return "{\"configs\":[{\"id\":\"1\",\"key\":\"cb.key\",\"type\":\"config\","
        + "\"valueType\":\"string\",\"default\":{\"rules\":[{\"criteria\":[{\"operator\":"
        + "\"ALWAYS_TRUE\"}],\"value\":{\"type\":\"string\",\"value\":\"g"
        + generation
        + "\"}}]}}],\"meta\":{\"version\":\"v"
        + generation
        + "\",\"environment\":\"production\",\"workspaceId\":\"ws\",\"generation\":"
        + generation
        + "}}";
  }

  /** Configs serves generation 1; SSE serves generations 2 and 3 back to back, then holds open. */
  private String startServer() throws IOException {
    HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    s.setExecutor(Executors.newCachedThreadPool());
    HttpHandler configs =
        ex -> {
          byte[] body = envelope(1).getBytes(StandardCharsets.UTF_8);
          ex.sendResponseHeaders(200, body.length);
          try (OutputStream out = ex.getResponseBody()) {
            out.write(body);
          }
        };
    HttpHandler sse =
        ex -> {
          ex.sendResponseHeaders(200, 0);
          OutputStream out = ex.getResponseBody();
          try {
            String frames =
                "id: v2\ndata: " + envelope(2) + "\n\nid: v3\ndata: " + envelope(3) + "\n\n";
            out.write(frames.getBytes(StandardCharsets.UTF_8));
            out.flush();
            Thread.sleep(20_000);
          } catch (IOException | InterruptedException ignored) {
            // closed by the client
          } finally {
            ex.close();
          }
        };
    s.createContext("/api/v2/configs", configs);
    s.createContext("/api/v2/sse/config", sse);
    s.start();
    servers.add(s);
    return "http://127.0.0.1:" + s.getAddress().getPort();
  }

  private static Options.Builder options(String base, RecordingLogger log) {
    return Options.builder()
        .sdkKey("test-sdk")
        .apiUrls(List.of(base))
        .streamUrls(List.of(base))
        .telemetryUrl(base)
        .environment("production")
        .initTimeout(Duration.ofSeconds(5))
        .disableTelemetry(true)
        .fallbackPollEnabled(false)
        .logger(log);
  }

  private static void await(BooleanSupplier cond) throws InterruptedException {
    long deadline = System.currentTimeMillis() + 10_000;
    while (!cond.getAsBoolean() && System.currentTimeMillis() < deadline) Thread.sleep(10);
  }

  private static RecordingLogger.Entry assertOneErrorWithStack(RecordingLogger log, String msg) {
    List<RecordingLogger.Entry> errors = new ArrayList<>();
    for (RecordingLogger.Entry e : log.entries) {
      if (e.level == Level.ERROR && e.throwable != null && msg.equals(e.throwable.getMessage())) {
        errors.add(e);
      }
    }
    assertEquals(1, errors.size(), "one ERROR carrying the user's exception: " + log.entries);
    RecordingLogger.Entry e = errors.get(0);
    assertTrue(NAMES_CALLBACK.matcher(e.message).find(), "names the callback: " + e.message);
    assertTrue(e.throwable.getStackTrace().length > 0, "stack trace kept");
    return e;
  }

  @Test
  void onConfigUpdateThrowIsCountedNotARestart() throws Exception {
    String base = startServer();
    RecordingLogger log = new RecordingLogger();
    AtomicInteger calls = new AtomicInteger();
    AtomicInteger otherListener = new AtomicInteger();
    String msg = "user onConfigUpdate boom (test 5)";
    Options o =
        options(base, log)
            .onConfigUpdate(
                () -> {
                  if (calls.incrementAndGet() == 1) throw new IllegalStateException(msg);
                })
            .build();

    try (Quonfig q = new Quonfig(o)) {
      q.onConfigUpdate(otherListener::incrementAndGet);
      await(() -> "g3".equals(q.getString("cb.key", "D")) && calls.get() >= 3);

      assertEquals("g3", q.getString("cb.key", "D"), "later envelopes still install");
      assertTrue(calls.get() >= 3, "the callback runs again after its throw: " + calls.get());
      assertTrue(otherListener.get() >= 2, "remaining listeners still run");
      assertEquals(1, q.callbackErrorsTotalForTest("on_config_update"));
      assertEquals(0, q.callbackErrorsTotalForTest("on_connection_state_change"));
      assertEquals(0, q.workerRestartTotalForTest("1"), "a callback throw is not a restart");
      assertEquals(0, q.workerRestartTotalForTest("2"));
      assertEquals(ConnectionState.CONNECTED, q.connectionState());
      assertNotNull(q.lastSuccessfulRefresh());
      assertOneErrorWithStack(log, msg);
    }
  }

  @Test
  void onConnectionStateChangeThrowIsCountedNotARestart() throws Exception {
    String base = startServer();
    RecordingLogger log = new RecordingLogger();
    AtomicInteger calls = new AtomicInteger();
    AtomicInteger otherListener = new AtomicInteger();
    String msg = "user onConnectionStateChange boom (test 5)";
    Options o =
        options(base, log)
            .onSseConnectionStateChange(
                connected -> {
                  if (calls.incrementAndGet() == 1) throw new IllegalStateException(msg);
                })
            .build();

    try (Quonfig q = new Quonfig(o)) {
      q.onSseConnectionStateChange(c -> otherListener.incrementAndGet());
      await(() -> "g3".equals(q.getString("cb.key", "D")) && calls.get() >= 1);

      assertEquals("g3", q.getString("cb.key", "D"), "envelopes still install");
      assertTrue(otherListener.get() >= 1, "remaining listeners still run");
      assertEquals(1, q.callbackErrorsTotalForTest("on_connection_state_change"));
      assertEquals(0, q.callbackErrorsTotalForTest("on_config_update"));
      assertEquals(0, q.workerRestartTotalForTest("1"), "a callback throw is not a restart");
      assertEquals(0, q.workerRestartTotalForTest("2"));
      assertEquals(ConnectionState.CONNECTED, q.connectionState());
      assertOneErrorWithStack(log, msg);
    }
  }

  @Test
  void unknownCallbackNameIsRejected() throws Exception {
    String base = startServer();
    try (Quonfig q = new Quonfig(options(base, new RecordingLogger()).build())) {
      IllegalArgumentException e =
          org.junit.jupiter.api.Assertions.assertThrows(
              IllegalArgumentException.class, () -> q.callbackErrorsTotalForTest("nope"));
      assertFalse(e.getMessage().isEmpty());
    }
  }
}
