dependencies {
    api(project(":core"))

    // The customer brings their own Logback version. compileOnly is the Gradle
    // equivalent of Maven's `provided` scope; vanniktech publishes these as
    // <scope>provided</scope> in the POM so consumers see the dep but don't get
    // a transitive Logback pulled in.
    compileOnly("ch.qos.logback:logback-classic:1.5.18")

    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("ch.qos.logback:logback-classic:1.5.18")
}
