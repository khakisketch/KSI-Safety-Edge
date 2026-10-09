// KSI Safety Edge — Android RC workspace (MSDK track, approved 2026-10-09).
//
// Module layout:
//   :rc-core  — pure Kotlin/JVM RC business logic (MOP client, mission
//               handling, evidence store, upload/query). Builds and tests
//               on ANY JVM host (this repo's ARM64 build host included),
//               no Android SDK required. The Android UI shell will wrap
//               this module; APK packaging needs an x86_64 Android SDK
//               container (see docs: host aarch64 has no aapt2).
plugins {
    kotlin("jvm") version "2.0.21" apply false
}
