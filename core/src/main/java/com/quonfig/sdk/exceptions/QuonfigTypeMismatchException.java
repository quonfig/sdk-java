package com.quonfig.sdk.exceptions;

/**
 * Raised by the {@code get*OrThrow} family when the config's value is not of the getter's type, for
 * example {@code getLongOrThrow} on a STRING config or {@code getBoolOrThrow} on a stored string
 * {@code "false"}. Strings are never coerced. The only conversions are INT to DOUBLE, and a whole
 * DOUBLE within +/-(2^53-1) to INT. Maps to the {@code type_mismatch} YAML error key in the
 * cross-SDK integration suite.
 *
 * <p>Extends {@link IllegalArgumentException}, which earlier releases threw for this case, so
 * existing {@code catch (IllegalArgumentException e)} blocks still catch it (qfg-koj9).
 */
public final class QuonfigTypeMismatchException extends IllegalArgumentException {

  public QuonfigTypeMismatchException(String message) {
    super(message);
  }

  public QuonfigTypeMismatchException(String message, Throwable cause) {
    super(message, cause);
  }
}
