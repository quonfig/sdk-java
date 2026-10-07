package com.quonfig.sdk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.event.Level;

/**
 * qfg-goi1.2.16 item 1+2 (sdk-go 2f6b98b / qfg-9dxb.6 parity): a delivery envelope (HTTP or SSE) is
 * parsed row by row. A row that fails to parse is skipped with a WARN naming its key, and the rest
 * of the envelope installs. If every row fails, the envelope is rejected as before so it can never
 * install as an empty workspace; on the SSE path that rejection is logged at WARN instead of being
 * swallowed silently.
 */
class EnvelopeBadRowTest {

  private final List<HttpServer> servers = new ArrayList<>();
  private final CountDownLatch release = new CountDownLatch(1);

  @AfterEach
  void stopServers() {
    release.countDown();
    for (HttpServer s : servers) s.stop(0);
    servers.clear();
  }

  private static String goodFlag(String key, boolean value) {
    return "{\"id\":\""
        + key
        + "\",\"key\":\""
        + key
        + "\",\"type\":\"feature_flag\",\"valueType\":\"bool\","
        + "\"default\":{\"rules\":[{\"criteria\":[],"
        + "\"value\":{\"type\":\"bool\",\"value\":"
        + value
        + "}}]}}";
  }

  /** A row with no {@code type}: the shape the audit repro used. */
  private static String badRow(String key) {
    return "{\"id\":\"x-" + key + "\",\"key\":\"" + key + "\",\"valueType\":\"bool\"}";
  }

  private static String envelope(int generation, String... rows) {
    return "{\"configs\":["
        + String.join(",", rows)
        + "],\"meta\":{\"version\":\"v"
        + generation
        + "\",\"environment\":\"production\",\"workspaceId\":\"ws\",\"generation\":"
        + generation
        + "}}";
  }

  private HttpServer startServer() throws IOException {
    HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    s.setExecutor(Executors.newCachedThreadPool());
    s.start();
    servers.add(s);
    return s;
  }

  private static String base(HttpServer s) {
    return "http://127.0.0.1:" + s.getAddress().getPort();
  }

  private HttpServer api(String body) throws IOException {
    HttpServer s = startServer();
    s.createContext(
        "/api/v2/configs",
        (HttpExchange ex) -> {
          byte[] b = body.getBytes(StandardCharsets.UTF_8);
          ex.getResponseHeaders().add("Content-Type", "application/json");
          ex.sendResponseHeaders(200, b.length);
          try (OutputStream out = ex.getResponseBody()) {
            out.write(b);
          }
        });
    return s;
  }

  /** SSE stub that sends one event (if non-null) then holds the stream open until teardown. */
  private HttpServer stream(String eventData) throws IOException {
    HttpServer s = startServer();
    s.createContext(
        "/api/v2/sse/config",
        (HttpExchange ex) -> {
          ex.getResponseHeaders().set("Content-Type", "text/event-stream");
          ex.sendResponseHeaders(200, 0);
          OutputStream out = ex.getResponseBody();
          try {
            if (eventData != null) {
              out.write(("data: " + eventData + "\n\n").getBytes(StandardCharsets.UTF_8));
              out.flush();
            }
            release.await(10, TimeUnit.SECONDS);
          } catch (IOException | InterruptedException ignored) {
            Thread.currentThread().interrupt();
          } finally {
            ex.close();
          }
        });
    return s;
  }

  private static Options options(
      HttpServer api, HttpServer stream, ApiUrlsFailoverWarnTest.RecordingLogger log) {
    return Options.builder()
        .sdkKey("test-sdk")
        .logger(log)
        .envLookup(k -> Optional.empty())
        .enableQuonfigUserContext(false)
        .apiUrls(List.of(base(api)))
        .streamUrls(List.of(base(stream)))
        // Strictly above the default configFetchHedgeAbort so the unrelated tuning WARN stays out.
        .initTimeout(Duration.ofSeconds(8))
        .disableTelemetry(true)
        .fallbackPollEnabled(false)
        .build();
  }

  private static boolean await(BooleanSupplier cond, long timeoutMs) throws InterruptedException {
    long deadline = System.currentTimeMillis() + timeoutMs;
    while (System.currentTimeMillis() < deadline) {
      if (cond.getAsBoolean()) return true;
      Thread.sleep(20);
    }
    return cond.getAsBoolean();
  }

  private static boolean warnMentions(ApiUrlsFailoverWarnTest.RecordingLogger log, String text) {
    return log.entries.stream()
        .anyMatch(e -> e.level == Level.WARN && (e.format + " " + e.args).contains(text));
  }

  /** (a) SSE push: the good row's change applies, the bad row is skipped with a WARN. */
  @Test
  void ssePush_skipsBadRow_installsTheRest() throws Exception {
    ApiUrlsFailoverWarnTest.RecordingLogger log = new ApiUrlsFailoverWarnTest.RecordingLogger();
    HttpServer api = api(envelope(1, goodFlag("flag.a", true)));
    HttpServer stream = stream(envelope(2, goodFlag("flag.a", false), badRow("flag.b")));

    try (Quonfig q = new Quonfig(options(api, stream, log))) {
      q.initFuture().get(5, TimeUnit.SECONDS);
      assertTrue(
          await(() -> q.heldGeneration() == 2, 3000),
          "gen-2 SSE push must install despite the bad flag.b row; held="
              + q.heldGeneration()
              + " log="
              + log.entries);
      assertEquals(false, q.getBool("flag.a", true), "flag.a's gen-2 change must apply");
      assertEquals(
          ErrorCode.FLAG_NOT_FOUND,
          q.getBoolDetails("flag.b", true).errorCode(),
          "the skipped row must not be installed");
      assertTrue(warnMentions(log, "flag.b"), "a WARN must name the skipped key: " + log.entries);
    }
  }

  /** (b) HTTP init: init succeeds with the good rows; the bad row is skipped with a WARN. */
  @Test
  void httpInit_skipsBadRow_installsTheRest() throws Exception {
    ApiUrlsFailoverWarnTest.RecordingLogger log = new ApiUrlsFailoverWarnTest.RecordingLogger();
    HttpServer api = api(envelope(1, goodFlag("flag.a", true), badRow("flag.b")));
    HttpServer stream = stream(null);

    try (Quonfig q = new Quonfig(options(api, stream, log))) {
      q.initFuture().get(5, TimeUnit.SECONDS);
      assertEquals(1, q.heldGeneration());
      assertEquals(true, q.getBool("flag.a", false));
      assertEquals(ErrorCode.FLAG_NOT_FOUND, q.getBoolDetails("flag.b", true).errorCode());
      assertTrue(warnMentions(log, "flag.b"), "a WARN must name the skipped key: " + log.entries);
    }
  }

  /** (c1) HTTP init: an envelope whose every row is bad is still rejected (no empty install). */
  @Test
  void httpInit_everyRowBad_rejectsEnvelope() throws Exception {
    ApiUrlsFailoverWarnTest.RecordingLogger log = new ApiUrlsFailoverWarnTest.RecordingLogger();
    HttpServer api = api(envelope(1, badRow("flag.a"), badRow("flag.b")));
    HttpServer stream = stream(null);

    try (Quonfig q = new Quonfig(options(api, stream, log))) {
      assertThrows(ExecutionException.class, () -> q.initFuture().get(5, TimeUnit.SECONDS));
      assertEquals(0, q.heldGeneration());
      assertEquals(0, q.configInstallCount());
    }
  }

  /**
   * (c2) SSE push: an envelope whose every row is bad is rejected (the held generation and values
   * stay), and the SSE handler logs the rejection at WARN instead of swallowing it.
   */
  @Test
  void ssePush_everyRowBad_rejectedWithWarn() throws Exception {
    ApiUrlsFailoverWarnTest.RecordingLogger log = new ApiUrlsFailoverWarnTest.RecordingLogger();
    HttpServer api = api(envelope(1, goodFlag("flag.a", true)));
    HttpServer stream = stream(envelope(2, badRow("flag.a"), badRow("flag.b")));

    try (Quonfig q = new Quonfig(options(api, stream, log))) {
      q.initFuture().get(5, TimeUnit.SECONDS);
      assertTrue(
          await(() -> warnMentions(log, "SSE envelope rejected"), 3000),
          "the SSE handler must WARN when it rejects an envelope: " + log.entries);
      assertEquals(1, q.heldGeneration(), "an all-bad envelope must not install");
      assertEquals(1, q.configInstallCount());
      assertEquals(true, q.getBool("flag.a", false), "held values must stay");
    }
  }

  /**
   * A row missing a required field fails with an error that names the field and the key, not a bare
   * NullPointerException, so the skip WARN (and a datadir reload WARN) is actionable.
   */
  @Test
  void parseConfigNode_missingRequiredField_namesFieldAndKey() throws Exception {
    ObjectMapper m = new ObjectMapper();
    IllegalArgumentException noType =
        assertThrows(
            IllegalArgumentException.class,
            () -> DatadirLoader.parseConfigNode(m.readTree(badRow("flag.b"))));
    assertTrue(noType.getMessage().contains("type"), noType.getMessage());
    assertTrue(noType.getMessage().contains("flag.b"), noType.getMessage());

    IllegalArgumentException noValueType =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                DatadirLoader.parseConfigNode(
                    m.readTree("{\"key\":\"flag.c\",\"type\":\"feature_flag\"}")));
    assertTrue(noValueType.getMessage().contains("valueType"), noValueType.getMessage());
    assertTrue(noValueType.getMessage().contains("flag.c"), noValueType.getMessage());

    IllegalArgumentException noKey =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                DatadirLoader.parseConfigNode(
                    m.readTree("{\"type\":\"feature_flag\",\"valueType\":\"bool\"}")));
    assertTrue(noKey.getMessage().contains("key"), noKey.getMessage());

    IllegalArgumentException noOperator =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                DatadirLoader.parseConfigNode(
                    m.readTree(
                        "{\"key\":\"flag.d\",\"type\":\"feature_flag\",\"valueType\":\"bool\","
                            + "\"default\":{\"rules\":[{\"criteria\":[{\"propertyName\":\"u\"}],"
                            + "\"value\":{\"type\":\"bool\",\"value\":true}}]}}")));
    assertTrue(noOperator.getMessage().contains("operator"), noOperator.getMessage());
    assertTrue(noOperator.getMessage().contains("flag.d"), noOperator.getMessage());
  }
}
