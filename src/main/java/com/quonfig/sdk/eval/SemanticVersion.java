package com.quonfig.sdk.eval;

import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Strict semver 2.0.0 parser and comparator. Mirrors {@code sdk-go/evalcore/semver.go}. */
public final class SemanticVersion implements Comparable<SemanticVersion> {

  private static final Pattern SEMVER =
      Pattern.compile(
          "^(?<major>0|[1-9]\\d*)\\.(?<minor>0|[1-9]\\d*)\\.(?<patch>0|[1-9]\\d*)"
              + "(?:-(?<prerelease>(?:0|[1-9]\\d*|\\d*[a-zA-Z-][0-9a-zA-Z-]*)"
              + "(?:\\.(?:0|[1-9]\\d*|\\d*[a-zA-Z-][0-9a-zA-Z-]*))*))?"
              + "(?:\\+(?<buildmetadata>[0-9a-zA-Z-]+(?:\\.[0-9a-zA-Z-]+)*))?$");

  private final int major;
  private final int minor;
  private final int patch;
  private final String prerelease;
  private final String buildMetadata;

  private SemanticVersion(
      int major, int minor, int patch, String prerelease, String buildMetadata) {
    this.major = major;
    this.minor = minor;
    this.patch = patch;
    this.prerelease = prerelease == null ? "" : prerelease;
    this.buildMetadata = buildMetadata == null ? "" : buildMetadata;
  }

  /** Parses {@code version}; throws {@link IllegalArgumentException} on invalid input. */
  public static SemanticVersion parse(String version) {
    if (version == null || version.isEmpty()) {
      throw new IllegalArgumentException("version string cannot be empty");
    }
    Matcher m = SEMVER.matcher(version);
    if (!m.matches()) {
      throw new IllegalArgumentException("invalid semantic version format: " + version);
    }
    return new SemanticVersion(
        Integer.parseInt(m.group("major")),
        Integer.parseInt(m.group("minor")),
        Integer.parseInt(m.group("patch")),
        m.group("prerelease"),
        m.group("buildmetadata"));
  }

  /** Same as {@link #parse(String)} but returns null instead of throwing. */
  public static SemanticVersion parseQuietly(String version) {
    if (version == null) return null;
    try {
      return parse(version);
    } catch (IllegalArgumentException e) {
      return null;
    }
  }

  public int major() {
    return major;
  }

  public int minor() {
    return minor;
  }

  public int patch() {
    return patch;
  }

  public String prerelease() {
    return prerelease;
  }

  public String buildMetadata() {
    return buildMetadata;
  }

  @Override
  public int compareTo(SemanticVersion other) {
    int c = Integer.compare(major, other.major);
    if (c != 0) return c;
    c = Integer.compare(minor, other.minor);
    if (c != 0) return c;
    c = Integer.compare(patch, other.patch);
    if (c != 0) return c;
    return comparePreRelease(prerelease, other.prerelease);
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (!(o instanceof SemanticVersion)) return false;
    SemanticVersion that = (SemanticVersion) o;
    return major == that.major
        && minor == that.minor
        && patch == that.patch
        && Objects.equals(prerelease, that.prerelease);
  }

  @Override
  public int hashCode() {
    return Objects.hash(major, minor, patch, prerelease);
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder();
    sb.append(major).append('.').append(minor).append('.').append(patch);
    if (!prerelease.isEmpty()) sb.append('-').append(prerelease);
    if (!buildMetadata.isEmpty()) sb.append('+').append(buildMetadata);
    return sb.toString();
  }

  private static int comparePreRelease(String pre1, String pre2) {
    if (pre1.isEmpty() && pre2.isEmpty()) return 0;
    // A version without prerelease has higher precedence than one with.
    if (pre1.isEmpty()) return 1;
    if (pre2.isEmpty()) return -1;

    String[] ids1 = pre1.split("\\.");
    String[] ids2 = pre2.split("\\.");
    int min = Math.min(ids1.length, ids2.length);
    for (int i = 0; i < min; i++) {
      int c = compareIdentifier(ids1[i], ids2[i]);
      if (c != 0) return c;
    }
    return Integer.compare(ids1.length, ids2.length);
  }

  private static int compareIdentifier(String a, String b) {
    boolean aNum = isNumeric(a);
    boolean bNum = isNumeric(b);
    if (aNum && bNum) {
      return Long.compare(Long.parseLong(a), Long.parseLong(b));
    }
    if (aNum) return -1; // numeric identifiers always have lower precedence
    if (bNum) return 1;
    return a.compareTo(b);
  }

  private static boolean isNumeric(String s) {
    if (s.isEmpty()) return false;
    for (int i = 0; i < s.length(); i++) {
      if (!Character.isDigit(s.charAt(i))) return false;
    }
    return true;
  }
}
