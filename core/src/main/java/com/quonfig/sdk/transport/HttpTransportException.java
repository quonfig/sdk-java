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

  private static final int[] NO_LEGS = new int[0];

  private final int statusCode;
  private final String bodyExcerpt;
  private final int[] legStatuses;

  public HttpTransportException(int statusCode, String bodyExcerpt, String message) {
    this(statusCode, bodyExcerpt, message, null, null);
  }

  public HttpTransportException(
      int statusCode, String bodyExcerpt, String message, Throwable cause) {
    this(statusCode, bodyExcerpt, message, cause, null);
  }

  /**
   * Like {@link #HttpTransportException(int, String, String, Throwable)}, also carrying the status
   * each base-URL leg answered with during the failover walk ({@code legStatuses[i]} for leg {@code
   * i}; 0 = no response, e.g. a dial error or timeout). The auth-failure rule reads the primary
   * leg's status from it (qfg-goi1.10.27).
   */
  public HttpTransportException(
      int statusCode, String bodyExcerpt, String message, Throwable cause, int[] legStatuses) {
    super(message, cause);
    this.statusCode = statusCode;
    this.bodyExcerpt = bodyExcerpt == null ? "" : bodyExcerpt;
    this.legStatuses = legStatuses == null ? NO_LEGS : legStatuses.clone();
  }

  /** HTTP status code from the last attempt, or 0 if the failure was a network/transport error. */
  public int statusCode() {
    return statusCode;
  }

  /**
   * The status each leg answered with during the failover walk, indexed by base-URL leg (0 = the
   * primary); 0 = no response from that leg. Empty when the walk was not recorded per leg.
   */
  public int[] legStatuses() {
    return legStatuses.clone();
  }

  /**
   * The primary (first) leg's status, or 0 when it gave no response or the walk was not recorded.
   */
  public int primaryStatus() {
    return legStatuses.length > 0 ? legStatuses[0] : 0;
  }

  /** First few KB of the response body — never the full body, never null. */
  public String bodyExcerpt() {
    return bodyExcerpt;
  }
}
