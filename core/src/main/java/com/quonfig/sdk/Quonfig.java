package com.quonfig.sdk;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.quonfig.sdk.datadir.DatadirWatcher;
import com.quonfig.sdk.eval.ConfigRow;
import com.quonfig.sdk.eval.ConfigStore;
import com.quonfig.sdk.eval.ContextSet;
import com.quonfig.sdk.eval.EvaluationMatch;
import com.quonfig.sdk.eval.Evaluator;
import com.quonfig.sdk.eval.Resolver;
import com.quonfig.sdk.eval.ResolverException;
import com.quonfig.sdk.eval.Value;
import com.quonfig.sdk.eval.ValueType;
import com.quonfig.sdk.supervisor.FallbackPoller;
import com.quonfig.sdk.supervisor.Supervisor;
import com.quonfig.sdk.telemetry.ContextShapeCollector;
import com.quonfig.sdk.telemetry.ContextUploadMode;
import com.quonfig.sdk.telemetry.EvaluationStat;
import com.quonfig.sdk.telemetry.EvaluationSummaryCollector;
import com.quonfig.sdk.telemetry.ExampleContextCollector;
import com.quonfig.sdk.telemetry.HttpTelemetrySender;
import com.quonfig.sdk.telemetry.TelemetryReporter;
import com.quonfig.sdk.telemetry.TelemetrySender;
import com.quonfig.sdk.transport.HttpTransport;
import com.quonfig.sdk.transport.SseClient;
import com.quonfig.sdk.wire.ConfigEnvelope;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import org.slf4j.event.Level;

/**
 * Main public client for the Quonfig Java SDK.
 *
 * <p>Construction modes (mutually exclusive):
 *
 * <ul>
 *   <li><b>datadir</b> — load configs synchronously from a workspace directory tree (configs/,
 *       feature-flags/, segments/, log-levels/, schemas/). Typical local-dev mode.
 *   <li><b>http</b> (default when neither datadir nor datafile is set) — fetch the initial envelope
 *       from {@link Options#apiUrls()} and stream updates over SSE from {@link
 *       Options#streamUrls()}. Constructor returns immediately; init runs in a background thread.
 *   <li><b>datafile</b> — load a pre-serialized {@link ConfigEnvelope} from {@link
 *       Options#datafile()} (filesystem path) or {@link Options#datafileEnvelope()} (in-memory).
 *       Mirrors sdk-node's {@code datafile?: string | object} shape; the envelope's {@code
 *       meta.environment} supplies the evaluation environment when the caller did not set one
 *       explicitly. Synchronous like datadir mode.
 * </ul>
 *
 * <p>Lifecycle: typed getters block on {@link #initFuture()} (with {@link Options#initTimeout()})
 * before returning. {@link #close()} stops background SSE / polling / telemetry threads. {@link
 * #flush()} drains telemetry without closing.
 *
 * <p>Thread-safety: all public methods are safe to call from any thread.
 */
public final class Quonfig implements AutoCloseable, LoggerClient {

  private static final ObjectMapper ENVELOPE_MAPPER = new ObjectMapper();
  private static final String CONFIGS_PATH = "/api/v2/configs";

  /**
   * Top-level context name used by {@link #shouldLog(String, Level)} to inject the logger path for
   * per-logger rule evaluation. Load-bearing for api-telemetry's example-context auto-capture; do
   * not rename without updating the matching constants in the other SDKs.
   */
  static final String QUONFIG_SDK_LOGGING_CONTEXT_NAME = "quonfig-sdk-logging";

  private final Options options;
  private final CompletableFuture<Void> initFuture;
  private final CopyOnWriteArrayList<Runnable> configUpdateListeners = new CopyOnWriteArrayList<>();
  private final CopyOnWriteArrayList<Consumer<Boolean>> sseListeners = new CopyOnWriteArrayList<>();

  /**
   * Environment used for evaluation and emitted in metadata.
   *
   * <ul>
   *   <li><b>datadir</b>: the {@link Options#environment()} pin (required).
   *   <li><b>delivery</b> (HTTP/SSE/fallback poll): always the installed envelope's {@code
   *       meta.environment} — the SDK key determines it server-side. A pin is ignored here (warned
   *       at init); see qfg-pinh.
   *   <li><b>datafile</b>: the pin if set, else {@code envelope.meta.environment} (sdk-node parity,
   *       see qfg-9hre).
   * </ul>
   */
  private volatile String effectiveEnvironment;

  private volatile InMemoryConfigStore store;
  private volatile Evaluator evaluator;
  private volatile Resolver resolver;
  private volatile boolean closed;
  private volatile SseClient sseClient;
  private volatile Supervisor supervisor;
  private volatile FallbackPoller fallbackPoller;
  private volatile HttpTransport httpTransport;
  private volatile DatadirWatcher datadirWatcher;

  /**
   * Wall-clock stamp of the most recent successful install (datadir/datafile constructor or any
   * later envelope install). {@code null} before the first install. Exposed via {@link
   * #lastSuccessfulRefresh()} (qfg-47c2.23).
   */
  private volatile Instant lastRefreshAt;

  /**
   * Canonical-ordering state (qfg-7h5d.1.10). {@code heldGeneration} is the {@code Meta.generation}
   * of the currently-installed delivery envelope; {@code configInstalls} counts successful delivery
   * installs over the client's lifetime; {@code resolvedFromIndex} is the base-URL index of the leg
   * that produced the held config ({@code -1} before the first HTTP install). All three are read by
   * the public failover/ordering accessors. The guard decision and the install that follows are
   * made atomic via {@link #installLock}; the fields stay {@code volatile} so the accessors observe
   * them without contending on the lock.
   */
  private volatile int heldGeneration;

  private volatile int configInstalls;
  private volatile int resolvedFromIndex = -1;
  private volatile boolean initialized;

  /**
   * Set to {@code 0} when the SSE stream connects (it is pinned to the primary stream URL and never
   * repoints). {@link #sseFailedOverToSecondary()} reports {@code sseStreamIndex > 0}; it is false
   * by design and exists so the chaos suite can assert SSE never fails over (scenario f05).
   */
  private volatile int sseStreamIndex = -1;

  /**
   * Serializes the reject-older guard decision with the install that follows across every delivery
   * path (initial HTTP fetch, manual {@link #refresh()}, SSE snapshot/update, fallback poller).
   */
  private final Object installLock = new Object();

  private final EvaluationSummaryCollector summaryCollector;
  private final ContextShapeCollector shapeCollector;
  private final ExampleContextCollector exampleCollector;
  private final TelemetryReporter telemetryReporter;

  public Quonfig(Options options) {
    this.options = Objects.requireNonNull(options, "options");
    if (options.onConfigUpdate() != null) {
      configUpdateListeners.add(options.onConfigUpdate());
    }
    if (options.onSseConnectionStateChange() != null) {
      sseListeners.add(options.onSseConnectionStateChange());
    }

    this.effectiveEnvironment = options.environment();

    if (options.datadir() != null && !options.datadir().isEmpty()) {
      if (options.environment() == null || options.environment().isEmpty()) {
        throw new IllegalStateException(
            "environment required for datadir mode; set Options.builder().environment(...) or QUONFIG_ENVIRONMENT");
      }
      List<ConfigRow> rows = DatadirLoader.load(Path.of(options.datadir()));
      installRows(rows);
      this.initFuture = CompletableFuture.completedFuture(null);
      fireConfigUpdate();
      if (options.dataDirAutoReload()) {
        startDatadirWatcher();
      }
    } else if ((options.datafile() != null && !options.datafile().isEmpty())
        || options.datafileEnvelope() != null) {
      ConfigEnvelope envelope = loadDatafileEnvelope(options);
      // datafile is NOT delivery mode: caller's explicit Options.environment() wins; otherwise
      // fall back to envelope.meta.environment so a self-contained datafile evaluates against the
      // environment it was generated for (sdk-node parity). Pass metaAuthoritative=false.
      installEnvelopeRows(envelope, false);
      this.initFuture = CompletableFuture.completedFuture(null);
      fireConfigUpdate();
    } else {
      // HTTP+SSE mode: initial fetch on a background thread so the constructor returns
      // immediately. Getters block on initFuture (with Options.initTimeout); on init failure
      // they return the caller's default with Reason.ERROR. Once init succeeds, SSE starts
      // streaming envelopes which atomically swap the in-memory store.
      if (options.sdkKey() == null || options.sdkKey().isEmpty()) {
        throw new IllegalStateException(
            "sdkKey required for HTTP mode; set Options.builder().sdkKey(...) or QUONFIG_BACKEND_SDK_KEY");
      }
      // In delivery (SDK-key) mode the active environment is determined server-side by the SDK
      // key and reported in meta.environment; an explicit environment pin (Options.environment()
      // / QUONFIG_ENVIRONMENT) is datadir-only and is ignored here. Warn once at init so a
      // mis-set pin is visible. Mirrors the cross-SDK contract (qfg-pinh); sdk-go always
      // evaluates against the installed envelope's meta.environment.
      if (options.environment() != null && !options.environment().isEmpty()) {
        options
            .logger()
            .warn(
                "quonfig: environment '{}' was set but the client is in delivery (SDK-key) mode; "
                    + "the active environment is determined by the SDK key, so this setting is "
                    + "ignored (it applies only when loading from a local data dir)",
                options.environment());
      }
      // The hedge's per-leg abort must sit below the overall init timeout so a late-but-newer heal
      // leg is not clipped by an init-timeout fired underneath it. Warn (don't throw) so a
      // mis-tuned pair is visible without breaking construction (mirrors the sdk-go pilot's
      // construction-time warning).
      if (options.initTimeout().compareTo(options.configFetchHedgeAbort()) <= 0) {
        options
            .logger()
            .warn(
                "quonfig: initTimeout ({}) <= configFetchHedgeAbort ({}); the hedge's per-leg abort "
                    + "should be strictly below initTimeout so a late-but-newer heal leg is not "
                    + "clipped — raise initTimeout or lower configFetchHedgeAbort",
                options.initTimeout(),
                options.configFetchHedgeAbort());
      }
      this.initFuture = new CompletableFuture<>();
      Thread t = new Thread(this::runInit, "quonfig-init");
      t.setDaemon(true);
      t.start();
    }

    TelemetrySender sender = resolveTelemetrySender(options);
    if (sender != null && !options.disableTelemetry()) {
      ContextUploadMode mode = options.contextUploadMode();
      this.summaryCollector = new EvaluationSummaryCollector(options.collectEvaluationSummaries());
      this.shapeCollector = new ContextShapeCollector(mode);
      this.exampleCollector = new ExampleContextCollector(mode);
      this.telemetryReporter =
          new TelemetryReporter(
              sender,
              options.instanceHash(),
              summaryCollector,
              shapeCollector,
              exampleCollector,
              options.telemetryInitialDelay(),
              options.telemetryFlushInterval(),
              options.telemetryMaxInterval());
      this.telemetryReporter.start();
    } else {
      this.summaryCollector = null;
      this.shapeCollector = null;
      this.exampleCollector = null;
      this.telemetryReporter = null;
    }
  }

  private static TelemetrySender resolveTelemetrySender(Options options) {
    if (options.telemetrySender() != null) return options.telemetrySender();
    if (options.sdkKey() == null || options.sdkKey().isEmpty()) return null;
    return new HttpTelemetrySender(options.telemetryUrl(), options.sdkKey());
  }

  private void installRows(List<ConfigRow> rows) {
    InMemoryConfigStore s = new InMemoryConfigStore(rows);
    Evaluator e = new Evaluator(s, options.weightedValueResolver());
    Resolver r = new Resolver(s, e, options.envLookup());
    this.store = s;
    this.evaluator = e;
    this.resolver = r;
    this.lastRefreshAt = Instant.now();
    this.initialized = true;
  }

  private void runInit() {
    try {
      List<URI> urls = new ArrayList<>(options.apiUrls().size());
      for (String u : options.apiUrls()) urls.add(URI.create(u));
      HttpTransport http =
          HttpTransport.builder()
              .urls(urls)
              .sdkKey(options.sdkKey())
              .timeout(options.initTimeout())
              // Per-URL config-fetch deadline so a hung primary fails over to the secondary inside
              // the init budget instead of starving it until initTimeout (qfg-7h5d.1.10). Still
              // governs the sequential refresh()/fallback-poll path; the hedged init fetch below
              // uses its own hedgeDelay/hedgeAbort.
              .configFetchTimeout(options.configFetchTimeout())
              .build();
      this.httpTransport = http;
      fetchInitialHedged(http);
    } catch (Exception e) {
      Throwable cause = e.getCause() != null ? e.getCause() : e;
      initFuture.completeExceptionally(
          new IllegalStateException("client initialization failed: " + cause.getMessage(), cause));
    }
  }

  /**
   * Parallel-failover hedge for the initial HTTP config fetch (qfg-7h5d.1.14). Fires the primary
   * leg (index 0) first; if it errors fast OR has not settled within the hedge delay it ALSO fires
   * the secondary leg (index 1) in parallel — without cancelling the primary, and at most once.
   * Each successful leg is installed through the reject-older guard, so watermark-max falls out:
   * the first install latches readiness and starts SSE; a late-but-newer leg heals forward; a
   * late-older leg is dropped. If every fired leg fails, the init future completes exceptionally,
   * preserving the existing init-failure (init-throw) contract.
   *
   * <p>This method blocks the init thread until every fired leg has settled (so a heal-forward
   * install lands before {@link #initFuture} is consumed by a slow caller), bounded by the per-leg
   * abort × the number of legs, which is below {@link Options#initTimeout()}.
   */
  private void fetchInitialHedged(HttpTransport http) {
    long hedgeDelayMs = options.configFetchHedgeDelay().toMillis();
    long hedgeAbortMs = options.configFetchHedgeAbort().toMillis();
    boolean hasSecondary = http.legCount() > 1;

    // CAS so the secondary fires AT MOST ONCE and NEVER after a fast primary win. The winning CASer
    // installs the secondary leg's future into secondaryLeg so the init thread can join on it.
    java.util.concurrent.atomic.AtomicBoolean secondaryFired =
        new java.util.concurrent.atomic.AtomicBoolean(false);
    java.util.concurrent.atomic.AtomicReference<CompletableFuture<Void>> secondaryLeg =
        new java.util.concurrent.atomic.AtomicReference<>(null);
    // Latches readiness/SSE on the FIRST successful install regardless of which leg won.
    java.util.concurrent.atomic.AtomicBoolean readyLatched =
        new java.util.concurrent.atomic.AtomicBoolean(false);
    java.util.List<Throwable> legErrors =
        java.util.Collections.synchronizedList(new java.util.ArrayList<>());

    CompletableFuture<Void> primaryLeg =
        fireHedgeLeg(http, 0, hedgeAbortMs, readyLatched, legErrors);

    if (hasSecondary) {
      Runnable fireSecondary =
          () -> {
            if (secondaryFired.compareAndSet(false, true)) {
              secondaryLeg.set(fireHedgeLeg(http, 1, hedgeAbortMs, readyLatched, legErrors));
            }
          };

      // Fast-error path: if the primary settles exceptionally, hedge now (whether that is before or
      // after the hedge delay — a fast error always hedges). On a fast success, mark the secondary
      // as suppressed so the timer below never hedges (cold standby). This runs on the HttpClient
      // executor; the CAS keeps it race-free against the timer. errorHedged completes only AFTER
      // the
      // hedge decision is made, so the init-thread join below cannot read a stale null
      // secondaryLeg.
      CompletableFuture<Void> errorHedged =
          primaryLeg.handle(
              (v, t) -> {
                if (t != null) {
                  fireSecondary.run();
                } else {
                  secondaryFired.set(true); // fast primary win — never hedge
                }
                return null;
              });

      // Hedge-delay timer: if the primary is still in flight when the delay elapses, fire the
      // secondary in parallel. A copy is timed so an exceptional primary does not bubble here — the
      // handle() above owns the fast-error hedge decision.
      try {
        primaryLeg.copy().get(hedgeDelayMs, TimeUnit.MILLISECONDS);
      } catch (TimeoutException te) {
        fireSecondary.run(); // primary still slow at the hedge delay — hedge in parallel
      } catch (Exception ignored) {
        // Primary already settled; errorHedged will make (or has made) the hedge decision.
      }

      // Make sure the fast-error hedge decision has been applied before we read secondaryLeg.
      errorHedged.join();
    }

    // Wait for every fired leg to settle so a heal-forward install can land before we hand control
    // back. Neutralize each leg's exceptional completion (errors are already in legErrors) so the
    // join never throws.
    primaryLeg.exceptionally(t -> null).join();
    CompletableFuture<Void> sec = secondaryLeg.get();
    if (sec != null) {
      sec.exceptionally(t -> null).join();
    }

    if (readyLatched.get()) {
      // At least one leg installed and already latched readiness + started SSE (see fireHedgeLeg).
      return;
    }

    // Nothing installed. Preserve the init-failure contract: surface the failure so getters apply
    // OnInitFailure (init-throw). If legErrors is empty the legs all 304'd (no change) — still a
    // successful, ready client; complete normally.
    if (!legErrors.isEmpty()) {
      Throwable first = legErrors.get(0);
      Throwable cause = first.getCause() != null ? first.getCause() : first;
      initFuture.completeExceptionally(
          new IllegalStateException("client initialization failed: " + cause.getMessage(), cause));
    } else {
      this.initialized = true;
      initFuture.complete(null);
    }
  }

  /**
   * Fires one hedge leg pinned to {@code legIndex}, bounded by {@code abortMs}, installs a 2xx body
   * through the reject-older guard, and on the FIRST successful install (across all legs) latches
   * readiness — completes {@link #initFuture}, fires the config-update callback, and starts SSE
   * exactly once. The returned future always completes normally; the leg's error (if any) is
   * recorded in {@code legErrors} so the caller can surface an all-legs-failed init failure.
   */
  private CompletableFuture<Void> fireHedgeLeg(
      HttpTransport http,
      int legIndex,
      long abortMs,
      java.util.concurrent.atomic.AtomicBoolean readyLatched,
      java.util.List<Throwable> legErrors) {
    // The returned future reflects the RAW leg outcome so the hedge arbiter can distinguish a fast
    // error (hedge now) from a fast success (suppress the hedge): it completes exceptionally on a
    // transport/timeout/decode error and normally on a successful resolve. The install + readiness
    // latch are side effects performed inside thenApply before the success propagates.
    return http.getFrom(legIndex, URI.create(CONFIGS_PATH), null)
        .orTimeout(abortMs, TimeUnit.MILLISECONDS)
        .thenAccept(
            resp -> {
              boolean installed;
              try {
                installed = resp.statusCode() != 304 && installDeliveryBody(resp.body(), legIndex);
              } catch (IOException e) {
                throw new java.util.concurrent.CompletionException(e);
              }
              // Latch readiness on the first leg to resolve successfully — complete initFuture and
              // start SSE exactly once. The first leg on a fresh client always installs (etag is
              // null, the generation guard accepts the first snapshot), so the latch coincides with
              // a real install.
              if (readyLatched.compareAndSet(false, true)) {
                initFuture.complete(null);
                startSse();
              }
              // Fire the config-update callback whenever this leg actually advanced the held
              // generation — on the first install (init) AND on a heal-forward install by a
              // late-but-newer leg (an extra post-ready callback, documented in the CHANGELOG).
              if (installed) {
                fireConfigUpdate();
              }
            })
        .whenComplete(
            (v, t) -> {
              if (t != null) {
                legErrors.add(t.getCause() != null ? t.getCause() : t);
              }
            });
  }

  /**
   * Parses a delivery-mode response body and installs it through the reject-older guard. Returns
   * {@code true} iff the envelope advanced the held generation (and was therefore installed).
   * {@code sourceIndex} is the base-URL leg that produced the body (from {@link
   * HttpTransport#lastResolvedIndex()}), or {@code -1} when the source leg is irrelevant (SSE).
   */
  private boolean installDeliveryBody(String body, int sourceIndex) throws IOException {
    ConfigEnvelope envelope = ENVELOPE_MAPPER.readValue(body, ConfigEnvelope.class);
    return installDelivery(envelope, sourceIndex);
  }

  /**
   * Canonical reject-older install guard (qfg-7h5d.1.10). Applies to every delivery install path
   * (initial HTTP fetch, manual {@link #refresh()}, SSE initial snapshot, SSE update, fallback
   * poller). The rule is the whole story — there is no source ranking:
   *
   * <ul>
   *   <li>A fresh client (nothing installed yet) always accepts the first snapshot, even at
   *       generation 0. A stale secondary payload can therefore seed a fresh client.
   *   <li>An established client installs only if {@code incoming.generation > heldGeneration}. An
   *       older payload is dropped, so a late failover to a stale secondary can never move the
   *       client backward; a later, newer leg heals forward.
   *   <li>A same-generation snapshot is a no-op (not strictly greater), so an equal second leg
   *       can't re-install or flap.
   *   <li>An unversioned snapshot (generation absent or {@code <= 0} — a server that predates the
   *       watermark, or one whose rev-count failed) carries no ordering information, so it is never
   *       rejected as "older"; freezing an established client on stale config would be worse.
   * </ul>
   *
   * <p>The decision and the install are made under {@link #installLock} so they are atomic with
   * respect to every other delivery path. Datadir/datafile installs are a local source of truth
   * (generation is always 0) and bypass this guard by calling {@link #installEnvelopeRows}
   * directly.
   */
  private boolean installDelivery(ConfigEnvelope envelope, int sourceIndex) {
    int incoming = envelope.meta() != null ? envelope.meta().generation() : 0;
    synchronized (installLock) {
      if (configInstalls != 0 && incoming > 0 && incoming <= heldGeneration) {
        // Reject-older / same-generation: keep the held envelope, do not flap. An unversioned
        // (incoming <= 0) snapshot carries no ordering info and falls through to install.
        return false;
      }
      // Initial HTTP fetch and fallback poll are delivery mode: meta.environment is authoritative.
      installEnvelopeRows(envelope, true);
      heldGeneration = incoming;
      configInstalls++;
      if (sourceIndex >= 0) {
        resolvedFromIndex = sourceIndex;
      }
      return true;
    }
  }

  private void installEnvelopeRows(ConfigEnvelope envelope, boolean metaAuthoritative) {
    applyMetaEnvironment(envelope, metaAuthoritative);
    List<ConfigRow> rows = new ArrayList<>(envelope.configs().size());
    for (JsonNode cfg : envelope.configs()) {
      rows.add(DatadirLoader.parseConfigNode(cfg));
    }
    installRows(rows);
  }

  /**
   * Adopt the envelope's {@code meta.environment} as the evaluation environment.
   *
   * <p><b>Delivery mode</b> ({@code metaAuthoritative=true} — HTTP initial fetch, SSE, fallback
   * poll): the server selects the environment via SDK-key scoping and reports it in {@code
   * meta.environment}; the per-config rows arrive scoped to that single env (singular {@code
   * environment} block). {@code meta.environment} is ALWAYS authoritative here, REGARDLESS of any
   * {@link Options#environment()} pin — an environment pin is datadir-only and is ignored in
   * delivery mode (warned once at init). Mirrors sdk-go, which always sets {@code c.envID =
   * envelope.Meta.Environment} on install (qfg-pinh).
   *
   * <p><b>Datafile mode</b> ({@code metaAuthoritative=false}): not delivery — an explicit {@link
   * Options#environment()} pin wins; otherwise fall back to {@code meta.environment} so a
   * self-contained datafile evaluates against the environment it was generated for (sdk-node
   * parity).
   */
  private void applyMetaEnvironment(ConfigEnvelope envelope, boolean metaAuthoritative) {
    if (!metaAuthoritative && options.environment() != null && !options.environment().isEmpty()) {
      // datafile mode with an explicit pin: pin wins.
      return;
    }
    if (envelope.meta() != null
        && envelope.meta().environment() != null
        && !envelope.meta().environment().isEmpty()) {
      this.effectiveEnvironment = envelope.meta().environment();
    }
  }

  private static ConfigEnvelope loadDatafileEnvelope(Options options) {
    if (options.datafileEnvelope() != null) {
      return options.datafileEnvelope();
    }
    Path file = Path.of(options.datafile());
    try {
      return ENVELOPE_MAPPER.readValue(Files.readAllBytes(file), ConfigEnvelope.class);
    } catch (IOException e) {
      throw new IllegalStateException("failed to read datafile " + file + ": " + e.getMessage(), e);
    }
  }

  private void startSse() {
    if (closed) return;

    // Use a one-element array so the lambdas can read the final supervisor reference. We have
    // to build the FallbackPoller (whose callbacks reference the supervisor) before the
    // Supervisor (whose worker list contains the poller). Java's "effectively final" rule for
    // captured locals forces this back-reference trick — sdk-go uses a struct field with the
    // same intent.
    Supervisor[] supBox = new Supervisor[1];

    FallbackPoller fp = null;
    if (options.fallbackPollEnabled() && options.fallbackPollIntervalMs() > 0) {
      Duration interval = Duration.ofMillis(options.fallbackPollIntervalMs());
      Duration threshold =
          options.fallbackPollThreshold() != null
              ? options.fallbackPollThreshold()
              : FallbackPoller.DEFAULT_THRESHOLD;
      fp =
          FallbackPoller.builder()
              .interval(interval)
              .threshold(threshold)
              .fetch(this::fallbackPollFetchOnce)
              .onEngage(
                  () -> {
                    Supervisor s = supBox[0];
                    if (s != null) s.setConnectionState(ConnectionState.FALLING_BACK);
                    options
                        .logger()
                        .warn(
                            "quonfig: Layer 2 fallback poller engaged (SSE disconnected past {}ms threshold); polling /api/v2/configs every {}ms",
                            threshold.toMillis(),
                            options.fallbackPollIntervalMs());
                    Consumer<Boolean> cb = options.onFallbackPollerStateChange();
                    if (cb != null) {
                      try {
                        cb.accept(true);
                      } catch (RuntimeException ignored) {
                        // user code; never tear down the client
                      }
                    }
                  })
              .onDisengage(
                  () -> {
                    // Disengage fires only on SSE reconnect — the connect edge has already
                    // set CONNECTED on the supervisor; re-asserting it is harmless and guards
                    // the rare ordering where the poller's tick beats the SSE callback.
                    Supervisor s = supBox[0];
                    if (s != null) s.setConnectionState(ConnectionState.CONNECTED);
                    options
                        .logger()
                        .info("quonfig: Layer 2 fallback poller disengaged (SSE recovered)");
                    Consumer<Boolean> cb = options.onFallbackPollerStateChange();
                    if (cb != null) {
                      try {
                        cb.accept(false);
                      } catch (RuntimeException ignored) {
                        // user code; never tear down the client
                      }
                    }
                  })
              .build();
      this.fallbackPoller = fp;
    }

    Supervisor.Builder supBuilder = Supervisor.builder();
    if (fp != null) {
      supBuilder.workers(List.of(new Supervisor.WorkerSpec("2", fp.worker())));
    }
    Supervisor sup = supBuilder.build();
    supBox[0] = sup;
    this.supervisor = sup;
    sup.start();

    final FallbackPoller fpRef = fp;
    // Arm the disconnect timer at the moment SSE is *meant* to be up. If the first
    // connection attempt succeeds quickly the SSE callback will clear it; if SSE never
    // establishes, fallback engages after the 120s threshold.
    if (fpRef != null) {
      fpRef.setSseConnected(false);
    }

    List<URI> streams = new ArrayList<>(options.streamUrls().size());
    for (String u : options.streamUrls()) streams.add(URI.create(u));
    SseClient.Builder sseBuilder = SseClient.builder().streamUrls(streams).sdkKey(options.sdkKey());
    if (options.sseReadWatchdog() != null) {
      sseBuilder.readWatchdog(options.sseReadWatchdog());
    }
    SseClient sse = sseBuilder.build();
    sse.onEnvelope(
        env -> {
          try {
            // SSE is delivery mode: meta.environment is authoritative on every update, matching
            // the initial HTTP fetch (qfg-pinh). The reject-older guard drops an SSE
            // snapshot/update
            // that doesn't advance the held generation, so a stale replay never regresses the
            // client
            // (qfg-7h5d.1.10). sourceIndex=-1: SSE installs don't change resolvedFrom (HTTP-only).
            if (installDelivery(env, -1)) {
              sup.recordSuccessfulRefresh();
              fireConfigUpdate();
            }
          } catch (RuntimeException ignored) {
            // Bad envelope is non-fatal — keep the prior store in place.
          }
        });
    sse.onConnectionStateChange(
        connected -> {
          // Feed the supervisor + fallback poller before invoking user callbacks so
          // Quonfig.connectionState() (qfg-47c2.23) observes the new state immediately.
          if (fpRef != null) {
            fpRef.setSseConnected(connected);
          }
          if (connected) {
            // The SSE stream is pinned to the primary stream URL and deliberately never repoints to
            // a secondary leg — failover is an HTTP-only property. Record the leg (always 0) so
            // sseFailedOverToSecondary() can assert the stream stayed on primary (scenario f05).
            sseStreamIndex = 0;
            sup.setConnectionState(ConnectionState.CONNECTED);
          } else if (fpRef == null || !fpRef.active()) {
            // Skip the DISCONNECTED edge while fallback is already engaged so the visible
            // state stays "falling_back" rather than flickering between the two.
            sup.setConnectionState(ConnectionState.DISCONNECTED);
          }
          for (Consumer<Boolean> l : sseListeners) {
            try {
              l.accept(connected);
            } catch (RuntimeException ignored) {
              // user code; never tear down the client
            }
          }
        });
    this.sseClient = sse;
    logPollingMode();
    sse.start();
  }

  /**
   * Performs one Layer 2 fallback fetch: GET {@code /api/v2/configs}, install rows, mark refresh.
   * Errors are swallowed — the poller's outer loop keeps ticking; a transient HTTP failure during a
   * partition is expected and not actionable. Called only when the {@link FallbackPoller} has
   * engaged.
   */
  private void fallbackPollFetchOnce() {
    HttpTransport http = this.httpTransport;
    if (http == null) return;
    try {
      HttpResponse<String> resp =
          http.get(URI.create(CONFIGS_PATH), null)
              .get(options.initTimeout().toMillis(), TimeUnit.MILLISECONDS);
      // Reject-older guard: a fallback poll that fails over to an older secondary must not regress
      // the held generation (qfg-7h5d.1.10). Only fire the update callbacks on an actual install.
      if (installDeliveryBody(resp.body(), http.lastResolvedIndex())) {
        Supervisor sup = this.supervisor;
        if (sup != null) sup.recordSuccessfulRefresh();
        fireConfigUpdate();
      }
    } catch (Exception e) {
      // Best-effort: HTTP errors during a partition are exactly what fallback polling
      // exists to weather. The next interval tick will try again.
      options.logger().debug("quonfig: fallback poll fetch failed: {}", e.getMessage());
    }
  }

  /**
   * One-shot startup log advertising the chosen Layer 1 (SSE) and Layer 2 (fallback poll) modes.
   * Parity with sdk-go's {@code logPollingMode}. Deployers grep for this line to confirm they're on
   * the fallback-only semantic after the rename from {@code enablePolling}/{@code pollInterval}.
   */
  private void logPollingMode() {
    String mode = options.fallbackPollEnabled() ? "sse-with-fallback-poll" : "sse-only";
    if (options.fallbackPollEnabled()) {
      long thresholdMs =
          options.fallbackPollThreshold() != null
              ? options.fallbackPollThreshold().toMillis()
              : FallbackPoller.DEFAULT_THRESHOLD.toMillis();
      options
          .logger()
          .info(
              "quonfig: polling configuration mode={} sse_enabled=true fallback_poll_enabled=true fallback_poll_interval_ms={} fallback_poll_threshold_ms={}",
              mode,
              options.fallbackPollIntervalMs(),
              thresholdMs);
    } else {
      options
          .logger()
          .info(
              "quonfig: polling configuration mode={} sse_enabled=true fallback_poll_enabled=false",
              mode);
    }
  }

  public Options options() {
    return options;
  }

  public CompletableFuture<Void> initFuture() {
    return initFuture;
  }

  public Set<String> keys() {
    awaitInit();
    return store.keys();
  }

  public boolean featureIsOn(String key, ContextSet ctx) {
    EvaluationDetails<Boolean> d = getBoolDetails(key, Boolean.FALSE, ctx);
    return Boolean.TRUE.equals(d.value());
  }

  public BoundQuonfig withContext(ContextSet ctx) {
    return new BoundQuonfig(this, ctx == null ? new ContextSet() : ctx);
  }

  /**
   * Returns {@code true} iff a message at {@code level} should be emitted for {@code loggerPath}.
   *
   * <p>Always injects {@code quonfig-sdk-logging.key=loggerPath} into the evaluation context.
   * Lookup order:
   *
   * <ul>
   *   <li>If {@link Options#loggerKey()} is set, evaluate that single config — rules in it dispatch
   *       on the injected logger-path context (sdk-node / sdk-go pattern).
   *   <li>Otherwise, look up a config keyed by {@code loggerPath}; on miss, walk up dotted parents
   *       ({@code com.foo.MyClass} → {@code com.foo} → {@code com} → {@code ""}).
   * </ul>
   *
   * <p>Returns {@code true} when no log-level config exists at any level — never silently swallow
   * logs.
   */
  public boolean shouldLog(String loggerPath, Level level) {
    return shouldLog(loggerPath, level, null);
  }

  public boolean shouldLog(String loggerPath, Level level, ContextSet ctx) {
    Objects.requireNonNull(loggerPath, "loggerPath");
    Objects.requireNonNull(level, "level");

    Optional<String> resolved = resolveLogLevelString(loggerPath, ctx);
    return resolved.map(s -> compareLevel(s, level)).orElse(true);
  }

  /**
   * Resolves the configured {@link LogLevel} for {@code loggerPath} (with optional context),
   * walking up dotted parents per {@link #shouldLog} semantics. Returns {@link Optional#empty()}
   * when no log-level config exists or the resolved value is unparseable — filters use that signal
   * as "no opinion" and defer to whatever the underlying logging library would do.
   */
  @Override
  public Optional<LogLevel> getLogLevel(String loggerPath, ContextSet ctx) {
    Objects.requireNonNull(loggerPath, "loggerPath");
    return resolveLogLevelString(loggerPath, ctx).flatMap(LogLevel::fromString);
  }

  private Optional<String> resolveLogLevelString(String loggerPath, ContextSet ctx) {
    ContextSet loggerCtx =
        new ContextSet()
            .withNamedContext(QUONFIG_SDK_LOGGING_CONTEXT_NAME, Map.of("key", loggerPath));
    ContextSet merged = merge(ctx, loggerCtx);

    String configuredKey = options.loggerKey();
    if (configuredKey != null && !configuredKey.isEmpty()) {
      return lookupLogLevel(configuredKey, merged);
    }

    String key = loggerPath;
    while (true) {
      Optional<String> resolved = lookupLogLevel(key, merged);
      if (resolved.isPresent()) return resolved;
      if (key.isEmpty()) return Optional.empty();
      int dot = key.lastIndexOf('.');
      key = dot < 0 ? "" : key.substring(0, dot);
    }
  }

  private Optional<String> lookupLogLevel(String key, ContextSet merged) {
    EvaluationDetails<String> d = getStringDetails(key, null, merged);
    if (d.reason() == Reason.ERROR || d.value() == null) {
      return Optional.empty();
    }
    return Optional.of(d.value());
  }

  private static boolean compareLevel(String resolvedLevel, Level desired) {
    Level resolved;
    try {
      resolved = Level.valueOf(resolvedLevel.toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      // Unparseable config value — don't drop logs.
      return true;
    }
    return resolved.toInt() <= desired.toInt();
  }

  public void onConfigUpdate(Runnable listener) {
    if (listener != null) configUpdateListeners.add(listener);
  }

  public void onSseConnectionStateChange(Consumer<Boolean> listener) {
    if (listener != null) sseListeners.add(listener);
  }

  /**
   * Wall-clock time of the most recent successful envelope install (any source: initial HTTP fetch,
   * SSE update, fallback poll, datadir, or datafile). {@code null} before the first install.
   *
   * <p>Diagnostic surface only — do not wire into a Kubernetes liveness probe. See {@link
   * ConnectionState} for the rationale.
   */
  public Instant lastSuccessfulRefresh() {
    return lastRefreshAt;
  }

  /**
   * Current transport health. In HTTP+SSE mode this tracks the {@link Supervisor}'s view of the SSE
   * worker (CONNECTED / DISCONNECTED / FALLING_BACK). In datadir/datafile mode the constructor
   * installs synchronously so the value is {@link ConnectionState#CONNECTED} once construction
   * returns. Before any install completes, returns {@link ConnectionState#INITIALIZING}.
   *
   * <p>Diagnostic surface only — do not wire into a Kubernetes liveness probe. See {@link
   * ConnectionState} for the rationale.
   */
  public ConnectionState connectionState() {
    Supervisor sup = this.supervisor;
    if (sup != null) {
      return sup.connectionState();
    }
    // No supervisor — either HTTP mode before startSse() runs, or datadir/datafile mode (where
    // a supervisor is never built). The install timestamp tells the two apart.
    return lastRefreshAt != null ? ConnectionState.CONNECTED : ConnectionState.INITIALIZING;
  }

  /**
   * Whether the client has installed at least one config envelope and is ready to evaluate. {@code
   * false} before the first install (HTTP mode, while init runs) and after {@link #close()}.
   */
  public boolean ready() {
    return initialized && !closed;
  }

  /**
   * {@code Meta.generation} of the config the client is currently holding ({@code 0} before the
   * first install, or when the server predates the watermark). A higher generation is strictly
   * newer; this is the value the canonical-ordering guard compares against on every install path.
   */
  public int heldGeneration() {
    return heldGeneration;
  }

  /**
   * Number of times a delivery envelope has been installed over the client's lifetime (initial
   * fetch, manual {@link #refresh()}, SSE snapshot/update, fallback poll). The canonical-ordering
   * guard keeps this from advancing on a same-or-older payload.
   */
  public int configInstallCount() {
    return configInstalls;
  }

  /**
   * Which configured upstream leg produced the config the client is currently holding: {@code
   * "primary"} (the first API URL), {@code "secondary"} (any later URL reached via failover), or
   * {@code ""} before the first successful HTTP install. Reflects the HTTP config-fetch path; SSE
   * installs do not change it.
   */
  public String resolvedFrom() {
    int idx = resolvedFromIndex;
    if (idx < 0) {
      return "";
    }
    return idx == 0 ? "primary" : "secondary";
  }

  /**
   * Whether the live SSE stream ever repointed to a non-primary leg. Always {@code false} by design
   * — SSE is pinned to the primary stream and failover is an HTTP-only property — and exists so the
   * chaos suite can assert that invariant (scenario f05) and catch a regression that silently
   * repoints the stream.
   */
  public boolean sseFailedOverToSecondary() {
    return sseStreamIndex > 0;
  }

  /**
   * Performs one manual poll of {@code GET /api/v2/configs} (walking the [primary, secondary]
   * failover list) and installs the result through the reject-older guard. A no-op in datadir/
   * datafile mode and before the HTTP transport is wired. Errors are swallowed — a transient HTTP
   * failure during a partition is expected and the next caller (or the fallback poller) retries.
   * Mirrors sdk-go's {@code Client.Refresh()}.
   */
  public void refresh() {
    HttpTransport http = this.httpTransport;
    if (http == null) {
      return;
    }
    try {
      HttpResponse<String> resp =
          http.get(URI.create(CONFIGS_PATH), null)
              .get(options.initTimeout().toMillis(), TimeUnit.MILLISECONDS);
      if (installDeliveryBody(resp.body(), http.lastResolvedIndex())) {
        Supervisor sup = this.supervisor;
        if (sup != null) sup.recordSuccessfulRefresh();
        fireConfigUpdate();
      }
    } catch (Exception e) {
      options.logger().debug("quonfig: refresh failed: {}", e.getMessage());
    }
  }

  /** Drains pending telemetry synchronously and posts it. No-op when telemetry is disabled. */
  public void flush() {
    awaitInit();
    if (telemetryReporter != null) {
      try {
        telemetryReporter.flush();
      } catch (IOException e) {
        // Surface as unchecked so callers don't have to declare; the reporter itself logs.
        throw new IllegalStateException("telemetry flush failed: " + e.getMessage(), e);
      }
    }
  }

  @Override
  public void close() {
    closed = true;
    DatadirWatcher dw = datadirWatcher;
    if (dw != null) {
      dw.close();
      datadirWatcher = null;
    }
    SseClient sse = sseClient;
    if (sse != null) sse.stop();
    Supervisor sup = supervisor;
    if (sup != null) sup.stop();
    if (telemetryReporter != null) telemetryReporter.close();
  }

  /**
   * Wires up a {@link DatadirWatcher} for {@link Options#datadir()} when {@link
   * Options#dataDirAutoReload()} is on. On registration failure (read-only fs, immutable container)
   * we log and continue without watching — the SDK keeps serving the init-time envelope rather than
   * throwing.
   */
  private void startDatadirWatcher() {
    String dir = options.datadir();
    if (dir == null || dir.isEmpty()) return;
    DatadirWatcher watcher =
        new DatadirWatcher(
            Path.of(dir),
            options.dataDirAutoReloadDebounceMs(),
            this::reloadDatadir,
            err ->
                options
                    .logger()
                    .warn(
                        "quonfig: datadir watcher error ({}): {}",
                        err.getClass().getSimpleName(),
                        err.getMessage()));
    if (!watcher.start()) {
      options
          .logger()
          .warn(
              "quonfig: dataDirAutoReload requested but watcher registration failed for {} — continuing without auto-reload",
              dir);
      return;
    }
    this.datadirWatcher = watcher;
  }

  /**
   * Re-reads the datadir into a fresh row set and atomically installs it. Parse-then-swap: any
   * exception during the read (mid-write JSON, garbage file) is logged and swallowed so the prior
   * envelope stays in the store and {@code onConfigUpdate} does NOT fire.
   */
  private void reloadDatadir() {
    if (closed) return;
    String dir = options.datadir();
    if (dir == null || dir.isEmpty()) return;
    try {
      List<ConfigRow> rows = DatadirLoader.load(Path.of(dir));
      installRows(rows);
      fireConfigUpdate();
    } catch (RuntimeException e) {
      options
          .logger()
          .warn(
              "quonfig: datadir reload failed; keeping previous envelope ({}): {}",
              e.getClass().getSimpleName(),
              e.getMessage());
    }
  }

  // ---- typed getters (no context) ----

  public String getString(String key, String def) {
    return getString(key, def, null);
  }

  public Boolean getBool(String key, Boolean def) {
    return getBool(key, def, null);
  }

  /**
   * @deprecated renamed for cross-SDK consistency — use {@link #getBool(String, Boolean)}.
   */
  @Deprecated
  public Boolean getBoolean(String key, Boolean def) {
    return getBool(key, def);
  }

  /**
   * @deprecated misleading name — returns {@link Long}, not {@link Integer}. Use {@link
   *     #getLong(String, Long)}.
   */
  @Deprecated
  public Long getInt(String key, Long def) {
    return getLong(key, def, null);
  }

  public Long getLong(String key, Long def) {
    return getLong(key, def, null);
  }

  public Double getDouble(String key, Double def) {
    return getDouble(key, def, null);
  }

  public List<String> getStringList(String key, List<String> def) {
    return getStringList(key, def, null);
  }

  public Duration getDuration(String key, Duration def) {
    return getDuration(key, def, null);
  }

  public Object getJson(String key, Object def) {
    return getJson(key, def, null);
  }

  // ---- typed getters (with context) ----

  public String getString(String key, String def, ContextSet ctx) {
    return getStringDetails(key, def, ctx).value();
  }

  public Boolean getBool(String key, Boolean def, ContextSet ctx) {
    return getBoolDetails(key, def, ctx).value();
  }

  /**
   * @deprecated renamed for cross-SDK consistency — use {@link #getBool(String, Boolean,
   *     ContextSet)}.
   */
  @Deprecated
  public Boolean getBoolean(String key, Boolean def, ContextSet ctx) {
    return getBool(key, def, ctx);
  }

  /**
   * @deprecated misleading name — returns {@link Long}, not {@link Integer}. Use {@link
   *     #getLong(String, Long, ContextSet)}.
   */
  @Deprecated
  public Long getInt(String key, Long def, ContextSet ctx) {
    return getLong(key, def, ctx);
  }

  public Long getLong(String key, Long def, ContextSet ctx) {
    return getLongDetails(key, def, ctx).value();
  }

  public Double getDouble(String key, Double def, ContextSet ctx) {
    return getDoubleDetails(key, def, ctx).value();
  }

  public List<String> getStringList(String key, List<String> def, ContextSet ctx) {
    return getStringListDetails(key, def, ctx).value();
  }

  public Duration getDuration(String key, Duration def, ContextSet ctx) {
    return getDurationDetails(key, def, ctx).value();
  }

  public Object getJson(String key, Object def, ContextSet ctx) {
    return getJsonDetails(key, def, ctx).value();
  }

  // ---- detail variants ----

  public EvaluationDetails<String> getStringDetails(String key, String def) {
    return getStringDetails(key, def, null);
  }

  public EvaluationDetails<Boolean> getBoolDetails(String key, Boolean def) {
    return getBoolDetails(key, def, null);
  }

  /**
   * @deprecated renamed for cross-SDK consistency — use {@link #getBoolDetails(String, Boolean)}.
   */
  @Deprecated
  public EvaluationDetails<Boolean> getBooleanDetails(String key, Boolean def) {
    return getBoolDetails(key, def);
  }

  /**
   * @deprecated misleading name — returns {@link Long}, not {@link Integer}. Use {@link
   *     #getLongDetails(String, Long)}.
   */
  @Deprecated
  public EvaluationDetails<Long> getIntDetails(String key, Long def) {
    return getLongDetails(key, def, null);
  }

  public EvaluationDetails<Long> getLongDetails(String key, Long def) {
    return getLongDetails(key, def, null);
  }

  public EvaluationDetails<Double> getDoubleDetails(String key, Double def) {
    return getDoubleDetails(key, def, null);
  }

  public EvaluationDetails<List<String>> getStringListDetails(String key, List<String> def) {
    return getStringListDetails(key, def, null);
  }

  public EvaluationDetails<Duration> getDurationDetails(String key, Duration def) {
    return getDurationDetails(key, def, null);
  }

  public EvaluationDetails<Object> getJsonDetails(String key, Object def) {
    return getJsonDetails(key, def, null);
  }

  public EvaluationDetails<String> getStringDetails(String key, String def, ContextSet ctx) {
    return typedDetails(key, def, ctx, ValueType.STRING, String.class);
  }

  public EvaluationDetails<Boolean> getBoolDetails(String key, Boolean def, ContextSet ctx) {
    return typedDetails(key, def, ctx, ValueType.BOOL, Boolean.class);
  }

  /**
   * @deprecated renamed for cross-SDK consistency — use {@link #getBoolDetails(String, Boolean,
   *     ContextSet)}.
   */
  @Deprecated
  public EvaluationDetails<Boolean> getBooleanDetails(String key, Boolean def, ContextSet ctx) {
    return getBoolDetails(key, def, ctx);
  }

  /**
   * @deprecated misleading name — returns {@link Long}, not {@link Integer}. Use {@link
   *     #getLongDetails(String, Long, ContextSet)}.
   */
  @Deprecated
  public EvaluationDetails<Long> getIntDetails(String key, Long def, ContextSet ctx) {
    return getLongDetails(key, def, ctx);
  }

  public EvaluationDetails<Long> getLongDetails(String key, Long def, ContextSet ctx) {
    return typedDetails(key, def, ctx, ValueType.INT, Long.class);
  }

  public EvaluationDetails<Double> getDoubleDetails(String key, Double def, ContextSet ctx) {
    return typedDetails(key, def, ctx, ValueType.DOUBLE, Double.class);
  }

  @SuppressWarnings({"unchecked", "rawtypes"})
  public EvaluationDetails<List<String>> getStringListDetails(
      String key, List<String> def, ContextSet ctx) {
    return (EvaluationDetails<List<String>>)
        (EvaluationDetails) typedDetails(key, def, ctx, ValueType.STRING_LIST, List.class);
  }

  public EvaluationDetails<Duration> getDurationDetails(String key, Duration def, ContextSet ctx) {
    return typedDetails(key, def, ctx, ValueType.DURATION, Duration.class);
  }

  public EvaluationDetails<Object> getJsonDetails(String key, Object def, ContextSet ctx) {
    return typedDetails(key, def, ctx, ValueType.JSON, Object.class);
  }

  // ---- core evaluation ----

  @SuppressWarnings("unchecked")
  private <T> EvaluationDetails<T> typedDetails(
      String key, T def, ContextSet ctx, ValueType expectedType, Class<T> javaType) {
    try {
      awaitInit();
    } catch (IllegalStateException e) {
      return new EvaluationDetails<>(
          def,
          Reason.ERROR,
          variantFor(Reason.ERROR, -1, -1),
          null,
          ErrorCode.GENERAL,
          e.getMessage(),
          baseMetadata(null, key, null, Reason.ERROR, -1, -1));
    }
    ContextSet effective = merge(options.globalContext(), ctx);

    ConfigRow cfg = store.getConfig(key);
    if (cfg == null) {
      return new EvaluationDetails<>(
          def,
          Reason.ERROR,
          variantFor(Reason.ERROR, -1, -1),
          null,
          ErrorCode.FLAG_NOT_FOUND,
          "config \"" + key + "\" not found",
          baseMetadata(null, key, null, Reason.ERROR, -1, -1));
    }

    if (shapeCollector != null) shapeCollector.push(effective);
    if (exampleCollector != null) exampleCollector.push(effective);

    if (!isCompatible(cfg.valueType(), expectedType)) {
      return new EvaluationDetails<>(
          def,
          Reason.ERROR,
          variantFor(Reason.ERROR, -1, -1),
          null,
          ErrorCode.TYPE_MISMATCH,
          "config \"" + key + "\" is " + cfg.valueType() + ", caller expected " + expectedType,
          metadataFor(cfg, Reason.ERROR, -1, -1));
    }

    EvaluationMatch match;
    try {
      match = evaluator.evaluate(cfg, effectiveEnvironment, effective);
    } catch (RuntimeException e) {
      return new EvaluationDetails<>(
          def,
          Reason.ERROR,
          variantFor(Reason.ERROR, -1, -1),
          null,
          ErrorCode.GENERAL,
          "evaluation failed for \"" + key + "\": " + e.getMessage(),
          metadataFor(cfg, Reason.ERROR, -1, -1));
    }

    if (!match.isMatch()) {
      return new EvaluationDetails<>(
          def,
          Reason.DEFAULT,
          variantFor(Reason.DEFAULT, -1, -1),
          null,
          null,
          null,
          metadataFor(cfg, Reason.DEFAULT, -1, -1));
    }

    Value resolvedVal;
    try {
      resolvedVal = resolver.resolve(match.value(), cfg, effectiveEnvironment, effective);
    } catch (ResolverException e) {
      return new EvaluationDetails<>(
          def,
          Reason.ERROR,
          variantFor(Reason.ERROR, -1, -1),
          null,
          ErrorCode.GENERAL,
          "resolve failed for \"" + key + "\": " + e.getMessage(),
          metadataFor(cfg, Reason.ERROR, match.ruleIndex(), match.weightedValueIndex()));
    }

    Reason r = match.weightedValueIndex() >= 0 ? Reason.SPLIT : mapEngineReason(match.reason());

    Object payload = resolvedVal != null ? resolvedVal.value() : null;
    T typed;
    try {
      typed = (T) coerceToJavaType(payload, expectedType, javaType);
    } catch (ClassCastException | IllegalArgumentException e) {
      return new EvaluationDetails<>(
          def,
          Reason.ERROR,
          variantFor(Reason.ERROR, -1, -1),
          null,
          ErrorCode.TYPE_MISMATCH,
          "cannot return \"" + key + "\" as " + expectedType + ": " + e.getMessage(),
          metadataFor(cfg, Reason.ERROR, match.ruleIndex(), match.weightedValueIndex()));
    }

    if (summaryCollector != null) {
      String reportable = Resolver.reportableValueFor(match.value()).orElse(null);
      summaryCollector.push(
          new EvaluationStat(
              cfg.id(),
              cfg.key(),
              cfg.type().name(),
              match.ruleIndex(),
              match.weightedValueIndex(),
              typed,
              reportable,
              reasonNumber(r)));
    }

    Integer variantIndex = r == Reason.SPLIT ? match.weightedValueIndex() : null;
    return new EvaluationDetails<>(
        typed,
        r,
        variantFor(r, match.ruleIndex(), match.weightedValueIndex()),
        variantIndex,
        null,
        null,
        metadataFor(cfg, r, match.ruleIndex(), match.weightedValueIndex()));
  }

  private static int reasonNumber(Reason r) {
    switch (r) {
      case STATIC:
        return 1;
      case TARGETING_MATCH:
        return 2;
      case SPLIT:
        return 3;
      case DEFAULT:
        return 4;
      case ERROR:
        return 5;
      case UNKNOWN:
      default:
        return 0;
    }
  }

  /**
   * Per project/plans/openfeature-resolution-details.md §2: variant is a synthetic
   * OpenFeature-style identifier always set on every {@link EvaluationDetails}.
   */
  private static String variantFor(Reason r, int ruleIndex, int weightedValueIndex) {
    switch (r) {
      case STATIC:
        return "static";
      case TARGETING_MATCH:
        return ruleIndex >= 0 ? "targeting:" + ruleIndex : "targeting";
      case SPLIT:
        return weightedValueIndex >= 0 ? "split:" + weightedValueIndex : "split";
      case DEFAULT:
      case ERROR:
      case UNKNOWN:
      default:
        return "default";
    }
  }

  private static boolean isCompatible(ValueType actual, ValueType requested) {
    if (actual == requested) return true;
    // log_level is stored as STRING-style enum; allow string getters to read it.
    if (requested == ValueType.STRING && actual == ValueType.LOG_LEVEL) return true;
    if (requested == ValueType.STRING && actual == ValueType.DURATION) return true;
    return false;
  }

  private static Object coerceToJavaType(Object payload, ValueType vt, Class<?> javaType) {
    if (payload == null) return null;
    if (vt == ValueType.DURATION) {
      if (payload instanceof Duration) return payload;
      if (payload instanceof String) return Duration.parse((String) payload);
    }
    if (vt == ValueType.INT) {
      if (payload instanceof Long) return payload;
      if (payload instanceof Number) return ((Number) payload).longValue();
    }
    if (vt == ValueType.DOUBLE) {
      if (payload instanceof Double) return payload;
      if (payload instanceof Number) return ((Number) payload).doubleValue();
    }
    if (vt == ValueType.JSON) return payload;
    if (vt == ValueType.STRING_LIST) return payload;
    if (javaType.isInstance(payload)) return payload;
    throw new ClassCastException(payload.getClass() + " is not " + javaType);
  }

  private static Reason mapEngineReason(EvaluationMatch.Reason r) {
    switch (r) {
      case STATIC:
        return Reason.STATIC;
      case TARGETING_MATCH:
        return Reason.TARGETING_MATCH;
      case DEFAULT:
      default:
        return Reason.DEFAULT;
    }
  }

  /**
   * Per qfg-ypcu / project/plans/openfeature-resolution-details.md §3: ruleIndex is included only
   * when reason is {@link Reason#TARGETING_MATCH} or {@link Reason#SPLIT}; weightedValueIndex only
   * on {@link Reason#SPLIT}; environment omitted when not known.
   */
  private Map<String, Object> baseMetadata(
      String configId,
      String configKey,
      String configType,
      Reason reason,
      int ruleIndex,
      int weightedIndex) {
    Map<String, Object> m = new LinkedHashMap<>();
    if (configId != null) m.put("configId", configId);
    if (configKey != null) m.put("configKey", configKey);
    if (configType != null) m.put("configType", configType);
    if (ruleIndex >= 0 && (reason == Reason.TARGETING_MATCH || reason == Reason.SPLIT)) {
      m.put("ruleIndex", ruleIndex);
    }
    if (weightedIndex >= 0 && reason == Reason.SPLIT) {
      m.put("weightedValueIndex", weightedIndex);
    }
    if (effectiveEnvironment != null && !effectiveEnvironment.isEmpty()) {
      m.put("environment", effectiveEnvironment);
    }
    return m;
  }

  private Map<String, Object> metadataFor(
      ConfigRow cfg, Reason reason, int ruleIndex, int weightedIndex) {
    return baseMetadata(cfg.id(), cfg.key(), cfg.type().name(), reason, ruleIndex, weightedIndex);
  }

  private void awaitInit() {
    if (closed) throw new IllegalStateException("Quonfig client is closed");
    try {
      initFuture.get(options.initTimeout().toMillis(), TimeUnit.MILLISECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("interrupted while awaiting init", e);
    } catch (TimeoutException e) {
      throw new IllegalStateException("client initialization exceeded " + options.initTimeout(), e);
    } catch (java.util.concurrent.ExecutionException e) {
      Throwable cause = e.getCause() != null ? e.getCause() : e;
      throw new IllegalStateException(cause.getMessage(), cause);
    }
  }

  private void fireConfigUpdate() {
    for (Runnable r : configUpdateListeners) {
      try {
        r.run();
      } catch (RuntimeException ex) {
        // listeners are user code; never let one tear down the client. Mirror sdk-go's
        // invokeOnConfigUpdate / sdk-node's invokeOnConfigUpdate by logging at ERROR so
        // operators (and chaos scenario 10) can observe that the callback panicked.
        options.logger().error("quonfig: onConfigUpdate callback threw; SDK continuing", ex);
      }
    }
  }

  /** Per-call merge — bound + per-call. Per-call wins on key collision. */
  static ContextSet merge(ContextSet base, ContextSet overlay) {
    if (base == null && overlay == null) return new ContextSet();
    if (base == null) return overlay;
    if (overlay == null) return base;
    ContextSet out = new ContextSet();
    for (Map.Entry<String, Map<String, Object>> e : base.data().entrySet()) {
      out.withNamedContext(e.getKey(), e.getValue());
    }
    for (Map.Entry<String, Map<String, Object>> e : overlay.data().entrySet()) {
      out.withNamedContext(e.getKey(), e.getValue());
    }
    return out;
  }

  /** Read-only in-memory ConfigStore backed by a map of key → ConfigRow. */
  private static final class InMemoryConfigStore implements ConfigStore {
    private final Map<String, ConfigRow> byKey;

    InMemoryConfigStore(List<ConfigRow> rows) {
      Map<String, ConfigRow> m = new LinkedHashMap<>(rows.size());
      List<ConfigRow> ordered = new ArrayList<>(rows);
      for (ConfigRow r : ordered) m.put(r.key(), r);
      this.byKey = Map.copyOf(m);
    }

    @Override
    public ConfigRow getConfig(String key) {
      return byKey.get(key);
    }

    Set<String> keys() {
      return new LinkedHashSet<>(byKey.keySet());
    }
  }
}
