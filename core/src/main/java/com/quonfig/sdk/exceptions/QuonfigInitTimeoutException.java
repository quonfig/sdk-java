package com.quonfig.sdk.exceptions;

import com.quonfig.sdk.Quonfig;

/**
 * The {@link QuonfigInitException} for {@link QuonfigInitException.Reason#TIMEOUT}: no init attempt
 * finished before {@code Options.initTimeout()} (the cross-SDK YAML's {@code
 * initialization_timeout} error key). Since 2.0.0 it extends {@link QuonfigInitException} and is no
 * longer {@code final}; existing {@code catch (QuonfigInitTimeoutException e)} blocks still match.
 */
public class QuonfigInitTimeoutException extends QuonfigInitException {

  private static final long serialVersionUID = 1L;

  public QuonfigInitTimeoutException(String message) {
    this(message, null, null);
  }

  public QuonfigInitTimeoutException(String message, Throwable cause) {
    this(message, cause, null);
  }

  public QuonfigInitTimeoutException(String message, Throwable cause, Quonfig client) {
    super(Reason.TIMEOUT, message, cause, client);
  }
}
