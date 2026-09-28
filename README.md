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
    implementation("com.quonfig:sdk-java:1.4.0")
    // optional, depending on which logging library you use:
    runtimeOnly("com.quonfig:sdk-java-logback:1.4.0")
    runtimeOnly("com.quonfig:sdk-java-log4j2:1.4.0")
    // optional, for Micronaut apps:
    implementation("com.quonfig:sdk-java-micronaut:1.4.0")
}
```

### Maven

```xml
<dependency>
    <groupId>com.quonfig</groupId>
    <artifactId>sdk-java</artifactId>
    <version>1.4.0</version>
</dependency>
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
