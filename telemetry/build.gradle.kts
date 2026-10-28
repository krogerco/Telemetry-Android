import com.kroger.gradle.config.junit5

plugins {
    alias(libs.plugins.conventions.publishedKotlinLibrary)
}

kotlin {
    coreLibrariesVersion = libs.versions.kotlin.get()
}

kover {
    currentProject {
        createVariant("default") {
            add("jvm")
        }
    }
}

dependencies {
    implementation(libs.kotlinx.coroutinesCore)

    junit5()
    testImplementation(libs.kotlinx.coroutinesTest)
    testImplementation(libs.kotlinTest)
}
