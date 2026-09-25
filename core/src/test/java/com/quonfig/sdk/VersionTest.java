package com.quonfig.sdk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;

/**
 * qfg-y8je.2: the SDK must report the real artifact version, not a stale literal. The published
 * 1.2.1 jar had no {@code Implementation-Version} manifest entry, so {@link Version} fell back to
 * {@code 0.0.1-SNAPSHOT} in production.
 */
class VersionTest {

  private static String expected() {
    String v = System.getProperty("quonfig.expectedSdkVersion");
    assertNotNull(v, "build must pass quonfig.expectedSdkVersion to the test JVM");
    return v;
  }

  @Test
  void getReturnsBuildVersion() {
    assertEquals(expected(), Version.get());
  }

  @Test
  void headerIsJavaPrefixedBuildVersion() {
    assertEquals("java-" + expected(), Version.header());
  }
}
