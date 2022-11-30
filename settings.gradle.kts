include(":telemetry")
include(":android")
include(":context-aware")
include(":firebase")
include(":sample_app")
enableFeaturePreview("VERSION_CATALOGS")

rootProject.name = "telemetry"
pluginManagement {
    repositories {
        maven {
            url = uri("https://krogertechnology.jfrog.io/artifactory/libs-release")
            credentials {
                username = System.getenv("KT_JFROG_USERID")
                password = System.getenv("KT_JFROG_TOKEN")
            }
            maven {
                url = uri("https://krogertechnology.jfrog.io/artifactory/remote-repos")
                credentials {
                    username = System.getenv("KT_JFROG_USERID")
                    password = System.getenv("KT_JFROG_TOKEN")
                }
            }
        }
        // Public portal required for ben-manes:version
        gradlePluginPortal()
    }
    plugins {
        id("de.mannodermaus.android-junit5").version("1.8.0.0")
        id("org.jetbrains.dokka").version("1.6.0")
        id("com.android.application").version("7.3.0")
        id("org.jetbrains.kotlin.plugin.serialization").version("1.5.31")
    }

    resolutionStrategy {
        eachPlugin {
            when (requested.id.id) {
                "dagger.hilt.android.plugin" -> useModule("com.google.dagger:hilt-android-gradle-plugin:2.40.5")
                "org.jetbrains.kotlin:kotlin-gradle-plugin:1.5.31" -> useModule("org.jetbrains.kotlin:kotlin-gradle-plugin:1.5.31")
            }
        }
        dependencyResolutionManagement {
            versionCatalogs {
                (files("gradle/libs.versions.toml"))
            }
        }
    }
}
