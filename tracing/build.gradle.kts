import com.kroger.gradle.config.junit5

plugins {
    alias(libs.plugins.conventions.publishedAndroidLibrary)
}

android {
    namespace = "com.kroger.telemetry.tracing"
}

kover {
    currentProject {
        createVariant("default") {
            add("debug")
        }
    }
}

dependencies {
    api(project(":telemetry"))

    implementation(libs.kotlinx.coroutinesAndroid)
    implementation(libs.injectJavax)

    junit5()
    testImplementation(libs.kotlinx.coroutinesTest)
    testImplementation(libs.kotlinTest)
}
