dependencies {
    api(project(":core"))

    compileOnly("io.micronaut:micronaut-http:4.10.34")

    testImplementation(platform("org.junit:junit-bom:6.1.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("io.micronaut:micronaut-http:4.10.34")
}
