# Changelog

## 2.4.1 - 2026-10-30

Security patch for runtime dependencies (qfg-66ko.19). No API or behaviour change.

- **jackson-databind 2.18.2 -> 2.18.12** (brings jackson-core 2.18.12). Clears GHSA-7hhh-6rmp-j9qf,
  GHSA-p6pp-m3f8-5c89, GHSA-r7wm-3cxj-wff9, GHSA-cxp5-3px4-pw24, GHSA-j3rv-43j4-c7qm,
  GHSA-q4xh-88c3-wmh7, GHSA-rmj7-2vxq-3g9f and GHSA-wv8q-qhhj-9h54.
- **guava 33.4.0-jre -> 33.7.2-jre.** Clears GHSA-xxph-c9ww-hj94.
- These raise the minimum jackson and guava versions `sdk-java` asks your build for. Gradle and Maven
  resolve the newer patch versions unless you pin older ones yourself. Floor raise approved by Jeff
  (qfg-66ko.19, 2026-10-29).
- `sdk-java-micronaut` now compiles against Micronaut 4.10.35 (was 4.7.10, GHSA-8hjv-92q9-g4xj).
  Micronaut stays a provided dependency: your app's Micronaut version is used, and the published POM
  is unchanged.
- Build: Gradle dependency locking is on with `gradle.lockfile` committed, so the SSDLC dependency
  scan sees the resolved tree. It pins only this repo's builds.

## 2.4.0 - 2026-10-29

Edge-case behaviour change on the details API's error path (qfg-wg1n). Only the
`ErrorCode` on a failed read changes; values, reasons and exceptions do not.

- **New `ErrorCode.PARSE_ERROR`.** The typed `get*Details` getters and
  `evaluateDetails` now report a value that has the right type but does not
  parse as `PARSE_ERROR` (was `TYPE_MISMATCH`): an ENV_VAR value that cannot be
  converted to the config's type (an int env var `"abc"`, a duration env var
  `"30s"`, a bool `"maybe"`, invalid json) and, on the typed getters, a
  malformed stored int, double or duration (`"twelve"`, `"30s"`). The reason
  (`Reason.ERROR`), the value (your default; null on `evaluateDetails`), the
  variant and the metadata are unchanged. A config of another type
  (`getStringDetails` on an int config, `getLongDetails` on `3.9`) is still
  `TYPE_MISMATCH`. `evaluateDetails` still returns a malformed stored value as
  stored with its success reason. The plain getters and the `get*OrThrow`
  family (`QuonfigCoercionException`, `QuonfigTypeMismatchException`) are
  unchanged. Code that compared `errorCode()` to `TYPE_MISMATCH` to detect a
  malformed value should also accept `PARSE_ERROR`, and an exhaustive `switch`
  over `ErrorCode` needs the new constant. openfeature-java 3.3.0 already maps
  `PARSE_ERROR` straight through to OpenFeature's `PARSE_ERROR`. Tests:
  `ParseErrorCodeTest`.

## 2.3.0 - 2026-10-29

Additive (qfg-177f, sdk-go `Client.EvaluateDetails` parity). No existing getter
changes.

- **New `evaluateDetails(String key)` and `evaluateDetails(String key, ContextSet ctx)`**
  on `Quonfig` and `BoundQuonfig`. Returns `EvaluationDetails<Object>` with the
  resolved value in its stored form (after ENV_VAR provision and decryption) and
  the same reason, variant, metadata and telemetry as the typed `*Details`
  getters, with no type check, coercion or parse validation. A duration is its
  ISO 8601 text exactly as stored (`"PT90S"`), a string_list a `List<String>`,
  json what `getJsonDetails` returns. A malformed stored value is returned raw
  with its success reason; an ENV_VAR value that does not parse as the config's
  type still fails at resolution (`Reason.ERROR`, `TYPE_MISMATCH`, null value),
  as in sdk-go. A missing key returns a null value with `FLAG_NOT_FOUND`. Never
  throws.
- **New `EvaluationDetails.valueType()`** (nullable `ValueType`) and an
  8-argument constructor. Set by `evaluateDetails` to the config's stored value
  type (null when the key is not found); null on the typed getters.
- An ENV_VAR-provided duration is now kept as its validated ISO text inside the
  resolver (as a stored duration is). `getDuration` still parses and rounds it,
  so its result is unchanged; evaluation telemetry now reports the env text
  (`"PT90S"`) instead of `Duration.toString()` (`"PT1M30S"`).

## 2.2.0 - 2026-10-26

The typed-getter contract (qfg-zvyy), shared by every Quonfig SDK and pinned by
the integration-test-data matrix (`getter.contract.*`, tag `v2026.10.23`). A
typed read that cannot return a value of the getter's type returns your default
and logs once, instead of a made-up value. For sdk-java this mostly widens what
the getters accept; the edge cases below change.

### UPGRADE to 2.2.0

1. **Numbers convert when exact.** `getDouble` now reads an int config
   (`30` -> `30.0`), and `getLong` reads a double config holding a whole number
   within +/-(2^53-1) (`3.0` -> `3`). Before, both returned your default with
   `ErrorCode.TYPE_MISMATCH`. `3.9`, `1e300` and `2^53` are still type
   mismatches; nothing is truncated.
2. **Malformed stored ints and doubles return your default.** A stored int or
   double that does not parse (for example `"twelve"` or `"abc"`) used to read
   as `0` / `0.0`. It now returns your default (or `null`) with
   `Reason.ERROR` / `ErrorCode.TYPE_MISMATCH`, and `getLongOrThrow` /
   `getDoubleOrThrow` throw `QuonfigCoercionException`, the same as a malformed
   ENV_VAR value or duration.
3. **`getString` no longer reads a duration config.** It returned the ISO-8601
   text (`"PT30S"`); it now returns your default and logs a type mismatch, like
   every other cross-type read. Read durations with `getDuration`.
4. **One WARN per key.** A type mismatch, a malformed value, an unset ENV_VAR or
   a decryption failure logs one WARN per key per client
   (`quonfig: read of "<key>" returned no value; the caller's default applies: ...`).
   It names the key, never the value. `featureIsOn` also logs once per key when
   it returns `false` because the key is missing, no rule matched, the value is
   not a bool, or resolution failed. It never throws. A wrong decryption key in
   production therefore degrades to your defaults with these WARNs; if that must
   be fatal, read the secret with `get*OrThrow`, which throws
   `QuonfigDecryptionException`.
5. **New exception subclasses (source and binary compatible).** `get*OrThrow`
   now throws `QuonfigTypeMismatchException` (extends
   `IllegalArgumentException`, which it threw before) for a wrong-type value,
   and `QuonfigCoercionException` (extends `QuonfigKeyNotFoundException`, which
   it threw before) for a value that cannot be converted. Existing `catch`
   blocks keep working. `QuonfigKeyNotFoundException` is no longer `final`.

### Added

- `com.quonfig.sdk.exceptions.QuonfigTypeMismatchException` and
  `com.quonfig.sdk.exceptions.QuonfigCoercionException` (qfg-koj9). Before, a
  coercion failure in `get*OrThrow` threw a plain `QuonfigKeyNotFoundException`
  reading "no value found", although the key existed.

### Changed

- `*Details` getters report `ErrorCode.TYPE_MISMATCH` (was `GENERAL`) for a
  malformed stored or ENV_VAR value, duration included.
- The malformed-duration WARN is now the shared read-failure WARN above.
- CI runs integration-test-data `v2026.10.26` (the contract matrix plus the generator mapping that expects `QuonfigCoercionException`, qfg-koj9).

## 2.1.0 - 2026-10-23

### Upgrade notes

> **UPGRADE: `HttpTransportException.bodyExcerpt()` holds at most 1 KiB (qfg-6vno.20.2, minor).**
> The SDK now reads at most 1 KiB of a non-2xx response body, so `bodyExcerpt()` holds at most
> 1 KiB of it (was 8 KiB). Code that parsed a longer upstream error body out of the excerpt sees a
> shorter string. The body is never logged or put in an error message.

### Changed (callback errors, qfg-6vno.28)

- **A throwing user callback is no longer counted as a worker restart.** The
  internal `quonfig_sdk_worker_restart_total{layer="1"}` counted every
  `onConfigUpdate` throw the SDK caught; it now counts only real SSE worker
  restarts. Caught throws are counted in a new internal
  `quonfig_sdk_callback_errors_total{callback}` (`on_config_update`,
  `on_connection_state_change`). Both counters are test seams, not public API
  (supervisor contract Test 5, integration-test-data `v2026.10.22.2`).
- **An `onSseConnectionStateChange` listener that throws is now logged** at
  ERROR with its stack trace (`quonfig: onConnectionStateChange callback threw;
  SDK continuing`). Before, the throw was swallowed silently. The other
  listeners still run, as before.
- Both callback paths now catch `Exception`, not only `RuntimeException`, so a
  sneaky-thrown checked exception is isolated too. An `Error` still propagates.

### Changed (size caps, qfg-6vno.20.2)

- **SSE events are capped at 4 MiB (4,194,304 bytes).** The stream reader stops
  reading at the cap, even for a line with no terminator, and counts every line
  of an event, so a joined multi-line `data:` is capped too. Before, one huge or
  unterminated event was buffered whole and could throw `OutOfMemoryError`, and
  every reconnect re-read it. An event over the cap is dropped and the
  connection counts as a failed attempt (backoff grows). Later connections that
  deliver only that event, with or without keepalives, do not report
  `connected`, so the fallback poller keeps the config fresh over HTTP. The
  next valid envelope ends the episode. One WARN names the stream host and the
  cap per episode; repeats log at DEBUG.
- **Config response bodies are capped at 16 MiB (16,777,216 bytes).** A 2xx
  whose `Content-Length` is over the cap fails before the body is read;
  otherwise the read stops at cap + 1. The leg fails exactly like a 5xx: the
  walk and the init hedge fail over, nothing installs, and
  `lastSuccessfulRefresh()` does not advance. One WARN per leg per episode;
  repeats log at DEBUG.
- **Non-2xx bodies are read at most 1 KiB** (SSE and HTTP) and are never logged
  or put in an error message. Before, the HTTP transport read the whole body
  and then kept 8 KiB of it.
- `HttpTransportException.bodyExcerpt()` now holds at most 1 KiB of the body
  (was 8 KiB). See the UPGRADE note above.
- The caps are constants, not options (supervisor contract "Rule — size caps"
  and Test 10, integration-test-data `v2026.10.22.2`).

### Fixed

- A boot the secondary delivery leg rescues (the primary refuses, the hedge
  installs off the secondary inside `initTimeout`) no longer logs a spurious
  `[quonfig] init recovered` WARN. The winning leg read "init decided" (also
  true on success) instead of "init decided on failure", so a healthy boot
  could log the heal line. Surfaced by chaos scenario f01 (qfg-6vno.3).
- An HTTP 304 to the first config fetch, with no config installed yet, is now
  a failed delivery leg. Init sends no `If-None-Match`, so only a misbehaving
  proxy answers 304 there. Before, the 304 completed `initFuture()`
  successfully and stopped the init retry loop with nothing loaded, so a
  `CONTINUE` client stayed on code defaults until SSE delivered a config. Now
  init retries and heals like any other failed attempt (qfg-6vno.11).

## 2.0.0 - 2026-10-21

### BREAKING (2.0.0)

> **BREAKING: the constructor now blocks until the first config loads, and throws
> `QuonfigInitException` when none can (qfg-9p4i.9).** Before, `new Quonfig(options)` returned at
> once in SDK-key mode and, when delivery was down, the client silently ran on your code defaults
> (`Reason.ERROR`). That instance then ran a different config from the rest of your fleet. Quonfig
> now fails by default, as the other server SDKs do.
>
> - **New option `Options.builder().onInitFailure(OnInitFailure)`**, default `FAIL`. In every mode
>   the constructor first blocks and retries through blips (jittered backoff, 0.5 s doubling) until
>   `initTimeout` (default 10 s, unchanged). A healthy boot returns as soon as the first config
>   lands, typically 100-300 ms.
>   - `FAIL`: at `initTimeout`, throw `QuonfigInitException`. When every delivery leg answers
>     401/403 it throws at once, without waiting out the timeout. `QuonfigInitTimeoutException` is
>     thrown when no attempt finished in time (legs hung).
>   - `WAIT`: keep blocking, with no upper bound, until a config loads. Pair it with a readiness
>     probe.
>   - `CONTINUE`: return at `initTimeout` with no config, the old fail-open behavior. Set this to
>     keep 1.x behavior.
> - **`QuonfigInitException`** (new, `extends RuntimeException`) has `reason()`
>   (`UNAVAILABLE`, `UNAUTHORIZED`, `TIMEOUT`) and `client()`. **`QuonfigInitTimeoutException`
>   is no longer `final`** and now extends it. Existing `catch (QuonfigInitTimeoutException e)`
>   blocks still compile and match.
> - **No dead clients.** The client a failed constructor built keeps retrying in the background.
>   `e.client()` returns it, and it becomes ready when delivery comes back. Keep it, or `close()`
>   it if you don't. A process that lets the exception escape is unaffected (daemon threads).
> - **Getters never block on init.** Before the first config loads, they return the caller's
>   default with `Reason.ERROR` and the new `ErrorCode.PROVIDER_NOT_READY` (was
>   `ErrorCode.GENERAL` after waiting up to `initTimeout`). `get*OrThrow` throws
>   `QuonfigKeyNotFoundException` (was `QuonfigInitTimeoutException` after the wait). `keys()`
>   returns an empty set.
> - **Readiness API:** `isReady()` and `awaitReady(Duration)`. `initFuture()` is kept. It completes
>   exceptionally with the `QuonfigInitException` when the constructor gives up (`FAIL` or
>   `CONTINUE`), and a completed future replaces it on heal.
> - **Logging contract (design section C):** DEBUG on init start. One WARN on the first failed
>   attempt, then a progress WARN every 5 s until `initTimeout`. An ERROR when `FAIL` raises or
>   `CONTINUE` starts on code defaults. Under `WAIT`, a WARN at the timeout and every 30 s after.
>   The recovery line is a WARN. Lines carry the `[quonfig]` prefix and leg hosts, never the SDK
>   key. A healthy boot logs nothing above DEBUG.
>
> **To upgrade:** wrap construction if you want to handle a down delivery path, or set
> `.onInitFailure(OnInitFailure.CONTINUE)` to keep 1.x fail-open behavior. Datadir and datafile
> modes are unchanged; they load synchronously. sdk-java had no legacy `onInitFailure` spellings,
> so there are no deprecated aliases.

### Changed (init-failure conformance, qfg-9p4i.17.3)

- **404 and 410 now count as a rejected SDK key at init**, like 401 and 403. When every delivery
  leg answers one of them, `FAIL` throws `QuonfigInitException` (reason `UNAUTHORIZED`) at once
  and `CONTINUE` returns at once. One leg alone never decides it.
- **`initTimeout` of zero or less runs exactly one init attempt**, then applies `onInitFailure`.
  Before, it decided before any attempt ran (`FAIL` threw "no attempt finished"), and every later
  background fetch was bounded by the same zero timeout, so the client never healed.
- **Init log lines and the `QuonfigInitException` message no longer carry URL credentials or the
  request URL.** The first attempt's errors read `primary=<host:port>: HTTP 503` (or `timed out`),
  like later attempts. Transport error messages strip `user:pass@` from the URL.
- **SSE and the fallback poller start at the init decision** under `CONTINUE` and for a caught
  `FAIL` (the client in `e.client()`), not only once the background retry heals. A config that
  arrives over SSE, the fallback poller or `refresh()` heals the client at once.
- **Init log lines carry an `event=quonfig.init.<name>` SLF4J key-value** (SLF4J 2 fluent API; no
  new dependency). When an app pins slf4j-api 1.x at runtime, they fall back to the classic calls
  with an `event=...` message prefix instead of failing construction. The recovery line names
  `stream` when SSE delivered the first config.
- README documents the init contract (`onInitFailure`, `initTimeout`, `QuonfigInitException`,
  readiness, closing a caught client).
- **When `initTimeout` cuts off an attempt that is still running, that attempt decides the
  reason** (design doc decision 10, the sdk-go rule). If none of its legs has answered, it hung
  and the reason is `TIMEOUT`, even when earlier attempts failed fast. If one leg already failed
  fast while another still hangs, the reason is `UNAVAILABLE`. Before, the reason came from the
  last attempt that had finished. A deadline that lands between attempts still uses the last
  finished attempt.
- **A finished retry attempt whose primary answered is no longer reported as `TIMEOUT`
  (qfg-9p4i.17.9, decision 7).** When the deadline fell in the backoff after a retry where the
  primary answered (for example 401 or 503) and the secondary timed out, the reason was
  `TIMEOUT`, because the error of the failover walk wraps the secondary's timeout. The reason now
  comes from each leg's own result: `TIMEOUT` only when every leg timed out, else `UNAVAILABLE`.
- **Each hedged init leg is bounded by `configFetchHedgeAbort` (default 6s), as documented.**
  Before, the JDK request timeout `configFetchTimeout` (default 3s) also bounded it, so a primary
  slower than 3s was cut off during init. `configFetchTimeout` still bounds the sequential
  retries, `refresh()` and the fallback poller.

### Fixed

- **A total init failure no longer leaves a permanently dead client (qfg-9p4i.4, patch).** When
  every leg failed at boot (refused, DNS, 5xx, or a rejected key), the client completed its init
  future exceptionally and stopped: nothing retried, SSE never started, nothing was logged, and
  every getter returned the caller's default with `Reason.ERROR` until the process restarted, even
  after `refresh()` installed a config (while `ready()` reported `true`). Now:
  - The failure is logged at ERROR with each leg's host and error
    (`[quonfig] init FAILED: ...`; "SDK key rejected" for 401/403/404/410). The SDK key is never
    logged.
  - A background thread keeps fetching with jittered backoff (0.5 s doubling, capped at 30 s) and
    logs a WARN every 30 s for 5 minutes, then every 5 minutes, while still not connected.
  - The first config that installs, from that retry or from `refresh()`, makes the getters serve
    it. The retry then logs `[quonfig] init recovered: ...` at WARN, fires the config-update
    callback, and starts SSE plus the fallback poller as a successful first fetch does.
    `initFuture()` returns a completed future from then on. `close()` stops the retry.
  - (Superseded in the same release by the 2.0.0 change above: the constructor now blocks, and
    `FAIL` is the default.)

## 1.8.0 - 2026-10-20

### Upgrade notes

> **UPGRADE: SSE reports `connected` at the first frame, not at the 200 response (qfg-goi1.10.9).**
> A stream connection now counts as connected, for `connectionState()`, the
> `onSseConnectionStateChange` listeners, the reconnect backoff and the fallback poller, only after
> it delivers its first valid frame: a config envelope or a keepalive. Before, it counted as soon as
> the 200 response headers arrived.
>
> - Against Quonfig's servers the headers and the first frame arrive together, so you should see
>   no difference.
> - Behind a proxy or server that sends 200 headers early, `connected` is reported when the first
>   frame arrives: at most one keepalive interval (30 s) later.
> - A connection that closes before any frame is now a failed attempt. The reconnect delay grows,
>   the client reports disconnected, and the fallback poller takes over after its usual threshold.
>
> Before this change, a server that answered 200 and closed at once made the SDK reconnect in a
> tight loop and keep stale config indefinitely.

### Changed

- **SSE is connected at its first valid frame (qfg-goi1.10.42, minor).** The connected edge, the
  backoff reset and the fallback poller's engage-timer cancel now happen at the first complete
  `: keepalive` block or validated envelope on a connection, before that envelope is installed,
  instead of at the 200 headers. A 200 that ends (EOF, IO error or read watchdog) before any frame
  is a failed attempt and doubles the reconnect delay; an IO error after a frame still resets it.
  Headers alone, a block cut off by EOF, malformed or non-envelope data and an `id:`-only block are
  not frames. In HTTP+SSE mode `connectionState()` also stays `INITIALIZING` in the short window
  between the init fetch and the start of the stream, where it briefly reported `CONNECTED`.

### Fixed

- **Weighted rollout variants keep their type (qfg-lrlh, patch).** A variant inside a
  `weighted_values` rollout is now parsed with the same typed decoder as a top-level value. Before,
  only int, double, bool and string_list variants were typed; a json variant came back from
  `getJson` as its `toString()` text (`"{a=1, b=c}"`), a provided (`ENV_VAR`) variant never read its
  environment variable (`getInt` returned the default with `TYPE_MISMATCH`), and log_level variants
  were typed as plain strings. Duration variants already worked through the config-type coercion.
  Matches sdk-go, which decodes each variant as a full `Value`.
- **A `getDuration` evaluation no longer drops the telemetry window (qfg-a996, patch).** The
  evaluation summary held the `java.time.Duration`, which the telemetry serializer cannot write, so
  the whole window (summaries, context shapes, examples, failover) was dropped with a WARN. The
  summary now reports the stored ISO-8601 string as `{"string": "PT90S"}`, as sdk-go does.

### Tests

- integration-test-data pin bumped to v2026.10.16.4 (supervisor contract Test 9 and the Test 2
  reset amendment; `get_or_raise` init-timeout cases now dial `http://192.0.2.1:1`).

## 1.7.0 - 2026-10-16

### Upgrade notes

> **UPGRADE: auth-failure rule (qfg-goi1.10.27, minor).** Retry cadence and log levels change
> after a rejected 401/403/404/410 fetch cycle; a revoked key logs one config ERROR.

> **UPGRADE: rule operator semantics (qfg-goi1.5.8, minor).** Aligns sdk-java with the canonical
> cross-SDK operator rules pinned in integration-test-data v2026.10.16.1. Two rules change who
> matches; check targeting that relies on them:
>
> - **Abbreviated semver now matches.** `PROP_SEMVER_*` read `"2"` as `2.0.0` and `"2.0"` as
>   `2.0.0` (context value and criterion). Before, an abbreviated version was invalid and the rule
>   never matched, so a user on app version `"4.1"` now matches `PROP_SEMVER_GREATER_THAN 4.0.18`.
> - **Whole-number floats stringify as plain digits.** A `Double`/`Float`/`BigDecimal` context value
>   with an integral value is compared as `"1"`, `"1234567"`, `"5000000000"` by `*_ONE_OF`,
>   starts/ends/contains and `HIERARCHICAL_MATCH`, so `PROP_IS_ONE_OF ["1234567"]` now matches
>   `1234567.0`, and lists holding `"1.0"` or exponent strings (`"5.0E9"`) stop matching.
>   Booleans stay `"true"`/`"false"`.
>
> Both who-matches changes were approved as minors under Decision 1 (qfg-goi1.5, 2026-10-09; prod
> corpus scan: 0 affected criteria).
>
> Dates are unchanged in sdk-java but are now strict in every SDK: pass dates to `PROP_BEFORE` /
> `PROP_AFTER` as epoch milliseconds or as ISO 8601 / RFC 3339 strings with `Z` or a numeric offset
> (date-only and offset-less strings never match).

> **UPGRADE: may change returned values (qfg-goi1.10.8, minor).** `getStringList` and `getJson`
> return a copy per call; see Changed below.

### Changed

- **A rejected SDK key slows down instead of retrying at the normal cadence (auth-failure rule,
  qfg-goi1.10.27, minor).** When a Layer 2 fallback-poll cycle gets no 200/304 on any leg and the
  primary leg answered 401, 403, 404 or 410, the client enters an internal auth-failed regime: SSE
  is stopped, the fallback poller stops, and the client sends one fetch probe across every leg
  every 150-300s (uniform, no doubling, never giving up). It logs one ERROR naming the status and
  the primary host (never the key) on entry, DEBUG per probe, and one INFO on exit. The first probe
  that gets a valid 200 or a 304 on any leg installs it, leaves auth-failed and restarts SSE from
  its base backoff. `connectionState()` reports `DISCONNECTED` while auth-failed (never
  `FALLING_BACK`); there is no new state or getter. A primary 503 with a secondary 401, a 200 on
  any leg, an SSE 401 on its own, the init fetch and `refresh()` never enter; SSE behavior before
  entry is unchanged. `HttpTransportException` now carries each leg's status
  (`legStatuses()`, `primaryStatus()`), since a failover walk used to report only the last leg's.
  Pinned by supervisor contract Test 8 (integration-test-data v2026.10.16,
  `SupervisorAuthFailureTest`). Before: the poller kept fetching every 60s in `FALLING_BACK`
  forever with SSE retrying alongside, logging only DEBUG.

- **Integral floats as plain digits; abbreviated semver (qfg-goi1.5.8, minor).** See the UPGRADE
  note above. Also fixes `IN_INT_RANGE` criteria stored as `{"type": "int_range", ...}`: the
  loader did not know the `int_range` value type, dropped the bounds and matched every value; the
  range is now `[start, end)` as specified.

### Changed (getStringList / getJson copies)

- **`getStringList` and `getJson` return a deep copy, not the cache's own object (qfg-goi1.10.8,
  minor).** The plain, `*Details` and `*OrThrow` forms (and `BoundQuonfig`) used to return the
  list or map held in the config cache, so a caller that mutated the result changed what every
  later read returned until the next config update. Each call now returns its own deep copy: maps
  are `LinkedHashMap` and lists `ArrayList` (mutable, as before for JSON; a `STRING_LIST` from a
  provided `ENV_VAR` value used to be unmodifiable and is now mutable too), so existing mutating
  callers keep working. Two calls no longer return the same instance (`==` is false); compare with
  `equals`. Matches sdk-go (543ee9c) and sdk-net (90483e3).

### Fixed

- **A confidential or `decryptWith` weighted rollout variant is redacted in telemetry and decrypted
  for the caller (qfg-goi1.10.19, patch).** The weighted resolver dropped the selected variant's
  `confidential`/`decryptWith` flags, so a confidential variant was sent to telemetry as
  `selectedValue` in plaintext, and a `decryptWith` variant returned its raw ciphertext to the
  caller (and sent that ciphertext to telemetry). The selected variant now carries its flags, so it
  is decrypted and telemetry reports `*****<hash>`. Matches sdk-go (`reportableValueFor` on the
  selected variant) and sdk-ruby (db98dd9).

- **Telemetry example contexts are copied when recorded, and one odd value no longer drops the
  window (qfg-goi1.2.38, patch).** With `contextUploadMode` `PERIODIC_EXAMPLE`, the example
  collector kept the caller's own context values and serialized them later on the telemetry
  thread, so a caller that mutated a nested map or list after the evaluation changed the recorded
  example or failed the flush, and one value a plain Jackson `ObjectMapper` cannot write (an
  `Instant`, say) dropped the whole telemetry window, at DEBUG. The collector now takes a
  JSON-safe deep copy at record time:
  - kept as is: `null`, `String`, `Boolean`, boxed integer and floating types, `BigInteger`,
    `BigDecimal`;
  - rendered to a string: `UUID`, `URI`, `Character` and other `CharSequence`s, enums (by name),
    `java.time` values (`Instant`, `LocalDate`, `OffsetDateTime`, `Duration`, `ZoneId`, ...;
    ISO-8601) and `java.util.Date` (as its `Instant`);
  - copied: maps (keys by `String.valueOf`), collections, object and primitive arrays (as lists);
  - dropped (the entry is left out, the rest of the example is kept): every other type
    (application objects, `Optional`, atomics), a container that contains itself (cut at the edge
    that closes the cycle) and containers nested more than 32 deep. A `key` or `trackingId` of a
    dropped type is rendered with `String.valueOf`, as grouping already did, so the example is
    never lost to its key.

  A telemetry window that still cannot be serialized is now logged once at WARN (DEBUG after).
  Matches sdk-go 1.6.0 (qfg-goi1.2.4, qfg-goi1.2.44, qfg-goi1.2.46).

- **The SSE thread no longer dies for good on an `Error` (qfg-goi1.10.7, patch).** The SSE loop
  only handled exceptions, so an `Error` on the SSE thread (for example an `OutOfMemoryError` from
  an unterminated event, or one thrown by the envelope handler) ended the loop and the thread, and
  config updates fell back to the polling interval for the life of the process. The loop now
  catches it per connection, logs it at ERROR, backs off and reconnects.

- **An `ENV_VAR` coercion error no longer contains the environment variable's value
  (qfg-goi1.2.32, patch).** When a provided `ENV_VAR` value could not be converted to the config's
  type, the `ResolverException` message (and so the `get*OrThrow` exception and the
  `EvaluationDetails` error message) quoted the raw value and the parser's message, which quotes
  it too, so a secret such as a database URL could reach logs. The message is now
  `environment variable "<NAME>" cannot be converted to <type> for config "<key>"`, with `<type>`
  the lowercase config-file type name (`int`, `double`, `bool`, `json`, `duration`), matching
  sdk-go and sdk-net. The parser exception is no longer chained as the cause, because its message
  quotes the value.

## 1.6.0 - 2026-10-09

### Changed

- **One malformed config row no longer drops the whole delivery envelope (qfg-goi1.2.16).** The
  HTTP init fetch, `refresh()`, the fallback poller and SSE now parse the envelope row by row. A
  row that fails to parse (for example one missing `type`) is skipped with a WARN naming its key
  and the envelope version, and every other row installs. The skipped key is not kept at its last
  good value: it is absent from the installed set, so lookups of it return the caller's default
  (`FLAG_NOT_FOUND`) until a later envelope carries a good row. Before, one bad row aborted the
  install, so every other config's update was lost (and on SSE, silently). If every row fails, the
  envelope is still rejected, so it can never install as an empty workspace. Matches sdk-go
  v1.4.0 (qfg-9dxb.6). Datadir and datafile loading do not skip rows: one bad row still fails the
  load, as before (only the exception changes, see below).
- **A rejected SSE envelope is logged at WARN (qfg-goi1.2.16).** The SSE handler used to swallow
  an install failure with no log line; it now logs `quonfig: SSE envelope rejected: ...` and keeps
  the held envelope.
- **`getLogLevel` returns empty while init is in flight, so the logging filters no longer block
  at startup (qfg-goi1.2.16).** In HTTP mode the constructor returns before the first config fetch
  completes. `getLogLevel` used to wait on that fetch like the typed getters (up to `initTimeout`,
  10s by default), and the Logback and Log4j2 filters call it on every log statement, so every
  thread that logged during startup stalled. It now returns `Optional.empty()` until init
  completes, which both filters map to NEUTRAL: log calls in that window follow the logging
  library's own levels, and Quonfig's levels apply once init completes. `shouldLog` and the typed
  getters are unchanged.
- **The SSE request times out waiting for response headers (qfg-goi1.2.16).** A server or proxy
  that accepted the connection and never answered used to pin the SSE thread forever, because the
  read watchdog only starts once headers arrive: the fallback poller took over after 120s, but SSE
  never came back until restart. The header wait is now bounded at 90s, after which the loop
  reconnects with backoff. It is not shorter because api-delivery sends headers with the first
  event, which on a cold workspace is the 30s heartbeat. It is a fixed internal value, not derived
  from `sseReadWatchdog`: latency adds to the wait for headers but not to the gaps between stream
  bytes, so a header bound equal to a short read watchdog stopped a slow but live stream from ever
  connecting (every attempt timed out at the watchdog window and reconnected; qfg-rriw). Only the
  header wait is bounded (the SDK waits on the async send, and does not use
  `HttpRequest.timeout`, which from JDK 26 also covers the response body), so a healthy
  long-lived stream is not cut on any JDK; the read watchdog alone governs the body. Matches
  sdk-go's separate `HeaderTimeout` (qfg-d1o9).
- **`close()` during init no longer leaves the SSE loop or fallback poller running
  (qfg-goi1.2.16).** If `close()` ran just as init was starting SSE, it could miss the SSE client
  and supervisor that were being created, and both then ran forever against a closed client
  (most likely with short-lived clients, tests and failed Spring context refreshes). `startSse()` now
  re-checks `closed` after creating them and stops both.
- **A config missing a required field fails with a message naming the field and the key
  (qfg-goi1.2.16).** `DatadirLoader.parseConfigNode` throws `IllegalArgumentException`
  (`config "flag.b" is missing required field "type"`) instead of a bare `NullPointerException`
  for an absent `key`, `type`, `valueType` or criterion `operator`. That message is what the skip
  WARN and a datadir reload WARN print.

### Internal

- **Chaos: `server_metric(...)` is SKIPPED with a reason instead of stubbed to 0
  (qfg-goi1.1.6).** api-delivery exports metrics via OTLP push only and the chaos rig has no
  scrape endpoint, so the harness cannot observe `quonfig_subscriber_lag_seconds`. It used to
  compare against a hardcoded 0, so `== 0` expectations passed without observing anything. Now
  each such expectation prints `SKIP` with the reason (server lag is covered by the staging drill
  qfg-47c2.19 and the `QuonfigSubscriberLagHigh` alert), a skipped leaf inside an `AND`/`OR` is
  neutral while the other leaves are still enforced, and the run ends with a "chaos skipped
  expressions" tally.
- **Chaos: an unknown `client.sdkMetric(...)` name fails loudly (qfg-goi1.1.6).** `ChaosProbe`
  returned 0 for any metric it does not observe; it now throws `IllegalArgumentException` naming
  the metric.
- **Chaos and failover-chaos run at integration-test-data `v2026.10.03` (qfg-goi1.1.6).** Both
  were pinned behind the unit pin (`v2026.05.13` and `v2026.06.19.1`). The unit suite has since
  moved to `v2026.10.07` (qfg-goi1.3: `featureIsOn` on a missing flag returns false; sdk-java
  already complied, so only the generated `EnabledTest` changed). The `v2026.10.03` tag adds a
  60s freshness hold to `05-sse-down` (qfg-e3ja). api-delivery still tracks `main` on purpose, and
  both workflows now print the resolved api-delivery commit in the log and the run summary.
- **Publish is gated on the unit tests (qfg-goi1.1.6).** `publish.yaml` now calls `test.yaml` on
  the tagged commit (`workflow_call`) and the Maven Central publish job `needs: test`, matching
  sdk-ruby and sdk-net. Before, a tag published even if Test was red on that commit. Chaos still
  runs on the tag but does not gate publish.

## 1.5.0 - 2026-10-02

### Added

- **`get*OrThrow` getters: the SDK's get_or_raise (qfg-2agi.27).** `Quonfig` and `BoundQuonfig`
  gain `getStringOrThrow`, `getBoolOrThrow`, `getLongOrThrow`, `getDoubleOrThrow`,
  `getStringListOrThrow`, `getDurationOrThrow` and `getJsonOrThrow`, each with an optional
  `ContextSet`. They return the value, or throw instead of falling back to a default: a missing key
  or no matching value throws `QuonfigKeyNotFoundException`, as does a value that cannot be coerced
  (an ENV_VAR `"not_a_number"` for an INT, or a malformed duration, stored or ENV_VAR); an unset
  env var throws `QuonfigEnvVarNotSetException`; a decryption failure throws
  `QuonfigDecryptionException`; an init timeout throws `QuonfigInitTimeoutException`; a value-type
  mismatch throws `IllegalArgumentException`. The exception classes existed before, but no SDK code
  threw them. The existing getters are unchanged.

### Fixed

- **A malformed duration no longer throws from the typed getters (qfg-2agi.8).** When a duration
  config holds a value that is not a valid ISO 8601 duration (for example `30s`, `PT0.5H` or
  `P1DT`), `getDuration` and `getDurationDetails` now return the caller's default (or `null`) with
  `Reason.ERROR`, and the SDK logs one warning per key. Before, a stored malformed value threw a
  `DateTimeParseException`. A malformed value from an environment variable already returned the
  default; it now logs the same warning.
- **Durations round half up to whole milliseconds (qfg-2agi.8).** `getDuration` now returns a
  whole number of milliseconds, rounded half up from the exact value: `PT0.0005S` is 1 ms and
  `PT0.999999999S` is 1000 ms. Before, `Duration#toMillis` truncated them to 0 ms and 999 ms.
- **Datadir mode rejects an unknown environment at init (qfg-2agi.19).** `new Quonfig(...)` with
  `datadir(...)` and an `environment(...)` not declared in the workspace's `quonfig.json` now
  throws `IllegalStateException` naming the available environments. Before, the client built fine
  and served default-row values. A workspace whose `quonfig.json` declares no environments still
  accepts any name.

### Internal

- **Tests pin the context-merge rule (qfg-2agi.38).** New `ContextMergeRuleTest` checks that a
  newer tier's named context replaces the whole same-named context and that named contexts it does
  not mention survive, for global + per-call, global + `withContext`, `withContext` + per-call, all
  three tiers together, and the injected dev-context `quonfig-user` under a customer global context.
  Each test uses disjoint attributes, so a property-level merge would fail it. No behaviour change.
- **The cross-SDK integration suite runs through the public API (qfg-2agi.30).** The generated
  `com.quonfig.sdk.integration` tests now call `Quonfig` / `BoundQuonfig` exactly as a customer
  does: the YAML `type:` picks the typed getter, `enabled` calls `featureIsOn`, and `get_or_raise`
  calls the `get*OrThrow` getters. Context tiers go through `Options.globalContext`,
  `withContext` and the per-call `ContextSet` instead of being merged by the generator, and
  telemetry cases assert the payload a real client sends on `flush()`. `TestSetup` no longer
  evaluates, resolves or throws on the SDK's behalf. Planting any of seven bugs in the SDK (duration
  getter returns 0, `featureIsOn` always false, typed getter ignores the default, global context
  ignored, telemetry reason changed, redaction dropped, `get*OrThrow` never throws) now turns the
  suite red. One case is marked `@Disabled` because sdk-java has no `on_init_failure` option.
- **Integration get_or_raise cases go through the public client (qfg-2agi.27).** The test
  harness's `runRaiseCase` now calls the public `get*OrThrow` getter for the config's type and lets
  the SDK throw. Before, the harness evaluated the key itself and threw the expected exception class
  on the SDK's behalf.
- **Integration DURATION cases now go through the public getter (qfg-2agi.4).** The generated
  cross-SDK tests assert every DURATION case via `Quonfig#getDuration` and
  `Quonfig#getDurationDetails` with exact millisecond comparison. The test-only ISO-8601 parser and
  the +/-1ms tolerance are gone. `PT0.5H` and `PT1.5M` (fractions outside the seconds field) are
  invalid under the shared duration grammar: the corpus now expects `PT30M` as a valid value and
  treats `PT0.5H` as malformed, and every DURATION case passes.

## 1.4.0 - 2026-09-28

### Changed

- **A weighted rollout that hashes on a missing property now hashes an empty value
  (qfg-9dxb.8).** When a weighted rollout hashes on a property that is missing from the context
  (or is null), the SDK now hashes an empty value, so every such caller gets the same variant for
  that flag: the variant a present empty string gets. In 1.3.0 such callers always got the first
  variant, even one with weight 0, and a null value was hashed as the text `"null"`.
  `EvaluationDetails.metadata()` reports `hashPropertyMissing: true` for these evaluations (not
  for a present empty string), the reason stays `SPLIT`, and the SDK logs one warning per flag per
  client. Results for a present, non-empty value are unchanged.

### Fixed

- **A weighted rollout with no hash property picks a random variant again (qfg-t9wo).** A weighted
  rollout with no hash property configured now picks a random variant on every evaluation,
  weighted by the weights. In 1.3.0 it always served the first variant, so a 10/90 split served
  the first variant 100% of the time.
- **A gen-0 payload no longer rolls config back (qfg-9dxb.9, qfg-9dxb.3).** Once the client has
  config from the server, a response with no generation number (sent only by a server with a
  damaged git store, which repairs itself) is ignored instead of possibly rolling config back.
  Clients that have only seen `qfg serve` still take every update. Such a response also no longer
  lowers `heldGeneration()`, and it is not counted as `guardRejected`. The trade-off: a change
  that arrives only in a gen-0 payload is applied from the next response that carries a higher
  generation.
- **A segment or encryption key that refers back to itself no longer crashes evaluation
  (qfg-9dxb.7).** A segment that includes itself through `IN_SEG` / `NOT_IN_SEG` (directly or via
  another segment), or a `decryptWith` key chain that loops, used to recurse until a
  `StackOverflowError` escaped the SDK. A looping segment reference is now treated like a missing
  segment (`IN_SEG` is false, `NOT_IN_SEG` is true), and a looping `decryptWith` chain fails like
  any other decryption error. This matches sdk-go.
- **New fields from the server no longer stop config updates (qfg-2nvg).** If the delivery
  server added a new field to the config response (at the top level or in `meta`), the Java SDK
  failed to parse it on both HTTP and SSE and stayed on its current config. Unknown fields are now
  tolerated in server payloads and datafiles. Error bodies such as `{"error":"x"}` from the server
  are still rejected, by the new envelope check below. A datafile that is not a config file (for
  example `{"error":"x"}` or a single per-config file) still fails at startup, as in 1.3.0.
- **Non-envelope delivery payloads are rejected (qfg-9dxb.3).** A 200 (or SSE event) whose body is
  not a config envelope (no `meta` object with a non-empty `version`) is now rejected by a new
  envelope check. Before, a body such as `{}` or `{"configs":[]}` decoded to an empty envelope and
  wiped every held config. It is now a leg error on HTTP: the init hedge fires the secondary and
  `refresh()` / the fallback poller fail over to the next URL. On SSE the event is dropped like
  malformed JSON. `qfg serve` payloads (version + environment, no generation) still install.

### Added

- `ConfigEnvelope.isDeliveryEnvelope()` and an `HttpTransport.get(URI, String, Predicate<String>)`
  overload that treats a rejected 2xx body as a leg error.
- `EvaluationDetails.metadata()` key `hashPropertyMissing` (see Changed above). Custom
  `WeightedValueResolver` implementations can report a missing hash property through the new
  `Resolved(Value, int, String)` constructor and `Resolved.missingHashProperty()`;
  `EvaluationMatch.missingHashProperty()` is also new. The existing `Resolved(Value, int)`
  constructor still works and reports nothing missing.

## 1.3.0 - 2026-09-25

### Changed

- **Telemetry transport policy (qfg-y8je.9).** The telemetry POST timeout goes from 30s to 15s
  (`telemetryTimeout`), plus a 5s connect/TLS timeout (`telemetryConnectTimeout`, was 10s). A failed
  batch is kept byte-for-byte and resent (no merging, so the server dedups a resend of a batch that
  did land); before, it was dropped. Resends happen no sooner than 30s after a failure and honor
  `Retry-After` up to 10 min. The retained queue is capped at 5 batches / 2MB / 5 min (oldest
  dropped; a batch larger than the byte cap is never kept). At most one POST is in flight.
  401/403/404 disable telemetry for the process with one ERROR; any other 4xx drops that batch with
  one ERROR. Logging: a failed POST logs at DEBUG, one WARN when data is actually dropped (then a
  summary at most every 10 min), one INFO on recovery. Before this, every failed POST logged a WARN.
  Telemetry now logs through the `logger(...)` option (default `com.quonfig.sdk`), like the rest of
  the SDK.
- **Fixed 60s cadence; the adaptive backoff is removed.** `telemetryMaxInterval` no longer has any
  effect (the builder method and getter are kept, deprecated, so existing code compiles). The first
  tick now fires after one flush interval instead of a separate 8s delay; an explicit
  `telemetryInitialDelay` is still honored.
- **`flush()`** sends the live window and waits for the POST (bounded by the timeout); after a
  failure it respects the 30s floor and `Retry-After`, and it no longer throws
  `IllegalStateException` on a failed POST (the batch is kept and resent).
- **`close()`** sends the live window once with a 5s deadline, does not resend retained batches,
  and stops the telemetry thread.
- **Memory caps:** context-shape fields (before: context names only) are capped at 10,000 per
  window alongside evaluation-summary keys and example contexts, and the example-context rate-limit
  map at 100,000.

### Fixed

- Once the example-context rate-limit map was full of recent keys, every flag evaluation with a new
  context key scanned all 100,000 entries under a lock. Expired keys are now pruned oldest-first,
  so a full map rejects a new key without a scan.

### Added

- Options: `telemetryTimeout`, `telemetryConnectTimeout`, `telemetryMaxRetainedBatches`,
  `telemetryMaxRetainedBytes`, `telemetryMaxRetainedAge`, `telemetryMaxEvaluationSummaries`,
  `telemetryMaxContextShapeFields`, `telemetryMaxExampleContexts`, and the test seams
  `telemetryClock` / `telemetryScheduler`. Invalid values (null, zero, negative) fall back to the
  default. `HttpTelemetrySender` gains a `(url, sdkKey, timeout, connectTimeout)` constructor and
  `TelemetryReporter` an `Options`-based constructor; its old constructors,
  `flushAndApplyBackoff()` and `currentInterval()` are deprecated.
- `contextUploadMode` default is unchanged (`PERIODIC_EXAMPLE`). No wire change, no removed public
  API, no new dependencies.

### Fixed

- **`X-Quonfig-SDK-Version` now reports the real SDK version (qfg-y8je.2).** Telemetry POSTs
  sent the literal `java-0.0.1`, and config delivery / SSE requests sent `java-0.0.1-SNAPSHOT`
  because the published jar's manifest carries no `Implementation-Version`. The version is now
  stamped into a `com/quonfig/sdk/sdk-version.properties` classpath resource at build time and
  read by `Version`, so every request carries e.g. `java-1.2.1`.

## 1.2.1 - 2026-09-14

### Changed

- **`guardRejected` telemetry now counts only STRICTLY older payloads (qfg-q18o; cross-SDK
  decision qfg-rr5b).** The reject-older install guard's *behavior* is unchanged — an
  equal-or-older envelope is still dropped, still does not install, and still stamps
  `lastSuccessfulRefresh()` exactly where it did before. What changed is the accounting:
  only an incoming generation **strictly less than** the held generation is counted as
  `guardRejected`. An equal-generation re-delivery is now a silent no-op (it logs a DEBUG
  line and counts nothing).

  Why: `guardRejected` exists to say "a leg tried to move us backwards" — that is what the
  `sdk_failover` dashboard panel reads it as. But two perfectly healthy server behaviors
  re-deliver the envelope the client already holds at the same generation: api-delivery
  re-sends the current envelope on every SSE connect (this SDK sends no `Last-Event-Id`, so
  every reconnect scored one), and a config poll answers with a full 200 whenever the per-leg
  ETag slot is empty (a fresh transport, a reconnect, the fallback poller's engage-time
  fetch). Those drops were counted as failover events, so the field reported ordering trouble
  on clients that had none.

  **Operator note — the field now reads LOWER than on 1.2.0 for identical traffic.** If you
  chart `guardRejected`, expect a step down after upgrading: steady-state clients that
  previously reported a non-zero count from SSE reconnects and cold-ETag polls alone will
  report 0. A non-zero value now means what it was always supposed to mean: a leg actually
  served an older generation than the one held. This is the Java half of a change landing in
  all six backend SDKs (sdk-ruby 1.4.1 and sdk-python 1.4.1 shipped it first; Go, .NET and
  Node follow), so counts may differ across languages until then.

  No wire, ClickHouse, or dashboard-query change — the field shape is identical and only
  becomes accurate. The unversioned carve-out (`generation` absent or `<= 0`, which installs
  rather than being rejected) is untouched.

### Fixed

- README install snippets pinned `1.0.0`; they now pin the current release.

## 1.2.0 - 2026-07-08

### Added

- **Warning when an explicit `apiUrls` disables failover (qfg-41nh.26; sdk-go parity).**
  The default (and every `QUONFIG_DOMAIN`-derived) API-URL list carries a primary and a
  secondary leg, and the SDK hedges/fails over between them. An explicit
  `Options.builder().apiUrls(...)` with a single entry silently dropped the secondary; the
  SDK now logs a one-line WARN at init pointing the caller at the fix (pass both a primary
  and a secondary URL). Behavior is otherwise unchanged; no new dependencies. A new README
  "Failover & `QUONFIG_DOMAIN`" section documents the hostname derivation and the failover
  model.
- **Failover-observability telemetry (qfg-41nh.18; sdk-go parity).** The SDK now folds
  three failover-behavior counters into the existing periodic telemetry flush so the
  failover dashboards can be built: `hedgeFired` (config-fetch cycles whose parallel
  init hedge fired its secondary leg), `guardRejected` (installs the reject-older
  ordering guard dropped, on both the HTTP config-fetch path and the SSE message path),
  and `resolvedFromPrimary` / `resolvedFromSecondary` (which upstream leg served each
  successful HTTP install; SSE installs are not counted). A new internal
  `FailoverCollector` accumulates the counters and is drained into the flush via the
  same pattern as the eval-summary and context collectors, emitting a `failover` event
  only when at least one counter is non-zero — a healthy steady-state client sends
  nothing. The counters carry no user data and ride any enabled telemetry stream
  regardless of the eval/context opt-outs; they respect the full telemetry opt-out
  (`disableTelemetry`). The wire shape is additive, so an older api-telemetry strips the
  unknown field. No new dependencies. `hedgeFired` reflects the init-only parallel
  hedge; `refresh()` and the Layer 2 fallback poller walk the leg list sequentially and
  never fire a parallel hedge, so they contribute only guard-rejected / resolved-from
  signals.

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
