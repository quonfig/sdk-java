package com.quonfig.sdk.telemetry;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.quonfig.sdk.Version;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

/**
 * Posts telemetry envelopes to {@code POST /api/v1/telemetry/} on api-telemetry.
 *
 * <p>Auth is HTTP Basic with {@code 1:&lt;sdkKey&gt;} (matching the existing HTTP transport). The
 * SDK's {@link TelemetryReporter} uses the asynchronous byte-level POST, which reports the status
 * and {@code Retry-After} instead of throwing, so the reporter can apply the telemetry transport
 * policy. {@link #send(Map)} keeps its historic contract: it throws {@link IOException} on a
 * non-2xx response or a transport error.
 *
 * <p>Defaults: 15s overall deadline per POST, 5s connect timeout (qfg-y8je.9).
 */
public final class HttpTelemetrySender implements TelemetrySender {
  static final int BODY_SNIPPET_CHARS = 1024;

  private final HttpClient client;
  private final URI endpoint;
  private final String authHeader;
  private final Duration timeout;
  private final ObjectMapper mapper = new ObjectMapper();

  public HttpTelemetrySender(String telemetryUrl, String sdkKey) {
    this(telemetryUrl, sdkKey, Duration.ofSeconds(15), Duration.ofSeconds(5));
  }

  /**
   * @param timeout overall deadline for one POST, request start to response end
   * @param connectTimeout TCP connect + TLS handshake deadline
   */
  public HttpTelemetrySender(
      String telemetryUrl, String sdkKey, Duration timeout, Duration connectTimeout) {
    this(buildClient(connectTimeout), telemetryUrl, sdkKey, timeout);
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
    TelemetryHttpResult res;
    try {
      res = postAsync(body, timeout).get();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IOException("interrupted while posting telemetry", e);
    } catch (ExecutionException e) {
      Throwable cause = e.getCause();
      if (cause instanceof IOException) throw (IOException) cause;
      throw new IOException("telemetry POST failed", cause);
    }
    int sc = res.status();
    if (sc < 200 || sc >= 300) {
      throw new IOException("telemetry POST returned HTTP " + sc);
    }
  }

  /**
   * One POST of already-serialized bytes. Completes with the HTTP outcome, or exceptionally on a
   * transport error or when the JDK's own request timeout fires. Cancelling the returned future
   * aborts the request.
   */
  CompletableFuture<TelemetryHttpResult> postAsync(byte[] body, Duration requestTimeout) {
    HttpRequest req =
        HttpRequest.newBuilder(endpoint)
            .timeout(requestTimeout)
            .header("Content-Type", "application/json")
            .header("Authorization", authHeader)
            .header("X-Quonfig-SDK-Version", Version.header())
            .POST(HttpRequest.BodyPublishers.ofByteArray(body))
            .build();
    CompletableFuture<HttpResponse<String>> raw =
        client.sendAsync(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    CompletableFuture<TelemetryHttpResult> out =
        new CompletableFuture<>() {
          @Override
          public boolean cancel(boolean mayInterruptIfRunning) {
            raw.cancel(mayInterruptIfRunning);
            return super.cancel(mayInterruptIfRunning);
          }
        };
    raw.whenComplete(
        (resp, err) -> {
          if (err != null) {
            out.completeExceptionally(err);
            return;
          }
          String text = resp.body() == null ? "" : resp.body();
          if (text.length() > BODY_SNIPPET_CHARS) text = text.substring(0, BODY_SNIPPET_CHARS);
          out.complete(
              new TelemetryHttpResult(
                  resp.statusCode(), resp.headers().firstValue("Retry-After").orElse(null), text));
        });
    return out;
  }

  /** The full telemetry endpoint URL, for log lines. */
  String endpoint() {
    return endpoint.toString();
  }

  /** The client's connect timeout in ms, or -1 when unset. */
  long connectTimeoutMs() {
    return client.connectTimeout().map(Duration::toMillis).orElse(-1L);
  }

  private static HttpClient buildClient(Duration connectTimeout) {
    return HttpClient.newBuilder().connectTimeout(connectTimeout).build();
  }
}
