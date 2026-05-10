package com.quonfig.sdk;

/**
 * Coarse classification of why an evaluation produced its value. Mirrors the OpenFeature {@code
 * Reason} enum so a future openfeature-java provider is a thin adapter.
 */
public enum Reason {
  /** First rule with no criteria — no targeting needed. */
  STATIC,
  /** A rule's criteria all matched the supplied context. */
  TARGETING_MATCH,
  /** Targeting matched and the matched value was a weighted bucket; one entry was selected. */
  SPLIT,
  /** No rule matched; the caller's default value was returned. */
  DEFAULT,
  /** Evaluation could not complete (missing key, type mismatch, decryption failure, …). */
  ERROR;
}
