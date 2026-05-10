package com.quonfig.sdk.log4j2;

import com.quonfig.sdk.LogLevel;
import com.quonfig.sdk.LoggerClient;
import java.util.Optional;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.filter.AbstractFilter;
import org.apache.logging.log4j.message.Message;

/**
 * Log4j2 filter that resolves log levels dynamically from Quonfig.
 *
 * <p>Customers wire it once at startup, after Log4j2 has initialized:
 *
 * <pre>{@code
 * Quonfig q = new Quonfig(opts);
 * QuonfigLog4j2Filter.install(q);
 * }</pre>
 *
 * <p>If Quonfig has no opinion for a given logger path the filter returns {@code NEUTRAL} so
 * existing logger thresholds and other filters continue to govern.
 *
 * <p><b>Important:</b> any dynamic Log4j2 reconfiguration removes context-level filters; reinstall
 * after reconfiguring.
 */
public final class QuonfigLog4j2Filter extends AbstractFilter {

  private final ThreadLocal<Boolean> recursionCheck = ThreadLocal.withInitial(() -> false);
  private final LoggerClient loggerClient;

  /**
   * Installs the filter at the active Log4j2 {@code LoggerContext}.
   *
   * @throws IllegalStateException if log4j-core is not on the classpath / the active context is not
   *     a Log4j2 Core context.
   */
  public static void install(LoggerClient loggerClient) {
    org.apache.logging.log4j.spi.LoggerContext ctx = LogManager.getContext(false);
    if (!(ctx instanceof LoggerContext)) {
      throw new IllegalStateException(
          "Cannot install QuonfigLog4j2Filter - LoggerContext is not Log4j2 Core. Found: "
              + ctx.getClass().getName()
              + ". Make sure log4j-core is on your classpath and properly configured.");
    }
    LoggerContext loggerContext = (LoggerContext) ctx;
    QuonfigLog4j2Filter filter = new QuonfigLog4j2Filter(loggerClient);
    filter.start();
    loggerContext.addFilter(filter);
    loggerContext.updateLoggers();
  }

  public QuonfigLog4j2Filter(LoggerClient loggerClient) {
    this.loggerClient = loggerClient;
  }

  Result decide(String loggerName, Level level) {
    if (recursionCheck.get()) {
      return Result.NEUTRAL;
    }
    try {
      recursionCheck.set(true);
      Optional<LogLevel> resolved = loggerClient.getLogLevel(loggerName);
      if (resolved.isEmpty()) {
        return Result.NEUTRAL;
      }
      Level floor = Log4jLevelMapper.toLog4jLevel(resolved.get());
      return level.isMoreSpecificThan(floor) ? Result.ACCEPT : Result.DENY;
    } catch (Exception e) {
      return Result.NEUTRAL;
    } finally {
      recursionCheck.set(false);
    }
  }

  @Override
  public Result filter(LogEvent event) {
    return decide(event.getLoggerName(), event.getLevel());
  }

  @Override
  public Result filter(Logger logger, Level level, Marker marker, Message msg, Throwable t) {
    return decide(logger.getName(), level);
  }

  @Override
  public Result filter(Logger logger, Level level, Marker marker, Object msg, Throwable t) {
    return decide(logger.getName(), level);
  }

  @Override
  public Result filter(Logger logger, Level level, Marker marker, String msg, Object... params) {
    return decide(logger.getName(), level);
  }

  @Override
  public Result filter(Logger logger, Level level, Marker marker, String msg, Object p0) {
    return decide(logger.getName(), level);
  }

  @Override
  public Result filter(
      Logger logger, Level level, Marker marker, String msg, Object p0, Object p1) {
    return decide(logger.getName(), level);
  }

  @Override
  public Result filter(
      Logger logger, Level level, Marker marker, String msg, Object p0, Object p1, Object p2) {
    return decide(logger.getName(), level);
  }

  @Override
  public Result filter(
      Logger logger,
      Level level,
      Marker marker,
      String msg,
      Object p0,
      Object p1,
      Object p2,
      Object p3) {
    return decide(logger.getName(), level);
  }

  @Override
  public Result filter(
      Logger logger,
      Level level,
      Marker marker,
      String msg,
      Object p0,
      Object p1,
      Object p2,
      Object p3,
      Object p4) {
    return decide(logger.getName(), level);
  }

  @Override
  public Result filter(
      Logger logger,
      Level level,
      Marker marker,
      String msg,
      Object p0,
      Object p1,
      Object p2,
      Object p3,
      Object p4,
      Object p5) {
    return decide(logger.getName(), level);
  }

  @Override
  public Result filter(
      Logger logger,
      Level level,
      Marker marker,
      String msg,
      Object p0,
      Object p1,
      Object p2,
      Object p3,
      Object p4,
      Object p5,
      Object p6) {
    return decide(logger.getName(), level);
  }

  @Override
  public Result filter(
      Logger logger,
      Level level,
      Marker marker,
      String msg,
      Object p0,
      Object p1,
      Object p2,
      Object p3,
      Object p4,
      Object p5,
      Object p6,
      Object p7) {
    return decide(logger.getName(), level);
  }

  @Override
  public Result filter(
      Logger logger,
      Level level,
      Marker marker,
      String msg,
      Object p0,
      Object p1,
      Object p2,
      Object p3,
      Object p4,
      Object p5,
      Object p6,
      Object p7,
      Object p8) {
    return decide(logger.getName(), level);
  }

  @Override
  public Result filter(
      Logger logger,
      Level level,
      Marker marker,
      String msg,
      Object p0,
      Object p1,
      Object p2,
      Object p3,
      Object p4,
      Object p5,
      Object p6,
      Object p7,
      Object p8,
      Object p9) {
    return decide(logger.getName(), level);
  }
}
