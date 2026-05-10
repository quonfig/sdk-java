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
  /** Internal evaluation failure not covered by another code (env var missing, decryption, …). */
  GENERAL;
}
