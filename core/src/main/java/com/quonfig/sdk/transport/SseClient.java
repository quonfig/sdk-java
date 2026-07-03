package com.quonfig.sdk.transport;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.quonfig.sdk.Version;
import com.quonfig.sdk.wire.ConfigEnvelope;
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
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * SSE transport for real-time {@link ConfigEnvelope} updates from {@code api-delivery} ({@code GET
 * /api/v2/sse/config}). Default update channel for sdk-java; polling is opt-in.
 *
 * <p>Implementation notes:
 *
 * <ul>
 *   <li>Hand-rolled line parser on {@link HttpClient} ({@link
 *       HttpResponse.BodyHandlers#ofInputStream}). The wire format we consume — {@code id:}/{@code
 *       data:} lines plus {@code :} comment keepalives, single-line JSON envelopes — is small
 *       enough that pulling in an SSE library would obscure rather than clarify.
 *   <li>Auth mirrors {@link HttpTransport}: HTTP Basic with username {@code 1}, password = sdkKey,
 *       plus {@code X-Quonfig-SDK-Version} and {@code Accept: text/event-stream}.
 *   <li>The stream is PINNED to the first entry of {@link Builder#streamUrls(List)} — it never
 *       fails over to a later leg. A downed primary stream is retried forever with backoff;
 *       failover is an HTTP-poll-only property (chaos scenario f05), so a stream herd never lands
 *       on the secondary and freshness is never silently bound to the mirror. Mirrors sdk-go
 *       ({@code startSSE} → {@code streamURLFor(0)}). The path {@code /api/v2/sse/config} is
 *       appended.
 *   <li>Reconnect: exponential backoff (initialDelay → maxDelay) with jitter, reset to initial on a
 *       successful event-bearing connection. Server-initiated disconnects (LB recycling, 30s
 *       comment heartbeats that eventually drop the socket) are non-fatal.
 *   <li>Comments ({@code :keepalive}) are silently dropped — they never trip the connection-state
 *       callback or the envelope handler.
 * </ul>
 *
 * <p>This class is internal to the SDK; the public {@code Quonfig} client wraps it and is
 * responsible for atomically swapping the {@code ConfigStore} + {@code Resolver}/{@code Evaluator}
 * in lockstep when an envelope arrives. The client itself does not own the read-path data.
 */
public final class SseClient {

  private static final Logger log = LoggerFactory.getLogger(SseClient.class);
  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final String SSE_PATH = "/api/v2/sse/config";

  private final List<URI> streamUrls;
  private final String authHeader;
  private final String userAgent;
  private final Duration initialDelay;
  private final Duration maxDelay;
  private final Duration readWatchdog;
  private final HttpClient http;
  // Single-thread scheduler that fires the SSE stall watchdog. Daemon-threaded so it
  // never holds the JVM alive; shut down by stop().
  private final ScheduledExecutorService watchdogExecutor;

  private volatile Consumer<ConfigEnvelope> envelopeHandler;
  private volatile Consumer<Boolean> stateHandler;

  private final AtomicBoolean started = new AtomicBoolean();
  private final AtomicBoolean stopped = new AtomicBoolean();
  private volatile Thread loopThread;
  private volatile boolean connected;

  // Tracks the currently-open SSE body stream so stop() can force a read to
  // unblock. BufferedReader.readLine() on a socket-backed stream does NOT
  // respond to Thread.interrupt(); the only reliable wakeup is to close the
  // underlying InputStream.
  private volatile InputStream activeBody;

  // Index into streamUrls of the leg the stream is connected on right now; -1 while
  // disconnected. Derived from the in-flight connection in connectOnce (not assumed),
  // so Quonfig.sseFailedOverToSecondary() reports real transport state. With the pin
  // in runLoop this is only ever -1 or 0; a reintroduced list-walk would surface here.
  private volatile int connectedStreamIndex = -1;

  private SseClient(Builder b) {
    Objects.requireNonNull(b.streamUrls, "streamUrls");
    if (b.streamUrls.isEmpty()) {
      throw new IllegalArgumentException("streamUrls must not be empty");
    }
    Objects.requireNonNull(b.sdkKey, "sdkKey");
    this.streamUrls = List.copyOf(b.streamUrls);
    this.userAgent = b.userAgent != null ? b.userAgent : Version.header();
    this.initialDelay = b.initialDelay != null ? b.initialDelay : Duration.ofMillis(500);
    this.maxDelay = b.maxDelay != null ? b.maxDelay : Duration.ofSeconds(30);
    // 90s = 3x the api-delivery 30s comment heartbeat (sse.go:67-88).
    this.readWatchdog = b.readWatchdog != null ? b.readWatchdog : Duration.ofSeconds(90);
    this.watchdogExecutor =
        Executors.newSingleThreadScheduledExecutor(
            r -> {
              Thread t = new Thread(r, "quonfig-sse-watchdog");
              t.setDaemon(true);
              return t;
            });
    this.http =
        b.httpClient != null
            ? b.httpClient
            // No read timeout — SSE streams are long-lived. Cancellation comes from
            // closing the active InputStream in stop().
            : HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    String creds = "1:" + b.sdkKey;
    this.authHeader =
        "Basic " + Base64.getEncoder().encodeToString(creds.getBytes(StandardCharsets.UTF_8));
  }

  public static Builder builder() {
    return new Builder();
  }

  /** Registers (or replaces) the handler invoked with each parsed {@link ConfigEnvelope}. */
  public void onEnvelope(Consumer<ConfigEnvelope> handler) {
    this.envelopeHandler = handler;
  }

  /**
   * Registers (or replaces) the handler invoked with {@code true} when a stream connects and {@code
   * false} when it drops. Never called twice in a row with the same value.
   */
  public void onConnectionStateChange(Consumer<Boolean> handler) {
    this.stateHandler = handler;
  }

  /**
   * Index into {@link Builder#streamUrls(List)} of the leg the stream is connected on right now, or
   * {@code -1} while disconnected. Derived from the actual in-flight connection rather than
   * assumed, so a caller asserting the SSE-pinned-to-primary invariant (chaos scenario f05) would
   * observe a non-zero index if stream-leg walking were ever reintroduced. Because {@link #runLoop}
   * pins the stream to the first entry, this only ever returns {@code -1} or {@code 0} by design.
   *
   * <p>Inside an {@link #onConnectionStateChange} {@code connected=true} callback this is
   * guaranteed to reflect the leg that just connected (it is written before the edge fires, on the
   * same thread).
   */
  public int connectedStreamIndex() {
    return connectedStreamIndex;
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
        // Best-effort: the read in the loop thread will see EOF or IOException.
      }
    }
    try {
      t.join(5000);
    } catch (InterruptedException ignored) {
      Thread.currentThread().interrupt();
    }
    // Cancel any pending watchdog and tear down the single scheduler thread so the
    // JVM can exit cleanly after stop(). shutdownNow() is safe even if no task is
    // running, and the executor is single-use (no restart path).
    watchdogExecutor.shutdownNow();
  }

  private void runLoop() {
    Duration delay = initialDelay;
    // PINNED to streamUrls[0]: the stream never walks the rest of the list. Failover is an
    // HTTP-poll-only property (f05) — while the primary stream is down the client retries it
    // forever with backoff and freshness degrades to the HTTP fallback path (which DOES fail
    // over across apiUrls) until the primary stream heals.
    URI primary = streamUrls.get(0);
    try {
      while (!stopped.get() && !Thread.currentThread().isInterrupted()) {
        boolean connectedOK = connectOnce(primary);
        if (stopped.get() || Thread.currentThread().isInterrupted()) {
          return;
        }

        if (connectedOK) {
          // Live stream that ended — reset backoff. Server-initiated close is
          // normal (LB recycles connections); don't punish it.
          delay = initialDelay;
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
          long doubled = Math.min(delay.toNanos() * 2, maxDelay.toNanos());
          delay = Duration.ofNanos(doubled);
        }
      }
    } finally {
      setConnected(false);
    }
  }

  /**
   * Opens a single SSE request against {@code base + /api/v2/sse/config} and reads until the body
   * errors or {@link #stop()} is called. Returns true iff the response came back 200 OK and the
   * stream was actually consumed — callers use this to distinguish backoff-worthy failures (DNS,
   * refused, 4xx, 5xx) from normal session recycling.
   */
  private boolean connectOnce(URI base) {
    URI target = appendPath(base, SSE_PATH);
    HttpRequest req;
    try {
      HttpRequest.Builder b =
          HttpRequest.newBuilder()
              .uri(target)
              .GET()
              .header("Authorization", authHeader)
              .header("Accept", "text/event-stream")
              .header("Cache-Control", "no-cache");
      if (userAgent != null && !userAgent.isEmpty()) {
        b.header("X-Quonfig-SDK-Version", userAgent);
      }
      req = b.build();
    } catch (IllegalArgumentException e) {
      log.warn("SSE: bad URL {}: {}", target, e.getMessage());
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
        // Drain a little so the connection can be reused, then bail. 401/403 are
        // treated like a transport error — a key rotation will be picked up on
        // the next reconnect cycle.
        try {
          body.readNBytes(4096);
        } catch (IOException ignored) {
          // ignored
        }
        return false;
      }
      // Record the ACTUAL leg this connection landed on before firing the connected
      // edge, so state-change handlers (Quonfig records it for the f05 assertion)
      // observe a consistent index. With the runLoop pin this is always 0.
      connectedStreamIndex = streamUrls.indexOf(base);
      setConnected(true);
      try {
        parseStream(body);
      } finally {
        setConnected(false);
        connectedStreamIndex = -1;
      }
      return true;
    } catch (IOException e) {
      // Stream errored mid-read — treat as normal recycling.
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
   * Reads SSE frames from {@code in} and calls {@link #envelopeHandler} for each complete event.
   * Implements the minimal subset of the SSE spec sufficient for api-delivery:
   *
   * <ul>
   *   <li>Lines starting with {@code "data:"} accumulate the per-event payload.
   *   <li>Lines starting with {@code ":"} are comments (keepalives) — ignored.
   *   <li>An empty line terminates the event: the buffered payload is parsed as a {@link
   *       ConfigEnvelope} via Jackson.
   *   <li>{@code id:}, {@code event:}, {@code retry:} are recognized but unused.
   * </ul>
   */
  private void parseStream(InputStream in) throws IOException {
    BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
    StringBuilder dataBuf = new StringBuilder();
    // Stall watchdog. Java's HttpClient has no per-read deadline; we get the same
    // effect by scheduling a task to close the body after readWatchdog of silence.
    // close() unblocks readLine() with EOF or IOException, which falls out to
    // connectOnce's catch and triggers reconnect. Each non-null readLine() — data,
    // keepalive, or otherwise — resets the timer. Keepalives (server emits one every
    // 30s) keep a healthy stream alive against a 90s default.
    AtomicReference<ScheduledFuture<?>> watchdog = new AtomicReference<>();
    try {
      watchdog.set(scheduleWatchdog(in));
      String line;
      while ((line = reader.readLine()) != null) {
        ScheduledFuture<?> prev = watchdog.getAndSet(scheduleWatchdog(in));
        if (prev != null) {
          prev.cancel(false);
        }
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
      }
    } finally {
      ScheduledFuture<?> prev = watchdog.getAndSet(null);
      if (prev != null) {
        prev.cancel(false);
      }
    }
  }

  private ScheduledFuture<?> scheduleWatchdog(InputStream body) {
    return watchdogExecutor.schedule(
        () -> {
          log.warn(
              "SSE: read watchdog fired after {}ms of silence — closing stream to force reconnect",
              readWatchdog.toMillis());
          try {
            body.close();
          } catch (IOException ignored) {
            // Best-effort: the read in the loop will already be unblocked.
          }
        },
        readWatchdog.toNanos(),
        TimeUnit.NANOSECONDS);
  }

  private void flush(StringBuilder dataBuf) {
    if (dataBuf.length() == 0) {
      return;
    }
    String payload = dataBuf.toString();
    dataBuf.setLength(0);
    Consumer<ConfigEnvelope> cb = envelopeHandler;
    if (cb == null) {
      return;
    }
    ConfigEnvelope env;
    try {
      env = MAPPER.readValue(payload, ConfigEnvelope.class);
    } catch (IOException e) {
      // Malformed payload — swallow so a single bad event doesn't tear down
      // the stream. The HTTP poller is the safety net.
      log.warn("SSE: discarding malformed envelope: {}", e.getMessage());
      return;
    }
    try {
      cb.accept(env);
    } catch (RuntimeException e) {
      log.warn("SSE: onEnvelope handler threw: {}", e.getMessage());
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

  /**
   * Joins {@code base} with {@code path}, collapsing exactly one trailing slash on the base. Kept
   * dependency-free so we don't pull in URI builder helpers.
   */
  static URI appendPath(URI base, String path) {
    String s = base.toString();
    if (s.endsWith("/")) {
      s = s.substring(0, s.length() - 1);
    }
    return URI.create(s + path);
  }

  private synchronized void setConnected(boolean v) {
    if (connected == v) {
      return;
    }
    connected = v;
    Consumer<Boolean> cb = stateHandler;
    if (cb != null) {
      try {
        cb.accept(v);
      } catch (RuntimeException e) {
        log.warn("SSE: onConnectionStateChange handler threw: {}", e.getMessage());
      }
    }
  }

  /** Builder for {@link SseClient}. */
  public static final class Builder {
    private List<URI> streamUrls;
    private String sdkKey;
    private String userAgent;
    private Duration initialDelay;
    private Duration maxDelay;
    private Duration readWatchdog;
    private HttpClient httpClient;

    /**
     * Primary stream URL first. Only the first entry is ever dialed — the stream is pinned to the
     * primary leg and never fails over (failover is HTTP-only, scenario f05). Later entries are
     * accepted for wiring symmetry with {@code Options.streamUrls()} but are never contacted.
     */
    public Builder streamUrls(List<URI> streamUrls) {
      this.streamUrls = streamUrls;
      return this;
    }

    public Builder sdkKey(String sdkKey) {
      this.sdkKey = sdkKey;
      return this;
    }

    /** X-Quonfig-SDK-Version value; defaults to {@link Version#header()}. */
    public Builder userAgent(String userAgent) {
      this.userAgent = userAgent;
      return this;
    }

    /** Default 500ms. */
    public Builder initialDelay(Duration initialDelay) {
      this.initialDelay = initialDelay;
      return this;
    }

    /** Default 30s. */
    public Builder maxDelay(Duration maxDelay) {
      this.maxDelay = maxDelay;
      return this;
    }

    /**
     * Stall-detection watchdog. Each chunk received on the SSE body resets the timer; when no bytes
     * arrive within the window the SDK closes the underlying stream and reconnects. Defaults to 90s
     * (3x the 30s server heartbeat). The default catches client-side wedges (corp proxy buffering,
     * dead NAT, half-open TCP) where the server thinks it delivered and the client knows it
     * received nothing.
     */
    public Builder readWatchdog(Duration readWatchdog) {
      this.readWatchdog = readWatchdog;
      return this;
    }

    /** Optional override; useful for tests. */
    public Builder httpClient(HttpClient httpClient) {
      this.httpClient = httpClient;
      return this;
    }

    public SseClient build() {
      return new SseClient(this);
    }
  }
}
