plugins {
    id("com.android.application")
    id("kotlin-android")
    kotlin("kapt")
    id("dagger.hilt.android.plugin")
}

kapt {
    correctErrorTypes = true
}

android {
    compileSdk = (SdkVersions.compileSdkVersion)
    buildToolsVersion = ("30.0.3")
    namespace = "com.kroger.telemetry.sample"

    defaultConfig {
        minSdk = (SdkVersions.minSdkVersion)
        targetSdk = (SdkVersions.targetSdkVersion)
        compileSdk = (SdkVersions.compileSdkVersion)
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlinOptions {
        jvmTarget = "11"
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

    implementation(libs.androidCoreKtx)
    implementation(libs.appCompat)
    implementation(libs.contraintLayount)
    implementation(libs.coroutines)
    implementation(libs.googleMaterial)
    implementation(libs.stdLib)

    implementation(libs.hilt)
    kapt(libs.hiltAndroidCompiler)
    kapt(libs.hiltCompiler)

    androidTestImplementation(libs.junitTestKtx)
    androidTestImplementation(libs.espresso)
}
