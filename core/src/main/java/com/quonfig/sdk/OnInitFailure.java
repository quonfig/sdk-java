package com.quonfig.sdk;

/**
 * What the {@link Quonfig} constructor does when no config has loaded by {@link
 * Options#initTimeout()} (SDK-key delivery mode only; datadir and datafile load synchronously). Set
 * with {@link Options.Builder#onInitFailure(OnInitFailure)}. The default is {@link #FAIL}.
 *
 * <p>In every mode the constructor first blocks and retries through blips until {@code
 * initTimeout}; a healthy boot returns as soon as the first config lands. Getters never block on
 * init. A client object that exists keeps retrying in the background and heals when delivery comes
 * back.
 */
public enum OnInitFailure {
  /**
   * Throw {@link com.quonfig.sdk.exceptions.QuonfigInitException} at {@code initTimeout} (or at
   * once when every delivery leg rejects the SDK key with 401/403). The default: an instance that
   * boots on code defaults runs a different config than the rest of the fleet.
   */
  FAIL,
  /** Keep blocking the constructor, with no upper bound, until a config loads. */
  WAIT,
  /**
   * Return from the constructor at {@code initTimeout} with no config. Getters return the caller's
   * default ({@link Reason#ERROR}, {@link ErrorCode#PROVIDER_NOT_READY}) until the background retry
   * installs one.
   */
  CONTINUE
}
