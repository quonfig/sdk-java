package com.quonfig.sdk.micronaut;

import com.quonfig.sdk.eval.ContextSet;
import io.micronaut.http.context.ServerRequestContext;
import java.util.Optional;

/**
 * Per-request {@link ContextSet} storage for Micronaut applications.
 *
 * <p>Micronaut is event-driven: a single HTTP request can be handled by many threads, so {@code
 * ThreadLocal} won't carry a {@link ContextSet} across the request's lifetime. Instead, this helper
 * stashes the context on the active {@code HttpRequest}'s attribute bag and reads it back via
 * {@link ServerRequestContext#currentRequest()} — same mechanism Micronaut uses for its own
 * propagated state.
 *
 * <p>Typical wiring: a request-scoped filter calls {@link #setContext(ContextSet)} early in the
 * pipeline, downstream code reads {@link #getContext()} when calling Quonfig with context-aware
 * APIs (e.g. {@code quonfig.shouldLog(path, level,
 * MicronautContextStore.getContext().orElse(null))}).
 *
 * <p>Without an active request all operations are quiet no-ops, so the helper is safe to call from
 * background threads / non-request paths.
 */
public final class MicronautContextStore {

  /** HttpRequest attribute key under which the {@link ContextSet} is stashed. */
  public static final String ATTRIBUTE_NAME = "quonfig-sdk-context";

  private MicronautContextStore() {}

  /** Returns the {@link ContextSet} stored on the current request, or empty when none. */
  public static Optional<ContextSet> getContext() {
    return ServerRequestContext.currentRequest()
        .flatMap(req -> req.getAttribute(ATTRIBUTE_NAME, ContextSet.class));
  }

  /**
   * Replaces the {@link ContextSet} on the current request, returning whatever was there before
   * (empty if nothing was set, or there's no active request).
   */
  public static Optional<ContextSet> setContext(ContextSet ctx) {
    return ServerRequestContext.currentRequest()
        .map(
            req -> {
              Optional<ContextSet> prev = req.getAttribute(ATTRIBUTE_NAME, ContextSet.class);
              req.setAttribute(ATTRIBUTE_NAME, ctx);
              return prev;
            })
        .orElse(Optional.empty());
  }

  /**
   * Clears the {@link ContextSet} from the current request, returning the value that was removed
   * (empty if nothing was set, or there's no active request).
   */
  public static Optional<ContextSet> clearContext() {
    return ServerRequestContext.currentRequest()
        .map(
            req -> {
              Optional<ContextSet> prev = req.getAttribute(ATTRIBUTE_NAME, ContextSet.class);
              req.removeAttribute(ATTRIBUTE_NAME, ContextSet.class);
              return prev;
            })
        .orElse(Optional.empty());
  }
}
