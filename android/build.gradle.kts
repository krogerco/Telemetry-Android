import com.kroger.gradle.config.junit5

plugins {
    alias(libs.plugins.conventions.publishedAndroidLibrary)
}

android {
    namespace = "com.kroger.telemetry.android"
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

    junit5()
    testImplementation(libs.kotlinx.coroutinesTest)
}
