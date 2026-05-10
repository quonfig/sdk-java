package com.quonfig.sdk.eval;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Operator constants and the single-criterion evaluator used by {@link Evaluator}.
 *
 * <p>Faithful port of {@code sdk-go/evalcore/operators.go} and {@code sdk-node/src/operators.ts}.
 * Pure logic — no IO, no allocation in the hot path beyond what Java forces on us.
 */
public final class Operators {
  private Operators() {}

  // Operator string constants — these match the values produced by app-quonfig.
  public static final String NOT_SET = "NOT_SET";
  public static final String ALWAYS_TRUE = "ALWAYS_TRUE";
  public static final String PROP_IS_ONE_OF = "PROP_IS_ONE_OF";
  public static final String PROP_IS_NOT_ONE_OF = "PROP_IS_NOT_ONE_OF";
  public static final String PROP_STARTS_WITH_ONE_OF = "PROP_STARTS_WITH_ONE_OF";
  public static final String PROP_DOES_NOT_START_WITH_ONE_OF = "PROP_DOES_NOT_START_WITH_ONE_OF";
  public static final String PROP_ENDS_WITH_ONE_OF = "PROP_ENDS_WITH_ONE_OF";
  public static final String PROP_DOES_NOT_END_WITH_ONE_OF = "PROP_DOES_NOT_END_WITH_ONE_OF";
  public static final String PROP_CONTAINS_ONE_OF = "PROP_CONTAINS_ONE_OF";
  public static final String PROP_DOES_NOT_CONTAIN_ONE_OF = "PROP_DOES_NOT_CONTAIN_ONE_OF";
  public static final String PROP_MATCHES = "PROP_MATCHES";
  public static final String PROP_DOES_NOT_MATCH = "PROP_DOES_NOT_MATCH";
  public static final String HIERARCHICAL_MATCH = "HIERARCHICAL_MATCH";
  public static final String IN_INT_RANGE = "IN_INT_RANGE";
  public static final String PROP_GREATER_THAN = "PROP_GREATER_THAN";
  public static final String PROP_GREATER_THAN_OR_EQUAL = "PROP_GREATER_THAN_OR_EQUAL";
  public static final String PROP_LESS_THAN = "PROP_LESS_THAN";
  public static final String PROP_LESS_THAN_OR_EQUAL = "PROP_LESS_THAN_OR_EQUAL";
  public static final String PROP_BEFORE = "PROP_BEFORE";
  public static final String PROP_AFTER = "PROP_AFTER";
  public static final String PROP_SEMVER_LESS_THAN = "PROP_SEMVER_LESS_THAN";
  public static final String PROP_SEMVER_EQUAL = "PROP_SEMVER_EQUAL";
  public static final String PROP_SEMVER_GREATER_THAN = "PROP_SEMVER_GREATER_THAN";
  public static final String IN_SEG = "IN_SEG";
  public static final String NOT_IN_SEG = "NOT_IN_SEG";
  public static final String IS_PRESENT = "IS_PRESENT";
  public static final String IS_NOT_PRESENT = "IS_NOT_PRESENT";

  /**
   * Evaluate a single criterion against the resolved context value.
   *
   * @param contextValue the resolved context value (may be null)
   * @param contextExists whether the dotted property actually exists in the context
   * @param criterion the criterion to evaluate
   * @param segmentResolver resolver for {@code IN_SEG} / {@code NOT_IN_SEG}; may be null
   * @return whether the criterion is satisfied
   */
  public static boolean evaluateCriterion(
      Object contextValue,
      boolean contextExists,
      Criterion criterion,
      SegmentResolver segmentResolver) {
    Value matchValue = criterion.valueToMatch();
    String op = criterion.operator();

    switch (op) {
      case NOT_SET:
        return false;

      case ALWAYS_TRUE:
        return true;

      case PROP_IS_ONE_OF:
      case PROP_IS_NOT_ONE_OF:
        {
          if (contextExists && matchValue != null) {
            List<String> matchStrings = stringListOf(matchValue);
            if (matchStrings != null) {
              List<String> contextStrings = toStringList(contextValue);
              boolean matchFound = false;
              for (String cv : contextStrings) {
                if (matchStrings.contains(cv)) {
                  matchFound = true;
                  break;
                }
              }
              return matchFound == op.equals(PROP_IS_ONE_OF);
            }
          }
          return op.equals(PROP_IS_NOT_ONE_OF);
        }

      case PROP_STARTS_WITH_ONE_OF:
      case PROP_DOES_NOT_START_WITH_ONE_OF:
        {
          if (contextExists && matchValue != null) {
            List<String> matchStrings = stringListOf(matchValue);
            if (matchStrings != null) {
              String cv = toStringOrEmpty(contextValue);
              boolean matchFound = anyMatch(matchStrings, cv, MatchKind.STARTS);
              return matchFound == op.equals(PROP_STARTS_WITH_ONE_OF);
            }
          }
          return op.equals(PROP_DOES_NOT_START_WITH_ONE_OF);
        }

      case PROP_ENDS_WITH_ONE_OF:
      case PROP_DOES_NOT_END_WITH_ONE_OF:
        {
          if (contextExists && matchValue != null) {
            List<String> matchStrings = stringListOf(matchValue);
            if (matchStrings != null) {
              String cv = toStringOrEmpty(contextValue);
              boolean matchFound = anyMatch(matchStrings, cv, MatchKind.ENDS);
              return matchFound == op.equals(PROP_ENDS_WITH_ONE_OF);
            }
          }
          return op.equals(PROP_DOES_NOT_END_WITH_ONE_OF);
        }

      case PROP_CONTAINS_ONE_OF:
      case PROP_DOES_NOT_CONTAIN_ONE_OF:
        {
          if (contextExists && matchValue != null) {
            List<String> matchStrings = stringListOf(matchValue);
            if (matchStrings != null) {
              String cv = toStringOrEmpty(contextValue);
              boolean matchFound = anyMatch(matchStrings, cv, MatchKind.CONTAINS);
              return matchFound == op.equals(PROP_CONTAINS_ONE_OF);
            }
          }
          return op.equals(PROP_DOES_NOT_CONTAIN_ONE_OF);
        }

      case PROP_MATCHES:
      case PROP_DOES_NOT_MATCH:
        {
          if (contextExists
              && matchValue != null
              && contextValue instanceof String
              && matchValue.value() instanceof String) {
            try {
              Pattern p = Pattern.compile((String) matchValue.value());
              boolean matched = p.matcher((String) contextValue).find();
              return matched == op.equals(PROP_MATCHES);
            } catch (PatternSyntaxException e) {
              return false;
            }
          }
          return false;
        }

      case HIERARCHICAL_MATCH:
        {
          if (contextExists && matchValue != null) {
            String cv = toStringOrEmpty(contextValue);
            String mv = toStringOrEmpty(matchValue.value());
            return cv.startsWith(mv);
          }
          return false;
        }

      case IN_INT_RANGE:
        {
          if (contextExists && matchValue != null) {
            long[] range = extractIntRange(matchValue);
            Double n = toDouble(contextValue);
            if (n != null) {
              return n >= range[0] && n < range[1];
            }
          }
          return false;
        }

      case PROP_GREATER_THAN:
      case PROP_GREATER_THAN_OR_EQUAL:
      case PROP_LESS_THAN:
      case PROP_LESS_THAN_OR_EQUAL:
        {
          if (contextExists
              && matchValue != null
              && isNumber(contextValue)
              && isNumber(matchValue.value())) {
            Double a = toDouble(contextValue);
            Double b = toDouble(matchValue.value());
            if (a != null && b != null) {
              int cmp = Double.compare(a, b);
              switch (op) {
                case PROP_GREATER_THAN:
                  return cmp > 0;
                case PROP_GREATER_THAN_OR_EQUAL:
                  return cmp >= 0;
                case PROP_LESS_THAN:
                  return cmp < 0;
                case PROP_LESS_THAN_OR_EQUAL:
                  return cmp <= 0;
                default:
                  return false;
              }
            }
          }
          return false;
        }

      case PROP_BEFORE:
      case PROP_AFTER:
        {
          if (contextExists && matchValue != null) {
            Long ctxMillis = dateToMillis(contextValue);
            Long matchMillis = dateToMillis(matchValue.value());
            if (ctxMillis != null && matchMillis != null) {
              return op.equals(PROP_BEFORE) ? ctxMillis < matchMillis : ctxMillis > matchMillis;
            }
          }
          return false;
        }

      case PROP_SEMVER_LESS_THAN:
      case PROP_SEMVER_EQUAL:
      case PROP_SEMVER_GREATER_THAN:
        {
          if (contextExists
              && matchValue != null
              && contextValue instanceof String
              && matchValue.value() instanceof String) {
            SemanticVersion ctx = SemanticVersion.parseQuietly((String) contextValue);
            SemanticVersion mv = SemanticVersion.parseQuietly((String) matchValue.value());
            if (ctx != null && mv != null) {
              int cmp = ctx.compareTo(mv);
              switch (op) {
                case PROP_SEMVER_LESS_THAN:
                  return cmp < 0;
                case PROP_SEMVER_EQUAL:
                  return cmp == 0;
                case PROP_SEMVER_GREATER_THAN:
                  return cmp > 0;
                default:
                  return false;
              }
            }
          }
          return false;
        }

      case IS_PRESENT:
      case IS_NOT_PRESENT:
        {
          // A property is "present" iff the dotted path resolved AND the value is non-null.
          // Empty string, 0, and false are intentionally treated as present.
          boolean present = contextExists && contextValue != null;
          return present == op.equals(IS_PRESENT);
        }

      case IN_SEG:
      case NOT_IN_SEG:
        {
          if (matchValue != null && segmentResolver != null) {
            String segKey = toStringOrEmpty(matchValue.value());
            SegmentResolver.Result r = segmentResolver.resolve(segKey);
            if (!r.found()) {
              return op.equals(NOT_IN_SEG);
            }
            return r.value() == op.equals(IN_SEG);
          }
          return op.equals(NOT_IN_SEG);
        }

      default:
        return false;
    }
  }

  // ---------- helpers ----------

  private enum MatchKind {
    STARTS,
    ENDS,
    CONTAINS
  }

  private static boolean anyMatch(List<String> needles, String haystack, MatchKind kind) {
    for (String n : needles) {
      switch (kind) {
        case STARTS:
          if (haystack.startsWith(n)) return true;
          break;
        case ENDS:
          if (haystack.endsWith(n)) return true;
          break;
        case CONTAINS:
          if (haystack.contains(n)) return true;
          break;
      }
    }
    return false;
  }

  private static List<String> stringListOf(Value v) {
    Object raw = v.value();
    if (raw instanceof List<?>) {
      List<?> list = (List<?>) raw;
      List<String> out = new ArrayList<>(list.size());
      for (Object o : list) {
        out.add(toStringOrEmpty(o));
      }
      return out;
    }
    return null;
  }

  private static String toStringOrEmpty(Object v) {
    if (v == null) return "";
    return v.toString();
  }

  private static List<String> toStringList(Object v) {
    if (v == null) return List.of();
    if (v instanceof Collection<?>) {
      List<String> out = new ArrayList<>(((Collection<?>) v).size());
      for (Object o : (Collection<?>) v) {
        out.add(toStringOrEmpty(o));
      }
      return out;
    }
    return List.of(toStringOrEmpty(v));
  }

  static boolean isNumber(Object v) {
    return v instanceof Number;
  }

  static Double toDouble(Object v) {
    if (v instanceof Number) return ((Number) v).doubleValue();
    if (v instanceof String) {
      try {
        return Double.parseDouble((String) v);
      } catch (NumberFormatException e) {
        return null;
      }
    }
    return null;
  }

  static long[] extractIntRange(Value v) {
    long start = Long.MIN_VALUE;
    long end = Long.MAX_VALUE;
    if (v == null) return new long[] {start, end};
    Object raw = v.value();
    if (raw instanceof Map<?, ?>) {
      Map<?, ?> m = (Map<?, ?>) raw;
      Object s = m.get("start");
      Object e = m.get("end");
      if (s != null) start = toLongOrZero(s);
      if (e != null) end = toLongOrZero(e);
    }
    return new long[] {start, end};
  }

  private static long toLongOrZero(Object v) {
    if (v instanceof Long) return (Long) v;
    if (v instanceof Integer) return (Integer) v;
    if (v instanceof Number) return ((Number) v).longValue();
    if (v instanceof String) {
      try {
        return Long.parseLong((String) v);
      } catch (NumberFormatException e) {
        return 0L;
      }
    }
    return 0L;
  }

  static Long dateToMillis(Object val) {
    if (val instanceof Number) return ((Number) val).longValue();
    if (val instanceof String) {
      String s = (String) val;
      try {
        return Instant.parse(s).toEpochMilli();
      } catch (DateTimeParseException ignored) {
        // fall through to numeric parse
      }
      try {
        return Long.parseLong(s);
      } catch (NumberFormatException e) {
        return null;
      }
    }
    return null;
  }
}
