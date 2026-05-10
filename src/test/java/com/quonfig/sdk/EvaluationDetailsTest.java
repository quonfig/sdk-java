package com.quonfig.sdk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Direct unit tests for the {@link EvaluationDetails} record contract: which fields are nullable,
 * which fields default when omitted, the immutability of {@code metadata()}, and the {@code
 * variant} string spec'd in {@code project/plans/openfeature-resolution-details.md}.
 */
class EvaluationDetailsTest {

  @Test
  void nullableFields_acceptNullExceptReason() {
    EvaluationDetails<String> d =
        new EvaluationDetails<>("default", Reason.DEFAULT, "default", null, null, null, null);
    assertEquals("default", d.value());
    assertEquals(Reason.DEFAULT, d.reason());
    assertEquals("default", d.variant());
    assertNull(d.variantIndex());
    assertNull(d.errorCode());
    assertNull(d.errorMessage());
    assertEquals(Map.of(), d.metadata());
  }

  @Test
  void valueMayBeNull() {
    // T may be a reference type whose value is legitimately null (e.g. JSON null).
    EvaluationDetails<Object> d =
        new EvaluationDetails<>(null, Reason.STATIC, "static", null, null, null, Map.of());
    assertNull(d.value());
    assertEquals(Reason.STATIC, d.reason());
  }

  @Test
  void reasonIsRequired_throwsOnNull() {
    assertThrows(
        NullPointerException.class,
        () -> new EvaluationDetails<>("x", null, "static", null, null, null, Map.of()));
  }

  @Test
  void variantIsRequired_throwsOnNull() {
    assertThrows(
        NullPointerException.class,
        () -> new EvaluationDetails<>("x", Reason.STATIC, null, null, null, null, Map.of()));
  }

  @Test
  void metadata_isImmutable_evenWhenSourceMapMutated() {
    Map<String, Object> source = new HashMap<>();
    source.put("configId", "cfg-1");
    EvaluationDetails<String> d =
        new EvaluationDetails<>("v", Reason.STATIC, "static", null, null, null, source);

    source.put("configId", "MUTATED");
    source.put("extra", "added");
    assertEquals("cfg-1", d.metadata().get("configId"));
    assertNull(d.metadata().get("extra"));

    assertThrows(UnsupportedOperationException.class, () -> d.metadata().put("k", "v"));
  }

  @Test
  void metadata_nullCoercesToEmpty() {
    EvaluationDetails<String> d =
        new EvaluationDetails<>("v", Reason.STATIC, "static", null, null, null, null);
    assertNotNull(d.metadata());
    assertTrue(d.metadata().isEmpty());
  }

  @Test
  void allReasonValuesAreCovered() {
    // Pin the enum so a future PR can't silently drop UNKNOWN (added per qfg-ypcu spec).
    Reason[] expected = {
      Reason.STATIC,
      Reason.TARGETING_MATCH,
      Reason.SPLIT,
      Reason.DEFAULT,
      Reason.ERROR,
      Reason.UNKNOWN
    };
    assertEquals(expected.length, Reason.values().length);
    for (Reason r : expected) {
      assertNotNull(Reason.valueOf(r.name()));
    }
  }
}
