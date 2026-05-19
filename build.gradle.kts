import com.diffplug.gradle.spotless.SpotlessExtension
import com.vanniktech.maven.publish.JavaLibrary
import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.MavenPublishBaseExtension
import com.vanniktech.maven.publish.SonatypeHost

plugins {
    id("com.diffplug.spotless") version "6.25.0" apply false
    id("com.vanniktech.maven.publish") version "0.30.0" apply false
}

subprojects {
    apply(plugin = "java-library")
    apply(plugin = "com.diffplug.spotless")
    apply(plugin = "com.vanniktech.maven.publish")

    group = providers.gradleProperty("GROUP").get()
    version = providers.gradleProperty("VERSION_NAME").get()

    repositories {
        mavenCentral()
    }

    extensions.configure<JavaPluginExtension> {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(17))
        }
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        // Tests resolve fixtures via `user.dir`/.. → integration-test-data (sibling of
        // sdk-java). Pinning workingDir to rootProject keeps that lookup stable now that
        // each subproject has its own projectDir.
        workingDir = rootProject.projectDir
        testLogging {
            events("passed", "failed", "skipped")
            showStandardStreams = false
        }
    }

    extensions.configure<SpotlessExtension> {
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

    // Vanniktech reads per-subproject POM_ARTIFACT_ID / POM_NAME / POM_DESCRIPTION from
    // each subproject's gradle.properties (Gradle's property hierarchy lets subprojects
    // override root). Coordinates and shared POM metadata (license, scm, developers) come
    // from root gradle.properties so version/group stay in lock-step across all four
    // artifacts. publish.yaml runs `./gradlew publishToMavenCentral` which fans out to
    // every subproject that applies the vanniktech plugin.
    extensions.configure<MavenPublishBaseExtension> {
        publishToMavenCentral(SonatypeHost.CENTRAL_PORTAL, automaticRelease = true)
        signAllPublications()
        configure(JavaLibrary(javadocJar = JavadocJar.Javadoc(), sourcesJar = true))
    }
}
