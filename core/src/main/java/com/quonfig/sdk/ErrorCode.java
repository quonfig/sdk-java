package com.quonfig.sdk;

/**
 * Error classifications returned in {@link EvaluationDetails#errorCode()} when {@link Reason#ERROR}
 * is the outcome. Names mirror OpenFeature's standard codes; Quonfig-specific causes (env var
 * missing, decryption failure) map onto {@link #GENERAL} with a descriptive {@code errorMessage}.
 */
public enum ErrorCode {
  /** No config exists for the requested key. */
  FLAG_NOT_FOUND,
  /**
   * The config exists but its declared {@code valueType} does not match the typed getter called.
   */
  TYPE_MISMATCH,
  /**
   * The client has no config yet: init has not completed, or it failed and the background retry has
   * not healed it (OpenFeature's {@code PROVIDER_NOT_READY}).
   */
  PROVIDER_NOT_READY,
  /**
   * The value has the requested type but does not parse as it: an ENV_VAR value that cannot be
   * converted to the config's type, or a malformed stored int, double or duration (OpenFeature's
   * {@code PARSE_ERROR}). Added in 2.4.0; earlier versions report these as {@link #TYPE_MISMATCH}.
   */
  PARSE_ERROR,
  /** Internal evaluation failure not covered by another code (env var missing, decryption, …). */
  GENERAL;
}
