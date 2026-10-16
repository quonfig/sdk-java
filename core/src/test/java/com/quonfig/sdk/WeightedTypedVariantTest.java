package com.quonfig.sdk;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A weighted rollout variant is parsed with the same typed decoder as a top-level value, so json,
 * duration, log_level and provided (ENV_VAR) variants keep their type (qfg-lrlh, sdk-go
 * evalcore/types.go parity).
 */
class WeightedTypedVariantTest {

  @TempDir Path workspaceDir;

  private void writeConfig(String key, String valueType, String variant) throws Exception {
    Files.writeString(
        workspaceDir.resolve("configs").resolve(key + ".json"),
        "{\"id\":\"id-"
            + key
            + "\",\"key\":\""
            + key
            + "\",\"type\":\"config\",\"valueType\":\""
            + valueType
            + "\",\"default\":{\"rules\":[{\"criteria\":[{\"operator\":\"ALWAYS_TRUE\"}],"
            + "\"value\":{\"type\":\"weighted_values\",\"value\":{\"weightedValues\":["
            + "{\"weight\":100,\"value\":"
            + variant
            + "}],\"hashByPropertyName\":\"user.key\"}}}]}}");
  }

  @BeforeEach
  void writeWorkspace() throws Exception {
    Files.writeString(
        workspaceDir.resolve("quonfig.json"),
        "{\"workspace\":\"test-ws\",\"environments\":[\"production\"]}");
    Files.createDirectories(workspaceDir.resolve("configs"));
    Files.createDirectories(workspaceDir.resolve("feature-flags"));
    Files.createDirectories(workspaceDir.resolve("segments"));
    writeConfig("w.json", "json", "{\"type\":\"json\",\"value\":{\"a\":1,\"b\":\"c\"}}");
    writeConfig("w.duration", "duration", "{\"type\":\"duration\",\"value\":\"PT1.5S\"}");
    writeConfig(
        "w.provided",
        "int",
        "{\"type\":\"provided\",\"value\":{\"source\":\"ENV_VAR\",\"lookup\":\"W_NUM\"}}");
  }

  private Quonfig newClient() {
    return new Quonfig(
        Options.builder()
            .datadir(workspaceDir.toString())
            .environment("production")
            .envLookup(k -> "W_NUM".equals(k) ? Optional.of("42") : Optional.empty())
            .enableQuonfigUserContext(false)
            .disableTelemetry(true)
            .build());
  }

  @Test
  void weightedJsonVariant_getJsonReturnsTheMap() {
    try (Quonfig q = newClient()) {
      EvaluationDetails<Object> d = q.getJsonDetails("w.json", null);
      assertEquals(Map.of("a", 1L, "b", "c"), d.value(), "details: " + d);
      assertEquals(Reason.SPLIT, d.reason(), "details: " + d);
    }
  }

  @Test
  void weightedDurationVariant_getDurationReturnsTheDuration() {
    try (Quonfig q = newClient()) {
      EvaluationDetails<Duration> d = q.getDurationDetails("w.duration", Duration.ZERO);
      assertEquals(Duration.ofMillis(1500), d.value(), "details: " + d);
      assertEquals(Reason.SPLIT, d.reason(), "details: " + d);
    }
  }

  @Test
  void weightedProvidedVariant_readsEnvVarCoercedToConfigType() {
    try (Quonfig q = newClient()) {
      EvaluationDetails<Long> d = q.getIntDetails("w.provided", -1L);
      assertEquals(42L, d.value(), "details: " + d);
      assertEquals(Reason.SPLIT, d.reason(), "details: " + d);
    }
  }
}
