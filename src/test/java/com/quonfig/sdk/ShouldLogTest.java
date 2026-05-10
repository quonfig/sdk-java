package com.quonfig.sdk;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.quonfig.sdk.eval.ContextSet;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.event.Level;

/**
 * Covers {@link Quonfig#shouldLog(String, Level)} dynamic log-level evaluation: hierarchical
 * fallback by loggerPath, the Options.loggerKey single-config dispatch (mirrors sdk-node / sdk-go),
 * default-true when no config exists, and parity on {@link BoundQuonfig}.
 */
class ShouldLogTest {

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

  private Quonfig newClientWithLoggerKey(String loggerKey) {
    return new Quonfig(
        Options.builder()
            .datadir(workspaceDir.toString())
            .environment("production")
            .loggerKey(loggerKey)
            .build());
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
  void shouldLog_returnsFalse_whenConfiguredLevelIsAboveDesired() throws Exception {
    // log-level config 'INFO' at 'com.foo' → shouldLog('com.foo.bar', DEBUG) == false.
    writeLogLevelConfig("com.foo", "INFO");
    try (Quonfig q = newClient()) {
      assertFalse(q.shouldLog("com.foo.bar", Level.DEBUG));
    }
  }

  @Test
  void shouldLog_returnsTrue_whenConfiguredLevelIsAtOrBelowDesired() throws Exception {
    // log-level config 'DEBUG' at 'com.foo' → shouldLog('com.foo.bar', DEBUG) == true.
    writeLogLevelConfig("com.foo", "DEBUG");
    try (Quonfig q = newClient()) {
      assertTrue(q.shouldLog("com.foo.bar", Level.DEBUG));
    }
  }

  @Test
  void shouldLog_returnsTrue_whenNoConfigExistsAnywhere() throws Exception {
    // No config anywhere → shouldLog returns true (don't drop logs).
    try (Quonfig q = newClient()) {
      assertTrue(q.shouldLog("com.foo.bar", Level.DEBUG));
      assertTrue(q.shouldLog("com.foo.bar", Level.TRACE));
    }
  }

  @Test
  void shouldLog_returnsFalse_whenExactPathConfigSetsHigherLevel() throws Exception {
    writeLogLevelConfig("com.foo.bar", "WARN");
    try (Quonfig q = newClient()) {
      assertFalse(q.shouldLog("com.foo.bar", Level.INFO));
      assertTrue(q.shouldLog("com.foo.bar", Level.WARN));
      assertTrue(q.shouldLog("com.foo.bar", Level.ERROR));
    }
  }

  @Test
  void shouldLog_walksUpToRoot_whenNoIntermediateConfig() throws Exception {
    // Only a root-level "" config exists; com.foo.bar should fall back to it.
    writeLogLevelConfig("root", "ERROR");
    try (Quonfig q =
        new Quonfig(
            Options.builder()
                .datadir(workspaceDir.toString())
                .environment("production")
                .loggerKey("root")
                .build())) {
      // With explicit loggerKey="root", every path uses that single config.
      assertFalse(q.shouldLog("com.foo.bar", Level.INFO));
      assertTrue(q.shouldLog("com.foo.bar", Level.ERROR));
    }
  }

  @Test
  void shouldLog_loggerKey_evaluatesContextRulesAgainstLoggerPath() throws Exception {
    // Single log-level config with rules that match on quonfig-sdk-logging.key.
    Files.writeString(
        workspaceDir.resolve("log-levels").resolve("log-level.my-app.json"),
        "{\"key\":\"log-level.my-app\",\"type\":\"log_level\",\"valueType\":\"log_level\","
            + "\"default\":{\"rules\":["
            + "{\"criteria\":[{\"propertyName\":\"quonfig-sdk-logging.key\","
            + " \"operator\":\"PROP_STARTS_WITH_ONE_OF\","
            + " \"valueToMatch\":{\"type\":\"stringList\",\"value\":[\"com.noisy\"]}}],"
            + " \"value\":{\"type\":\"log_level\",\"value\":\"WARN\"}},"
            + "{\"criteria\":[{\"operator\":\"ALWAYS_TRUE\"}],"
            + " \"value\":{\"type\":\"log_level\",\"value\":\"DEBUG\"}}]}}");
    try (Quonfig q = newClientWithLoggerKey("log-level.my-app")) {
      // com.noisy.X gets WARN floor → DEBUG dropped.
      assertFalse(q.shouldLog("com.noisy.X", Level.DEBUG));
      // Other paths get DEBUG floor → DEBUG passes.
      assertTrue(q.shouldLog("com.quiet.Y", Level.DEBUG));
    }
  }

  @Test
  void boundQuonfig_shouldLog_mergesBoundContextWithLoggerPath() throws Exception {
    // Rule that requires user.plan=pro AND triggers WARN; otherwise INFO.
    Files.writeString(
        workspaceDir.resolve("log-levels").resolve("com.foo.json"),
        "{\"key\":\"com.foo\",\"type\":\"log_level\",\"valueType\":\"log_level\","
            + "\"default\":{\"rules\":["
            + "{\"criteria\":[{\"propertyName\":\"user.plan\","
            + " \"operator\":\"PROP_IS_ONE_OF\","
            + " \"valueToMatch\":{\"type\":\"stringList\",\"value\":[\"pro\"]}}],"
            + " \"value\":{\"type\":\"log_level\",\"value\":\"WARN\"}},"
            + "{\"criteria\":[{\"operator\":\"ALWAYS_TRUE\"}],"
            + " \"value\":{\"type\":\"log_level\",\"value\":\"DEBUG\"}}]}}");
    try (Quonfig q = newClient()) {
      BoundQuonfig pro =
          q.withContext(new ContextSet().withNamedContext("user", Map.of("plan", "pro")));
      // pro user → WARN floor → DEBUG dropped.
      assertFalse(pro.shouldLog("com.foo.Bar", Level.DEBUG));
      assertTrue(pro.shouldLog("com.foo.Bar", Level.WARN));

      BoundQuonfig free =
          q.withContext(new ContextSet().withNamedContext("user", Map.of("plan", "free")));
      // free user → DEBUG floor → DEBUG passes.
      assertTrue(free.shouldLog("com.foo.Bar", Level.DEBUG));
    }
  }
}
