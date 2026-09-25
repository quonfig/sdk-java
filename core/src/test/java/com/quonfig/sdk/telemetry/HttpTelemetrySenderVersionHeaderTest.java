package com.quonfig.sdk.telemetry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * qfg-y8je.2: telemetry POSTs must carry the real SDK version in {@code X-Quonfig-SDK-Version};
 * api-telemetry derives clientName/clientVersion from it. It used to be the literal {@code
 * java-0.0.1}.
 */
class HttpTelemetrySenderVersionHeaderTest {

  private HttpServer server;

  @AfterEach
  void stop() {
    if (server != null) server.stop(0);
  }

  @Test
  void sendsBuildVersionHeader() throws Exception {
    String expected = System.getProperty("quonfig.expectedSdkVersion");
    assertNotNull(expected, "build must pass quonfig.expectedSdkVersion to the test JVM");

    AtomicReference<String> header = new AtomicReference<>();
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/api/v1/telemetry/",
        ex -> {
          header.set(ex.getRequestHeaders().getFirst("X-Quonfig-SDK-Version"));
          ex.getRequestBody().readAllBytes();
          ex.sendResponseHeaders(200, -1);
          ex.close();
        });
    server.start();

    new HttpTelemetrySender("http://127.0.0.1:" + server.getAddress().getPort(), "sdk-key")
        .send(Map.of("events", java.util.List.of()));

    assertEquals("java-" + expected, header.get());
  }
}
