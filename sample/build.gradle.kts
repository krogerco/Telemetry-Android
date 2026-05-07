import com.kroger.gradle.config.hiltKsp

plugins {
    alias(libs.plugins.conventions.androidApplication)
    alias(libs.plugins.dagger.hilt)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.kroger.telemetry.sample"

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
        }
    }

    buildFeatures {
        viewBinding = true
    }
}

tasks.withType<JavaCompile> {
    options.compilerArgs.add("-Xlint:deprecation")
}

dependencies {
    implementation(project(":android"))
    implementation(project(":context-aware"))

    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constrainlayout)
    implementation(libs.androidx.coreKtx)
    implementation(libs.kotlinx.coroutinesCore)
    implementation(libs.android.material)

    hiltKsp()

    androidTestImplementation(libs.android.test.espressoCore)
    androidTestImplementation(libs.androidx.test.ext.junitKtx)
}
