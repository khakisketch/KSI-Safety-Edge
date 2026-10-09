// KSI Safety Edge — Android RC workspace (MSDK track, approved 2026-10-09).
//
// Module layout:
//   :rc-core  — pure Kotlin/JVM RC business logic (MOP client, mission
//               handling, evidence store, upload/query). Builds and tests
//               on ANY JVM host (this repo's ARM64 build host included),
//               no Android SDK required. The Android UI shell will wrap
//               this module; APK packaging needs an x86_64 Android SDK
//               container (see docs: host aarch64 has no aapt2).
//   :rc-app   — Android application shell (A4): screens bound to the real
//               rc-core state (mission, evidence, sync). MOP transport and
//               the drone link are interfaces; DJI MSDK wiring lands when
//               the RC device + API key are available (H1 gate).
plugins {
    kotlin("jvm") version "2.0.21" apply false
    kotlin("android") version "2.0.21" apply false
    id("com.android.application") version "8.5.2" apply false
}
