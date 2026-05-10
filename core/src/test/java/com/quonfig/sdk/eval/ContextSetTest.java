package com.quonfig.sdk.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ContextSetTest {

  @Test
  void dottedLookup_findsKeyInNamedContext() {
    Map<String, Object> userData = new LinkedHashMap<>();
    userData.put("email", "a@b.com");
    userData.put("age", 42);
    ContextSet ctx = new ContextSet().withNamedContext("user", userData);

    ContextSet.Lookup result = ctx.getContextValue("user.email");
    assertTrue(result.exists());
    assertEquals("a@b.com", result.value());

    ContextSet.Lookup ageResult = ctx.getContextValue("user.age");
    assertTrue(ageResult.exists());
    assertEquals(42, ageResult.value());
  }

  @Test
  void dottedLookup_missingContextReturnsNotExists() {
    ContextSet ctx = new ContextSet();
    ContextSet.Lookup result = ctx.getContextValue("user.email");
    assertFalse(result.exists());
  }

  @Test
  void dottedLookup_missingKeyInExistingContext() {
    ContextSet ctx = new ContextSet().withNamedContext("user", Map.of("email", "a@b.com"));
    ContextSet.Lookup result = ctx.getContextValue("user.unknown");
    assertFalse(result.exists());
  }

  @Test
  void noDot_returnsNotExists() {
    ContextSet ctx = new ContextSet().withNamedContext("user", Map.of("email", "a"));
    assertFalse(ctx.getContextValue("nodotted").exists());
  }

  @Test
  void presentNullValue_existsButNullValue() {
    Map<String, Object> userData = new LinkedHashMap<>();
    userData.put("email", null);
    ContextSet ctx = new ContextSet().withNamedContext("user", userData);
    ContextSet.Lookup result = ctx.getContextValue("user.email");
    // Java map containsKey vs get distinguishes "key set to null" from "missing".
    // The eval semantics treat null as absent for IS_PRESENT but the lookup itself
    // can report the key as set; what matters is `value()` is null.
    assertTrue(result.exists(), "key was set in the map");
    assertEquals(null, result.value());
  }

  @Test
  void magicCurrentTime_returnsMillisAndExists() {
    ContextSet ctx = new ContextSet();
    long before = System.currentTimeMillis();
    ContextSet.Lookup r = ctx.getContextValue("quonfig.current-time");
    long after = System.currentTimeMillis();
    assertTrue(r.exists());
    assertNotNull(r.value());
    long v = ((Number) r.value()).longValue();
    assertTrue(v >= before && v <= after, "current-time must be wall-clock millis");
  }

  @Test
  void magicCurrentTime_aliasesAlsoWork() {
    ContextSet ctx = new ContextSet();
    assertTrue(ctx.getContextValue("prefab.current-time").exists());
    assertTrue(ctx.getContextValue("reforge.current-time").exists());
  }
}
