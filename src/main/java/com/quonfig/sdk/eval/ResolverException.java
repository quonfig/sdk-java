package com.quonfig.sdk.eval;

/**
 * Thrown by {@link Resolver#resolve} when a value cannot be materialized: missing environment
 * variable, unparseable env-var value for the declared type, or AES-GCM decryption failure.
 */
public final class ResolverException extends RuntimeException {

  /** Discriminator matching the error categories in sdk-go's resolver package. */
  public enum Kind {
    MISSING_ENV_VAR,
    UNABLE_TO_COERCE,
    UNABLE_TO_DECRYPT,
    MISSING_DEFAULT;
  }

  private final Kind kind;

  public ResolverException(Kind kind, String message) {
    super(message);
    this.kind = kind;
  }

  public ResolverException(Kind kind, String message, Throwable cause) {
    super(message, cause);
    this.kind = kind;
  }

  public Kind kind() {
    return kind;
  }
}
