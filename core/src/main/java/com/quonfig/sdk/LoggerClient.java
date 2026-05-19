package com.quonfig.sdk;

import com.quonfig.sdk.eval.ContextSet;
import java.util.Optional;

/**
 * Thin handle the Logback / Log4j2 / Micronaut filter modules consume. {@link Quonfig} implements
 * this interface so customers can pass {@code quonfig::asLoggerClient} (or just the {@link Quonfig}
 * instance itself) into a filter without exposing the full API surface.
 */
public interface LoggerClient {

  /** See {@link Quonfig#getLogLevel(String, ContextSet)}. */
  Optional<LogLevel> getLogLevel(String loggerPath, ContextSet ctx);

  /** Convenience overload — equivalent to {@code getLogLevel(loggerPath, null)}. */
  default Optional<LogLevel> getLogLevel(String loggerPath) {
    return getLogLevel(loggerPath, null);
  }
}
