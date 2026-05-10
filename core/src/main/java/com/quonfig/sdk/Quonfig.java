package com.quonfig.sdk;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.quonfig.sdk.eval.ConfigRow;
import com.quonfig.sdk.eval.ConfigStore;
import com.quonfig.sdk.eval.ContextSet;
import com.quonfig.sdk.eval.EvaluationMatch;
import com.quonfig.sdk.eval.Evaluator;
import com.quonfig.sdk.eval.Resolver;
import com.quonfig.sdk.eval.ResolverException;
import com.quonfig.sdk.eval.Value;
import com.quonfig.sdk.eval.ValueType;
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
   * Environment used for evaluation and emitted in metadata. Defaults to {@link
   * Options#environment()}; in datafile mode without an explicit environment, falls back to {@code
   * envelope.meta.environment} (sdk-node parity, see qfg-9hre).
   */
  private volatile String effectiveEnvironment;

  private volatile InMemoryConfigStore store;
  private volatile Evaluator evaluator;
  private volatile Resolver resolver;
  private volatile boolean closed;
  private volatile SseClient sseClient;

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
    } else if ((options.datafile() != null && !options.datafile().isEmpty())
        || options.datafileEnvelope() != null) {
      ConfigEnvelope envelope = loadDatafileEnvelope(options);
      // Per sdk-node: caller's explicit Options.environment() wins; otherwise fall back to
      // envelope.meta.environment so a self-contained datafile evaluates against the
      // environment it was generated for.
      if ((effectiveEnvironment == null || effectiveEnvironment.isEmpty())
          && envelope.meta() != null
          && envelope.meta().environment() != null
          && !envelope.meta().environment().isEmpty()) {
        this.effectiveEnvironment = envelope.meta().environment();
      }
      installEnvelopeRows(envelope);
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
              .build();
      HttpResponse<String> resp =
          http.get(URI.create(CONFIGS_PATH), null)
              .get(options.initTimeout().toMillis(), TimeUnit.MILLISECONDS);
      installEnvelope(resp.body());
      initFuture.complete(null);
      fireConfigUpdate();
      startSse();
    } catch (TimeoutException e) {
      initFuture.completeExceptionally(
          new IllegalStateException("client initialization exceeded " + options.initTimeout(), e));
    } catch (Exception e) {
      Throwable cause = e.getCause() != null ? e.getCause() : e;
      initFuture.completeExceptionally(
          new IllegalStateException("client initialization failed: " + cause.getMessage(), cause));
    }
  }

  private void installEnvelope(String body) throws IOException {
    ConfigEnvelope envelope = ENVELOPE_MAPPER.readValue(body, ConfigEnvelope.class);
    installEnvelopeRows(envelope);
  }

  private void installEnvelopeRows(ConfigEnvelope envelope) {
    List<ConfigRow> rows = new ArrayList<>(envelope.configs().size());
    for (JsonNode cfg : envelope.configs()) {
      rows.add(DatadirLoader.parseConfigNode(cfg));
    }
    installRows(rows);
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
    List<URI> streams = new ArrayList<>(options.streamUrls().size());
    for (String u : options.streamUrls()) streams.add(URI.create(u));
    SseClient sse = SseClient.builder().streamUrls(streams).sdkKey(options.sdkKey()).build();
    sse.onEnvelope(
        env -> {
          try {
            List<ConfigRow> rows = new ArrayList<>(env.configs().size());
            for (JsonNode cfg : env.configs()) rows.add(DatadirLoader.parseConfigNode(cfg));
            installRows(rows);
            fireConfigUpdate();
          } catch (RuntimeException ignored) {
            // Bad envelope is non-fatal — keep the prior store in place.
          }
        });
    sse.onConnectionStateChange(
        connected -> {
          for (Consumer<Boolean> l : sseListeners) {
            try {
              l.accept(connected);
            } catch (RuntimeException ignored) {
              // user code; never tear down the client
            }
          }
        });
    this.sseClient = sse;
    sse.start();
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
    EvaluationDetails<Boolean> d = getBooleanDetails(key, Boolean.FALSE, ctx);
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
    SseClient sse = sseClient;
    if (sse != null) sse.stop();
    if (telemetryReporter != null) telemetryReporter.close();
  }

  // ---- typed getters (no context) ----

  public String getString(String key, String def) {
    return getString(key, def, null);
  }

  public Boolean getBoolean(String key, Boolean def) {
    return getBoolean(key, def, null);
  }

  public Long getInt(String key, Long def) {
    return getInt(key, def, null);
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

  public Boolean getBoolean(String key, Boolean def, ContextSet ctx) {
    return getBooleanDetails(key, def, ctx).value();
  }

  public Long getInt(String key, Long def, ContextSet ctx) {
    return getIntDetails(key, def, ctx).value();
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

  public EvaluationDetails<Boolean> getBooleanDetails(String key, Boolean def) {
    return getBooleanDetails(key, def, null);
  }

  public EvaluationDetails<Long> getIntDetails(String key, Long def) {
    return getIntDetails(key, def, null);
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

  public EvaluationDetails<Boolean> getBooleanDetails(String key, Boolean def, ContextSet ctx) {
    return typedDetails(key, def, ctx, ValueType.BOOL, Boolean.class);
  }

  public EvaluationDetails<Long> getIntDetails(String key, Long def, ContextSet ctx) {
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
      } catch (RuntimeException ignored) {
        // listeners are user code; never let one tear down the client
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
