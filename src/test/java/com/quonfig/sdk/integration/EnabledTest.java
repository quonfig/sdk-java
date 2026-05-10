// AUTO-GENERATED from integration-test-data/tests/eval/enabled.yaml. DO NOT EDIT.
// Regenerate with:
//   cd integration-test-data/generators && npm run generate -- --target=java
// Source: integration-test-data/generators/src/targets/java.ts

package com.quonfig.sdk.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class EnabledTest {

  @Test
  @DisplayName("returns the correct value for a simple flag")
  void returnsTheCorrectValueForASimpleFlag() throws Exception {
    Object actual = TestSetup.enabledCase("feature-flag.simple", TestSetup.map());
    assertEquals(true, actual);
  }

  @Test
  @DisplayName("always returns false for a non-boolean flag")
  void alwaysReturnsFalseForANonBooleanFlag() throws Exception {
    Object actual = TestSetup.enabledCase("feature-flag.integer", TestSetup.map());
    assertEquals(false, actual);
  }

  @Test
  @DisplayName("returns true for a PROP_IS_ONE_OF rule when any prop matches")
  void returnsTrueForAPropIsOneOfRuleWhenAnyPropMatches() throws Exception {
    Object actual =
        TestSetup.enabledCase(
            "feature-flag.properties.positive",
            TestSetup.map("", TestSetup.map("name", "michael", "domain", "something.com")));
    assertEquals(true, actual);
  }

  @Test
  @DisplayName("returns false for a PROP_IS_ONE_OF rule when no prop matches")
  void returnsFalseForAPropIsOneOfRuleWhenNoPropMatches() throws Exception {
    Object actual =
        TestSetup.enabledCase(
            "feature-flag.properties.positive",
            TestSetup.map("", TestSetup.map("name", "lauren", "domain", "something.com")));
    assertEquals(false, actual);
  }

  @Test
  @DisplayName("returns true for a PROP_IS_NOT_ONE_OF rule when any prop doesn't match")
  void returnsTrueForAPropIsNotOneOfRuleWhenAnyPropDoesnTMatch() throws Exception {
    Object actual =
        TestSetup.enabledCase(
            "feature-flag.properties.negative",
            TestSetup.map("", TestSetup.map("name", "lauren", "domain", "prefab.cloud")));
    assertEquals(true, actual);
  }

  @Test
  @DisplayName("returns false for a PROP_IS_NOT_ONE_OF rule when all props match")
  void returnsFalseForAPropIsNotOneOfRuleWhenAllPropsMatch() throws Exception {
    Object actual =
        TestSetup.enabledCase(
            "feature-flag.properties.negative",
            TestSetup.map("", TestSetup.map("name", "michael", "domain", "prefab.cloud")));
    assertEquals(false, actual);
  }

  @Test
  @DisplayName(
      "returns true for PROP_ENDS_WITH_ONE_OF rule when the given prop has a matching suffix")
  void returnsTrueForPropEndsWithOneOfRuleWhenTheGivenPropHasAMatchingSuffix() throws Exception {
    Object actual =
        TestSetup.enabledCase(
            "feature-flag.ends-with-one-of.positive",
            TestSetup.map("", TestSetup.map("email", "jeff@prefab.cloud")));
    assertEquals(true, actual);
  }

  @Test
  @DisplayName(
      "returns false for PROP_ENDS_WITH_ONE_OF rule when the given prop doesn't have a matching suffix")
  void returnsFalseForPropEndsWithOneOfRuleWhenTheGivenPropDoesnTHaveAMatchingSuffix()
      throws Exception {
    Object actual =
        TestSetup.enabledCase(
            "feature-flag.ends-with-one-of.positive",
            TestSetup.map("", TestSetup.map("email", "jeff@test.com")));
    assertEquals(false, actual);
  }

  @Test
  @DisplayName(
      "returns true for PROP_DOES_NOT_END_WITH_ONE_OF rule when the given prop doesn't have a matching suffix")
  void returnsTrueForPropDoesNotEndWithOneOfRuleWhenTheGivenPropDoesnTHaveAMatchingSuffix()
      throws Exception {
    Object actual =
        TestSetup.enabledCase(
            "feature-flag.ends-with-one-of.negative",
            TestSetup.map("", TestSetup.map("email", "michael@test.com")));
    assertEquals(true, actual);
  }

  @Test
  @DisplayName(
      "returns false for PROP_DOES_NOT_END_WITH_ONE_OF rule when the given prop has a matching suffix")
  void returnsFalseForPropDoesNotEndWithOneOfRuleWhenTheGivenPropHasAMatchingSuffix()
      throws Exception {
    Object actual =
        TestSetup.enabledCase(
            "feature-flag.ends-with-one-of.negative",
            TestSetup.map("", TestSetup.map("email", "michael@prefab.cloud")));
    assertEquals(false, actual);
  }

  @Test
  @DisplayName(
      "returns true for PROP_STARTS_WITH_ONE_OF rule when the given prop has a matching prefix")
  void returnsTrueForPropStartsWithOneOfRuleWhenTheGivenPropHasAMatchingPrefix() throws Exception {
    Object actual =
        TestSetup.enabledCase(
            "feature-flag.starts-with-one-of.positive",
            TestSetup.map("user", TestSetup.map("email", "foo@prefab.cloud")));
    assertEquals(true, actual);
  }

  @Test
  @DisplayName(
      "returns false for PROP_STARTS_WITH_ONE_OF rule when the given prop doesn't have a matching prefix")
  void returnsFalseForPropStartsWithOneOfRuleWhenTheGivenPropDoesnTHaveAMatchingPrefix()
      throws Exception {
    Object actual =
        TestSetup.enabledCase(
            "feature-flag.starts-with-one-of.positive",
            TestSetup.map("user", TestSetup.map("email", "notfoo@prefab.cloud")));
    assertEquals(false, actual);
  }

  @Test
  @DisplayName(
      "returns true for PROP_DOES_NOT_START_WITH_ONE_OF rule when the given prop doesn't have a matching prefix")
  void returnsTrueForPropDoesNotStartWithOneOfRuleWhenTheGivenPropDoesnTHaveAMatchingPrefix()
      throws Exception {
    Object actual =
        TestSetup.enabledCase(
            "feature-flag.starts-with-one-of.negative",
            TestSetup.map("user", TestSetup.map("email", "notfoo@prefab.cloud")));
    assertEquals(true, actual);
  }

  @Test
  @DisplayName(
      "returns false for PROP_DOES_NOT_START_WITH_ONE_OF rule when the given prop has a matching prefix")
  void returnsFalseForPropDoesNotStartWithOneOfRuleWhenTheGivenPropHasAMatchingPrefix()
      throws Exception {
    Object actual =
        TestSetup.enabledCase(
            "feature-flag.starts-with-one-of.negative",
            TestSetup.map("user", TestSetup.map("email", "foo@prefab.cloud")));
    assertEquals(false, actual);
  }

  @Test
  @DisplayName(
      "returns true for PROP_CONTAINS_ONE_OF rule when the given prop has a matching substring")
  void returnsTrueForPropContainsOneOfRuleWhenTheGivenPropHasAMatchingSubstring() throws Exception {
    Object actual =
        TestSetup.enabledCase(
            "feature-flag.contains-one-of.positive",
            TestSetup.map("user", TestSetup.map("email", "somefoo@prefab.cloud")));
    assertEquals(true, actual);
  }

  @Test
  @DisplayName(
      "returns false for PROP_CONTAINS_ONE_OF rule when the given prop doesn't have a matching substring")
  void returnsFalseForPropContainsOneOfRuleWhenTheGivenPropDoesnTHaveAMatchingSubstring()
      throws Exception {
    Object actual =
        TestSetup.enabledCase(
            "feature-flag.contains-one-of.positive",
            TestSetup.map("user", TestSetup.map("email", "info@prefab.cloud")));
    assertEquals(false, actual);
  }

  @Test
  @DisplayName(
      "returns true for PROP_DOES_NOT_CONTAIN_ONE_OF rule when the given prop doesn't have a matching substring")
  void returnsTrueForPropDoesNotContainOneOfRuleWhenTheGivenPropDoesnTHaveAMatchingSubstring()
      throws Exception {
    Object actual =
        TestSetup.enabledCase(
            "feature-flag.contains-one-of.negative",
            TestSetup.map("user", TestSetup.map("email", "info@prefab.cloud")));
    assertEquals(true, actual);
  }

  @Test
  @DisplayName(
      "returns false for PROP_DOES_NOT_CONTAIN_ONE_OF rule when the given prop has a matching substring")
  void returnsFalseForPropDoesNotContainOneOfRuleWhenTheGivenPropHasAMatchingSubstring()
      throws Exception {
    Object actual =
        TestSetup.enabledCase(
            "feature-flag.contains-one-of.negative",
            TestSetup.map("user", TestSetup.map("email", "notfoo@prefab.cloud")));
    assertEquals(false, actual);
  }

  @Test
  @DisplayName("returns true for IN_SEG when the segment rule matches")
  void returnsTrueForInSegWhenTheSegmentRuleMatches() throws Exception {
    Object actual =
        TestSetup.enabledCase(
            "feature-flag.in-segment.positive",
            TestSetup.map("user", TestSetup.map("key", "lauren")));
    assertEquals(true, actual);
  }

  @Test
  @DisplayName("returns false for IN_SEG when the segment rule doesn't match")
  void returnsFalseForInSegWhenTheSegmentRuleDoesnTMatch() throws Exception {
    Object actual =
        TestSetup.enabledCase(
            "feature-flag.in-segment.positive",
            TestSetup.map("user", TestSetup.map("key", "josh")));
    assertEquals(false, actual);
  }

  @Test
  @DisplayName("returns false for IN_SEG if any segment rule fails to match")
  void returnsFalseForInSegIfAnySegmentRuleFailsToMatch() throws Exception {
    Object actual =
        TestSetup.enabledCase(
            "feature-flag.in-seg.segment-and",
            TestSetup.map(
                "user", TestSetup.map("key", "josh"), "", TestSetup.map("domain", "prefab.cloud")));
    assertEquals(false, actual);
  }

  @Test
  @DisplayName("returns true for IN_SEG (segment-and) if all rules matches")
  void returnsTrueForInSegSegmentAndIfAllRulesMatches() throws Exception {
    Object actual =
        TestSetup.enabledCase(
            "feature-flag.in-seg.segment-and",
            TestSetup.map(
                "user",
                TestSetup.map("key", "michael"),
                "",
                TestSetup.map("domain", "prefab.cloud")));
    assertEquals(true, actual);
  }

  @Test
  @DisplayName("returns true for IN_SEG (segment-or) if any segment rule matches (lookup)")
  void returnsTrueForInSegSegmentOrIfAnySegmentRuleMatchesLookup() throws Exception {
    Object actual =
        TestSetup.enabledCase(
            "feature-flag.in-seg.segment-or",
            TestSetup.map(
                "user",
                TestSetup.map("key", "michael"),
                "",
                TestSetup.map("domain", "example.com")));
    assertEquals(true, actual);
  }

  @Test
  @DisplayName("returns true for IN_SEG (segment-or) if any segment rule matches (prop)")
  void returnsTrueForInSegSegmentOrIfAnySegmentRuleMatchesProp() throws Exception {
    Object actual =
        TestSetup.enabledCase(
            "feature-flag.in-seg.segment-or",
            TestSetup.map(
                "user", TestSetup.map("key", "nobody"), "", TestSetup.map("domain", "gmail.com")));
    assertEquals(true, actual);
  }

  @Test
  @DisplayName("returns true for NOT_IN_SEG when the segment rule doesn't match")
  void returnsTrueForNotInSegWhenTheSegmentRuleDoesnTMatch() throws Exception {
    Object actual =
        TestSetup.enabledCase(
            "feature-flag.in-segment.negative",
            TestSetup.map("user", TestSetup.map("key", "josh")));
    assertEquals(true, actual);
  }

  @Test
  @DisplayName("returns false for NOT_IN_SEG when the segment rule matches")
  void returnsFalseForNotInSegWhenTheSegmentRuleMatches() throws Exception {
    Object actual =
        TestSetup.enabledCase(
            "feature-flag.in-segment.negative",
            TestSetup.map("user", TestSetup.map("key", "michael")));
    assertEquals(false, actual);
  }

  @Test
  @DisplayName("returns false for NOT_IN_SEG if any segment rule matches")
  void returnsFalseForNotInSegIfAnySegmentRuleMatches() throws Exception {
    Object actual =
        TestSetup.enabledCase(
            "feature-flag.in-segment.multiple-criteria.negative",
            TestSetup.map(
                "user", TestSetup.map("key", "josh"), "", TestSetup.map("domain", "prefab.cloud")));
    assertEquals(true, actual);
  }

  @Test
  @DisplayName("returns true for NOT_IN_SEG if no segment rule matches")
  void returnsTrueForNotInSegIfNoSegmentRuleMatches() throws Exception {
    Object actual =
        TestSetup.enabledCase(
            "feature-flag.in-segment.multiple-criteria.negative",
            TestSetup.map(
                "user",
                TestSetup.map("key", "josh"),
                "",
                TestSetup.map("domain", "something.com")));
    assertEquals(true, actual);
  }

  @Test
  @DisplayName("returns true for NOT_IN_SEG (segment-and) if not segment rule fails to match")
  void returnsTrueForNotInSegSegmentAndIfNotSegmentRuleFailsToMatch() throws Exception {
    Object actual =
        TestSetup.enabledCase(
            "feature-flag.not-in-seg.segment-and",
            TestSetup.map(
                "user", TestSetup.map("key", "josh"), "", TestSetup.map("domain", "prefab.cloud")));
    assertEquals(true, actual);
  }

  @Test
  @DisplayName("returns true for IN_SEG (segment-and) if not segment rule fails to match")
  void returnsTrueForInSegSegmentAndIfNotSegmentRuleFailsToMatch() throws Exception {
    Object actual =
        TestSetup.enabledCase(
            "feature-flag.in-seg.segment-and",
            TestSetup.map(
                "user", TestSetup.map("key", "josh"), "", TestSetup.map("domain", "prefab.cloud")));
    assertEquals(false, actual);
  }

  @Test
  @DisplayName("returns false for NOT_IN_SEG (segment-and) if segment rules matches")
  void returnsFalseForNotInSegSegmentAndIfSegmentRulesMatches() throws Exception {
    Object actual =
        TestSetup.enabledCase(
            "feature-flag.not-in-seg.segment-and",
            TestSetup.map(
                "user",
                TestSetup.map("key", "michael"),
                "",
                TestSetup.map("domain", "prefab.cloud")));
    assertEquals(false, actual);
  }

  @Test
  @DisplayName("returns true for NOT_IN_SEG (segment-or) if no segment rule matches")
  void returnsTrueForNotInSegSegmentOrIfNoSegmentRuleMatches() throws Exception {
    Object actual =
        TestSetup.enabledCase(
            "feature-flag.not-in-seg.segment-or",
            TestSetup.map(
                "user",
                TestSetup.map("key", "nobody"),
                "",
                TestSetup.map("domain", "example.com")));
    assertEquals(true, actual);
  }

  @Test
  @DisplayName("returns false for NOT_IN_SEG (segment-or) if one segment rule matches (prop)")
  void returnsFalseForNotInSegSegmentOrIfOneSegmentRuleMatchesProp() throws Exception {
    Object actual =
        TestSetup.enabledCase(
            "feature-flag.not-in-seg.segment-or",
            TestSetup.map(
                "user", TestSetup.map("key", "nobody"), "", TestSetup.map("domain", "gmail.com")));
    assertEquals(false, actual);
  }

  @Test
  @DisplayName("returns false for NOT_IN_SEG (segment-or) if one segment rule matches (lookup)")
  void returnsFalseForNotInSegSegmentOrIfOneSegmentRuleMatchesLookup() throws Exception {
    Object actual =
        TestSetup.enabledCase(
            "feature-flag.not-in-seg.segment-or",
            TestSetup.map(
                "user",
                TestSetup.map("key", "michael"),
                "",
                TestSetup.map("domain", "example.com")));
    assertEquals(false, actual);
  }

  @Test
  @DisplayName(
      "returns true for PROP_BEFORE rule when the given prop represents a date (string) before the rule's time")
  void returnsTrueForPropBeforeRuleWhenTheGivenPropRepresentsADateStringBeforeTheRuleSTime()
      throws Exception {
    Object actual =
        TestSetup.enabledCase(
            "feature-flag.before",
            TestSetup.map("user", TestSetup.map("creation_date", "2024-11-01T00:00:00Z")));
    assertEquals(true, actual);
  }

  @Test
  @DisplayName(
      "returns true for PROP_BEFORE rule when the given prop represents a date (number) before the rule's time")
  void returnsTrueForPropBeforeRuleWhenTheGivenPropRepresentsADateNumberBeforeTheRuleSTime()
      throws Exception {
    Object actual =
        TestSetup.enabledCase(
            "feature-flag.before",
            TestSetup.map("user", TestSetup.map("creation_date", 1730419200000L)));
    assertEquals(true, actual);
  }

  @Test
  @DisplayName(
      "returns false for PROP_BEFORE rule when the given prop represents a date (number) exactly matching rule's time")
  void returnsFalseForPropBeforeRuleWhenTheGivenPropRepresentsADateNumberExactlyMatchingRuleSTime()
      throws Exception {
    Object actual =
        TestSetup.enabledCase(
            "feature-flag.before",
            TestSetup.map("user", TestSetup.map("creation_date", 1733011200000L)));
    assertEquals(false, actual);
  }

  @Test
  @DisplayName(
      "returns false for PROP_BEFORE rule when the given prop represents a date (number) AFTER the rule's time")
  void returnsFalseForPropBeforeRuleWhenTheGivenPropRepresentsADateNumberAfterTheRuleSTime()
      throws Exception {
    Object actual =
        TestSetup.enabledCase(
            "feature-flag.before",
            TestSetup.map("user", TestSetup.map("creation_date", "2025-01-01T00:00:00Z")));
    assertEquals(false, actual);
  }

  @Test
  @DisplayName("returns false for PROP_BEFORE rule when the given prop won't parse as a date")
  void returnsFalseForPropBeforeRuleWhenTheGivenPropWonTParseAsADate() throws Exception {
    Object actual =
        TestSetup.enabledCase(
            "feature-flag.before",
            TestSetup.map("user", TestSetup.map("creation_date", "not a date")));
    assertEquals(false, actual);
  }

  @Test
  @DisplayName("returns false for PROP_BEFORE rule using current-time relative to 2050-01-01")
  void returnsFalseForPropBeforeRuleUsingCurrentTimeRelativeTo20500101() throws Exception {
    Object actual = TestSetup.enabledCase("feature-flag.before.current-time", TestSetup.map());
    assertEquals(true, actual);
  }

  @Test
  @DisplayName(
      "returns true for PROP_AFTER rule when the given prop represents a date (string) after the rule's time")
  void returnsTrueForPropAfterRuleWhenTheGivenPropRepresentsADateStringAfterTheRuleSTime()
      throws Exception {
    Object actual =
        TestSetup.enabledCase(
            "feature-flag.after",
            TestSetup.map("user", TestSetup.map("creation_date", "2025-01-01T00:00:00Z")));
    assertEquals(true, actual);
  }

  @Test
  @DisplayName(
      "returns true for PROP_AFTER rule when the given prop represents a date (number) after the rule's time")
  void returnsTrueForPropAfterRuleWhenTheGivenPropRepresentsADateNumberAfterTheRuleSTime()
      throws Exception {
    Object actual =
        TestSetup.enabledCase(
            "feature-flag.after",
            TestSetup.map("user", TestSetup.map("creation_date", 1735689600000L)));
    assertEquals(true, actual);
  }

  @Test
  @DisplayName(
      "returns false for PROP_AFTER rule when the given prop represents a date (number) exactly matching rule's time")
  void returnsFalseForPropAfterRuleWhenTheGivenPropRepresentsADateNumberExactlyMatchingRuleSTime()
      throws Exception {
    Object actual =
        TestSetup.enabledCase(
            "feature-flag.after",
            TestSetup.map("user", TestSetup.map("creation_date", 1733011200000L)));
    assertEquals(false, actual);
  }

  @Test
  @DisplayName(
      "returns false for PROP_BEFORE rule when the given prop represents a date (number) BEFORE the rule's time")
  void returnsFalseForPropBeforeRuleWhenTheGivenPropRepresentsADateNumberBeforeTheRuleSTime()
      throws Exception {
    Object actual =
        TestSetup.enabledCase(
            "feature-flag.after",
            TestSetup.map("user", TestSetup.map("creation_date", "2024-01-01T00:00:00Z")));
    assertEquals(false, actual);
  }

  @Test
  @DisplayName("returns false for PROP_AFTER rule when the given prop won't parse as a date")
  void returnsFalseForPropAfterRuleWhenTheGivenPropWonTParseAsADate() throws Exception {
    Object actual =
        TestSetup.enabledCase(
            "feature-flag.after",
            TestSetup.map("user", TestSetup.map("creation_date", "not a date")));
    assertEquals(false, actual);
  }

  @Test
  @DisplayName("returns false for PROP_AFTER rule using current-time relative to 2025-01-01")
  void returnsFalseForPropAfterRuleUsingCurrentTimeRelativeTo20250101() throws Exception {
    Object actual = TestSetup.enabledCase("feature-flag.after.current-time", TestSetup.map());
    assertEquals(true, actual);
  }

  @Test
  @DisplayName(
      "returns true for PROP_LESS_THAN rule when the given prop is less than the rule's value")
  void returnsTrueForPropLessThanRuleWhenTheGivenPropIsLessThanTheRuleSValue() throws Exception {
    Object actual =
        TestSetup.enabledCase(
            "feature-flag.less-than", TestSetup.map("user", TestSetup.map("age", 20L)));
    assertEquals(true, actual);
  }

  @Test
  @DisplayName(
      "returns true for PROP_LESS_THAN rule when the given prop is less than the rule's value (float)")
  void returnsTrueForPropLessThanRuleWhenTheGivenPropIsLessThanTheRuleSValueFloat()
      throws Exception {
    Object actual =
        TestSetup.enabledCase(
            "feature-flag.less-than", TestSetup.map("user", TestSetup.map("age", 20.5d)));
    assertEquals(true, actual);
  }

  @Test
  @DisplayName("returns false for PROP_LESS_THAN rule when the given prop is equal to rule's value")
  void returnsFalseForPropLessThanRuleWhenTheGivenPropIsEqualToRuleSValue() throws Exception {
    Object actual =
        TestSetup.enabledCase(
            "feature-flag.less-than", TestSetup.map("user", TestSetup.map("age", 30L)));
    assertEquals(false, actual);
  }

  @Test
  @DisplayName("returns false for PROP_LESS_THAN rule when the given prop a string")
  void returnsFalseForPropLessThanRuleWhenTheGivenPropAString() throws Exception {
    Object actual =
        TestSetup.enabledCase(
            "feature-flag.less-than", TestSetup.map("user", TestSetup.map("age", "20")));
    assertEquals(false, actual);
  }

  @Test
  @DisplayName(
      "returns true for PROP_LESS_THAN_OR_EQUAL rule when the given prop is less than the rule's value")
  void returnsTrueForPropLessThanOrEqualRuleWhenTheGivenPropIsLessThanTheRuleSValue()
      throws Exception {
    Object actual =
        TestSetup.enabledCase(
            "feature-flag.less-than-or-equal", TestSetup.map("user", TestSetup.map("age", 20L)));
    assertEquals(true, actual);
  }

  @Test
  @DisplayName(
      "returns true for PROP_LESS_THAN_OR_EQUAL rule when the given prop is less than the rule's value (float)")
  void returnsTrueForPropLessThanOrEqualRuleWhenTheGivenPropIsLessThanTheRuleSValueFloat()
      throws Exception {
    Object actual =
        TestSetup.enabledCase(
            "feature-flag.less-than-or-equal", TestSetup.map("user", TestSetup.map("age", 20.5d)));
    assertEquals(true, actual);
  }

  @Test
  @DisplayName(
      "returns false for PROP_LESS_THAN_OR_EQUAL rule when the given prop is equal to rule's value")
  void returnsFalseForPropLessThanOrEqualRuleWhenTheGivenPropIsEqualToRuleSValue()
      throws Exception {
    Object actual =
        TestSetup.enabledCase(
            "feature-flag.less-than-or-equal", TestSetup.map("user", TestSetup.map("age", 30L)));
    assertEquals(true, actual);
  }

  @Test
  @DisplayName("returns false for PROP_LESS_THAN_OR_EQUAL rule when the given prop a string")
  void returnsFalseForPropLessThanOrEqualRuleWhenTheGivenPropAString() throws Exception {
    Object actual =
        TestSetup.enabledCase(
            "feature-flag.less-than-or-equal", TestSetup.map("user", TestSetup.map("age", "20")));
    assertEquals(false, actual);
  }

  @Test
  @DisplayName(
      "returns true for PROP_GREATER_THAN rule when the given prop is greater than the rule's value")
  void returnsTrueForPropGreaterThanRuleWhenTheGivenPropIsGreaterThanTheRuleSValue()
      throws Exception {
    Object actual =
        TestSetup.enabledCase(
            "feature-flag.greater-than", TestSetup.map("user", TestSetup.map("age", 100L)));
    assertEquals(true, actual);
  }

  @Test
  @DisplayName(
      "returns true for PROP_GREATER_THAN rule when the given prop is greater than the rule's value (float)")
  void returnsTrueForPropGreaterThanRuleWhenTheGivenPropIsGreaterThanTheRuleSValueFloat()
      throws Exception {
    Object actual =
        TestSetup.enabledCase(
            "feature-flag.greater-than", TestSetup.map("user", TestSetup.map("age", 30.5d)));
    assertEquals(true, actual);
  }

  @Test
  @DisplayName(
      "returns true for PROP_GREATER_THAN rule when the given prop is greater than the rule's float value (float)")
  void returnsTrueForPropGreaterThanRuleWhenTheGivenPropIsGreaterThanTheRuleSFloatValueFloat()
      throws Exception {
    Object actual =
        TestSetup.enabledCase(
            "feature-flag.greater-than.double", TestSetup.map("user", TestSetup.map("age", 32.7d)));
    assertEquals(true, actual);
  }

  @Test
  @DisplayName(
      "returns true for PROP_GREATER_THAN rule when the given prop is greater than the rule's float value (integer)")
  void returnsTrueForPropGreaterThanRuleWhenTheGivenPropIsGreaterThanTheRuleSFloatValueInteger()
      throws Exception {
    Object actual =
        TestSetup.enabledCase(
            "feature-flag.greater-than.double", TestSetup.map("user", TestSetup.map("age", 32L)));
    assertEquals(true, actual);
  }

  @Test
  @DisplayName(
      "returns false for PROP_GREATER_THAN rule when the given prop is equal to rule's value")
  void returnsFalseForPropGreaterThanRuleWhenTheGivenPropIsEqualToRuleSValue() throws Exception {
    Object actual =
        TestSetup.enabledCase(
            "feature-flag.greater-than", TestSetup.map("user", TestSetup.map("age", 30L)));
    assertEquals(false, actual);
  }

  @Test
  @DisplayName("returns false for PROP_GREATER_THAN rule when the given prop a string")
  void returnsFalseForPropGreaterThanRuleWhenTheGivenPropAString() throws Exception {
    Object actual =
        TestSetup.enabledCase(
            "feature-flag.greater-than", TestSetup.map("user", TestSetup.map("age", "100")));
    assertEquals(false, actual);
  }

  @Test
  @DisplayName(
      "returns true for PROP_GREATER_THAN_OR_EQUAL rule when the given prop is greater than the rule's value")
  void returnsTrueForPropGreaterThanOrEqualRuleWhenTheGivenPropIsGreaterThanTheRuleSValue()
      throws Exception {
    Object actual =
        TestSetup.enabledCase(
            "feature-flag.greater-than-or-equal", TestSetup.map("user", TestSetup.map("age", 30L)));
    assertEquals(true, actual);
  }

  @Test
  @DisplayName(
      "returns true for PROP_GREATER_THAN_OR_EQUAL rule when the given prop is greater than the rule's value (float)")
  void returnsTrueForPropGreaterThanOrEqualRuleWhenTheGivenPropIsGreaterThanTheRuleSValueFloat()
      throws Exception {
    Object actual =
        TestSetup.enabledCase(
            "feature-flag.greater-than-or-equal",
            TestSetup.map("user", TestSetup.map("age", 30.5d)));
    assertEquals(true, actual);
  }

  @Test
  @DisplayName(
      "returns true for PROP_GREATER_THAN_OR_EQUAL rule when the given prop is equal to rule's value")
  void returnsTrueForPropGreaterThanOrEqualRuleWhenTheGivenPropIsEqualToRuleSValue()
      throws Exception {
    Object actual =
        TestSetup.enabledCase(
            "feature-flag.greater-than-or-equal", TestSetup.map("user", TestSetup.map("age", 30L)));
    assertEquals(true, actual);
  }

  @Test
  @DisplayName("returns false for PROP_GREATER_THAN_OR_EQUAL rule when the given prop a string")
  void returnsFalseForPropGreaterThanOrEqualRuleWhenTheGivenPropAString() throws Exception {
    Object actual =
        TestSetup.enabledCase(
            "feature-flag.greater-than-or-equal",
            TestSetup.map("user", TestSetup.map("age", "100")));
    assertEquals(false, actual);
  }

  @Test
  @DisplayName("returns true for PROP_MATCHES rule when the given prop matches the regex")
  void returnsTrueForPropMatchesRuleWhenTheGivenPropMatchesTheRegex() throws Exception {
    Object actual =
        TestSetup.enabledCase(
            "feature-flag.matches", TestSetup.map("user", TestSetup.map("code", "aaaaaab")));
    assertEquals(true, actual);
  }

  @Test
  @DisplayName("returns false for PROP_MATCHES rule when the given prop does not match the regex")
  void returnsFalseForPropMatchesRuleWhenTheGivenPropDoesNotMatchTheRegex() throws Exception {
    Object actual =
        TestSetup.enabledCase(
            "feature-flag.matches", TestSetup.map("user", TestSetup.map("code", "aa")));
    assertEquals(false, actual);
  }

  @Test
  @DisplayName(
      "returns true for PROP_DOES_NOT_MATCH rule when the given prop does not match the regex")
  void returnsTrueForPropDoesNotMatchRuleWhenTheGivenPropDoesNotMatchTheRegex() throws Exception {
    Object actual =
        TestSetup.enabledCase(
            "feature-flag.does-not-match", TestSetup.map("user", TestSetup.map("code", "b")));
    assertEquals(true, actual);
  }

  @Test
  @DisplayName("returns false for PROP_DOES_NOT_MATCH rule when the given prop matches the regex")
  void returnsFalseForPropDoesNotMatchRuleWhenTheGivenPropMatchesTheRegex() throws Exception {
    Object actual =
        TestSetup.enabledCase(
            "feature-flag.does-not-match", TestSetup.map("user", TestSetup.map("code", "aabb")));
    assertEquals(false, actual);
  }

  @Test
  @DisplayName("returns true for IS_PRESENT rule when the given prop is a non-empty string")
  void returnsTrueForIsPresentRuleWhenTheGivenPropIsANonEmptyString() throws Exception {
    Object actual =
        TestSetup.enabledCase(
            "feature-flag.is-present", TestSetup.map("user", TestSetup.map("id", "abc")));
    assertEquals(true, actual);
  }

  @Test
  @DisplayName("returns true for IS_PRESENT rule when the given prop is an empty string")
  void returnsTrueForIsPresentRuleWhenTheGivenPropIsAnEmptyString() throws Exception {
    Object actual =
        TestSetup.enabledCase(
            "feature-flag.is-present", TestSetup.map("user", TestSetup.map("id", "")));
    assertEquals(true, actual);
  }

  @Test
  @DisplayName("returns true for IS_PRESENT rule when the given prop is the integer zero")
  void returnsTrueForIsPresentRuleWhenTheGivenPropIsTheIntegerZero() throws Exception {
    Object actual =
        TestSetup.enabledCase(
            "feature-flag.is-present", TestSetup.map("user", TestSetup.map("id", 0L)));
    assertEquals(true, actual);
  }

  @Test
  @DisplayName("returns true for IS_PRESENT rule when the given prop is boolean false")
  void returnsTrueForIsPresentRuleWhenTheGivenPropIsBooleanFalse() throws Exception {
    Object actual =
        TestSetup.enabledCase(
            "feature-flag.is-present", TestSetup.map("user", TestSetup.map("id", false)));
    assertEquals(true, actual);
  }

  @Test
  @DisplayName("returns false for IS_PRESENT rule when the given prop is null")
  void returnsFalseForIsPresentRuleWhenTheGivenPropIsNull() throws Exception {
    Object actual =
        TestSetup.enabledCase(
            "feature-flag.is-present", TestSetup.map("user", TestSetup.map("id", null)));
    assertEquals(false, actual);
  }

  @Test
  @DisplayName(
      "returns false for IS_PRESENT rule when the given prop key is missing from the context")
  void returnsFalseForIsPresentRuleWhenTheGivenPropKeyIsMissingFromTheContext() throws Exception {
    Object actual =
        TestSetup.enabledCase(
            "feature-flag.is-present", TestSetup.map("user", TestSetup.map("name", "bob")));
    assertEquals(false, actual);
  }

  @Test
  @DisplayName("returns false for IS_PRESENT rule when no contexts are provided at all")
  void returnsFalseForIsPresentRuleWhenNoContextsAreProvidedAtAll() throws Exception {
    Object actual = TestSetup.enabledCase("feature-flag.is-present", TestSetup.map());
    assertEquals(false, actual);
  }

  @Test
  @DisplayName("returns false for IS_NOT_PRESENT rule when the given prop is a non-empty string")
  void returnsFalseForIsNotPresentRuleWhenTheGivenPropIsANonEmptyString() throws Exception {
    Object actual =
        TestSetup.enabledCase(
            "feature-flag.is-not-present", TestSetup.map("user", TestSetup.map("id", "abc")));
    assertEquals(false, actual);
  }

  @Test
  @DisplayName("returns true for IS_NOT_PRESENT rule when the given prop is null")
  void returnsTrueForIsNotPresentRuleWhenTheGivenPropIsNull() throws Exception {
    Object actual =
        TestSetup.enabledCase(
            "feature-flag.is-not-present", TestSetup.map("user", TestSetup.map("id", null)));
    assertEquals(true, actual);
  }

  @Test
  @DisplayName(
      "returns true for IS_NOT_PRESENT rule when the given prop key is missing from the context")
  void returnsTrueForIsNotPresentRuleWhenTheGivenPropKeyIsMissingFromTheContext() throws Exception {
    Object actual =
        TestSetup.enabledCase(
            "feature-flag.is-not-present", TestSetup.map("user", TestSetup.map("name", "bob")));
    assertEquals(true, actual);
  }

  @Test
  @DisplayName("returns true for IS_PRESENT rule on a nested path when the nested prop is set")
  void returnsTrueForIsPresentRuleOnANestedPathWhenTheNestedPropIsSet() throws Exception {
    Object actual =
        TestSetup.enabledCase(
            "feature-flag.is-present-nested",
            TestSetup.map("organization", TestSetup.map("domain", "example.com")));
    assertEquals(true, actual);
  }

  @Test
  @DisplayName(
      "returns false for IS_PRESENT rule on a nested path when the nested key is missing but the parent context exists")
  void returnsFalseForIsPresentRuleOnANestedPathWhenTheNestedKeyIsMissingButTheParentContextExists()
      throws Exception {
    Object actual =
        TestSetup.enabledCase(
            "feature-flag.is-present-nested",
            TestSetup.map("organization", TestSetup.map("name", "Acme Inc")));
    assertEquals(false, actual);
  }

  @Test
  @DisplayName(
      "returns false for IS_PRESENT rule on a nested path when the parent context is entirely absent")
  void returnsFalseForIsPresentRuleOnANestedPathWhenTheParentContextIsEntirelyAbsent()
      throws Exception {
    Object actual =
        TestSetup.enabledCase(
            "feature-flag.is-present-nested", TestSetup.map("user", TestSetup.map("id", "abc")));
    assertEquals(false, actual);
  }

  @Test
  @DisplayName("returns true for PROP_SEMVER_EQUAL rule when the given prop equals the version")
  void returnsTrueForPropSemverEqualRuleWhenTheGivenPropEqualsTheVersion() throws Exception {
    Object actual =
        TestSetup.enabledCase(
            "feature-flag.semver-equal", TestSetup.map("app", TestSetup.map("version", "2.0.0")));
    assertEquals(true, actual);
  }

  @Test
  @DisplayName(
      "returns false for PROP_SEMVER_EQUAL rule when the given prop does not equal the version")
  void returnsFalseForPropSemverEqualRuleWhenTheGivenPropDoesNotEqualTheVersion() throws Exception {
    Object actual =
        TestSetup.enabledCase(
            "feature-flag.semver-equal", TestSetup.map("app", TestSetup.map("version", "2.0.1")));
    assertEquals(false, actual);
  }

  @Test
  @DisplayName("returns false for PROP_SEMVER_EQUAL rule when the given prop is not a valid semver")
  void returnsFalseForPropSemverEqualRuleWhenTheGivenPropIsNotAValidSemver() throws Exception {
    Object actual =
        TestSetup.enabledCase(
            "feature-flag.semver-equal", TestSetup.map("app", TestSetup.map("version", "2.0")));
    assertEquals(false, actual);
  }

  @Test
  @DisplayName("returns true for PROP_SEMVER_LESS_THAN rule when the given prop is less than 2.0.0")
  void returnsTrueForPropSemverLessThanRuleWhenTheGivenPropIsLessThan200() throws Exception {
    Object actual =
        TestSetup.enabledCase(
            "feature-flag.semver-less-than",
            TestSetup.map("app", TestSetup.map("version", "1.5.1")));
    assertEquals(true, actual);
  }

  @Test
  @DisplayName(
      "returns false for PROP_SEMVER_LESS_THAN rule when the given prop equals the version")
  void returnsFalseForPropSemverLessThanRuleWhenTheGivenPropEqualsTheVersion() throws Exception {
    Object actual =
        TestSetup.enabledCase(
            "feature-flag.semver-less-than",
            TestSetup.map("app", TestSetup.map("version", "2.0.0")));
    assertEquals(false, actual);
  }

  @Test
  @DisplayName(
      "returns false for PROP_SEMVER_LESS_THAN rule when the given prop is greater than the version")
  void returnsFalseForPropSemverLessThanRuleWhenTheGivenPropIsGreaterThanTheVersion()
      throws Exception {
    Object actual =
        TestSetup.enabledCase(
            "feature-flag.semver-less-than",
            TestSetup.map("app", TestSetup.map("version", "2.2.1")));
    assertEquals(false, actual);
  }

  @Test
  @DisplayName(
      "returns true for PROP_SEMVER_GREATER_THAN rule when the given prop is greater than 2.0.0")
  void returnsTrueForPropSemverGreaterThanRuleWhenTheGivenPropIsGreaterThan200() throws Exception {
    Object actual =
        TestSetup.enabledCase(
            "feature-flag.semver-greater-than",
            TestSetup.map("app", TestSetup.map("version", "2.5.1")));
    assertEquals(true, actual);
  }

  @Test
  @DisplayName(
      "returns false for PROP_SEMVER_GREATER_THAN rule when the given prop equals the version")
  void returnsFalseForPropSemverGreaterThanRuleWhenTheGivenPropEqualsTheVersion() throws Exception {
    Object actual =
        TestSetup.enabledCase(
            "feature-flag.semver-greater-than",
            TestSetup.map("app", TestSetup.map("version", "2.0.0")));
    assertEquals(false, actual);
  }

  @Test
  @DisplayName(
      "returns false for PROP_SEMVER_EQUAL rule when the given prop is less than the version")
  void returnsFalseForPropSemverEqualRuleWhenTheGivenPropIsLessThanTheVersion() throws Exception {
    Object actual =
        TestSetup.enabledCase(
            "feature-flag.semver-greater-than",
            TestSetup.map("app", TestSetup.map("version", "0.0.5")));
    assertEquals(false, actual);
  }
}
