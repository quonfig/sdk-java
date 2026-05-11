dependencies {
    implementation("com.fasterxml.jackson.core:jackson-databind:2.18.2")
    implementation("org.slf4j:slf4j-api:2.0.16")
    implementation("com.google.guava:guava:33.4.0-jre")

    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    // Chaos test runner (com.quonfig.sdk.chaos) — reads scenario YAML files
    // from integration-test-data/chaos/scenarios. Gated on CHAOS_RUN=1, so
    // this is test-only and never pulled into the published artifact.
    testImplementation("org.yaml:snakeyaml:2.3")
}
