package com.quonfig.sdk.exceptions;

/**
 * Raised when a config key has no resolved value and the caller did not supply a default.
 *
 * <p>Mirrors {@code QuonfigKeyNotFoundError} in sdk-python and the {@code missing_default} YAML
 * error key in the cross-SDK integration suite.
 *
 * <p>A value that exists but cannot be converted to the requested type (an ENV_VAR {@code
 * "not_a_number"} read as INT, a malformed stored int, double or duration) raises the subclass
 * {@link QuonfigCoercionException}. It extends this class so code written against earlier releases,
 * which threw this class for that case, keeps catching it.
 */
public class QuonfigKeyNotFoundException extends RuntimeException {

  public QuonfigKeyNotFoundException(String message) {
    super(message);
  }

  public QuonfigKeyNotFoundException(String message, Throwable cause) {
    super(message, cause);
  }
}
