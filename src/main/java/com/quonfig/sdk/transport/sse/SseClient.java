package com.quonfig.sdk.transport.sse;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * SSE client for real-time config updates from api-delivery (GET /api/v2/sse/config).
 *
 * <p>Ported from sdk-go/sse_client.go using the JDK's {@link HttpClient}. The wire format we
 * consume — id/data lines plus ":" comment keepalives, single-line JSON envelopes — is trivial
 * enough that a small line-based parser is clearer than pulling in an SSE library.
 *
 * <p>Auth mirrors the rest of the SDK: HTTP Basic with user="1", password=apiKey, plus
 * X-Quonfig-SDK-Version and Accept: text/event-stream.
 *
 * <p>Reconnect policy: exponential backoff (initialDelay → maxDelay) with jitter, reset on a
 * successful 200 response. The background daemon thread lives until {@link #stop()}.
 *
 * <p>This class is internal to the SDK; the public client API will wrap it.
 */
public final class SseClient {

  private static final Logger log = LoggerFactory.getLogger(SseClient.class);

  private final Config cfg;
  private final HttpClient http;
  private final AtomicBoolean started = new AtomicBoolean();
  private final AtomicBoolean stopped = new AtomicBoolean();
  private volatile Thread loopThread;
  private volatile boolean connected;

  // Tracks the currently-open SSE body stream so stop() can force a read to
  // unblock. BufferedReader.readLine() on a socket-backed stream does NOT
  // respond to Thread.interrupt(); the only reliable wakeup is to close the
  // underlying InputStream.
  private volatile InputStream activeBody;

  private SseClient(Config cfg) {
    this.cfg = Objects.requireNonNull(cfg, "cfg");
    Objects.requireNonNull(cfg.url, "url");
    Objects.requireNonNull(cfg.apiKey, "apiKey");
    Objects.requireNonNull(cfg.onEnvelope, "onEnvelope");
    this.http =
        cfg.httpClient != null
            ? cfg.httpClient
            // No read timeout — SSE streams are long-lived. Cancellation comes from
            // interrupting the loop thread, which closes the underlying socket.
            : HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
  }

  public static Builder builder() {
    return new Builder();
  }

  /** Starts the background reconnect loop. Idempotent. */
  public void start() {
    if (!started.compareAndSet(false, true)) {
      return;
    }
    Thread t = new Thread(this::runLoop, "quonfig-sse");
    t.setDaemon(true);
    loopThread = t;
    t.start();
  }

  /**
   * Cancels the in-flight connection and waits up to 5s for the loop to exit. Idempotent and safe
   * to call before {@link #start()}.
   */
  public void stop() {
    if (!stopped.compareAndSet(false, true)) {
      return;
    }
    Thread t = loopThread;
    if (t == null) {
      return;
    }
    t.interrupt();
    InputStream body = activeBody;
    if (body != null) {
      try {
        body.close();
      } catch (IOException ignored) {
        // Best-effort; the read in the loop thread will see EOF or IOException.
      }
    }
    try {
      t.join(5000);
    } catch (InterruptedException ignored) {
      Thread.currentThread().interrupt();
    }
  }

  private void runLoop() {
    Duration delay = cfg.initialDelay;
    try {
      while (!stopped.get() && !Thread.currentThread().isInterrupted()) {
        boolean connectedOK = connectOnce();
        if (stopped.get() || Thread.currentThread().isInterrupted()) {
          return;
        }

        if (connectedOK) {
          // We had a live stream that ended — reset backoff. Server-initiated
          // close is normal (LB recycles connections); don't punish it.
          delay = cfg.initialDelay;
        }

        // Jittered sleep: delay/2 + rand(0..delay/2).
        long delayNanos = delay.toNanos();
        long jitterNanos = ThreadLocalRandom.current().nextLong(delayNanos + 1);
        long sleepNanos = delayNanos / 2 + jitterNanos / 2;
        try {
          Thread.sleep(sleepNanos / 1_000_000L, (int) (sleepNanos % 1_000_000L));
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          return;
        }

        if (!connectedOK) {
          long doubled = Math.min(delay.toNanos() * 2, cfg.maxDelay.toNanos());
          delay = Duration.ofNanos(doubled);
        }
      }
    } finally {
      setConnected(false);
    }
  }

  /**
   * Opens a single SSE request and reads until the body errors or the thread is interrupted.
   * Returns true iff response headers came back 200 OK (the connection was "live" at some point).
   * Callers use this to distinguish backoff-worthy failures (DNS, refused, 401) from normal session
   * recycling.
   */
  private boolean connectOnce() {
    HttpRequest req;
    try {
      String basic =
          Base64.getEncoder().encodeToString(("1:" + cfg.apiKey).getBytes(StandardCharsets.UTF_8));
      HttpRequest.Builder b =
          HttpRequest.newBuilder()
              .uri(URI.create(cfg.url))
              .GET()
              .header("Authorization", "Basic " + basic)
              .header("Accept", "text/event-stream")
              .header("Cache-Control", "no-cache");
      if (cfg.userAgent != null && !cfg.userAgent.isEmpty()) {
        b.header("X-Quonfig-SDK-Version", cfg.userAgent);
      }
      req = b.build();
    } catch (IllegalArgumentException e) {
      log.warn("SSE: bad URL {}: {}", cfg.url, e.getMessage());
      return false;
    }

    HttpResponse<InputStream> resp;
    try {
      resp = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
    } catch (IOException | InterruptedException e) {
      if (e instanceof InterruptedException) {
        Thread.currentThread().interrupt();
      }
      return false;
    }

    InputStream body = resp.body();
    activeBody = body;
    try {
      if (resp.statusCode() != 200) {
        // Drain a bit so the connection can be reused, then bail. Treat 401/403
        // the same as a network failure — a key rotation will be picked up on
        // the next reconnect.
        try {
          body.readNBytes(4096);
        } catch (IOException ignored) {
          // ignored
        }
        return false;
      }
      setConnected(true);
      try {
        parseStream(body);
      } finally {
        setConnected(false);
      }
      return true;
    } catch (IOException e) {
      // Stream errored mid-read — treat as a normal recycling event so the
      // backoff stays at initial.
      return true;
    } finally {
      activeBody = null;
      try {
        body.close();
      } catch (IOException ignored) {
        // ignored
      }
    }
  }

  /**
   * Reads SSE frames from {@code in} and calls onEnvelope for each complete event. Handles a
   * minimal subset of the SSE spec sufficient for api-delivery:
   *
   * <ul>
   *   <li>Lines starting with "data:" accumulate the per-event payload.
   *   <li>Lines starting with ":" are comments (keepalives) — ignored.
   *   <li>"id:" is recognized but unused (no last-event-id resume yet).
   *   <li>An empty line terminates the event: the buffer is parsed as a JSON envelope.
   * </ul>
   */
  private void parseStream(InputStream in) throws IOException {
    BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
    StringBuilder dataBuf = new StringBuilder();
    String line;
    while ((line = reader.readLine()) != null) {
      if (Thread.currentThread().isInterrupted()) {
        return;
      }
      if (line.isEmpty()) {
        flush(dataBuf);
        continue;
      }
      if (line.charAt(0) == ':') {
        continue;
      }
      String rest = stripFieldPrefix(line, "data:");
      if (rest != null) {
        if (dataBuf.length() > 0) {
          dataBuf.append('\n');
        }
        dataBuf.append(rest);
      }
      // "id:", "event:", "retry:" intentionally ignored.
    }
  }

  private void flush(StringBuilder dataBuf) {
    if (dataBuf.length() == 0) {
      return;
    }
    String payload = dataBuf.toString();
    dataBuf.setLength(0);
    try {
      JsonNode env = MAPPER.readTree(payload);
      cfg.onEnvelope.accept(env);
    } catch (IOException e) {
      // Malformed payload — swallow so a single bad event doesn't tear down
      // the stream. The HTTP poller is the safety net.
      log.warn("SSE: discarding malformed envelope: {}", e.getMessage());
    } catch (RuntimeException e) {
      log.warn("SSE: onEnvelope callback threw: {}", e.getMessage());
    }
  }

  /** Returns the field value if {@code line} starts with {@code prefix} (with optional space). */
  private static String stripFieldPrefix(String line, String prefix) {
    if (!line.startsWith(prefix)) {
      return null;
    }
    String rest = line.substring(prefix.length());
    if (!rest.isEmpty() && rest.charAt(0) == ' ') {
      rest = rest.substring(1);
    }
    return rest;
  }

  private synchronized void setConnected(boolean v) {
    if (connected == v) {
      return;
    }
    connected = v;
    Consumer<Boolean> cb = cfg.onStateChange;
    if (cb != null) {
      try {
        cb.accept(v);
      } catch (RuntimeException e) {
        log.warn("SSE: onStateChange callback threw: {}", e.getMessage());
      }
    }
  }

  private static final ObjectMapper MAPPER = new ObjectMapper();

  /** Configuration captured by {@link Builder#build()}. */
  static final class Config {
    final String url;
    final String apiKey;
    final String userAgent;
    final Consumer<JsonNode> onEnvelope;
    final Consumer<Boolean> onStateChange;
    final Duration initialDelay;
    final Duration maxDelay;
    final HttpClient httpClient;

    Config(Builder b) {
      this.url = b.url;
      this.apiKey = b.apiKey;
      this.userAgent = b.userAgent;
      this.onEnvelope = b.onEnvelope;
      this.onStateChange = b.onStateChange;
      this.initialDelay = b.initialDelay != null ? b.initialDelay : Duration.ofMillis(500);
      this.maxDelay = b.maxDelay != null ? b.maxDelay : Duration.ofSeconds(30);
      this.httpClient = b.httpClient;
    }
  }

  /** Builder for {@link SseClient}. */
  public static final class Builder {
    private String url;
    private String apiKey;
    private String userAgent;
    private Consumer<JsonNode> onEnvelope;
    private Consumer<Boolean> onStateChange;
    private Duration initialDelay;
    private Duration maxDelay;
    private HttpClient httpClient;

    public Builder url(String url) {
      this.url = url;
      return this;
    }

    public Builder apiKey(String apiKey) {
      this.apiKey = apiKey;
      return this;
    }

    public Builder userAgent(String userAgent) {
      this.userAgent = userAgent;
      return this;
    }

    public Builder onEnvelope(Consumer<JsonNode> onEnvelope) {
      this.onEnvelope = onEnvelope;
      return this;
    }

    public Builder onStateChange(Consumer<Boolean> onStateChange) {
      this.onStateChange = onStateChange;
      return this;
    }

    public Builder initialDelay(Duration initialDelay) {
      this.initialDelay = initialDelay;
      return this;
    }

    public Builder maxDelay(Duration maxDelay) {
      this.maxDelay = maxDelay;
      return this;
    }

    public Builder httpClient(HttpClient httpClient) {
      this.httpClient = httpClient;
      return this;
    }

    public SseClient build() {
      return new SseClient(new Config(this));
    }
  }
}
