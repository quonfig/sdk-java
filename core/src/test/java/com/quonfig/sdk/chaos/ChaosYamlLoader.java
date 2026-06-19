package com.quonfig.sdk.chaos;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.yaml.snakeyaml.Yaml;

/**
 * SnakeYAML-based parser for one chaos scenario file. We can't use SnakeYAML's
 * Constructor-with-typed-bean machinery because the YAML uses snake_case while the model uses
 * camelCase; instead we walk the raw {@code Map}/{@code List} structure and shovel fields onto
 * {@link ChaosScenario}. Kept dependency-light: SnakeYAML is the only test-only dep used here.
 */
final class ChaosYamlLoader {

  private ChaosYamlLoader() {}

  static ChaosScenario load(Path path) {
    Yaml yaml = new Yaml();
    Map<String, Object> root;
    try {
      root = yaml.load(Files.newBufferedReader(path));
    } catch (IOException e) {
      throw new RuntimeException("read scenario " + path + ": " + e.getMessage(), e);
    }
    ChaosScenario s = new ChaosScenario();
    s.function = asString(root.get("function"));
    s.tests = new ArrayList<>();
    Object tests = root.get("tests");
    if (tests instanceof List) {
      for (Object t : (List<?>) tests) {
        if (t instanceof Map) s.tests.add(parseRun((Map<String, Object>) t));
      }
    }
    return s;
  }

  private static ChaosScenario.Run parseRun(Map<String, Object> m) {
    ChaosScenario.Run run = new ChaosScenario.Run();
    run.name = asString(m.get("name"));
    run.description = asString(m.get("description"));
    run.setup = parseSetup(asMap(m.get("setup")));
    run.chaos = parseEvents(m.get("chaos"));
    run.expectations = parseExpectations(m.get("expectations"));
    return run;
  }

  private static ChaosScenario.Setup parseSetup(Map<String, Object> m) {
    ChaosScenario.Setup s = new ChaosScenario.Setup();
    if (m == null) return s;
    s.sdk = asString(m.get("sdk"));
    s.sseEndpoint = asString(m.get("sse_endpoint"));
    s.httpEndpoint = asString(m.get("http_endpoint"));
    s.wallClockSeconds = asInt(m.get("wall_clock_seconds"), 30);
    s.userCallback = asString(m.get("user_callback"));
    s.topology = asString(m.get("topology"));
    s.upstreams = parseUpstreams(m.get("upstreams"));
    return s;
  }

  private static List<ChaosScenario.Upstream> parseUpstreams(Object raw) {
    if (!(raw instanceof List)) return null;
    List<ChaosScenario.Upstream> out = new ArrayList<>();
    for (Object el : (List<?>) raw) {
      if (!(el instanceof Map)) continue;
      Map<String, Object> um = (Map<String, Object>) el;
      ChaosScenario.Upstream u = new ChaosScenario.Upstream();
      u.role = asString(um.get("role"));
      u.generation = asInt(um.get("generation"), 0);
      out.add(u);
    }
    return out;
  }

  private static List<ChaosScenario.Event> parseEvents(Object raw) {
    List<ChaosScenario.Event> out = new ArrayList<>();
    if (!(raw instanceof List)) return out;
    for (Object el : (List<?>) raw) {
      if (!(el instanceof Map)) continue;
      Map<String, Object> em = (Map<String, Object>) el;
      ChaosScenario.Event ev = new ChaosScenario.Event();
      ev.atMs = asInt(em.get("at_ms"), 0);
      if (em.get("inject") instanceof Map)
        ev.inject = parseInject((Map<String, Object>) em.get("inject"));
      if (em.get("clear") instanceof String) ev.clear = (String) em.get("clear");
      if (em.get("process") instanceof Map)
        ev.process = parseProcess((Map<String, Object>) em.get("process"));
      out.add(ev);
    }
    return out;
  }

  private static ChaosScenario.Inject parseInject(Map<String, Object> m) {
    ChaosScenario.Inject inj = new ChaosScenario.Inject();
    inj.name = asString(m.get("name"));
    inj.sseSilentStallAfterMs = asIntegerOrNull(m.get("sse_silent_stall_after_ms"));
    inj.sseLatencyMs = asIntegerOrNull(m.get("sse_latency_ms"));
    inj.sseBandwidthKbps = asIntegerOrNull(m.get("sse_bandwidth_kbps"));
    inj.sseDownMs = asIntegerOrNull(m.get("sse_down_ms"));
    inj.bothDownMs = asIntegerOrNull(m.get("both_down_ms"));
    inj.sseHalfOpenAfterBytes = asIntegerOrNull(m.get("sse_half_open_after_bytes"));
    inj.sseHttpStatus = asIntegerOrNull(m.get("sse_http_status"));
    inj.primaryRefusedMs = asIntegerOrNull(m.get("primary_refused_ms"));
    inj.primaryHangMs = asIntegerOrNull(m.get("primary_hang_ms"));
    inj.primaryLatencyMs = asIntegerOrNull(m.get("primary_latency_ms"));
    inj.proxy = asString(m.get("proxy"));
    if (m.get("toxic") instanceof Map)
      inj.toxic = new LinkedHashMap<>((Map<String, Object>) m.get("toxic"));
    return inj;
  }

  private static ChaosScenario.Process parseProcess(Map<String, Object> m) {
    ChaosScenario.Process p = new ChaosScenario.Process();
    p.action = asString(m.get("action"));
    p.count = asInt(m.get("count"), 0);
    p.intervalMs = asInt(m.get("interval_ms"), 0);
    return p;
  }

  private static List<ChaosScenario.Expectation> parseExpectations(Object raw) {
    List<ChaosScenario.Expectation> out = new ArrayList<>();
    if (!(raw instanceof List)) return out;
    for (Object el : (List<?>) raw) {
      if (!(el instanceof Map)) continue;
      Map<String, Object> em = (Map<String, Object>) el;
      ChaosScenario.Expectation e = new ChaosScenario.Expectation();
      e.withinMs = asInt(em.get("within_ms"), 0);
      e.mustHoldForMs = asInt(em.get("must_hold_for_ms"), 0);
      e.assertExpr = asString(em.get("assert"));
      out.add(e);
    }
    return out;
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> asMap(Object o) {
    return o instanceof Map ? (Map<String, Object>) o : null;
  }

  private static String asString(Object o) {
    return o == null ? null : String.valueOf(o);
  }

  private static int asInt(Object o, int def) {
    if (o instanceof Number) return ((Number) o).intValue();
    if (o instanceof String) {
      try {
        return Integer.parseInt((String) o);
      } catch (NumberFormatException ignored) {
        return def;
      }
    }
    return def;
  }

  private static Integer asIntegerOrNull(Object o) {
    if (o == null) return null;
    if (o instanceof Number) return ((Number) o).intValue();
    if (o instanceof String) {
      try {
        return Integer.parseInt((String) o);
      } catch (NumberFormatException ignored) {
        return null;
      }
    }
    return null;
  }
}
