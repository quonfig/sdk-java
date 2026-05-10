package com.quonfig.sdk.wire;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Collections;
import java.util.List;

/**
 * Wire-format wrapper for an HTTP config-download response from {@code api-delivery}. Mirrors
 * {@code sdk-go/config.go ConfigEnvelope}: a list of per-config wire payloads plus response
 * metadata (version, environment, workspace id).
 *
 * <p>Each entry in {@link #configs()} is the same JSON shape that {@link
 * com.quonfig.sdk.DatadirLoader} reads from a workspace file, so consumers can route each node
 * through the existing parser. Held as a {@link JsonNode} rather than an exploded POJO so the
 * envelope is forward-compatible with any field additions to the per-config payload — the wire spec
 * evolves on the per-config schema, not on this wrapper.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public final class ConfigEnvelope {
  private final List<JsonNode> configs;
  private final Meta meta;

  @JsonCreator
  public ConfigEnvelope(
      @JsonProperty("configs") List<JsonNode> configs, @JsonProperty("meta") Meta meta) {
    this.configs = configs == null ? Collections.emptyList() : List.copyOf(configs);
    this.meta = meta;
  }

  @JsonProperty("configs")
  public List<JsonNode> configs() {
    return configs;
  }

  @JsonProperty("meta")
  public Meta meta() {
    return meta;
  }
}
