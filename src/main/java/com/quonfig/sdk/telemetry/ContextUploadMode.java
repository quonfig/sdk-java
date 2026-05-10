package com.quonfig.sdk.telemetry;

/**
 * How named-context data is reported to the telemetry endpoint.
 *
 * <ul>
 *   <li>{@link #NONE} — no context shapes or examples are sent.
 *   <li>{@link #SHAPES} — record only the shape (named contexts, property names + types) of every
 *       evaluation context. No property values are sent.
 *   <li>{@link #PERIODIC_EXAMPLE} — record shapes plus a periodic full-context sample, with
 *       confidential properties redacted.
 * </ul>
 */
public enum ContextUploadMode {
  NONE,
  SHAPES,
  PERIODIC_EXAMPLE;

  public static ContextUploadMode parse(String s) {
    if (s == null) return PERIODIC_EXAMPLE;
    switch (s.toLowerCase()) {
      case "none":
        return NONE;
      case "shapes":
        return SHAPES;
      case "periodic_example":
      default:
        return PERIODIC_EXAMPLE;
    }
  }
}
