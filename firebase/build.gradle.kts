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

    implementation(libs.androidCoreKtx)
    implementation(libs.coroutines)
    implementation(libs.firebaseAnalytics)
    implementation(libs.injectJavax)
    implementation(libs.stdLib)


    testImplementation(libs.mockk)
    testImplementation(libs.coroutinesTest)
    testImplementation(libs.jupiterApi)
    testRuntimeOnly(libs.jupiterEngine)
}
