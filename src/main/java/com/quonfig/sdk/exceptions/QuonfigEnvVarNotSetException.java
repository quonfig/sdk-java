package com.quonfig.sdk.exceptions;

/**
 * Raised when a config's {@code PROVIDED} (env-var-sourced) value cannot be resolved because the
 * named environment variable is not set. Maps to the {@code missing_env_var} YAML error key.
 */
public final class QuonfigEnvVarNotSetException extends RuntimeException {

  public QuonfigEnvVarNotSetException(String message) {
    super(message);
  }

  public QuonfigEnvVarNotSetException(String message, Throwable cause) {
    super(message, cause);
  }
}
