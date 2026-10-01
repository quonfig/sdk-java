package com.quonfig.sdk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.quonfig.sdk.ApiUrlsFailoverWarnTest.RecordingLogger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Duration contract (qfg-2agi.8; plan project/plans/2026-10-01-duration-validity.md, decisions 2
 * and 3).
 *
 * <ul>
 *   <li>Milliseconds: exact decimal, rounded half up ({@code PT0.0005S} = 1 ms).
 *   <li>A malformed value, stored or ENV_VAR-provided, never throws from the typed getters: the
 *       caller's default (or null), {@link Reason#ERROR}, and one WARN per key.
 * </ul>
 */
class DurationContractTest {

  @TempDir Path workspaceDir;

  private RecordingLogger logger;
  private final Map<String, String> env = new HashMap<>();

  @BeforeEach
  void writeWorkspace() throws Exception {
    Files.writeString(
        workspaceDir.resolve("quonfig.json"),
        "{\"workspace\":\"test-ws\",\"environments\":[\"production\"]}");
    Files.createDirectories(workspaceDir.resolve("configs"));
    Files.createDirectories(workspaceDir.resolve("feature-flags"));
    Files.createDirectories(workspaceDir.resolve("segments"));
    logger = new RecordingLogger();
  }

  private void writeStored(String key, String value) throws Exception {
    writeRaw(key, "{\"type\":\"duration\",\"value\":\"" + value + "\"}");
  }

  private void writeProvided(String key, String envVar) throws Exception {
    writeRaw(
        key,
        "{\"type\":\"provided\",\"value\":{\"source\":\"ENV_VAR\",\"lookup\":\"" + envVar + "\"}}");
  }

  private void writeRaw(String key, String valueJson) throws Exception {
    Files.writeString(
        workspaceDir.resolve("configs").resolve(key + ".json"),
        "{\"id\":\"id-"
            + key
            + "\",\"key\":\""
            + key
            + "\",\"type\":\"config\",\"valueType\":\"duration\","
            + "\"default\":{\"rules\":[{\"criteria\":[{\"operator\":\"ALWAYS_TRUE\"}],"
            + "\"value\":"
            + valueJson
            + "}]}}");
  }

  private Quonfig newClient() {
    return new Quonfig(
        Options.builder()
            .datadir(workspaceDir.toString())
            .environment("production")
            .logger(logger)
            .envLookup(k -> Optional.ofNullable(env.get(k)))
            .enableQuonfigUserContext(false)
            .disableTelemetry(true)
            .build());
  }

  private long warnCount(String key) {
    return logger.entries.stream()
        .filter(e -> e.level == org.slf4j.event.Level.WARN)
        .filter(e -> e.args.contains(key))
        .count();
  }

  @ParameterizedTest
  @CsvSource({
    "PT2.01S, 2010",
    "PT1.005S, 1005",
    "PT0.0005S, 1",
    "PT0.0004S, 0",
    "PT0.123456789S, 123",
    "PT0.999999999S, 1000",
    "P36500D, 3153600000000",
    "PT876000H, 3153600000000"
  })
  void storedDuration_roundsHalfUpToMillis(String value, long millis) throws Exception {
    writeStored("d", value);
    try (Quonfig q = newClient()) {
      assertEquals(millis, q.getDuration("d", null).toMillis());
      assertEquals(millis, q.getDurationDetails("d", null).value().toMillis());
    }
  }

  @Test
  void envDuration_roundsHalfUpToMillis() throws Exception {
    writeProvided("d", "DUR");
    env.put("DUR", "PT0.0005S");
    try (Quonfig q = newClient()) {
      assertEquals(1, q.getDuration("d", null).toMillis());
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"30s", "PT0.5H", "P1DT", "garbage", ""})
  void storedMalformed_returnsDefault_reasonError_warnsOnce(String value) throws Exception {
    writeStored("bad", value);
    try (Quonfig q = newClient()) {
      Duration def = Duration.ofMillis(7000);
      assertEquals(def, q.getDuration("bad", def));
      assertNull(q.getDuration("bad", null));
      EvaluationDetails<Duration> d = q.getDurationDetails("bad", def);
      assertEquals(def, d.value());
      assertEquals(Reason.ERROR, d.reason());
      assertEquals(1, warnCount("bad"), "one WARN per key: " + logger.entries);
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"30s", "PT0.5H", "P1DT", "garbage"})
  void envMalformed_returnsDefault_reasonError_warnsOnce(String value) throws Exception {
    writeProvided("bad", "DUR");
    env.put("DUR", value);
    try (Quonfig q = newClient()) {
      Duration def = Duration.ofMillis(7000);
      assertEquals(def, q.getDuration("bad", def));
      assertNull(q.getDuration("bad", null));
      EvaluationDetails<Duration> d = q.getDurationDetails("bad", def);
      assertEquals(def, d.value());
      assertEquals(Reason.ERROR, d.reason());
      assertEquals(1, warnCount("bad"), "one WARN per key: " + logger.entries);
    }
  }
}
