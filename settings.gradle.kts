include(":telemetry")
include(":android")
include(":context-aware")
include(":firebase")
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
