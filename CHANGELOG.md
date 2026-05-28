# Changelog

## 0.0.3 - 2026-05-28

SDK-1.0 method/enum unification (`project/plans/sdk-1.0-unification.md` §1).
All changes are strictly additive and source-compatible — the legacy methods
and enum constant are retained as `@Deprecated` forwarders for one minor cycle,
so existing callers keep compiling and existing payloads keep deserializing.

### Added

- **`getBool` / `getBoolDetails` (qfg-6nxx).** Canonical boolean accessors on
  both `Quonfig` and `BoundQuonfig`, aligning Java with the `getBool` /
  `get_bool` spelling used by node, ruby, python, and go. `getBoolean` /
  `getBooleanDetails` remain as `@Deprecated` thin forwarders; `featureIsOn`
  now calls `getBoolDetails` directly.
- **`getLong` / `getLongDetails` (qfg-8xhx).** Java's `getInt` / `getIntDetails`
  were misleading — they return `Long`, not `Integer`. The new `getLong` /
  `getLongDetails` have identical behavior; `getInt` / `getIntDetails` remain as
  `@Deprecated` forwarders. Wire format and resolver are unchanged (still
  64-bit) — purely a naming fix, no semantic change.

### Changed

- **`ContextUploadMode.SHAPES` → `SHAPES_ONLY` (qfg-6svs).** `SHAPES_ONLY` is
  the new canonical enum constant and parses from the wire value `"shapes_only"`.
  The legacy `SHAPES` constant remains as a deprecated forwarder pointing at
  `SHAPES_ONLY`, and `parse()` also accepts the wire value `"shapes"` as a
  deprecated alias, so existing callers and payloads keep working for one minor
  cycle.

## 0.0.2 - 2026-05-19

Hardening and surface-expansion release covering 12 commits since `v0.0.1`. The headliners: a multi-module Gradle restructure that ships three new published artifacts for Logback / Log4j2 / Micronaut, a datafile-mode loader matching sdk-node, opt-in filesystem auto-reload for datadir mode, a Tier-1 supervisor + Layer-2 fallback poller wired in behind the SSE transport, and the first cross-SDK chaos harness wiring for Java. All four Maven Central artifacts ship in lock-step from this tag.

### Multi-module restructure (new artifacts)

- Multi-module Gradle restructure: `:core`, `:logback`, `:log4j2`, `:micronaut`. The existing `com.quonfig:sdk-java` artifact is unchanged in coordinates and contents (qfg-wgfu)
- Three new artifacts published from this tag alongside `sdk-java`: `com.quonfig:sdk-java-logback`, `com.quonfig:sdk-java-log4j2`, `com.quonfig:sdk-java-micronaut` (qfg-wgfu)
- Public `LogLevel` enum and `LoggerClient` interface; `Quonfig` now implements `LoggerClient` and exposes `getLogLevel(loggerPath, ContextSet) -> Optional<LogLevel>` so filter modules can resolve levels without going through `shouldLog`'s boolean comparison (qfg-wgfu)
- `QuonfigLogbackTurboFilter.install(loggerClient)` — drop-in Logback `TurboFilter` with hierarchical logger-path fallback and a recursion guard (qfg-wgfu)
- `QuonfigLog4j2Filter.install(loggerClient)` — drop-in Log4j2 context-level filter with the same semantics (qfg-wgfu)
- `MicronautContextStore` — request-scoped `ContextSet` storage backed by `ServerRequestContext` for Micronaut event-loop apps (qfg-wgfu)
- Each filter module declares its logging library as `compileOnly` so customers bring their own version

### Features

- **Datafile mode.** `Options.Builder.datafile(String)` and `Options.Builder.datafileEnvelope(ConfigEnvelope)` load configs from a serialized envelope (filesystem path or pre-parsed object), matching sdk-node's `datafile?: string | object` shape. The envelope's `meta.environment` supplies the evaluation environment when the caller does not set `Options.environment()` explicitly. Replaces the previous `IllegalStateException` thrown at construct time (qfg-9hre)
- **Datadir auto-reload (opt-in).** `Options.Builder.dataDirAutoReload(true)` enables a `java.nio.file.WatchService`-based watcher that debounces filesystem bursts (default 200ms via `dataDirAutoReloadDebounceMs(long)`), parse-then-swap reloads via `DatadirLoader.load`, and fires the existing `onConfigUpdate` callback. Graceful read-only-fs / immutable-container degrade, daemon-thread cleanup on `close()`, symlink-aware via `Path.toRealPath()`. macOS uses a polling `WatchService` with a ~2s detection floor (documented); Linux/Windows are sub-100ms. Mirrors sdk-node's `qfg-mol-0kr` shape (qfg-mol-3jq, docs qfg-zx3y.5)
- **Layer-1 SSE read watchdog.** `SseClient.parseStream` now schedules a 90s read watchdog on a single-thread executor; each chunk resets it, expiry closes `activeBody`, unblocks `readLine`, and falls through to the existing reconnect path. Override via `Options.sseReadWatchdog`. Closes the silent-stall / half-open hang that the JDK `HttpClient` had no deadline for (qfg-47c2.12)
- **Supervisor (watcher-of-the-watchers).** New `com.quonfig.sdk.supervisor.Supervisor` — Java parity for sdk-go's Supervisor. One instance per `Quonfig` client wraps each background worker in a try/catch boundary: uncaught `Throwable` or non-stop exit logs at ERROR, increments `quonfig_sdk_worker_restart_total{layer="<n>"}`, sleeps an exponential backoff (500ms → 30s cap), and restarts. Clean shutdowns aren't counted; `stop()` joins all workers within a 5s deadline (qfg-47c2.18)
- **Layer-2 fallback poller.** Replaces the dead `enablePolling` / `pollInterval` builders with cross-SDK `fallbackPollEnabled` / `fallbackPollIntervalMs` (defaults `true` / 60_000). New `FallbackPoller` runs as a Layer-2 worker under the Supervisor; engages after SSE has been disconnected for 120s and disengages when SSE recovers. Identical semantics to sdk-node and sdk-go (qfg-47c2.21)
- **Health primitives.** New public enum `com.quonfig.sdk.ConnectionState` (`CONNECTED`, `DISCONNECTED`, `FALLING_BACK`, `INITIALIZING`). `Quonfig.lastSuccessfulRefresh()` returns an `Instant` of the most recent `installRows()` call (any source); `Quonfig.connectionState()` delegates to the Supervisor in HTTP+SSE mode and reports `CONNECTED` for static modes once rows are installed. Per-plan README warning: do not wire these into k8s liveness probes (qfg-47c2.23)

### Fixes

- `Quonfig.fireConfigUpdate` no longer silently swallows `RuntimeException` from user listeners — listener exceptions now log at ERROR with both `callback` and `onConfigUpdate` in the message, matching sdk-go's `invokeOnConfigUpdate` and giving operators / chaos harnesses an observable signal (qfg-srj8)
- `fix(datadir)`: workspace loader now excludes the `schemas/` directory so JSON Schema files alongside config envelopes don't get walked as configs

### Tests, chaos, and CI

- **sdk-java wired into the cross-SDK chaos harness.** New JUnit 5 chaos runner under `core/src/test/java/com/quonfig/sdk/chaos/` loads YAML scenarios from `integration-test-data/chaos/scenarios/`, drives toxiproxy via its admin API, and observes a fresh `Quonfig` client per scenario. Gated on `CHAOS_RUN=1` so default `./gradlew test` still skips it. `scripts/run-chaos.sh` wraps the boot dance (build api-delivery in FIXTURE_DIR mode, bring up the shared toxiproxy launcher, run `:core:test` with the chaos filter). Test-only `snakeyaml 2.3` dep added (qfg-47c2.5)
- Chaos scenario 07 now toggles toxiproxy's `setEnabled(false)` instead of relying on the `limit_data` toxic, which only tripped on the 30s SSE heartbeat — outside the scenario's 15s deadline. Mirrors sdk-go / sdk-node / sdk-python / sdk-ruby (qfg-47c2.29)
- `ChaosTest` installs a `ProbeBridgeLogger` that forwards every SDK SLF4J call into `ChaosProbe.log`, so `sdkLog`-based scenario expectations now have a signal to match (qfg-srj8)
- `QUONFIG_CHAOS_SESSION` + `QUONFIG_CHAOS_OWNER_PID` env vars exported so the cross-SDK file lock in `integration-test-data/chaos` can detect concurrent runs and attribute teardown ownership correctly (qfg-47c2.32)
- CI: `integration-test-data` checkout pinned to tag `v2026.05.13` so a typo in shared YAML can't break every SDK's CI at once

### Internal

- `chore: bump VERSION_NAME to 0.0.2-SNAPSHOT` (post-0.0.1 development)

## 0.0.1 - 2026-05-10

First public release of the Quonfig Java SDK. Greenfield port of the Quonfig client targeting Java 17+, published to Maven Central as `com.quonfig:sdk-java`. Tracks the [qfg-oi0j epic](https://github.com/quonfig/sdk-java/issues).

- Bootstrap Gradle (Kotlin DSL) project with Spotless + google-java-format (qfg-oi0j.1)
- `EvaluationDetails` and wire types aligned with the cross-SDK qfg-ypcu spec (qfg-oi0j.2)
- Datadir-mode wire-format `ConfigEnvelope` loader (qfg-oi0j.3)
- HTTP transport with Basic auth, ETag handling, and primary→secondary failover (qfg-oi0j.4)
- Murmur3-based weighted value resolver for deterministic A/B bucketing (qfg-oi0j.5)
- Dynamic per-logger log-level evaluation via `shouldLog(loggerPath, Level)` (qfg-oi0j.6)
- CI workflow (`.github/workflows/test.yaml`) running JUnit + Spotless on PRs (qfg-oi0j.7)
- SSE client with `List<URI>` failover and typed `ConfigEnvelope` decoding (qfg-mol-d51, qfg-mol-1hh)
- Evaluation engine + operators ported from sdk-go reference (qfg-mol-a9w)
- `Resolver` with ENV_VAR provided values, AES-GCM decryption, and type coercion across `STRING_LIST`, `JSON`, and `DURATION` (qfg-mol-9gp, qfg-mol-5bv)
- Public client surface: `Quonfig`, `BoundQuonfig`, `EvaluationDetails` (qfg-mol-d7z)
- Telemetry: eval summaries, context shapes, example contexts (qfg-mol-rqg)
- Cross-SDK YAML integration corpus wired into the test suite (qfg-mol-iv4, qfg-mol-5bw)
- HTTP+SSE transports wired into the `Quonfig` client at init (qfg-mol-1q2)
- Maven Central publishing wired via Sonatype Central Portal + in-memory GPG signing (qfg-mol-6o0)
