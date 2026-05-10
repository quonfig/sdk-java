package com.quonfig.sdk;

import java.util.Locale;
import java.util.Optional;

/**
 * Quonfig-internal log level enum, used by {@link Quonfig#getLogLevel} and the Logback / Log4j2 /
 * Micronaut filter modules to map between Quonfig's log-level config payload and each logging
 * library's level type.
 *
 * <p>Order is most-severe-first; {@link #ordinal()} is suitable for "is at least as severe"
 * comparisons. Mirrors the canonical level set in sdk-node, sdk-go, and sdk-ruby.
 */
public enum LogLevel {
  FATAL,
  ERROR,
  WARN,
  INFO,
  DEBUG,
  TRACE;

  /**
   * Parses the string representation of a level emitted by api-delivery / datadir log-level
   * configs. Matches case-insensitively. Returns empty for unknown / null input — callers should
   * treat that as "no opinion".
   */
  public static Optional<LogLevel> fromString(String s) {
    if (s == null) return Optional.empty();
    try {
      return Optional.of(LogLevel.valueOf(s.toUpperCase(Locale.ROOT)));
    } catch (IllegalArgumentException e) {
      return Optional.empty();
    }
  }
}
