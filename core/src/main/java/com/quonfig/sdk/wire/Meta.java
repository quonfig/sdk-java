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
  private final int generation;

  @JsonCreator
  public Meta(
      @JsonProperty("version") String version,
      @JsonProperty("environment") String environment,
      @JsonProperty("workspaceId") String workspaceId,
      @JsonProperty("generation") int generation) {
    this.version = version;
    this.environment = environment;
    this.workspaceId = workspaceId;
    this.generation = generation;
  }

  /** Back-compat constructor for callers that predate the {@link #generation()} watermark. */
  public Meta(String version, String environment, String workspaceId) {
    this(version, environment, workspaceId, 0);
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

  /**
   * Monotonic, per-branch commit counter ({@code git rev-list --count HEAD}) served by api-delivery
   * alongside {@link #version()}. Unlike the SHA in {@code version} — which is unordered — a higher
   * generation is strictly newer, so the SDK can order two snapshots and reject an older one.
   * Purely additive: servers that predate the watermark omit it and it decodes to {@code 0}. Maps
   * 1:1 to {@code sdk-go Meta.Generation}.
   */
  @JsonProperty("generation")
  public int generation() {
    return generation;
  }
}
