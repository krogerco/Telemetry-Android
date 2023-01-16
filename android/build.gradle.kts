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
    api(project(":telemetry"))

    implementation(libs.coroutinesAndroid)
    implementation(libs.stdLib)

    testImplementation(libs.coroutinesTest)
    testImplementation(libs.jupiterApi)
    testRuntimeOnly(libs.jupiterEngine)
}
