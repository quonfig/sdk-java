package com.quonfig.sdk.telemetry;

import java.io.IOException;
import java.util.Map;

/**
 * Sends a telemetry envelope to api-telemetry.
 *
 * <p>Implementations must throw {@link IOException} (or any subclass) on transport failure; the
 * reporter then keeps the batch and resends it under the telemetry transport policy. Returning
 * normally signals success.
 */
@FunctionalInterface
public interface TelemetrySender {
  void send(Map<String, Object> payload) throws IOException;
}
