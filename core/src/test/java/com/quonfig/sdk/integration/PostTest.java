// AUTO-GENERATED from integration-test-data/tests/eval/post.yaml. DO NOT EDIT.
// Regenerate with:
//   cd integration-test-data/generators && npm run generate -- --target=java
// Source: integration-test-data/generators/src/targets/java.ts

package com.quonfig.sdk.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.quonfig.sdk.BoundQuonfig;
import com.quonfig.sdk.Quonfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PostTest {

  @Test
  @DisplayName("reports context shape aggregation")
  void reportsContextShapeAggregation() throws Exception {
    try (TestSetup.TelemetryClient t =
        TestSetup.telemetryClient(TestSetup.map("context_upload_mode", ":shape_only"), null)) {
      Quonfig client = t.client();
      t.saw(
          "brand.new.string",
          client.getString(
              "brand.new.string",
              null,
              TestSetup.ctx(
                  TestSetup.map(
                      "user",
                      TestSetup.map("name", "Michael", "age", 38L, "human", true),
                      "role",
                      TestSetup.map(
                          "name",
                          "developer",
                          "admin",
                          false,
                          "salary",
                          15.75d,
                          "permissions",
                          TestSetup.list("read", "write"))))));
      assertEquals(
          TestSetup.list(
              TestSetup.map(
                  "name", "user", "field_types", TestSetup.map("name", 2L, "age", 1L, "human", 5L)),
              TestSetup.map(
                  "name",
                  "role",
                  "field_types",
                  TestSetup.map("name", 2L, "admin", 5L, "salary", 4L, "permissions", 10L))),
          t.contextShapes());
    }
  }

  @Test
  @DisplayName("reports evaluation summary")
  void reportsEvaluationSummary() throws Exception {
    try (TestSetup.TelemetryClient t = TestSetup.telemetryClient(TestSetup.map(), null)) {
      Quonfig client = t.client();
      BoundQuonfig scoped =
          client.withContext(
              TestSetup.ctx(TestSetup.map("user", TestSetup.map("tracking_id", "92a202f2"))));
      t.saw("my-test-key", scoped.getString("my-test-key", null));
      t.saw("feature-flag.integer", scoped.getLong("feature-flag.integer", null));
      t.saw("my-string-list-key", scoped.getStringList("my-string-list-key", null));
      t.saw("feature-flag.integer", scoped.getLong("feature-flag.integer", null));
      t.saw("feature-flag.weighted", scoped.getLong("feature-flag.weighted", null));
      assertEquals(
          TestSetup.list(
              TestSetup.map(
                  "key",
                  "my-test-key",
                  "type",
                  "CONFIG",
                  "value",
                  "my-test-value",
                  "value_type",
                  "string",
                  "count",
                  1L,
                  "reason",
                  2L,
                  "selected_value",
                  TestSetup.map("string", "my-test-value"),
                  "summary",
                  TestSetup.map("config_row_index", 0L, "conditional_value_index", 1L)),
              TestSetup.map(
                  "key",
                  "my-string-list-key",
                  "type",
                  "CONFIG",
                  "value",
                  TestSetup.list("a", "b", "c"),
                  "value_type",
                  "string_list",
                  "count",
                  1L,
                  "reason",
                  1L,
                  "selected_value",
                  TestSetup.map("stringList", TestSetup.list("a", "b", "c")),
                  "summary",
                  TestSetup.map("config_row_index", 0L, "conditional_value_index", 0L)),
              TestSetup.map(
                  "key",
                  "feature-flag.integer",
                  "type",
                  "FEATURE_FLAG",
                  "value",
                  3L,
                  "value_type",
                  "int",
                  "count",
                  2L,
                  "reason",
                  2L,
                  "selected_value",
                  TestSetup.map("int", 3L),
                  "summary",
                  TestSetup.map("config_row_index", 0L, "conditional_value_index", 1L)),
              TestSetup.map(
                  "key",
                  "feature-flag.weighted",
                  "type",
                  "FEATURE_FLAG",
                  "value",
                  2L,
                  "value_type",
                  "int",
                  "count",
                  1L,
                  "reason",
                  3L,
                  "selected_value",
                  TestSetup.map("int", 2L),
                  "summary",
                  TestSetup.map(
                      "config_row_index",
                      0L,
                      "conditional_value_index",
                      0L,
                      "weighted_value_index",
                      2L))),
          t.evaluationSummaries());
    }
  }

  @Test
  @DisplayName("reports example contexts")
  void reportsExampleContexts() throws Exception {
    try (TestSetup.TelemetryClient t = TestSetup.telemetryClient(TestSetup.map(), null)) {
      Quonfig client = t.client();
      t.saw(
          "brand.new.string",
          client.getString(
              "brand.new.string",
              null,
              TestSetup.ctx(
                  TestSetup.map(
                      "user",
                      TestSetup.map("name", "michael", "age", 38L, "key", "michael:1234"),
                      "device",
                      TestSetup.map("mobile", false),
                      "team",
                      TestSetup.map("id", 3.5d)))));
      assertEquals(
          TestSetup.map(
              "user",
              TestSetup.map("name", "michael", "age", 38L, "key", "michael:1234"),
              "device",
              TestSetup.map("mobile", false),
              "team",
              TestSetup.map("id", 3.5d)),
          t.exampleContexts());
    }
  }

  @Test
  @DisplayName("example contexts without key are not reported")
  void exampleContextsWithoutKeyAreNotReported() throws Exception {
    try (TestSetup.TelemetryClient t = TestSetup.telemetryClient(TestSetup.map(), null)) {
      Quonfig client = t.client();
      t.saw(
          "brand.new.string",
          client.getString(
              "brand.new.string",
              null,
              TestSetup.ctx(
                  TestSetup.map(
                      "user",
                      TestSetup.map("name", "michael", "age", 38L),
                      "device",
                      TestSetup.map("mobile", false),
                      "team",
                      TestSetup.map("id", 3.5d)))));
      assertNull(t.exampleContexts());
    }
  }
}
