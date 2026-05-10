// AUTO-GENERATED from integration-test-data/tests/eval/telemetry.yaml. DO NOT EDIT.
// Regenerate with:
//   cd integration-test-data/generators && npm run generate -- --target=java
// Source: integration-test-data/generators/src/targets/java.ts

package com.quonfig.sdk.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TelemetryTest {

  @Test
  @DisplayName("reason is STATIC for config with no targeting rules")
  void reasonIsStaticForConfigWithNoTargetingRules() throws Exception {
    Object aggregator = TestSetup.buildAggregator("evaluation_summary", TestSetup.map());
    TestSetup.feedAggregator(
        aggregator,
        "evaluation_summary",
        TestSetup.map("keys", TestSetup.list("brand.new.string")),
        TestSetup.map());
    assertEquals(
        TestSetup.list(
            TestSetup.map(
                "key",
                "brand.new.string",
                "type",
                "CONFIG",
                "value",
                "hello.world",
                "value_type",
                "string",
                "count",
                1L,
                "reason",
                1L,
                "selected_value",
                TestSetup.map("string", "hello.world"),
                "summary",
                TestSetup.map("config_row_index", 0L, "conditional_value_index", 0L))),
        TestSetup.aggregatorPost(aggregator, "evaluation_summary", "/api/v1/telemetry"));
  }

  @Test
  @DisplayName("reason is STATIC for feature flag with only ALWAYS_TRUE rules")
  void reasonIsStaticForFeatureFlagWithOnlyAlwaysTrueRules() throws Exception {
    Object aggregator = TestSetup.buildAggregator("evaluation_summary", TestSetup.map());
    TestSetup.feedAggregator(
        aggregator,
        "evaluation_summary",
        TestSetup.map("keys", TestSetup.list("always.true")),
        TestSetup.map());
    assertEquals(
        TestSetup.list(
            TestSetup.map(
                "key",
                "always.true",
                "type",
                "FEATURE_FLAG",
                "value",
                true,
                "value_type",
                "bool",
                "count",
                1L,
                "reason",
                1L,
                "selected_value",
                TestSetup.map("bool", true),
                "summary",
                TestSetup.map("config_row_index", 0L, "conditional_value_index", 0L))),
        TestSetup.aggregatorPost(aggregator, "evaluation_summary", "/api/v1/telemetry"));
  }

  @Test
  @DisplayName(
      "reason is TARGETING_MATCH when config has targeting rules but evaluation falls through")
  void reasonIsTargetingMatchWhenConfigHasTargetingRulesButEvaluationFallsThrough()
      throws Exception {
    Object aggregator = TestSetup.buildAggregator("evaluation_summary", TestSetup.map());
    TestSetup.feedAggregator(
        aggregator,
        "evaluation_summary",
        TestSetup.map("keys", TestSetup.list("my-test-key")),
        TestSetup.map());
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
                TestSetup.map("config_row_index", 0L, "conditional_value_index", 1L))),
        TestSetup.aggregatorPost(aggregator, "evaluation_summary", "/api/v1/telemetry"));
  }

  @Test
  @DisplayName("reason is TARGETING_MATCH when a targeting rule matches")
  void reasonIsTargetingMatchWhenATargetingRuleMatches() throws Exception {
    Object aggregator = TestSetup.buildAggregator("evaluation_summary", TestSetup.map());
    TestSetup.feedAggregator(
        aggregator,
        "evaluation_summary",
        TestSetup.map("keys", TestSetup.list("feature-flag.integer")),
        TestSetup.map("user", TestSetup.map("key", "michael")));
    assertEquals(
        TestSetup.list(
            TestSetup.map(
                "key",
                "feature-flag.integer",
                "type",
                "FEATURE_FLAG",
                "value",
                5L,
                "value_type",
                "int",
                "count",
                1L,
                "reason",
                2L,
                "selected_value",
                TestSetup.map("int", 5L),
                "summary",
                TestSetup.map("config_row_index", 0L, "conditional_value_index", 0L))),
        TestSetup.aggregatorPost(aggregator, "evaluation_summary", "/api/v1/telemetry"));
  }

  @Test
  @DisplayName("reason is SPLIT for weighted value evaluation")
  void reasonIsSplitForWeightedValueEvaluation() throws Exception {
    Object aggregator = TestSetup.buildAggregator("evaluation_summary", TestSetup.map());
    TestSetup.feedAggregator(
        aggregator,
        "evaluation_summary",
        TestSetup.map("keys", TestSetup.list("feature-flag.weighted")),
        TestSetup.map("user", TestSetup.map("tracking_id", "92a202f2")));
    assertEquals(
        TestSetup.list(
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
  @DisplayName("reason is TARGETING_MATCH for feature flag fallthrough with targeting rules")
  void reasonIsTargetingMatchForFeatureFlagFallthroughWithTargetingRules() throws Exception {
    Object aggregator = TestSetup.buildAggregator("evaluation_summary", TestSetup.map());
    TestSetup.feedAggregator(
        aggregator,
        "evaluation_summary",
        TestSetup.map("keys", TestSetup.list("feature-flag.integer")),
        TestSetup.map());
    assertEquals(
        TestSetup.list(
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
                1L,
                "reason",
                2L,
                "selected_value",
                TestSetup.map("int", 3L),
                "summary",
                TestSetup.map("config_row_index", 0L, "conditional_value_index", 1L))),
        TestSetup.aggregatorPost(aggregator, "evaluation_summary", "/api/v1/telemetry"));
  }

  @Test
  @DisplayName("evaluation summary deduplicates identical evaluations")
  void evaluationSummaryDeduplicatesIdenticalEvaluations() throws Exception {
    Object aggregator = TestSetup.buildAggregator("evaluation_summary", TestSetup.map());
    TestSetup.feedAggregator(
        aggregator,
        "evaluation_summary",
        TestSetup.map(
            "keys",
            TestSetup.list(
                "brand.new.string",
                "brand.new.string",
                "brand.new.string",
                "brand.new.string",
                "brand.new.string")),
        TestSetup.map());
    assertEquals(
        TestSetup.list(
            TestSetup.map(
                "key",
                "brand.new.string",
                "type",
                "CONFIG",
                "value",
                "hello.world",
                "value_type",
                "string",
                "count",
                5L,
                "reason",
                1L,
                "selected_value",
                TestSetup.map("string", "hello.world"),
                "summary",
                TestSetup.map("config_row_index", 0L, "conditional_value_index", 0L))),
        TestSetup.aggregatorPost(aggregator, "evaluation_summary", "/api/v1/telemetry"));
  }

  @Test
  @DisplayName("evaluation summary creates separate counters for different rules of same config")
  void evaluationSummaryCreatesSeparateCountersForDifferentRulesOfSameConfig() throws Exception {
    Object aggregator = TestSetup.buildAggregator("evaluation_summary", TestSetup.map());
    TestSetup.feedAggregator(
        aggregator,
        "evaluation_summary",
        TestSetup.map(
            "keys",
            TestSetup.list("feature-flag.integer"),
            "keys_without_context",
            TestSetup.list("feature-flag.integer")),
        TestSetup.map("user", TestSetup.map("key", "michael")));
    assertEquals(
        TestSetup.list(
            TestSetup.map(
                "key",
                "feature-flag.integer",
                "type",
                "FEATURE_FLAG",
                "value",
                5L,
                "value_type",
                "int",
                "count",
                1L,
                "reason",
                2L,
                "selected_value",
                TestSetup.map("int", 5L),
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
                1L,
                "reason",
                2L,
                "selected_value",
                TestSetup.map("int", 3L),
                "summary",
                TestSetup.map("config_row_index", 0L, "conditional_value_index", 1L))),
        TestSetup.aggregatorPost(aggregator, "evaluation_summary", "/api/v1/telemetry"));
  }

  @Test
  @DisplayName("evaluation summary groups by config key")
  void evaluationSummaryGroupsByConfigKey() throws Exception {
    Object aggregator = TestSetup.buildAggregator("evaluation_summary", TestSetup.map());
    TestSetup.feedAggregator(
        aggregator,
        "evaluation_summary",
        TestSetup.map("keys", TestSetup.list("brand.new.string", "always.true")),
        TestSetup.map());
    assertEquals(
        TestSetup.list(
            TestSetup.map(
                "key",
                "brand.new.string",
                "type",
                "CONFIG",
                "value",
                "hello.world",
                "value_type",
                "string",
                "count",
                1L,
                "reason",
                1L,
                "selected_value",
                TestSetup.map("string", "hello.world"),
                "summary",
                TestSetup.map("config_row_index", 0L, "conditional_value_index", 0L)),
            TestSetup.map(
                "key",
                "always.true",
                "type",
                "FEATURE_FLAG",
                "value",
                true,
                "value_type",
                "bool",
                "count",
                1L,
                "reason",
                1L,
                "selected_value",
                TestSetup.map("bool", true),
                "summary",
                TestSetup.map("config_row_index", 0L, "conditional_value_index", 0L))),
        TestSetup.aggregatorPost(aggregator, "evaluation_summary", "/api/v1/telemetry"));
  }

  @Test
  @DisplayName("selectedValue wraps string correctly")
  void selectedvalueWrapsStringCorrectly() throws Exception {
    Object aggregator = TestSetup.buildAggregator("evaluation_summary", TestSetup.map());
    TestSetup.feedAggregator(
        aggregator,
        "evaluation_summary",
        TestSetup.map("keys", TestSetup.list("brand.new.string")),
        TestSetup.map());
    assertEquals(
        TestSetup.list(
            TestSetup.map(
                "key",
                "brand.new.string",
                "type",
                "CONFIG",
                "value",
                "hello.world",
                "value_type",
                "string",
                "count",
                1L,
                "reason",
                1L,
                "selected_value",
                TestSetup.map("string", "hello.world"),
                "summary",
                TestSetup.map("config_row_index", 0L, "conditional_value_index", 0L))),
        TestSetup.aggregatorPost(aggregator, "evaluation_summary", "/api/v1/telemetry"));
  }

  @Test
  @DisplayName("selectedValue wraps boolean correctly")
  void selectedvalueWrapsBooleanCorrectly() throws Exception {
    Object aggregator = TestSetup.buildAggregator("evaluation_summary", TestSetup.map());
    TestSetup.feedAggregator(
        aggregator,
        "evaluation_summary",
        TestSetup.map("keys", TestSetup.list("brand.new.boolean")),
        TestSetup.map());
    assertEquals(
        TestSetup.list(
            TestSetup.map(
                "key",
                "brand.new.boolean",
                "type",
                "CONFIG",
                "value",
                false,
                "value_type",
                "bool",
                "count",
                1L,
                "reason",
                1L,
                "selected_value",
                TestSetup.map("bool", false),
                "summary",
                TestSetup.map("config_row_index", 0L, "conditional_value_index", 0L))),
        TestSetup.aggregatorPost(aggregator, "evaluation_summary", "/api/v1/telemetry"));
  }

  @Test
  @DisplayName("selectedValue wraps int correctly")
  void selectedvalueWrapsIntCorrectly() throws Exception {
    Object aggregator = TestSetup.buildAggregator("evaluation_summary", TestSetup.map());
    TestSetup.feedAggregator(
        aggregator,
        "evaluation_summary",
        TestSetup.map("keys", TestSetup.list("brand.new.int")),
        TestSetup.map());
    assertEquals(
        TestSetup.list(
            TestSetup.map(
                "key",
                "brand.new.int",
                "type",
                "CONFIG",
                "value",
                123L,
                "value_type",
                "int",
                "count",
                1L,
                "reason",
                1L,
                "selected_value",
                TestSetup.map("int", 123L),
                "summary",
                TestSetup.map("config_row_index", 0L, "conditional_value_index", 0L))),
        TestSetup.aggregatorPost(aggregator, "evaluation_summary", "/api/v1/telemetry"));
  }

  @Test
  @DisplayName("selectedValue wraps double correctly")
  void selectedvalueWrapsDoubleCorrectly() throws Exception {
    Object aggregator = TestSetup.buildAggregator("evaluation_summary", TestSetup.map());
    TestSetup.feedAggregator(
        aggregator,
        "evaluation_summary",
        TestSetup.map("keys", TestSetup.list("brand.new.double")),
        TestSetup.map());
    assertEquals(
        TestSetup.list(
            TestSetup.map(
                "key",
                "brand.new.double",
                "type",
                "CONFIG",
                "value",
                123.99d,
                "value_type",
                "double",
                "count",
                1L,
                "reason",
                1L,
                "selected_value",
                TestSetup.map("double", 123.99d),
                "summary",
                TestSetup.map("config_row_index", 0L, "conditional_value_index", 0L))),
        TestSetup.aggregatorPost(aggregator, "evaluation_summary", "/api/v1/telemetry"));
  }

  @Test
  @DisplayName("selectedValue wraps string list correctly")
  void selectedvalueWrapsStringListCorrectly() throws Exception {
    Object aggregator = TestSetup.buildAggregator("evaluation_summary", TestSetup.map());
    TestSetup.feedAggregator(
        aggregator,
        "evaluation_summary",
        TestSetup.map("keys", TestSetup.list("my-string-list-key")),
        TestSetup.map());
    assertEquals(
        TestSetup.list(
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
                TestSetup.map("config_row_index", 0L, "conditional_value_index", 0L))),
        TestSetup.aggregatorPost(aggregator, "evaluation_summary", "/api/v1/telemetry"));
  }

  @Test
  @DisplayName("context shape merges fields across multiple records")
  void contextShapeMergesFieldsAcrossMultipleRecords() throws Exception {
    Object aggregator = TestSetup.buildAggregator("context_shape", TestSetup.map());
    TestSetup.feedAggregator(
        aggregator,
        "context_shape",
        TestSetup.list(
            TestSetup.map("user", TestSetup.map("name", "alice", "age", 30L)),
            TestSetup.map(
                "user",
                TestSetup.map("name", "bob", "score", 9.5d),
                "team",
                TestSetup.map("name", "engineering"))),
        TestSetup.map());
    assertEquals(
        TestSetup.list(
            TestSetup.map(
                "name", "user", "field_types", TestSetup.map("name", 2L, "age", 1L, "score", 4L)),
            TestSetup.map("name", "team", "field_types", TestSetup.map("name", 2L))),
        TestSetup.aggregatorPost(aggregator, "context_shape", "/api/v1/context-shapes"));
  }

  @Test
  @DisplayName("example contexts deduplicates by key value")
  void exampleContextsDeduplicatesByKeyValue() throws Exception {
    Object aggregator = TestSetup.buildAggregator("example_contexts", TestSetup.map());
    TestSetup.feedAggregator(
        aggregator,
        "example_contexts",
        TestSetup.list(
            TestSetup.map("user", TestSetup.map("key", "user-123", "name", "alice")),
            TestSetup.map("user", TestSetup.map("key", "user-123", "name", "bob"))),
        TestSetup.map());
    assertEquals(
        TestSetup.map("user", TestSetup.map("key", "user-123", "name", "alice")),
        TestSetup.aggregatorPost(aggregator, "example_contexts", "/api/v1/telemetry"));
  }

  @Test
  @DisplayName("telemetry disabled emits nothing")
  void telemetryDisabledEmitsNothing() throws Exception {
    Object aggregator =
        TestSetup.buildAggregator(
            "evaluation_summary",
            TestSetup.map("collect_evaluation_summaries", false, "context_upload_mode", ":none"));
    TestSetup.feedAggregator(
        aggregator,
        "evaluation_summary",
        TestSetup.map("keys", TestSetup.list("brand.new.string")),
        TestSetup.map());
    assertEquals(
        null, TestSetup.aggregatorPost(aggregator, "evaluation_summary", "/api/v1/telemetry"));
  }

  @Test
  @DisplayName("shapes only mode reports shapes but not examples")
  void shapesOnlyModeReportsShapesButNotExamples() throws Exception {
    Object aggregator =
        TestSetup.buildAggregator(
            "context_shape", TestSetup.map("context_upload_mode", ":shape_only"));
    TestSetup.feedAggregator(
        aggregator,
        "context_shape",
        TestSetup.map("user", TestSetup.map("name", "alice", "key", "alice-123")),
        TestSetup.map());
    assertEquals(
        TestSetup.list(
            TestSetup.map("name", "user", "field_types", TestSetup.map("name", 2L, "key", 2L))),
        TestSetup.aggregatorPost(aggregator, "context_shape", "/api/v1/context-shapes"));
  }

  @Test
  @DisplayName("log level evaluations are excluded from telemetry")
  void logLevelEvaluationsAreExcludedFromTelemetry() throws Exception {
    Object aggregator = TestSetup.buildAggregator("evaluation_summary", TestSetup.map());
    TestSetup.feedAggregator(
        aggregator,
        "evaluation_summary",
        TestSetup.map("keys", TestSetup.list("log-level.prefab.criteria_evaluator")),
        TestSetup.map());
    assertEquals(
        null, TestSetup.aggregatorPost(aggregator, "evaluation_summary", "/api/v1/telemetry"));
  }

  @Test
  @DisplayName("empty context produces no context telemetry")
  void emptyContextProducesNoContextTelemetry() throws Exception {
    Object aggregator = TestSetup.buildAggregator("context_shape", TestSetup.map());
    TestSetup.feedAggregator(aggregator, "context_shape", TestSetup.map(), TestSetup.map());
    assertEquals(
        null, TestSetup.aggregatorPost(aggregator, "context_shape", "/api/v1/context-shapes"));
  }

  @Test
  @DisplayName("confidential plain string is redacted in selectedValue")
  void confidentialPlainStringIsRedactedInSelectedvalue() throws Exception {
    Object aggregator = TestSetup.buildAggregator("evaluation_summary", TestSetup.map());
    TestSetup.feedAggregator(
        aggregator,
        "evaluation_summary",
        TestSetup.map("keys", TestSetup.list("confidential.new.string")),
        TestSetup.map());
    assertEquals(
        TestSetup.list(
            TestSetup.map(
                "key",
                "confidential.new.string",
                "type",
                "CONFIG",
                "value",
                "hello.world",
                "value_type",
                "string",
                "count",
                1L,
                "reason",
                1L,
                "selected_value",
                TestSetup.map("string", "*****18aa7"),
                "summary",
                TestSetup.map("config_row_index", 0L, "conditional_value_index", 0L))),
        TestSetup.aggregatorPost(aggregator, "evaluation_summary", "/api/v1/telemetry"));
  }

  @Test
  @DisplayName("confidential encrypted string is redacted using ciphertext hash")
  void confidentialEncryptedStringIsRedactedUsingCiphertextHash() throws Exception {
    Object aggregator = TestSetup.buildAggregator("evaluation_summary", TestSetup.map());
    TestSetup.feedAggregator(
        aggregator,
        "evaluation_summary",
        TestSetup.map("keys", TestSetup.list("a.secret.config")),
        TestSetup.map());
    assertEquals(
        TestSetup.list(
            TestSetup.map(
                "key",
                "a.secret.config",
                "type",
                "CONFIG",
                "value",
                "hello.world",
                "value_type",
                "string",
                "count",
                1L,
                "reason",
                1L,
                "selected_value",
                TestSetup.map("string", "*****936c9"),
                "summary",
                TestSetup.map("config_row_index", 0L, "conditional_value_index", 0L))),
        TestSetup.aggregatorPost(aggregator, "evaluation_summary", "/api/v1/telemetry"));
  }
}
