// AUTO-GENERATED from integration-test-data/tests/eval/datadir_value_type.yaml. DO NOT EDIT.
// Regenerate with:
//   cd integration-test-data/generators && npm run generate -- --target=java
// Source: integration-test-data/generators/src/targets/java.ts

package com.quonfig.sdk.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class DatadirValueTypeTest {

  @Test
  @DisplayName("datadir int config value is loaded as a number, not a string")
  void datadirIntConfigValueIsLoadedAsANumberNotAString() throws Exception {
    Object actual =
        TestSetup.datadirGet(
            TestSetup.map("datadir", TestSetup.DATADIR, "environment", "Production"),
            "brand.new.int");
    assertEquals(123L, actual);
    TestSetup.assertRawValueNumeric(
        TestSetup.map("datadir", TestSetup.DATADIR, "environment", "Production"), "brand.new.int");
  }

  @Test
  @DisplayName("datadir double config value is loaded as a number, not a string")
  void datadirDoubleConfigValueIsLoadedAsANumberNotAString() throws Exception {
    Object actual =
        TestSetup.datadirGet(
            TestSetup.map("datadir", TestSetup.DATADIR, "environment", "Production"),
            "my-double-key");
    TestSetup.assertDoubleEquals(9.95d, actual);
    TestSetup.assertRawValueNumeric(
        TestSetup.map("datadir", TestSetup.DATADIR, "environment", "Production"), "my-double-key");
  }
}
