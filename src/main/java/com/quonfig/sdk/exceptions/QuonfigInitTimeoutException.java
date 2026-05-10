package com.quonfig.sdk.exceptions;

/**
 * Raised when a {@code Quonfig} client cannot complete initialization within {@code
 * Options.initTimeout()}. Used for the {@code on_init_failure: raise} construction policy when the
 * configured API endpoint is unreachable (the cross-SDK YAML's {@code initialization_timeout} error
 * key).
 */
public final class QuonfigInitTimeoutException extends RuntimeException {

  public QuonfigInitTimeoutException(String message) {
    super(message);
  }

  public QuonfigInitTimeoutException(String message, Throwable cause) {
    super(message, cause);
  }
}
