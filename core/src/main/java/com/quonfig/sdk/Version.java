package com.quonfig.sdk;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * SDK version constant exposed to telemetry and the {@code X-Quonfig-SDK-Version} HTTP header.
 *
 * <p>The version is read from {@code com/quonfig/sdk/sdk-version.properties}, which Gradle's {@code
 * processResources} stamps with the project version at build time, so it is correct both on the
 * {@code ./gradlew test} classpath and inside the published jar. The JAR manifest's {@code
 * Implementation-Version} is consulted only if that resource is missing (e.g. a repackaged jar).
 */
public final class Version {

  /** Last-resort value when no build-stamped version can be found. */
  static final String FALLBACK = "unknown";

  static final String RESOURCE = "sdk-version.properties";

  private static final String VALUE = lookup();
  private static final String HEADER = "java-" + VALUE;

  private Version() {}

  /** Returns the bare semver, e.g. {@code "1.2.1"}. */
  public static String get() {
    return VALUE;
  }

  /** Returns the value sent in {@code X-Quonfig-SDK-Version}, e.g. {@code "java-1.2.1"}. */
  public static String header() {
    return HEADER;
  }

  private static String lookup() {
    String fromResource = fromResource();
    if (fromResource != null) {
      return fromResource;
    }
    Package pkg = Version.class.getPackage();
    if (pkg != null) {
      String impl = pkg.getImplementationVersion();
      if (impl != null && !impl.isEmpty()) {
        return impl;
      }
    }
    return FALLBACK;
  }

  private static String fromResource() {
    try (InputStream in = Version.class.getResourceAsStream(RESOURCE)) {
      if (in == null) {
        return null;
      }
      Properties props = new Properties();
      props.load(in);
      String v = props.getProperty("version");
      // An unexpanded "${version}" means the resource was not filtered by the build.
      if (v == null || v.isEmpty() || v.contains("${")) {
        return null;
      }
      return v.trim();
    } catch (IOException e) {
      return null;
    }
  }
}
