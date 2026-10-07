package com.quonfig.sdk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.quonfig.sdk.eval.ContextSet;
import com.sun.net.httpserver.HttpServer;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Covers {@link Quonfig#getLogLevel(String, ContextSet)} — the accessor the Logback / Log4j2 /
 * Micronaut filter modules consume. {@link Quonfig#shouldLog} returns a boolean comparison; the
 * filters need the actual resolved {@link LogLevel} so they can map it to their own level type.
 */
class GetLogLevelTest {

  @TempDir Path workspaceDir;

  @BeforeEach
  void writeWorkspaceManifest() throws Exception {
    Files.writeString(
        workspaceDir.resolve("quonfig.json"),
        "{\"workspace\":\"test-ws\",\"environments\":[\"production\"]}");
    Files.createDirectories(workspaceDir.resolve("configs"));
    Files.createDirectories(workspaceDir.resolve("feature-flags"));
    Files.createDirectories(workspaceDir.resolve("segments"));
    Files.createDirectories(workspaceDir.resolve("log-levels"));
  }

  private Quonfig newClient() {
    return new Quonfig(
        Options.builder().datadir(workspaceDir.toString()).environment("production").build());
  }

  private void writeLogLevelConfig(String key, String level) throws Exception {
    Files.writeString(
        workspaceDir.resolve("log-levels").resolve(key + ".json"),
        "{\"key\":\""
            + key
            + "\",\"type\":\"log_level\",\"valueType\":\"log_level\","
            + "\"default\":{\"rules\":[{\"criteria\":[{\"operator\":\"ALWAYS_TRUE\"}],"
            + "\"value\":{\"type\":\"log_level\",\"value\":\""
            + level
            + "\"}}]}}");
  }

  @Test
  void getLogLevel_returnsResolvedLevel_whenExactPathConfigured() throws Exception {
    writeLogLevelConfig("com.foo.bar", "WARN");
    try (Quonfig q = newClient()) {
      Optional<LogLevel> resolved = q.getLogLevel("com.foo.bar", null);
      assertTrue(resolved.isPresent());
      assertEquals(LogLevel.WARN, resolved.get());
    }
  }

  @Test
  void getLogLevel_walksUpDottedParents() throws Exception {
    writeLogLevelConfig("com.foo", "INFO");
    try (Quonfig q = newClient()) {
      Optional<LogLevel> resolved = q.getLogLevel("com.foo.bar.Baz", null);
      assertTrue(resolved.isPresent());
      assertEquals(LogLevel.INFO, resolved.get());
    }
  }

  @Test
  void getLogLevel_returnsEmpty_whenNoConfigAnywhere() throws Exception {
    try (Quonfig q = newClient()) {
      Optional<LogLevel> resolved = q.getLogLevel("com.foo.bar", null);
      assertFalse(resolved.isPresent());
    }
  }

  @Test
  void getLogLevel_parsesAllLevelsCaseInsensitively() throws Exception {
    writeLogLevelConfig("a", "trace");
    writeLogLevelConfig("b", "DEBUG");
    writeLogLevelConfig("c", "Info");
    writeLogLevelConfig("d", "WARN");
    writeLogLevelConfig("e", "ERROR");
    writeLogLevelConfig("f", "fatal");
    try (Quonfig q = newClient()) {
      assertEquals(LogLevel.TRACE, q.getLogLevel("a", null).orElseThrow());
      assertEquals(LogLevel.DEBUG, q.getLogLevel("b", null).orElseThrow());
      assertEquals(LogLevel.INFO, q.getLogLevel("c", null).orElseThrow());
      assertEquals(LogLevel.WARN, q.getLogLevel("d", null).orElseThrow());
      assertEquals(LogLevel.ERROR, q.getLogLevel("e", null).orElseThrow());
      assertEquals(LogLevel.FATAL, q.getLogLevel("f", null).orElseThrow());
    }
  }

  /**
   * qfg-goi1.2.16 item 3: before init completes, {@code getLogLevel} returns empty immediately
   * instead of blocking on the init future (up to {@code initTimeout}). The logging filters call it
   * on every log statement, so blocking here stalls every thread that logs during startup. Once
   * init completes, the configured level resolves as usual.
   */
  @Test
  void getLogLevel_returnsEmptyImmediately_beforeInitCompletes() throws Exception {
    String envelope =
        "{\"configs\":[{\"id\":\"ll\",\"key\":\"a.b\",\"type\":\"log_level\","
            + "\"valueType\":\"log_level\",\"default\":{\"rules\":[{\"criteria\":[],"
            + "\"value\":{\"type\":\"log_level\",\"value\":\"WARN\"}}]}}],"
            + "\"meta\":{\"version\":\"v1\",\"environment\":\"production\",\"generation\":1}}";
    HttpServer api = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    api.setExecutor(Executors.newCachedThreadPool());
    api.createContext(
        "/api/v2/configs",
        ex -> {
          try {
            Thread.sleep(3000); // slow delivery: init is in flight for ~3s
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
          byte[] b = envelope.getBytes(StandardCharsets.UTF_8);
          ex.sendResponseHeaders(200, b.length);
          try (OutputStream out = ex.getResponseBody()) {
            out.write(b);
          }
        });
    api.start();
    String base = "http://127.0.0.1:" + api.getAddress().getPort();
    Options o =
        Options.builder()
            .sdkKey("test-sdk")
            .envLookup(k -> Optional.empty())
            .enableQuonfigUserContext(false)
            .apiUrls(List.of(base))
            .streamUrls(List.of(base))
            .disableTelemetry(true)
            .fallbackPollEnabled(false)
            .configFetchTimeout(Duration.ofSeconds(8))
            .configFetchHedgeAbort(Duration.ofSeconds(8))
            .initTimeout(Duration.ofSeconds(10))
            .build();
    try (Quonfig q = new Quonfig(o)) {
      long start = System.nanoTime();
      Optional<LogLevel> early = q.getLogLevel("a.b", null);
      long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
      assertTrue(elapsedMs < 500, "getLogLevel must not block on init; took " + elapsedMs + "ms");
      assertFalse(early.isPresent(), "no opinion before init completes");

      q.initFuture().get(10, TimeUnit.SECONDS);
      assertEquals(LogLevel.WARN, q.getLogLevel("a.b", null).orElseThrow());
    } finally {
      api.stop(0);
    }
  }
}
