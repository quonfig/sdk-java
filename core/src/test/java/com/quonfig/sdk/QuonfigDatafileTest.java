package com.quonfig.sdk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.quonfig.sdk.wire.ConfigEnvelope;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Marker;
import org.slf4j.event.Level;
import org.slf4j.helpers.AbstractLogger;

/**
 * Datafile mode (qfg-9hre): match sdk-node's {@code datafile?: string | object} shape — accept a
 * filesystem path to a serialized envelope or a pre-parsed {@link ConfigEnvelope} directly. The
 * envelope's {@code meta.environment} supplies the evaluation environment when the caller did not
 * set one explicitly (sdk-node parity); explicit {@link Options.Builder#environment(String)} wins.
 */
class QuonfigDatafileTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  // A self-contained envelope: one string config "greeting" with a static rule per environment
  // and an environment-specific override for "production".
  private static final String ENVELOPE_JSON =
      "{"
          + "\"meta\":{\"version\":\"datafile-v1\",\"environment\":\"production\","
          + "  \"workspaceId\":\"ws-1\"},"
          + "\"configs\":["
          + "  {\"id\":\"cfg-1\",\"key\":\"greeting\",\"type\":\"config\",\"valueType\":\"string\","
          + "   \"default\":{\"rules\":[{\"criteria\":[],"
          + "     \"value\":{\"type\":\"string\",\"value\":\"hello-default\"}}]},"
          + "   \"environments\":[{\"id\":\"production\",\"rules\":[{\"criteria\":[],"
          + "     \"value\":{\"type\":\"string\",\"value\":\"hello-prod\"}}]}]}"
          + "]}";

  @Test
  void datafile_path_loadsEnvelopeFromFile_andUsesMetaEnvironment(@TempDir Path tmp)
      throws Exception {
    Path file = tmp.resolve("quonfig-datafile.json");
    Files.writeString(file, ENVELOPE_JSON);

    try (Quonfig q = new Quonfig(Options.builder().datafile(file.toString()).build())) {
      // env from envelope.meta.environment ("production") drives evaluation, so the
      // production override wins over the default rule.
      assertEquals("hello-prod", q.getString("greeting", "fallback"));
      EvaluationDetails<String> d = q.getStringDetails("greeting", "fallback");
      assertEquals(Reason.STATIC, d.reason());
      assertEquals("production", d.metadata().get("environment"));
    }
  }

  @Test
  void datafile_envelope_object_loadsConfigsDirectly() throws Exception {
    ConfigEnvelope envelope = MAPPER.readValue(ENVELOPE_JSON, ConfigEnvelope.class);
    try (Quonfig q = new Quonfig(Options.builder().datafileEnvelope(envelope).build())) {
      assertEquals("hello-prod", q.getString("greeting", "fallback"));
    }
  }

  @Test
  void datafile_explicitEnvironment_overridesEnvelopeMeta(@TempDir Path tmp) throws Exception {
    Path file = tmp.resolve("quonfig-datafile.json");
    Files.writeString(file, ENVELOPE_JSON);

    try (Quonfig q =
        new Quonfig(
            Options.builder()
                .datafile(file.toString())
                .environment("staging") // explicit caller choice wins over meta.environment
                .build())) {
      // staging has no environment-specific rules in the envelope, so the default rule applies.
      assertEquals("hello-default", q.getString("greeting", "fallback"));
      EvaluationDetails<String> d = q.getStringDetails("greeting", "fallback");
      assertEquals("staging", d.metadata().get("environment"));
    }
  }

  @Test
  void datafile_missingFile_throwsOnConstruct(@TempDir Path tmp) {
    Path missing = tmp.resolve("does-not-exist.json");
    assertThrows(
        RuntimeException.class,
        () -> new Quonfig(Options.builder().datafile(missing.toString()).build()));
  }

  // qfg-srj8 / chaos scenario 10: a RuntimeException thrown by an onConfigUpdate listener
  // must NOT crash the client and MUST be logged at ERROR with a message matching
  // /callback|onConfigUpdate/i so the chaos harness's sdkLog matcher can find it. Mirrors
  // sdk-go's invokeOnConfigUpdate logging and sdk-node's invokeOnConfigUpdate try/catch.
  @Test
  void datafile_onConfigUpdateListenerThrows_logsErrorWithCallbackKeyword() throws Exception {
    ConfigEnvelope envelope = MAPPER.readValue(ENVELOPE_JSON, ConfigEnvelope.class);
    RecordingLogger recording = new RecordingLogger();
    try (Quonfig q =
        new Quonfig(
            Options.builder()
                .datafileEnvelope(envelope)
                .logger(recording)
                .onConfigUpdate(
                    () -> {
                      throw new RuntimeException("simulated user-callback panic");
                    })
                .build())) {
      // Client must remain usable — the throw must not tear down construction.
      assertEquals("hello-prod", q.getString("greeting", "fallback"));
    }
    Pattern keyword = Pattern.compile("callback|onConfigUpdate", Pattern.CASE_INSENSITIVE);
    long matched =
        recording.entries.stream()
            .filter(e -> e.level == Level.ERROR)
            .filter(e -> keyword.matcher(e.format).find())
            .count();
    assertTrue(
        matched >= 1,
        "expected >=1 error log matching /callback|onConfigUpdate/i but saw: " + recording.entries);
  }

  /** Minimal SLF4J logger that captures every call as a (level, format) pair. */
  static final class RecordingLogger extends AbstractLogger {
    final List<Entry> entries = new ArrayList<>();

    static final class Entry {
      final Level level;
      final String format;

      Entry(Level level, String format) {
        this.level = level;
        this.format = format;
      }

      @Override
      public String toString() {
        return level + ":" + format;
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
      entries.add(new Entry(level, messagePattern == null ? "" : messagePattern));
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
