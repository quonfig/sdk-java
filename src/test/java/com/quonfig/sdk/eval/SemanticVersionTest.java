package com.quonfig.sdk.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SemanticVersionTest {

  @Test
  void parses_majorMinorPatch() {
    SemanticVersion v = SemanticVersion.parse("1.2.3");
    assertNotNull(v);
    assertEquals(1, v.major());
    assertEquals(2, v.minor());
    assertEquals(3, v.patch());
  }

  @Test
  void parses_prereleaseAndBuild() {
    SemanticVersion v = SemanticVersion.parse("1.2.3-rc.1+build.42");
    assertNotNull(v);
    assertEquals("rc.1", v.prerelease());
    assertEquals("build.42", v.buildMetadata());
  }

  @Test
  void parseQuiet_returnsNullForInvalid() {
    assertNull(SemanticVersion.parseQuietly("not a version"));
    assertNull(SemanticVersion.parseQuietly("1.2"));
    assertNull(SemanticVersion.parseQuietly("01.2.3"), "leading zero not allowed");
  }

  @Test
  void compareCore_majorMinorPatch() {
    assertTrue(SemanticVersion.parse("2.0.0").compareTo(SemanticVersion.parse("1.99.99")) > 0);
    assertTrue(SemanticVersion.parse("1.2.3").compareTo(SemanticVersion.parse("1.2.4")) < 0);
    assertEquals(0, SemanticVersion.parse("1.2.3").compareTo(SemanticVersion.parse("1.2.3")));
  }

  @Test
  void compare_prereleaseHasLowerPrecedence() {
    assertTrue(
        SemanticVersion.parse("1.2.3-rc.1").compareTo(SemanticVersion.parse("1.2.3")) < 0,
        "prerelease < release");
    assertTrue(
        SemanticVersion.parse("1.2.3-rc.1").compareTo(SemanticVersion.parse("1.2.3-rc.2")) < 0);
    assertTrue(
        SemanticVersion.parse("1.2.3-alpha").compareTo(SemanticVersion.parse("1.2.3-beta")) < 0);
    assertTrue(
        SemanticVersion.parse("1.2.3-rc.1").compareTo(SemanticVersion.parse("1.2.3-rc.1.1")) < 0,
        "shorter prerelease set < longer with shared prefix");
  }

  @Test
  void compare_numericPrereleaseIdentifiersComparedNumerically() {
    assertTrue(SemanticVersion.parse("1.2.3-1").compareTo(SemanticVersion.parse("1.2.3-2")) < 0);
    // numeric < non-numeric
    assertTrue(
        SemanticVersion.parse("1.2.3-1").compareTo(SemanticVersion.parse("1.2.3-alpha")) < 0);
  }
}
