package com.quonfig.sdk;

/**
 * SDK version constant exposed to telemetry and the {@code X-Quonfig-SDK-Version} HTTP header.
 *
 * <p>At runtime we prefer {@code Quonfig.class.getPackage().getImplementationVersion()} (populated
 * by the JAR manifest at publish time), falling back to a compiled-in constant for in-tree dev
 * builds where the JAR isn't built yet.
 */
public final class Version {

  /**
   * Fallback used for in-tree dev runs (tests, gradle classpath). Bump in lockstep with releases.
   */
  static final String FALLBACK = "0.0.1-SNAPSHOT";

  private static final String VALUE = lookup();
  private static final String HEADER = "java-" + VALUE;

  private Version() {}

  /** Returns the bare semver, e.g. {@code "0.0.1"}. */
  public static String get() {
    return VALUE;
  }

  /** Returns the value sent in {@code X-Quonfig-SDK-Version}, e.g. {@code "java-0.0.1"}. */
  public static String header() {
    return HEADER;
  }

  private static String lookup() {
    Package pkg = Version.class.getPackage();
    if (pkg != null) {
      String impl = pkg.getImplementationVersion();
      if (impl != null && !impl.isEmpty()) {
        return impl;
      }
    }
    return FALLBACK;
  }
}
