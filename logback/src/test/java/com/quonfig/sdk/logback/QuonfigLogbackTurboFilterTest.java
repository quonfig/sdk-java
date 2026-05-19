package com.quonfig.sdk.logback;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import com.quonfig.sdk.LogLevel;
import com.quonfig.sdk.LoggerClient;
import com.quonfig.sdk.eval.ContextSet;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Smoke test: install {@link QuonfigLogbackTurboFilter} into Logback's programmatic LoggerContext,
 * log via SLF4J, and assert that messages below the level returned by the stubbed {@link
 * LoggerClient} are dropped while messages at or above are accepted.
 */
class QuonfigLogbackTurboFilterTest {

  private LoggerContext context;
  private RecordingAppender appender;
  private StubLoggerClient stub;

  @BeforeEach
  void setUp() {
    context = (LoggerContext) LoggerFactory.getILoggerFactory();
    context.reset();
    // Reset clears all loggers; re-set the root level to TRACE so the appender sees
    // every event the turbo filter chooses to ACCEPT — otherwise the logger's own
    // level threshold would mask the filter's behavior.
    context.getLogger(Logger.ROOT_LOGGER_NAME).setLevel(ch.qos.logback.classic.Level.TRACE);
    appender = new RecordingAppender();
    appender.setContext(context);
    appender.start();
    context.getLogger(Logger.ROOT_LOGGER_NAME).addAppender(appender);
    stub = new StubLoggerClient();
  }

  @AfterEach
  void tearDown() {
    context.reset();
  }

  @Test
  void install_acceptsMessagesAtOrAboveConfiguredLevel() {
    stub.put("com.foo", LogLevel.WARN);
    QuonfigLogbackTurboFilter.install(stub);

    Logger logger = LoggerFactory.getLogger("com.foo");
    logger.info("info-msg");
    logger.warn("warn-msg");
    logger.error("error-msg");

    List<String> messages = appender.messages();
    assertEquals(2, messages.size(), "INFO must be filtered out when floor=WARN");
    assertTrue(messages.contains("warn-msg"));
    assertTrue(messages.contains("error-msg"));
  }

  @Test
  void install_walksUpDottedParents() {
    stub.put("com.foo", LogLevel.ERROR);
    QuonfigLogbackTurboFilter.install(stub);

    Logger logger = LoggerFactory.getLogger("com.foo.Bar.Baz");
    logger.info("info-msg");
    logger.warn("warn-msg");
    logger.error("error-msg");

    List<String> messages = appender.messages();
    assertEquals(1, messages.size(), "only ERROR survives an ERROR floor");
    assertTrue(messages.contains("error-msg"));
  }

  @Test
  void install_isNeutral_whenLoggerClientHasNoOpinion() {
    // No stubbed level for any logger path → filter returns NEUTRAL → logger's own level
    // governs. Root is TRACE, so all events pass through.
    QuonfigLogbackTurboFilter.install(stub);

    Logger logger = LoggerFactory.getLogger("com.unconfigured");
    logger.trace("t");
    logger.debug("d");
    logger.info("i");
    logger.warn("w");
    logger.error("e");

    assertEquals(
        5,
        appender.messages().size(),
        "filter must not drop logs when LoggerClient has no opinion");
  }

  @Test
  void install_dropsBelowFloor_evenWhenLoggerLevelWouldAccept() {
    stub.put("com.foo", LogLevel.WARN);
    QuonfigLogbackTurboFilter.install(stub);

    // Logger's own level is TRACE (set in @BeforeEach via root). Without the filter,
    // DEBUG would be appended. With the filter, the WARN floor must override.
    Logger logger = LoggerFactory.getLogger("com.foo");
    logger.debug("debug-msg");

    assertEquals(0, appender.messages().size(), "WARN floor must drop DEBUG");
  }

  /** Records every appended log message in order — the verification surface for filter tests. */
  private static final class RecordingAppender extends AppenderBase<ILoggingEvent> {
    private final List<String> messages = new ArrayList<>();

    @Override
    protected synchronized void append(ILoggingEvent eventObject) {
      messages.add(eventObject.getFormattedMessage());
    }

    synchronized List<String> messages() {
      return new ArrayList<>(messages);
    }
  }

  /** Test double — returns whatever LogLevel was put for a given logger path. */
  private static final class StubLoggerClient implements LoggerClient {
    private final Map<String, LogLevel> levels = new HashMap<>();

    void put(String path, LogLevel level) {
      levels.put(path, level);
    }

    @Override
    public Optional<LogLevel> getLogLevel(String loggerPath, ContextSet ctx) {
      // Mirror Quonfig's hierarchical fallback so the test exercises the filter, not the
      // resolution logic which lives in core.
      String key = loggerPath;
      while (true) {
        if (levels.containsKey(key)) return Optional.of(levels.get(key));
        if (key.isEmpty()) return Optional.empty();
        int dot = key.lastIndexOf('.');
        key = dot < 0 ? "" : key.substring(0, dot);
      }
    }
  }
}
