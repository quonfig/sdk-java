package com.quonfig.sdk.transport;

import com.quonfig.sdk.Version;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * HTTP transport for api-delivery (config fetch + GETs with ETag) and api-telemetry (POST). Uses
 * the JDK's {@link HttpClient} directly so the SDK has zero runtime HTTP dependencies.
 *
 * <p>Auth: HTTP Basic with username {@code 1}, password = {@code sdkKey} — matches sdk-node and
 * sdk-go.
 *
 * <p>Failover: each call walks {@link Builder#urls(List)} in order, returning on the first 2xx (and
 * 304 on GET). A non-2xx (other than 304 on GET) or a socket failure on the last URL raises {@link
 * HttpTransportException}.
 *
 * <p>The {@code url} passed to {@link #get(URI, String)} / {@link #post(URI, String)} contributes
 * its path and query — the scheme/host/port from the configured base URLs decides where each
 * attempt actually goes. This is what lets a single call rotate from primary to secondary without
 * the caller knowing which host is which.
 */
public final class HttpTransport {

  /** Body excerpt is capped to keep error allocations bounded. */
  static final int MAX_BODY_EXCERPT = 8192;

  /**
   * Default per-URL config-fetch deadline. Bounds a single attempt against one base URL so a hung
   * primary (TCP accepted, no response) aborts fast instead of starving the secondary until the
   * caller's overall {@code initTimeout}. ~3s mirrors the sdk-go pilot's {@code
   * DefaultConfigFetchTimeout} (qfg-7h5d.1.4/.10).
   */
  public static final Duration DEFAULT_CONFIG_FETCH_TIMEOUT = Duration.ofSeconds(3);

  private final HttpClient http;
  private final List<URI> baseUrls;
  private final String authHeader;
  private final Duration timeout;
  private final Duration configFetchTimeout;

  /**
   * Index into {@link #baseUrls} of the base URL that produced the most recent successful (2xx/304)
   * response, or {@code -1} before any. The Quonfig client reads this right after a fetch resolves
   * to record which leg ("primary"/"secondary") served the held config. Single writer per resolve;
   * {@code volatile} is sufficient for the read-after-resolve handoff.
   */
  private volatile int lastResolvedIndex = -1;

  private HttpTransport(Builder b) {
    Objects.requireNonNull(b.urls, "urls");
    if (b.urls.isEmpty()) {
      throw new IllegalArgumentException("urls must not be empty");
    }
    Objects.requireNonNull(b.sdkKey, "sdkKey");
    this.baseUrls = List.copyOf(b.urls);
    this.timeout = b.timeout != null ? b.timeout : Duration.ofSeconds(10);
    this.configFetchTimeout =
        b.configFetchTimeout != null ? b.configFetchTimeout : DEFAULT_CONFIG_FETCH_TIMEOUT;
    this.http =
        b.httpClient != null
            ? b.httpClient
            : HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    String creds = "1:" + b.sdkKey;
    this.authHeader =
        "Basic " + Base64.getEncoder().encodeToString(creds.getBytes(StandardCharsets.UTF_8));
  }

  public static Builder builder() {
    return new Builder();
  }

  /**
   * Index into the configured base URLs of the leg that produced the most recent successful
   * response, or {@code -1} before the first success. {@code 0} is the primary; any higher index
   * was reached via failover. The Quonfig client reads this immediately after a config fetch
   * resolves.
   */
  public int lastResolvedIndex() {
    return lastResolvedIndex;
  }

  /**
   * GETs {@code url} (path/query only — host comes from the configured base URLs). When {@code
   * etag} is non-null, sends {@code If-None-Match}; a 304 response is returned to the caller as a
   * regular {@link HttpResponse}, not raised.
   */
  public CompletableFuture<HttpResponse<String>> get(URI url, String etag) {
    Objects.requireNonNull(url, "url");
    return tryFrom(url, etag, "GET", null, 0)
        .orTimeout(timeout.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
  }

  /** POSTs {@code body} to {@code url}. Same path/host semantics as {@link #get(URI, String)}. */
  public CompletableFuture<HttpResponse<String>> post(URI url, String body) {
    Objects.requireNonNull(url, "url");
    Objects.requireNonNull(body, "body");
    return tryFrom(url, null, "POST", body, 0)
        .orTimeout(timeout.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
  }

  private CompletableFuture<HttpResponse<String>> tryFrom(
      URI input, String etag, String method, String body, int idx) {
    URI target = rebase(baseUrls.get(idx), input);
    HttpRequest req = buildRequest(target, method, body, etag);
    return http.sendAsync(req, HttpResponse.BodyHandlers.ofString())
        .handle(
            (resp, t) -> {
              boolean isLast = idx + 1 >= baseUrls.size();
              if (t != null) {
                if (isLast) {
                  Throwable cause = t.getCause() != null ? t.getCause() : t;
                  HttpTransportException ex =
                      new HttpTransportException(
                          0, "", "transport error contacting " + target + ": " + cause, cause);
                  return CompletableFuture.<HttpResponse<String>>failedFuture(ex);
                }
                return tryFrom(input, etag, method, body, idx + 1);
              }
              int sc = resp.statusCode();
              if (sc >= 200 && sc < 300) {
                lastResolvedIndex = idx;
                return CompletableFuture.completedFuture(resp);
              }
              if ("GET".equals(method) && sc == 304) {
                lastResolvedIndex = idx;
                return CompletableFuture.completedFuture(resp);
              }
              if (isLast) {
                String excerpt = excerpt(resp.body());
                HttpTransportException ex =
                    new HttpTransportException(sc, excerpt, "HTTP " + sc + " from " + target);
                return CompletableFuture.<HttpResponse<String>>failedFuture(ex);
              }
              return tryFrom(input, etag, method, body, idx + 1);
            })
        .thenCompose(f -> f);
  }

  private HttpRequest buildRequest(URI target, String method, String body, String etag) {
    // Per-URL deadline: bound THIS single attempt (one base URL) rather than the whole failover
    // chain, so a hung primary aborts after configFetchTimeout and the secondary still has budget
    // inside the caller's overall timeout. Applies uniformly to the initial fetch and the fallback
    // poller, since both route through this transport (qfg-7h5d.1.4/.10).
    HttpRequest.Builder b =
        HttpRequest.newBuilder(target)
            .timeout(configFetchTimeout)
            .header("Authorization", authHeader)
            .header("X-Quonfig-SDK-Version", Version.header())
            .header("Accept", "application/json");
    if ("GET".equals(method)) {
      if (etag != null && !etag.isEmpty()) {
        b.header("If-None-Match", etag);
      }
      b.GET();
    } else {
      b.header("Content-Type", "application/json");
      b.POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
    }
    return b.build();
  }

  /**
   * Combines the host/port/scheme from {@code base} with the path/query from {@code input}. If
   * {@code input} has no path (e.g. opaque URI), only the base is used.
   */
  static URI rebase(URI base, URI input) {
    String baseStr = base.toString();
    if (baseStr.endsWith("/")) {
      baseStr = baseStr.substring(0, baseStr.length() - 1);
    }
    String path = input.getRawPath();
    if (path == null || path.isEmpty()) {
      path = "";
    }
    String query = input.getRawQuery();
    StringBuilder sb = new StringBuilder(baseStr).append(path);
    if (query != null) {
      sb.append('?').append(query);
    }
    return URI.create(sb.toString());
  }

  private static String excerpt(String body) {
    if (body == null) return "";
    if (body.length() <= MAX_BODY_EXCERPT) return body;
    return body.substring(0, MAX_BODY_EXCERPT);
  }

  /** Builder for {@link HttpTransport}. */
  public static final class Builder {
    private List<URI> urls;
    private String sdkKey;
    private Duration timeout;
    private Duration configFetchTimeout;
    private HttpClient httpClient;

    public Builder urls(List<URI> urls) {
      this.urls = urls;
      return this;
    }

    public Builder sdkKey(String sdkKey) {
      this.sdkKey = sdkKey;
      return this;
    }

    public Builder timeout(Duration timeout) {
      this.timeout = timeout;
      return this;
    }

    /**
     * Per-URL config-fetch deadline. Bounds each individual base-URL attempt; defaults to {@link
     * HttpTransport#DEFAULT_CONFIG_FETCH_TIMEOUT} (~3s) when null. Keep this well under the
     * caller's overall init timeout so a hung leg fails over with budget to spare.
     */
    public Builder configFetchTimeout(Duration configFetchTimeout) {
      this.configFetchTimeout = configFetchTimeout;
      return this;
    }

    public Builder httpClient(HttpClient httpClient) {
      this.httpClient = httpClient;
      return this;
    }

    public HttpTransport build() {
      return new HttpTransport(this);
    }
  }
}
