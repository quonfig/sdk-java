package com.quonfig.sdk.exceptions;

/**
 * Raised when a config key has no resolved value and the caller did not supply a default.
 *
 * <p>Mirrors {@code QuonfigKeyNotFoundError} in sdk-python and the {@code missing_default} YAML
 * error key in the cross-SDK integration suite. Also raised when a {@code PROVIDED} env-var value
 * fails type coercion (e.g. coercing {@code "not_a_number"} to INT) — that path produces this
 * exception in sdk-python too.
 */
public final class QuonfigKeyNotFoundException extends RuntimeException {

  public QuonfigKeyNotFoundException(String message) {
    super(message);
  }

  public QuonfigKeyNotFoundException(String message, Throwable cause) {
    super(message, cause);
  }
}
