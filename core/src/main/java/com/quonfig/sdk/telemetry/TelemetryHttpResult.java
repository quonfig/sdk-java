package com.quonfig.sdk.telemetry;

/**
 * Outcome of one telemetry POST that got an HTTP response: the status, the raw {@code Retry-After}
 * header (or null) and the first 1024 characters of the response body.
 */
record TelemetryHttpResult(int status, String retryAfter, String bodySnippet) {}
