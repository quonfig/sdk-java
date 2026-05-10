package com.quonfig.sdk;

/**
 * Coarse classification of why an evaluation produced its value. Mirrors the OpenFeature {@code
 * Reason} enum so a future openfeature-java provider is a thin adapter.
 *
 * <p>Order matches the wire-protocol integer codes (see {@code
 * project/plans/openfeature-resolution-details.md} §5): {@code UNKNOWN}=0, {@code STATIC}=1, {@code
 * TARGETING_MATCH}=2, {@code SPLIT}=3, {@code DEFAULT}=4, {@code ERROR}=5.
 */
public enum Reason {
  /** Defensive: any unmapped reason falls into this bucket. */
  UNKNOWN,
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
