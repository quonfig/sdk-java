package com.quonfig.sdk.log4j2;

import com.quonfig.sdk.LogLevel;
import org.apache.logging.log4j.Level;

final class Log4jLevelMapper {

  private Log4jLevelMapper() {}

  static Level toLog4jLevel(LogLevel quonfigLevel) {
    switch (quonfigLevel) {
      case FATAL:
        return Level.FATAL;
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
