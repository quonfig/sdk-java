package com.quonfig.sdk.logback;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.turbo.TurboFilter;
import org.slf4j.ILoggerFactory;
import org.slf4j.LoggerFactory;

final class LogbackUtils {

  private LogbackUtils() {}

  static void installTurboFilter(TurboFilter turboFilter) {
    ILoggerFactory iLoggerFactory = LoggerFactory.getILoggerFactory();
    if (!(iLoggerFactory instanceof LoggerContext)) {
      throw new IllegalStateException(
          "Cannot install "
              + turboFilter.getClass().getSimpleName()
              + " - LoggerFactory is not a Logback LoggerContext. Found: "
              + iLoggerFactory.getClass().getName()
              + ". Make sure Logback is on your classpath and SLF4J is bound to Logback.");
    }
    LoggerContext loggerContext = (LoggerContext) iLoggerFactory;
    turboFilter.setContext(loggerContext);
    turboFilter.start();
    loggerContext.addTurboFilter(turboFilter);
  }
}
