import com.kroger.gradle.config.junit5

plugins {
    alias(libs.plugins.conventions.publishedAndroidLibrary)
}

android {
    namespace = "com.kroger.telemetry.contextaware"
}

kover {
    currentProject {
        createVariant("default") {
            add("debug")
        }
    }
}

dependencies {
    implementation(project(":telemetry"))
    implementation(libs.injectJavax)

    junit5()
    testImplementation(libs.mockk)
}
