package com.quonfig.sdk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.quonfig.sdk.wire.ConfigEnvelope;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
}
