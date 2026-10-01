// AUTO-GENERATED from integration-test-data/tests/eval/telemetry.yaml. DO NOT EDIT.
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

class TelemetryTest {

  @Test
  @DisplayName("reason is STATIC for config with no targeting rules")
  void reasonIsStaticForConfigWithNoTargetingRules() throws Exception {
    try (TestSetup.TelemetryClient t = TestSetup.telemetryClient(TestSetup.map(), null)) {
      Quonfig client = t.client();
      t.saw("brand.new.string", client.getString("brand.new.string", null));
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
          t.evaluationSummaries());
    }
  }

  @Test
  @DisplayName("reason is STATIC for feature flag with only ALWAYS_TRUE rules")
  void reasonIsStaticForFeatureFlagWithOnlyAlwaysTrueRules() throws Exception {
    try (TestSetup.TelemetryClient t = TestSetup.telemetryClient(TestSetup.map(), null)) {
      Quonfig client = t.client();
      t.saw("always.true", client.getBool("always.true", null));
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
          t.evaluationSummaries());
    }
  }

  @Test
  @DisplayName(
      "reason is TARGETING_MATCH when config has targeting rules but evaluation falls through")
  void reasonIsTargetingMatchWhenConfigHasTargetingRulesButEvaluationFallsThrough()
      throws Exception {
    try (TestSetup.TelemetryClient t = TestSetup.telemetryClient(TestSetup.map(), null)) {
      Quonfig client = t.client();
      t.saw("my-test-key", client.getString("my-test-key", null));
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
          t.evaluationSummaries());
    }
  }

  @Test
  @DisplayName("reason is TARGETING_MATCH when a targeting rule matches")
  void reasonIsTargetingMatchWhenATargetingRuleMatches() throws Exception {
    try (TestSetup.TelemetryClient t = TestSetup.telemetryClient(TestSetup.map(), null)) {
      Quonfig client = t.client();
      BoundQuonfig scoped =
          client.withContext(TestSetup.ctx(TestSetup.map("user", TestSetup.map("key", "michael"))));
      t.saw("feature-flag.integer", scoped.getLong("feature-flag.integer", null));
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
          t.evaluationSummaries());
    }
  }

  @Test
  @DisplayName("reason is SPLIT for weighted value evaluation")
  void reasonIsSplitForWeightedValueEvaluation() throws Exception {
    try (TestSetup.TelemetryClient t = TestSetup.telemetryClient(TestSetup.map(), null)) {
      Quonfig client = t.client();
      BoundQuonfig scoped =
          client.withContext(
              TestSetup.ctx(TestSetup.map("user", TestSetup.map("tracking_id", "92a202f2"))));
      t.saw("feature-flag.weighted", scoped.getLong("feature-flag.weighted", null));
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
          t.evaluationSummaries());
    }
  }

  @Test
  @DisplayName("reason is SPLIT for weighted value landing in bucket 0")
  void reasonIsSplitForWeightedValueLandingInBucket0() throws Exception {
    try (TestSetup.TelemetryClient t = TestSetup.telemetryClient(TestSetup.map(), null)) {
      Quonfig client = t.client();
      BoundQuonfig scoped =
          client.withContext(
              TestSetup.ctx(TestSetup.map("user", TestSetup.map("tracking_id", "3e9459d6"))));
      t.saw("feature-flag.weighted", scoped.getLong("feature-flag.weighted", null));
      assertEquals(
          TestSetup.list(
              TestSetup.map(
                  "key",
                  "feature-flag.weighted",
                  "type",
                  "FEATURE_FLAG",
                  "value",
                  1L,
                  "value_type",
                  "int",
                  "count",
                  1L,
                  "reason",
                  3L,
                  "selected_value",
                  TestSetup.map("int", 1L),
                  "summary",
                  TestSetup.map(
                      "config_row_index",
                      0L,
                      "conditional_value_index",
                      0L,
                      "weighted_value_index",
                      0L))),
          t.evaluationSummaries());
    }
  }

  @Test
  @DisplayName("reason is TARGETING_MATCH for feature flag fallthrough with targeting rules")
  void reasonIsTargetingMatchForFeatureFlagFallthroughWithTargetingRules() throws Exception {
    try (TestSetup.TelemetryClient t = TestSetup.telemetryClient(TestSetup.map(), null)) {
      Quonfig client = t.client();
      t.saw("feature-flag.integer", client.getLong("feature-flag.integer", null));
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
          t.evaluationSummaries());
    }
  }

  @Test
  @DisplayName("evaluation summary deduplicates identical evaluations")
  void evaluationSummaryDeduplicatesIdenticalEvaluations() throws Exception {
    try (TestSetup.TelemetryClient t = TestSetup.telemetryClient(TestSetup.map(), null)) {
      Quonfig client = t.client();
      t.saw("brand.new.string", client.getString("brand.new.string", null));
      t.saw("brand.new.string", client.getString("brand.new.string", null));
      t.saw("brand.new.string", client.getString("brand.new.string", null));
      t.saw("brand.new.string", client.getString("brand.new.string", null));
      t.saw("brand.new.string", client.getString("brand.new.string", null));
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
          t.evaluationSummaries());
    }
  }

  @Test
  @DisplayName("evaluation summary creates separate counters for different rules of same config")
  void evaluationSummaryCreatesSeparateCountersForDifferentRulesOfSameConfig() throws Exception {
    try (TestSetup.TelemetryClient t = TestSetup.telemetryClient(TestSetup.map(), null)) {
      Quonfig client = t.client();
      BoundQuonfig scoped =
          client.withContext(TestSetup.ctx(TestSetup.map("user", TestSetup.map("key", "michael"))));
      t.saw("feature-flag.integer", scoped.getLong("feature-flag.integer", null));
      t.saw("feature-flag.integer", client.getLong("feature-flag.integer", null));
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
          t.evaluationSummaries());
    }
  }

  @Test
  @DisplayName("evaluation summary groups by config key")
  void evaluationSummaryGroupsByConfigKey() throws Exception {
    try (TestSetup.TelemetryClient t = TestSetup.telemetryClient(TestSetup.map(), null)) {
      Quonfig client = t.client();
      t.saw("brand.new.string", client.getString("brand.new.string", null));
      t.saw("always.true", client.getBool("always.true", null));
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
          t.evaluationSummaries());
    }
  }

  @Test
  @DisplayName("selectedValue wraps string correctly")
  void selectedvalueWrapsStringCorrectly() throws Exception {
    try (TestSetup.TelemetryClient t = TestSetup.telemetryClient(TestSetup.map(), null)) {
      Quonfig client = t.client();
      t.saw("brand.new.string", client.getString("brand.new.string", null));
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
          t.evaluationSummaries());
    }
  }

  @Test
  @DisplayName("selectedValue wraps boolean correctly")
  void selectedvalueWrapsBooleanCorrectly() throws Exception {
    try (TestSetup.TelemetryClient t = TestSetup.telemetryClient(TestSetup.map(), null)) {
      Quonfig client = t.client();
      t.saw("brand.new.boolean", client.getBool("brand.new.boolean", null));
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
          t.evaluationSummaries());
    }
  }

  @Test
  @DisplayName("selectedValue wraps int correctly")
  void selectedvalueWrapsIntCorrectly() throws Exception {
    try (TestSetup.TelemetryClient t = TestSetup.telemetryClient(TestSetup.map(), null)) {
      Quonfig client = t.client();
      t.saw("brand.new.int", client.getLong("brand.new.int", null));
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
          t.evaluationSummaries());
    }
  }

  @Test
  @DisplayName("selectedValue wraps double correctly")
  void selectedvalueWrapsDoubleCorrectly() throws Exception {
    try (TestSetup.TelemetryClient t = TestSetup.telemetryClient(TestSetup.map(), null)) {
      Quonfig client = t.client();
      t.saw("brand.new.double", client.getDouble("brand.new.double", null));
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
          t.evaluationSummaries());
    }
  }

  @Test
  @DisplayName("selectedValue wraps string list correctly")
  void selectedvalueWrapsStringListCorrectly() throws Exception {
    try (TestSetup.TelemetryClient t = TestSetup.telemetryClient(TestSetup.map(), null)) {
      Quonfig client = t.client();
      t.saw("my-string-list-key", client.getStringList("my-string-list-key", null));
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
          t.evaluationSummaries());
    }
  }

  @Test
  @DisplayName("context shape merges fields across multiple records")
  void contextShapeMergesFieldsAcrossMultipleRecords() throws Exception {
    try (TestSetup.TelemetryClient t = TestSetup.telemetryClient(TestSetup.map(), null)) {
      Quonfig client = t.client();
      t.saw(
          "brand.new.string",
          client.getString(
              "brand.new.string",
              null,
              TestSetup.ctx(TestSetup.map("user", TestSetup.map("name", "alice", "age", 30L)))));
      t.saw(
          "brand.new.string",
          client.getString(
              "brand.new.string",
              null,
              TestSetup.ctx(
                  TestSetup.map(
                      "user",
                      TestSetup.map("name", "bob", "score", 9.5d),
                      "team",
                      TestSetup.map("name", "engineering")))));
      assertEquals(
          TestSetup.list(
              TestSetup.map(
                  "name", "user", "field_types", TestSetup.map("name", 2L, "age", 1L, "score", 4L)),
              TestSetup.map("name", "team", "field_types", TestSetup.map("name", 2L))),
          t.contextShapes());
    }
  }

  @Test
  @DisplayName("example contexts deduplicates by key value")
  void exampleContextsDeduplicatesByKeyValue() throws Exception {
    try (TestSetup.TelemetryClient t = TestSetup.telemetryClient(TestSetup.map(), null)) {
      Quonfig client = t.client();
      t.saw(
          "brand.new.string",
          client.getString(
              "brand.new.string",
              null,
              TestSetup.ctx(
                  TestSetup.map("user", TestSetup.map("key", "user-123", "name", "alice")))));
      t.saw(
          "brand.new.string",
          client.getString(
              "brand.new.string",
              null,
              TestSetup.ctx(
                  TestSetup.map("user", TestSetup.map("key", "user-123", "name", "bob")))));
      assertEquals(
          TestSetup.map("user", TestSetup.map("key", "user-123", "name", "alice")),
          t.exampleContexts());
    }
  }

  @Test
  @DisplayName("telemetry disabled emits nothing")
  void telemetryDisabledEmitsNothing() throws Exception {
    try (TestSetup.TelemetryClient t =
        TestSetup.telemetryClient(
            TestSetup.map("collect_evaluation_summaries", false, "context_upload_mode", ":none"),
            null)) {
      Quonfig client = t.client();
      t.saw("brand.new.string", client.getString("brand.new.string", null));
      assertNull(t.evaluationSummaries());
    }
  }

  @Test
  @DisplayName("shapes only mode reports shapes but not examples")
  void shapesOnlyModeReportsShapesButNotExamples() throws Exception {
    try (TestSetup.TelemetryClient t =
        TestSetup.telemetryClient(TestSetup.map("context_upload_mode", ":shape_only"), null)) {
      Quonfig client = t.client();
      t.saw(
          "brand.new.string",
          client.getString(
              "brand.new.string",
              null,
              TestSetup.ctx(
                  TestSetup.map("user", TestSetup.map("name", "alice", "key", "alice-123")))));
      assertEquals(
          TestSetup.list(
              TestSetup.map("name", "user", "field_types", TestSetup.map("name", 2L, "key", 2L))),
          t.contextShapes());
    }
  }

  @Test
  @DisplayName("log level evaluations are excluded from telemetry")
  void logLevelEvaluationsAreExcludedFromTelemetry() throws Exception {
    try (TestSetup.TelemetryClient t = TestSetup.telemetryClient(TestSetup.map(), null)) {
      Quonfig client = t.client();
      t.saw(
          "log-level.prefab.criteria_evaluator",
          client.getString("log-level.prefab.criteria_evaluator", null));
      assertNull(t.evaluationSummaries());
    }
  }

  @Test
  @DisplayName("empty context produces no context telemetry")
  void emptyContextProducesNoContextTelemetry() throws Exception {
    try (TestSetup.TelemetryClient t = TestSetup.telemetryClient(TestSetup.map(), null)) {
      Quonfig client = t.client();
      t.saw(
          "brand.new.string",
          client.getString("brand.new.string", null, TestSetup.ctx(TestSetup.map())));
      assertNull(t.contextShapes());
    }
  }

  @Test
  @DisplayName("confidential plain string is redacted in selectedValue")
  void confidentialPlainStringIsRedactedInSelectedvalue() throws Exception {
    try (TestSetup.TelemetryClient t = TestSetup.telemetryClient(TestSetup.map(), null)) {
      Quonfig client = t.client();
      t.saw("confidential.new.string", client.getString("confidential.new.string", null));
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
          t.evaluationSummaries());
    }
  }

  @Test
  @DisplayName("confidential encrypted string is redacted using ciphertext hash")
  void confidentialEncryptedStringIsRedactedUsingCiphertextHash() throws Exception {
    try (TestSetup.TelemetryClient t = TestSetup.telemetryClient(TestSetup.map(), null)) {
      Quonfig client = t.client();
      t.saw("a.secret.config", client.getString("a.secret.config", null));
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
          t.evaluationSummaries());
    }
  }
}
