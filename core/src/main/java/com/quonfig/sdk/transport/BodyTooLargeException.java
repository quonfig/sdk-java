package com.quonfig.sdk.transport;

import java.io.IOException;

/**
 * A 2xx response body over {@link HttpTransport#MAX_CONFIG_BODY_BYTES}. Internal: the transport
 * turns it into an {@link HttpTransportException} for a failed leg (size-caps rule, qfg-6vno.20.2).
 */
final class BodyTooLargeException extends IOException {
  private static final long serialVersionUID = 1L;

  private final int statusCode;

  BodyTooLargeException(int statusCode, long limit) {
    super("response body exceeds the maximum size of " + limit + " bytes");
    this.statusCode = statusCode;
  }

  int statusCode() {
    return statusCode;
  }
}
