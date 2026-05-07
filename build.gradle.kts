plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.junit5) apply false
    alias(libs.plugins.compose) apply false
    alias(libs.plugins.conventions.androidApplication) apply false
    alias(libs.plugins.conventions.publishedAndroidLibrary) apply false
    alias(libs.plugins.conventions.publishedKotlinLibrary) apply false
    alias(libs.plugins.conventions.root)
    alias(libs.plugins.dependencyAnalysis) apply false
    alias(libs.plugins.dokka)
    alias(libs.plugins.gradleVersions) apply false
    alias(libs.plugins.dagger.hilt) apply false
    alias(libs.plugins.kotlinter) apply false
    alias(libs.plugins.kotlinx.kover) apply true
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.mavenPublish) apply false
}

kover {
    currentProject {
        createVariant("default") {
            // no sources and tests in root module
        }
    }
}

dependencies {
    kover(project(":android"))
    kover(project(":context-aware"))
    kover(project(":firebase"))
    kover(project(":telemetry"))
}
