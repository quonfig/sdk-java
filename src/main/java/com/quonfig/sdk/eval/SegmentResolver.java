package com.quonfig.sdk.eval;

/**
 * Resolves an {@code IN_SEG} / {@code NOT_IN_SEG} reference to a boolean. Implementations typically
 * recurse into the {@link Evaluator} to evaluate the referenced segment config.
 */
@FunctionalInterface
public interface SegmentResolver {
  Result resolve(String segmentKey);

  /** Result of a segment lookup: was the segment found, and if so what did it evaluate to? */
  final class Result {
    private static final Result NOT_FOUND = new Result(false, false);
    private static final Result FOUND_TRUE = new Result(true, true);
    private static final Result FOUND_FALSE = new Result(true, false);

    private final boolean found;
    private final boolean value;

    private Result(boolean found, boolean value) {
      this.found = found;
      this.value = value;
    }

    public static Result notFound() {
      return NOT_FOUND;
    }

    public static Result found(boolean value) {
      return value ? FOUND_TRUE : FOUND_FALSE;
    }

    public boolean found() {
      return found;
    }

    public boolean value() {
      return value;
    }
  }
}
