package com.quonfig.sdk.transport;

/**
 * Raised when an HTTP request to api-delivery / api-telemetry returns a non-2xx response (other
 * than 304 on a GET) on every URL the transport tried, or when the underlying socket fails on every
 * URL.
 *
 * <p>{@link #bodyExcerpt()} is capped (see {@link HttpTransport#MAX_BODY_EXCERPT}) so a runaway
 * server response can't OOM the SDK.
 */
public final class HttpTransportException extends RuntimeException {

  private final int statusCode;
  private final String bodyExcerpt;

  public HttpTransportException(int statusCode, String bodyExcerpt, String message) {
    super(message);
    this.statusCode = statusCode;
    this.bodyExcerpt = bodyExcerpt == null ? "" : bodyExcerpt;
  }

  public HttpTransportException(
      int statusCode, String bodyExcerpt, String message, Throwable cause) {
    super(message, cause);
    this.statusCode = statusCode;
    this.bodyExcerpt = bodyExcerpt == null ? "" : bodyExcerpt;
  }

  /** HTTP status code from the last attempt, or 0 if the failure was a network/transport error. */
  public int statusCode() {
    return statusCode;
  }

  /** First few KB of the response body — never the full body, never null. */
  public String bodyExcerpt() {
    return bodyExcerpt;
  }
}
