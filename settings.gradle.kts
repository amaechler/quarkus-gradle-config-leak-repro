rootProject.name = "quarkus-gradle-config-leak-repro"

pluginManagement {
    val quarkusPluginVersion: String by settings
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
    plugins {
        id("io.quarkus") version quarkusPluginVersion
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

include("module-a", "module-b")
