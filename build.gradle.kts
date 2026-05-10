import com.vanniktech.maven.publish.JavaLibrary
import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.SonatypeHost

plugins {
    `java-library`
    id("com.diffplug.spotless") version "6.25.0"
    id("com.vanniktech.maven.publish") version "0.30.0"
}

group = providers.gradleProperty("GROUP").get()
version = providers.gradleProperty("VERSION_NAME").get()

repositories {
    mavenCentral()
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

dependencies {
    implementation("com.fasterxml.jackson.core:jackson-databind:2.18.2")
    implementation("org.slf4j:slf4j-api:2.0.16")
    implementation("com.google.guava:guava:33.4.0-jre")

    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "failed", "skipped")
        showStandardStreams = false
    }
}

spotless {
    java {
        target("src/**/*.java")
        googleJavaFormat("1.24.0")
        removeUnusedImports()
        trimTrailingWhitespace()
        endWithNewline()
    }
    kotlinGradle {
        target("*.gradle.kts")
        ktlint("1.5.0")
    }
}

mavenPublishing {
    // Publishes to the Sonatype Central Portal (the new Maven Central upload
    // mechanism that replaces the legacy OSSRH staging repo). Set
    // automaticRelease=true so tag-triggered CI does not need a manual
    // "release" click in the portal UI once the namespace is verified.
    publishToMavenCentral(SonatypeHost.CENTRAL_PORTAL, automaticRelease = true)

    // GPG signing reads from env vars in CI (ORG_GRADLE_PROJECT_signingInMemoryKey,
    // ORG_GRADLE_PROJECT_signingInMemoryKeyPassword) — see .github/workflows/publish.yaml.
    // Locally, signing is only required for the Sonatype `publish` task; the
    // `publishToMavenLocal` task installs unsigned artifacts so dev builds
    // work without GPG keys.
    signAllPublications()

    // Coordinates and POM metadata (name/description/url/license/developers/scm)
    // are populated from gradle.properties — vanniktech reads GROUP, VERSION_NAME,
    // POM_ARTIFACT_ID, POM_NAME, POM_DESCRIPTION, POM_URL, POM_LICENSE_*,
    // POM_DEVELOPER_*, and POM_SCM_* automatically. No explicit pom { } block needed.
    configure(JavaLibrary(javadocJar = JavadocJar.Javadoc(), sourcesJar = true))
}
