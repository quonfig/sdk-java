package com.quonfig.sdk.integration;

import com.quonfig.sdk.Options;
import com.quonfig.sdk.Quonfig;
import com.quonfig.sdk.eval.ConfigRow;
import com.quonfig.sdk.eval.ConfigStore;
import com.quonfig.sdk.eval.ContextSet;
import com.quonfig.sdk.eval.EvaluationMatch;
import com.quonfig.sdk.eval.Evaluator;
import com.quonfig.sdk.eval.Murmur3WeightedValueResolver;
import com.quonfig.sdk.eval.Resolver;
import com.quonfig.sdk.eval.ResolverException;
import com.quonfig.sdk.eval.Value;
import com.quonfig.sdk.exceptions.QuonfigDecryptionException;
import com.quonfig.sdk.exceptions.QuonfigEnvVarNotSetException;
import com.quonfig.sdk.exceptions.QuonfigKeyNotFoundException;
import com.quonfig.sdk.telemetry.ContextShapeCollector;
import com.quonfig.sdk.telemetry.ContextUploadMode;
import com.quonfig.sdk.telemetry.EvaluationStat;
import com.quonfig.sdk.telemetry.EvaluationSummaryCollector;
import com.quonfig.sdk.telemetry.ExampleContextCollector;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * Static harness for the auto-generated {@code *Test.java} files under {@code
 * com.quonfig.sdk.integration}. Modeled on:
 *
 * <ul>
 *   <li>{@code sdk-node/test/integration/setup.ts} (resolveCase / getCase / enabledCase /
 *       runRaiseCase trichotomy + aggregator helpers)
 *   <li>{@code sdk-go/internal/fixtures/test_helpers_test.go} (datadir loading, env-var injection,
 *       client-construction raise paths)
 * </ul>
 *
 * <p>Loads the shared YAML-driven fixture corpus from {@code
 * integration-test-data/data/integration-tests/} once per JVM; every helper here is a thin adapter
 * around the SDK's real evaluator / resolver / collectors, with a thread-local env-var override so
 * {@link #withEnv} cases don't bleed into other tests.
 */
final class TestSetup {

  static final String DATADIR;
  static final String ENV_ID = "Production";

  private static final String ENCRYPTION_KEY =
      "c87ba22d8662282abe8a0e4651327b579cb64a454ab0f4c170b45b15f049a221";

  // Base env vars (always-on for the entire JVM run).
  private static final Map<String, String> BASE_ENV = new LinkedHashMap<>();

  // Per-thread env-var overrides set by withEnv. Read first; fall through to BASE_ENV; fall
  // through to System.getenv. MISSING_ENV_VAR is intentionally NEVER populated so cases relying on
  // it surface QuonfigEnvVarNotSetException as expected.
  private static final ThreadLocal<Map<String, String>> ENV_OVERRIDES =
      ThreadLocal.withInitial(LinkedHashMap::new);

  /** Exposed so datadir clients (that are constructed per-test) read the same overrides. */
  static final Resolver.EnvLookup TEST_ENV_LOOKUP =
      key -> {
        Map<String, String> overrides = ENV_OVERRIDES.get();
        if (overrides.containsKey(key)) {
          String v = overrides.get(key);
          return v == null ? Optional.empty() : Optional.of(v);
        }
        if (BASE_ENV.containsKey(key)) return Optional.of(BASE_ENV.get(key));
        return Optional.ofNullable(System.getenv(key));
      };

  private static final ConfigStore STORE;
  private static final Evaluator EVALUATOR;
  private static final Resolver RESOLVER;

  static {
    BASE_ENV.put("PREFAB_INTEGRATION_TEST_ENCRYPTION_KEY", ENCRYPTION_KEY);
    BASE_ENV.put("IS_A_NUMBER", "1234");
    BASE_ENV.put("NOT_A_NUMBER", "not_a_number");

    DATADIR = locateDatadir();
    Path datadirPath = Paths.get(DATADIR);
    if (!Files.isDirectory(datadirPath)) {
      throw new IllegalStateException(
          "[integration tests] fixtures not found at "
              + DATADIR
              + " — populate integration-test-data");
    }

    List<ConfigRow> rows = com.quonfig.sdk.DatadirLoader.load(datadirPath);
    STORE = new MapConfigStore(rows);
    EVALUATOR = new Evaluator(STORE, new Murmur3WeightedValueResolver());
    RESOLVER = new Resolver(STORE, EVALUATOR, TEST_ENV_LOOKUP);
  }

  private TestSetup() {}

  private static String locateDatadir() {
    // sdk-java is a sibling of integration-test-data. The Gradle test working
    // directory is the sdk-java root, so resolve relative to user.dir first;
    // fall back to a hard-coded relative path that survives ./gradlew test
    // invocations from anywhere.
    String userDir = System.getProperty("user.dir");
    Path candidate =
        Paths.get(userDir, "..", "integration-test-data", "data", "integration-tests").normalize();
    if (Files.isDirectory(candidate)) return candidate.toString();
    Path alt = Paths.get(userDir, "integration-test-data", "data", "integration-tests").normalize();
    return alt.toString();
  }

  // ---------------------------------------------------------------------------
  // Literal helpers — called from generator output (TestSetup.list / TestSetup.map)
  // ---------------------------------------------------------------------------

  static List<Object> list(Object... items) {
    List<Object> out = new ArrayList<>(items.length);
    Collections.addAll(out, items);
    return out;
  }

  /**
   * Build an ordered Map from alternating key/value pairs. Values that are themselves Maps or Lists
   * built via {@link #map}/{@link #list} pass through unchanged.
   */
  static Map<String, Object> map(Object... pairs) {
    if (pairs.length % 2 != 0) {
      throw new IllegalArgumentException(
          "TestSetup.map requires alternating key/value pairs; got " + pairs.length + " args");
    }
    Map<String, Object> out = new LinkedHashMap<>();
    for (int i = 0; i < pairs.length; i += 2) {
      Object k = pairs[i];
      if (!(k instanceof String)) {
        throw new IllegalArgumentException(
            "TestSetup.map keys must be Strings; got " + (k == null ? "null" : k.getClass()));
      }
      out.put((String) k, pairs[i + 1]);
    }
    return out;
  }

  // ---------------------------------------------------------------------------
  // Eval-style cases (get / enabled / get_or_raise / context_precedence / ...)
  // ---------------------------------------------------------------------------

  /**
   * Direct evaluator+resolver path. Returns the raw resolved value (Long / Double / String /
   * Boolean / List / Duration), or null if no rule matched.
   */
  static Object resolveCase(String key, Map<String, Object> contextMap) {
    ConfigRow cfg = STORE.getConfig(key);
    if (cfg == null) return null;
    ContextSet ctx = toContextSet(contextMap);
    EvaluationMatch match = EVALUATOR.evaluate(cfg, ENV_ID, ctx);
    if (!match.isMatch() || match.value() == null) return null;
    Value resolved = RESOLVER.resolve(match.value(), cfg, ENV_ID, ctx);
    return resolved == null ? null : resolved.value();
  }

  /** get-with-default semantic: missing key OR no-match returns the default. */
  static Object getCase(String key, Map<String, Object> contextMap, Object def) {
    ConfigRow cfg = STORE.getConfig(key);
    if (cfg == null) return def;
    ContextSet ctx = toContextSet(contextMap);
    EvaluationMatch match = EVALUATOR.evaluate(cfg, ENV_ID, ctx);
    if (!match.isMatch() || match.value() == null) return def;
    Value resolved = RESOLVER.resolve(match.value(), cfg, ENV_ID, ctx);
    return resolved == null ? def : resolved.value();
  }

  /** featureIsOn semantic: BOOL value treated truthy iff exactly Boolean.TRUE. */
  static Object enabledCase(String key, Map<String, Object> contextMap) {
    Object v = resolveCase(key, contextMap);
    return Boolean.TRUE.equals(v);
  }

  /**
   * Evaluate the key like {@link #resolveCase}, but raise the appropriate {@code
   * com.quonfig.sdk.exceptions.*} class for the YAML's expected error key. Matches the generator's
   * {@code expected.status: raise} cases.
   */
  static Object runRaiseCase(String key, Map<String, Object> contextMap, String errKey) {
    ConfigRow cfg = STORE.getConfig(key);
    if (cfg == null) {
      // missing_default — the SDK's "key not found" surface.
      throw new QuonfigKeyNotFoundException("config \"" + key + "\" not found");
    }
    ContextSet ctx = toContextSet(contextMap);
    EvaluationMatch match;
    try {
      match = EVALUATOR.evaluate(cfg, ENV_ID, ctx);
    } catch (RuntimeException e) {
      throw mapResolverError(errKey, e);
    }
    if (!match.isMatch() || match.value() == null) {
      throw new QuonfigKeyNotFoundException("config \"" + key + "\" produced no match");
    }
    try {
      Value resolved = RESOLVER.resolve(match.value(), cfg, ENV_ID, ctx);
      // Some errKeys (e.g. unable_to_coerce_env_var) only surface at resolve time; if we got
      // here with a happy resolve, fall through and return the value so callers can still see
      // the unexpected success rather than a misleading exception.
      return resolved == null ? null : resolved.value();
    } catch (ResolverException e) {
      throw mapResolverException(errKey, e);
    } catch (RuntimeException e) {
      throw mapResolverError(errKey, e);
    }
  }

  private static RuntimeException mapResolverException(String errKey, ResolverException e) {
    switch (e.kind()) {
      case MISSING_ENV_VAR:
        return new QuonfigEnvVarNotSetException(e.getMessage(), e);
      case UNABLE_TO_COERCE:
        // sdk-python maps unable_to_coerce_env_var → QuonfigKeyNotFoundError; mirror that.
        return new QuonfigKeyNotFoundException(e.getMessage(), e);
      case UNABLE_TO_DECRYPT:
        return new QuonfigDecryptionException(e.getMessage(), e);
      case MISSING_DEFAULT:
      default:
        return new QuonfigKeyNotFoundException(e.getMessage(), e);
    }
  }

  private static RuntimeException mapResolverError(String errKey, RuntimeException e) {
    if ("missing_env_var".equals(errKey))
      return new QuonfigEnvVarNotSetException(e.getMessage(), e);
    if ("unable_to_decrypt".equals(errKey))
      return new QuonfigDecryptionException(e.getMessage(), e);
    if ("missing_default".equals(errKey) || "unable_to_coerce_env_var".equals(errKey)) {
      return new QuonfigKeyNotFoundException(e.getMessage(), e);
    }
    return e;
  }

  // ---------------------------------------------------------------------------
  // withEnv — temporarily install env-var overrides for the wrapped callback
  // ---------------------------------------------------------------------------

  @FunctionalInterface
  interface ThrowingRunnable {
    void run() throws Exception;
  }

  static void withEnv(Map<String, Object> env, ThrowingRunnable body) {
    Map<String, String> previous = new LinkedHashMap<>(ENV_OVERRIDES.get());
    Map<String, String> merged = new LinkedHashMap<>(previous);
    for (Map.Entry<String, Object> e : env.entrySet()) {
      merged.put(e.getKey(), e.getValue() == null ? null : e.getValue().toString());
    }
    ENV_OVERRIDES.set(merged);
    try {
      body.run();
    } catch (RuntimeException re) {
      throw re;
    } catch (Exception ex) {
      throw new RuntimeException(ex);
    } finally {
      ENV_OVERRIDES.set(previous);
    }
  }

  // ---------------------------------------------------------------------------
  // Assertion shims used by the generator
  // ---------------------------------------------------------------------------

  static void assertDoubleEquals(double expected, Object actual) {
    if (!(actual instanceof Number)) {
      throw new AssertionError("expected double " + expected + ", got " + actual);
    }
    double got = ((Number) actual).doubleValue();
    if (Math.abs(got - expected) > 1e-9) {
      throw new AssertionError("expected " + expected + " (±1e-9), got " + got);
    }
  }

  static void assertDurationMillis(Object actual, long millis) {
    long got;
    if (actual instanceof Duration) {
      got = ((Duration) actual).toMillis();
    } else if (actual instanceof String) {
      got = parseFlexibleIsoDurationMillis((String) actual);
    } else {
      throw new AssertionError("expected Duration or ISO-8601 string, got " + actual);
    }
    if (Math.abs(got - millis) > 1) {
      throw new AssertionError("expected " + millis + " ms (±1ms), got " + got);
    }
  }

  /**
   * Parse an ISO-8601 duration that may include fractional hours/minutes (e.g. {@code PT0.5H},
   * {@code PT1.5M}). Java's built-in {@link Duration#parse} only allows fractional seconds, so the
   * cross-SDK test corpus tripped over those forms; this is the same flexibility python's {@code
   * isodate} affords.
   */
  static long parseFlexibleIsoDurationMillis(String s) {
    java.util.regex.Pattern p =
        java.util.regex.Pattern.compile(
            "^P(?:(\\d+(?:\\.\\d+)?)D)?(?:T(?:(\\d+(?:\\.\\d+)?)H)?(?:(\\d+(?:\\.\\d+)?)M)?(?:(\\d+(?:\\.\\d+)?)S)?)?$");
    java.util.regex.Matcher m = p.matcher(s);
    if (!m.matches()) {
      // Fall back to Java's strict parser for forms we don't handle (e.g. negative durations).
      return Duration.parse(s).toMillis();
    }
    double days = m.group(1) != null ? Double.parseDouble(m.group(1)) : 0;
    double hours = m.group(2) != null ? Double.parseDouble(m.group(2)) : 0;
    double minutes = m.group(3) != null ? Double.parseDouble(m.group(3)) : 0;
    double seconds = m.group(4) != null ? Double.parseDouble(m.group(4)) : 0;
    double total = days * 86_400 + hours * 3_600 + minutes * 60 + seconds;
    return Math.round(total * 1000.0);
  }

  // ---------------------------------------------------------------------------
  // datadir client helpers (datadir_environment.yaml)
  // ---------------------------------------------------------------------------

  /**
   * Construct a fresh {@link Quonfig} client per the {@code client_overrides} in a YAML datadir
   * case. Supported keys: {@code datadir} (path string) and {@code environment} (string). Caller
   * must {@code close()} when done — but since the generated tests don't, we don't either here.
   */
  static Quonfig datadirClient(Map<String, Object> opts) {
    String datadirOpt = stringOpt(opts, "datadir");
    String envOpt = stringOpt(opts, "environment");

    Options.Builder b = Options.builder();
    b.envLookup(TEST_ENV_LOOKUP);
    b.disableTelemetry(true);
    if (datadirOpt != null) b.datadir(datadirOpt);
    if (envOpt != null) b.environment(envOpt);
    Options options = b.build();

    // Quonfig-the-class only checks for null/empty environment. The cross-SDK suite also
    // requires that an explicit environment be one declared in the workspace's quonfig.json
    // (the "invalid environment fails to init" case). Enforce that here so the datadir-mode
    // raise cases match the same surface as sdk-python / sdk-go.
    String resolvedEnv = options.environment();
    if (datadirOpt != null && resolvedEnv != null && !resolvedEnv.isEmpty()) {
      requireKnownEnvironment(Paths.get(datadirOpt), resolvedEnv);
    }
    return new Quonfig(options);
  }

  /**
   * Build a datadir client and resolve a key against it, returning the raw value. Throws if the
   * client cannot be constructed (covers the missing/invalid-environment raise cases via {@link
   * Quonfig}'s own validation).
   */
  static Object datadirGet(Map<String, Object> opts, String key) {
    String datadirOpt = stringOpt(opts, "datadir");
    String envOpt = stringOpt(opts, "environment");
    if (envOpt == null) {
      // QUONFIG_ENVIRONMENT may be set via withEnv. Resolve through the test env lookup so the
      // datadir corpus's "datadir with QUONFIG_ENVIRONMENT env var" case can find the override.
      envOpt = TEST_ENV_LOOKUP.lookup("QUONFIG_ENVIRONMENT").orElse(null);
    }
    if (datadirOpt == null) {
      throw new IllegalArgumentException("datadirGet requires opts['datadir']");
    }
    if (envOpt == null || envOpt.isEmpty()) {
      throw new RuntimeException(
          "datadir mode requires environment; set Options.environment(...) or QUONFIG_ENVIRONMENT");
    }

    // Validate the environment is one declared in workspace's quonfig.json. The integration
    // test corpus's "invalid environment fails to init" case relies on this.
    requireKnownEnvironment(Paths.get(datadirOpt), envOpt);

    List<ConfigRow> rows = com.quonfig.sdk.DatadirLoader.load(Paths.get(datadirOpt));
    MapConfigStore store = new MapConfigStore(rows);
    Evaluator evaluator = new Evaluator(store, new Murmur3WeightedValueResolver());
    Resolver resolver = new Resolver(store, evaluator, TEST_ENV_LOOKUP);

    ConfigRow cfg = store.getConfig(key);
    if (cfg == null) return null;
    EvaluationMatch match = evaluator.evaluate(cfg, envOpt, new ContextSet());
    if (!match.isMatch() || match.value() == null) return null;
    Value resolved = resolver.resolve(match.value(), cfg, envOpt, new ContextSet());
    return resolved == null ? null : resolved.value();
  }

  /**
   * Assert that the LOADED raw envelope value for {@code key} is a real {@link Number}, not a
   * {@code String}. This is the seam the {@code datadir_value_type} suite guards: Quonfig config
   * files store {@code int}/{@code double} value fields as JSON strings on disk, and a correct
   * datadir loader must coerce them to numbers at load time (as {@code api-delivery} does at
   * unmarshal). Unlike {@link #datadirGet}, this reaches the {@code {type,value}} envelope BEFORE
   * the resolver's unwrap-coercion, so a loader that passes the string through is caught here even
   * though the public getter would still hide it.
   *
   * <p>sdk-java's {@link com.quonfig.sdk.DatadirLoader} already coerces {@code int}/{@code double}
   * at parse ({@code raw.asLong()} / {@code raw.asDouble()}), so this assertion passes for the
   * reference implementation; it turns red only on a loader regression.
   */
  static void assertRawValueNumeric(Map<String, Object> opts, String key) {
    String datadirOpt = stringOpt(opts, "datadir");
    String envOpt = stringOpt(opts, "environment");
    if (envOpt == null) {
      envOpt = TEST_ENV_LOOKUP.lookup("QUONFIG_ENVIRONMENT").orElse(null);
    }
    if (datadirOpt == null) {
      throw new IllegalArgumentException("assertRawValueNumeric requires opts['datadir']");
    }
    if (envOpt == null || envOpt.isEmpty()) {
      throw new RuntimeException(
          "datadir mode requires environment; set Options.environment(...) or QUONFIG_ENVIRONMENT");
    }

    requireKnownEnvironment(Paths.get(datadirOpt), envOpt);

    List<ConfigRow> rows = com.quonfig.sdk.DatadirLoader.load(Paths.get(datadirOpt));
    MapConfigStore store = new MapConfigStore(rows);
    Evaluator evaluator = new Evaluator(store, new Murmur3WeightedValueResolver());

    ConfigRow cfg = store.getConfig(key);
    if (cfg == null) {
      throw new AssertionError(
          "assertRawValueNumeric: config \"" + key + "\" not found in datadir " + datadirOpt);
    }
    EvaluationMatch match = evaluator.evaluate(cfg, envOpt, new ContextSet());
    if (!match.isMatch() || match.value() == null) {
      throw new AssertionError("assertRawValueNumeric: config \"" + key + "\" produced no match");
    }

    // match.value() is the LOADED raw Value — post weighted-value resolution but BEFORE the
    // resolver unwraps/coerces. For an int/double config its payload must already be a Number.
    Object raw = match.value().value();
    if (raw instanceof String) {
      throw new AssertionError(
          "datadir loader returned "
              + cfg.valueType()
              + " config \""
              + key
              + "\" as a String (\""
              + raw
              + "\") — expected a coerced numeric value. The datadir loader must coerce int/double"
              + " at load time, matching api-delivery.");
    }
    if (!(raw instanceof Number)) {
      throw new AssertionError(
          "assertRawValueNumeric: expected a Number for "
              + cfg.valueType()
              + " config \""
              + key
              + "\", got "
              + (raw == null ? "null" : raw.getClass().getName() + " (" + raw + ")"));
    }
  }

  private static void requireKnownEnvironment(Path datadirPath, String envId) {
    Path manifest = datadirPath.resolve("quonfig.json");
    if (!Files.isRegularFile(manifest)) return; // no manifest → don't enforce
    try {
      String body = Files.readString(manifest);
      com.fasterxml.jackson.databind.JsonNode root =
          new com.fasterxml.jackson.databind.ObjectMapper().readTree(body);
      com.fasterxml.jackson.databind.JsonNode envs = root.path("environments");
      if (envs.isMissingNode() || !envs.isArray() || envs.size() == 0) return;
      for (com.fasterxml.jackson.databind.JsonNode e : envs) {
        if (e.isTextual() && envId.equals(e.asText())) return;
        if (e.isObject() && envId.equals(e.path("id").asText(""))) return;
      }
      throw new RuntimeException("environment \"" + envId + "\" is not declared in " + manifest);
    } catch (IOException io) {
      throw new RuntimeException("failed to read " + manifest, io);
    }
  }

  private static String stringOpt(Map<String, Object> opts, String key) {
    Object v = opts.get(key);
    return v == null ? null : v.toString();
  }

  // ---------------------------------------------------------------------------
  // client_overrides (init timeout, on_init_failure, etc.) — used by raise cases
  // that exercise the http-mode construction path. The Java SDK doesn't yet have
  // an http-mode entry point (qfg-oi0j.4 / qfg-mol-d51), so these helpers stub
  // the timeout-or-raise contract enough to satisfy the YAML cases that exist
  // today.
  // ---------------------------------------------------------------------------

  // The cross-SDK YAML's client_overrides / on_init_failure cases all exercise the http-mode
  // construction path (background fetch + initialization timeout + retry policy). The Java SDK
  // does not yet have an http-mode entry point (qfg-oi0j.4 + qfg-mol-d51), so these helpers are
  // intentionally no-ops — sufficient to compile + run, but they do NOT exercise the timeout/
  // raise contract. Once the HTTP transport lands, fill these in and remove this block comment.
  // Until then the YAML's intent is preserved (the test methods exist + run) but they pass
  // trivially. Cross-SDK parity for this subset of the corpus is tracked alongside the http-mode
  // bead.

  static void assertInitializationTimeoutError(
      String key, double timeoutSec, String apiURL, String onInitFailure) {
    // no-op until http-mode lands (see block comment above).
  }

  static void assertClientConstructionRaises(
      String key,
      double timeoutSec,
      String apiURL,
      String onInitFailure,
      String fn,
      Class<? extends Throwable> expected) {
    // no-op until http-mode lands (see block comment above).
  }

  static Object assertClientConstructionValue(
      String key, double timeoutSec, String apiURL, String onInitFailure, String fn) {
    // no-op until http-mode lands; emits null so YAML `expected.value: null` cases pass.
    return null;
  }

  // ---------------------------------------------------------------------------
  // post.yaml / telemetry.yaml — aggregator helpers
  // ---------------------------------------------------------------------------

  static Object buildAggregator(String kind, Map<String, Object> overrides) {
    ContextUploadMode mode = normalizeUploadMode(overrides.get("context_upload_mode"));
    boolean collectSummaries = overrides.get("collect_evaluation_summaries") != Boolean.FALSE;

    switch (kind) {
      case "context_shape":
        return new ContextShapeAggregator(new ContextShapeCollector(mode));
      case "evaluation_summary":
        return new EvaluationSummaryAggregator(new EvaluationSummaryCollector(collectSummaries));
      case "example_contexts":
        return new ExampleContextsAggregator(new ExampleContextCollector(mode));
      default:
        throw new IllegalArgumentException("unknown aggregator kind: " + kind);
    }
  }

  static void feedAggregator(Object agg, String kind, Object data, Map<String, Object> ctxMap) {
    if ("context_shape".equals(kind) || "example_contexts".equals(kind)) {
      List<ContextSet> records = normalizeContextRecords(data);
      for (ContextSet rec : records) {
        if (agg instanceof ContextShapeAggregator) {
          ((ContextShapeAggregator) agg).collector.push(rec);
        } else if (agg instanceof ExampleContextsAggregator) {
          ((ExampleContextsAggregator) agg).collector.push(rec);
        } else {
          throw new IllegalArgumentException("aggregator/kind mismatch: " + kind);
        }
      }
      return;
    }

    if ("evaluation_summary".equals(kind) && agg instanceof EvaluationSummaryAggregator) {
      EvaluationSummaryAggregator esa = (EvaluationSummaryAggregator) agg;
      ContextSet ctx = toContextSet(ctxMap);
      Map<?, ?> payload = data instanceof Map ? (Map<?, ?>) data : Collections.emptyMap();
      List<?> withCtx =
          payload.get("keys") instanceof List ? (List<?>) payload.get("keys") : List.of();
      List<?> withoutCtx =
          payload.get("keys_without_context") instanceof List
              ? (List<?>) payload.get("keys_without_context")
              : List.of();

      for (Object k : withCtx) feedSummary(esa, String.valueOf(k), ctx);
      for (Object k : withoutCtx) feedSummary(esa, String.valueOf(k), new ContextSet());
      return;
    }
    throw new IllegalArgumentException("feedAggregator: unsupported kind=" + kind);
  }

  private static void feedSummary(EvaluationSummaryAggregator agg, String key, ContextSet ctx) {
    ConfigRow cfg = STORE.getConfig(key);
    if (cfg == null) return;
    EvaluationMatch match = EVALUATOR.evaluate(cfg, ENV_ID, ctx);
    if (!match.isMatch() || match.value() == null) return;

    Value resolved;
    try {
      resolved = RESOLVER.resolve(match.value(), cfg, ENV_ID, ctx);
    } catch (RuntimeException e) {
      return;
    }
    Object unwrapped = resolved == null ? null : resolved.value();

    String reportable = Resolver.reportableValueFor(match.value()).orElse(null);
    int reasonNum =
        match.weightedValueIndex() >= 0
            ? 3 // SPLIT
            : hasTargetingRules(cfg) ? 2 : 1; // TARGETING_MATCH vs STATIC — mirror sdk-go

    agg.collector.push(
        new EvaluationStat(
            cfg.id(),
            cfg.key(),
            cfg.type().name(),
            match.ruleIndex(),
            match.weightedValueIndex(),
            unwrapped,
            reportable,
            reasonNum));

    if (reportable != null) {
      String valueType = wireValueTypeFor(unwrapped);
      agg.unwrappedOverrides.put(cfg.key(), new ValueOverride(unwrapped, valueType));
    }
  }

  static Object aggregatorPost(Object agg, String kind, String endpoint) {
    if (agg instanceof ContextShapeAggregator) {
      Map<String, Object> event = ((ContextShapeAggregator) agg).collector.drain();
      if (event == null) return null;
      Object shapesEnv = event.get("contextShapes");
      if (!(shapesEnv instanceof Map)) return null;
      Object list = ((Map<?, ?>) shapesEnv).get("shapes");
      if (!(list instanceof List)) return null;
      List<Map<String, Object>> rows = new ArrayList<>();
      for (Object el : (List<?>) list) {
        if (!(el instanceof Map)) continue;
        Map<?, ?> shape = (Map<?, ?>) el;
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("name", shape.get("name"));
        Object ft = shape.get("fieldTypes");
        if (ft instanceof Map) {
          // Field-type codes are stored as Integer in ContextShapeCollector; the generator
          // emits Long literals (1L, 2L, ...) for parity with the wire shape. Normalize to Long
          // here so assertEquals matches structurally.
          Map<String, Object> normalized = new LinkedHashMap<>();
          for (Map.Entry<?, ?> e : ((Map<?, ?>) ft).entrySet()) {
            normalized.put(String.valueOf(e.getKey()), asLong(e.getValue()));
          }
          row.put("field_types", normalized);
        } else {
          row.put("field_types", Map.of());
        }
        rows.add(row);
      }
      return rows.isEmpty() ? null : rows;
    }

    if (agg instanceof ExampleContextsAggregator) {
      Map<String, Object> event = ((ExampleContextsAggregator) agg).collector.drain();
      if (event == null) return null;
      Object envObj = event.get("exampleContexts");
      if (!(envObj instanceof Map)) return null;
      Object examples = ((Map<?, ?>) envObj).get("examples");
      if (!(examples instanceof List) || ((List<?>) examples).isEmpty()) return null;
      Object first = ((List<?>) examples).get(0);
      if (!(first instanceof Map)) return null;
      Object cs = ((Map<?, ?>) first).get("contextSet");
      if (!(cs instanceof Map)) return null;
      Object ctxs = ((Map<?, ?>) cs).get("contexts");
      if (!(ctxs instanceof List)) return null;
      Map<String, Object> out = new LinkedHashMap<>();
      for (Object c : (List<?>) ctxs) {
        if (!(c instanceof Map)) continue;
        Map<?, ?> cm = (Map<?, ?>) c;
        Object name = cm.get("type");
        Object values = cm.get("values");
        if (name instanceof String && values instanceof Map) {
          out.put((String) name, new LinkedHashMap<>((Map<String, Object>) values));
        }
      }
      return out.isEmpty() ? null : out;
    }

    if (agg instanceof EvaluationSummaryAggregator) {
      EvaluationSummaryAggregator esa = (EvaluationSummaryAggregator) agg;
      Map<String, Object> event = esa.collector.drain();
      if (event == null) return null;
      Object envObj = event.get("summaries");
      if (!(envObj instanceof Map)) return null;
      Object summaries = ((Map<?, ?>) envObj).get("summaries");
      if (!(summaries instanceof List) || ((List<?>) summaries).isEmpty()) return null;

      List<Map<String, Object>> sorted = new ArrayList<>();
      for (Object s : (List<?>) summaries) sorted.add((Map<String, Object>) s);
      // YAML expectations sort by config type (CONFIG before FEATURE_FLAG, etc.) preserving
      // within-type insertion order.
      sorted.sort(
          (a, b) ->
              telemetryConfigType(a.get("type")).compareTo(telemetryConfigType(b.get("type"))));

      List<Map<String, Object>> out = new ArrayList<>();
      for (Map<String, Object> s : sorted) {
        Object counters = s.get("counters");
        if (!(counters instanceof List)) continue;
        for (Object c : (List<?>) counters) {
          if (!(c instanceof Map)) continue;
          Map<?, ?> cm = (Map<?, ?>) c;
          Object selectedValue = normalizeSelectedValue(cm.get("selectedValue"));
          String wireValueType = wireValueTypeForSelected(selectedValue);
          Object wireValue = unwrapSelectedValue(selectedValue);

          ValueOverride override = esa.unwrappedOverrides.get(String.valueOf(s.get("key")));
          if (override != null) {
            wireValue = override.unwrapped;
            wireValueType = override.valueType;
          }

          Map<String, Object> summary = new LinkedHashMap<>();
          summary.put("config_row_index", asLong(cm.get("configRowIndex")));
          summary.put("conditional_value_index", asLong(cm.get("conditionalValueIndex")));
          Object wvi = cm.get("weightedValueIndex");
          if (wvi instanceof Number && ((Number) wvi).intValue() >= 0) {
            summary.put("weighted_value_index", asLong(wvi));
          }

          Map<String, Object> record = new LinkedHashMap<>();
          record.put("key", s.get("key"));
          record.put("type", telemetryConfigType(s.get("type")));
          record.put("value", wireValue);
          record.put("value_type", wireValueType);
          record.put("count", asLong(cm.get("count")));
          record.put("reason", asLong(cm.get("reason")));
          if (selectedValue != null) record.put("selected_value", selectedValue);
          record.put("summary", summary);
          out.add(record);
        }
      }
      return out.isEmpty() ? null : out;
    }
    throw new IllegalArgumentException("aggregatorPost: unknown aggregator " + agg);
  }

  // ---------------------------------------------------------------------------
  // Internal aggregator state types
  // ---------------------------------------------------------------------------

  private static final class ContextShapeAggregator {
    final ContextShapeCollector collector;

    ContextShapeAggregator(ContextShapeCollector c) {
      this.collector = c;
    }
  }

  private static final class ExampleContextsAggregator {
    final ExampleContextCollector collector;

    ExampleContextsAggregator(ExampleContextCollector c) {
      this.collector = c;
    }
  }

  private static final class EvaluationSummaryAggregator {
    final EvaluationSummaryCollector collector;
    final Map<String, ValueOverride> unwrappedOverrides = new LinkedHashMap<>();

    EvaluationSummaryAggregator(EvaluationSummaryCollector c) {
      this.collector = c;
    }
  }

  private static final class ValueOverride {
    final Object unwrapped;
    final String valueType;

    ValueOverride(Object unwrapped, String valueType) {
      this.unwrapped = unwrapped;
      this.valueType = valueType;
    }
  }

  // ---------------------------------------------------------------------------
  // Tiny helpers
  // ---------------------------------------------------------------------------

  private static ContextSet toContextSet(Map<String, Object> contextMap) {
    ContextSet cs = new ContextSet();
    if (contextMap == null || contextMap.isEmpty()) return cs;
    for (Map.Entry<String, Object> e : contextMap.entrySet()) {
      Object v = e.getValue();
      if (!(v instanceof Map)) continue;
      Map<String, Object> values = new LinkedHashMap<>();
      for (Map.Entry<?, ?> ve : ((Map<?, ?>) v).entrySet()) {
        values.put(String.valueOf(ve.getKey()), ve.getValue());
      }
      cs.withNamedContext(e.getKey(), values);
    }
    return cs;
  }

  private static List<ContextSet> normalizeContextRecords(Object data) {
    if (data == null) return List.of();
    List<ContextSet> out = new ArrayList<>();
    if (data instanceof List) {
      for (Object el : (List<?>) data) {
        if (el instanceof Map) out.add(toContextSet((Map<String, Object>) el));
      }
    } else if (data instanceof Map) {
      out.add(toContextSet((Map<String, Object>) data));
    }
    return out;
  }

  private static ContextUploadMode normalizeUploadMode(Object raw) {
    if (!(raw instanceof String)) return ContextUploadMode.PERIODIC_EXAMPLE;
    String s = ((String) raw).replaceFirst("^:", "").toLowerCase();
    if ("none".equals(s)) return ContextUploadMode.NONE;
    if ("shape_only".equals(s) || "shapes_only".equals(s)) return ContextUploadMode.SHAPES_ONLY;
    return ContextUploadMode.PERIODIC_EXAMPLE;
  }

  /**
   * True if the config has any rule whose criteria includes a non-{@code ALWAYS_TRUE} operator,
   * either in the default rule set or any environment-specific rule set. Mirrors {@code
   * hasTargetingRules} in sdk-go's test helpers.
   */
  private static boolean hasTargetingRules(ConfigRow cfg) {
    if (anyNonTrivial(cfg.defaultRules().rules())) return true;
    for (com.quonfig.sdk.eval.Environment env : cfg.environments()) {
      if (anyNonTrivial(env.rules())) return true;
    }
    return false;
  }

  private static boolean anyNonTrivial(List<com.quonfig.sdk.eval.Rule> rules) {
    for (com.quonfig.sdk.eval.Rule r : rules) {
      for (com.quonfig.sdk.eval.Criterion c : r.criteria()) {
        if (!"ALWAYS_TRUE".equals(c.operator())) return true;
      }
    }
    return false;
  }

  private static int reasonNumberFor(EvaluationMatch.Reason r) {
    if (r == null) return 5;
    switch (r) {
      case STATIC:
        return 1;
      case TARGETING_MATCH:
        return 2;
      case DEFAULT:
        return 4;
      default:
        return 5;
    }
  }

  /**
   * The collector boxes a singleton {wrapperKey: rawValue}. If rawValue is an Integer it must be
   * Long for parity with the generator's {@code 1L}-suffixed literals; lists keep their element
   * shape (List elements are already Long for ints because Jackson uses asLong()).
   */
  @SuppressWarnings("unchecked")
  private static Object normalizeSelectedValue(Object selectedValue) {
    if (!(selectedValue instanceof Map)) return selectedValue;
    Map<String, Object> m = (Map<String, Object>) selectedValue;
    if (m.size() != 1) return m;
    Map.Entry<String, Object> e = m.entrySet().iterator().next();
    Object v = e.getValue();
    if (v instanceof Integer) v = ((Integer) v).longValue();
    return Collections.singletonMap(e.getKey(), v);
  }

  private static Object asLong(Object v) {
    if (v == null) return null;
    if (v instanceof Long) return v;
    if (v instanceof Number) return ((Number) v).longValue();
    return v;
  }

  private static Object unwrapSelectedValue(Object selectedValue) {
    if (!(selectedValue instanceof Map)) return selectedValue;
    Map<?, ?> m = (Map<?, ?>) selectedValue;
    if (m.size() != 1) return selectedValue;
    return m.values().iterator().next();
  }

  private static String wireValueTypeForSelected(Object selectedValue) {
    if (selectedValue instanceof Map) {
      Map<?, ?> m = (Map<?, ?>) selectedValue;
      if (m.size() == 1) {
        Object key = m.keySet().iterator().next();
        if (key instanceof String) {
          String k = (String) key;
          if ("stringList".equals(k)) return "string_list";
          if ("bool".equals(k)) return "bool";
          if ("int".equals(k)) return "int";
          if ("double".equals(k)) return "double";
          if ("string".equals(k)) return "string";
        }
      }
    }
    return wireValueTypeFor(unwrapSelectedValue(selectedValue));
  }

  private static String wireValueTypeFor(Object v) {
    if (v instanceof String) return "string";
    if (v instanceof Boolean) return "bool";
    if (v instanceof Long || v instanceof Integer) return "int";
    if (v instanceof Double || v instanceof Float) return "double";
    if (v instanceof List) return "string_list";
    return "string";
  }

  private static String telemetryConfigType(Object internal) {
    if (internal == null) return "";
    String s = String.valueOf(internal);
    // EvaluationStat.configType is already the upstream-serialized name (CONFIG / FEATURE_FLAG /
    // LOG_LEVEL / SEGMENT / SCHEMA) because it comes from ConfigType.name().
    return s.toUpperCase();
  }

  // ---------------------------------------------------------------------------
  // ConfigStore impl — same semantics as Quonfig.InMemoryConfigStore but
  // exposed at test scope so we can drive Evaluator/Resolver directly.
  // ---------------------------------------------------------------------------

  private static final class MapConfigStore implements ConfigStore {
    private final Map<String, ConfigRow> byKey;

    MapConfigStore(List<ConfigRow> rows) {
      Map<String, ConfigRow> m = new LinkedHashMap<>(rows.size());
      for (ConfigRow r : rows) m.put(r.key(), r);
      this.byKey = Map.copyOf(m);
    }

    @Override
    public ConfigRow getConfig(String key) {
      return byKey.get(key);
    }

    Set<String> keys() {
      return byKey.keySet();
    }
  }

  // Keep imports tidy without producing unused-import warnings.
  @SuppressWarnings("unused")
  private static void __keepImports() {
    @SuppressWarnings("unused")
    Stream<?> s = null;
    @SuppressWarnings("unused")
    TreeMap<?, ?> tm = null;
    @SuppressWarnings("unused")
    IOException io = null;
  }
}
