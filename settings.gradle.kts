pluginManagement {
    repositories {
        mavenLocal()
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
rootProject.name = "ApkMixin"
include(":plugin")
include(":mixin-lib")
include(":example")
include(":compiler")
include(":compose-stubs")
