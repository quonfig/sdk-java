package com.quonfig.sdk.telemetry;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;

/**
 * Posts telemetry envelopes to {@code POST /api/v1/telemetry/} on api-telemetry.
 *
 * <p>Auth is HTTP Basic with {@code 1:&lt;sdkKey&gt;} (matching the existing HTTP transport). On
 * non-2xx responses or transport errors, throws {@link IOException} so the reporter applies its
 * backoff policy.
 */
public final class HttpTelemetrySender implements TelemetrySender {
  private final HttpClient client;
  private final URI endpoint;
  private final String authHeader;
  private final Duration timeout;
  private final ObjectMapper mapper = new ObjectMapper();

  public HttpTelemetrySender(String telemetryUrl, String sdkKey) {
    this(buildDefaultClient(), telemetryUrl, sdkKey, Duration.ofSeconds(30));
  }

  public HttpTelemetrySender(
      HttpClient client, String telemetryUrl, String sdkKey, Duration timeout) {
    this.client = client;
    String base = telemetryUrl.endsWith("/") ? telemetryUrl : telemetryUrl + "/";
    String url = base + "api/v1/telemetry/";
    this.endpoint = URI.create(url);
    String creds = "1:" + (sdkKey == null ? "" : sdkKey);
    this.authHeader =
        "Basic " + Base64.getEncoder().encodeToString(creds.getBytes(StandardCharsets.UTF_8));
    this.timeout = timeout;
  }

  @Override
  public void send(Map<String, Object> payload) throws IOException {
    byte[] body = mapper.writeValueAsBytes(payload);
    HttpRequest req =
        HttpRequest.newBuilder(endpoint)
            .timeout(timeout)
            .header("Content-Type", "application/json")
            .header("Authorization", authHeader)
            .header("X-Quonfig-SDK-Version", "java-0.0.1")
            .POST(HttpRequest.BodyPublishers.ofByteArray(body))
            .build();
    HttpResponse<Void> resp;
    try {
      resp = client.send(req, HttpResponse.BodyHandlers.discarding());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IOException("interrupted while posting telemetry", e);
    }
    int sc = resp.statusCode();
    if (sc < 200 || sc >= 300) {
      throw new IOException("telemetry POST returned HTTP " + sc);
    }
  }

  private static HttpClient buildDefaultClient() {
    return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
  }
}
