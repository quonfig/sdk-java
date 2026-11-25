package com.quonfig.sdk.eval;

/** Top-level config type discriminator. */
public enum ConfigType {
  FEATURE_FLAG,
  CONFIG,
  SEGMENT,
  LOG_LEVEL,
  SCHEMA,
  /**
   * A qf1 secret: a document from the workspace's {@code secrets/} directory (or a wire entry of
   * type {@code secret}). Its string values are qf1 ciphertexts, decrypted on read with the private
   * keys of the {@code QUONFIG_KEYSET*} environment variable each value names.
   */
  SECRET;
}
