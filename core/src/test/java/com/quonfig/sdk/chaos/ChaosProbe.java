package com.quonfig.sdk.chaos;

import com.quonfig.sdk.ChaosSdkSeams;
import com.quonfig.sdk.ConnectionState;
import com.quonfig.sdk.Quonfig;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

/**
 * Test-only probe over an sdk-java {@code Quonfig} client. It keeps NO model of its own
 * (qfg-6vno.3): every value a chaos expression asserts on is read from the SDK, through {@link
 * SdkView}, whose production binding ({@link #bindClient}) reads the client's public getters
 * ({@code connectionState()}, {@code lastSuccessfulRefresh()}, {@code ready()}, {@code
 * getString()}) and the package-private SDK test seams behind {@link ChaosSdkSeams} (worker
 * restarts, SSE dial attempts, fallback-poller state). The only things the probe records itself are
 * what the SDK hands it: log lines (its logger hook), ready events (its {@code onConfigUpdate}
 * hook) and uncaught thread deaths (the JVM's uncaught-exception hook), plus the runner's own
 * init-call outcome.
 *
 * <p>Until a client is bound (the constructor has not returned), {@link #hasClient()} is false and
 * the evaluator fails every client-backed leaf rather than answering for the SDK.
 */
final class ChaosProbe implements AutoCloseable {

  /**
   * What the probe reads from the SDK. Tests may inject a fake; {@link #bindClient} binds a real
   * client.
   */
  interface SdkView {
    ConnectionState connectionState();

    boolean fallbackPollerActive();

    long workerRestartTotal(String layer);

    long callbackErrorsTotal(String callback);

    long sseConnectAttempts();

    Instant lastSuccessfulRefresh();

    boolean ready();

    String getString(String key, String def);
  }

  private volatile SdkView view;
  private final Object lock = new Object();
  private final List<LogLine> logs = new ArrayList<>();
  private final AtomicInteger readyEvents = new AtomicInteger();
  // Runner-owned: the outcome of the init call in init_failure scenarios (00-*); null otherwise.
  private volatile String initState;

  // processStillAlive (qfg-6vno.3): a JVM does not exit when a background thread dies, so "the
  // process survived" means "no thread died from an uncaught exception while this probe watched".
  // The default handler sees every thread without its own handler, SDK threads included (the SDK
  // installs none). Harness threads ("chaos-*") are excluded: their failures are recorded by the
  // runner as scenario failures instead.
  private final AtomicBoolean processCrashed = new AtomicBoolean(false);
  private volatile String crashDetail;
  private final Thread.UncaughtExceptionHandler previousHandler;
  private final Thread.UncaughtExceptionHandler handler;

  ChaosProbe() {
    previousHandler = Thread.getDefaultUncaughtExceptionHandler();
    handler =
        (t, e) -> {
          if (!t.getName().startsWith("chaos-")) {
            crashDetail = t.getName() + ": " + e;
            processCrashed.set(true);
            System.err.println("chaos probe: thread " + crashDetail + " died uncaught");
          }
          if (previousHandler != null) previousHandler.uncaughtException(t, e);
        };
    Thread.setDefaultUncaughtExceptionHandler(handler);
  }

  /** Restores the JVM's previous default uncaught-exception handler. Idempotent. */
  @Override
  public void close() {
    if (Thread.getDefaultUncaughtExceptionHandler() == handler) {
      Thread.setDefaultUncaughtExceptionHandler(previousHandler);
    }
  }

  /** Reads every SDK-sourced value from {@code client} from now on. */
  void bindClient(Quonfig client) {
    bindView(
        new SdkView() {
          @Override
          public ConnectionState connectionState() {
            return client.connectionState();
          }

          @Override
          public boolean fallbackPollerActive() {
            return ChaosSdkSeams.fallbackPollerActive(client);
          }

          @Override
          public long workerRestartTotal(String layer) {
            return ChaosSdkSeams.workerRestartTotal(client, layer);
          }

          @Override
          public long callbackErrorsTotal(String callback) {
            return ChaosSdkSeams.callbackErrorsTotal(client, callback);
          }

          @Override
          public long sseConnectAttempts() {
            return ChaosSdkSeams.sseConnectAttempts(client);
          }

          @Override
          public Instant lastSuccessfulRefresh() {
            return client.lastSuccessfulRefresh();
          }

          @Override
          public boolean ready() {
            return client.ready();
          }

          @Override
          public String getString(String key, String def) {
            return client.getString(key, def);
          }
        });
  }

  /** Test seam: binds an arbitrary view (unit tests of the evaluator). */
  void bindView(SdkView v) {
    this.view = v;
  }

  boolean hasClient() {
    return view != null;
  }

  private SdkView view() {
    SdkView v = view;
    if (v == null) throw new IllegalStateException("no client object bound to the probe");
    return v;
  }

  /** The SDK's {@code connectionState()} in the contract's spelling. */
  String connectionState() {
    switch (view().connectionState()) {
      case INITIALIZING:
        return "initializing";
      case CONNECTED:
        return "connected";
      case DISCONNECTED:
        return "disconnected";
      case FALLING_BACK:
        return "falling_back";
      default:
        throw new IllegalStateException("unmapped ConnectionState " + view().connectionState());
    }
  }

  boolean fallbackPollerActive() {
    return view().fallbackPollerActive();
  }

  /** Wall-clock millis of the client's last successful refresh; 0 == never. */
  long lastSuccessfulRefreshMs() {
    Instant at = view().lastSuccessfulRefresh();
    return at == null ? 0 : at.toEpochMilli();
  }

  boolean ready() {
    return view().ready();
  }

  String getString(String key, String def) {
    return view().getString(key, def);
  }

  boolean processStillAlive() {
    return !processCrashed.get();
  }

  String crashDetail() {
    return crashDetail;
  }

  double sdkMetric(String name, String layer) {
    return sdkMetric(name, layer == null ? null : "layer", layer);
  }

  /**
   * {@code name} filtered by one label ({@code labelKey}={@code labelValue}), or summed over every
   * value when {@code labelKey} is null. A label the metric does not carry fails loudly.
   */
  double sdkMetric(String name, String labelKey, String labelValue) {
    boolean unlabeled = labelKey == null || labelValue == null || labelValue.isEmpty();
    switch (name) {
      case "quonfig_sdk_worker_restart_total":
        if (unlabeled) {
          return view().workerRestartTotal("1") + view().workerRestartTotal("2");
        }
        requireLabel(name, "layer", labelKey);
        return view().workerRestartTotal(labelValue);
      case "quonfig_sdk_callback_errors_total":
        if (unlabeled) {
          return view().callbackErrorsTotal("on_config_update")
              + view().callbackErrorsTotal("on_connection_state_change");
        }
        requireLabel(name, "callback", labelKey);
        return view().callbackErrorsTotal(labelValue);
      case "quonfig_sse_connect_attempts_total":
        if (!unlabeled) {
          throw new IllegalArgumentException(name + " carries no label (got " + labelKey + ")");
        }
        return view().sseConnectAttempts();
      default:
        // Fail loudly (qfg-goi1.1.6): a silent 0 would make `== 0` / `< N` expectations on a
        // metric this probe does not observe pass vacuously.
        throw new IllegalArgumentException(
            "unknown sdkMetric name '"
                + name
                + "': ChaosProbe observes only quonfig_sdk_worker_restart_total,"
                + " quonfig_sdk_callback_errors_total and quonfig_sse_connect_attempts_total");
    }
  }

  private static void requireLabel(String name, String want, String got) {
    if (!want.equals(got)) {
      throw new IllegalArgumentException(name + " is labeled by " + want + ", not " + got);
    }
  }

  // ---- init_failure scenarios (00-*) ----

  /** Runner-owned: 'pending' | 'returned' | 'raised' for the init call; null outside 00-*. */
  void setInitState(String s) {
    this.initState = s;
  }

  String initState() {
    return initState;
  }

  /** Hook for the SDK's onConfigUpdate callback, registered once the init call has returned. */
  void onReadyEvent() {
    readyEvents.incrementAndGet();
  }

  int readyEventCount() {
    return readyEvents.get();
  }

  // ---- SDK log hook ----

  void log(String level, String message) {
    synchronized (lock) {
      logs.add(new LogLine(level, message));
    }
  }

  /** Lines at exactly {@code level} (case-insensitive level name) matching {@code re}. */
  int sdkLogMatches(String level, Pattern re) {
    synchronized (lock) {
      int n = 0;
      for (LogLine l : logs) {
        if (level != null && !level.isEmpty() && !level.equalsIgnoreCase(l.level)) continue;
        if (re == null || re.matcher(l.message).find()) n++;
      }
      return n;
    }
  }

  private static final class LogLine {
    final String level;
    final String message;

    LogLine(String level, String message) {
      this.level = level;
      this.message = message;
    }
  }
}
