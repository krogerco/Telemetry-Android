import com.kroger.gradle.config.junit5

plugins {
    alias(libs.plugins.conventions.publishedKotlinLibrary)
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
