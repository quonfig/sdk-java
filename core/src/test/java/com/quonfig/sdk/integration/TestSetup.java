package com.quonfig.sdk.integration;

import com.quonfig.sdk.Options;
import com.quonfig.sdk.Quonfig;
import com.quonfig.sdk.eval.ContextSet;
import com.quonfig.sdk.eval.Resolver;
import com.quonfig.sdk.telemetry.ContextUploadMode;
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
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Harness for the auto-generated {@code *Test.java} files under {@code com.quonfig.sdk.integration}
 * (generator: integration-test-data/generators/src/targets/java.ts).
 *
 * <p>PUBLIC API ONLY (qfg-2agi.30). The generated tests call {@link Quonfig} / {@link
 * com.quonfig.sdk.BoundQuonfig} getters themselves, the way a customer does. This class only:
 *
 * <ul>
 *   <li>builds clients through the public {@link Options} builder (datadir fixture, SDK-key http
 *       client, telemetry client with a capturing {@link
 *       com.quonfig.sdk.telemetry.TelemetrySender});
 *   <li>turns generator literals into {@link ContextSet}s and installs per-test env vars through
 *       {@link Options.Builder#envLookup};
 *   <li>projects the telemetry payload the real client hands its sender onto the YAML's {@code
 *       expected_data} shape.
 * </ul>
 *
 * <p>It never evaluates, resolves, merges contexts or throws on the SDK's behalf.
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

  /** The env lookup every harness client is built with (public {@code Options.envLookup}). */
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

  static {
    BASE_ENV.put("PREFAB_INTEGRATION_TEST_ENCRYPTION_KEY", ENCRYPTION_KEY);
    BASE_ENV.put("IS_A_NUMBER", "1234");
    BASE_ENV.put("NOT_A_NUMBER", "not_a_number");

    DATADIR = locateDatadir();
    if (!Files.isDirectory(Paths.get(DATADIR))) {
      throw new IllegalStateException(
          "[integration tests] fixtures not found at "
              + DATADIR
              + " — populate integration-test-data");
    }
  }

  private TestSetup() {}

  private static String locateDatadir() {
    // sdk-java is a sibling of integration-test-data. The Gradle test working directory is the
    // sdk-java root (or core/), so try both.
    String userDir = System.getProperty("user.dir");
    Path candidate =
        Paths.get(userDir, "..", "integration-test-data", "data", "integration-tests").normalize();
    if (Files.isDirectory(candidate)) return candidate.toString();
    Path alt =
        Paths.get(userDir, "..", "..", "integration-test-data", "data", "integration-tests")
            .normalize();
    if (Files.isDirectory(alt)) return alt.toString();
    return Paths.get(userDir, "integration-test-data", "data", "integration-tests")
        .normalize()
        .toString();
  }

  // ---------------------------------------------------------------------------
  // Literal helpers — called from generator output
  // ---------------------------------------------------------------------------

  static List<Object> list(Object... items) {
    List<Object> out = new ArrayList<>(items.length);
    Collections.addAll(out, items);
    return out;
  }

  /** Ordered Map from alternating key/value pairs. */
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

  /** A public {@link ContextSet} from a {@code {contextName: {property: value}}} literal. */
  @SuppressWarnings("unchecked")
  static ContextSet ctx(Map<String, Object> contexts) {
    ContextSet cs = new ContextSet();
    for (Map.Entry<String, Object> e : contexts.entrySet()) {
      if (!(e.getValue() instanceof Map)) {
        throw new IllegalArgumentException("context " + e.getKey() + " must be a map");
      }
      cs.withNamedContext(e.getKey(), (Map<String, Object>) e.getValue());
    }
    return cs;
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
    } catch (RuntimeException | Error re) {
      throw re;
    } catch (Exception ex) {
      throw new RuntimeException(ex);
    } finally {
      ENV_OVERRIDES.set(previous);
    }
  }

  // ---------------------------------------------------------------------------
  // Clients (public Options builder only)
  // ---------------------------------------------------------------------------

  /** Datadir fixture client settings shared by every harness client. */
  private static Options.Builder fixtureOptions() {
    return Options.builder()
        .datadir(DATADIR)
        .environment(ENV_ID)
        .envLookup(TEST_ENV_LOOKUP)
        .enableQuonfigUserContext(false)
        .dataDirAutoReload(false);
  }

  private static volatile Quonfig sharedClient;

  /** Shared fixture client (no global context), built once per JVM. Do not close. */
  static Quonfig client() {
    Quonfig c = sharedClient;
    if (c == null) {
      synchronized (TestSetup.class) {
        c = sharedClient;
        if (c == null) {
          c = new Quonfig(fixtureOptions().disableTelemetry(true).build());
          sharedClient = c;
        }
      }
    }
    return c;
  }

  /** A fresh fixture client with {@code Options.globalContext(global)}. Caller closes it. */
  static Quonfig newClient(ContextSet global) {
    return new Quonfig(fixtureOptions().globalContext(global).disableTelemetry(true).build());
  }

  /**
   * A client per a datadir case's {@code client_overrides} ({@code datadir}, {@code environment}).
   * Built inside {@link #withEnv} so the SDK itself reads {@code QUONFIG_ENVIRONMENT} through the
   * env lookup. Caller closes it.
   */
  static Quonfig datadirClient(Map<String, Object> opts) {
    Options.Builder b =
        Options.builder()
            .envLookup(TEST_ENV_LOOKUP)
            .enableQuonfigUserContext(false)
            .dataDirAutoReload(false)
            .disableTelemetry(true);
    Object datadir = opts.get("datadir");
    Object environment = opts.get("environment");
    if (datadir != null) b.datadir(datadir.toString());
    if (environment != null) b.environment(environment.toString());
    return new Quonfig(b.build());
  }

  /**
   * An SDK-key client pointed at {@code apiUrl} with {@code initTimeout = timeoutSec} (the YAML's
   * {@code prefab_api_url} / {@code initialization_timeout_sec}). Caller closes it.
   */
  static Quonfig httpClient(String apiUrl, double timeoutSec) {
    return new Quonfig(
        Options.builder()
            .sdkKey("itd-init-timeout")
            .apiUrls(List.of(apiUrl))
            .streamUrls(List.of(apiUrl))
            .initTimeout(Duration.ofMillis(Math.max(1L, Math.round(timeoutSec * 1000))))
            .fallbackPollEnabled(false)
            .envLookup(TEST_ENV_LOOKUP)
            .enableQuonfigUserContext(false)
            .disableTelemetry(true)
            .build());
  }

  // ---------------------------------------------------------------------------
  // Telemetry: a real client + capturing sender, projected onto expected_data
  // ---------------------------------------------------------------------------

  static TelemetryClient telemetryClient(Map<String, Object> overrides, ContextSet global) {
    return new TelemetryClient(overrides, global);
  }

  /**
   * A fixture client whose telemetry goes to an in-memory sender. Ticks are pushed out of reach;
   * the projections call the public {@link Quonfig#flush()} and read what the SDK sent.
   */
  static final class TelemetryClient implements AutoCloseable {
    private final Quonfig client;
    private final List<Map<String, Object>> sent = new CopyOnWriteArrayList<>();
    private final Map<String, Object> seen = new LinkedHashMap<>();
    private List<Map<String, Object>> events;

    TelemetryClient(Map<String, Object> overrides, ContextSet global) {
      Options.Builder b =
          fixtureOptions()
              .telemetrySender(sent::add)
              .telemetryInitialDelay(Duration.ofHours(1))
              .telemetryFlushInterval(Duration.ofHours(1))
              .contextUploadMode(uploadMode(overrides.get("context_upload_mode")))
              .collectEvaluationSummaries(
                  !Boolean.FALSE.equals(overrides.get("collect_evaluation_summaries")));
      if (global != null) b.globalContext(global);
      this.client = new Quonfig(b.build());
    }

    Quonfig client() {
      return client;
    }

    /** Records the value a public getter returned for {@code key} (see evaluationSummaries). */
    void saw(String key, Object value) {
      seen.put(key, value);
    }

    @Override
    public void close() {
      client.close();
    }

    /** Every event the SDK sent, after one public {@link Quonfig#flush()}. */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> events() {
      if (events == null) {
        client.flush();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> payload : sent) {
          Object ev = payload.get("events");
          if (ev instanceof List) {
            for (Object e : (List<?>) ev) {
              if (e instanceof Map) out.add((Map<String, Object>) e);
            }
          }
        }
        events = out;
      }
      return events;
    }

    private List<Object> eventBodies(String eventName, String listField) {
      List<Object> out = new ArrayList<>();
      for (Map<String, Object> e : events()) {
        Object body = e.get(eventName);
        if (body instanceof Map) {
          Object list = ((Map<?, ?>) body).get(listField);
          if (list instanceof List) out.addAll((List<?>) list);
        }
      }
      return out;
    }

    /** {@code contextShapes} events as {@code [{name, field_types}]}, or null when none. */
    Object contextShapes() {
      List<Map<String, Object>> rows = new ArrayList<>();
      for (Object el : eventBodies("contextShapes", "shapes")) {
        if (!(el instanceof Map)) continue;
        Map<?, ?> shape = (Map<?, ?>) el;
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("name", shape.get("name"));
        Object ft = shape.get("fieldTypes");
        row.put("field_types", ft instanceof Map ? normalize(ft) : Map.of());
        rows.add(row);
      }
      return rows.isEmpty() ? null : rows;
    }

    /** The first {@code exampleContexts} example as {@code {name: values}}, or null. */
    Object exampleContexts() {
      List<Object> examples = eventBodies("exampleContexts", "examples");
      if (examples.isEmpty() || !(examples.get(0) instanceof Map)) return null;
      Object cs = ((Map<?, ?>) examples.get(0)).get("contextSet");
      if (!(cs instanceof Map)) return null;
      Object ctxs = ((Map<?, ?>) cs).get("contexts");
      if (!(ctxs instanceof List)) return null;
      Map<String, Object> out = new LinkedHashMap<>();
      for (Object c : (List<?>) ctxs) {
        if (!(c instanceof Map)) continue;
        Map<?, ?> cm = (Map<?, ?>) c;
        if (cm.get("type") instanceof String && cm.get("values") instanceof Map) {
          out.put((String) cm.get("type"), normalize(cm.get("values")));
        }
      }
      return out.isEmpty() ? null : out;
    }

    /**
     * {@code summaries} events flattened to one record per counter, ordered by config type (CONFIG
     * before FEATURE_FLAG, insertion order within a type) as the YAML lists them. {@code value} /
     * {@code value_type} come from the counter's selectedValue; for a redacted counter
     * (selectedValue {@code *****xxxxx}) they come from the value the public getter returned.
     */
    Object evaluationSummaries() {
      List<Map<String, Object>> summaries = new ArrayList<>();
      for (Object s : eventBodies("summaries", "summaries")) {
        if (s instanceof Map) summaries.add(castMap(s));
      }
      summaries.sort(
          (a, b) -> String.valueOf(a.get("type")).compareTo(String.valueOf(b.get("type"))));

      List<Map<String, Object>> out = new ArrayList<>();
      for (Map<String, Object> s : summaries) {
        Object counters = s.get("counters");
        if (!(counters instanceof List)) continue;
        for (Object c : (List<?>) counters) {
          if (!(c instanceof Map)) continue;
          Map<?, ?> cm = (Map<?, ?>) c;
          Object selected = normalize(cm.get("selectedValue"));
          Object value = unwrap(selected);
          String valueType = wireValueType(selected);
          if (value instanceof String && ((String) value).startsWith("*****")) {
            Object got = seen.get(String.valueOf(s.get("key")));
            value = normalize(got);
            valueType = valueTypeOf(got);
          }

          Map<String, Object> summary = new LinkedHashMap<>();
          summary.put("config_row_index", normalize(cm.get("configRowIndex")));
          summary.put("conditional_value_index", normalize(cm.get("conditionalValueIndex")));
          if (cm.get("weightedValueIndex") instanceof Number) {
            summary.put("weighted_value_index", normalize(cm.get("weightedValueIndex")));
          }

          Map<String, Object> record = new LinkedHashMap<>();
          record.put("key", s.get("key"));
          record.put("type", s.get("type"));
          record.put("value", value);
          record.put("value_type", valueType);
          record.put("count", normalize(cm.get("count")));
          record.put("reason", normalize(cm.get("reason")));
          if (selected != null) record.put("selected_value", selected);
          record.put("summary", summary);
          out.add(record);
        }
      }
      return out.isEmpty() ? null : out;
    }
  }

  private static ContextUploadMode uploadMode(Object raw) {
    if (!(raw instanceof String)) return ContextUploadMode.PERIODIC_EXAMPLE;
    String s = ((String) raw).replaceFirst("^:", "").toLowerCase();
    if ("none".equals(s)) return ContextUploadMode.NONE;
    if ("shape_only".equals(s) || "shapes_only".equals(s)) return ContextUploadMode.SHAPES_ONLY;
    return ContextUploadMode.PERIODIC_EXAMPLE;
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> castMap(Object o) {
    return (Map<String, Object>) o;
  }

  /**
   * JSON round-trip numbers come back as Integer; the generator emits Long literals. Recursively
   * widen Integer to Long so assertEquals compares structurally.
   */
  private static Object normalize(Object v) {
    if (v instanceof Integer || v instanceof Short || v instanceof Byte) {
      return ((Number) v).longValue();
    }
    if (v instanceof Map) {
      Map<String, Object> out = new LinkedHashMap<>();
      for (Map.Entry<?, ?> e : ((Map<?, ?>) v).entrySet()) {
        out.put(String.valueOf(e.getKey()), normalize(e.getValue()));
      }
      return out;
    }
    if (v instanceof List) {
      List<Object> out = new ArrayList<>();
      for (Object el : (List<?>) v) out.add(normalize(el));
      return out;
    }
    return v;
  }

  private static Object unwrap(Object selected) {
    if (selected instanceof Map && ((Map<?, ?>) selected).size() == 1) {
      return ((Map<?, ?>) selected).values().iterator().next();
    }
    return selected;
  }

  private static String wireValueType(Object selected) {
    if (selected instanceof Map && ((Map<?, ?>) selected).size() == 1) {
      Object k = ((Map<?, ?>) selected).keySet().iterator().next();
      if ("stringList".equals(k)) return "string_list";
      if (k instanceof String) return (String) k;
    }
    return valueTypeOf(unwrap(selected));
  }

  private static String valueTypeOf(Object v) {
    if (v instanceof Boolean) return "bool";
    if (v instanceof Long || v instanceof Integer) return "int";
    if (v instanceof Double || v instanceof Float) return "double";
    if (v instanceof List) return "string_list";
    return "string";
  }
}
