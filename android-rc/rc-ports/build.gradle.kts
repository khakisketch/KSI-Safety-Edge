plugins {
    kotlin("jvm")
}

repositories {
    mavenCentral()
}

// Pure Kotlin port interfaces + bench doubles (no Android/Jackson deps):
// DroneLink / VideoSource / VideoRecorder live here so that rc-app (bench
// flavor) and rc-msdk (DJI implementations) can both depend on them without
// a Gradle cycle. The L1 pipeline stays frozen; this is the minimal split
// the A9 plan allows.
dependencies {
    testImplementation(kotlin("test"))
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.10.2")
}

tasks.test {
    useJUnitPlatform()
}
