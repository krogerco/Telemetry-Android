plugins {
    id(Plugins.androidLibrary.id)
    id(Plugins.release.id)
}

android {
    buildTypes {
        getByName("release") {
            consumerProguardFile("proguard-rules.pro")
        }
    }
}

dependencies {
    implementation(project(":telemetry"))

    implementation(libs.injectJavax)

    testImplementation(libs.mockk)
    testImplementation(libs.jupiterApi)
    testRuntimeOnly(libs.jupiterEngine)
}
