import java.util.Properties

plugins {
    id("com.android.application")
    kotlin("android")
}

repositories {
    google()
    mavenCentral()
}

// A9: DJI developer app key (package ksi.safetyedge.rc) from
// local.properties (`dji.apiKey=...`), never committed. Blank = SDK not
// initialised — bench builds and the frozen pipeline stay deterministic.
val djiApiKey: String = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}.getProperty("dji.apiKey") ?: ""

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

    // A9: bench = dev host with fake ports (no DJI SDK, frozen pipeline);
    // device = RC build with the real DJI MSDK adapters (rc-msdk).
    flavorDimensions += "platform"
    productFlavors {
        create("bench") { dimension = "platform" }
        create("device") {
            dimension = "platform"
            manifestPlaceholders["djiApiKey"] = djiApiKey
            // RC Plus 2 is arm64; the official sample also restricts ABIs.
            // Without this the APK carries every ABI's natives (~190MB).
            ndk {
                abiFilters += "arm64-v8a"
            }
        }
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
    // The DJI MSDK AAR ships native libraries expecting extraction
    // (its manifest declares extractNativeLibs=true).
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
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
    // A9: port interfaces + bench doubles (DroneLink/VideoSource/VideoRecorder)
    implementation(project(":rc-ports"))
    // rc-core exposes Jackson types in its API; declare them explicitly
    // here (rc-core itself is unchanged).
    implementation("com.fasterxml.jackson.core:jackson-databind:2.17.2")
    implementation("androidx.appcompat:appcompat:1.7.0")
    // A8: background sync (survives process death; network constraints).
    implementation("androidx.work:work-runtime:2.9.1")

    // A9: the device flavor only — real DJI MSDK adapters.
    "deviceImplementation"(project(":rc-msdk"))

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
