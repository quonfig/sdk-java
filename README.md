# sdk-java

Java SDK for [Quonfig](https://quonfig.com) — feature flags and configuration as files in git.

## Artifacts

This repo publishes four artifacts in lock-step from a single tag.

| Artifact | Purpose |
|----------|---------|
| `com.quonfig:sdk-java` | Core SDK — config evaluation, HTTP+SSE transport, datadir loader, telemetry. |
| `com.quonfig:sdk-java-logback` | Drop-in Logback `TurboFilter` that pulls log levels from Quonfig. |
| `com.quonfig:sdk-java-log4j2` | Drop-in Log4j2 filter that pulls log levels from Quonfig. |
| `com.quonfig:sdk-java-micronaut` | Per-request `ContextSet` storage for Micronaut HTTP apps. |

Replace the version below with the latest from [Maven Central](https://central.sonatype.com/artifact/com.quonfig/sdk-java).

### Gradle (Kotlin DSL)

```kotlin
dependencies {
    implementation("com.quonfig:sdk-java:2.3.0")
    // optional, depending on which logging library you use:
    runtimeOnly("com.quonfig:sdk-java-logback:2.3.0")
    runtimeOnly("com.quonfig:sdk-java-log4j2:2.3.0")
    // optional, for Micronaut apps:
    implementation("com.quonfig:sdk-java-micronaut:2.3.0")
}
```

### Maven

```xml
<dependency>
    <groupId>com.quonfig</groupId>
    <artifactId>sdk-java</artifactId>
    <version>2.3.0</version>
</dependency>
```

## Startup and init failure

In SDK-key (delivery) mode, `new Quonfig(options)` is the init call. It blocks until the first
config loads, retrying through blips with jittered backoff, for up to `initTimeout` (default
10 s). A healthy boot returns as soon as the first config lands. If no config has loaded by
then, `onInitFailure` decides what happens:

| `onInitFailure` | At `initTimeout` |
|-----------------|------------------|
| `FAIL` (default) | Throws `QuonfigInitException`. |
| `WAIT` | Keeps blocking, with no upper bound, until a config loads. Pair it with a readiness probe. |
| `CONTINUE` | Returns with no config. Getters return your code defaults until a config loads. |

```java
Options opts = Options.builder()
    .sdkKey(System.getenv("QUONFIG_BACKEND_SDK_KEY"))
    .initTimeout(Duration.ofSeconds(10))
    .onInitFailure(OnInitFailure.FAIL) // or WAIT, CONTINUE
    .build();

try {
  Quonfig q = new Quonfig(opts);
} catch (QuonfigInitException e) {
  // e.reason() is one of:
  //   UNAUTHORIZED  every delivery leg answered 401/403/404/410
  //   TIMEOUT       the last attempt hung (every leg timed out), or none finished
  //   UNAVAILABLE   the delivery legs answered with errors (refused, DNS, 5xx, ...)
  // e.client() is a live client that keeps retrying and heals when delivery comes back.
  // Keep it, or close it:
  e.client().close();
}
```

- **Rejected SDK key.** When every delivery leg answers 401, 403, 404 or 410, `FAIL` throws (and
  `CONTINUE` returns) at once, without waiting out `initTimeout`, with reason `UNAUTHORIZED`.
  One leg alone never decides this.
- **`initTimeout` of zero or less** runs exactly one init attempt and then applies
  `onInitFailure`: `FAIL` throws, `CONTINUE` starts on code defaults, and `WAIT` keeps retrying.
  It does not mean "no deadline".
- **A caught `FAIL` leaves a live client.** `QuonfigInitException.client()` returns the client the
  constructor built. It keeps retrying in the background (with SSE and the fallback poller running)
  and becomes ready when delivery recovers. If you do not keep it, call `close()` on it, especially
  if you retry construction in a loop: every failed constructor leaves one such client.
  `QuonfigInitTimeoutException` (reason `TIMEOUT`) extends `QuonfigInitException`.
- **Getters never block on init.** Before the first config loads they return the caller's default
  with `Reason.ERROR` and `ErrorCode.PROVIDER_NOT_READY`.
- **Readiness:** `isReady()` reports whether a config has loaded, and `awaitReady(Duration)` blocks
  up to the given time for one (returns `true` once ready). `initFuture()` completes when the first
  config loads.
- **Logging:** init lines start with `[quonfig] init` and carry an `event=quonfig.init.<name>`
  SLF4J key-value (`start`, `retrying`, `waiting`, `unauthorized`, `failed`, `continue`, `wait`,
  `recovered`, `not_connected`). They name leg hosts and `HTTP <status>` only, never the SDK key,
  URL credentials or response bodies. A healthy boot logs nothing above DEBUG.

Datadir and datafile modes load synchronously and do not use `initTimeout` or `onInitFailure`.

## Typed getters

Each typed getter takes the key, your default and an optional `ContextSet`:
`getString`, `getBool`, `getLong`, `getDouble`, `getStringList`, `getDuration`, `getJson`, plus
`featureIsOn`, the `*Details` variants (value, `Reason`, `ErrorCode`) and the `*OrThrow` family.
They share one contract with every Quonfig SDK (2.2.0+):

- **A read that cannot return a value of the getter's type returns your default** (or `null` if you
  passed `null`) and logs one WARN per key per client. That covers a value of another type, a
  malformed stored or ENV_VAR value (`"twelve"` in an int config), an unset ENV_VAR and a value
  that cannot be decrypted. The WARN names the key, never the value. The `*Details` variant
  reports `Reason.ERROR`, with `ErrorCode.TYPE_MISMATCH` for a wrong-type or malformed value.
- **No string coercion.** A stored string `"false"` is not a bool, `"3"` is not an int, and a
  duration config is not a string.
- **Numbers convert only when exact.** `getDouble` reads an int config (`30` -> `30.0`).
  `getLong` reads a double config only when the value is whole and within +/-(2^53-1)
  (`3.0` -> `3`); `3.9` or `1e300` is a type mismatch. Nothing is truncated.
- **`featureIsOn` never throws.** A missing key, a flag with no matching rule, a non-bool value or
  any resolution error returns `false` and logs one WARN per key.
- **`get*OrThrow` fails hard.** It throws `QuonfigKeyNotFoundException` (missing key or no matching
  rule), `QuonfigCoercionException` (a malformed value; a subclass of
  `QuonfigKeyNotFoundException`), `QuonfigEnvVarNotSetException`, `QuonfigDecryptionException` or
  `QuonfigTypeMismatchException` (a subclass of `IllegalArgumentException`). Use it for a config
  that must not silently fall back, for example a secret whose decryption key may be wrong:

  ```java
  String dbPassword = quonfig.getStringOrThrow("db.password"); // throws on a bad decryption key
  ```

## Untyped details read

`evaluateDetails(key)` / `evaluateDetails(key, ctx)` (2.3.0+, also on `withContext(...)`) returns
an `EvaluationDetails<Object>` with the value exactly as stored, no type check and no coercion, plus
the same `Reason`, variant and metadata as the typed `*Details` getters. `valueType()` names the
config's stored type. A duration comes back as its ISO 8601 text (`"PT90S"`, not re-normalized to
`PT1M30S`); a malformed stored value comes back raw with its success reason. A missing key returns
a `null` value with `Reason.ERROR` / `ErrorCode.FLAG_NOT_FOUND`. It never throws. It is meant for
adapters such as OpenFeature providers that do their own typing.

```java
EvaluationDetails<Object> d = quonfig.evaluateDetails("http.timeout");
if (d.valueType() == ValueType.DURATION) {
  String iso = (String) d.value(); // "PT90S"
}
```

## Dynamic log levels

Add the matching filter at startup; every logger automatically picks up Quonfig log-level configs.

```java
Quonfig q = new Quonfig(opts);
QuonfigLogbackTurboFilter.install(q);   // or QuonfigLog4j2Filter.install(q);
```

## Failover & `QUONFIG_DOMAIN`

By default the SDK derives every hostname from `QUONFIG_DOMAIN` (default `quonfig.com`):

| Role                     | URL                                     |
|--------------------------|-----------------------------------------|
| Config fetch (primary)   | `https://primary.quonfig.com`           |
| SSE stream (primary)     | `https://stream.primary.quonfig.com`    |
| Config fetch (secondary) | `https://secondary.quonfig.com`         |
| SSE stream (secondary)   | `https://stream.secondary.quonfig.com`  |
| Telemetry                | `https://telemetry.quonfig.com`         |

Set `QUONFIG_DOMAIN` (or `Options.builder().domain(...)`) to move all of them together — e.g.
`QUONFIG_DOMAIN=quonfig-staging.com`. **Automatic failover and hedging between the primary and the
secondary are on by default** — the secondary runs on separate infrastructure, and the SDK fails
over to it if the primary is unreachable and hedges to it if the primary is slow.

`apiUrls(...)` replaces the derived list wholesale. To keep automatic failover with custom URLs,
**pass both a primary and a secondary URL**:

```java
Quonfig q =
    new Quonfig(
        Options.builder()
            .sdkKey("your-sdk-key")
            .apiUrls(
                List.of(
                    "https://primary.your-proxy.example",
                    "https://secondary.your-proxy.example"))
            .build());
```

A single URL disables failover, and the SDK logs a warning at init. See
[Reliability](https://docs.quonfig.com/docs/explanations/architecture/resiliency) for the full model.

## Datadir mode: auto-reload on file changes

When you build a `Quonfig` client with `Options.builder().datadir("./path")`, configs are loaded
once from disk during construction. Opt in to `dataDirAutoReload(true)` to have the SDK watch the
directory with `java.nio.file.WatchService` and re-read the envelope whenever files change — an
editor save, a `git pull`, or a build step.

```java
import com.quonfig.sdk.Options;
import com.quonfig.sdk.Quonfig;

Options opts =
    Options.builder()
        .datadir("./workspace-data")
        .environment("development")
        .dataDirAutoReload(true) // off by default — must be opted in
        .onConfigUpdate(() -> System.out.println("Quonfig configs reloaded from disk"))
        .build();

Quonfig q = new Quonfig(opts);

// Edit a file under ./workspace-data and onConfigUpdate fires after the debounce window.

// On shutdown, close() stops the watcher thread and cancels any pending debounce.
q.close();
```

### When to enable

- Local development with the datadir checked out from git.
- Self-hosted servers that `git pull` the datadir on a schedule.
- CI / integration jobs that mutate the datadir between assertions.

### When NOT to enable

- **Read-only / immutable filesystems** (some containers, scratch images, AWS Lambda layers). Watch
  registration may fail; the SDK degrades gracefully (logs and keeps serving the envelope it loaded
  at construction) but you're paying for a no-op watcher thread.
- **Build-time-embedded workflows** where the datadir is packaged into the JAR and never changes at
  runtime — watching wastes a file descriptor and a daemon thread.
- **Production paths where reload timing matters** — you'd usually rather pin the envelope you
  shipped with and roll forward through a redeploy than have it shift under traffic.

Default is `false`; datadir mode is silent until you opt in.

### Behavior contract

- **Parse-then-swap.** If the new envelope fails to parse (truncated write, mid-`git pull` state,
  invalid JSON), the SDK logs the error and **keeps serving the previous envelope**.
  `onConfigUpdate` is _not_ fired on parse failure — only on a successful swap.
- **Debounced.** Filesystem bursts (atomic-rename editor saves, `git pull` touching dozens of files)
  coalesce into a single re-read. Default window: **200ms**. Tune via
  `dataDirAutoReloadDebounceMs(long)` if you need a different window.
- **Graceful degrade.** If `WatchService` registration fails (read-only fs, immutable container,
  too-many-open-files), the SDK logs and continues without watching — the `Quonfig` constructor
  does **not** throw.
- **Symlinks.** The watcher resolves the datadir to its real path at start (`Path.toRealPath()`).
  Editing the file the symlink points at _is_ detected; atomic flips that retarget the link itself
  are **not**.
- **Shutdown.** `Quonfig.close()` stops the watcher, cancels any pending debounce, and joins the
  watcher daemon thread (2s grace). The watcher lifecycle is tied to the client — no separate
  handle to manage.

### macOS caveat

The JDK ships a polling `WatchService` on macOS rather than a native FSEvents/kqueue
implementation. Even with the `HIGH` sensitivity modifier the SDK already requests, detection
latency is **~2 seconds** on macOS. On Linux (`inotify`) and Windows (`ReadDirectoryChangesW`)
events arrive in well under 100ms. This is a JDK platform behavior, not something the SDK can tune
away — if you need sub-second reaction on macOS, prefer triggering reloads via your build tool or
file-watching script rather than relying on `WatchService`.

### Tuning the debounce window

```java
Options.builder()
    .datadir("./workspace-data")
    .dataDirAutoReload(true)
    .dataDirAutoReloadDebounceMs(1000) // wait a full second after the last event
    .build();
```

The default (200ms) is tuned for interactive editing. Raise it if you have a noisy producer
(continuously regenerating files) and you'd rather see one reload per second than per save.

See the [open-source / local how-to](https://docs.quonfig.com/docs/how-tos/open-source-local) for
the cross-SDK story (sdk-node, sdk-go, sdk-ruby, sdk-python, sdk-java).

## Health primitives

`Quonfig.lastSuccessfulRefresh()` returns the wall-clock time of the most recent installed
envelope (any source). `Quonfig.connectionState()` returns one of `CONNECTED`, `DISCONNECTED`,
`FALLING_BACK`, or `INITIALIZING`.

> Do not wire `lastSuccessfulRefresh()` or `connectionState()` directly into a Kubernetes liveness probe. These signals are diagnostic, not pass/fail. A liveness probe based on SDK freshness will amplify transient network blips into restart cascades.

## Dev context from `qfg login` (on by default)

On a machine where someone ran `qfg login`, the SDK reads the signed-in email from
`~/.quonfig/tokens.json` (`tokens-<domain>.json` for a non-production `QUONFIG_DOMAIN`) and adds it
to every evaluation as the `quonfig-user.email` context, so rules can target it and it reaches
telemetry example contexts. Customer contexts with the same name win. Turn it off with the
`enableQuonfigUserContext(false)` builder option or `QUONFIG_DEV_CONTEXT=false` (the option wins
over the env var). Production servers normally have no tokens file, so nothing is added there.

## Telemetry

The SDK sends usage telemetry to `telemetryUrl` so the Quonfig dashboard can show which flags and
configs are evaluated and with what contexts. Telemetry never affects flag evaluation: every failure
below is contained in the background reporter.

**What is sent.** Evaluation summaries (per flag/config: counts per rule and value), context shapes
(context field names and types), example contexts (up to one per context key per hour) and failover
counters. Opt out with `collectEvaluationSummaries(false)` and
`contextUploadMode(ContextUploadMode.SHAPES_ONLY)` (no example contexts) or `ContextUploadMode.NONE`
(no context data), or turn it all off with `disableTelemetry(true)`.

**How it is sent.**

- One POST every `telemetryFlushInterval` (60s), with at most one POST in flight. A tick that fires
  while a POST is still out is skipped and its data rolls into the next window.
- Each POST has an overall deadline of `telemetryTimeout` (15s) and a connect/TLS timeout of
  `telemetryConnectTimeout` (5s).
- When a POST fails (timeout, network error, 408, 429 or 5xx), the serialized batch is kept
  byte-for-byte and resent unchanged, never merged with newer data, so the server can recognize a
  resend of a batch that did land. Up to `telemetryMaxRetainedBatches` (5) batches /
  `telemetryMaxRetainedBytes` (2MB) are kept for up to `telemetryMaxRetainedAge` (5 minutes);
  beyond that the oldest is dropped, and a single batch larger than the byte cap is sent once and
  never kept. Resends happen no sooner than 30s after a failure and after any `Retry-After`
  (honored up to 10 minutes), oldest first, then the current window.
- A 401, 403 or 404 means the SDK key or `telemetryUrl` is wrong: the SDK logs one error and
  disables telemetry for the rest of the process. Any other 4xx drops that one batch with an error
  (the server rejected the payload) and telemetry continues.

**Logging** (through SLF4J, or the `logger(...)` you pass). A failed POST logs at DEBUG only. The
first batch actually dropped logs one WARN with the last POST result and queue depth; further drops
log at DEBUG with a summary WARN at most every 10 minutes; the first success after failures logs one
INFO line.

**`flush()` and `close()`.** `flush()` sends the current window now and waits for the POST (useful
in serverless handlers); after a failure it respects the 30s floor and `Retry-After`, and it does
not throw. `close()` sends the current window once with a 5s deadline, does not resend kept
batches, and leaves no telemetry thread running.

**Memory.** Everything is bounded: at most 10,000 evaluation-summary keys, 10,000 context-shape
fields and 10,000 example contexts per window (`telemetryMaxEvaluationSummaries`,
`telemetryMaxContextShapeFields`, `telemetryMaxExampleContexts`; keys already seen keep counting at
the cap), a 100,000-entry example-context rate-limit map, and the 2MB retained queue.

```java
Options opts = Options.builder()
    .sdkKey(System.getenv("QUONFIG_BACKEND_SDK_KEY"))
    .telemetryFlushInterval(Duration.ofSeconds(60))   // default
    .telemetryTimeout(Duration.ofSeconds(15))         // default
    .telemetryConnectTimeout(Duration.ofSeconds(5))   // default
    .telemetryMaxRetainedBatches(5)                   // default
    .telemetryMaxRetainedBytes(2L * 1024 * 1024)      // default
    .telemetryMaxRetainedAge(Duration.ofMinutes(5))   // default
    .build();
```

## Requirements

- Java 17 or later

## License

Apache License 2.0
