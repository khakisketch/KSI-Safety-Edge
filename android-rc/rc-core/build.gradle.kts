plugins {
    kotlin("jvm")
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("com.fasterxml.jackson.core:jackson-databind:2.17.2")
    // RFC 8785 (JCS) canonicalization — byte-for-byte digest parity with the
    // Python ksi_contracts implementation (see MopContractTest goldens).
    implementation("io.github.erdtman:java-json-canonicalization:1.1")

    testImplementation(kotlin("test"))
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.10.2")
}

tasks.test {
    useJUnitPlatform()
}
