// AUTO-GENERATED from integration-test-data/tests/eval/datadir_environment.yaml. DO NOT EDIT.
// Regenerate with:
//   cd integration-test-data/generators && npm run generate -- --target=java
// Source: integration-test-data/generators/src/targets/java.ts

package com.quonfig.sdk.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class DatadirEnvironmentTest {

  @Test
  @DisplayName("datadir with environment option gets environment-specific value")
  void datadirWithEnvironmentOptionGetsEnvironmentSpecificValue() throws Exception {
    Object actual =
        TestSetup.datadirGet(
            TestSetup.map("datadir", TestSetup.DATADIR, "environment", "Production"),
            "james.test.key");
    assertEquals("test4", actual);
  }

  @Test
  @DisplayName("datadir with QUONFIG_ENVIRONMENT env var gets environment-specific value")
  void datadirWithQuonfigEnvironmentEnvVarGetsEnvironmentSpecificValue() throws Exception {
    TestSetup.withEnv(
        TestSetup.map("QUONFIG_ENVIRONMENT", "Production"),
        () -> {
          Object actual =
              TestSetup.datadirGet(TestSetup.map("datadir", TestSetup.DATADIR), "james.test.key");
          assertEquals("test4", actual);
        });
  }

  @Test
  @DisplayName("environment option supersedes QUONFIG_ENVIRONMENT env var")
  void environmentOptionSupersedesQuonfigEnvironmentEnvVar() throws Exception {
    TestSetup.withEnv(
        TestSetup.map("QUONFIG_ENVIRONMENT", "nonexistent"),
        () -> {
          Object actual =
              TestSetup.datadirGet(
                  TestSetup.map("datadir", TestSetup.DATADIR, "environment", "Production"),
                  "james.test.key");
          assertEquals("test4", actual);
        });
  }

  @Test
  @DisplayName("config without environment override returns default value")
  void configWithoutEnvironmentOverrideReturnsDefaultValue() throws Exception {
    Object actual =
        TestSetup.datadirGet(
            TestSetup.map("datadir", TestSetup.DATADIR, "environment", "Production"),
            "config.with.only.default.env.row");
    assertEquals("hello from no env row", actual);
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
