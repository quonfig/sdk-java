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
    implementation("com.quonfig:sdk-java:0.0.2")
    // optional, depending on which logging library you use:
    runtimeOnly("com.quonfig:sdk-java-logback:0.0.2")
    runtimeOnly("com.quonfig:sdk-java-log4j2:0.0.2")
    // optional, for Micronaut apps:
    implementation("com.quonfig:sdk-java-micronaut:0.0.2")
}
```

### Maven

```xml
<dependency>
    <groupId>com.quonfig</groupId>
    <artifactId>sdk-java</artifactId>
    <version>0.0.2</version>
</dependency>
```

## Dynamic log levels

Add the matching filter at startup; every logger automatically picks up Quonfig log-level configs.

```java
Quonfig q = new Quonfig(opts);
QuonfigLogbackTurboFilter.install(q);   // or QuonfigLog4j2Filter.install(q);
```

## Health primitives

`Quonfig.lastSuccessfulRefresh()` returns the wall-clock time of the most recent installed
envelope (any source). `Quonfig.connectionState()` returns one of `CONNECTED`, `DISCONNECTED`,
`FALLING_BACK`, or `INITIALIZING`.

> Do not wire `lastSuccessfulRefresh()` or `connectionState()` directly into a Kubernetes liveness probe. These signals are diagnostic, not pass/fail. A liveness probe based on SDK freshness will amplify transient network blips into restart cascades.

## Requirements

- Java 17 or later

## License

Apache License 2.0
