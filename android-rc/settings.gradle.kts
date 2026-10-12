rootProject.name = "ksi-safety-edge-rc"
include(":rc-core")
include(":rc-app")
include(":rc-ports")
include(":rc-msdk")

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
