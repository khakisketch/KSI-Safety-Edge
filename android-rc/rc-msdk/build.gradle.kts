plugins {
    id("com.android.library")
    kotlin("android")
}

repositories {
    google()
    mavenCentral()
}

// A9: real DJI MSDK v5 adapters implementing the frozen rc-ports interfaces.
// Honest boundary: everything here compiles and packages without a
// developer app key; SDK initialisation/registration, real MOP transport
// and video reception succeed only on real hardware (device gate).
android {
    namespace = "ksi.msdk"
    compileSdk = 34

    defaultConfig {
        minSdk = 26
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    api(project(":rc-ports"))
    implementation(project(":rc-core"))
    // Official DJI MSDK v5 coordinates (verified on Maven Central).
    // provided = compile stubs; the real aircraft AAR ships in the APK.
    implementation("com.dji:dji-sdk-v5-aircraft:5.18.0")
    compileOnly("com.dji:dji-sdk-v5-aircraft-provided:5.18.0")
    implementation("com.dji:dji-sdk-v5-networkImp:5.18.0")

    testImplementation("junit:junit:4.13.2")
}
