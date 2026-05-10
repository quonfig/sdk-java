# Changelog

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
