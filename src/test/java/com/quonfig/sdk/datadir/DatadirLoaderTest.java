package com.quonfig.sdk.datadir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.quonfig.sdk.wire.ConfigEnvelope;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Acceptance tests for the wire-format datadir loader at {@link DatadirLoader}. Mirrors the
 * sdk-node loader ({@code sdk-node/src/datadir.ts}) and sdk-go workspace loader ({@code
 * workspace_loader.go}) so an envelope produced from a static distribution is interchangeable with
 * one served by api-delivery.
 */
class DatadirLoaderTest {

  @Test
  void load_integrationCorpus_returnsEnvelopeWithConfigsAndExpectedKeys() {
    Path corpus = locateCorpus();
    ConfigEnvelope env = DatadirLoader.load(corpus, "Production");

    assertNotNull(env);
    assertNotNull(env.meta());
    assertEquals("Production", env.meta().environment());
    assertTrue(env.configs().size() > 0, "expected non-empty configs from fixture");

    Set<String> keys = new HashSet<>();
    for (JsonNode node : env.configs()) {
      keys.add(node.path("key").asText());
    }
    // These three keys exist in the corpus across configs/, feature-flags/, and segments/ — so
    // assertion proves the loader walked all of them, not just one subdir.
    assertTrue(keys.contains("basic.rule.config"), "missing configs/ key: " + keys);
    assertTrue(keys.contains("always.true"), "missing feature-flags/ key: " + keys);
    assertTrue(keys.contains("emails"), "missing segments/ key: " + keys);
  }

  @Test
  void load_unknownEnvironment_throwsListingAvailable(@TempDir Path workspace) throws Exception {
    Files.writeString(
        workspace.resolve("quonfig.json"), "{\"environments\":[\"production\",\"staging\"]}");
    Files.createDirectories(workspace.resolve("configs"));

    IllegalArgumentException ex =
        assertThrows(
            IllegalArgumentException.class, () -> DatadirLoader.load(workspace, "development"));
    assertTrue(ex.getMessage().contains("development"), ex.getMessage());
    assertTrue(ex.getMessage().contains("production"), ex.getMessage());
    assertTrue(ex.getMessage().contains("staging"), ex.getMessage());
  }

  @Test
  void load_emptyEnvironment_throws(@TempDir Path workspace) throws Exception {
    Files.writeString(workspace.resolve("quonfig.json"), "{\"environments\":[\"production\"]}");
    assertThrows(IllegalArgumentException.class, () -> DatadirLoader.load(workspace, ""));
    assertThrows(IllegalArgumentException.class, () -> DatadirLoader.load(workspace, null));
  }

  @Test
  void load_validatesEnvironmentAgainstQuonfigJson_caseSensitive(@TempDir Path workspace)
      throws Exception {
    Files.writeString(workspace.resolve("quonfig.json"), "{\"environments\":[\"Production\"]}");
    Files.createDirectories(workspace.resolve("configs"));

    // exact match passes
    ConfigEnvelope ok = DatadirLoader.load(workspace, "Production");
    assertEquals("Production", ok.meta().environment());

    // wrong case fails (matches sdk-node + sdk-go behavior — strings compared exactly)
    assertThrows(IllegalArgumentException.class, () -> DatadirLoader.load(workspace, "production"));
  }

  @Test
  void load_dotfilesAndNonJsonFilesSkipped(@TempDir Path workspace) throws Exception {
    Files.writeString(workspace.resolve("quonfig.json"), "{\"environments\":[\"production\"]}");
    Path configs = workspace.resolve("configs");
    Files.createDirectories(configs);
    Files.writeString(
        configs.resolve("real.json"),
        "{\"id\":\"r1\",\"key\":\"real\",\"type\":\"config\",\"valueType\":\"string\","
            + "\"default\":{\"rules\":[]}}");
    Files.writeString(configs.resolve(".hidden.json"), "{\"key\":\"hidden\"}");
    Files.writeString(configs.resolve("README.md"), "not json");

    ConfigEnvelope env = DatadirLoader.load(workspace, "production");
    assertEquals(1, env.configs().size());
    assertEquals("real", env.configs().get(0).path("key").asText());
  }

  private static Path locateCorpus() {
    String userDir = System.getProperty("user.dir");
    Path candidate =
        Paths.get(userDir, "..", "integration-test-data", "data", "integration-tests").normalize();
    if (Files.isDirectory(candidate)) return candidate;
    return Paths.get(userDir, "integration-test-data", "data", "integration-tests").normalize();
  }
}
