package com.quonfig.sdk.exceptions;

import com.quonfig.sdk.Quonfig;

/**
 * Thrown by the {@link Quonfig} constructor under {@link com.quonfig.sdk.OnInitFailure#FAIL} (the
 * default) when no config loaded: every delivery leg rejected the SDK key ({@link
 * Reason#UNAUTHORIZED}), every attempt failed until {@code initTimeout} ({@link
 * Reason#UNAVAILABLE}), or no attempt finished before {@code initTimeout} ({@link Reason#TIMEOUT},
 * thrown as the {@link QuonfigInitTimeoutException} subclass).
 *
 * <p>The client is not dead: {@link #client()} returns the instance the constructor built, which
 * keeps retrying in the background and becomes {@link Quonfig#isReady() ready} when delivery comes
 * back. Keep it, or {@link Quonfig#close() close} it if you do not.
 */
public class QuonfigInitException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /** Why init failed. */
  public enum Reason {
    /** Every delivery leg failed (refused, DNS, 5xx, ...) until {@code initTimeout}. */
    UNAVAILABLE,
    /** Every delivery leg answered 401/403/404/410: the SDK key was rejected. */
    UNAUTHORIZED,
    /**
     * The last init attempt hung (every leg timed out), or none finished before {@code
     * initTimeout}.
     */
    TIMEOUT
  }

  private final Reason reason;
  private final transient Quonfig client;

  public QuonfigInitException(String message) {
    this(Reason.UNAVAILABLE, message, null, null);
  }

  public QuonfigInitException(String message, Throwable cause) {
    this(Reason.UNAVAILABLE, message, cause, null);
  }

  public QuonfigInitException(Reason reason, String message, Throwable cause, Quonfig client) {
    super(message, cause);
    this.reason = reason;
    this.client = client;
  }

  public Reason reason() {
    return reason;
  }

  /**
   * The client the failed constructor built, still retrying in the background; {@code null} when
   * the exception did not come from a constructor.
   */
  public Quonfig client() {
    return client;
  }
}
