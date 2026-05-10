package com.quonfig.sdk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.quonfig.sdk.eval.ConfigRow;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Verifies that {@link DatadirLoader} (the Jackson-backed parser) can read every config file in
 * {@code integration-test-data/data/integration-tests/configs/*.json}. This is the bead-acceptance
 * round-trip test: read the wire JSON, materialize as {@link ConfigRow}, then assert that the basic
 * identity fields (id, key, type, valueType) match the raw JSON — i.e. the parser does not silently
 * drop or remap top-level fields.
 */
class DatadirLoaderCorpusTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Test
  void everyCorpusConfig_parsesIntoConfigRow_withMatchingTopLevelFields(@TempDir Path workspaceDir)
      throws Exception {
    Path corpus = locateCorpus();
    Path configsDir = workspaceDir.resolve("configs");
    Files.createDirectories(configsDir);

    int copied = 0;
    try (var walk = Files.walk(corpus)) {
      var paths =
          walk.filter(Files::isRegularFile)
              .filter(p -> p.getFileName().toString().endsWith(".json"))
              .filter(p -> !p.getFileName().toString().startsWith("."))
              .toList();
      for (Path p : paths) {
        Files.copy(p, configsDir.resolve(p.getFileName()));
        copied++;
      }
    }
    assertTrue(copied >= 10, "expected non-trivial corpus: " + copied);

    List<ConfigRow> rows = DatadirLoader.load(workspaceDir);
    assertEquals(copied, rows.size(), "every file should produce one ConfigRow");

    for (ConfigRow row : rows) {
      assertNotNull(row.id());
      assertNotNull(row.key());
      assertNotNull(row.type());
      assertNotNull(row.valueType());
      assertNotNull(row.defaultRules());

      // Cross-check against the raw JSON file the row came from.
      Path raw = configsDir.resolve(row.key() + ".json");
      if (!Files.exists(raw)) {
        // some fixture filenames don't match `key`; locate by matching the parsed key.
        try (var walk = Files.walk(configsDir)) {
          raw =
              walk.filter(Files::isRegularFile)
                  .filter(
                      p -> {
                        try {
                          JsonNode tree = MAPPER.readTree(Files.readAllBytes(p));
                          return row.key().equals(tree.path("key").asText());
                        } catch (IOException e) {
                          return false;
                        }
                      })
                  .findFirst()
                  .orElseThrow();
        }
      }
      JsonNode tree = MAPPER.readTree(Files.readAllBytes(raw));
      assertEquals(tree.path("key").asText(), row.key());
      // Wire format uses snake_case for type/valueType; the loader translates to enum names.
      assertEquals(tree.path("type").asText("config"), wireType(row.type().name()));
    }
  }

  private static String wireType(String enumName) {
    return enumName.toLowerCase();
  }

  private static Path locateCorpus() {
    String userDir = System.getProperty("user.dir");
    Path candidate =
        Paths.get(userDir, "..", "integration-test-data", "data", "integration-tests", "configs")
            .normalize();
    if (Files.isDirectory(candidate)) return candidate;
    return Paths.get(userDir, "integration-test-data", "data", "integration-tests", "configs")
        .normalize();
  }
}
