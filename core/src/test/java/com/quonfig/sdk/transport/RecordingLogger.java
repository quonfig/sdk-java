package com.quonfig.sdk.transport;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.slf4j.Marker;
import org.slf4j.event.Level;
import org.slf4j.helpers.AbstractLogger;
import org.slf4j.helpers.MessageFormatter;

/**
 * Test-only SLF4J logger that records every call (level, formatted message, throwable) at every
 * level. Shared by the size-caps tests (qfg-6vno.20.2) in this package and in {@code
 * com.quonfig.sdk}.
 */
public final class RecordingLogger extends AbstractLogger {

  /** One recorded call. */
  public static final class Entry {
    public final Level level;
    public final String message;
    public final Throwable throwable;

    Entry(Level level, String message, Throwable throwable) {
      this.level = level;
      this.message = message;
      this.throwable = throwable;
    }

    @Override
    public String toString() {
      return level + ": " + message + (throwable == null ? "" : " [" + throwable + "]");
    }
  }

  public final List<Entry> entries = new CopyOnWriteArrayList<>();

  /** Entries at {@code level} whose formatted message contains {@code fragment}. */
  public List<Entry> matching(Level level, String fragment) {
    List<Entry> out = new ArrayList<>();
    for (Entry e : entries) {
      if (e.level == level && e.message.contains(fragment)) out.add(e);
    }
    return out;
  }

  @Override
  protected String getFullyQualifiedCallerName() {
    return RecordingLogger.class.getName();
  }

  @Override
  protected void handleNormalizedLoggingCall(
      Level level, Marker marker, String messagePattern, Object[] arguments, Throwable throwable) {
    String msg = MessageFormatter.basicArrayFormat(messagePattern, arguments);
    entries.add(new Entry(level, msg == null ? "" : msg, throwable));
  }

  @Override
  public boolean isTraceEnabled() {
    return true;
  }

  @Override
  public boolean isTraceEnabled(Marker marker) {
    return true;
  }

  @Override
  public boolean isDebugEnabled() {
    return true;
  }

  @Override
  public boolean isDebugEnabled(Marker marker) {
    return true;
  }

  @Override
  public boolean isInfoEnabled() {
    return true;
  }

  @Override
  public boolean isInfoEnabled(Marker marker) {
    return true;
  }

  @Override
  public boolean isWarnEnabled() {
    return true;
  }

  @Override
  public boolean isWarnEnabled(Marker marker) {
    return true;
  }

  @Override
  public boolean isErrorEnabled() {
    return true;
  }

  @Override
  public boolean isErrorEnabled(Marker marker) {
    return true;
  }
}
