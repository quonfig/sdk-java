package com.quonfig.sdk.logback;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.turbo.TurboFilter;
import ch.qos.logback.core.spi.FilterReply;
import com.quonfig.sdk.LogLevel;
import com.quonfig.sdk.LoggerClient;
import java.util.Optional;
import org.slf4j.Marker;

abstract class BaseTurboFilter extends TurboFilter {

  protected final LoggerClient loggerClient;
  private final ThreadLocal<Boolean> recursionCheck = ThreadLocal.withInitial(() -> false);

  BaseTurboFilter(LoggerClient loggerClient) {
    this.loggerClient = loggerClient;
  }

  /** Resolve a Quonfig log level for the given Logback logger; empty means "no opinion". */
  abstract Optional<LogLevel> getLogLevel(Logger logger, Level level);

  @Override
  public FilterReply decide(
      Marker marker, Logger logger, Level level, String s, Object[] objects, Throwable throwable) {
    // Quonfig's evaluator may itself emit log records (e.g. via SLF4J); without this guard
    // the filter would re-enter on its own evaluation and stack-overflow.
    if (recursionCheck.get()) {
      return FilterReply.NEUTRAL;
    }
    try {
      recursionCheck.set(true);
      Optional<LogLevel> resolved = getLogLevel(logger, level);
      if (resolved.isEmpty()) {
        // No Quonfig log-level config matches this logger path → defer to logger's own
        // threshold and any other filters in the chain.
        return FilterReply.NEUTRAL;
      }
      Level floor = LogbackLevelMapper.toLogbackLevel(resolved.get());
      return level.isGreaterOrEqual(floor) ? FilterReply.ACCEPT : FilterReply.DENY;
    } catch (Exception e) {
      return FilterReply.NEUTRAL;
    } finally {
      recursionCheck.set(false);
    }
  }
}
