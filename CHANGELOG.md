# Changelog

## 1.1.1 - 2026-07-03

### Fixed

- **`lastSuccessfulRefresh()` now advances on answered-but-not-installed HTTP fetches
  (qfg-41nh.15; sdk-go qfg-41nh.11 parity).** `refresh()`, the Layer 2 fallback poller,
  and the hedged init legs stamped the refresh clock only when an envelope actually
  installed, so a healthy client long-parked on same-generation answers (or 304s)
  under-reported liveness — the mechanism behind the sdk-go qfg-sc90 chaos red. An
  answered leg — a 304, or a 200 whose envelope the reject-older guard dropped — now
  stamps; errors still never stamp. SSE installs are unchanged (install-gated — the
  canonical semantics sdk-net adopted in qfg-41nh.8). Liveness (`lastSuccessfulRefresh()`)
  and config freshness (`heldGeneration()`) are separate signals.
- **SSE stream pinned to the primary leg (qfg-41nh.7).** `SseClient` walked the full
  stream-URL list on every reconnect round, so while the primary stream was down the
  live stream silently repointed at the secondary — violating the design invariant
  that failover is HTTP-poll-only (chaos scenario f05). The stream now dials only
  `streamUrls[0]`, retrying it forever with the existing backoff + read watchdog
  (sdk-go semantics). The HTTP paths are unchanged: the hedged initial fetch,
  `refresh()`, and the fallback poller still fail over across all `apiUrls`.
- **`Quonfig.sseFailedOverToSecondary()` now reports real transport state.** It was
  backed by a hardcoded `0` recorded on every SSE connect, which made the f05 chaos
  assertion vacuous. It now derives from the leg the stream actually connected on
  (new internal `SseClient.connectedStreamIndex()` accessor, latched at its maximum),
  and the failover-chaos rig passes a live secondary stream URL as a canary so f05
  genuinely fails if stream-leg walking is ever reintroduced.

## 1.1.0 - 2026-07-01

### Added

- **Canonical-ordering accessors and `Quonfig.refresh()` (qfg-7h5d.1.10).** New public
  `ready()`, `heldGeneration()`, `configInstallCount()`, `resolvedFrom()`, and
  `sseFailedOverToSecondary()` observe the failover/ordering state; `refresh()`
  performs one manual `[primary, secondary]` poll + guarded install. `Meta` now
  carries the `generation` watermark (decodes to 0 from servers that predate it).
- **Per-URL config-fetch timeout (qfg-7h5d.1.10).** New `Options.configFetchTimeout`
  (default ~3s) bounds each individual base-URL attempt on the initial fetch and the
  fallback poller, so a hung or black-holed primary aborts fast and the SDK fails
  over to the secondary inside `initTimeout` instead of starving it. Additive and
  backward-compatible — the default already makes a hung upstream fail over.
- **`Options.configFetchHedgeDelay` (default ~2s) and `Options.configFetchHedgeAbort`
  (default ~6s).** Two additive, backward-compatible options tune the hedge: the
  delay is how long the primary is given before the secondary is also fired; the
  abort is the per-leg hard deadline (distinct from `configFetchTimeout`, which still
  governs the sequential `refresh()`/fallback-poll path). The client logs a warning
  at construction when `initTimeout <= configFetchHedgeAbort`.

### Changed

- **Reject-older install guard (qfg-7h5d.1.10).** Every delivery install path (initial
  HTTP fetch, `refresh()`, SSE initial snapshot, SSE update, fallback poller) now
  installs only if the incoming `Meta.generation` advances the held generation. A
  stale secondary can seed a *fresh* client but can never move an *established* client
  backward, and a same-generation payload is a no-op (no flap, no `onConfigUpdate`).
  Mirrors the sdk-go pilot and the §5f cross-SDK contract.
- **Install-guard carve-out for unversioned snapshots.** A delivery payload whose
  `generation` is absent or `<= 0` (e.g. from a server that predates the generation
  watermark) is installed by an established client rather than rejected as older.
  Defensive back-compat guard — with servers that emit true generations it never
  triggers.
- **Parallel-failover hedge on the initial HTTP config fetch (qfg-7h5d.1.14).** The
  initial config fetch now fires the primary leg first and, only if it is slow past
  the hedge delay OR errors fast, ALSO fires the secondary leg **in parallel** —
  without cancelling the primary. A fast healthy primary answers inside the hedge
  delay, so the secondary stays a cold standby and a healthy system adds zero
  secondary load. Whatever arrives is installed through the existing reject-older
  guard, so watermark-max falls out: the higher generation wins, a late older payload
  never regresses an established client, and a late newer payload heals forward.
  Readiness latches on the first successful install. Mirrors the sdk-go pilot.

### Behavioral notes (backward-compatible)

- `resolvedFrom()` may now return `"primary"` in a fast-both topology where 1.0.0
  (sequential) would have returned `"secondary"` — the hedge prefers the leg that
  actually won the race, and a fast primary wins without contacting the secondary.
- An **extra** post-ready `onConfigUpdate` callback may fire on heal-forward: when a
  late-but-newer leg lands after readiness has latched, its install advances the
  generation and notifies listeners.
- ETags are now effectively per-leg on the hedge: each hedge leg passes its own ETag
  (currently `null` on the initial fetch), so two concurrent legs never share a
  mutable ETag slot — there is no 304-mask race between the legs.

## 1.0.0 - 2026-06-06

- **Stable 1.0.0 release.** The Quonfig Java SDK (`com.quonfig:sdk-java` and the
  `sdk-java-logback` companion) is now declared stable. No API or behavior changes
  from 0.0.5 — this is a coordinated 1.0.0 version stamp across the entire Quonfig
  SDK family.

## 0.0.5 - 2026-06-02

### Added

- **Token-file dev-context loader, default-on (qfg-bw7g.6).** New `DevContextLoader`
  reads `qfg login`'s `~/.quonfig/tokens.json` (per-domain filename derived from
  `apiUrls`) and injects `{ quonfig-user: { email } }` into the global evaluation
  context. Wired through `Options.build()`: default-on, gated solely by the token
  file's presence, so it is inert in production (no token file there). Precedence:
  explicit `enableQuonfigUserContext` option, else `QUONFIG_DEV_CONTEXT` env, else
  `true`. Dev-context merges **under** the customer `globalContext`, so customer keys
  win on collision. Mirrors the sdk-node loader. No new dependencies (uses Jackson).
  Set `enableQuonfigUserContext(false)` or `QUONFIG_DEV_CONTEXT=false` to opt out.

## 0.0.4 - 2026-05-29

Per-environment override correctness in delivery mode, plus an evaluation-reason
alignment fix. All changes are bug fixes — no API surface or wire-format changes.

### Fixed

- **Parse the singular delivery `environment` block (qfg-xpln.1).** `parseConfigNode`
  now reads the singular `environment` object that api-delivery emits for the
  active environment, so per-environment value overrides resolve correctly in
  delivery mode instead of falling through to the default.
- **`meta.environment` is authoritative in delivery mode (qfg-pinh).** When loading
  from a delivery payload, the environment carried in `meta.environment` is the
  source of truth. An explicit `Options.environment()` pin is honored only in
  datadir mode; in delivery mode an explicit pin is ignored and logged at WARN,
  matching the cross-SDK contract.
- **Evaluation reason aligned with canonical STATIC/SPLIT semantics (qfg-q7yz).**
  `ALWAYS_TRUE`-only configs now report `STATIC` (not `TARGETING_MATCH`), and
  weighted-value configs report `SPLIT` out of the box. The evaluator decides
  `STATIC` vs `TARGETING_MATCH` via whole-config `hasTargetingRules()` (mirroring
  sdk-go's `runtime_eval.go`), and `Options` now defaults to
  `Murmur3WeightedValueResolver` so weighted configs resolve without explicit
  wiring. Matches sdk-go's reference evaluator and integration-test-data.

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
