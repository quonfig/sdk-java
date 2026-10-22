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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

  /**
   * At most this many bytes of a non-2xx body are ever read, and they are what {@link
   * HttpTransportException#bodyExcerpt()} exposes. Never logged, never in a message (size-caps
   * rule, qfg-6vno.20.2; was 8 KiB, read after buffering the whole body).
   */
  static final int MAX_BODY_EXCERPT = 1024;

  /**
   * Size-caps rule (ITD supervisor contract, qfg-6vno.20.2): a 2xx body over this many bytes is a
   * failed leg, exactly like a 5xx. A {@code Content-Length} over the cap fails before the body is
   * read; otherwise reading stops at cap + 1. A constant, not an option.
   */
  static final int MAX_CONFIG_BODY_BYTES = 16 * 1024 * 1024;

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
  private final Logger log;

  /**
   * Per base URL: whether that leg is in an oversized-body episode. The first oversized body of an
   * episode logs one WARN, repeats log at DEBUG; the next accepted 2xx from that leg ends it.
   */
  private final AtomicBoolean[] oversizedEpisode;

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
    this.log = b.logger != null ? b.logger : LoggerFactory.getLogger(HttpTransport.class);
    this.oversizedEpisode = new AtomicBoolean[baseUrls.size()];
    for (int i = 0; i < oversizedEpisode.length; i++) oversizedEpisode[i] = new AtomicBoolean();
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
    return get(url, etag, null);
  }

  /**
   * Like {@link #get(URI, String)}, but a 2xx whose body {@code acceptBody} rejects (returns {@code
   * false} or throws) is treated as a leg error rather than a success: the walk fails over to the
   * next base URL exactly as it would on a 5xx, and on the last URL the future completes
   * exceptionally with {@link HttpTransportException}. {@link #lastResolvedIndex()} is only stamped
   * for an accepted body. A 304 is never passed to the validator. {@code null} accepts every body
   * (qfg-9dxb.3).
   */
  public CompletableFuture<HttpResponse<String>> get(
      URI url, String etag, Predicate<String> acceptBody) {
    return get(url, etag, acceptBody, null);
  }

  /**
   * Like {@link #get(URI, String, Predicate)}, and also reports each leg that fails during the
   * failover walk to {@code onLegFailed} as it fails: the transport error (a request timeout is a
   * {@link java.net.http.HttpTimeoutException}), or an {@link HttpTransportException} for an
   * unusable status or a rejected body. Lets the init loop tell a leg that answered fast from one
   * still in flight when initTimeout cuts the attempt. {@code null} reports nothing.
   */
  public CompletableFuture<HttpResponse<String>> get(
      URI url, String etag, Predicate<String> acceptBody, Consumer<Throwable> onLegFailed) {
    Objects.requireNonNull(url, "url");
    return tryFrom(url, etag, "GET", null, 0, acceptBody, new int[baseUrls.size()], onLegFailed)
        .orTimeout(timeout.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
  }

  /**
   * Number of configured base URLs (legs). The parallel-failover hedge fires the primary (leg 0)
   * first and, only if it is slow or errors, also the secondary (leg 1) in parallel.
   */
  public int legCount() {
    return baseUrls.size();
  }

  /**
   * GETs {@code url} (path/query only) from a SINGLE leg — {@code baseUrls.get(legIndex)} — without
   * the sequential idx+1 failover walk of {@link #get(URI, String)}. This is the per-leg primitive
   * the parallel-failover hedge fires for leg 0 and leg 1 independently, so each leg has its own
   * in-flight {@link CompletableFuture} and its own deadline; the hedge orchestrates which legs run
   * (see {@code Quonfig.runInit}). On a 2xx/304 the future completes with the response (and {@link
   * #lastResolvedIndex} is NOT touched — the caller knows which leg it asked for); any other status
   * or a transport error completes the future exceptionally with {@link HttpTransportException}.
   *
   * <p>Unlike {@link #get(URI, String)} this does NOT apply the overall {@link #timeout}; the hedge
   * caller bounds each leg with its own per-leg abort deadline (the JDK {@code HttpRequest.timeout}
   * is {@code configFetchTimeout} here; the overload taking a request timeout replaces it). ETag is
   * a per-call parameter, so two concurrent legs cannot share a mutable ETag slot — there is no
   * 304-mask data race.
   */
  public CompletableFuture<HttpResponse<String>> getFrom(int legIndex, URI url, String etag) {
    return getFrom(legIndex, url, etag, null);
  }

  /**
   * Like {@link #getFrom(int, URI, String)}, but the JDK request timeout is {@code requestTimeout}
   * instead of the per-URL {@code configFetchTimeout}. The init hedge passes its per-leg abort, so
   * a leg is bounded by the hedge abort (design default 6s) rather than the shorter sequential
   * per-URL deadline. {@code null} keeps {@code configFetchTimeout}.
   */
  public CompletableFuture<HttpResponse<String>> getFrom(
      int legIndex, URI url, String etag, Duration requestTimeout) {
    Objects.requireNonNull(url, "url");
    if (legIndex < 0 || legIndex >= baseUrls.size()) {
      return CompletableFuture.failedFuture(
          new HttpTransportException(
              0, "", "leg index " + legIndex + " out of range (legs=" + baseUrls.size() + ")"));
    }
    URI target = rebase(baseUrls.get(legIndex), url);
    HttpRequest req =
        buildRequest(
            target,
            "GET",
            null,
            etag,
            requestTimeout != null && requestTimeout.compareTo(Duration.ZERO) > 0
                ? requestTimeout
                : configFetchTimeout);
    return http.sendAsync(req, CappedBody.HANDLER)
        .handle(
            (resp, t) -> {
              if (t != null) {
                Throwable cause = t.getCause() != null ? t.getCause() : t;
                if (cause instanceof BodyTooLargeException) {
                  int status = ((BodyTooLargeException) cause).statusCode();
                  noteOversized(legIndex, target);
                  return CompletableFuture.<HttpResponse<String>>failedFuture(
                      new HttpTransportException(
                          status,
                          "",
                          "HTTP " + status + " from " + redact(target) + ": " + cause.getMessage(),
                          cause));
                }
                return CompletableFuture.<HttpResponse<String>>failedFuture(t);
              }
              int sc = resp.statusCode();
              if ((sc >= 200 && sc < 300) || sc == 304) {
                if (sc != 304) oversizedEpisode[legIndex].set(false);
                return CompletableFuture.completedFuture(resp);
              }
              return CompletableFuture.<HttpResponse<String>>failedFuture(
                  new HttpTransportException(
                      sc, resp.body(), "HTTP " + sc + " from " + redact(target)));
            })
        .thenCompose(f -> f);
  }

  /** POSTs {@code body} to {@code url}. Same path/host semantics as {@link #get(URI, String)}. */
  public CompletableFuture<HttpResponse<String>> post(URI url, String body) {
    Objects.requireNonNull(url, "url");
    Objects.requireNonNull(body, "body");
    return tryFrom(url, null, "POST", body, 0, null, new int[baseUrls.size()], null)
        .orTimeout(timeout.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
  }

  /**
   * One step of the failover walk. {@code legStatuses} records each leg's answer (0 = no response)
   * and rides on the exception raised when every leg failed, so a caller can see the primary's
   * status rather than only the last leg's (qfg-goi1.10.27).
   */
  private CompletableFuture<HttpResponse<String>> tryFrom(
      URI input,
      String etag,
      String method,
      String body,
      int idx,
      Predicate<String> acceptBody,
      int[] legStatuses,
      Consumer<Throwable> onLegFailed) {
    URI target = rebase(baseUrls.get(idx), input);
    HttpRequest req = buildRequest(target, method, body, etag);
    return http.sendAsync(req, CappedBody.HANDLER)
        .handle(
            (resp, t) -> {
              boolean isLast = idx + 1 >= baseUrls.size();
              if (t != null) {
                Throwable cause = t.getCause() != null ? t.getCause() : t;
                if (cause instanceof BodyTooLargeException) {
                  // Over the body cap: a failed leg exactly like a 5xx (size-caps rule). Nothing
                  // was handed to the caller, so nothing installs and no ETag is kept.
                  int status = ((BodyTooLargeException) cause).statusCode();
                  legStatuses[idx] = status;
                  noteOversized(idx, target);
                  HttpTransportException ex =
                      new HttpTransportException(
                          status,
                          "",
                          "HTTP " + status + " from " + redact(target) + ": " + cause.getMessage(),
                          cause,
                          legStatuses);
                  reportLegFailed(onLegFailed, ex);
                  if (isLast) {
                    return CompletableFuture.<HttpResponse<String>>failedFuture(ex);
                  }
                  return tryFrom(
                      input, etag, method, body, idx + 1, acceptBody, legStatuses, onLegFailed);
                }
                reportLegFailed(onLegFailed, cause);
                if (isLast) {
                  HttpTransportException ex =
                      new HttpTransportException(
                          0,
                          "",
                          "transport error contacting " + redact(target) + ": " + cause,
                          cause,
                          legStatuses);
                  return CompletableFuture.<HttpResponse<String>>failedFuture(ex);
                }
                return tryFrom(
                    input, etag, method, body, idx + 1, acceptBody, legStatuses, onLegFailed);
              }
              int sc = resp.statusCode();
              legStatuses[idx] = sc;
              if (sc >= 200 && sc < 300) {
                if (acceptBody != null && !accepts(acceptBody, resp.body())) {
                  // A 2xx the caller can't use (e.g. not a config envelope) is a leg error, so the
                  // walk fails over just as it would on a 5xx (qfg-9dxb.3).
                  HttpTransportException ex =
                      new HttpTransportException(
                          sc,
                          excerpt(resp.body()),
                          "HTTP " + sc + " from " + redact(target) + ": rejected body",
                          null,
                          legStatuses);
                  reportLegFailed(onLegFailed, ex);
                  if (isLast) {
                    return CompletableFuture.<HttpResponse<String>>failedFuture(ex);
                  }
                  return tryFrom(
                      input, etag, method, body, idx + 1, acceptBody, legStatuses, onLegFailed);
                }
                lastResolvedIndex = idx;
                oversizedEpisode[idx].set(false);
                return CompletableFuture.completedFuture(resp);
              }
              if ("GET".equals(method) && sc == 304) {
                lastResolvedIndex = idx;
                return CompletableFuture.completedFuture(resp);
              }
              HttpTransportException ex =
                  new HttpTransportException(
                      sc, resp.body(), "HTTP " + sc + " from " + redact(target), null, legStatuses);
              reportLegFailed(onLegFailed, ex);
              if (isLast) {
                return CompletableFuture.<HttpResponse<String>>failedFuture(ex);
              }
              return tryFrom(
                  input, etag, method, body, idx + 1, acceptBody, legStatuses, onLegFailed);
            })
        .thenCompose(f -> f);
  }

  /** One WARN per oversized episode on leg {@code idx}; repeats at DEBUG (size-caps rule). */
  private void noteOversized(int idx, URI target) {
    if (oversizedEpisode[idx].compareAndSet(false, true)) {
      log.warn(
          "[quonfig] config response from {} exceeds the maximum size of {} bytes; failing this"
              + " leg (nothing installed)",
          redact(target),
          MAX_CONFIG_BODY_BYTES);
    } else {
      log.debug(
          "[quonfig] config response from {} still exceeds the maximum size of {} bytes",
          redact(target),
          MAX_CONFIG_BODY_BYTES);
    }
  }

  private static void reportLegFailed(Consumer<Throwable> onLegFailed, Throwable cause) {
    if (onLegFailed == null) return;
    try {
      onLegFailed.accept(cause);
    } catch (RuntimeException ignored) {
      // A listener must never break the failover walk.
    }
  }

  private static boolean accepts(Predicate<String> acceptBody, String body) {
    try {
      return acceptBody.test(body);
    } catch (RuntimeException e) {
      return false;
    }
  }

  private HttpRequest buildRequest(URI target, String method, String body, String etag) {
    return buildRequest(target, method, body, etag, configFetchTimeout);
  }

  private HttpRequest buildRequest(
      URI target, String method, String body, String etag, Duration requestTimeout) {
    // Per-URL deadline: bound THIS single attempt (one base URL) rather than the whole failover
    // chain, so a hung primary aborts after configFetchTimeout and the secondary still has budget
    // inside the caller's overall timeout. Applies uniformly to the initial fetch and the fallback
    // poller, since both route through this transport (qfg-7h5d.1.4/.10).
    HttpRequest.Builder b =
        HttpRequest.newBuilder(target)
            .timeout(requestTimeout)
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

  /**
   * {@code target} for an error message, with any URL userinfo ({@code user:pass@}) removed, so a
   * credential embedded in a configured base URL never reaches a log line or an exception message
   * (design doc decision 9).
   */
  static String redact(URI target) {
    if (target.getRawUserInfo() == null) return target.toString();
    try {
      return new URI(
              target.getScheme(),
              null,
              target.getHost(),
              target.getPort(),
              target.getPath(),
              target.getQuery(),
              target.getFragment())
          .toString();
    } catch (java.net.URISyntaxException e) {
      return target.getScheme() + "://" + target.getHost();
    }
  }

  /**
   * A rejected 2xx body is already in memory (under the body cap); keep at most {@link
   * #MAX_BODY_EXCERPT} UTF-8 bytes of it for {@link HttpTransportException#bodyExcerpt()}.
   */
  private static String excerpt(String body) {
    if (body == null) return "";
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    if (bytes.length <= MAX_BODY_EXCERPT) return body;
    return CappedBody.decodePrefix(bytes, MAX_BODY_EXCERPT);
  }

  /** Builder for {@link HttpTransport}. */
  public static final class Builder {
    private List<URI> urls;
    private String sdkKey;
    private Duration timeout;
    private Duration configFetchTimeout;
    private HttpClient httpClient;
    private Logger logger;

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

    /** Test seam: where the size-cap WARN goes. Defaults to this class's SLF4J logger. */
    Builder logger(Logger logger) {
      this.logger = logger;
      return this;
    }

    public HttpTransport build() {
      return new HttpTransport(this);
    }
  }
}
