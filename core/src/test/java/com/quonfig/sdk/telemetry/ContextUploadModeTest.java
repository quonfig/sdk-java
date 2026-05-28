package com.quonfig.sdk.telemetry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.Test;

/**
 * Pins the wire-value contract for {@link ContextUploadMode#parse(String)}.
 *
 * <p>The SDK family agreed on {@code "shapes_only"} as the canonical shape-only value in qfg-6svs
 * (see project/plans/sdk-1.0-unification.md, Section 1). The pre-1.0 value {@code "shapes"} is kept
 * as a deprecated alias for one minor cycle to keep existing callers working.
 */
class ContextUploadModeTest {

  @Test
  void parseShapesOnlyReturnsShapesOnly() {
    assertEquals(ContextUploadMode.SHAPES_ONLY, ContextUploadMode.parse("shapes_only"));
  }

  @Test
  void parseShapesIsDeprecatedAliasForShapesOnly() {
    assertEquals(ContextUploadMode.SHAPES_ONLY, ContextUploadMode.parse("shapes"));
  }

  @Test
  void parseNoneReturnsNone() {
    assertEquals(ContextUploadMode.NONE, ContextUploadMode.parse("none"));
  }

  @Test
  void parsePeriodicExampleReturnsPeriodicExample() {
    assertEquals(ContextUploadMode.PERIODIC_EXAMPLE, ContextUploadMode.parse("periodic_example"));
  }

  @Test
  void parseIsCaseInsensitive() {
    assertEquals(ContextUploadMode.SHAPES_ONLY, ContextUploadMode.parse("SHAPES_ONLY"));
    assertEquals(ContextUploadMode.SHAPES_ONLY, ContextUploadMode.parse("Shapes_Only"));
  }

  @Test
  void parseUnknownDefaultsToPeriodicExample() {
    assertEquals(ContextUploadMode.PERIODIC_EXAMPLE, ContextUploadMode.parse("bogus"));
  }

  @Test
  void parseNullDefaultsToPeriodicExample() {
    assertEquals(ContextUploadMode.PERIODIC_EXAMPLE, ContextUploadMode.parse(null));
  }

  /**
   * The deprecated SHAPES constant must remain referenceable and behave the same as SHAPES_ONLY in
   * any collector that branches on the enum value. We do not promise SHAPES == SHAPES_ONLY as
   * identical enum instances (Java enums can't alias), but they must share the same downstream
   * effect.
   */
  @Test
  @SuppressWarnings("deprecation")
  void deprecatedShapesConstantStillResolves() {
    // Read-only access proves the constant is still on the type.
    ContextUploadMode legacy = ContextUploadMode.SHAPES;
    assertSame(ContextUploadMode.SHAPES, legacy);
  }
}
