package com.quonfig.sdk.datadir;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.quonfig.sdk.wire.ConfigEnvelope;
import com.quonfig.sdk.wire.Meta;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Reads a Quonfig workspace directory tree (configs/, feature-flags/, segments/, log-levels/,
 * schemas/, plus quonfig.json) into a {@link ConfigEnvelope} — the same wire format api-delivery
 * returns over HTTP. This is the no-network code path used by integration tests and consumers who
 * bootstrap from a static distribution.
 *
 * <p>Mirrors {@code sdk-go/workspace_loader.go loadWorkspaceEnvelope} and {@code
 * sdk-node/src/datadir.ts loadEnvelopeFromDatadir} so an envelope produced from a datadir is
 * interchangeable with one served by api-delivery: same JSON schema per config, same Meta wrapper.
 *
 * <p>Per-config nodes are kept as {@link JsonNode} rather than exploded POJOs so the loader stays
 * forward-compatible with any field additions to the per-config payload — the wire spec evolves on
 * the per-config schema, not the envelope wrapper. Downstream consumers (e.g. the existing {@link
 * com.quonfig.sdk.DatadirLoader} parser) route each node through the shared parser.
 */
public final class DatadirLoader {

  private static final List<String> SUBDIRS =
      List.of("configs", "feature-flags", "segments", "log-levels", "schemas");
  private static final ObjectMapper MAPPER = new ObjectMapper();

  private DatadirLoader() {}

  public static ConfigEnvelope load(Path workspaceDir, String environmentName) {
    if (environmentName == null || environmentName.isEmpty()) {
      throw new IllegalArgumentException(
          "environment required for datadir mode; pass environmentName or set QUONFIG_ENVIRONMENT");
    }

    List<String> available = readEnvironmentNames(workspaceDir.resolve("quonfig.json"));
    if (!available.isEmpty() && !available.contains(environmentName)) {
      throw new IllegalArgumentException(
          "environment \""
              + environmentName
              + "\" not found in workspace; available environments: "
              + String.join(", ", available));
    }

    List<JsonNode> configs = new ArrayList<>();
    for (String subdir : SUBDIRS) {
      Path p = workspaceDir.resolve(subdir);
      if (!Files.isDirectory(p)) continue;
      try (Stream<Path> walk = Files.walk(p)) {
        walk.filter(Files::isRegularFile)
            .filter(f -> f.getFileName().toString().endsWith(".json"))
            .filter(f -> !f.getFileName().toString().startsWith("."))
            .sorted(Comparator.comparing(path -> path.getFileName().toString()))
            .forEach(
                f -> {
                  try {
                    configs.add(MAPPER.readTree(Files.readAllBytes(f)));
                  } catch (IOException e) {
                    throw new UncheckedIOException("parse " + f, e);
                  }
                });
      } catch (IOException e) {
        throw new UncheckedIOException("walk " + p, e);
      }
    }

    String workspaceId =
        workspaceDir.getFileName() != null ? workspaceDir.getFileName().toString() : null;
    Meta meta = new Meta("datadir:" + workspaceDir, environmentName, workspaceId);
    return new ConfigEnvelope(configs, meta);
  }

  private static List<String> readEnvironmentNames(Path manifest) {
    if (!Files.isRegularFile(manifest)) return List.of();
    try {
      JsonNode root = MAPPER.readTree(Files.readAllBytes(manifest));
      JsonNode envs = root.path("environments");
      if (!envs.isArray()) return List.of();
      List<String> out = new ArrayList<>();
      for (JsonNode e : envs) {
        if (e.isTextual()) {
          String s = e.asText().trim();
          if (!s.isEmpty()) out.add(s);
        }
      }
      return out;
    } catch (IOException e) {
      return List.of();
    }
  }
}
