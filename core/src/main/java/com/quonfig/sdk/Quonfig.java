package com.quonfig.sdk;

import com.fasterxml.jackson.databind.DeserializationFeature;
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
import com.quonfig.sdk.exceptions.QuonfigCoercionException;
import com.quonfig.sdk.exceptions.QuonfigDecryptionException;
import com.quonfig.sdk.exceptions.QuonfigEnvVarNotSetException;
import com.quonfig.sdk.exceptions.QuonfigInitException;
import com.quonfig.sdk.exceptions.QuonfigInitTimeoutException;
import com.quonfig.sdk.exceptions.QuonfigKeyNotFoundException;
import com.quonfig.sdk.exceptions.QuonfigTypeMismatchException;
import com.quonfig.sdk.supervisor.FallbackPoller;
import com.quonfig.sdk.supervisor.Supervisor;
import com.quonfig.sdk.telemetry.ContextShapeCollector;
import com.quonfig.sdk.telemetry.ContextUploadMode;
import com.quonfig.sdk.telemetry.EvaluationStat;
import com.quonfig.sdk.telemetry.EvaluationSummaryCollector;
import com.quonfig.sdk.telemetry.ExampleContextCollector;
import com.quonfig.sdk.telemetry.FailoverCollector;
import com.quonfig.sdk.telemetry.HttpTelemetrySender;
import com.quonfig.sdk.telemetry.TelemetryReporter;
import com.quonfig.sdk.telemetry.TelemetrySender;
import com.quonfig.sdk.transport.HttpTransport;
import com.quonfig.sdk.transport.HttpTransportException;
import com.quonfig.sdk.transport.SseClient;
import com.quonfig.sdk.wire.ConfigEnvelope;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.stream.Collectors;
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
 *       Options#streamUrls()}. The constructor blocks until the first config loads, retrying
 *       through blips until {@link Options#initTimeout()}; then {@link Options#onInitFailure()}
 *       decides: {@link OnInitFailure#FAIL} (the default) throws {@link QuonfigInitException},
 *       {@link OnInitFailure#WAIT} keeps blocking, {@link OnInitFailure#CONTINUE} returns on code
 *       defaults.
 *   <li><b>datafile</b> — load a pre-serialized {@link ConfigEnvelope} from {@link
 *       Options#datafile()} (filesystem path) or {@link Options#datafileEnvelope()} (in-memory).
 *       Mirrors sdk-node's {@code datafile?: string | object} shape; the envelope's {@code
 *       meta.environment} supplies the evaluation environment when the caller did not set one
 *       explicitly. Synchronous like datadir mode.
 * </ul>
 *
 * <p>Lifecycle: getters never block on init; before the first config loads they return the caller's
 * default with {@link Reason#ERROR} / {@link ErrorCode#PROVIDER_NOT_READY}. A client that exists
 * (including the one {@link QuonfigInitException#client()} carries after a caught FAIL) is never
 * dead: it retries in the background, and getters serve the real config once one installs. {@link
 * #close()} stops background SSE / polling / init-retry / telemetry threads. {@link #flush()}
 * drains telemetry without closing.
 *
 * <p>Thread-safety: all public methods are safe to call from any thread.
 */
public final class Quonfig implements AutoCloseable, LoggerClient {

  /**
   * Ignores unknown properties so an additive server field (top-level or in {@code meta}) never
   * breaks decoding (qfg-2nvg). Envelope shape is validated explicitly via {@link
   * ConfigEnvelope#isDeliveryEnvelope()}, not by mapper strictness.
   */
  private static final ObjectMapper ENVELOPE_MAPPER =
      new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

  /** Top-level JSON field names {@link ConfigEnvelope} declares (configs, meta). */
  private static final Set<String> ENVELOPE_FIELDS =
      Set.copyOf(
          ENVELOPE_MAPPER
              .getDeserializationConfig()
              .introspect(ENVELOPE_MAPPER.constructType(ConfigEnvelope.class))
              .findProperties()
              .stream()
              .map(p -> p.getName())
              .collect(Collectors.toSet()));

  private static final String CONFIGS_PATH = "/api/v2/configs";

  /**
   * Top-level context name used by {@link #shouldLog(String, Level)} to inject the logger path for
   * per-logger rule evaluation. Load-bearing for api-telemetry's example-context auto-capture; do
   * not rename without updating the matching constants in the other SDKs.
   */
  static final String QUONFIG_SDK_LOGGING_CONTEXT_NAME = "quonfig-sdk-logging";

  private final Options options;

  /**
   * Init outcome. Replaced (never mutated back) by a completed future when the client heals after a
   * total init failure (qfg-9p4i.4), so {@link #initFuture()} reflects the current state. Typed
   * getters gate on {@link #initialized} first, so a caller that holds the old failed future still
   * gets live values from the client once config arrives.
   */
  private volatile CompletableFuture<Void> initFuture;

  private final CopyOnWriteArrayList<Runnable> configUpdateListeners = new CopyOnWriteArrayList<>();
  private final CopyOnWriteArrayList<Consumer<Boolean>> sseListeners = new CopyOnWriteArrayList<>();
  // Config keys already warned about a weighted rollout hashing on a missing property (qfg-9dxb.8).
  private final Set<String> warnedMissingHashProperty = ConcurrentHashMap.newKeySet();
  // Config keys already warned about a typed read or featureIsOn that returned no value: a type
  // mismatch, a malformed value, an unset ENV_VAR or a decryption failure, so each key logs once
  // per client (qfg-zvyy; generalizes the qfg-2agi.8 malformed-duration warning).
  private final Set<String> warnedReadFailure = ConcurrentHashMap.newKeySet();

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

  // Internal Layer 1 counters (qfg-6vno.3), shared by every SseClient this client builds and read
  // only through the package-private *ForTest seams below. See SseClient for what they count.
  private final AtomicLong sseConnectAttempts = new AtomicLong();
  private final AtomicLong sseWorkerRestarts = new AtomicLong();
  // quonfig_sdk_callback_errors_total{callback} (qfg-6vno.28): user callback invocations that threw
  // and were caught. Not worker restarts; read through callbackErrorsTotalForTest.
  private final AtomicLong configUpdateCallbackErrors = new AtomicLong();
  private final AtomicLong connectionStateCallbackErrors = new AtomicLong();
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

  /** {@code meta.version} of the held delivery envelope; logged on recovery. */
  private volatile String heldVersion;

  /** The init thread (first hedged attempt, then the retry loop); null outside delivery mode. */
  private volatile Thread initRetryThread;

  /**
   * Init-progress state shared by the init thread and the blocking constructor / {@link
   * #awaitReady(Duration)} (qfg-9p4i.9). Guarded by {@link #initSignal}; every change notifies it.
   */
  private final Object initSignal = new Object();

  private volatile long initStartNanos;

  /** Failed init attempts so far. */
  private int initAttemptsFailed;

  /** {@code <errs>} of the latest failed attempt (section C). */
  private volatile String initLastErrs = "";

  /**
   * The latest failed attempt got an auth-class status (401/403/404/410) from every leg; {@link
   * #initAuthStatus} holds it.
   */
  private boolean initAuthRejected;

  private int initAuthStatus;

  /** Every leg of the latest failed attempt timed out (no answer), as opposed to failing fast. */
  private boolean initLastAttemptTimedOut;

  /**
   * An init attempt is running and has not finished. When initTimeout cuts it, it is the last
   * attempt (design doc decision 10): {@code timeout} unless one of its legs already answered.
   */
  private boolean initAttemptInFlight;

  /** A leg of the in-flight attempt already failed with an answer (not a timeout). */
  private boolean initInFlightLegAnswered;

  /**
   * Sequence number of the current init attempt. A leg report carries the number of the attempt
   * that fired it, so a late leg of an earlier attempt never marks the current one as answered.
   */
  private int initAttemptSeq;

  /** {@link System#nanoTime()} when the in-flight attempt started. */
  private long initInFlightStartNanos;

  /** The constructor has finished its init decision (returned or thrown). */
  private volatile boolean initDecided;

  /** The constructor decided without a config (CONTINUE, or FAIL whose client survives). */
  private volatile boolean initDecidedOnFailure;

  /** The client healed after a decided init failure; guards the single recovery line. */
  private final java.util.concurrent.atomic.AtomicBoolean initHealed =
      new java.util.concurrent.atomic.AtomicBoolean();

  /**
   * The SLF4J 2 fluent API is usable; cleared on the first LinkageError (slf4j-api 1.x at runtime),
   * after which init lines use the classic calls with an {@code event=} prefix.
   */
  private volatile boolean fluentInitLogging = true;

  /** SSE + the fallback poller have been started (startSse runs once). */
  private final java.util.concurrent.atomic.AtomicBoolean sseStarted =
      new java.util.concurrent.atomic.AtomicBoolean();

  /**
   * Highest stream-URL index the SSE stream has EVER connected on, as reported by {@link
   * SseClient#connectedStreamIndex()}; {@code -1} until the first connect. {@link
   * #sseFailedOverToSecondary()} reports {@code sseStreamIndex > 0}. Because {@link SseClient} pins
   * the stream to {@code streamUrls[0]} (failover is HTTP-poll-only), this latches {@code 0} in
   * practice — but it is derived from the transport's real connection state, not assumed, so the
   * chaos suite's f05 assertion would catch a regression that reintroduces stream-leg walking.
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

  /**
   * Failover-observability counters (qfg-41nh.18) folded into the periodic telemetry flush.
   * Non-null exactly when telemetry is enabled — the counters carry no user data and are the
   * operational signal for the secondary-delivery hardening, so they ride any enabled telemetry
   * stream regardless of the eval/context opt-outs (mirrors sdk-go). {@code null} when telemetry is
   * disabled, so the record helpers are quiet no-ops.
   */
  private final FailoverCollector failoverCollector;

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
      DatadirLoader.requireKnownEnvironment(Path.of(options.datadir()), options.environment());
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
      // they return the caller's default with Reason.ERROR while a background retry keeps
      // fetching until config arrives (qfg-9p4i.4). Once a config installs, SSE starts
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
      if (!oneAttemptInit(options)
          && options.initTimeout().compareTo(options.configFetchHedgeAbort()) <= 0) {
        options
            .logger()
            .warn(
                "quonfig: initTimeout ({}) <= configFetchHedgeAbort ({}); the hedge's per-leg abort "
                    + "should be strictly below initTimeout so a late-but-newer heal leg is not "
                    + "clipped — raise initTimeout or lower configFetchHedgeAbort",
                options.initTimeout(),
                options.configFetchHedgeAbort());
      }
      // A single explicit apiUrls entry disables automatic failover: the SDK's default (and every
      // QUONFIG_DOMAIN-derived) URL list carries both a primary and a secondary leg, and the SDK
      // hedges/fails over between them. An explicit apiUrls override replaces that list wholesale,
      // so a one-entry override silently drops the secondary. Warn once at init (qfg-41nh.26;
      // sdk-go parity). A two-URL override, and the derived default, keep failover and stay quiet.
      if (options.apiUrlsExplicit() && options.apiUrls().size() < 2) {
        options
            .logger()
            .warn(
                "quonfig: explicit apiUrls disables automatic failover to the secondary; pass both "
                    + "primary and secondary URLs to keep it");
      }
      this.initFuture = new CompletableFuture<>();
      this.initStartNanos = System.nanoTime();
      initLog(
          Level.DEBUG,
          "start",
          "[quonfig] init: fetching config (initTimeout={}, onInitFailure={})",
          formatSeconds(options.initTimeout()),
          modeName(options.onInitFailure()));
      Thread t = new Thread(this::runInit, "quonfig-init");
      t.setDaemon(true);
      this.initRetryThread = t;
      t.start();
    }

    TelemetrySender sender = resolveTelemetrySender(options);
    if (sender != null && !options.disableTelemetry()) {
      ContextUploadMode mode = options.contextUploadMode();
      this.summaryCollector =
          new EvaluationSummaryCollector(
              options.collectEvaluationSummaries(), options.telemetryMaxEvaluationSummaries());
      this.shapeCollector =
          new ContextShapeCollector(mode, options.telemetryMaxContextShapeFields());
      this.exampleCollector =
          new ExampleContextCollector(
              mode, options.telemetryMaxExampleContexts(), 60L * 60L * 1000L);
      this.failoverCollector = new FailoverCollector();
      this.telemetryReporter =
          new TelemetryReporter(
              sender,
              options.instanceHash(),
              summaryCollector,
              shapeCollector,
              exampleCollector,
              failoverCollector,
              options);
      this.telemetryReporter.start();
    } else {
      this.summaryCollector = null;
      this.shapeCollector = null;
      this.exampleCollector = null;
      this.failoverCollector = null;
      this.telemetryReporter = null;
    }

    if (initRetryThread != null) {
      // Delivery mode: the constructor is the init call (design B.6). Blocks until the first config
      // loads or onInitFailure decides at initTimeout (qfg-9p4i.9).
      try {
        awaitInitOutcome();
      } catch (QuonfigInitException e) {
        // A caught FAIL leaves this client alive in e.client(): start its update channel now,
        // like a CONTINUE start (sdk-go startBackgroundWorkers parity, qfg-9p4i.17.3).
        if (e.client() == this) startSse();
        throw e;
      }
      // CONTINUE on defaults: SSE + the fallback poller run from the decision point, not only
      // after the background retry heals. On a ready client this is a no-op (already started).
      startSse();
    }
  }

  private static TelemetrySender resolveTelemetrySender(Options options) {
    if (options.telemetrySender() != null) return options.telemetrySender();
    if (options.sdkKey() == null || options.sdkKey().isEmpty()) return null;
    return new HttpTelemetrySender(
        options.telemetryUrl(),
        options.sdkKey(),
        options.telemetryTimeout(),
        options.telemetryConnectTimeout());
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
    synchronized (initSignal) {
      initSignal.notifyAll();
    }
  }

  private void runInit() {
    try {
      List<URI> urls = new ArrayList<>(options.apiUrls().size());
      for (String u : options.apiUrls()) urls.add(URI.create(u));
      HttpTransport http =
          HttpTransport.builder()
              .urls(urls)
              .sdkKey(options.sdkKey())
              .timeout(fetchBudget(options))
              // Per-URL config-fetch deadline so a hung primary fails over to the secondary inside
              // the init budget instead of starving it until initTimeout (qfg-7h5d.1.10). Still
              // governs the sequential refresh()/fallback-poll path; the hedged init fetch below
              // uses its own hedgeDelay/hedgeAbort.
              .configFetchTimeout(options.configFetchTimeout())
              .build();
      this.httpTransport = http;
      if (fetchInitialHedged(http)) {
        initRetryLoop(http, initStartNanos);
      }
    } catch (Exception e) {
      // Misconfigured URLs (the transport could not be built): nothing to retry against. The
      // constructor reports it at initTimeout per onInitFailure.
      Throwable cause = e.getCause() != null ? e.getCause() : e;
      onInitAttemptFailed(
          "invalid apiUrls (" + cause.getClass().getSimpleName() + ")", 0, isTimeout(cause));
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
  private boolean fetchInitialHedged(HttpTransport http) {
    int attemptSeq = beginInitAttempt();
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
    // Per-leg errors for the init-failure log line (section C <errs>), keyed by leg index.
    Map<Integer, Throwable> legErrorByIndex = new java.util.concurrent.ConcurrentSkipListMap<>();

    CompletableFuture<Void> primaryLeg =
        fireHedgeLeg(http, 0, hedgeAbortMs, readyLatched, legErrors, legErrorByIndex, attemptSeq);

    if (hasSecondary) {
      Runnable fireSecondary =
          () -> {
            if (secondaryFired.compareAndSet(false, true)) {
              secondaryLeg.set(
                  fireHedgeLeg(
                      http, 1, hedgeAbortMs, readyLatched, legErrors, legErrorByIndex, attemptSeq));
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
      // The hedge fired its secondary leg this cycle (the primary was slow or errored). Recorded
      // once per hedged cycle regardless of which leg's payload won the guard (qfg-41nh.18). The
      // hedge is init-only in sdk-java; refresh()/fallback-poll walk the leg list sequentially and
      // never fire a parallel hedge, so this is the sole hedge-fired site.
      recordHedgeFired();
      sec.exceptionally(t -> null).join();
    }

    if (readyLatched.get()) {
      // At least one leg installed and already latched readiness + started SSE (see fireHedgeLeg).
      return false;
    }

    // Nothing installed. Preserve the init-failure contract: surface the failure so getters apply
    // OnInitFailure (init-throw). A 304 with no store installed is a leg error (qfg-6vno.11), so
    // legErrors is empty only when every leg 304'd over an already-installed store (no change) —
    // still a successful, ready client; complete normally.
    if (!legErrors.isEmpty()) {
      StringBuilder errs = new StringBuilder();
      int authStatus = legErrorByIndex.size() == http.legCount() ? 0 : -1;
      boolean allTimedOut = true;
      for (Map.Entry<Integer, Throwable> le : legErrorByIndex.entrySet()) {
        if (errs.length() > 0) errs.append("; ");
        errs.append(legLabel(le.getKey()))
            .append('=')
            .append(legHost(le.getKey()))
            .append(": ")
            .append(describeLegError(le.getValue()));
        int st =
            le.getValue() instanceof HttpTransportException
                ? ((HttpTransportException) le.getValue()).statusCode()
                : 0;
        if (authStatus >= 0) authStatus = isAuthClassStatus(st) ? st : -1;
        allTimedOut &= isTimeout(le.getValue());
      }
      onInitAttemptFailed(errs.toString(), Math.max(authStatus, 0), allTimedOut);
      return true;
    }
    this.initialized = true;
    completeInitFuture();
    synchronized (initSignal) {
      initSignal.notifyAll();
    }
    return false;
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
      java.util.List<Throwable> legErrors,
      Map<Integer, Throwable> legErrorByIndex,
      int attemptSeq) {
    // The returned future reflects the RAW leg outcome so the hedge arbiter can distinguish a fast
    // error (hedge now) from a fast success (suppress the hedge): it completes exceptionally on a
    // transport/timeout/decode error and normally on a successful resolve. The install + readiness
    // latch are side effects performed inside thenApply before the success propagates.
    // The JDK request timeout is the hedge abort too, so the leg is bounded by hedgeAbort (design
    // default 6s) and not by the shorter sequential configFetchTimeout (qfg-9p4i.17.9).
    return http.getFrom(legIndex, URI.create(CONFIGS_PATH), null, Duration.ofMillis(abortMs))
        .orTimeout(abortMs, TimeUnit.MILLISECONDS)
        .thenAccept(
            resp -> {
              boolean installed;
              if (resp.statusCode() == 304 && store == null) {
                // Init sends no If-None-Match, so a 304 here is a protocol violation (a misbehaving
                // proxy/middlebox). With nothing installed it must be a leg error, not an answered
                // leg: latching readiness on it would complete initFuture with no store and stop
                // the init retry loop, wedging the client on code defaults (qfg-6vno.11).
                throw new java.util.concurrent.CompletionException(
                    new HttpTransportException(
                        304,
                        "",
                        "304 Not Modified with no config installed (no If-None-Match sent)"));
              }
              try {
                installed = resp.statusCode() != 304 && installDeliveryBody(resp.body(), legIndex);
              } catch (IOException e) {
                throw new java.util.concurrent.CompletionException(e);
              }
              // An answered leg is a successful refresh whether or not it installed — a 304 or a
              // guard-dropped 200 still proves the fetch loop is alive (qfg-41nh.15; sdk-go
              // qfg-41nh.11 parity). A parse failure threw above and never stamps.
              recordSuccessfulRefresh();
              // Latch readiness on the first leg to resolve successfully — complete initFuture and
              // start SSE exactly once. The first leg on a fresh client always installs (etag is
              // null, the generation guard accepts the first snapshot), so the latch coincides with
              // a real install.
              if (readyLatched.compareAndSet(false, true)) {
                completeInitFuture();
                if (initDecidedOnFailure && initHealed.compareAndSet(false, true)) {
                  // The constructor already gave up (CONTINUE at initTimeout, or a caught FAIL)
                  // while this first attempt was still in flight: this is the heal (section C).
                  // Not initDecided: this leg's install wakes the constructor, which marks init
                  // decided on SUCCESS before we get here, and a rescued boot must log no init
                  // WARN (section C, D case 9; chaos f01).
                  logRecovered(initStartNanos, legIndex);
                }
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
                Throwable cause = t.getCause() != null ? t.getCause() : t;
                legErrors.add(cause);
                legErrorByIndex.putIfAbsent(legIndex, cause);
                onInitLegFailed(attemptSeq, cause);
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
    return installDelivery(parseDeliveryEnvelope(body), sourceIndex);
  }

  /**
   * Decodes a delivery response body and rejects anything that is not a real envelope (no {@code
   * meta} object with a non-empty {@code version}; see {@link
   * ConfigEnvelope#isDeliveryEnvelope()}). A {@code {}} or error-object 200 would otherwise decode
   * to an empty envelope and wipe every held config (qfg-9dxb.3). The check is independent of the
   * mapper's unknown-property handling.
   */
  private static ConfigEnvelope parseDeliveryEnvelope(String body) throws IOException {
    ConfigEnvelope envelope = ENVELOPE_MAPPER.readValue(body, ConfigEnvelope.class);
    if (envelope == null || !envelope.isDeliveryEnvelope()) {
      throw new IOException("not a config envelope (missing meta.version)");
    }
    return envelope;
  }

  /**
   * One sequential config fetch ({@code refresh()} and the fallback poller): walks the [primary,
   * secondary] legs and installs the first answer through the reject-older guard. A 200 whose body
   * is not a valid envelope is a leg error, so the walk fails over to the next leg instead of
   * returning it (qfg-9dxb.3). Returns {@code true} iff an envelope was installed; throws when
   * every leg failed.
   */
  private boolean fetchAndInstallSequential(HttpTransport http) throws Exception {
    return fetchAndInstallSequential(http, null);
  }

  /** {@code onLegFailed} sees each leg that fails during the walk (see {@link HttpTransport}). */
  private boolean fetchAndInstallSequential(
      HttpTransport http, java.util.function.Consumer<Throwable> onLegFailed) throws Exception {
    java.util.concurrent.atomic.AtomicReference<ConfigEnvelope> parsed =
        new java.util.concurrent.atomic.AtomicReference<>();
    HttpResponse<String> resp =
        http.get(
                URI.create(CONFIGS_PATH),
                null,
                body -> {
                  try {
                    parsed.set(parseDeliveryEnvelope(body));
                    return true;
                  } catch (IOException e) {
                    options
                        .logger()
                        .debug("quonfig: rejecting config response body: {}", e.getMessage());
                    return false;
                  }
                },
                onLegFailed)
            .get(fetchBudget(options).toMillis(), TimeUnit.MILLISECONDS);
    if (resp.statusCode() == 304) {
      return false;
    }
    return installDelivery(parsed.get(), http.lastResolvedIndex());
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
   *       can't re-install or flap. It is deliberately NOT counted as a guard rejection — only a
   *       strictly older payload is (qfg-rr5b); see the accounting note at the guard itself.
   *   <li>An unversioned snapshot (generation absent or {@code <= 0}) installs ONLY while the
   *       client has never held a real generation ({@code heldGeneration == 0}), so a client that
   *       has only ever seen gen-0 servers (e.g. {@code qfg serve}) keeps taking each payload. Once
   *       a positive generation is held, a gen-0 payload is a silent no-op (qfg-9dxb.9): the
   *       pre-watermark servers that sent gen 0 on every payload are long gone, and gen 0 now only
   *       comes from a server whose git object store is damaged (rev-count failed) — it must not
   *       override a held real generation. It is not counted as a guard rejection, since it is not
   *       provably older. An unversioned install never changes the held generation (qfg-9dxb.3).
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
      if (configInstalls != 0 && incoming <= 0 && heldGeneration > 0) {
        // Unversioned payload on a client that holds a real generation (qfg-9dxb.9): gen 0 today
        // only comes from a damaged-store server (rev-count failed), so it must not override the
        // held envelope. Silent no-op — not provably older, so NOT counted as guardRejected.
        options
            .logger()
            .debug(
                "quonfig: unversioned payload while holding generation {} — no-op", heldGeneration);
        return false;
      }
      if (configInstalls != 0 && incoming > 0 && incoming <= heldGeneration) {
        // Reject-older / same-generation: keep the held envelope, do not flap.
        //
        // Accounting (qfg-rr5b, narrows qfg-41nh.18): only a STRICTLY older payload is counted as
        // a guard rejection — "a leg tried to move us backwards" is the one thing guardRejected is
        // meant to report, and what the sdk_failover dashboard panel reads. Two healthy server
        // behaviors re-deliver the generation the client already holds: api-delivery re-sends the
        // current envelope on every SSE connect (this SDK sends no Last-Event-Id), and a config
        // poll whose per-leg ETag slot is empty (a fresh transport, a reconnect, the fallback
        // poller's engage-time fetch) answers with a full 200 at the same generation. Counting
        // those made a healthy client report failover activity it never had. An equal-generation
        // re-delivery is a silent no-op: still not installed, still advances liveness exactly where
        // it did before, but not counted.
        // Applies to every install path — the HTTP reject-older drop AND the SSE guard no-op both
        // funnel through here.
        if (incoming < heldGeneration) {
          recordGuardRejected();
        } else {
          options
              .logger()
              .debug(
                  "quonfig: same-generation re-delivery at generation {} — no-op, not counted as a"
                      + " guard rejection",
                  incoming);
        }
        return false;
      }
      // Parse row by row BEFORE touching any state: a bad row is skipped with a WARN, and an
      // envelope whose every row is bad throws here, leaving the held envelope, environment and
      // generation untouched (qfg-goi1.2.16; sdk-go qfg-9dxb.6 parity).
      List<ConfigRow> rows = parseDeliveryRows(envelope);
      // Initial HTTP fetch and fallback poll are delivery mode: meta.environment is authoritative.
      applyMetaEnvironment(envelope, true);
      installRows(rows);
      // An unversioned install (incoming <= 0) only reaches here on a first install or while held
      // is still 0 (qfg-9dxb.9); it never changes the held generation (qfg-9dxb.3). A positive
      // incoming is strictly greater here (or this is the first install), so this keeps the max.
      if (incoming > 0) {
        heldGeneration = incoming;
      }
      heldVersion = envelope.meta() != null ? envelope.meta().version() : null;
      configInstalls++;
      if (sourceIndex >= 0) {
        resolvedFromIndex = sourceIndex;
        // Failover observability: record which leg (primary/secondary) served this successful HTTP
        // install. SSE installs pass sourceIndex=-1 and are not counted (qfg-41nh.18).
        recordResolvedFrom(sourceIndex);
      }
      return true;
    }
  }

  /**
   * Parses a delivery envelope (HTTP or SSE) row by row. A row that fails to parse (for example one
   * missing {@code type}) is skipped with a WARN naming its key, and the rest install: one bad row
   * must not freeze every other config on its last envelope. If every row fails, the first error is
   * rethrown so the envelope is rejected as before and can never install as an empty workspace
   * (sdk-go 2f6b98b / qfg-9dxb.6 parity). An envelope with zero rows is a legitimately empty
   * workspace and still installs. Datafile mode stays strict ({@link #installEnvelopeRows}).
   */
  private List<ConfigRow> parseDeliveryRows(ConfigEnvelope envelope) {
    List<JsonNode> configs = envelope.configs();
    List<ConfigRow> rows = new ArrayList<>(configs.size());
    RuntimeException first = null;
    for (JsonNode cfg : configs) {
      try {
        rows.add(DatadirLoader.parseConfigNode(cfg));
      } catch (RuntimeException e) {
        if (first == null) first = e;
        options
            .logger()
            .warn(
                "quonfig: skipping config that failed to parse; other configs are unaffected"
                    + " (key={}, version={}): {}",
                cfg != null ? cfg.path("key").asText("") : "",
                envelope.meta() != null ? envelope.meta().version() : "",
                e.toString());
      }
    }
    if (rows.isEmpty() && first != null) {
      throw first;
    }
    return rows;
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
      // ENVELOPE_MAPPER ignores unknown fields (qfg-2nvg), so a file that is not a datafile (an
      // error object, a single per-config file) would decode to zero configs. Reject a non-object,
      // or an object with no "configs" that carries a field the envelope does not declare. Shapes
      // v1.3.0 loaded as zero configs ({}, {"configs":null}, meta only) still load.
      JsonNode tree = ENVELOPE_MAPPER.readTree(Files.readAllBytes(file));
      if (tree == null || !tree.isObject()) {
        throw new IllegalStateException(
            "datafile "
                + file
                + " is not a Quonfig datafile: expected a top-level \"configs\" array");
      }
      if (!tree.has("configs")) {
        Iterator<String> names = tree.fieldNames();
        while (names.hasNext()) {
          String name = names.next();
          if (!ENVELOPE_FIELDS.contains(name)) {
            throw new IllegalStateException(
                "datafile "
                    + file
                    + " is not a Quonfig datafile: unexpected top-level field \""
                    + name
                    + "\" and no \"configs\"");
          }
        }
      }
      return ENVELOPE_MAPPER.treeToValue(tree, ConfigEnvelope.class);
    } catch (IOException e) {
      throw new IllegalStateException("failed to read datafile " + file + ": " + e.getMessage(), e);
    }
  }

  /** Upper bound on the background init-retry backoff (design B.3: 0.5s doubling, cap 30s). */
  private static final long INIT_RETRY_MAX_BACKOFF_MS = 30_000L;

  /** Progress WARN cadence while the constructor blocks before initTimeout (section C). */
  private static final long INIT_PROGRESS_INTERVAL_NANOS = TimeUnit.SECONDS.toNanos(5);

  /** WAIT-mode progress WARN cadence once initTimeout has passed (section C). */
  private static final long WAIT_PROGRESS_INTERVAL_NANOS = TimeUnit.SECONDS.toNanos(30);

  /**
   * An init attempt starts: none of its legs has answered yet. Returns the attempt's sequence
   * number, which its leg reports carry (see {@link #onInitLegFailed}).
   */
  private int beginInitAttempt() {
    synchronized (initSignal) {
      initAttemptInFlight = true;
      initInFlightLegAnswered = false;
      initInFlightStartNanos = System.nanoTime();
      return ++initAttemptSeq;
    }
  }

  /**
   * A leg of attempt {@code attemptSeq} failed. An answer (any status, a rejected body, or a fast
   * transport error such as a refused connect) means the leg did not hang; a request timeout does.
   * The cause is the leg's own error, never a wrapper whose cause chain names another leg.
   */
  private void onInitLegFailed(int attemptSeq, Throwable cause) {
    if (isTimeout(cause)) return;
    synchronized (initSignal) {
      if (attemptSeq == initAttemptSeq) initInFlightLegAnswered = true;
    }
  }

  /** True when no leg of the current attempt has answered: every leg timed out or is pending. */
  private boolean initAttemptHung() {
    synchronized (initSignal) {
      return !initInFlightLegAnswered;
    }
  }

  /**
   * The reason for a failed init at the deadline (design doc decisions 7 and 10, the sdk-go rule):
   * {@code timeout} when the last attempt hung or none completed, else {@code unavailable}. An
   * attempt the deadline cuts while still in flight is the last attempt: it hung unless one of its
   * legs already answered. An attempt that started at or after the deadline (the retry loop can
   * begin one before this thread wakes) does not count. Caller holds {@link #initSignal}.
   */
  private QuonfigInitException.Reason deadlineReason(long deadline) {
    boolean cut = initAttemptInFlight && initInFlightStartNanos - deadline < 0;
    boolean hung =
        cut ? !initInFlightLegAnswered : initAttemptsFailed == 0 || initLastAttemptTimedOut;
    return hung ? QuonfigInitException.Reason.TIMEOUT : QuonfigInitException.Reason.UNAVAILABLE;
  }

  /**
   * Records one failed init attempt (every fired leg failed) and wakes the blocking constructor.
   * {@code authStatus} is 401/403 when every leg rejected the SDK key, else 0. The first failure
   * before the init decision is the "start of wait" WARN (section C); a healthy boot never logs
   * init above DEBUG.
   */
  private void onInitAttemptFailed(String errs, int authStatus, boolean timedOut) {
    errs = redactUserinfo(errs);
    int attempts;
    synchronized (initSignal) {
      attempts = ++initAttemptsFailed;
      initLastErrs = errs;
      initAuthRejected = isAuthClassStatus(authStatus);
      initAuthStatus = authStatus;
      initLastAttemptTimedOut = timedOut;
      initAttemptInFlight = false;
      initSignal.notifyAll();
    }
    if (attempts == 1 && !initDecided && !closed) {
      initLog(
          Level.WARN,
          "retrying",
          "[quonfig] init: config not loaded yet, retrying until initTimeout ({}). {}",
          formatSeconds(options.initTimeout()),
          errs);
    }
  }

  /**
   * The constructor's init gate (design B.3, section C). Blocks until the first config loads, then
   * returns. Otherwise, at {@link Options#initTimeout()} (or at once when every leg rejected the
   * key) applies {@link Options#onInitFailure()}: FAIL throws {@link QuonfigInitException} carrying
   * this still-retrying client, CONTINUE returns on code defaults, WAIT keeps blocking (an auth
   * rejection is logged and retried: the key may be provisioned late). Logs the section C lines.
   */
  private void awaitInitOutcome() {
    OnInitFailure mode = options.onInitFailure();
    long start = initStartNanos;
    // initTimeout <= 0 (design doc decision 8): run exactly one attempt, then apply the policy.
    boolean oneAttempt = oneAttemptInit(options);
    long timeoutNanos = oneAttempt ? 0L : options.initTimeout().toNanos();
    long deadline = start + timeoutNanos;
    long nextProgress = start + INIT_PROGRESS_INTERVAL_NANOS;
    long nextWaitProgress = Long.MAX_VALUE;
    boolean authLogged = false;
    boolean waitTimeoutLogged = false;
    synchronized (initSignal) {
      try {
        while (true) {
          if (initialized) {
            initDecided = true;
            return;
          }
          long now = System.nanoTime();
          if (initAuthRejected) {
            if (mode == OnInitFailure.FAIL) {
              throw initFailed(QuonfigInitException.Reason.UNAUTHORIZED, now);
            }
            if (mode == OnInitFailure.CONTINUE) {
              continueOnDefaults(QuonfigInitException.Reason.UNAUTHORIZED, now);
              return;
            }
            if (!authLogged) {
              authLogged = true;
              initLog(
                  Level.ERROR,
                  "unauthorized",
                  "[quonfig] init: SDK key rejected ({}) by every delivery leg. Check the key"
                      + " and environment. onInitFailure=wait: still blocking startup and"
                      + " retrying (the key may be provisioned late). {}",
                  initAuthStatus,
                  initLastErrs);
            }
          }
          boolean pastDeadline = oneAttempt ? initAttemptsFailed > 0 : now - deadline >= 0;
          if (pastDeadline && mode != OnInitFailure.WAIT) {
            QuonfigInitException.Reason reason = deadlineReason(deadline);
            if (mode == OnInitFailure.FAIL) throw initFailed(reason, now);
            continueOnDefaults(reason, now);
            return;
          }
          if (pastDeadline && !waitTimeoutLogged) {
            waitTimeoutLogged = true;
            nextWaitProgress = now + WAIT_PROGRESS_INTERVAL_NANOS;
            initLog(
                Level.WARN,
                "wait",
                "[quonfig] init: no config after {}s. onInitFailure=wait: still blocking startup"
                    + " and retrying indefinitely. {}. Use onInitFailure=fail or continue to"
                    + " bound startup.",
                seconds(now - start),
                initLastErrs);
          } else if (pastDeadline && now - nextWaitProgress >= 0) {
            nextWaitProgress = now + WAIT_PROGRESS_INTERVAL_NANOS;
            initLog(
                Level.WARN,
                "waiting",
                "[quonfig] init: still blocked waiting for config ({} elapsed, attempt {}). {}",
                formatElapsed(TimeUnit.NANOSECONDS.toSeconds(now - start)),
                initAttemptsFailed + 1,
                initLastErrs);
          } else if (!pastDeadline && now - nextProgress >= 0) {
            nextProgress += INIT_PROGRESS_INTERVAL_NANOS;
            if (initAttemptsFailed > 0) {
              initLog(
                  Level.WARN,
                  "waiting",
                  "[quonfig] init: still waiting for config ({}s elapsed of {}, attempt {}). {}",
                  seconds(now - start),
                  formatSeconds(options.initTimeout()),
                  initAttemptsFailed + 1,
                  initLastErrs);
            }
          }
          long wakeAt =
              pastDeadline
                  ? nextWaitProgress
                  : oneAttempt ? nextProgress : Math.min(deadline, nextProgress);
          long waitMs = Math.max(1L, TimeUnit.NANOSECONDS.toMillis(wakeAt - now) + 1);
          initSignal.wait(waitMs);
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        initDecided = true;
        QuonfigInitException ex =
            new QuonfigInitException(
                QuonfigInitException.Reason.UNAVAILABLE,
                "[quonfig] init interrupted before a config loaded",
                e,
                this);
        initFuture.completeExceptionally(ex);
        throw ex;
      }
    }
  }

  /** FAIL: logs the section C ERROR line and builds the exception carrying this client. */
  private QuonfigInitException initFailed(QuonfigInitException.Reason reason, long now) {
    initDecidedOnFailure = true;
    initDecided = true;
    String msg;
    if (reason == QuonfigInitException.Reason.UNAUTHORIZED) {
      msg =
          "[quonfig] init FAILED: SDK key rejected ("
              + initAuthStatus
              + ") by every delivery leg. Check the key and environment. onInitFailure=fail:"
              + " raising now. "
              + initLastErrs;
      initLog(
          Level.ERROR,
          "unauthorized",
          "[quonfig] init FAILED: SDK key rejected ({}) by every delivery leg. Check the key"
              + " and environment. onInitFailure=fail: raising now. {}",
          initAuthStatus,
          initLastErrs);
    } else {
      String errs = initLastErrs.isEmpty() ? "no attempt finished" : initLastErrs;
      msg =
          "[quonfig] init FAILED: no config after " + seconds(now - initStartNanos) + "s. " + errs;
      initLog(
          Level.ERROR,
          "failed",
          "[quonfig] init FAILED: no config after {}s. {}. onInitFailure=fail: raising"
              + " QuonfigInitException. Options: onInitFailure=wait to keep waiting,"
              + " onInitFailure=continue to start on code defaults, or raise initTimeout.",
          seconds(now - initStartNanos),
          errs);
    }
    QuonfigInitException ex =
        reason == QuonfigInitException.Reason.TIMEOUT
            ? new QuonfigInitTimeoutException(msg, null, this)
            : new QuonfigInitException(reason, msg, null, this);
    initFuture.completeExceptionally(ex);
    return ex;
  }

  /** CONTINUE: logs the section C ERROR line (server SDK) and lets the constructor return. */
  private void continueOnDefaults(QuonfigInitException.Reason reason, long now) {
    initDecidedOnFailure = true;
    initDecided = true;
    String errs = initLastErrs.isEmpty() ? "no attempt finished" : initLastErrs;
    if (reason == QuonfigInitException.Reason.UNAUTHORIZED) {
      initLog(
          Level.ERROR,
          "unauthorized",
          "[quonfig] init FAILED: SDK key rejected ({}) by every delivery leg. Check the key"
              + " and environment. onInitFailure=continue: STARTING ON CODE DEFAULTS. This"
              + " instance may disagree with the rest of your fleet. Retrying in background. {}",
          initAuthStatus,
          errs);
    } else {
      initLog(
          Level.ERROR,
          "continue",
          "[quonfig] init: no config after {}s. onInitFailure=continue: STARTING ON CODE"
              + " DEFAULTS. This instance may disagree with the rest of your fleet. Retrying in"
              + " background. {}",
          seconds(now - initStartNanos),
          errs);
    }
    initFuture.completeExceptionally(
        new QuonfigInitException(
            reason, "[quonfig] init: no config loaded; continuing on code defaults", null, null));
  }

  private static String seconds(long nanos) {
    return String.format(java.util.Locale.ROOT, "%.1f", nanos / 1_000_000_000.0);
  }

  /** {@code 10s} / {@code 1.5s}: a Duration in seconds for the init log lines. */
  private static String formatSeconds(Duration d) {
    long ms = d.toMillis();
    return ms % 1000 == 0
        ? (ms / 1000) + "s"
        : String.format(java.util.Locale.ROOT, "%.1fs", ms / 1000.0);
  }

  private static String modeName(OnInitFailure mode) {
    return mode.name().toLowerCase(Locale.ROOT);
  }

  /** True when {@code t} (or a cause) is a request timeout: the leg never answered. */
  private static boolean isTimeout(Throwable t) {
    for (Throwable c = t; c != null; c = c.getCause()) {
      if (c instanceof TimeoutException || c instanceof java.net.http.HttpTimeoutException) {
        return true;
      }
      if (c.getCause() == c) break;
    }
    return false;
  }

  /**
   * Background retry after a total init failure. Fetches with jittered exponential backoff (0.5s,
   * 1s, 2s, ... capped at 30s) until a config installs (here, or via {@link #refresh()}) or the
   * client closes, then heals: completes {@link #initFuture()}, logs the recovery at WARN, and
   * starts SSE plus the fallback poller exactly as a successful first fetch does. While not ready
   * it logs a WARN every 30s for 5 minutes, then every 5 minutes (section C).
   */
  private void initRetryLoop(HttpTransport http, long startNanos) {
    String lastErrs = initLastErrs;
    long nextWarnNanos = startNanos + TimeUnit.SECONDS.toNanos(30);
    int attempt = 0;
    while (!closed) {
      if (initialized) {
        healAfterInitFailure(startNanos, false);
        return;
      }
      long base = Math.min(INIT_RETRY_MAX_BACKOFF_MS, 500L << Math.min(attempt, 6));
      long jittered =
          base / 2 + java.util.concurrent.ThreadLocalRandom.current().nextLong(base / 2 + 1);
      Duration wait = Duration.ofMillis(jittered);
      java.util.function.BiFunction<String, Duration, Duration> hook = options.waitHookForTest();
      if (hook != null) wait = hook.apply("init-retry", wait);
      try {
        Thread.sleep(Math.max(0L, wait.toMillis()));
      } catch (InterruptedException e) {
        if (closed) return;
        Thread.currentThread().interrupt();
        return;
      }
      if (closed) return;
      attempt++;
      int attemptSeq = beginInitAttempt();
      try {
        boolean installed =
            fetchAndInstallSequential(http, cause -> onInitLegFailed(attemptSeq, cause));
        recordSuccessfulRefresh();
        if (initialized) {
          healAfterInitFailure(startNanos, installed);
          return;
        }
      } catch (Exception e) {
        Throwable cause = e;
        while ((cause instanceof java.util.concurrent.ExecutionException
                || cause instanceof java.util.concurrent.CompletionException)
            && cause.getCause() != null) {
          cause = cause.getCause();
        }
        lastErrs = describeSequentialFailure(cause);
        options.logger().debug("[quonfig] init retry {} failed: {}", attempt, lastErrs);
        // Hung iff no leg answered (design doc decision 7). Not isTimeout(cause): when the primary
        // answered 401/503 and the secondary timed out, the walk's error wraps the secondary's
        // HttpTimeoutException, yet the attempt did not hang (qfg-9p4i.17.9).
        onInitAttemptFailed(lastErrs, sequentialAuthStatus(cause), initAttemptHung());
      }
      long now = System.nanoTime();
      // Background WARNs start once the constructor has decided (CONTINUE, or a caught FAIL);
      // while it still blocks (WAIT, or before initTimeout) it logs its own progress lines.
      if (initDecided && now - nextWarnNanos >= 0 && !closed && !initialized) {
        long elapsedS = TimeUnit.NANOSECONDS.toSeconds(now - startNanos);
        initLog(
            Level.WARN,
            "not_connected",
            "[quonfig] still not connected to Quonfig delivery ({} since start); serving"
                + " defaults. {}",
            formatElapsed(elapsedS),
            lastErrs);
        long step = elapsedS < 300 ? 30 : 300;
        nextWarnNanos = now + TimeUnit.SECONDS.toNanos(step);
      }
    }
  }

  private void healAfterInitFailure(long startNanos, boolean installedHere) {
    if (closed) return;
    // SSE, the fallback poller, refresh() and the retry loop can each install the first config
    // after the init decision; only the first heals (one recovery line, one ready transition).
    if (!initHealed.compareAndSet(false, true)) return;
    completeInitFuture();
    logRecovered(startNanos, resolvedFromIndex);
    if (installedHere) fireConfigUpdate();
    startSse();
  }

  /**
   * Marks init complete. Within the init window the original future is still pending: complete it.
   * After a decided failure it holds the failure: replace it so {@link #initFuture()} reflects the
   * current state.
   */
  private void completeInitFuture() {
    CompletableFuture<Void> f = this.initFuture;
    if (!f.complete(null) && f.isCompletedExceptionally()) {
      this.initFuture = CompletableFuture.completedFuture(null);
    }
  }

  /** Heal line (section C): WARN, the level that saw the failure. {@code idx} -1 = refresh(). */
  private void logRecovered(long startNanos, int idx) {
    initLog(
        Level.WARN,
        "recovered",
        "[quonfig] init recovered: config loaded after {}s via {} (version {}). Now serving"
            + " live config.",
        seconds(System.nanoTime() - startNanos),
        idx >= 0 ? legLabel(idx) : "stream",
        heldVersion);
  }

  /**
   * After the init decision (CONTINUE, or a caught FAIL), an install by SSE, the fallback poller or
   * {@link #refresh()} heals the client right away instead of waiting for the background retry to
   * wake (its backoff reaches 30s).
   */
  private void healIfDecided() {
    if (initDecidedOnFailure && initialized) {
      healAfterInitFailure(initStartNanos, false);
    }
  }

  /**
   * Logs one init line (section C) with an {@code event=quonfig.init.<name>} key-value (SLF4J 2
   * fluent API; a logger without key-value support prints it as a {@code event=...} prefix).
   */
  private void initLog(Level level, String event, String format, Object... args) {
    String ev = "quonfig.init." + event;
    org.slf4j.Logger log = options.logger();
    if (fluentInitLogging) {
      try {
        log.atLevel(level).addKeyValue("event", ev).log(format, args);
        return;
      } catch (LinkageError e) {
        // slf4j-api 1.x won the classpath (e.g. pinned by a Spring Boot 2 BOM): there is no
        // fluent API at runtime. Never let a log line break construction; use the classic calls.
        fluentInitLogging = false;
      }
    }
    String f = "event=" + ev + " " + format;
    switch (level) {
      case ERROR:
        log.error(f, args);
        break;
      case WARN:
        log.warn(f, args);
        break;
      case INFO:
        log.info(f, args);
        break;
      case DEBUG:
        log.debug(f, args);
        break;
      default:
        log.trace(f, args);
    }
  }

  /** {@code initTimeout <= 0}: run exactly one attempt, then apply onInitFailure (decision 8). */
  private static boolean oneAttemptInit(Options options) {
    Duration t = options.initTimeout();
    return t.isZero() || t.isNegative();
  }

  /**
   * Bound for one sequential config fetch (background retry, refresh(), fallback poll). It is
   * initTimeout, except that a {@code <= 0} initTimeout (one init attempt, decision 8) must not
   * clip every later fetch to zero, so those fall back to {@link Options#DEFAULT_INIT_TIMEOUT}.
   */
  private static Duration fetchBudget(Options options) {
    return oneAttemptInit(options) ? Options.DEFAULT_INIT_TIMEOUT : options.initTimeout();
  }

  /**
   * One leg's error for the init {@code <errs>}: {@code HTTP <status>}, {@code timed out}, or a
   * short description. Never the transport message, which names the request URL (design doc
   * decision 9: hosts and statuses only, no userinfo, no response bodies).
   */
  private static String describeLegError(Throwable t) {
    if (t instanceof HttpTransportException && ((HttpTransportException) t).statusCode() > 0) {
      return "HTTP " + ((HttpTransportException) t).statusCode();
    }
    if (isTimeout(t)) return "timed out";
    for (Throwable c = t; c != null; c = c.getCause()) {
      if (c instanceof java.io.IOException && !(c instanceof java.net.http.HttpTimeoutException)) {
        if (c instanceof com.fasterxml.jackson.core.JsonProcessingException
            || String.valueOf(c.getMessage()).startsWith("not a config envelope")) {
          return "invalid config response";
        }
        return "no response (" + c.getClass().getSimpleName() + ")";
      }
      if (c.getCause() == c) break;
    }
    return "no response (" + t.getClass().getSimpleName() + ")";
  }

  /** Strips URL userinfo ({@code scheme://user:pass@host}) from a log/exception string. */
  static String redactUserinfo(String s) {
    if (s == null || s.indexOf('@') < 0) return s;
    return s.replaceAll("(?i)([a-z][a-z0-9+.-]*://)[^/@\\s]+@", "$1");
  }

  /** An auth-class status (401/403/404/410) when every leg of a sequential walk got one, else 0. */
  private static int sequentialAuthStatus(Throwable cause) {
    if (!(cause instanceof HttpTransportException)) return 0;
    int[] legs = ((HttpTransportException) cause).legStatuses();
    if (legs.length == 0) return 0;
    for (int st : legs) if (!isAuthClassStatus(st)) return 0;
    return legs[0];
  }

  private String describeSequentialFailure(Throwable cause) {
    if (cause instanceof HttpTransportException) {
      int[] legs = ((HttpTransportException) cause).legStatuses();
      if (legs.length > 0) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < legs.length; i++) {
          if (sb.length() > 0) sb.append("; ");
          sb.append(legLabel(i)).append('=').append(legHost(i)).append(": ");
          sb.append(legs[i] > 0 ? "HTTP " + legs[i] : "no response");
        }
        return sb.toString();
      }
    }
    return describeLegError(cause);
  }

  private static String legLabel(int idx) {
    return idx == 0 ? "primary" : idx == 1 ? "secondary" : "leg" + idx;
  }

  /** host[:port] of base-URL leg {@code idx}; never carries the SDK key or URL credentials. */
  private String legHost(int idx) {
    String raw = idx < options.apiUrls().size() ? options.apiUrls().get(idx) : "";
    try {
      URI u = URI.create(raw);
      if (u.getHost() != null) {
        return u.getPort() >= 0 ? u.getHost() + ":" + u.getPort() : u.getHost();
      }
    } catch (IllegalArgumentException ignored) {
      // fall through
    }
    return "?";
  }

  private static String formatElapsed(long seconds) {
    long m = seconds / 60;
    long sec = seconds % 60;
    return m > 0 ? m + "m" + sec + "s" : sec + "s";
  }

  private void startSse() {
    if (closed) return;
    // Called at the init decision (CONTINUE / caught FAIL), on the first install and at heal;
    // only the first call builds the update channel.
    if (!sseStarted.compareAndSet(false, true)) return;
    Consumer<Quonfig> hook = options.startSseHookForTest();
    if (hook != null) hook.accept(this);

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
              .cycle(this::fallbackPollCycle)
              .logger(options.logger())
              .onAuthFailed(entered -> handleAuthFailed(supBox[0], entered))
              .waitHook(options.waitHookForTest())
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

    SseClient sse = newSseClient(sup, fpRef);
    this.sseClient = sse;
    // close() reads sseClient and supervisor once. If it ran after the entry check above but
    // before both fields were assigned, it saw null and stopped nothing; stop them here so the SSE
    // loop and the fallback poller never run against a closed client (qfg-goi1.2.16). A close()
    // after this point sees both fields; SseClient and Supervisor are safe to stop before start.
    if (closed) {
      sse.stop();
      sup.stop();
      return;
    }
    logPollingMode();
    sse.start();
  }

  /**
   * Builds (does not start) the SSE client and wires its envelope and connection-state callbacks
   * into the supervisor and the fallback poller. Used at startup and when the client leaves
   * auth-failed, where a brand-new client restarts the stream from its base backoff.
   */
  private SseClient newSseClient(Supervisor sup, FallbackPoller fpRef) {
    List<URI> streams = new ArrayList<>(options.streamUrls().size());
    for (String u : options.streamUrls()) streams.add(URI.create(u));
    SseClient.Builder sseBuilder = SseClient.builder().streamUrls(streams).sdkKey(options.sdkKey());
    if (options.sseReadWatchdog() != null) {
      sseBuilder.readWatchdog(options.sseReadWatchdog());
    }
    sseBuilder.waitHook(options.waitHookForTest());
    sseBuilder.counters(sseConnectAttempts, sseWorkerRestarts);
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
              healIfDecided();
              fireConfigUpdate();
            }
          } catch (RuntimeException e) {
            // Bad envelope is non-fatal — keep the prior store in place. Per-row failures are
            // skipped (and logged) inside installDelivery, so this only sees envelope-level
            // failures (every row bad, meta errors); log them so the drop is not silent.
            options.logger().warn("quonfig: SSE envelope rejected: {}", e.toString());
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
            // Record the leg the stream ACTUALLY connected on (latching the max so
            // sseFailedOverToSecondary() reports "ever left primary"). SseClient pins the
            // stream to streamUrls[0] — failover is an HTTP-only property — so this records
            // 0; deriving it from the transport rather than hardcoding it means the f05
            // chaos assertion would catch a regression that walks the stream list again.
            int leg = sse.connectedStreamIndex();
            if (leg > sseStreamIndex) {
              sseStreamIndex = leg;
            }
            sup.setConnectionState(ConnectionState.CONNECTED);
          } else if (fpRef == null || !fpRef.active()) {
            // Skip the DISCONNECTED edge while fallback is already engaged so the visible
            // state stays "falling_back" rather than flickering between the two.
            sup.setConnectionState(ConnectionState.DISCONNECTED);
          }
          for (Consumer<Boolean> l : sseListeners) {
            try {
              l.accept(connected);
            } catch (Exception ex) {
              // User code; never tear down the client. Counted and logged, not a worker restart
              // (contract Test 5, qfg-6vno.28). Remaining listeners still run.
              connectionStateCallbackErrors.incrementAndGet();
              options
                  .logger()
                  .error("quonfig: onConnectionStateChange callback threw; SDK continuing", ex);
            }
          }
        });
    return sse;
  }

  /**
   * Applies the auth-failure rule's transport side (R3, R4, R6; qfg-goi1.10.27). On entry SSE is
   * stopped (not attempted while auth-failed) and the state is {@code disconnected}, never {@code
   * falling_back}. On exit SSE restarts as a new client, so it begins from its base backoff, and
   * the state stays {@code disconnected} until it connects. Runs on the fallback-poller thread.
   */
  private void handleAuthFailed(Supervisor sup, boolean entered) {
    if (sup == null) return;
    if (entered) {
      SseClient sse = this.sseClient;
      if (sse != null) sse.stop();
      sup.setConnectionState(ConnectionState.DISCONNECTED);
      return;
    }
    sup.setConnectionState(ConnectionState.DISCONNECTED);
    if (closed) return;
    SseClient sse = newSseClient(sup, this.fallbackPoller);
    this.sseClient = sse;
    // close() reads sseClient once; a close() that ran before the assignment stopped the old
    // client only, so stop this one here (same guard as startSse).
    if (closed) {
      sse.stop();
      return;
    }
    sse.start();
  }

  /**
   * Performs one Layer 2 fallback fetch cycle: GET {@code /api/v2/configs} across the legs, install
   * rows, mark refresh. Called only when the {@link FallbackPoller} has engaged, or for an
   * auth-failed probe. A transient HTTP failure during a partition is expected and not actionable:
   * it logs at DEBUG and reports {@link FallbackPoller.CycleResult#failed()}. A cycle in which no
   * leg answered 200/304 and the primary leg answered 401/403/404/410 reports {@link
   * FallbackPoller.CycleResult#authRejected} instead (rule R2, qfg-goi1.10.27); the poller logs the
   * entering cycle's ERROR in place of the DEBUG line. A primary 503 with a secondary 401 is not
   * this (a new key can lag the secondary's manifest).
   */
  private FallbackPoller.CycleResult fallbackPollCycle() {
    HttpTransport http = this.httpTransport;
    if (http == null) return FallbackPoller.CycleResult.failed();
    try {
      // Reject-older guard: a fallback poll that fails over to an older secondary must not regress
      // the held generation (qfg-7h5d.1.10). Only fire the update callbacks on an actual install —
      // but an ANSWERED poll (304, or a 200 the guard dropped) is still a successful refresh, so
      // it stamps liveness either way (qfg-41nh.15; sdk-go qfg-41nh.11 parity).
      boolean installed = fetchAndInstallSequential(http);
      recordSuccessfulRefresh();
      if (installed) {
        healIfDecided();
        fireConfigUpdate();
      }
      return FallbackPoller.CycleResult.answered();
    } catch (Exception e) {
      Throwable cause = e;
      while ((cause instanceof java.util.concurrent.ExecutionException
              || cause instanceof java.util.concurrent.CompletionException)
          && cause.getCause() != null) {
        cause = cause.getCause();
      }
      if (cause instanceof HttpTransportException) {
        int primary = ((HttpTransportException) cause).primaryStatus();
        if (isAuthClassStatus(primary)) {
          return FallbackPoller.CycleResult.authRejected(primary, primaryHost());
        }
      }
      // Best-effort: HTTP errors during a partition are exactly what fallback polling
      // exists to weather. The next interval tick will try again.
      options.logger().debug("quonfig: fallback poll fetch failed: {}", e.getMessage());
      return FallbackPoller.CycleResult.failed();
    }
  }

  /**
   * Whether a config-fetch status is in the auth class of the cross-SDK auth-failure rule (R1):
   * 401, 403, 404, 410.
   */
  static boolean isAuthClassStatus(int status) {
    return status == 401 || status == 403 || status == 404 || status == 410;
  }

  /** The primary leg's host[:port] for the auth-failed ERROR line. Never carries the key. */
  private String primaryHost() {
    String raw = options.apiUrls().isEmpty() ? "" : options.apiUrls().get(0);
    try {
      URI u = URI.create(raw);
      if (u.getHost() != null) {
        return u.getPort() >= 0 ? u.getHost() + ":" + u.getPort() : u.getHost();
      }
    } catch (IllegalArgumentException ignored) {
      // fall through to the raw string
    }
    return raw;
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

  /**
   * The current init outcome. Completed when the first config loads; completed exceptionally with
   * the {@link QuonfigInitException} when the constructor gave up (FAIL or CONTINUE at {@code
   * initTimeout}). Once the background retry (or {@link #refresh()}) installs a config, a completed
   * future replaces a failed one. A future fetched before the heal keeps its failure, so use {@link
   * #isReady()} / {@link #awaitReady(Duration)} for liveness.
   */
  public CompletableFuture<Void> initFuture() {
    return initFuture;
  }

  /** Keys of the held config; empty before the first config loads. */
  public Set<String> keys() {
    requireOpen();
    InMemoryConfigStore s = store;
    return initialized && s != null ? s.keys() : Set.of();
  }

  /**
   * Reports whether a feature flag is on. Never throws: a missing key, a flag with no matching
   * rule, a non-boolean value (including a stored string {@code "true"}) or any resolution error
   * returns {@code false} and logs a warning once per key per client (qfg-zvyy). Before the first
   * config loads, and on a closed client, it returns {@code false} without logging.
   */
  public boolean featureIsOn(String key, ContextSet ctx) {
    EvaluationDetails<Boolean> d;
    try {
      d = getBoolDetails(key, Boolean.FALSE, ctx);
    } catch (RuntimeException e) {
      warnReadFailure(key, "featureIsOn failed (" + e.getClass().getSimpleName() + ")");
      return false;
    }
    if (d.errorCode() == ErrorCode.FLAG_NOT_FOUND) {
      warnReadFailure(key, "the key does not exist; featureIsOn returns false");
    } else if (d.reason() == Reason.DEFAULT) {
      warnReadFailure(key, "no rule matched; featureIsOn returns false");
    }
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
   *
   * <p>Returns {@link Optional#empty()} immediately until the first config loads. The logging
   * filters call this on every log statement, so it must never block (qfg-goi1.2.16).
   */
  @Override
  public Optional<LogLevel> getLogLevel(String loggerPath, ContextSet ctx) {
    Objects.requireNonNull(loggerPath, "loggerPath");
    if (!initialized) {
      return Optional.empty();
    }
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
   * Wall-clock time of the most recent successful refresh: an envelope install (any source —
   * initial HTTP fetch, SSE update, fallback poll, datadir, or datafile) or an
   * answered-but-not-installed HTTP config fetch — a 304, or a 200 whose same/older-generation
   * envelope the reject-older guard dropped (qfg-41nh.15; sdk-go qfg-41nh.11 parity). A healthy
   * client long-parked on same-generation answers stays fresh here: liveness (this) and config
   * freshness ({@link #heldGeneration()}) are separate signals. Errors and SSE connection failures
   * never stamp. {@code null} before the first install/answer.
   *
   * <p>Diagnostic surface only — do not wire into a Kubernetes liveness probe. See {@link
   * ConnectionState} for the rationale.
   */
  /** Test seam (package-private): whether the Layer 2 fallback poller is engaged right now. */
  boolean fallbackPollerActiveForTest() {
    FallbackPoller fp = this.fallbackPoller;
    return fp != null && fp.active();
  }

  /**
   * Test seam (package-private, qfg-6vno.3): {@code quonfig_sdk_worker_restart_total{layer}}. Layer
   * {@code "2"} is the {@link Supervisor}'s own restart count for the fallback poller. Layer {@code
   * "1"} is the SSE worker: SseClient restarts (a connected session that ended, or an unexpected
   * error). Only real restarts count; a user callback throw is counted in {@link
   * #callbackErrorsTotalForTest} instead (contract Test 5, qfg-6vno.28).
   */
  long workerRestartTotalForTest(String layer) {
    if ("1".equals(layer)) return sseWorkerRestarts.get();
    if ("2".equals(layer)) {
      Supervisor sup = this.supervisor;
      return sup == null ? 0L : sup.workerRestartTotal("2");
    }
    throw new IllegalArgumentException("unknown worker layer '" + layer + "' (want 1 or 2)");
  }

  /**
   * Test seam (package-private, qfg-6vno.28): {@code quonfig_sdk_callback_errors_total{callback}},
   * user callback invocations that threw and were caught: {@code "on_config_update"} or {@code
   * "on_connection_state_change"}. A callback throw is never a worker restart (contract Test 5).
   */
  long callbackErrorsTotalForTest(String callback) {
    if ("on_config_update".equals(callback)) return configUpdateCallbackErrors.get();
    if ("on_connection_state_change".equals(callback)) return connectionStateCallbackErrors.get();
    throw new IllegalArgumentException(
        "unknown callback '"
            + callback
            + "' (want on_config_update or on_connection_state_change)");
  }

  /** Test seam (package-private, qfg-6vno.3): SSE dials so far, failed ones included. */
  long sseConnectAttemptsForTest() {
    return sseConnectAttempts.get();
  }

  public Instant lastSuccessfulRefresh() {
    return lastRefreshAt;
  }

  /**
   * Stamps "now" as the most recent successful refresh. Called on every ANSWERED HTTP config fetch
   * (installed or not) and — via {@link #installRows} — on every install. Mirrors the supervisor's
   * bookkeeping stamp when one exists.
   */
  private void recordSuccessfulRefresh() {
    this.lastRefreshAt = Instant.now();
    Supervisor sup = this.supervisor;
    if (sup != null) sup.recordSuccessfulRefresh();
  }

  /**
   * Records one config-fetch cycle whose hedge fired the secondary leg. Quiet no-op when telemetry
   * is disabled ({@link #failoverCollector} is {@code null}).
   */
  private void recordHedgeFired() {
    FailoverCollector fc = this.failoverCollector;
    if (fc != null) fc.recordHedgeFired();
  }

  /**
   * Records one install dropped by the reject-older ordering guard (HTTP or SSE). Quiet no-op when
   * telemetry is disabled.
   */
  private void recordGuardRejected() {
    FailoverCollector fc = this.failoverCollector;
    if (fc != null) fc.recordGuardRejected();
  }

  /**
   * Records one successful HTTP install by the leg that served it ({@code sourceIndex} 0 = primary,
   * &gt; 0 = secondary; negative ignored). Quiet no-op when telemetry is disabled.
   */
  private void recordResolvedFrom(int sourceIndex) {
    FailoverCollector fc = this.failoverCollector;
    if (fc != null) fc.recordResolvedFrom(sourceIndex);
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
  /** HTTP+SSE (SDK-key delivery) mode: neither a datadir nor a datafile is configured. */
  private boolean deliveryMode() {
    return (options.datadir() == null || options.datadir().isEmpty())
        && (options.datafile() == null || options.datafile().isEmpty())
        && options.datafileEnvelope() == null;
  }

  public ConnectionState connectionState() {
    Supervisor sup = this.supervisor;
    if (sup != null) {
      return sup.connectionState();
    }
    // No supervisor — either HTTP mode before startSse() builds it, or datadir/datafile mode
    // (where a supervisor is never built). HTTP mode stays INITIALIZING until the stream reports:
    // the init fetch stamps lastRefreshAt just before startSse() runs, and reporting CONNECTED in
    // that window would claim a stream that has not delivered a frame (qfg-goi1.10.42).
    if (deliveryMode()) return ConnectionState.INITIALIZING;
    return lastRefreshAt != null ? ConnectionState.CONNECTED : ConnectionState.INITIALIZING;
  }

  /**
   * Whether the client has installed at least one config envelope and is ready to evaluate. {@code
   * false} before the first install (HTTP mode, while init runs) and after {@link #close()}.
   */
  public boolean ready() {
    return initialized && !closed;
  }

  /** Readiness query (design B.4): same as {@link #ready()}. */
  public boolean isReady() {
    return ready();
  }

  /**
   * Blocks up to {@code timeout} for the client to become ready (design B.4); returns {@link
   * #isReady()}. Useful on a client that survived a caught {@link QuonfigInitException} or was
   * built with {@link OnInitFailure#CONTINUE}. Returns {@code false} at once after {@link #close()}
   * and, with the interrupt flag restored, when the waiting thread is interrupted.
   */
  public boolean awaitReady(Duration timeout) {
    long deadline = System.nanoTime() + Math.max(0L, timeout.toNanos());
    synchronized (initSignal) {
      while (!ready()) {
        if (closed) return false;
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) return false;
        try {
          initSignal.wait(Math.max(1L, TimeUnit.NANOSECONDS.toMillis(remaining)));
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          return ready();
        }
      }
      return true;
    }
  }

  /**
   * Highest {@code Meta.generation} the client has installed ({@code 0} before the first install,
   * or while only unversioned servers have answered). A higher generation is strictly newer; this
   * is the value the canonical-ordering guard compares against on every install path. An
   * unversioned payload (generation absent or {@code <= 0}) installs only while this is {@code 0}
   * (qfg-9dxb.9) and never changes it (qfg-9dxb.3).
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
   * Whether the live SSE stream ever connected on a non-primary leg. Always {@code false} by design
   * — {@link SseClient} dials only {@code streamUrls[0]} and retries it forever; failover is an
   * HTTP-only property. Unlike a hardcoded constant, this is derived from the transport's
   * actually-connected leg ({@link SseClient#connectedStreamIndex()}, latched at its maximum), so
   * the chaos suite's f05 assertion genuinely catches a regression that silently repoints the
   * stream.
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
      // An answered poll (304, or a 200 whose envelope the reject-older guard dropped) is a
      // successful refresh even when nothing installs; only an actual install fires the update
      // callbacks (qfg-41nh.15; sdk-go qfg-41nh.11 parity).
      boolean installed = fetchAndInstallSequential(http);
      recordSuccessfulRefresh();
      if (installed) {
        healIfDecided();
        fireConfigUpdate();
      }
    } catch (Exception e) {
      options.logger().debug("quonfig: refresh failed: {}", e.getMessage());
    }
  }

  /**
   * Sends pending telemetry now and waits for the POST (bounded by the telemetry timeout). No-op
   * when telemetry is disabled. After a failed POST it respects the 30s resend floor and {@code
   * Retry-After}; a failed batch is kept and resent later, so this does not throw.
   */
  public void flush() {
    requireOpen();
    if (telemetryReporter != null) {
      try {
        telemetryReporter.flush();
      } catch (IOException e) {
        // Not thrown since the telemetry transport policy; kept for the reporter's signature.
        throw new IllegalStateException("telemetry flush failed: " + e.getMessage(), e);
      }
    }
  }

  @Override
  public void close() {
    closed = true;
    synchronized (initSignal) {
      initSignal.notifyAll();
    }
    Thread retry = initRetryThread;
    if (retry != null) retry.interrupt();
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

  // ---- untyped details read (qfg-177f) ----

  /** See {@link #evaluateDetails(String, ContextSet)}; evaluated with no per-call context. */
  public EvaluationDetails<Object> evaluateDetails(String key) {
    return evaluateDetails(key, null);
  }

  /**
   * Untyped details read, the Java form of sdk-go's {@code Client.EvaluateDetails}: evaluates
   * {@code key} and returns the resolved value in its stored / wire form, with the same reason,
   * variant, metadata and telemetry as the typed {@code get*Details} getters.
   *
   * <p>Unlike the typed getters there is no type check, no coercion and no parse validation. The
   * value is the one the config stores, after ENV_VAR provision and decryption: a duration is its
   * ISO 8601 text exactly as stored (e.g. {@code "PT90S"}, never re-normalized through {@link
   * Duration}), a string_list a {@code List<String>}, a json value what {@link #getJsonDetails}
   * returns, an int a {@code Long}, a double a {@code Double}, a bool a {@code Boolean}. A
   * malformed stored value is returned raw with its success reason. An ENV_VAR-provided value is
   * still converted to the config's value type at resolution (as in sdk-go), so an env var that
   * does not parse as that type is {@link Reason#ERROR} / {@link ErrorCode#TYPE_MISMATCH} with a
   * null value. Lists and maps are copies the caller may mutate.
   *
   * <p>{@link EvaluationDetails#valueType()} carries the config's stored value type (e.g. {@link
   * ValueType#DURATION}) so a caller can interpret the value; it is null when the key is not found.
   * {@code metadata.configType} stays the config kind ({@code CONFIG} / {@code FEATURE_FLAG}), as
   * on the typed getters.
   *
   * <p>A missing key, a closed or not-yet-ready client, or an evaluation failure returns a null
   * value with {@link Reason#ERROR} and the same {@link ErrorCode} the typed getters use. Never
   * throws.
   */
  public EvaluationDetails<Object> evaluateDetails(String key, ContextSet ctx) {
    EvaluationDetails<Object> d;
    try {
      d = evaluateTyped(key, null, ctx, null, Object.class, false);
    } catch (RuntimeException e) {
      warnReadFailure(key, "evaluation failed (" + e.getClass().getSimpleName() + ")");
      return new EvaluationDetails<>(
          null,
          Reason.ERROR,
          variantFor(Reason.ERROR, -1, -1),
          null,
          ErrorCode.GENERAL,
          "evaluation failed for \"" + key + "\": " + e.getMessage(),
          baseMetadata(null, key, null, Reason.ERROR, -1, -1));
    }
    InMemoryConfigStore s = store;
    ConfigRow cfg = !closed && initialized && s != null ? s.getConfig(key) : null;
    if (cfg == null) return d;
    return new EvaluationDetails<>(
        d.value(),
        d.reason(),
        d.variant(),
        d.variantIndex(),
        d.errorCode(),
        d.errorMessage(),
        d.metadata(),
        cfg.valueType());
  }

  // ---- throwing variants (get_or_raise) ----

  /**
   * Returns the value of a STRING config, or throws when there is no value to return. This is the
   * SDK's {@code get_or_raise} (python {@code get} with {@code on_no_default="error"}, go {@code
   * GetStringValue}'s error): use it when a missing or broken config must fail loudly instead of
   * falling back to a default.
   *
   * <p>Every {@code get*OrThrow} method throws:
   *
   * <ul>
   *   <li>{@link QuonfigKeyNotFoundException} when the key does not exist or no rule produced a
   *       value (including before the first config loads, on a client that is not ready).
   *   <li>{@link QuonfigCoercionException} (a {@link QuonfigKeyNotFoundException}) when the value
   *       cannot be converted to the requested type: an ENV_VAR value such as {@code
   *       "not_a_number"} for an INT, or a malformed int, double or duration, stored or ENV_VAR.
   *   <li>{@link QuonfigEnvVarNotSetException} when an ENV_VAR-provided value's variable is unset.
   *   <li>{@link QuonfigDecryptionException} when a confidential value cannot be decrypted.
   *   <li>{@link QuonfigTypeMismatchException} (an {@link IllegalArgumentException}) when the
   *       config's value is not of the getter's type, for example {@code getLongOrThrow} on a
   *       STRING config or {@code getBoolOrThrow} on a stored string {@code "false"}. Strings are
   *       never coerced; INT reads a whole DOUBLE within +/-(2^53-1) and DOUBLE reads an INT.
   *   <li>{@link IllegalStateException} when the client is closed.
   * </ul>
   *
   * <p>The plain typed getters ({@link #getString(String, String)} and friends) never throw for
   * these cases: they return the caller's default (or {@code null}) and log one WARN per key. Use
   * this family when a broken config, such as a wrong decryption key in production, must fail hard.
   */
  public String getStringOrThrow(String key) {
    return getStringOrThrow(key, null);
  }

  /** See {@link #getStringOrThrow(String)}. */
  public String getStringOrThrow(String key, ContextSet ctx) {
    return typedOrThrow(key, ctx, ValueType.STRING, String.class);
  }

  /** See {@link #getStringOrThrow(String)}. */
  public Boolean getBoolOrThrow(String key) {
    return getBoolOrThrow(key, null);
  }

  /** See {@link #getStringOrThrow(String)}. */
  public Boolean getBoolOrThrow(String key, ContextSet ctx) {
    return typedOrThrow(key, ctx, ValueType.BOOL, Boolean.class);
  }

  /** See {@link #getStringOrThrow(String)}. */
  public Long getLongOrThrow(String key) {
    return getLongOrThrow(key, null);
  }

  /** See {@link #getStringOrThrow(String)}. */
  public Long getLongOrThrow(String key, ContextSet ctx) {
    return typedOrThrow(key, ctx, ValueType.INT, Long.class);
  }

  /** See {@link #getStringOrThrow(String)}. */
  public Double getDoubleOrThrow(String key) {
    return getDoubleOrThrow(key, null);
  }

  /** See {@link #getStringOrThrow(String)}. */
  public Double getDoubleOrThrow(String key, ContextSet ctx) {
    return typedOrThrow(key, ctx, ValueType.DOUBLE, Double.class);
  }

  /** See {@link #getStringOrThrow(String)}. */
  public List<String> getStringListOrThrow(String key) {
    return getStringListOrThrow(key, null);
  }

  /** See {@link #getStringOrThrow(String)}. */
  @SuppressWarnings("unchecked")
  public List<String> getStringListOrThrow(String key, ContextSet ctx) {
    return (List<String>) typedOrThrow(key, ctx, ValueType.STRING_LIST, List.class);
  }

  /** See {@link #getStringOrThrow(String)}. */
  public Duration getDurationOrThrow(String key) {
    return getDurationOrThrow(key, null);
  }

  /** See {@link #getStringOrThrow(String)}. */
  public Duration getDurationOrThrow(String key, ContextSet ctx) {
    return typedOrThrow(key, ctx, ValueType.DURATION, Duration.class);
  }

  /** See {@link #getStringOrThrow(String)}. */
  public Object getJsonOrThrow(String key) {
    return getJsonOrThrow(key, null);
  }

  /** See {@link #getStringOrThrow(String)}. */
  public Object getJsonOrThrow(String key, ContextSet ctx) {
    return typedOrThrow(key, ctx, ValueType.JSON, Object.class);
  }

  // ---- core evaluation ----

  private <T> EvaluationDetails<T> typedDetails(
      String key, T def, ContextSet ctx, ValueType expectedType, Class<T> javaType) {
    return evaluateTyped(key, def, ctx, expectedType, javaType, false);
  }

  private <T> T typedOrThrow(
      String key, ContextSet ctx, ValueType expectedType, Class<T> javaType) {
    return evaluateTyped(key, null, ctx, expectedType, javaType, true).value();
  }

  private static String noValueMessage(String key) {
    return "No value found for key '" + key + "'";
  }

  private static RuntimeException resolverFailure(String key, ResolverException e) {
    String msg = "resolve failed for \"" + key + "\": " + e.getMessage();
    switch (e.kind()) {
      case MISSING_ENV_VAR:
        return new QuonfigEnvVarNotSetException(msg, e);
      case UNABLE_TO_DECRYPT:
        return new QuonfigDecryptionException(msg, e);
      case UNABLE_TO_COERCE:
        return new QuonfigCoercionException(msg, e);
      case MISSING_DEFAULT:
      default:
        return new QuonfigKeyNotFoundException(msg, e);
    }
  }

  /**
   * Shared evaluation for the typed getters. With {@code raise} false every failure becomes a
   * {@link Reason#ERROR} details carrying {@code def}; with {@code raise} true (the {@code
   * get*OrThrow} family) every failure, and a missing value, throws instead.
   */
  @SuppressWarnings("unchecked")
  private <T> EvaluationDetails<T> evaluateTyped(
      String key, T def, ContextSet ctx, ValueType expectedType, Class<T> javaType, boolean raise) {
    // Getters never block on init (design B.3): the constructor is the only gate.
    if (closed) {
      if (raise) throw new IllegalStateException(CLOSED_MESSAGE);
      return new EvaluationDetails<>(
          def,
          Reason.ERROR,
          variantFor(Reason.ERROR, -1, -1),
          null,
          ErrorCode.GENERAL,
          CLOSED_MESSAGE,
          baseMetadata(null, key, null, Reason.ERROR, -1, -1));
    }
    // Any install makes the client ready, including one after a failed init (background retry or
    // refresh()); a failed init future must not shadow it (qfg-9p4i.4).
    if (!initialized) {
      if (raise) throw new QuonfigKeyNotFoundException(noValueMessage(key));
      return new EvaluationDetails<>(
          def,
          Reason.ERROR,
          variantFor(Reason.ERROR, -1, -1),
          null,
          ErrorCode.PROVIDER_NOT_READY,
          "Quonfig client is not ready: no config has loaded yet",
          baseMetadata(null, key, null, Reason.ERROR, -1, -1));
    }
    ContextSet effective = merge(options.globalContext(), ctx);

    ConfigRow cfg = store.getConfig(key);
    if (cfg == null) {
      if (raise) throw new QuonfigKeyNotFoundException(noValueMessage(key));
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

    // expectedType null is the untyped evaluateDetails read: no type check, no coercion.
    if (expectedType != null && !isCompatible(cfg.valueType(), expectedType)) {
      String msg =
          "config \"" + key + "\" is " + cfg.valueType() + ", caller expected " + expectedType;
      if (raise) throw new QuonfigTypeMismatchException(msg);
      warnReadFailure(key, msg);
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
      if (raise) {
        if (e instanceof ResolverException) throw resolverFailure(key, (ResolverException) e);
        throw e;
      }
      warnReadFailure(key, "evaluation failed (" + e.getClass().getSimpleName() + ")");
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
      if (raise) throw new QuonfigKeyNotFoundException(noValueMessage(key));
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
      if (raise) throw resolverFailure(key, e);
      warnReadFailure(key, e.getMessage());
      return new EvaluationDetails<>(
          def,
          Reason.ERROR,
          variantFor(Reason.ERROR, -1, -1),
          null,
          e.kind() == ResolverException.Kind.UNABLE_TO_COERCE
              ? ErrorCode.TYPE_MISMATCH
              : ErrorCode.GENERAL,
          "resolve failed for \"" + key + "\": " + e.getMessage(),
          metadataFor(cfg, Reason.ERROR, match.ruleIndex(), match.weightedValueIndex()));
    }

    Reason r = match.weightedValueIndex() >= 0 ? Reason.SPLIT : mapEngineReason(match.reason());

    Object payload = resolvedVal != null ? resolvedVal.value() : null;
    T typed;
    try {
      typed =
          (T)
              (expectedType == null
                  ? wireValue(payload)
                  : coerceToJavaType(payload, expectedType, javaType));
    } catch (DateTimeException | ArithmeticException | MalformedValueException e) {
      // Malformed stored value: a duration that does not parse (qfg-2agi.8) or an int/double that
      // does not parse (qfg-zvyy, qfg-zqoh). The same contract as an ENV_VAR value that fails to
      // coerce: caller's default, Reason.ERROR, one WARN per key, never a throw. The get*OrThrow
      // family raises the same coercion error as an ENV_VAR value instead. The message names the
      // key, never the value (a DateTimeParseException message quotes the text).
      String msg = "config \"" + key + "\" has a malformed " + expectedType + " value";
      if (raise) throw new QuonfigCoercionException(msg);
      warnReadFailure(key, msg);
      return new EvaluationDetails<>(
          def,
          Reason.ERROR,
          variantFor(Reason.ERROR, -1, -1),
          null,
          ErrorCode.TYPE_MISMATCH,
          msg,
          metadataFor(cfg, Reason.ERROR, match.ruleIndex(), match.weightedValueIndex()));
    } catch (ClassCastException | IllegalArgumentException e) {
      String msg = "cannot return \"" + key + "\" as " + expectedType + ": " + e.getMessage();
      if (raise) throw new QuonfigTypeMismatchException(msg, e);
      warnReadFailure(key, msg);
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
      // A java.time.Duration is not JSON-serializable and would drop the whole telemetry window
      // (qfg-a996). Report the stored ISO-8601 string, as sdk-go does ({"string": "PT90S"}).
      Object wireValue = typed;
      if (typed instanceof Duration) {
        wireValue = resolvedVal.value() instanceof String ? resolvedVal.value() : typed.toString();
      }
      summaryCollector.push(
          new EvaluationStat(
              cfg.id(),
              cfg.key(),
              cfg.type().name(),
              match.ruleIndex(),
              match.weightedValueIndex(),
              wireValue,
              reportable,
              reasonNumber(r)));
    }

    Integer variantIndex = r == Reason.SPLIT ? match.weightedValueIndex() : null;
    Map<String, Object> metadata =
        metadataFor(cfg, r, match.ruleIndex(), match.weightedValueIndex());
    if (match.missingHashProperty() != null) {
      metadata.put("hashPropertyMissing", true);
      if (warnedMissingHashProperty.add(cfg.key())) {
        options
            .logger()
            .warn(
                "quonfig: weighted rollout for \"{}\" hashes on \"{}\" which is missing from"
                    + " context; hashing an empty value instead",
                cfg.key(),
                match.missingHashProperty());
      }
    }
    return new EvaluationDetails<>(
        typed,
        r,
        variantFor(r, match.ruleIndex(), match.weightedValueIndex()),
        variantIndex,
        null,
        null,
        metadata);
  }

  /**
   * Logs once per config key per client that a read returned no value, so a broken config is not
   * mistaken for "using the default" (qfg-zvyy, sdk-go warnReadFailure parity). {@code reason}
   * names the key and the failure, never the value.
   */
  private void warnReadFailure(String key, String reason) {
    if (warnedReadFailure.add(key)) {
      options
          .logger()
          .warn(
              "quonfig: read of \"{}\" returned no value; the caller's default applies: {}",
              key,
              reason);
    }
  }

  /** A stored int or double that did not parse (kept as its raw text by ValueDecoder). */
  private static final class MalformedValueException extends RuntimeException {
    MalformedValueException() {
      super(null, null, false, false);
    }
  }

  /** Largest integer a double holds exactly, 2^53-1; matches {@code qfg verify}'s int grammar. */
  private static final double MAX_SAFE_INT = 9007199254740991.0;

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

  /**
   * Which stored value types a typed getter accepts (qfg-zvyy). Strings are never coerced: a stored
   * {@code "false"} is not a BOOL and a DURATION is not a STRING. The only cross-type reads are
   * STRING on LOG_LEVEL, DOUBLE on INT (always exact) and INT on DOUBLE (checked per value in
   * {@link #coerceToJavaType}: whole and within +/-(2^53-1), never truncated).
   */
  private static boolean isCompatible(ValueType actual, ValueType requested) {
    if (actual == requested) return true;
    // log_level is stored as STRING-style enum; allow string getters to read it.
    if (requested == ValueType.STRING && actual == ValueType.LOG_LEVEL) return true;
    if (requested == ValueType.DOUBLE && actual == ValueType.INT) return true;
    if (requested == ValueType.INT && actual == ValueType.DOUBLE) return true;
    return false;
  }

  private static Object coerceToJavaType(Object payload, ValueType vt, Class<?> javaType) {
    if (payload == null) return null;
    if (vt == ValueType.DURATION) {
      if (payload instanceof Duration) return roundHalfUpToMillis((Duration) payload);
      if (payload instanceof String) return roundHalfUpToMillis(Duration.parse((String) payload));
    }
    if (vt == ValueType.INT) {
      if (payload instanceof Long || payload instanceof Integer) {
        return ((Number) payload).longValue();
      }
      if (payload instanceof Double || payload instanceof Float) {
        double d = ((Number) payload).doubleValue();
        if (Double.isNaN(d) || d != Math.rint(d) || Math.abs(d) > MAX_SAFE_INT) {
          throw new IllegalArgumentException(
              "DOUBLE value is not a whole number within +/-(2^53-1)");
        }
        return (long) d;
      }
      if (payload instanceof String) throw new MalformedValueException();
    }
    if (vt == ValueType.DOUBLE) {
      if (payload instanceof Double) return payload;
      if (payload instanceof Long || payload instanceof Integer || payload instanceof Float) {
        return ((Number) payload).doubleValue();
      }
      if (payload instanceof String) throw new MalformedValueException();
    }
    // Copy-on-return (qfg-goi1.10.8, sdk-go 543ee9c parity): the payload is the cache's own
    // object, so hand back a deep copy a caller can mutate without changing what later reads see.
    if (vt == ValueType.JSON) return deepCopyJson(payload);
    if (vt == ValueType.STRING_LIST) return deepCopyJson(payload);
    if (javaType.isInstance(payload)) return payload;
    throw new ClassCastException(payload.getClass() + " is not " + javaType);
  }

  /**
   * The untyped read's value: the payload as stored, with lists and maps deep-copied off the cache.
   * A {@link Duration} payload (not produced by the decoder or resolver today) is reported as its
   * ISO 8601 text, so the value always stays in wire form.
   */
  private static Object wireValue(Object payload) {
    if (payload instanceof Duration) return payload.toString();
    return deepCopyJson(payload);
  }

  /**
   * Deep copy of a decoded JSON value: maps become {@link LinkedHashMap} and lists {@link
   * ArrayList} (mutable, insertion order kept, the types callers already get); scalars are
   * immutable and returned as is.
   */
  static Object deepCopyJson(Object v) {
    if (v instanceof Map) {
      Map<?, ?> src = (Map<?, ?>) v;
      Map<Object, Object> out = new LinkedHashMap<>(Math.max(16, src.size() * 2));
      for (Map.Entry<?, ?> e : src.entrySet()) out.put(e.getKey(), deepCopyJson(e.getValue()));
      return out;
    }
    if (v instanceof List) {
      List<?> src = (List<?>) v;
      List<Object> out = new ArrayList<>(src.size());
      for (Object e : src) out.add(deepCopyJson(e));
      return out;
    }
    return v;
  }

  /**
   * A duration means an integer millisecond count, rounded half up from the exact decimal value
   * (qfg-2agi decision 2): PT0.0005S is 1 ms, PT0.0004S is 0 ms. {@link Duration#toMillis()} alone
   * truncates.
   */
  static Duration roundHalfUpToMillis(Duration d) {
    // getNano() is always in [0, 1e9), so this is floor(d) in ms; then round the remainder.
    long ms = Math.addExact(Math.multiplyExact(d.getSeconds(), 1000L), d.getNano() / 1_000_000);
    if (d.getNano() % 1_000_000 >= 500_000) ms = Math.addExact(ms, 1L);
    return Duration.ofMillis(ms);
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

  private static final String CLOSED_MESSAGE = "Quonfig client is closed";

  private void requireOpen() {
    if (closed) throw new IllegalStateException(CLOSED_MESSAGE);
  }

  private void fireConfigUpdate() {
    for (Runnable r : configUpdateListeners) {
      try {
        r.run();
      } catch (Exception ex) {
        // Listeners are user code; never let one tear down the client. Logged at ERROR with the
        // stack and counted in callback_errors_total{callback=on_config_update}; not a worker
        // restart (contract Test 5, chaos scenario 10; qfg-6vno.28).
        configUpdateCallbackErrors.incrementAndGet();
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
