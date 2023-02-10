include(":telemetry")
include(":android")
include(":context-aware")
include(":firebase")
include(":sample")
enableFeaturePreview("VERSION_CATALOGS")

rootProject.name = "telemetry"
pluginManagement {
    repositories {
        mavenCentral()
        google()

        // Public portal required for ben-manes:version
        gradlePluginPortal()
    }
    plugins {
        id("de.mannodermaus.android-junit5").version("1.8.1.0")
        id("org.jetbrains.dokka").version("1.7.20")
        id("com.android.application").version("7.3.0")
        id("org.jetbrains.kotlin.plugin.serialization").version("1.8.10")
    }

    resolutionStrategy {
        eachPlugin {
            when (requested.id.id) {
                "dagger.hilt.android.plugin" -> useModule("com.google.dagger:hilt-android-gradle-plugin:2.44.2")
                "org.jetbrains.kotlin:kotlin-gradle-plugin:1.8.10" -> useModule("org.jetbrains.kotlin:kotlin-gradle-plugin:1.8.10")
            }
        }
        dependencyResolutionManagement {
            versionCatalogs {
                (files("gradle/libs.versions.toml"))
            }
        }
    }
}
