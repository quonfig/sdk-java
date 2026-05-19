package com.quonfig.sdk.logback;

import ch.qos.logback.classic.Level;
import com.quonfig.sdk.LogLevel;

final class LogbackLevelMapper {

  private LogbackLevelMapper() {}

  static Level toLogbackLevel(LogLevel quonfigLevel) {
    switch (quonfigLevel) {
      case FATAL:
        // Logback has no FATAL; ERROR is the closest mapping (Reforge / sdk-ruby do the same).
        return Level.ERROR;
      case ERROR:
        return Level.ERROR;
      case WARN:
        return Level.WARN;
      case INFO:
        return Level.INFO;
      case DEBUG:
        return Level.DEBUG;
      case TRACE:
        return Level.TRACE;
      default:
        return Level.DEBUG;
    }
  }
}
