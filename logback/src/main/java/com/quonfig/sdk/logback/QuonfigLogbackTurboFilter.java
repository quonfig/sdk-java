package com.quonfig.sdk.logback;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import com.quonfig.sdk.LogLevel;
import com.quonfig.sdk.LoggerClient;
import java.util.Optional;

/**
 * Logback {@link ch.qos.logback.classic.turbo.TurboFilter} that resolves log levels dynamically
 * from Quonfig.
 *
 * <p>Once installed, every Logback logger is gated by whatever level Quonfig has configured for the
 * logger's name (with dotted-parent fallback handled inside {@link
 * com.quonfig.sdk.Quonfig#getLogLevel}). Customers wire it once at startup:
 *
 * <pre>{@code
 * Quonfig q = new Quonfig(opts);
 * QuonfigLogbackTurboFilter.install(q);
 * }</pre>
 *
 * <p>If Quonfig has no opinion for a given logger path the filter returns {@code NEUTRAL}, so
 * existing logger thresholds and other filters continue to govern.
 */
public final class QuonfigLogbackTurboFilter extends BaseTurboFilter {

  QuonfigLogbackTurboFilter(LoggerClient loggerClient) {
    super(loggerClient);
  }

  /**
   * Installs the Quonfig turbo filter into the active Logback {@code LoggerContext}.
   *
   * @param loggerClient the {@link LoggerClient} to consult — typically the {@code Quonfig}
   *     instance itself, since {@code Quonfig implements LoggerClient}.
   * @throws IllegalStateException if SLF4J is not bound to Logback.
   */
  public static void install(LoggerClient loggerClient) {
    LogbackUtils.installTurboFilter(new QuonfigLogbackTurboFilter(loggerClient));
  }

  @Override
  Optional<LogLevel> getLogLevel(Logger logger, Level level) {
    return loggerClient.getLogLevel(logger.getName());
  }
}
