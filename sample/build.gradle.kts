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

kotlin {
    compilerOptions {
        optIn.add("com.kroger.telemetry.tracing.ExperimentalTracingApi")
    }
}

tasks.withType<JavaCompile> {
    options.compilerArgs.add("-Xlint:deprecation")
}

dependencies {
    implementation(project(":android"))
    implementation(project(":context-aware"))
    implementation(project(":tracing"))

    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.coreKtx)
    implementation(libs.kotlinx.coroutinesCore)
    implementation(libs.android.material)

    hiltKsp()

    androidTestImplementation(libs.android.test.espressoCore)
    androidTestImplementation(libs.androidx.test.ext.junitKtx)
}
