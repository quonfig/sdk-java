// AUTO-GENERATED from integration-test-data/tests/eval/enabled.yaml. DO NOT EDIT.
// Regenerate with:
//   cd integration-test-data/generators && npm run generate -- --target=java
// Source: integration-test-data/generators/src/targets/java.ts

package com.quonfig.sdk.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.quonfig.sdk.BoundQuonfig;
import com.quonfig.sdk.Quonfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class EnabledTest {

  @Test
  @DisplayName("returns the correct value for a simple flag")
  void returnsTheCorrectValueForASimpleFlag() throws Exception {
    Quonfig client = TestSetup.client();
    assertEquals(true, client.featureIsOn("feature-flag.simple", null));
  }

  @Test
  @DisplayName("always returns false for a non-boolean flag")
  void alwaysReturnsFalseForANonBooleanFlag() throws Exception {
    Quonfig client = TestSetup.client();
    assertEquals(false, client.featureIsOn("feature-flag.integer", null));
  }

  @Test
  @DisplayName("returns true for a PROP_IS_ONE_OF rule when any prop matches")
  void returnsTrueForAPropIsOneOfRuleWhenAnyPropMatches() throws Exception {
    Quonfig client = TestSetup.client();
    assertEquals(
        true,
        client.featureIsOn(
            "feature-flag.properties.positive",
            TestSetup.ctx(
                TestSetup.map("", TestSetup.map("name", "michael", "domain", "something.com")))));
  }

  @Test
  @DisplayName("returns false for a PROP_IS_ONE_OF rule when no prop matches")
  void returnsFalseForAPropIsOneOfRuleWhenNoPropMatches() throws Exception {
    Quonfig client = TestSetup.client();
    assertEquals(
        false,
        client.featureIsOn(
            "feature-flag.properties.positive",
            TestSetup.ctx(
                TestSetup.map("", TestSetup.map("name", "lauren", "domain", "something.com")))));
  }

  @Test
  @DisplayName("returns true for a PROP_IS_NOT_ONE_OF rule when any prop doesn't match")
  void returnsTrueForAPropIsNotOneOfRuleWhenAnyPropDoesnTMatch() throws Exception {
    Quonfig client = TestSetup.client();
    assertEquals(
        true,
        client.featureIsOn(
            "feature-flag.properties.negative",
            TestSetup.ctx(
                TestSetup.map("", TestSetup.map("name", "lauren", "domain", "prefab.cloud")))));
  }

  @Test
  @DisplayName("returns false for a PROP_IS_NOT_ONE_OF rule when all props match")
  void returnsFalseForAPropIsNotOneOfRuleWhenAllPropsMatch() throws Exception {
    Quonfig client = TestSetup.client();
    assertEquals(
        false,
        client.featureIsOn(
            "feature-flag.properties.negative",
            TestSetup.ctx(
                TestSetup.map("", TestSetup.map("name", "michael", "domain", "prefab.cloud")))));
  }

  @Test
  @DisplayName(
      "returns true for PROP_ENDS_WITH_ONE_OF rule when the given prop has a matching suffix")
  void returnsTrueForPropEndsWithOneOfRuleWhenTheGivenPropHasAMatchingSuffix() throws Exception {
    Quonfig client = TestSetup.client();
    BoundQuonfig scoped =
        client.withContext(
            TestSetup.ctx(TestSetup.map("", TestSetup.map("email", "jeff@prefab.cloud"))));
    assertEquals(true, scoped.featureIsOn("feature-flag.ends-with-one-of.positive"));
  }

  @Test
  @DisplayName(
      "returns false for PROP_ENDS_WITH_ONE_OF rule when the given prop doesn't have a matching suffix")
  void returnsFalseForPropEndsWithOneOfRuleWhenTheGivenPropDoesnTHaveAMatchingSuffix()
      throws Exception {
    Quonfig client = TestSetup.client();
    assertEquals(
        false,
        client.featureIsOn(
            "feature-flag.ends-with-one-of.positive",
            TestSetup.ctx(TestSetup.map("", TestSetup.map("email", "jeff@test.com")))));
  }

  @Test
  @DisplayName(
      "returns true for PROP_DOES_NOT_END_WITH_ONE_OF rule when the given prop doesn't have a matching suffix")
  void returnsTrueForPropDoesNotEndWithOneOfRuleWhenTheGivenPropDoesnTHaveAMatchingSuffix()
      throws Exception {
    Quonfig client = TestSetup.client();
    BoundQuonfig scoped =
        client.withContext(
            TestSetup.ctx(TestSetup.map("", TestSetup.map("email", "michael@test.com"))));
    assertEquals(true, scoped.featureIsOn("feature-flag.ends-with-one-of.negative"));
  }

  @Test
  @DisplayName(
      "returns false for PROP_DOES_NOT_END_WITH_ONE_OF rule when the given prop has a matching suffix")
  void returnsFalseForPropDoesNotEndWithOneOfRuleWhenTheGivenPropHasAMatchingSuffix()
      throws Exception {
    Quonfig client = TestSetup.client();
    assertEquals(
        false,
        client.featureIsOn(
            "feature-flag.ends-with-one-of.negative",
            TestSetup.ctx(TestSetup.map("", TestSetup.map("email", "michael@prefab.cloud")))));
  }

  @Test
  @DisplayName(
      "returns true for PROP_STARTS_WITH_ONE_OF rule when the given prop has a matching prefix")
  void returnsTrueForPropStartsWithOneOfRuleWhenTheGivenPropHasAMatchingPrefix() throws Exception {
    Quonfig client = TestSetup.client();
    BoundQuonfig scoped =
        client.withContext(
            TestSetup.ctx(TestSetup.map("user", TestSetup.map("email", "foo@prefab.cloud"))));
    assertEquals(true, scoped.featureIsOn("feature-flag.starts-with-one-of.positive"));
  }

  @Test
  @DisplayName(
      "returns false for PROP_STARTS_WITH_ONE_OF rule when the given prop doesn't have a matching prefix")
  void returnsFalseForPropStartsWithOneOfRuleWhenTheGivenPropDoesnTHaveAMatchingPrefix()
      throws Exception {
    Quonfig client = TestSetup.client();
    BoundQuonfig scoped =
        client.withContext(
            TestSetup.ctx(TestSetup.map("user", TestSetup.map("email", "notfoo@prefab.cloud"))));
    assertEquals(false, scoped.featureIsOn("feature-flag.starts-with-one-of.positive"));
  }

  @Test
  @DisplayName(
      "returns true for PROP_DOES_NOT_START_WITH_ONE_OF rule when the given prop doesn't have a matching prefix")
  void returnsTrueForPropDoesNotStartWithOneOfRuleWhenTheGivenPropDoesnTHaveAMatchingPrefix()
      throws Exception {
    Quonfig client = TestSetup.client();
    BoundQuonfig scoped =
        client.withContext(
            TestSetup.ctx(TestSetup.map("user", TestSetup.map("email", "notfoo@prefab.cloud"))));
    assertEquals(true, scoped.featureIsOn("feature-flag.starts-with-one-of.negative"));
  }

  @Test
  @DisplayName(
      "returns false for PROP_DOES_NOT_START_WITH_ONE_OF rule when the given prop has a matching prefix")
  void returnsFalseForPropDoesNotStartWithOneOfRuleWhenTheGivenPropHasAMatchingPrefix()
      throws Exception {
    Quonfig client = TestSetup.client();
    BoundQuonfig scoped =
        client.withContext(
            TestSetup.ctx(TestSetup.map("user", TestSetup.map("email", "foo@prefab.cloud"))));
    assertEquals(false, scoped.featureIsOn("feature-flag.starts-with-one-of.negative"));
  }

  @Test
  @DisplayName(
      "returns true for PROP_CONTAINS_ONE_OF rule when the given prop has a matching substring")
  void returnsTrueForPropContainsOneOfRuleWhenTheGivenPropHasAMatchingSubstring() throws Exception {
    Quonfig client = TestSetup.client();
    BoundQuonfig scoped =
        client.withContext(
            TestSetup.ctx(TestSetup.map("user", TestSetup.map("email", "somefoo@prefab.cloud"))));
    assertEquals(true, scoped.featureIsOn("feature-flag.contains-one-of.positive"));
  }

  @Test
  @DisplayName(
      "returns false for PROP_CONTAINS_ONE_OF rule when the given prop doesn't have a matching substring")
  void returnsFalseForPropContainsOneOfRuleWhenTheGivenPropDoesnTHaveAMatchingSubstring()
      throws Exception {
    Quonfig client = TestSetup.client();
    BoundQuonfig scoped =
        client.withContext(
            TestSetup.ctx(TestSetup.map("user", TestSetup.map("email", "info@prefab.cloud"))));
    assertEquals(false, scoped.featureIsOn("feature-flag.contains-one-of.positive"));
  }

  @Test
  @DisplayName(
      "returns true for PROP_DOES_NOT_CONTAIN_ONE_OF rule when the given prop doesn't have a matching substring")
  void returnsTrueForPropDoesNotContainOneOfRuleWhenTheGivenPropDoesnTHaveAMatchingSubstring()
      throws Exception {
    Quonfig client = TestSetup.client();
    BoundQuonfig scoped =
        client.withContext(
            TestSetup.ctx(TestSetup.map("user", TestSetup.map("email", "info@prefab.cloud"))));
    assertEquals(true, scoped.featureIsOn("feature-flag.contains-one-of.negative"));
  }

  @Test
  @DisplayName(
      "returns false for PROP_DOES_NOT_CONTAIN_ONE_OF rule when the given prop has a matching substring")
  void returnsFalseForPropDoesNotContainOneOfRuleWhenTheGivenPropHasAMatchingSubstring()
      throws Exception {
    Quonfig client = TestSetup.client();
    BoundQuonfig scoped =
        client.withContext(
            TestSetup.ctx(TestSetup.map("user", TestSetup.map("email", "notfoo@prefab.cloud"))));
    assertEquals(false, scoped.featureIsOn("feature-flag.contains-one-of.negative"));
  }

  @Test
  @DisplayName("returns true for IN_SEG when the segment rule matches")
  void returnsTrueForInSegWhenTheSegmentRuleMatches() throws Exception {
    Quonfig client = TestSetup.client();
    BoundQuonfig scoped =
        client.withContext(TestSetup.ctx(TestSetup.map("user", TestSetup.map("key", "lauren"))));
    assertEquals(true, scoped.featureIsOn("feature-flag.in-segment.positive"));
  }

  @Test
  @DisplayName("returns false for IN_SEG when the segment rule doesn't match")
  void returnsFalseForInSegWhenTheSegmentRuleDoesnTMatch() throws Exception {
    Quonfig client = TestSetup.client();
    assertEquals(
        false,
        client.featureIsOn(
            "feature-flag.in-segment.positive",
            TestSetup.ctx(TestSetup.map("user", TestSetup.map("key", "josh")))));
  }

  @Test
  @DisplayName("returns false for IN_SEG if any segment rule fails to match")
  void returnsFalseForInSegIfAnySegmentRuleFailsToMatch() throws Exception {
    Quonfig client = TestSetup.client();
    BoundQuonfig scoped =
        client.withContext(
            TestSetup.ctx(
                TestSetup.map(
                    "user",
                    TestSetup.map("key", "josh"),
                    "",
                    TestSetup.map("domain", "prefab.cloud"))));
    assertEquals(false, scoped.featureIsOn("feature-flag.in-seg.segment-and"));
  }

  @Test
  @DisplayName("returns true for IN_SEG (segment-and) if all rules matches")
  void returnsTrueForInSegSegmentAndIfAllRulesMatches() throws Exception {
    Quonfig client = TestSetup.client();
    assertEquals(
        true,
        client.featureIsOn(
            "feature-flag.in-seg.segment-and",
            TestSetup.ctx(
                TestSetup.map(
                    "user",
                    TestSetup.map("key", "michael"),
                    "",
                    TestSetup.map("domain", "prefab.cloud")))));
  }

  @Test
  @DisplayName("returns true for IN_SEG (segment-or) if any segment rule matches (lookup)")
  void returnsTrueForInSegSegmentOrIfAnySegmentRuleMatchesLookup() throws Exception {
    Quonfig client = TestSetup.client();
    BoundQuonfig scoped =
        client.withContext(
            TestSetup.ctx(
                TestSetup.map(
                    "user",
                    TestSetup.map("key", "michael"),
                    "",
                    TestSetup.map("domain", "example.com"))));
    assertEquals(true, scoped.featureIsOn("feature-flag.in-seg.segment-or"));
  }

  @Test
  @DisplayName("returns true for IN_SEG (segment-or) if any segment rule matches (prop)")
  void returnsTrueForInSegSegmentOrIfAnySegmentRuleMatchesProp() throws Exception {
    Quonfig client = TestSetup.client();
    assertEquals(
        true,
        client.featureIsOn(
            "feature-flag.in-seg.segment-or",
            TestSetup.ctx(
                TestSetup.map(
                    "user",
                    TestSetup.map("key", "nobody"),
                    "",
                    TestSetup.map("domain", "gmail.com")))));
  }

  @Test
  @DisplayName("returns true for NOT_IN_SEG when the segment rule doesn't match")
  void returnsTrueForNotInSegWhenTheSegmentRuleDoesnTMatch() throws Exception {
    Quonfig client = TestSetup.client();
    BoundQuonfig scoped =
        client.withContext(TestSetup.ctx(TestSetup.map("user", TestSetup.map("key", "josh"))));
    assertEquals(true, scoped.featureIsOn("feature-flag.in-segment.negative"));
  }

  @Test
  @DisplayName("returns false for NOT_IN_SEG when the segment rule matches")
  void returnsFalseForNotInSegWhenTheSegmentRuleMatches() throws Exception {
    Quonfig client = TestSetup.client();
    assertEquals(
        false,
        client.featureIsOn(
            "feature-flag.in-segment.negative",
            TestSetup.ctx(TestSetup.map("user", TestSetup.map("key", "michael")))));
  }

  @Test
  @DisplayName("returns false for NOT_IN_SEG if any segment rule matches")
  void returnsFalseForNotInSegIfAnySegmentRuleMatches() throws Exception {
    Quonfig client = TestSetup.client();
    BoundQuonfig scoped =
        client.withContext(
            TestSetup.ctx(
                TestSetup.map(
                    "user",
                    TestSetup.map("key", "josh"),
                    "",
                    TestSetup.map("domain", "prefab.cloud"))));
    assertEquals(true, scoped.featureIsOn("feature-flag.in-segment.multiple-criteria.negative"));
  }

  @Test
  @DisplayName("returns true for NOT_IN_SEG if no segment rule matches")
  void returnsTrueForNotInSegIfNoSegmentRuleMatches() throws Exception {
    Quonfig client = TestSetup.client();
    assertEquals(
        true,
        client.featureIsOn(
            "feature-flag.in-segment.multiple-criteria.negative",
            TestSetup.ctx(
                TestSetup.map(
                    "user",
                    TestSetup.map("key", "josh"),
                    "",
                    TestSetup.map("domain", "something.com")))));
  }

  @Test
  @DisplayName("returns true for NOT_IN_SEG (segment-and) if not segment rule fails to match")
  void returnsTrueForNotInSegSegmentAndIfNotSegmentRuleFailsToMatch() throws Exception {
    Quonfig client = TestSetup.client();
    BoundQuonfig scoped =
        client.withContext(
            TestSetup.ctx(
                TestSetup.map(
                    "user",
                    TestSetup.map("key", "josh"),
                    "",
                    TestSetup.map("domain", "prefab.cloud"))));
    assertEquals(true, scoped.featureIsOn("feature-flag.not-in-seg.segment-and"));
  }

  @Test
  @DisplayName("returns true for IN_SEG (segment-and) if not segment rule fails to match")
  void returnsTrueForInSegSegmentAndIfNotSegmentRuleFailsToMatch() throws Exception {
    Quonfig client = TestSetup.client();
    assertEquals(
        false,
        client.featureIsOn(
            "feature-flag.in-seg.segment-and",
            TestSetup.ctx(
                TestSetup.map(
                    "user",
                    TestSetup.map("key", "josh"),
                    "",
                    TestSetup.map("domain", "prefab.cloud")))));
  }

  @Test
  @DisplayName("returns false for NOT_IN_SEG (segment-and) if segment rules matches")
  void returnsFalseForNotInSegSegmentAndIfSegmentRulesMatches() throws Exception {
    Quonfig client = TestSetup.client();
    BoundQuonfig scoped =
        client.withContext(
            TestSetup.ctx(
                TestSetup.map(
                    "user",
                    TestSetup.map("key", "michael"),
                    "",
                    TestSetup.map("domain", "prefab.cloud"))));
    assertEquals(false, scoped.featureIsOn("feature-flag.not-in-seg.segment-and"));
  }

  @Test
  @DisplayName("returns true for NOT_IN_SEG (segment-or) if no segment rule matches")
  void returnsTrueForNotInSegSegmentOrIfNoSegmentRuleMatches() throws Exception {
    Quonfig client = TestSetup.client();
    assertEquals(
        true,
        client.featureIsOn(
            "feature-flag.not-in-seg.segment-or",
            TestSetup.ctx(
                TestSetup.map(
                    "user",
                    TestSetup.map("key", "nobody"),
                    "",
                    TestSetup.map("domain", "example.com")))));
  }

  @Test
  @DisplayName("returns false for NOT_IN_SEG (segment-or) if one segment rule matches (prop)")
  void returnsFalseForNotInSegSegmentOrIfOneSegmentRuleMatchesProp() throws Exception {
    Quonfig client = TestSetup.client();
    BoundQuonfig scoped =
        client.withContext(
            TestSetup.ctx(
                TestSetup.map(
                    "user",
                    TestSetup.map("key", "nobody"),
                    "",
                    TestSetup.map("domain", "gmail.com"))));
    assertEquals(false, scoped.featureIsOn("feature-flag.not-in-seg.segment-or"));
  }

  @Test
  @DisplayName("returns false for NOT_IN_SEG (segment-or) if one segment rule matches (lookup)")
  void returnsFalseForNotInSegSegmentOrIfOneSegmentRuleMatchesLookup() throws Exception {
    Quonfig client = TestSetup.client();
    assertEquals(
        false,
        client.featureIsOn(
            "feature-flag.not-in-seg.segment-or",
            TestSetup.ctx(
                TestSetup.map(
                    "user",
                    TestSetup.map("key", "michael"),
                    "",
                    TestSetup.map("domain", "example.com")))));
  }

  @Test
  @DisplayName(
      "returns true for PROP_BEFORE rule when the given prop represents a date (string) before the rule's time")
  void returnsTrueForPropBeforeRuleWhenTheGivenPropRepresentsADateStringBeforeTheRuleSTime()
      throws Exception {
    Quonfig client = TestSetup.client();
    BoundQuonfig scoped =
        client.withContext(
            TestSetup.ctx(
                TestSetup.map("user", TestSetup.map("creation_date", "2024-11-01T00:00:00Z"))));
    assertEquals(true, scoped.featureIsOn("feature-flag.before"));
  }

  @Test
  @DisplayName(
      "returns true for PROP_BEFORE rule when the given prop represents a date (number) before the rule's time")
  void returnsTrueForPropBeforeRuleWhenTheGivenPropRepresentsADateNumberBeforeTheRuleSTime()
      throws Exception {
    Quonfig client = TestSetup.client();
    BoundQuonfig scoped =
        client.withContext(
            TestSetup.ctx(TestSetup.map("user", TestSetup.map("creation_date", 1730419200000L))));
    assertEquals(true, scoped.featureIsOn("feature-flag.before"));
  }

  @Test
  @DisplayName(
      "returns false for PROP_BEFORE rule when the given prop represents a date (number) exactly matching rule's time")
  void returnsFalseForPropBeforeRuleWhenTheGivenPropRepresentsADateNumberExactlyMatchingRuleSTime()
      throws Exception {
    Quonfig client = TestSetup.client();
    BoundQuonfig scoped =
        client.withContext(
            TestSetup.ctx(TestSetup.map("user", TestSetup.map("creation_date", 1733011200000L))));
    assertEquals(false, scoped.featureIsOn("feature-flag.before"));
  }

  @Test
  @DisplayName(
      "returns false for PROP_BEFORE rule when the given prop represents a date (number) AFTER the rule's time")
  void returnsFalseForPropBeforeRuleWhenTheGivenPropRepresentsADateNumberAfterTheRuleSTime()
      throws Exception {
    Quonfig client = TestSetup.client();
    BoundQuonfig scoped =
        client.withContext(
            TestSetup.ctx(
                TestSetup.map("user", TestSetup.map("creation_date", "2025-01-01T00:00:00Z"))));
    assertEquals(false, scoped.featureIsOn("feature-flag.before"));
  }

  @Test
  @DisplayName("returns false for PROP_BEFORE rule when the given prop won't parse as a date")
  void returnsFalseForPropBeforeRuleWhenTheGivenPropWonTParseAsADate() throws Exception {
    Quonfig client = TestSetup.client();
    BoundQuonfig scoped =
        client.withContext(
            TestSetup.ctx(TestSetup.map("user", TestSetup.map("creation_date", "not a date"))));
    assertEquals(false, scoped.featureIsOn("feature-flag.before"));
  }

  @Test
  @DisplayName("returns false for PROP_BEFORE rule using current-time relative to 2050-01-01")
  void returnsFalseForPropBeforeRuleUsingCurrentTimeRelativeTo20500101() throws Exception {
    Quonfig client = TestSetup.client();
    assertEquals(true, client.featureIsOn("feature-flag.before.current-time", null));
  }

  @Test
  @DisplayName(
      "returns true for PROP_AFTER rule when the given prop represents a date (string) after the rule's time")
  void returnsTrueForPropAfterRuleWhenTheGivenPropRepresentsADateStringAfterTheRuleSTime()
      throws Exception {
    Quonfig client = TestSetup.client();
    BoundQuonfig scoped =
        client.withContext(
            TestSetup.ctx(
                TestSetup.map("user", TestSetup.map("creation_date", "2025-01-01T00:00:00Z"))));
    assertEquals(true, scoped.featureIsOn("feature-flag.after"));
  }

  @Test
  @DisplayName(
      "returns true for PROP_AFTER rule when the given prop represents a date (number) after the rule's time")
  void returnsTrueForPropAfterRuleWhenTheGivenPropRepresentsADateNumberAfterTheRuleSTime()
      throws Exception {
    Quonfig client = TestSetup.client();
    BoundQuonfig scoped =
        client.withContext(
            TestSetup.ctx(TestSetup.map("user", TestSetup.map("creation_date", 1735689600000L))));
    assertEquals(true, scoped.featureIsOn("feature-flag.after"));
  }

  @Test
  @DisplayName(
      "returns false for PROP_AFTER rule when the given prop represents a date (number) exactly matching rule's time")
  void returnsFalseForPropAfterRuleWhenTheGivenPropRepresentsADateNumberExactlyMatchingRuleSTime()
      throws Exception {
    Quonfig client = TestSetup.client();
    BoundQuonfig scoped =
        client.withContext(
            TestSetup.ctx(TestSetup.map("user", TestSetup.map("creation_date", 1733011200000L))));
    assertEquals(false, scoped.featureIsOn("feature-flag.after"));
  }

  @Test
  @DisplayName(
      "returns false for PROP_BEFORE rule when the given prop represents a date (number) BEFORE the rule's time")
  void returnsFalseForPropBeforeRuleWhenTheGivenPropRepresentsADateNumberBeforeTheRuleSTime()
      throws Exception {
    Quonfig client = TestSetup.client();
    BoundQuonfig scoped =
        client.withContext(
            TestSetup.ctx(
                TestSetup.map("user", TestSetup.map("creation_date", "2024-01-01T00:00:00Z"))));
    assertEquals(false, scoped.featureIsOn("feature-flag.after"));
  }

  @Test
  @DisplayName("returns false for PROP_AFTER rule when the given prop won't parse as a date")
  void returnsFalseForPropAfterRuleWhenTheGivenPropWonTParseAsADate() throws Exception {
    Quonfig client = TestSetup.client();
    BoundQuonfig scoped =
        client.withContext(
            TestSetup.ctx(TestSetup.map("user", TestSetup.map("creation_date", "not a date"))));
    assertEquals(false, scoped.featureIsOn("feature-flag.after"));
  }

  @Test
  @DisplayName("returns false for PROP_AFTER rule using current-time relative to 2025-01-01")
  void returnsFalseForPropAfterRuleUsingCurrentTimeRelativeTo20250101() throws Exception {
    Quonfig client = TestSetup.client();
    assertEquals(true, client.featureIsOn("feature-flag.after.current-time", null));
  }

  @Test
  @DisplayName(
      "returns true for PROP_LESS_THAN rule when the given prop is less than the rule's value")
  void returnsTrueForPropLessThanRuleWhenTheGivenPropIsLessThanTheRuleSValue() throws Exception {
    Quonfig client = TestSetup.client();
    BoundQuonfig scoped =
        client.withContext(TestSetup.ctx(TestSetup.map("user", TestSetup.map("age", 20L))));
    assertEquals(true, scoped.featureIsOn("feature-flag.less-than"));
  }

  @Test
  @DisplayName(
      "returns true for PROP_LESS_THAN rule when the given prop is less than the rule's value (float)")
  void returnsTrueForPropLessThanRuleWhenTheGivenPropIsLessThanTheRuleSValueFloat()
      throws Exception {
    Quonfig client = TestSetup.client();
    BoundQuonfig scoped =
        client.withContext(TestSetup.ctx(TestSetup.map("user", TestSetup.map("age", 20.5d))));
    assertEquals(true, scoped.featureIsOn("feature-flag.less-than"));
  }

  @Test
  @DisplayName("returns false for PROP_LESS_THAN rule when the given prop is equal to rule's value")
  void returnsFalseForPropLessThanRuleWhenTheGivenPropIsEqualToRuleSValue() throws Exception {
    Quonfig client = TestSetup.client();
    BoundQuonfig scoped =
        client.withContext(TestSetup.ctx(TestSetup.map("user", TestSetup.map("age", 30L))));
    assertEquals(false, scoped.featureIsOn("feature-flag.less-than"));
  }

  @Test
  @DisplayName("returns false for PROP_LESS_THAN rule when the given prop a string")
  void returnsFalseForPropLessThanRuleWhenTheGivenPropAString() throws Exception {
    Quonfig client = TestSetup.client();
    BoundQuonfig scoped =
        client.withContext(TestSetup.ctx(TestSetup.map("user", TestSetup.map("age", "20"))));
    assertEquals(false, scoped.featureIsOn("feature-flag.less-than"));
  }

  @Test
  @DisplayName(
      "returns true for PROP_LESS_THAN_OR_EQUAL rule when the given prop is less than the rule's value")
  void returnsTrueForPropLessThanOrEqualRuleWhenTheGivenPropIsLessThanTheRuleSValue()
      throws Exception {
    Quonfig client = TestSetup.client();
    BoundQuonfig scoped =
        client.withContext(TestSetup.ctx(TestSetup.map("user", TestSetup.map("age", 20L))));
    assertEquals(true, scoped.featureIsOn("feature-flag.less-than-or-equal"));
  }

  @Test
  @DisplayName(
      "returns true for PROP_LESS_THAN_OR_EQUAL rule when the given prop is less than the rule's value (float)")
  void returnsTrueForPropLessThanOrEqualRuleWhenTheGivenPropIsLessThanTheRuleSValueFloat()
      throws Exception {
    Quonfig client = TestSetup.client();
    BoundQuonfig scoped =
        client.withContext(TestSetup.ctx(TestSetup.map("user", TestSetup.map("age", 20.5d))));
    assertEquals(true, scoped.featureIsOn("feature-flag.less-than-or-equal"));
  }

  @Test
  @DisplayName(
      "returns false for PROP_LESS_THAN_OR_EQUAL rule when the given prop is equal to rule's value")
  void returnsFalseForPropLessThanOrEqualRuleWhenTheGivenPropIsEqualToRuleSValue()
      throws Exception {
    Quonfig client = TestSetup.client();
    BoundQuonfig scoped =
        client.withContext(TestSetup.ctx(TestSetup.map("user", TestSetup.map("age", 30L))));
    assertEquals(true, scoped.featureIsOn("feature-flag.less-than-or-equal"));
  }

  @Test
  @DisplayName("returns false for PROP_LESS_THAN_OR_EQUAL rule when the given prop a string")
  void returnsFalseForPropLessThanOrEqualRuleWhenTheGivenPropAString() throws Exception {
    Quonfig client = TestSetup.client();
    BoundQuonfig scoped =
        client.withContext(TestSetup.ctx(TestSetup.map("user", TestSetup.map("age", "20"))));
    assertEquals(false, scoped.featureIsOn("feature-flag.less-than-or-equal"));
  }

  @Test
  @DisplayName(
      "returns true for PROP_GREATER_THAN rule when the given prop is greater than the rule's value")
  void returnsTrueForPropGreaterThanRuleWhenTheGivenPropIsGreaterThanTheRuleSValue()
      throws Exception {
    Quonfig client = TestSetup.client();
    BoundQuonfig scoped =
        client.withContext(TestSetup.ctx(TestSetup.map("user", TestSetup.map("age", 100L))));
    assertEquals(true, scoped.featureIsOn("feature-flag.greater-than"));
  }

  @Test
  @DisplayName(
      "returns true for PROP_GREATER_THAN rule when the given prop is greater than the rule's value (float)")
  void returnsTrueForPropGreaterThanRuleWhenTheGivenPropIsGreaterThanTheRuleSValueFloat()
      throws Exception {
    Quonfig client = TestSetup.client();
    BoundQuonfig scoped =
        client.withContext(TestSetup.ctx(TestSetup.map("user", TestSetup.map("age", 30.5d))));
    assertEquals(true, scoped.featureIsOn("feature-flag.greater-than"));
  }

  @Test
  @DisplayName(
      "returns true for PROP_GREATER_THAN rule when the given prop is greater than the rule's float value (float)")
  void returnsTrueForPropGreaterThanRuleWhenTheGivenPropIsGreaterThanTheRuleSFloatValueFloat()
      throws Exception {
    Quonfig client = TestSetup.client();
    BoundQuonfig scoped =
        client.withContext(TestSetup.ctx(TestSetup.map("user", TestSetup.map("age", 32.7d))));
    assertEquals(true, scoped.featureIsOn("feature-flag.greater-than.double"));
  }

  @Test
  @DisplayName(
      "returns true for PROP_GREATER_THAN rule when the given prop is greater than the rule's float value (integer)")
  void returnsTrueForPropGreaterThanRuleWhenTheGivenPropIsGreaterThanTheRuleSFloatValueInteger()
      throws Exception {
    Quonfig client = TestSetup.client();
    BoundQuonfig scoped =
        client.withContext(TestSetup.ctx(TestSetup.map("user", TestSetup.map("age", 32L))));
    assertEquals(true, scoped.featureIsOn("feature-flag.greater-than.double"));
  }

  @Test
  @DisplayName(
      "returns false for PROP_GREATER_THAN rule when the given prop is equal to rule's value")
  void returnsFalseForPropGreaterThanRuleWhenTheGivenPropIsEqualToRuleSValue() throws Exception {
    Quonfig client = TestSetup.client();
    BoundQuonfig scoped =
        client.withContext(TestSetup.ctx(TestSetup.map("user", TestSetup.map("age", 30L))));
    assertEquals(false, scoped.featureIsOn("feature-flag.greater-than"));
  }

  @Test
  @DisplayName("returns false for PROP_GREATER_THAN rule when the given prop a string")
  void returnsFalseForPropGreaterThanRuleWhenTheGivenPropAString() throws Exception {
    Quonfig client = TestSetup.client();
    BoundQuonfig scoped =
        client.withContext(TestSetup.ctx(TestSetup.map("user", TestSetup.map("age", "100"))));
    assertEquals(false, scoped.featureIsOn("feature-flag.greater-than"));
  }

  @Test
  @DisplayName(
      "returns true for PROP_GREATER_THAN_OR_EQUAL rule when the given prop is greater than the rule's value")
  void returnsTrueForPropGreaterThanOrEqualRuleWhenTheGivenPropIsGreaterThanTheRuleSValue()
      throws Exception {
    Quonfig client = TestSetup.client();
    BoundQuonfig scoped =
        client.withContext(TestSetup.ctx(TestSetup.map("user", TestSetup.map("age", 30L))));
    assertEquals(true, scoped.featureIsOn("feature-flag.greater-than-or-equal"));
  }

  @Test
  @DisplayName(
      "returns true for PROP_GREATER_THAN_OR_EQUAL rule when the given prop is greater than the rule's value (float)")
  void returnsTrueForPropGreaterThanOrEqualRuleWhenTheGivenPropIsGreaterThanTheRuleSValueFloat()
      throws Exception {
    Quonfig client = TestSetup.client();
    BoundQuonfig scoped =
        client.withContext(TestSetup.ctx(TestSetup.map("user", TestSetup.map("age", 30.5d))));
    assertEquals(true, scoped.featureIsOn("feature-flag.greater-than-or-equal"));
  }

  @Test
  @DisplayName(
      "returns true for PROP_GREATER_THAN_OR_EQUAL rule when the given prop is equal to rule's value")
  void returnsTrueForPropGreaterThanOrEqualRuleWhenTheGivenPropIsEqualToRuleSValue()
      throws Exception {
    Quonfig client = TestSetup.client();
    BoundQuonfig scoped =
        client.withContext(TestSetup.ctx(TestSetup.map("user", TestSetup.map("age", 30L))));
    assertEquals(true, scoped.featureIsOn("feature-flag.greater-than-or-equal"));
  }

  @Test
  @DisplayName("returns false for PROP_GREATER_THAN_OR_EQUAL rule when the given prop a string")
  void returnsFalseForPropGreaterThanOrEqualRuleWhenTheGivenPropAString() throws Exception {
    Quonfig client = TestSetup.client();
    BoundQuonfig scoped =
        client.withContext(TestSetup.ctx(TestSetup.map("user", TestSetup.map("age", "100"))));
    assertEquals(false, scoped.featureIsOn("feature-flag.greater-than-or-equal"));
  }

  @Test
  @DisplayName("returns true for PROP_MATCHES rule when the given prop matches the regex")
  void returnsTrueForPropMatchesRuleWhenTheGivenPropMatchesTheRegex() throws Exception {
    Quonfig client = TestSetup.client();
    BoundQuonfig scoped =
        client.withContext(TestSetup.ctx(TestSetup.map("user", TestSetup.map("code", "aaaaaab"))));
    assertEquals(true, scoped.featureIsOn("feature-flag.matches"));
  }

  @Test
  @DisplayName("returns false for PROP_MATCHES rule when the given prop does not match the regex")
  void returnsFalseForPropMatchesRuleWhenTheGivenPropDoesNotMatchTheRegex() throws Exception {
    Quonfig client = TestSetup.client();
    BoundQuonfig scoped =
        client.withContext(TestSetup.ctx(TestSetup.map("user", TestSetup.map("code", "aa"))));
    assertEquals(false, scoped.featureIsOn("feature-flag.matches"));
  }

  @Test
  @DisplayName(
      "returns true for PROP_DOES_NOT_MATCH rule when the given prop does not match the regex")
  void returnsTrueForPropDoesNotMatchRuleWhenTheGivenPropDoesNotMatchTheRegex() throws Exception {
    Quonfig client = TestSetup.client();
    BoundQuonfig scoped =
        client.withContext(TestSetup.ctx(TestSetup.map("user", TestSetup.map("code", "b"))));
    assertEquals(true, scoped.featureIsOn("feature-flag.does-not-match"));
  }

  @Test
  @DisplayName("returns false for PROP_DOES_NOT_MATCH rule when the given prop matches the regex")
  void returnsFalseForPropDoesNotMatchRuleWhenTheGivenPropMatchesTheRegex() throws Exception {
    Quonfig client = TestSetup.client();
    BoundQuonfig scoped =
        client.withContext(TestSetup.ctx(TestSetup.map("user", TestSetup.map("code", "aabb"))));
    assertEquals(false, scoped.featureIsOn("feature-flag.does-not-match"));
  }

  @Test
  @DisplayName("returns true for IS_PRESENT rule when the given prop is a non-empty string")
  void returnsTrueForIsPresentRuleWhenTheGivenPropIsANonEmptyString() throws Exception {
    Quonfig client = TestSetup.client();
    BoundQuonfig scoped =
        client.withContext(TestSetup.ctx(TestSetup.map("user", TestSetup.map("id", "abc"))));
    assertEquals(true, scoped.featureIsOn("feature-flag.is-present"));
  }

  @Test
  @DisplayName("returns true for IS_PRESENT rule when the given prop is an empty string")
  void returnsTrueForIsPresentRuleWhenTheGivenPropIsAnEmptyString() throws Exception {
    Quonfig client = TestSetup.client();
    BoundQuonfig scoped =
        client.withContext(TestSetup.ctx(TestSetup.map("user", TestSetup.map("id", ""))));
    assertEquals(true, scoped.featureIsOn("feature-flag.is-present"));
  }

  @Test
  @DisplayName("returns true for IS_PRESENT rule when the given prop is the integer zero")
  void returnsTrueForIsPresentRuleWhenTheGivenPropIsTheIntegerZero() throws Exception {
    Quonfig client = TestSetup.client();
    BoundQuonfig scoped =
        client.withContext(TestSetup.ctx(TestSetup.map("user", TestSetup.map("id", 0L))));
    assertEquals(true, scoped.featureIsOn("feature-flag.is-present"));
  }

  @Test
  @DisplayName("returns true for IS_PRESENT rule when the given prop is boolean false")
  void returnsTrueForIsPresentRuleWhenTheGivenPropIsBooleanFalse() throws Exception {
    Quonfig client = TestSetup.client();
    BoundQuonfig scoped =
        client.withContext(TestSetup.ctx(TestSetup.map("user", TestSetup.map("id", false))));
    assertEquals(true, scoped.featureIsOn("feature-flag.is-present"));
  }

  @Test
  @DisplayName("returns false for IS_PRESENT rule when the given prop is null")
  void returnsFalseForIsPresentRuleWhenTheGivenPropIsNull() throws Exception {
    Quonfig client = TestSetup.client();
    BoundQuonfig scoped =
        client.withContext(TestSetup.ctx(TestSetup.map("user", TestSetup.map("id", null))));
    assertEquals(false, scoped.featureIsOn("feature-flag.is-present"));
  }

  @Test
  @DisplayName(
      "returns false for IS_PRESENT rule when the given prop key is missing from the context")
  void returnsFalseForIsPresentRuleWhenTheGivenPropKeyIsMissingFromTheContext() throws Exception {
    Quonfig client = TestSetup.client();
    BoundQuonfig scoped =
        client.withContext(TestSetup.ctx(TestSetup.map("user", TestSetup.map("name", "bob"))));
    assertEquals(false, scoped.featureIsOn("feature-flag.is-present"));
  }

  @Test
  @DisplayName("returns false for IS_PRESENT rule when no contexts are provided at all")
  void returnsFalseForIsPresentRuleWhenNoContextsAreProvidedAtAll() throws Exception {
    Quonfig client = TestSetup.client();
    assertEquals(false, client.featureIsOn("feature-flag.is-present", null));
  }

  @Test
  @DisplayName("returns false for IS_NOT_PRESENT rule when the given prop is a non-empty string")
  void returnsFalseForIsNotPresentRuleWhenTheGivenPropIsANonEmptyString() throws Exception {
    Quonfig client = TestSetup.client();
    BoundQuonfig scoped =
        client.withContext(TestSetup.ctx(TestSetup.map("user", TestSetup.map("id", "abc"))));
    assertEquals(false, scoped.featureIsOn("feature-flag.is-not-present"));
  }

  @Test
  @DisplayName("returns true for IS_NOT_PRESENT rule when the given prop is null")
  void returnsTrueForIsNotPresentRuleWhenTheGivenPropIsNull() throws Exception {
    Quonfig client = TestSetup.client();
    BoundQuonfig scoped =
        client.withContext(TestSetup.ctx(TestSetup.map("user", TestSetup.map("id", null))));
    assertEquals(true, scoped.featureIsOn("feature-flag.is-not-present"));
  }

  @Test
  @DisplayName(
      "returns true for IS_NOT_PRESENT rule when the given prop key is missing from the context")
  void returnsTrueForIsNotPresentRuleWhenTheGivenPropKeyIsMissingFromTheContext() throws Exception {
    Quonfig client = TestSetup.client();
    BoundQuonfig scoped =
        client.withContext(TestSetup.ctx(TestSetup.map("user", TestSetup.map("name", "bob"))));
    assertEquals(true, scoped.featureIsOn("feature-flag.is-not-present"));
  }

  @Test
  @DisplayName("returns true for IS_PRESENT rule on a nested path when the nested prop is set")
  void returnsTrueForIsPresentRuleOnANestedPathWhenTheNestedPropIsSet() throws Exception {
    Quonfig client = TestSetup.client();
    BoundQuonfig scoped =
        client.withContext(
            TestSetup.ctx(TestSetup.map("organization", TestSetup.map("domain", "example.com"))));
    assertEquals(true, scoped.featureIsOn("feature-flag.is-present-nested"));
  }

  @Test
  @DisplayName(
      "returns false for IS_PRESENT rule on a nested path when the nested key is missing but the parent context exists")
  void returnsFalseForIsPresentRuleOnANestedPathWhenTheNestedKeyIsMissingButTheParentContextExists()
      throws Exception {
    Quonfig client = TestSetup.client();
    BoundQuonfig scoped =
        client.withContext(
            TestSetup.ctx(TestSetup.map("organization", TestSetup.map("name", "Acme Inc"))));
    assertEquals(false, scoped.featureIsOn("feature-flag.is-present-nested"));
  }

  @Test
  @DisplayName(
      "returns false for IS_PRESENT rule on a nested path when the parent context is entirely absent")
  void returnsFalseForIsPresentRuleOnANestedPathWhenTheParentContextIsEntirelyAbsent()
      throws Exception {
    Quonfig client = TestSetup.client();
    BoundQuonfig scoped =
        client.withContext(TestSetup.ctx(TestSetup.map("user", TestSetup.map("id", "abc"))));
    assertEquals(false, scoped.featureIsOn("feature-flag.is-present-nested"));
  }

  @Test
  @DisplayName("returns true for PROP_SEMVER_EQUAL rule when the given prop equals the version")
  void returnsTrueForPropSemverEqualRuleWhenTheGivenPropEqualsTheVersion() throws Exception {
    Quonfig client = TestSetup.client();
    BoundQuonfig scoped =
        client.withContext(TestSetup.ctx(TestSetup.map("app", TestSetup.map("version", "2.0.0"))));
    assertEquals(true, scoped.featureIsOn("feature-flag.semver-equal"));
  }

  @Test
  @DisplayName(
      "returns false for PROP_SEMVER_EQUAL rule when the given prop does not equal the version")
  void returnsFalseForPropSemverEqualRuleWhenTheGivenPropDoesNotEqualTheVersion() throws Exception {
    Quonfig client = TestSetup.client();
    BoundQuonfig scoped =
        client.withContext(TestSetup.ctx(TestSetup.map("app", TestSetup.map("version", "2.0.1"))));
    assertEquals(false, scoped.featureIsOn("feature-flag.semver-equal"));
  }

  @Test
  @DisplayName("returns false for PROP_SEMVER_EQUAL rule when the given prop is not a valid semver")
  void returnsFalseForPropSemverEqualRuleWhenTheGivenPropIsNotAValidSemver() throws Exception {
    Quonfig client = TestSetup.client();
    BoundQuonfig scoped =
        client.withContext(TestSetup.ctx(TestSetup.map("app", TestSetup.map("version", "2.0"))));
    assertEquals(false, scoped.featureIsOn("feature-flag.semver-equal"));
  }

  @Test
  @DisplayName("returns true for PROP_SEMVER_LESS_THAN rule when the given prop is less than 2.0.0")
  void returnsTrueForPropSemverLessThanRuleWhenTheGivenPropIsLessThan200() throws Exception {
    Quonfig client = TestSetup.client();
    BoundQuonfig scoped =
        client.withContext(TestSetup.ctx(TestSetup.map("app", TestSetup.map("version", "1.5.1"))));
    assertEquals(true, scoped.featureIsOn("feature-flag.semver-less-than"));
  }

  @Test
  @DisplayName(
      "returns false for PROP_SEMVER_LESS_THAN rule when the given prop equals the version")
  void returnsFalseForPropSemverLessThanRuleWhenTheGivenPropEqualsTheVersion() throws Exception {
    Quonfig client = TestSetup.client();
    BoundQuonfig scoped =
        client.withContext(TestSetup.ctx(TestSetup.map("app", TestSetup.map("version", "2.0.0"))));
    assertEquals(false, scoped.featureIsOn("feature-flag.semver-less-than"));
  }

  @Test
  @DisplayName(
      "returns false for PROP_SEMVER_LESS_THAN rule when the given prop is greater than the version")
  void returnsFalseForPropSemverLessThanRuleWhenTheGivenPropIsGreaterThanTheVersion()
      throws Exception {
    Quonfig client = TestSetup.client();
    BoundQuonfig scoped =
        client.withContext(TestSetup.ctx(TestSetup.map("app", TestSetup.map("version", "2.2.1"))));
    assertEquals(false, scoped.featureIsOn("feature-flag.semver-less-than"));
  }

  @Test
  @DisplayName(
      "returns true for PROP_SEMVER_GREATER_THAN rule when the given prop is greater than 2.0.0")
  void returnsTrueForPropSemverGreaterThanRuleWhenTheGivenPropIsGreaterThan200() throws Exception {
    Quonfig client = TestSetup.client();
    BoundQuonfig scoped =
        client.withContext(TestSetup.ctx(TestSetup.map("app", TestSetup.map("version", "2.5.1"))));
    assertEquals(true, scoped.featureIsOn("feature-flag.semver-greater-than"));
  }

  @Test
  @DisplayName(
      "returns false for PROP_SEMVER_GREATER_THAN rule when the given prop equals the version")
  void returnsFalseForPropSemverGreaterThanRuleWhenTheGivenPropEqualsTheVersion() throws Exception {
    Quonfig client = TestSetup.client();
    BoundQuonfig scoped =
        client.withContext(TestSetup.ctx(TestSetup.map("app", TestSetup.map("version", "2.0.0"))));
    assertEquals(false, scoped.featureIsOn("feature-flag.semver-greater-than"));
  }

  @Test
  @DisplayName(
      "returns false for PROP_SEMVER_EQUAL rule when the given prop is less than the version")
  void returnsFalseForPropSemverEqualRuleWhenTheGivenPropIsLessThanTheVersion() throws Exception {
    Quonfig client = TestSetup.client();
    BoundQuonfig scoped =
        client.withContext(TestSetup.ctx(TestSetup.map("app", TestSetup.map("version", "0.0.5"))));
    assertEquals(false, scoped.featureIsOn("feature-flag.semver-greater-than"));
  }
}
