rootProject.name = "ksi-safety-edge-rc"
include(":rc-core")
include(":rc-app")

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
