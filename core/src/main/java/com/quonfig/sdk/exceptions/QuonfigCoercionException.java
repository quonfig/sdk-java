package com.quonfig.sdk.exceptions;

/**
 * Raised by the {@code get*OrThrow} family when a config's value exists but cannot be converted to
 * the requested type: an ENV_VAR-provided value such as {@code "not_a_number"} read as INT, or a
 * malformed stored int, double or duration. Maps to the {@code unable_to_coerce_env_var} YAML error
 * key in the cross-SDK integration suite.
 *
 * <p>Extends {@link QuonfigKeyNotFoundException}, which earlier releases threw for this case, so
 * existing {@code catch (QuonfigKeyNotFoundException e)} blocks still catch it (qfg-koj9). The
 * message names the key, never the value.
 */
public final class QuonfigCoercionException extends QuonfigKeyNotFoundException {

  public QuonfigCoercionException(String message) {
    super(message);
  }

  public QuonfigCoercionException(String message, Throwable cause) {
    super(message, cause);
  }
}
