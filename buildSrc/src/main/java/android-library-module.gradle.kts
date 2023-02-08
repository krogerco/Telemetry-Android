import org.gradle.kotlin.dsl.invoke

plugins {
    id("com.android.library")
    kotlin("android")
    id("jacoco")
    id("de.mannodermaus.android-junit5")
    id("org.jetbrains.dokka")
}

android {
    compileSdk = SdkVersions.compileSdkVersion

    defaultConfig {
        minSdk = (SdkVersions.minSdkVersion)
        targetSdk = (SdkVersions.targetSdkVersion)
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlinOptions {
        jvmTarget = "11"
    }

    packagingOptions {
        exclude("META-INF/AL2.0")
        exclude("META-INF/LGPL2.1")
    }
}

jacoco {
    toolVersion = "0.8.7"
}

tasks {
    withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
        kotlinOptions.jvmTarget = "11"
        kotlinOptions.freeCompilerArgs += "-Xopt-in=kotlin.RequiresOptIn"
        kotlinOptions.freeCompilerArgs += "-Xexplicit-api=strict"
    }

    withType<JacocoReport> {
        reports {
            csv.isEnabled = false
            html.isEnabled = false
        }
    }
}

tasks.dokkaHtml {
    dokkaSourceSets {
        configureEach {
            offlineMode.set(true)
        }
    }
}
