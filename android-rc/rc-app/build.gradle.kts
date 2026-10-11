plugins {
    id("com.android.application")
    kotlin("android")
}

repositories {
    google()
    mavenCentral()
}

android {
    namespace = "ksi.safetyedge.rc"
    compileSdk = 34

    defaultConfig {
        applicationId = "ksi.safetyedge.rc"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.4.0-a4"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // The whole RC business logic (MOP, mission, evidence, upload/query).
    // rc-core stays platform-free; Android specifics (transport, clock,
    // storage dir) are injected from this module.
    implementation(project(":rc-core"))
    // rc-core exposes Jackson types in its API; declare them explicitly
    // here (rc-core itself is unchanged).
    implementation("com.fasterxml.jackson.core:jackson-databind:2.17.2")
    implementation("androidx.appcompat:appcompat:1.7.0")
    // A8: background sync (survives process death; network constraints).
    implementation("androidx.work:work-runtime:2.9.1")

    // A6/A7 host-side unit tests for the pure models/policies.
    testImplementation("junit:junit:4.13.2")
    // A8/L1 closure: real WorkManager execution tests (Robolectric, host).
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.work:work-testing:2.9.1")
    testImplementation("androidx.test:core:1.6.1")
}

android {
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

tasks.withType<Test>().configureEach {
    // Host is aarch64; the x86_64 container path runs the JVM under
    // qemu-user where AVX emulation can crash Robolectric's native
    // runtime. Disable AVX only in that (amd64) case.
    if (System.getProperty("os.arch") == "amd64") {
        jvmArgs("-XX:UseAVX=0")
    }
}
