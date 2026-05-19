// AUTO-GENERATED from integration-test-data/tests/eval/post.yaml. DO NOT EDIT.
// Regenerate with:
//   cd integration-test-data/generators && npm run generate -- --target=java
// Source: integration-test-data/generators/src/targets/java.ts

package com.quonfig.sdk.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PostTest {

  @Test
  @DisplayName("reports context shape aggregation")
  void reportsContextShapeAggregation() throws Exception {
    Object aggregator =
        TestSetup.buildAggregator(
            "context_shape", TestSetup.map("context_upload_mode", ":shape_only"));
    TestSetup.feedAggregator(
        aggregator,
        "context_shape",
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
                TestSetup.list("read", "write"))),
        TestSetup.map());
    assertEquals(
        TestSetup.list(
            TestSetup.map(
                "name", "user", "field_types", TestSetup.map("name", 2L, "age", 1L, "human", 5L)),
            TestSetup.map(
                "name",
                "role",
                "field_types",
                TestSetup.map("name", 2L, "admin", 5L, "salary", 4L, "permissions", 10L))),
        TestSetup.aggregatorPost(aggregator, "context_shape", "/api/v1/context-shapes"));
  }

  @Test
  @DisplayName("reports evaluation summary")
  void reportsEvaluationSummary() throws Exception {
    Object aggregator = TestSetup.buildAggregator("evaluation_summary", TestSetup.map());
    TestSetup.feedAggregator(
        aggregator,
        "evaluation_summary",
        TestSetup.map(
            "keys",
            TestSetup.list(
                "my-test-key",
                "feature-flag.integer",
                "my-string-list-key",
                "feature-flag.integer",
                "feature-flag.weighted")),
        TestSetup.map("user", TestSetup.map("tracking_id", "92a202f2")));
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
        TestSetup.aggregatorPost(aggregator, "evaluation_summary", "/api/v1/telemetry"));
  }

  @Test
  @DisplayName("reports example contexts")
  void reportsExampleContexts() throws Exception {
    Object aggregator = TestSetup.buildAggregator("example_contexts", TestSetup.map());
    TestSetup.feedAggregator(
        aggregator,
        "example_contexts",
        TestSetup.map(
            "user",
            TestSetup.map("name", "michael", "age", 38L, "key", "michael:1234"),
            "device",
            TestSetup.map("mobile", false),
            "team",
            TestSetup.map("id", 3.5d)),
        TestSetup.map());
    assertEquals(
        TestSetup.map(
            "user",
            TestSetup.map("name", "michael", "age", 38L, "key", "michael:1234"),
            "device",
            TestSetup.map("mobile", false),
            "team",
            TestSetup.map("id", 3.5d)),
        TestSetup.aggregatorPost(aggregator, "example_contexts", "/api/v1/telemetry"));
  }

  @Test
  @DisplayName("example contexts without key are not reported")
  void exampleContextsWithoutKeyAreNotReported() throws Exception {
    Object aggregator = TestSetup.buildAggregator("example_contexts", TestSetup.map());
    TestSetup.feedAggregator(
        aggregator,
        "example_contexts",
        TestSetup.map(
            "user",
            TestSetup.map("name", "michael", "age", 38L),
            "device",
            TestSetup.map("mobile", false),
            "team",
            TestSetup.map("id", 3.5d)),
        TestSetup.map());
    assertEquals(
        null, TestSetup.aggregatorPost(aggregator, "example_contexts", "/api/v1/telemetry"));
  }
}
