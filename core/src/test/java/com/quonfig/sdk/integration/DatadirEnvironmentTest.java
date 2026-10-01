// AUTO-GENERATED from integration-test-data/tests/eval/datadir_environment.yaml. DO NOT EDIT.
// Regenerate with:
//   cd integration-test-data/generators && npm run generate -- --target=java
// Source: integration-test-data/generators/src/targets/java.ts

package com.quonfig.sdk.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.quonfig.sdk.Quonfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class DatadirEnvironmentTest {

  @Test
  @DisplayName("datadir with environment option gets environment-specific value")
  void datadirWithEnvironmentOptionGetsEnvironmentSpecificValue() throws Exception {
    try (Quonfig client =
        TestSetup.datadirClient(
            TestSetup.map("datadir", TestSetup.DATADIR, "environment", "Production"))) {
      assertEquals("test4", client.getString("james.test.key", null));
    }
  }

  @Test
  @DisplayName("datadir with QUONFIG_ENVIRONMENT env var gets environment-specific value")
  void datadirWithQuonfigEnvironmentEnvVarGetsEnvironmentSpecificValue() throws Exception {
    TestSetup.withEnv(
        TestSetup.map("QUONFIG_ENVIRONMENT", "Production"),
        () -> {
          try (Quonfig client =
              TestSetup.datadirClient(TestSetup.map("datadir", TestSetup.DATADIR))) {
            assertEquals("test4", client.getString("james.test.key", null));
          }
        });
  }

  @Test
  @DisplayName("environment option supersedes QUONFIG_ENVIRONMENT env var")
  void environmentOptionSupersedesQuonfigEnvironmentEnvVar() throws Exception {
    TestSetup.withEnv(
        TestSetup.map("QUONFIG_ENVIRONMENT", "nonexistent"),
        () -> {
          try (Quonfig client =
              TestSetup.datadirClient(
                  TestSetup.map("datadir", TestSetup.DATADIR, "environment", "Production"))) {
            assertEquals("test4", client.getString("james.test.key", null));
          }
        });
  }

  @Test
  @DisplayName("config without environment override returns default value")
  void configWithoutEnvironmentOverrideReturnsDefaultValue() throws Exception {
    try (Quonfig client =
        TestSetup.datadirClient(
            TestSetup.map("datadir", TestSetup.DATADIR, "environment", "Production"))) {
      assertEquals(
          "hello from no env row", client.getString("config.with.only.default.env.row", null));
    }
  }

  @Test
  @DisplayName("datadir without environment fails to init")
  void datadirWithoutEnvironmentFailsToInit() throws Exception {
    assertThrows(
        RuntimeException.class,
        () -> TestSetup.datadirClient(TestSetup.map("datadir", TestSetup.DATADIR)));
  }

  @Test
  @DisplayName("datadir with invalid environment fails to init")
  void datadirWithInvalidEnvironmentFailsToInit() throws Exception {
    assertThrows(
        RuntimeException.class,
        () ->
            TestSetup.datadirClient(
                TestSetup.map("datadir", TestSetup.DATADIR, "environment", "nonexistent")));
  }
}
