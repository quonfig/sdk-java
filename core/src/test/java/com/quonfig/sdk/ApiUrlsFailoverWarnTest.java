package com.quonfig.sdk;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.slf4j.Marker;
import org.slf4j.event.Level;
import org.slf4j.helpers.AbstractLogger;

/**
 * qfg-41nh.26: The default (and every {@code QUONFIG_DOMAIN}-derived) API-URL list carries a
 * primary and a secondary leg, and the SDK hedges/fails over between them. An explicit {@link
 * Options.Builder#apiUrls(List)} with a single entry silently drops the secondary — disabling
 * automatic failover — so the SDK emits a one-line WARN at init pointing the caller at the fix
 * (pass both a primary and a secondary URL). A two-URL override, and the QUONFIG_DOMAIN-derived
 * default, must NOT warn.
 *
 * <p>Mirrors the sdk-go pilot ({@code apiurls_failover_warn_test.go}, commit 8e35340).
 */
class ApiUrlsFailoverWarnTest {

  private static final String FAILOVER_WARN_FRAGMENT =
      "explicit apiUrls disables automatic failover";

  /**
   * A single explicit {@code apiUrls} entry disables failover, so the SDK must WARN once at init.
   */
  @Test
  void singleExplicitApiUrl_warnsFailoverLost() {
    RecordingLogger recording = new RecordingLogger();
    Options o = baseBuilder(recording).apiUrls(List.of("https://primary.example.test")).build();

    try (Quonfig q = new Quonfig(o)) {
      // warning is emitted synchronously in the constructor, before the background init thread runs
    }

    assertEquals(
        1,
        failoverWarnCount(recording),
        "single explicit apiUrl drops the secondary and must WARN, saw: " + recording.entries);
  }

  /** Two explicit {@code apiUrls} keep failover, so the SDK must NOT warn. */
  @Test
  void twoExplicitApiUrls_doNotWarn() {
    RecordingLogger recording = new RecordingLogger();
    Options o =
        baseBuilder(recording)
            .apiUrls(List.of("https://primary.example.test", "https://secondary.example.test"))
            .build();

    try (Quonfig q = new Quonfig(o)) {
      // no-op
    }

    assertEquals(
        0,
        failoverWarnCount(recording),
        "two explicit URLs keep failover and must not warn, saw: " + recording.entries);
  }

  /**
   * The QUONFIG_DOMAIN-derived default list carries both a primary and a secondary leg (caller did
   * not set {@code apiUrls} explicitly), so the SDK must NOT warn.
   */
  @Test
  void domainDerivedDefault_doesNotWarn() {
    RecordingLogger recording = new RecordingLogger();
    // No .apiUrls(...) → derived from domain → [primary.example.test, secondary.example.test].
    Options o = baseBuilder(recording).domain("example.test").build();

    try (Quonfig q = new Quonfig(o)) {
      // no-op
    }

    assertEquals(
        0,
        failoverWarnCount(recording),
        "domain-derived list carries both legs and must not warn, saw: " + recording.entries);
  }

  private static Options.Builder baseBuilder(RecordingLogger recording) {
    return Options.builder()
        .sdkKey("test-key")
        .logger(recording)
        // Deterministic: ignore any ambient QUONFIG_* env vars (domain, environment, dev-context).
        .envLookup(k -> Optional.empty())
        .enableQuonfigUserContext(false)
        .disableTelemetry(true)
        .fallbackPollEnabled(false)
        // Keep the background init thread short (bogus hosts fail fast); initTimeout stays strictly
        // above hedgeAbort so the unrelated initTimeout<=hedgeAbort warning never fires.
        .configFetchHedgeDelay(Duration.ofMillis(50))
        .configFetchHedgeAbort(Duration.ofMillis(300))
        .initTimeout(Duration.ofSeconds(2));
  }

  private static long failoverWarnCount(RecordingLogger recording) {
    return recording.entries.stream()
        .filter(e -> e.level == Level.WARN)
        .filter(e -> e.format.contains(FAILOVER_WARN_FRAGMENT))
        .count();
  }

  /** Minimal SLF4J logger capturing each call as (level, message-pattern, stringified-args). */
  static final class RecordingLogger extends AbstractLogger {
    final List<Entry> entries = new CopyOnWriteArrayList<>();

    static final class Entry {
      final Level level;
      final String format;
      final String args;

      Entry(Level level, String format, String args) {
        this.level = level;
        this.format = format;
        this.args = args;
      }

      @Override
      public String toString() {
        return level + ":" + format + " " + args;
      }
    }

    @Override
    protected String getFullyQualifiedCallerName() {
      return RecordingLogger.class.getName();
    }

    @Override
    protected void handleNormalizedLoggingCall(
        Level level,
        Marker marker,
        String messagePattern,
        Object[] arguments,
        Throwable throwable) {
      entries.add(
          new Entry(
              level,
              messagePattern == null ? "" : messagePattern,
              arguments == null ? "" : Arrays.toString(arguments)));
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
}
