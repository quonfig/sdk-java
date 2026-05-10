# Changelog

## 0.0.2 - 2026-05-10

Multi-module restructure that ships transparent dynamic-log-level integrations for Logback, Log4j2, and Micronaut. Customers add one filter at startup and every logger picks up Quonfig log levels — no per-call-site `shouldLog` wrapping. Tracks [qfg-wgfu](https://github.com/quonfig/sdk-java/issues).

- Multi-module Gradle restructure: `:core`, `:logback`, `:log4j2`, `:micronaut`. Existing `com.quonfig:sdk-java` artifact unchanged in coordinates and contents (qfg-wgfu)
- New artifacts published in lock-step from this repo: `com.quonfig:sdk-java-logback`, `com.quonfig:sdk-java-log4j2`, `com.quonfig:sdk-java-micronaut` (qfg-wgfu)
- Public `LogLevel` enum + `LoggerClient` interface; `Quonfig` implements `LoggerClient` and exposes `getLogLevel(loggerPath, ContextSet) -> Optional<LogLevel>` (qfg-wgfu)
- `QuonfigLogbackTurboFilter.install(loggerClient)` — Logback turbo filter with hierarchical logger-path fallback and recursion guard (qfg-wgfu)
- `QuonfigLog4j2Filter.install(loggerClient)` — Log4j2 context-level filter with the same semantics (qfg-wgfu)
- `MicronautContextStore` — request-scoped `ContextSet` storage backed by `ServerRequestContext` for Micronaut event-loop apps (qfg-wgfu)

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
