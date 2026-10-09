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
}
