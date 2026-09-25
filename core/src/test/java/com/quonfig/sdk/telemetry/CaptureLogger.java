package com.quonfig.sdk.telemetry;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.slf4j.Marker;
import org.slf4j.event.Level;
import org.slf4j.helpers.LegacyAbstractLogger;
import org.slf4j.helpers.MessageFormatter;

/**
 * SLF4J {@link org.slf4j.Logger} that records every line with its level, all levels enabled. The
 * contract's {@code log_count(level, /re/)}. No logging backend needed (slf4j-api only).
 */
final class CaptureLogger extends LegacyAbstractLogger {

  static final class Line {
    final Level level;
    final String msg;

    Line(Level level, String msg) {
      this.level = level;
      this.msg = msg;
    }

    @Override
    public String toString() {
      return level + " " + msg;
    }
  }

  private final List<Line> lines = new ArrayList<>();

  CaptureLogger() {
    this.name = "capture";
  }

  synchronized List<Line> lines() {
    return new ArrayList<>(lines);
  }

  synchronized void clear() {
    lines.clear();
  }

  /** Lines at {@code level} whose message matches {@code re} (find, not full match). */
  synchronized int count(Level level, String re) {
    Pattern p = Pattern.compile(re);
    int n = 0;
    for (Line l : lines) if (l.level == level && p.matcher(l.msg).find()) n++;
    return n;
  }

  int count(Level level) {
    return count(level, ".*");
  }

  synchronized String first(Level level) {
    for (Line l : lines) if (l.level == level) return l.msg;
    return null;
  }

  @Override
  protected String getFullyQualifiedCallerName() {
    return null;
  }

  @Override
  protected synchronized void handleNormalizedLoggingCall(
      Level level, Marker marker, String pattern, Object[] args, Throwable t) {
    lines.add(new Line(level, MessageFormatter.basicArrayFormat(pattern, args)));
  }

  @Override
  public boolean isTraceEnabled() {
    return true;
  }

  @Override
  public boolean isDebugEnabled() {
    return true;
  }

  @Override
  public boolean isInfoEnabled() {
    return true;
  }

  @Override
  public boolean isWarnEnabled() {
    return true;
  }

  @Override
  public boolean isErrorEnabled() {
    return true;
  }
}
