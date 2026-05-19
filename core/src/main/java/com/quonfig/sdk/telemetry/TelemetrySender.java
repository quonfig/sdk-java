package com.quonfig.sdk.telemetry;

import java.io.IOException;
import java.util.Map;

/**
 * Sends a telemetry envelope to api-telemetry.
 *
 * <p>Implementations must throw {@link IOException} (or any subclass) on transport failure so the
 * reporter can apply exponential backoff. Returning normally signals success.
 */
@FunctionalInterface
public interface TelemetrySender {
  void send(Map<String, Object> payload) throws IOException;
}
