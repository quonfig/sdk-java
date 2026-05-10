dependencies {
    api(project(":core"))

    compileOnly("org.apache.logging.log4j:log4j-api:2.24.3")
    compileOnly("org.apache.logging.log4j:log4j-core:2.24.3")

    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("org.apache.logging.log4j:log4j-api:2.24.3")
    testImplementation("org.apache.logging.log4j:log4j-core:2.24.3")
}
