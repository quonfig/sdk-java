package com.quonfig.sdk.wire;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Response metadata that accompanies a {@link ConfigEnvelope}. Maps 1:1 to {@code sdk-go Meta} so
 * an envelope serialized by either SDK is consumable by both.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public final class Meta {
  private final String version;
  private final String environment;
  private final String workspaceId;

  @JsonCreator
  public Meta(
      @JsonProperty("version") String version,
      @JsonProperty("environment") String environment,
      @JsonProperty("workspaceId") String workspaceId) {
    this.version = version;
    this.environment = environment;
    this.workspaceId = workspaceId;
  }

  @JsonProperty("version")
  public String version() {
    return version;
  }

  @JsonProperty("environment")
  public String environment() {
    return environment;
  }

  @JsonProperty("workspaceId")
  public String workspaceId() {
    return workspaceId;
  }
}
