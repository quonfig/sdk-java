package com.quonfig.sdk;

import com.quonfig.sdk.eval.ContextSet;
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

  private final String sdkKey;
  private final String domain;
  private final List<String> apiUrls;
  private final List<String> streamUrlsOverride;
  private final String telemetryUrl;
  private final String environment;
  private final Duration initTimeout;
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

  private Options(Builder b) {
    Resolver.EnvLookup env = b.envLookup != null ? b.envLookup : Resolver.DEFAULT_ENV_LOOKUP;

    this.envLookup = env;
    this.sdkKey = b.sdkKey != null ? b.sdkKey : env.lookup("QUONFIG_BACKEND_SDK_KEY").orElse(null);
    this.domain = b.domain != null ? b.domain : env.lookup("QUONFIG_DOMAIN").orElse(DEFAULT_DOMAIN);
    this.environment =
        b.environment != null ? b.environment : env.lookup("QUONFIG_ENVIRONMENT").orElse(null);

    String d = this.domain;
    this.apiUrls =
        b.apiUrls != null
            ? List.copyOf(b.apiUrls)
            : List.of("https://primary." + d, "https://secondary." + d);
    this.streamUrlsOverride = b.streamUrls != null ? List.copyOf(b.streamUrls) : null;
    this.telemetryUrl = b.telemetryUrl != null ? b.telemetryUrl : "https://telemetry." + d;

    this.initTimeout = b.initTimeout != null ? b.initTimeout : DEFAULT_INIT_TIMEOUT;
    this.fallbackPollEnabled = b.fallbackPollEnabled;
    this.fallbackPollIntervalMs = b.fallbackPollIntervalMs;
    this.fallbackPollThreshold = b.fallbackPollThreshold;
    this.onFallbackPollerStateChange = b.onFallbackPollerStateChange;
    this.globalContext = b.globalContext;
    this.logger = b.logger != null ? b.logger : LoggerFactory.getLogger("com.quonfig.sdk");
    this.datadir = b.datadir;
    this.datafile = b.datafile;
    this.datafileEnvelope = b.datafileEnvelope;
    this.onConfigUpdate = b.onConfigUpdate;
    this.onSseConnectionStateChange = b.onSseConnectionStateChange;
    this.weightedValueResolver = b.weightedValueResolver;
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

    public Options build() {
      return new Options(this);
    }
  }
}
