package com.quonfig.sdk.telemetry;

/**
 * How named-context data is reported to the telemetry endpoint.
 *
 * <ul>
 *   <li>{@link #NONE} — no context shapes or examples are sent.
 *   <li>{@link #SHAPES_ONLY} — record only the shape (named contexts, property names + types) of
 *       every evaluation context. No property values are sent.
 *   <li>{@link #PERIODIC_EXAMPLE} — record shapes plus a periodic full-context sample, with
 *       confidential properties redacted.
 * </ul>
 *
 * <p>The {@code SHAPES_ONLY} value was renamed from the pre-1.0 {@code SHAPES} during the SDK-1.0
 * enum unification (qfg-6svs, see project/plans/sdk-1.0-unification.md, Section 1). The legacy
 * {@code SHAPES} constant remains as a deprecated forwarder for one minor cycle so existing callers
 * keep compiling; {@link #parse(String)} also accepts the wire value {@code "shapes"} as a
 * deprecated alias for {@code "shapes_only"}.
 */
public enum ContextUploadMode {
  NONE,
  SHAPES_ONLY,
  PERIODIC_EXAMPLE;

  /**
   * Deprecated alias for {@link #SHAPES_ONLY}, retained for one minor cycle so source-level
   * references to {@code ContextUploadMode.SHAPES} keep compiling. New code should use {@link
   * #SHAPES_ONLY}.
   *
   * @deprecated Use {@link #SHAPES_ONLY} instead. Will be removed in a future minor release.
   */
  @Deprecated public static final ContextUploadMode SHAPES = SHAPES_ONLY;

  public static ContextUploadMode parse(String s) {
    if (s == null) return PERIODIC_EXAMPLE;
    switch (s.toLowerCase()) {
      case "none":
        return NONE;
      case "shapes_only":
      case "shapes":
        // "shapes" is the pre-1.0 wire value, retained as a deprecated alias
        // for one minor cycle while consumers migrate to "shapes_only".
        return SHAPES_ONLY;
      case "periodic_example":
      default:
        return PERIODIC_EXAMPLE;
    }
  }
}
