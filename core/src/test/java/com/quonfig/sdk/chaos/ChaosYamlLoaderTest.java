package com.quonfig.sdk.chaos;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Chaos scenario loader strictness (qfg-goi1.7 timeline rule 4). */
class ChaosYamlLoaderTest {

  @TempDir Path dir;

  private Path write(String expectationsYaml) throws Exception {
    Path p = dir.resolve("s.yaml");
    Files.writeString(
        p,
        "function: sse_resilience\n"
            + "tests:\n"
            + "  - name: t\n"
            + "    setup: { sdk: any, wall_clock_seconds: 10 }\n"
            + "    expectations:\n"
            + expectationsYaml);
    return p;
  }

  @Test
  void loadsAfterMs() throws Exception {
    ChaosScenario s =
        ChaosYamlLoader.load(
            write(
                "      - within_ms: 9000\n"
                    + "        after_ms: 4000\n"
                    + "        must_hold_for_ms: 2000\n"
                    + "        assert: \"client.connectionState() == 'connected'\"\n"));
    ChaosScenario.Expectation e = s.tests.get(0).expectations.get(0);
    assertEquals(4000, e.afterMs);
    assertEquals(9000, e.withinMs);
    assertEquals(2000, e.mustHoldForMs);
  }

  @Test
  void rejectsUnknownExpectationKey_namingIt() throws Exception {
    Path p =
        write(
            "      - within_ms: 9000\n"
                + "        not_before_ms: 4000\n"
                + "        assert: \"client.connectionState() == 'connected'\"\n");
    RuntimeException ex = assertThrows(RuntimeException.class, () -> ChaosYamlLoader.load(p));
    assertTrue(
        ex.getMessage().contains("not_before_ms"),
        "loader error must name the unknown key: " + ex.getMessage());
  }
}
