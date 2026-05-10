package com.quonfig.sdk.log4j2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.quonfig.sdk.LogLevel;
import com.quonfig.sdk.LoggerClient;
import com.quonfig.sdk.eval.ContextSet;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Smoke test: install {@link QuonfigLog4j2Filter} into a Log4j2 {@code LoggerContext}, log via the
 * Log4j2 facade, and assert that the filter drops events below the floor returned by the stubbed
 * {@link LoggerClient}.
 */
class QuonfigLog4j2FilterTest {

  private LoggerContext context;
  private RecordingAppender appender;
  private LoggerConfig rootConfig;
  private QuonfigLog4j2Filter installedFilter;
  private StubLoggerClient stub;

  @BeforeEach
  void setUp() {
    context = (LoggerContext) LogManager.getContext(false);
    Configuration config = context.getConfiguration();
    rootConfig = config.getRootLogger();
    rootConfig.setLevel(Level.TRACE);

    appender = new RecordingAppender();
    appender.start();
    config.addAppender(appender);
    rootConfig.addAppender(appender, Level.TRACE, null);

    stub = new StubLoggerClient();
    context.updateLoggers();
  }

  @AfterEach
  void tearDown() {
    if (installedFilter != null) {
      context.removeFilter(installedFilter);
    }
    rootConfig.removeAppender(appender.getName());
    context.updateLoggers();
  }

  @Test
  void install_acceptsMessagesAtOrAboveConfiguredLevel() {
    stub.put("com.foo", LogLevel.WARN);
    installedFilter = installFilter();

    Logger logger = LogManager.getLogger("com.foo");
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
    installedFilter = installFilter();

    Logger logger = LogManager.getLogger("com.foo.Bar.Baz");
    logger.info("info-msg");
    logger.warn("warn-msg");
    logger.error("error-msg");

    List<String> messages = appender.messages();
    assertEquals(1, messages.size());
    assertTrue(messages.contains("error-msg"));
  }

  @Test
  void install_isNeutral_whenLoggerClientHasNoOpinion() {
    installedFilter = installFilter();

    Logger logger = LogManager.getLogger("com.unconfigured");
    logger.trace("t");
    logger.debug("d");
    logger.info("i");
    logger.warn("w");
    logger.error("e");

    assertEquals(5, appender.messages().size());
  }

  @Test
  void install_dropsBelowFloor_evenWhenLoggerLevelWouldAccept() {
    stub.put("com.foo", LogLevel.WARN);
    installedFilter = installFilter();

    Logger logger = LogManager.getLogger("com.foo");
    logger.debug("debug-msg");

    assertEquals(0, appender.messages().size());
  }

  private QuonfigLog4j2Filter installFilter() {
    QuonfigLog4j2Filter filter = new QuonfigLog4j2Filter(stub);
    filter.start();
    context.addFilter(filter);
    context.updateLoggers();
    return filter;
  }

  private static final class RecordingAppender extends AbstractAppender {
    private final List<String> messages = new ArrayList<>();

    RecordingAppender() {
      super(
          "recording", null, null, true, org.apache.logging.log4j.core.config.Property.EMPTY_ARRAY);
    }

    @Override
    public synchronized void append(LogEvent event) {
      messages.add(event.getMessage().getFormattedMessage());
    }

    synchronized List<String> messages() {
      return new ArrayList<>(messages);
    }
  }

  private static final class StubLoggerClient implements LoggerClient {
    private final Map<String, LogLevel> levels = new HashMap<>();

    void put(String path, LogLevel level) {
      levels.put(path, level);
    }

    @Override
    public Optional<LogLevel> getLogLevel(String loggerPath, ContextSet ctx) {
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
