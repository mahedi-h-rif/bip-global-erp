plugins {
    `java-library`
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

dependencies {
    api("org.bouncycastle:bcpkix-jdk18on:1.86")
    implementation("org.apache.santuario:xmlsec:4.0.4")
    implementation("tools.jackson.core:jackson-databind:3.2.3")

    testImplementation(platform("org.junit:junit-bom:6.1.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    options.compilerArgs.add("-parameters")
}

tasks.test {
    useJUnitPlatform()
    // Live calls to the ZATCA developer sandbox run only when explicitly requested.
    systemProperty("zatca.live", System.getProperty("zatca.live", "false"))
    testLogging { events("passed", "failed", "skipped"); showStandardStreams = true }
}
