package com.quonfig.sdk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.quonfig.sdk.eval.ContextSet;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
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
}
