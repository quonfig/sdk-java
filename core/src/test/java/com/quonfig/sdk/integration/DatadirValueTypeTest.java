// AUTO-GENERATED from integration-test-data/tests/eval/datadir_value_type.yaml. DO NOT EDIT.
// Regenerate with:
//   cd integration-test-data/generators && npm run generate -- --target=java
// Source: integration-test-data/generators/src/targets/java.ts

package com.quonfig.sdk.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.quonfig.sdk.Quonfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class DatadirValueTypeTest {

  @Test
  @DisplayName("datadir int config value is loaded as a number, not a string")
  void datadirIntConfigValueIsLoadedAsANumberNotAString() throws Exception {
    try (Quonfig client =
        TestSetup.datadirClient(
            TestSetup.map("datadir", TestSetup.DATADIR, "environment", "Production"))) {
      assertEquals(123L, client.getLong("brand.new.int", null));
    }
  }

  @Test
  @DisplayName("datadir double config value is loaded as a number, not a string")
  void datadirDoubleConfigValueIsLoadedAsANumberNotAString() throws Exception {
    try (Quonfig client =
        TestSetup.datadirClient(
            TestSetup.map("datadir", TestSetup.DATADIR, "environment", "Production"))) {
      assertEquals(9.95d, client.getDouble("my-double-key", null));
    }
  }
}
