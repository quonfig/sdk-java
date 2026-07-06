package com.quonfig.sdk;

import com.quonfig.sdk.eval.ContextSet;
import com.quonfig.sdk.eval.Murmur3WeightedValueResolver;
import com.quonfig.sdk.eval.Resolver;
import com.quonfig.sdk.eval.WeightedValueResolver;
import com.quonfig.sdk.telemetry.ContextUploadMode;
import com.quonfig.sdk.telemetry.TelemetrySender;
import com.quonfig.sdk.wire.ConfigEnvelope;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Immutable configuration for a {@link Quonfig} client. Build via {@link #builder()}.
 *
 * <p>Env-var fallbacks resolved at {@link Builder#build()}:
 *
 * <ul>
 *   <li>{@code QUONFIG_BACKEND_SDK_KEY} → {@link #sdkKey()}
 *   <li>{@code QUONFIG_DOMAIN} → {@link #domain()}
 *   <li>{@code QUONFIG_ENVIRONMENT} → {@link #environment()}
 * </ul>
 *
 * <p>URL derivation from {@link #domain()} (default {@code quonfig.com}) — explicit {@link
 * Builder#apiUrls(List)} / {@link Builder#telemetryUrl(String)} take precedence:
 *
 * <ul>
 *   <li>{@code https://primary.${domain}}
 *   <li>{@code https://secondary.${domain}}
 *   <li>{@code https://stream.primary.${domain}}
 *   <li>{@code https://stream.secondary.${domain}}
 *   <li>{@code https://telemetry.${domain}}
 * </ul>
 */
public final class Options {

  public static final String DEFAULT_DOMAIN = "quonfig.com";
  public static final Duration DEFAULT_INIT_TIMEOUT = Duration.ofSeconds(10);

  /**
   * Default per-URL config-fetch deadline (~3s). Bounds each base-URL attempt on the initial fetch
   * and the fallback poller so a hung primary fails over to the secondary inside {@link
   * #initTimeout()}. Mirrors the sdk-go pilot (qfg-7h5d.1.4/.10).
   */
  public static final Duration DEFAULT_CONFIG_FETCH_TIMEOUT = Duration.ofSeconds(3);

  /**
   * Default hedge delay (~2s). On the initial HTTP config fetch the parallel-failover hedge fires
   * the primary leg first and waits this long before ALSO firing the secondary in parallel (without
   * cancelling the primary). A healthy sub-second primary answers well inside this window, so the
   * secondary stays a cold standby and a healthy system adds zero secondary load. Must be below the
   * worst-case healable primary latency and far below {@link #DEFAULT_CONFIG_FETCH_HEDGE_ABORT}.
   * Mirrors the sdk-go pilot's {@code DefaultConfigFetchHedgeDelay} (qfg-7h5d.1.14).
   */
  public static final Duration DEFAULT_CONFIG_FETCH_HEDGE_DELAY = Duration.ofSeconds(2);

  /**
   * Default per-leg hard-abort deadline on the hedged init fetch (~6s). Each hedge leg is bounded
   * by this; it MUST exceed the longest healable primary latency so a late-but-newer primary heals
   * forward (rather than aborting), and MUST be below {@link #initTimeout()} so the init-path heal
   * leg is not clipped. Distinct from {@link #DEFAULT_CONFIG_FETCH_TIMEOUT}, which still bounds the
   * sequential refresh/fallback-poll attempts. Mirrors the sdk-go pilot's {@code
   * DefaultConfigFetchHedgeAbort} (qfg-7h5d.1.14).
   */
  public static final Duration DEFAULT_CONFIG_FETCH_HEDGE_ABORT = Duration.ofSeconds(6);

  private final String sdkKey;
  private final String domain;
  private final List<String> apiUrls;
  private final boolean apiUrlsExplicit;
  private final List<String> streamUrlsOverride;
  private final String telemetryUrl;
  private final String environment;
  private final Duration initTimeout;
  private final Duration configFetchTimeout;
  private final Duration configFetchHedgeDelay;
  private final Duration configFetchHedgeAbort;
  private final boolean fallbackPollEnabled;
  private final long fallbackPollIntervalMs;
  private final Duration fallbackPollThreshold;
  private final Consumer<Boolean> onFallbackPollerStateChange;
  private final ContextSet globalContext;
  private final Logger logger;
  private final String datadir;
  private final String datafile;
  private final ConfigEnvelope datafileEnvelope;
  private final Resolver.EnvLookup envLookup;
  private final Runnable onConfigUpdate;
  private final Consumer<Boolean> onSseConnectionStateChange;
  private final WeightedValueResolver weightedValueResolver;
  private final boolean disableTelemetry;
  private final boolean collectEvaluationSummaries;
  private final ContextUploadMode contextUploadMode;
  private final TelemetrySender telemetrySender;
  private final Duration telemetryInitialDelay;
  private final Duration telemetryFlushInterval;
  private final Duration telemetryMaxInterval;
  private final String instanceHash;
  private final String loggerKey;
  private final Duration sseReadWatchdog;
  private final boolean dataDirAutoReload;
  private final long dataDirAutoReloadDebounceMs;
  private final Boolean enableQuonfigUserContext;

  public static final long DEFAULT_DATADIR_AUTORELOAD_DEBOUNCE_MS = 200L;

  private Options(Builder b) {
    Resolver.EnvLookup env = b.envLookup != null ? b.envLookup : Resolver.DEFAULT_ENV_LOOKUP;

    this.envLookup = env;
    this.sdkKey = b.sdkKey != null ? b.sdkKey : env.lookup("QUONFIG_BACKEND_SDK_KEY").orElse(null);
    this.domain = b.domain != null ? b.domain : env.lookup("QUONFIG_DOMAIN").orElse(DEFAULT_DOMAIN);
    this.environment =
        b.environment != null ? b.environment : env.lookup("QUONFIG_ENVIRONMENT").orElse(null);

    String d = this.domain;
    // Track whether the caller explicitly supplied apiUrls: the derived default carries BOTH a
    // primary and a secondary leg (so failover/hedge is on), but an explicit override replaces the
    // list wholesale, so a single-entry override silently drops the secondary. Consumed by the
    // init-time failover warning in Quonfig (qfg-41nh.26).
    this.apiUrlsExplicit = b.apiUrls != null;
    this.apiUrls =
        b.apiUrls != null
            ? List.copyOf(b.apiUrls)
            : List.of("https://primary." + d, "https://secondary." + d);
    this.streamUrlsOverride = b.streamUrls != null ? List.copyOf(b.streamUrls) : null;
    this.telemetryUrl = b.telemetryUrl != null ? b.telemetryUrl : "https://telemetry." + d;

    this.enableQuonfigUserContext = b.enableQuonfigUserContext;

    this.initTimeout = b.initTimeout != null ? b.initTimeout : DEFAULT_INIT_TIMEOUT;
    this.configFetchTimeout =
        b.configFetchTimeout != null ? b.configFetchTimeout : DEFAULT_CONFIG_FETCH_TIMEOUT;
    this.configFetchHedgeDelay =
        b.configFetchHedgeDelay != null
            ? b.configFetchHedgeDelay
            : DEFAULT_CONFIG_FETCH_HEDGE_DELAY;
    this.configFetchHedgeAbort =
        b.configFetchHedgeAbort != null
            ? b.configFetchHedgeAbort
            : DEFAULT_CONFIG_FETCH_HEDGE_ABORT;
    this.fallbackPollEnabled = b.fallbackPollEnabled;
    this.fallbackPollIntervalMs = b.fallbackPollIntervalMs;
    this.fallbackPollThreshold = b.fallbackPollThreshold;
    this.onFallbackPollerStateChange = b.onFallbackPollerStateChange;
    this.logger = b.logger != null ? b.logger : LoggerFactory.getLogger("com.quonfig.sdk");

    // Dev-context injection (quonfig-user.email). Default ON, gated only by the presence of the
    // tokens file (the loader no-ops without it, so this is dead in prod). Precedence: explicit
    // option ?? QUONFIG_DEV_CONTEXT env ?? true. The dev-context is merged UNDER the customer's
    // globalContext so customer keys win on collision. Mirrors sdk-node/src/quonfig.ts.
    boolean devContextEnabled = resolveDevContextEnabled(b.enableQuonfigUserContext, env);
    ContextSet devContext =
        devContextEnabled ? DevContextLoader.load(this.apiUrls, this.logger) : null;
    this.globalContext = mergeDevContext(devContext, b.globalContext);
    this.datadir = b.datadir;
    this.datafile = b.datafile;
    this.datafileEnvelope = b.datafileEnvelope;
    this.onConfigUpdate = b.onConfigUpdate;
    this.onSseConnectionStateChange = b.onSseConnectionStateChange;
    // Default to Murmur3 bucketing so weighted-value configs resolve (and report reason SPLIT)
    // out of the box, matching sdk-go/sdk-node. Without this the SDK would hand back the raw
    // unresolved weighted value and never emit SPLIT. Callers can still override. qfg-q7yz.
    this.weightedValueResolver =
        b.weightedValueResolver != null
            ? b.weightedValueResolver
            : new Murmur3WeightedValueResolver();
    this.disableTelemetry = b.disableTelemetry;
    this.collectEvaluationSummaries = b.collectEvaluationSummaries;
    this.contextUploadMode =
        b.contextUploadMode != null ? b.contextUploadMode : ContextUploadMode.PERIODIC_EXAMPLE;
    this.telemetrySender = b.telemetrySender;
    this.telemetryInitialDelay =
        b.telemetryInitialDelay != null ? b.telemetryInitialDelay : Duration.ofSeconds(8);
    this.telemetryFlushInterval =
        b.telemetryFlushInterval != null ? b.telemetryFlushInterval : Duration.ofSeconds(60);
    this.telemetryMaxInterval =
        b.telemetryMaxInterval != null ? b.telemetryMaxInterval : Duration.ofSeconds(600);
    this.instanceHash =
        b.instanceHash != null ? b.instanceHash : java.util.UUID.randomUUID().toString();
    this.loggerKey = b.loggerKey;
    this.sseReadWatchdog = b.sseReadWatchdog;
    this.dataDirAutoReload = b.dataDirAutoReload;
    this.dataDirAutoReloadDebounceMs =
        b.dataDirAutoReloadDebounceMs > 0
            ? b.dataDirAutoReloadDebounceMs
            : DEFAULT_DATADIR_AUTORELOAD_DEBOUNCE_MS;
  }

  /**
   * Resolves whether dev-context injection is enabled: explicit option wins; else the {@code
   * QUONFIG_DEV_CONTEXT} env var ("true"/"false"); else default {@code true}.
   */
  private static boolean resolveDevContextEnabled(Boolean explicit, Resolver.EnvLookup env) {
    if (explicit != null) {
      return explicit;
    }
    String raw = env.lookup("QUONFIG_DEV_CONTEXT").orElse(null);
    if ("true".equals(raw)) return true;
    if ("false".equals(raw)) return false;
    return true;
  }

  /**
   * Merges the injected dev-context UNDER the customer's globalContext: every named context from
   * the dev-context is added first, then the customer's contexts overwrite on collision (customer
   * keys win). Either side may be null.
   */
  private static ContextSet mergeDevContext(ContextSet devContext, ContextSet customer) {
    if (devContext == null) {
      return customer;
    }
    ContextSet out = new ContextSet();
    for (var e : devContext.data().entrySet()) {
      out.withNamedContext(e.getKey(), e.getValue());
    }
    if (customer != null) {
      for (var e : customer.data().entrySet()) {
        out.withNamedContext(e.getKey(), e.getValue());
      }
    }
    return out;
  }

  public String sdkKey() {
    return sdkKey;
  }

  public String domain() {
    return domain;
  }

  public List<String> apiUrls() {
    return apiUrls;
  }

  /**
   * Whether the caller supplied {@link Builder#apiUrls(List)} explicitly (as opposed to the list
   * being derived from {@link #domain()}). The derived default always carries both a primary and a
   * secondary leg; an explicit single-entry override drops the secondary and disables automatic
   * failover. Used to warn once at init in that case (qfg-41nh.26).
   */
  public boolean apiUrlsExplicit() {
    return apiUrlsExplicit;
  }

  public String telemetryUrl() {
    return telemetryUrl;
  }

  public String environment() {
    return environment;
  }

  public Duration initTimeout() {
    return initTimeout;
  }

  /**
   * Per-URL config-fetch deadline. Each base-URL attempt (initial fetch and fallback poller) is
   * bounded by this duration, so a hung or black-holed primary aborts fast and the secondary is
   * tried within the remaining {@link #initTimeout()} budget. Defaults to {@link
   * #DEFAULT_CONFIG_FETCH_TIMEOUT} (~3s). Additive and backward-compatible — the default already
   * makes a hung upstream fail over, so existing callers need not set it.
   */
  public Duration configFetchTimeout() {
    return configFetchTimeout;
  }

  /**
   * Hedge delay for the parallel-failover hedge on the initial HTTP config fetch: how long the
   * primary leg is given before the secondary is ALSO fired in parallel (without cancelling the
   * primary). Defaults to {@link #DEFAULT_CONFIG_FETCH_HEDGE_DELAY} (~2s). A fast healthy primary
   * answers inside this window so the secondary stays a cold standby (zero extra load). Additive
   * and backward-compatible.
   */
  public Duration configFetchHedgeDelay() {
    return configFetchHedgeDelay;
  }

  /**
   * Per-leg hard-abort deadline on the hedged init fetch. Each hedge leg is bounded by this; it
   * must exceed the longest healable primary latency (so a late-but-newer primary heals forward
   * instead of aborting) and must be below {@link #initTimeout()} (so the init-path heal leg is not
   * clipped). Defaults to {@link #DEFAULT_CONFIG_FETCH_HEDGE_ABORT} (~6s). Distinct from {@link
   * #configFetchTimeout()}, which still bounds the sequential refresh/fallback-poll attempts.
   */
  public Duration configFetchHedgeAbort() {
    return configFetchHedgeAbort;
  }

  /**
   * Whether the Layer 2 fallback poller engages when SSE has been disconnected past {@link
   * com.quonfig.sdk.supervisor.FallbackPoller#DEFAULT_THRESHOLD}. Defaults to {@code true} (cross-
   * SDK parity with sdk-go/sdk-node). When false, an SSE-only client never falls back to HTTP
   * polling during an outage.
   */
  public boolean fallbackPollEnabled() {
    return fallbackPollEnabled;
  }

  /**
   * Poll cadence (milliseconds) once the Layer 2 fallback poller has engaged. Default 60000 (60s);
   * cross-SDK parity with sdk-node's {@code fallbackPollIntervalMs}. Ignored when {@link
   * #fallbackPollEnabled()} is false.
   */
  public long fallbackPollIntervalMs() {
    return fallbackPollIntervalMs;
  }

  /**
   * Disconnect duration before the Layer 2 fallback poller engages. {@code null} means use the
   * cross-SDK default ({@link com.quonfig.sdk.supervisor.FallbackPoller#DEFAULT_THRESHOLD}, 120s).
   * Surfaced for tests and the chaos harness — production should leave this unset.
   */
  public Duration fallbackPollThreshold() {
    return fallbackPollThreshold;
  }

  /**
   * Optional callback invoked with {@code true} when the Layer 2 fallback poller engages and {@code
   * false} when it disengages. Mostly useful for tests and the chaos harness; production code
   * should prefer {@code Quonfig.connectionState()} (qfg-47c2.23).
   */
  public Consumer<Boolean> onFallbackPollerStateChange() {
    return onFallbackPollerStateChange;
  }

  public ContextSet globalContext() {
    return globalContext;
  }

  /**
   * Whether the dev-only {@code quonfig-user.email} context is auto-injected from {@code qfg
   * login}'s tokens file. {@code null} means unset (the cross-SDK default-on path applies, gated by
   * {@code QUONFIG_DEV_CONTEXT} then the file's presence). Resolution happens at {@link
   * Builder#build()}, so this returns the raw builder value, not the resolved decision.
   */
  public Boolean enableQuonfigUserContext() {
    return enableQuonfigUserContext;
  }

  public Logger logger() {
    return logger;
  }

  public String datadir() {
    return datadir;
  }

  public String datafile() {
    return datafile;
  }

  /**
   * Pre-parsed datafile envelope. Mutually exclusive with {@link #datafile()} and {@link
   * #datadir()}. Mirrors sdk-node's object-form {@code datafile?: string | object} — supply a
   * {@link ConfigEnvelope} you've already deserialized (e.g., from a CDN-bundled JSON blob) to skip
   * the file read.
   */
  public ConfigEnvelope datafileEnvelope() {
    return datafileEnvelope;
  }

  public Resolver.EnvLookup envLookup() {
    return envLookup;
  }

  public Runnable onConfigUpdate() {
    return onConfigUpdate;
  }

  public Consumer<Boolean> onSseConnectionStateChange() {
    return onSseConnectionStateChange;
  }

  /**
   * Returns the weighted-values bucketing strategy. Null until {@code qfg-oi0j.5} lands the
   * MurmurHash3 implementation; tests may inject a fake to exercise the SPLIT reason path.
   */
  public WeightedValueResolver weightedValueResolver() {
    return weightedValueResolver;
  }

  public boolean disableTelemetry() {
    return disableTelemetry;
  }

  public boolean collectEvaluationSummaries() {
    return collectEvaluationSummaries;
  }

  public ContextUploadMode contextUploadMode() {
    return contextUploadMode;
  }

  public TelemetrySender telemetrySender() {
    return telemetrySender;
  }

  public Duration telemetryInitialDelay() {
    return telemetryInitialDelay;
  }

  public Duration telemetryFlushInterval() {
    return telemetryFlushInterval;
  }

  public Duration telemetryMaxInterval() {
    return telemetryMaxInterval;
  }

  public String instanceHash() {
    return instanceHash;
  }

  /**
   * Optional config key used by {@link Quonfig#shouldLog(String, org.slf4j.event.Level)} for the
   * single-config dispatch pattern. When set, that config is evaluated against a context with
   * {@code quonfig-sdk-logging.key=loggerPath} so a single log-level config can drive per-logger
   * rules. When unset, {@code shouldLog} looks up a config keyed by the loggerPath itself, walking
   * up dotted parents on miss.
   */
  public String loggerKey() {
    return loggerKey;
  }

  /**
   * SSE stall watchdog. When non-null, overrides the SSE transport's default 90s read deadline —
   * each chunk received resets the timer and the SDK closes the stream on stall. Surfaced mostly
   * for the chaos harness, which needs a sub-30s value to exercise the deadline-trip mechanism
   * within scenario expectation windows. Production should leave this null and take the 90s default
   * (= 3x the 30s server heartbeat).
   */
  public Duration sseReadWatchdog() {
    return sseReadWatchdog;
  }

  /**
   * Whether the datadir-mode client should watch the workspace for filesystem changes and
   * atomically reload the envelope on every debounced burst. Default {@code false} — adding a
   * background watcher thread to a previously-quiet datadir client is a behavior change, so it is
   * opt-in. When enabled, the SDK fires the same {@link #onConfigUpdate()} callback used by the
   * SSE/HTTP paths after a successful reload; partial-write parse errors keep the prior envelope
   * (no broken state is ever exposed). Cross-SDK parity with {@code sdk-node} (qfg-mol-0kr).
   */
  public boolean dataDirAutoReload() {
    return dataDirAutoReload;
  }

  /**
   * Debounce window (milliseconds) that coalesces filesystem bursts (atomic-rename editor saves,
   * {@code git pull} flurries) into a single reload. Default {@link
   * #DEFAULT_DATADIR_AUTORELOAD_DEBOUNCE_MS} (200ms). Ignored when {@link #dataDirAutoReload()} is
   * {@code false}.
   */
  public long dataDirAutoReloadDebounceMs() {
    return dataDirAutoReloadDebounceMs;
  }

  /**
   * Stream-base URLs for SSE. Resolution order: {@link Builder#streamUrls(List)} explicit override
   * → derived from {@link #apiUrls()} (each {@code primary.X}/{@code secondary.X} → {@code
   * stream.primary.X}/{@code stream.secondary.X}). The explicit override exists for cases where the
   * derivation rule doesn't fit (local dev pointed at {@code 127.0.0.1:port}, custom proxy hosts).
   */
  public List<String> streamUrls() {
    if (streamUrlsOverride != null) return streamUrlsOverride;
    return apiUrls.stream().map(Options::toStreamUrl).toList();
  }

  private static String toStreamUrl(String apiUrl) {
    int scheme = apiUrl.indexOf("://");
    if (scheme < 0) return apiUrl;
    String prefix = apiUrl.substring(0, scheme + 3);
    String host = apiUrl.substring(scheme + 3);
    return prefix + "stream." + host;
  }

  public static Builder builder() {
    return new Builder();
  }

  public static final class Builder {
    private String sdkKey;
    private String domain;
    private List<String> apiUrls;
    private List<String> streamUrls;
    private String telemetryUrl;
    private String environment;
    private Duration initTimeout;
    private Duration configFetchTimeout;
    private Duration configFetchHedgeDelay;
    private Duration configFetchHedgeAbort;
    private boolean fallbackPollEnabled = true;
    private long fallbackPollIntervalMs = 60_000L;
    private Duration fallbackPollThreshold;
    private Consumer<Boolean> onFallbackPollerStateChange;
    private ContextSet globalContext;
    private Logger logger;
    private String datadir;
    private String datafile;
    private ConfigEnvelope datafileEnvelope;
    private Resolver.EnvLookup envLookup;
    private Runnable onConfigUpdate;
    private Consumer<Boolean> onSseConnectionStateChange;
    private WeightedValueResolver weightedValueResolver;
    private boolean disableTelemetry;
    private boolean collectEvaluationSummaries = true;
    private ContextUploadMode contextUploadMode;
    private TelemetrySender telemetrySender;
    private Duration telemetryInitialDelay;
    private Duration telemetryFlushInterval;
    private Duration telemetryMaxInterval;
    private String instanceHash;
    private String loggerKey;
    private Duration sseReadWatchdog;
    private boolean dataDirAutoReload;
    private long dataDirAutoReloadDebounceMs;
    private Boolean enableQuonfigUserContext;

    public Builder sdkKey(String v) {
      this.sdkKey = v;
      return this;
    }

    public Builder domain(String v) {
      this.domain = v;
      return this;
    }

    public Builder apiUrls(List<String> v) {
      this.apiUrls = Objects.requireNonNull(v, "apiUrls");
      return this;
    }

    /**
     * Explicit override for the SSE stream URLs. When unset, stream URLs are derived from {@link
     * #apiUrls(List)} by prefixing {@code stream.} to the host portion.
     */
    public Builder streamUrls(List<String> v) {
      this.streamUrls = Objects.requireNonNull(v, "streamUrls");
      return this;
    }

    public Builder telemetryUrl(String v) {
      this.telemetryUrl = v;
      return this;
    }

    public Builder environment(String v) {
      this.environment = v;
      return this;
    }

    public Builder initTimeout(Duration v) {
      this.initTimeout = v;
      return this;
    }

    /**
     * Overrides the per-URL config-fetch deadline. Default (when null) is {@link
     * Options#DEFAULT_CONFIG_FETCH_TIMEOUT} (~3s). Bounds each base-URL attempt on the initial
     * fetch and the fallback poller; keep it well under {@link #initTimeout(Duration)} so a hung
     * leg fails over with budget to reach the next one. Pass a larger value only if a healthy
     * upstream legitimately takes longer than the default to answer a config fetch.
     */
    public Builder configFetchTimeout(Duration v) {
      this.configFetchTimeout = v;
      return this;
    }

    /**
     * Overrides the hedge delay on the initial HTTP config fetch — how long the primary leg is
     * given before the secondary is ALSO fired in parallel. Default (when null) is {@link
     * Options#DEFAULT_CONFIG_FETCH_HEDGE_DELAY} (~2s). Keep it below the worst-case healable
     * primary latency so a slow-but-alive primary triggers the hedge, and well below {@link
     * #configFetchHedgeAbort(Duration)}.
     */
    public Builder configFetchHedgeDelay(Duration v) {
      this.configFetchHedgeDelay = v;
      return this;
    }

    /**
     * Overrides the per-leg hard-abort deadline on the hedged init fetch. Default (when null) is
     * {@link Options#DEFAULT_CONFIG_FETCH_HEDGE_ABORT} (~6s). It must exceed the longest healable
     * primary latency so a late-but-newer primary heals forward, and must be below {@link
     * #initTimeout(Duration)} so the init-path heal leg is not clipped; the client logs a warning
     * at construction when {@code initTimeout <= configFetchHedgeAbort}.
     */
    public Builder configFetchHedgeAbort(Duration v) {
      this.configFetchHedgeAbort = v;
      return this;
    }

    /**
     * Whether to engage the Layer 2 fallback poller after SSE has been disconnected past the
     * cross-SDK 120s threshold. Defaults to {@code true}. Disable for SSE-only deployments where an
     * HTTP fallback poll is unwanted (e.g. dataplanes where each fetch is metered).
     */
    public Builder fallbackPollEnabled(boolean v) {
      this.fallbackPollEnabled = v;
      return this;
    }

    /**
     * Poll cadence (in milliseconds) once the Layer 2 fallback poller engages. Default 60000 (60s).
     * Cross-SDK parity with sdk-node's {@code fallbackPollIntervalMs} option.
     */
    public Builder fallbackPollIntervalMs(long v) {
      this.fallbackPollIntervalMs = v;
      return this;
    }

    /**
     * Overrides the disconnect duration before the Layer 2 fallback poller engages. Default (when
     * null) is {@link com.quonfig.sdk.supervisor.FallbackPoller#DEFAULT_THRESHOLD} (120s). Tests
     * use a sub-second value to verify wire-up without burning real time; production should leave
     * this unset.
     */
    public Builder fallbackPollThreshold(Duration v) {
      this.fallbackPollThreshold = v;
      return this;
    }

    /**
     * Optional listener invoked with {@code true} when the Layer 2 fallback poller engages (SSE
     * down past threshold) and {@code false} when it disengages (SSE recovered or client closed).
     * Surfaced primarily for the chaos harness; production should read polling state via {@code
     * Quonfig.connectionState()} once qfg-47c2.23 lands.
     */
    public Builder onFallbackPollerStateChange(Consumer<Boolean> v) {
      this.onFallbackPollerStateChange = v;
      return this;
    }

    public Builder globalContext(ContextSet v) {
      this.globalContext = v;
      return this;
    }

    public Builder logger(Logger v) {
      this.logger = v;
      return this;
    }

    public Builder datadir(String v) {
      this.datadir = v;
      return this;
    }

    public Builder datafile(String v) {
      this.datafile = v;
      return this;
    }

    /**
     * Pre-parsed datafile envelope (sdk-node's object-form datafile). Mutually exclusive with
     * {@link #datafile(String)} and {@link #datadir(String)}.
     */
    public Builder datafileEnvelope(ConfigEnvelope v) {
      this.datafileEnvelope = v;
      return this;
    }

    public Builder envLookup(Resolver.EnvLookup v) {
      this.envLookup = v;
      return this;
    }

    public Builder onConfigUpdate(Runnable v) {
      this.onConfigUpdate = v;
      return this;
    }

    public Builder onSseConnectionStateChange(Consumer<Boolean> v) {
      this.onSseConnectionStateChange = v;
      return this;
    }

    public Builder weightedValueResolver(WeightedValueResolver v) {
      this.weightedValueResolver = v;
      return this;
    }

    public Builder disableTelemetry(boolean v) {
      this.disableTelemetry = v;
      return this;
    }

    public Builder collectEvaluationSummaries(boolean v) {
      this.collectEvaluationSummaries = v;
      return this;
    }

    public Builder contextUploadMode(ContextUploadMode v) {
      this.contextUploadMode = v;
      return this;
    }

    public Builder telemetrySender(TelemetrySender v) {
      this.telemetrySender = v;
      return this;
    }

    public Builder telemetryInitialDelay(Duration v) {
      this.telemetryInitialDelay = v;
      return this;
    }

    public Builder telemetryFlushInterval(Duration v) {
      this.telemetryFlushInterval = v;
      return this;
    }

    public Builder telemetryMaxInterval(Duration v) {
      this.telemetryMaxInterval = v;
      return this;
    }

    public Builder instanceHash(String v) {
      this.instanceHash = v;
      return this;
    }

    public Builder loggerKey(String v) {
      this.loggerKey = v;
      return this;
    }

    /**
     * Overrides the SSE stall watchdog. Default (when null) is 90s. The chaos harness uses a
     * sub-30s value to exercise the deadline-trip mechanism within the 15s expectation window of
     * scenario 07; production should leave this unset.
     */
    public Builder sseReadWatchdog(Duration v) {
      this.sseReadWatchdog = v;
      return this;
    }

    /**
     * Opt in to filesystem watching of the {@link #datadir(String)} workspace. When {@code true}, a
     * background daemon thread re-reads the directory and atomically swaps the in-memory store on
     * every debounced burst, firing the existing {@link #onConfigUpdate(Runnable)} callback.
     * Default {@code false}.
     *
     * <p>Risk mitigations baked into the implementation:
     *
     * <ul>
     *   <li><b>Partial-write races</b> — parse-then-swap: a parse error keeps the prior envelope
     *       and skips the callback (no broken state is exposed).
     *   <li><b>Burst events</b> — debounced via {@link #dataDirAutoReloadDebounceMs(long)} (default
     *       200ms). Editor atomic-renames and {@code git pull} flurries coalesce to one reload.
     *   <li><b>Read-only filesystem</b> — registration failure is logged and the SDK keeps serving
     *       the init-time envelope rather than throwing.
     *   <li><b>Thread cleanup</b> — the watcher is a daemon thread, stopped synchronously on {@link
     *       Quonfig#close()}.
     *   <li><b>Symlinked datadirs</b> — the datadir is resolved via {@code Path.toRealPath()} at
     *       start, so events on the underlying directory are observed.
     * </ul>
     *
     * <p><b>macOS caveat:</b> The JDK uses a polling {@code WatchService} on macOS; the detection
     * floor is ~2s even at HIGH sensitivity. On Linux (inotify) and Windows (ReadDirectoryChangesW)
     * the latency is well under 100ms.
     */
    public Builder dataDirAutoReload(boolean v) {
      this.dataDirAutoReload = v;
      return this;
    }

    /**
     * Overrides the debounce window (milliseconds) that coalesces filesystem bursts. Default {@link
     * Options#DEFAULT_DATADIR_AUTORELOAD_DEBOUNCE_MS} (200ms). Tests use shorter values for
     * responsiveness; production should leave this at the default.
     */
    public Builder dataDirAutoReloadDebounceMs(long v) {
      this.dataDirAutoReloadDebounceMs = v;
      return this;
    }

    /**
     * Controls auto-injection of the dev-only {@code quonfig-user.email} context, read from {@code
     * qfg login}'s {@code ~/.quonfig/tokens.json}. Unset (default) means the cross-SDK default-on
     * behavior applies: enabled unless {@code QUONFIG_DEV_CONTEXT=false}, and inert anyway when no
     * tokens file exists (so it is dead in production). Pass {@code false} to hard-disable, or
     * {@code true} to force-enable regardless of {@code QUONFIG_DEV_CONTEXT}. Cross-SDK parity with
     * sdk-node's {@code enableQuonfigUserContext}.
     */
    public Builder enableQuonfigUserContext(Boolean v) {
      this.enableQuonfigUserContext = v;
      return this;
    }

    public Options build() {
      return new Options(this);
    }
  }
}
