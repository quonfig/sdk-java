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
    implementation("com.quonfig:sdk-java:1.0.0")
    // optional, depending on which logging library you use:
    runtimeOnly("com.quonfig:sdk-java-logback:1.0.0")
    runtimeOnly("com.quonfig:sdk-java-log4j2:1.0.0")
    // optional, for Micronaut apps:
    implementation("com.quonfig:sdk-java-micronaut:1.0.0")
}
```

### Maven

```xml
<dependency>
    <groupId>com.quonfig</groupId>
    <artifactId>sdk-java</artifactId>
    <version>1.0.0</version>
</dependency>
```

## Dynamic log levels

Add the matching filter at startup; every logger automatically picks up Quonfig log-level configs.

```java
Quonfig q = new Quonfig(opts);
QuonfigLogbackTurboFilter.install(q);   // or QuonfigLog4j2Filter.install(q);
```

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

## Requirements

- Java 17 or later

## License

Apache License 2.0
