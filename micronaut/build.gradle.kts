dependencies {
    api(project(":core"))

    compileOnly("io.micronaut:micronaut-http:4.7.10")

    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("io.micronaut:micronaut-http:4.7.10")
}
