package com.quonfig.sdk.eval;

/** Reads {@link ConfigRow} entries by key. The Evaluator uses this to recurse into segments. */
@FunctionalInterface
public interface ConfigStore {
  /** Returns the config row for {@code key} or null if missing. */
  ConfigRow getConfig(String key);
}
