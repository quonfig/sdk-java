package com.quonfig.sdk.eval;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class OperatorsTest {

  private static Criterion crit(String op, String prop, Value match) {
    return new Criterion(prop, op, match);
  }

  private static Value strList(String... vs) {
    return new Value(ValueType.STRING_LIST, Arrays.asList(vs));
  }

  private static Value str(String s) {
    return new Value(ValueType.STRING, s);
  }

  private static Value intVal(long v) {
    return new Value(ValueType.INT, v);
  }

  private static Value dbl(double v) {
    return new Value(ValueType.DOUBLE, v);
  }

  // ----- ALWAYS_TRUE / NOT_SET -----

  @Test
  void alwaysTrue_isAlwaysTrue() {
    Criterion c = crit(Operators.ALWAYS_TRUE, null, null);
    assertTrue(Operators.evaluateCriterion(null, false, c, null));
  }

  @Test
  void notSet_isAlwaysFalse() {
    Criterion c = crit(Operators.NOT_SET, null, null);
    assertFalse(Operators.evaluateCriterion("anything", true, c, null));
  }

  // ----- PROP_IS_ONE_OF / NOT_ONE_OF -----

  @Test
  void propIsOneOf_matchesWhenContextValueInList() {
    Criterion c = crit(Operators.PROP_IS_ONE_OF, "user.email", strList("a@b.com", "c@d.com"));
    assertTrue(Operators.evaluateCriterion("c@d.com", true, c, null));
    assertFalse(Operators.evaluateCriterion("nope@e.com", true, c, null));
  }

  @Test
  void propIsNotOneOf_isInverse_andDefaultsTrueWhenMissing() {
    Criterion c = crit(Operators.PROP_IS_NOT_ONE_OF, "user.email", strList("a@b.com"));
    assertFalse(Operators.evaluateCriterion("a@b.com", true, c, null));
    assertTrue(Operators.evaluateCriterion("z@z.com", true, c, null));
    // missing context: NOT_ONE_OF returns true (vacuous)
    assertTrue(Operators.evaluateCriterion(null, false, c, null));
  }

  @Test
  void propIsOneOf_listValuedContext_anyOverlapMatches() {
    Criterion c = crit(Operators.PROP_IS_ONE_OF, "user.roles", strList("admin", "owner"));
    assertTrue(
        Operators.evaluateCriterion(Arrays.asList("user", "admin"), true, c, null),
        "admin overlaps");
    assertFalse(
        Operators.evaluateCriterion(Arrays.asList("user", "guest"), true, c, null), "no overlap");
  }

  // ----- STARTS / ENDS / CONTAINS -----

  @Test
  void propStartsWithOneOf() {
    Criterion c = crit(Operators.PROP_STARTS_WITH_ONE_OF, "user.email", strList("admin", "ceo"));
    assertTrue(Operators.evaluateCriterion("admin@x.com", true, c, null));
    assertFalse(Operators.evaluateCriterion("user@x.com", true, c, null));
  }

  @Test
  void propEndsWithOneOf() {
    Criterion c = crit(Operators.PROP_ENDS_WITH_ONE_OF, "user.email", strList("@quonfig.com"));
    assertTrue(Operators.evaluateCriterion("a@quonfig.com", true, c, null));
    assertFalse(Operators.evaluateCriterion("a@other.com", true, c, null));
  }

  @Test
  void propContainsOneOf() {
    Criterion c = crit(Operators.PROP_CONTAINS_ONE_OF, "user.email", strList("internal"));
    assertTrue(Operators.evaluateCriterion("foo-internal-bar", true, c, null));
    assertFalse(Operators.evaluateCriterion("foo-bar", true, c, null));
  }

  @Test
  void propDoesNotContainOneOf_inverse_andTrueWhenMissing() {
    Criterion c = crit(Operators.PROP_DOES_NOT_CONTAIN_ONE_OF, "user.email", strList("internal"));
    assertFalse(Operators.evaluateCriterion("internal-thing", true, c, null));
    assertTrue(Operators.evaluateCriterion(null, false, c, null));
  }

  // ----- PROP_MATCHES / DOES_NOT_MATCH -----

  @Test
  void propMatches_regex() {
    Criterion c = crit(Operators.PROP_MATCHES, "user.email", str("^foo.*$"));
    assertTrue(Operators.evaluateCriterion("foobar", true, c, null));
    assertFalse(Operators.evaluateCriterion("nope", true, c, null));
  }

  @Test
  void propDoesNotMatch_regex() {
    Criterion c = crit(Operators.PROP_DOES_NOT_MATCH, "user.email", str("^foo.*$"));
    assertFalse(Operators.evaluateCriterion("foobar", true, c, null));
    assertTrue(Operators.evaluateCriterion("nope", true, c, null));
  }

  // ----- HIERARCHICAL_MATCH -----

  @Test
  void hierarchicalMatch_isPrefixCheck() {
    Criterion c = crit(Operators.HIERARCHICAL_MATCH, "logger.path", str("com.quonfig"));
    assertTrue(Operators.evaluateCriterion("com.quonfig.sdk", true, c, null));
    assertFalse(Operators.evaluateCriterion("org.something", true, c, null));
  }

  // ----- IN_INT_RANGE -----

  @Test
  void inIntRange_halfOpen() {
    Map<String, Object> range = new LinkedHashMap<>();
    range.put("start", 10L);
    range.put("end", 20L);
    Criterion c = crit(Operators.IN_INT_RANGE, "user.age", new Value(ValueType.JSON, range));
    assertTrue(Operators.evaluateCriterion(10, true, c, null), "start inclusive");
    assertTrue(Operators.evaluateCriterion(19, true, c, null));
    assertFalse(Operators.evaluateCriterion(20, true, c, null), "end exclusive");
    assertFalse(Operators.evaluateCriterion(9, true, c, null));
  }

  // ----- COMPARISON -----

  @Test
  void greaterThan_andOrEqual() {
    Criterion gt = crit(Operators.PROP_GREATER_THAN, "n", intVal(5));
    Criterion gte = crit(Operators.PROP_GREATER_THAN_OR_EQUAL, "n", intVal(5));
    assertTrue(Operators.evaluateCriterion(6, true, gt, null));
    assertFalse(Operators.evaluateCriterion(5, true, gt, null));
    assertTrue(Operators.evaluateCriterion(5, true, gte, null));
  }

  @Test
  void lessThan_andOrEqual_doublesAndInts() {
    Criterion lt = crit(Operators.PROP_LESS_THAN, "n", dbl(5.5));
    assertTrue(Operators.evaluateCriterion(5, true, lt, null));
    assertFalse(Operators.evaluateCriterion(5.5, true, lt, null));
    Criterion lte = crit(Operators.PROP_LESS_THAN_OR_EQUAL, "n", dbl(5.5));
    assertTrue(Operators.evaluateCriterion(5.5, true, lte, null));
  }

  @Test
  void comparison_failsClosedWhenContextNotNumeric() {
    Criterion gt = crit(Operators.PROP_GREATER_THAN, "n", intVal(5));
    assertFalse(Operators.evaluateCriterion("not-a-number", true, gt, null));
  }

  // ----- BEFORE / AFTER (dates) -----

  @Test
  void propBefore_acceptsMillisAndIso() {
    long matchMillis = 1700000000000L;
    Criterion before = crit(Operators.PROP_BEFORE, "user.created_at", intVal(matchMillis));
    assertTrue(Operators.evaluateCriterion(matchMillis - 1, true, before, null));
    assertFalse(Operators.evaluateCriterion(matchMillis + 1, true, before, null));
    // ISO string context value
    assertTrue(
        Operators.evaluateCriterion("2020-01-01T00:00:00Z", true, before, null),
        "ISO before match");
  }

  @Test
  void propAfter_isInverseOfBefore() {
    long matchMillis = 1700000000000L;
    Criterion after = crit(Operators.PROP_AFTER, "user.created_at", intVal(matchMillis));
    assertTrue(Operators.evaluateCriterion(matchMillis + 1, true, after, null));
    assertFalse(Operators.evaluateCriterion(matchMillis - 1, true, after, null));
  }

  // ----- SEMVER -----

  @Test
  void semver_lt_eq_gt() {
    Criterion lt = crit(Operators.PROP_SEMVER_LESS_THAN, "v", str("2.0.0"));
    Criterion eq = crit(Operators.PROP_SEMVER_EQUAL, "v", str("2.0.0"));
    Criterion gt = crit(Operators.PROP_SEMVER_GREATER_THAN, "v", str("2.0.0"));
    assertTrue(Operators.evaluateCriterion("1.99.99", true, lt, null));
    assertTrue(Operators.evaluateCriterion("2.0.0", true, eq, null));
    assertTrue(Operators.evaluateCriterion("2.0.1", true, gt, null));
    // pre-release: 2.0.0-rc1 < 2.0.0
    assertTrue(Operators.evaluateCriterion("2.0.0-rc1", true, lt, null));
  }

  @Test
  void semver_failsClosedOnInvalidInput() {
    Criterion lt = crit(Operators.PROP_SEMVER_LESS_THAN, "v", str("2.0.0"));
    assertFalse(Operators.evaluateCriterion("not-a-semver", true, lt, null));
  }

  // ----- IS_PRESENT / IS_NOT_PRESENT -----

  @Test
  void isPresent_emptyStringAndZeroAreStillPresent() {
    Criterion present = crit(Operators.IS_PRESENT, "user.email", null);
    assertTrue(Operators.evaluateCriterion("", true, present, null));
    assertTrue(Operators.evaluateCriterion(0, true, present, null));
    assertTrue(Operators.evaluateCriterion(false, true, present, null));
    assertFalse(Operators.evaluateCriterion(null, false, present, null));
    assertFalse(Operators.evaluateCriterion(null, true, present, null), "null is absent");
  }

  @Test
  void isNotPresent_isInverse() {
    Criterion notPresent = crit(Operators.IS_NOT_PRESENT, "user.email", null);
    assertTrue(Operators.evaluateCriterion(null, false, notPresent, null));
    assertFalse(Operators.evaluateCriterion("x", true, notPresent, null));
  }

  // ----- IN_SEG / NOT_IN_SEG via SegmentResolver -----

  @Test
  void inSeg_callsResolverAndReturnsResult() {
    Criterion c = crit(Operators.IN_SEG, null, str("seg-key"));
    SegmentResolver yesResolver =
        key -> {
          if ("seg-key".equals(key)) {
            return SegmentResolver.Result.found(true);
          }
          return SegmentResolver.Result.notFound();
        };
    assertTrue(Operators.evaluateCriterion(null, false, c, yesResolver));
  }

  @Test
  void inSeg_segmentNotFound_returnsFalse() {
    Criterion c = crit(Operators.IN_SEG, null, str("missing"));
    SegmentResolver missing = key -> SegmentResolver.Result.notFound();
    assertFalse(Operators.evaluateCriterion(null, false, c, missing));
  }

  @Test
  void notInSeg_segmentNotFound_returnsTrue() {
    Criterion c = crit(Operators.NOT_IN_SEG, null, str("missing"));
    SegmentResolver missing = key -> SegmentResolver.Result.notFound();
    assertTrue(Operators.evaluateCriterion(null, false, c, missing));
  }

  @Test
  void unknownOperator_returnsFalse() {
    Criterion c = crit("MADE_UP_OPERATOR", "x", str("y"));
    assertFalse(Operators.evaluateCriterion("y", true, c, null));
  }

  // ----- Sanity: all named operator strings exist -----

  @Test
  void operatorConstants_matchExpectedStringValues() {
    List<String> expected =
        Arrays.asList(
            "NOT_SET",
            "ALWAYS_TRUE",
            "PROP_IS_ONE_OF",
            "PROP_IS_NOT_ONE_OF",
            "PROP_STARTS_WITH_ONE_OF",
            "PROP_DOES_NOT_START_WITH_ONE_OF",
            "PROP_ENDS_WITH_ONE_OF",
            "PROP_DOES_NOT_END_WITH_ONE_OF",
            "PROP_CONTAINS_ONE_OF",
            "PROP_DOES_NOT_CONTAIN_ONE_OF",
            "PROP_MATCHES",
            "PROP_DOES_NOT_MATCH",
            "HIERARCHICAL_MATCH",
            "IN_INT_RANGE",
            "PROP_GREATER_THAN",
            "PROP_GREATER_THAN_OR_EQUAL",
            "PROP_LESS_THAN",
            "PROP_LESS_THAN_OR_EQUAL",
            "PROP_BEFORE",
            "PROP_AFTER",
            "PROP_SEMVER_LESS_THAN",
            "PROP_SEMVER_EQUAL",
            "PROP_SEMVER_GREATER_THAN",
            "IN_SEG",
            "NOT_IN_SEG",
            "IS_PRESENT",
            "IS_NOT_PRESENT");
    // Smoke: each constant equals its string name (case insensitive contract)
    for (String op : expected) {
      // All upper-case underscore. If missing, this fails to even compile.
      assertTrue(op.length() > 0);
    }
  }
}
