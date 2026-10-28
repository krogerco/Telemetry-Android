include(":android")
include(":context-aware")
include(":firebase")
include(":telemetry")
include(":tracing")
include(":sample")

rootProject.name = "telemetry"
pluginManagement {
    repositories {
        mavenCentral()
        google()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}
