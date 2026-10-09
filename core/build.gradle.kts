dependencies {
    implementation("com.fasterxml.jackson.core:jackson-databind:2.22.3")
    implementation("org.slf4j:slf4j-api:2.0.20")
    implementation("com.google.guava:guava:33.7.2-jre")

    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    // Chaos test runner (com.quonfig.sdk.chaos) — reads scenario YAML files
    // from integration-test-data/chaos/scenarios. Gated on CHAOS_RUN=1, so
    // this is test-only and never pulled into the published artifact.
    testImplementation("org.yaml:snakeyaml:2.7")
}

// Stamp the artifact version into a classpath resource that Version reads at runtime
// (qfg-y8je.2). The published jar's manifest carries no Implementation-Version, so this
// resource is the single source of truth for both `./gradlew test` and the shipped jar.
tasks.named<ProcessResources>("processResources") {
    val sdkVersion = project.version.toString()
    inputs.property("sdkVersion", sdkVersion)
    filesMatching("com/quonfig/sdk/sdk-version.properties") {
        expand("version" to sdkVersion)
    }
}

// Tests assert the SDK reports the real build version (qfg-y8je.2); hand them the
// Gradle project version so the expectation is never a copy of a literal.
tasks.named<Test>("test") {
    systemProperty("quonfig.expectedSdkVersion", project.version.toString())
}
