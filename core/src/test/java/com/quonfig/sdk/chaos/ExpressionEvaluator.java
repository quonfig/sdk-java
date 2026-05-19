package com.quonfig.sdk.chaos;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Tiny evaluator for the chaos-scenario assertion expressions. Supports the same vocabulary as
 * sdk-go's runner — see {@code integration-test-data/chaos/supervisor-test-contract.md} for the
 * full grammar. Returns {@code (passed, reason)} pairs so debug logging shows *why* an assertion
 * failed without needing a real parser.
 */
final class ExpressionEvaluator {

  static final class Result {
    final boolean passed;
    final String reason;

    Result(boolean passed, String reason) {
      this.passed = passed;
      this.reason = reason;
    }
  }

  private static final Pattern RE_CONN_STATE =
      Pattern.compile("^client\\.connectionState\\(\\)\\s*(==|!=)\\s*'([^']+)'$");
  private static final Pattern RE_FALLBACK =
      Pattern.compile("^client\\.fallbackPollerActive\\(\\)\\s*==\\s*(true|false)$");
  private static final Pattern RE_PROC_ALIVE =
      Pattern.compile("^client\\.processStillAlive\\(\\)\\s*==\\s*(true|false)$");
  private static final Pattern RE_LAST_REFRESH =
      Pattern.compile(
          "^client\\.lastSuccessfulRefresh\\(\\)\\s*(>=|>|<=|<|==)\\s*\\(now\\(\\)\\s*-\\s*(\\d+)\\)$");
  private static final Pattern RE_SDK_METRIC =
      Pattern.compile(
          "^client\\.sdkMetric\\(\\s*'([^']+)'\\s*(?:,\\s*layer=\\s*'([^']+)'\\s*)?\\)\\s*(>=|<=|==|!=|<|>)\\s*(\\d+)$");
  private static final Pattern RE_SERVER_METRIC =
      Pattern.compile("^server_metric\\(\\s*'([^']+)'\\s*\\)\\s*(>=|<=|==|!=|<|>)\\s*(\\d+)$");
  private static final Pattern RE_SDK_LOG =
      Pattern.compile(
          "^client\\.sdkLog\\(\\s*'([^']+)'\\s*,\\s*/(.+)/i\\s*\\)\\s*(>=|<=|==|!=|<|>)\\s*(\\d+)$");

  private final ChaosProbe probe;

  ExpressionEvaluator(ChaosProbe probe) {
    this.probe = probe;
  }

  Result evaluate(String expr) {
    String e = expr == null ? "" : expr.trim();
    if (e.isEmpty()) return new Result(true, "");
    if (e.contains(" OR ")) {
      List<String> parts = splitOutsideQuotesAndRegex(e, " OR ");
      List<String> reasons = new ArrayList<>();
      for (String p : parts) {
        Result r = evaluate(p);
        if (r.passed) return new Result(true, "");
        reasons.add(r.reason);
      }
      return new Result(false, "OR: " + String.join(" | ", reasons));
    }
    if (e.contains(" AND ")) {
      for (String p : splitOutsideQuotesAndRegex(e, " AND ")) {
        Result r = evaluate(p);
        if (!r.passed) return new Result(false, "AND: " + r.reason);
      }
      return new Result(true, "");
    }
    return leaf(e);
  }

  private Result leaf(String expr) {
    Matcher m;
    if ((m = RE_CONN_STATE.matcher(expr)).matches()) {
      String got = probe.connectionState();
      String want = m.group(2);
      boolean ok = "==".equals(m.group(1)) ? got.equals(want) : !got.equals(want);
      return new Result(ok, "connectionState=" + got + " " + m.group(1) + " " + want);
    }
    if ((m = RE_FALLBACK.matcher(expr)).matches()) {
      boolean want = Boolean.parseBoolean(m.group(1));
      boolean got = probe.fallbackPollerActive();
      return new Result(got == want, "fallbackPollerActive=" + got + " want " + want);
    }
    if ((m = RE_PROC_ALIVE.matcher(expr)).matches()) {
      boolean want = Boolean.parseBoolean(m.group(1));
      boolean got = probe.processStillAlive();
      return new Result(got == want, "processStillAlive=" + got + " want " + want);
    }
    if ((m = RE_LAST_REFRESH.matcher(expr)).matches()) {
      long ago = Long.parseLong(m.group(2));
      long last = probe.lastSuccessfulRefreshMs();
      long threshold = System.currentTimeMillis() - ago;
      boolean ok = compareLong(m.group(1), last, threshold);
      return new Result(ok, "lastSuccessfulRefresh=" + last + " " + m.group(1) + " " + threshold);
    }
    if ((m = RE_SDK_METRIC.matcher(expr)).matches()) {
      String metric = m.group(1);
      String layer = m.group(2);
      double got = probe.sdkMetric(metric, layer);
      double want = Double.parseDouble(m.group(4));
      boolean ok = compareDouble(m.group(3), got, want);
      return new Result(
          ok,
          "sdkMetric(" + metric + ",layer=" + layer + ")=" + got + " " + m.group(3) + " " + want);
    }
    if ((m = RE_SERVER_METRIC.matcher(expr)).matches()) {
      // Server-side metrics aren't exposed to the SDK; stub to 0.
      double got = 0;
      double want = Double.parseDouble(m.group(3));
      boolean ok = compareDouble(m.group(2), got, want);
      return new Result(ok, "server_metric(" + m.group(1) + ")=0 " + m.group(2) + " " + want);
    }
    if ((m = RE_SDK_LOG.matcher(expr)).matches()) {
      String level = m.group(1);
      Pattern re = Pattern.compile(m.group(2), Pattern.CASE_INSENSITIVE);
      int n = probe.sdkLogMatches(level, re);
      int want = Integer.parseInt(m.group(4));
      boolean ok = compareLong(m.group(3), n, want);
      return new Result(
          ok, "sdkLog(" + level + ",/" + m.group(2) + "/i)=" + n + " " + m.group(3) + " " + want);
    }
    return new Result(false, "unrecognized expression: " + expr);
  }

  private static boolean compareLong(String op, long a, long b) {
    switch (op) {
      case "==":
        return a == b;
      case "!=":
        return a != b;
      case "<":
        return a < b;
      case "<=":
        return a <= b;
      case ">":
        return a > b;
      case ">=":
        return a >= b;
      default:
        return false;
    }
  }

  private static boolean compareDouble(String op, double a, double b) {
    switch (op) {
      case "==":
        return a == b;
      case "!=":
        return a != b;
      case "<":
        return a < b;
      case "<=":
        return a <= b;
      case ">":
        return a > b;
      case ">=":
        return a >= b;
      default:
        return false;
    }
  }

  // Split expr on sep but ignore occurrences inside single-quoted strings or /regex/i literals.
  static List<String> splitOutsideQuotesAndRegex(String expr, String sep) {
    List<String> out = new ArrayList<>();
    boolean inSq = false;
    boolean inRe = false;
    int start = 0;
    int i = 0;
    while (i < expr.length()) {
      char c = expr.charAt(i);
      if (c == '\'' && !inRe) inSq = !inSq;
      else if (c == '/' && !inSq) inRe = !inRe;
      if (!inSq && !inRe && i + sep.length() <= expr.length() && expr.startsWith(sep, i)) {
        out.add(expr.substring(start, i));
        start = i + sep.length();
        i = start;
        continue;
      }
      i++;
    }
    out.add(expr.substring(start));
    return out;
  }
}
