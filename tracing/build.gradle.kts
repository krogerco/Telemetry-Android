import com.kroger.gradle.config.junit5
import org.jetbrains.kotlin.gradle.dsl.JvmDefaultMode

plugins {
    alias(libs.plugins.conventions.publishedKotlinLibrary)
}

kotlin {
    coreLibrariesVersion = libs.versions.kotlin.get()
    compilerOptions {
        jvmDefault = JvmDefaultMode.NO_COMPATIBILITY
    }
}

kover {
    currentProject {
        createVariant("default") {
            add("jvm")
        }
    }
}

dependencies {
    api(project(":telemetry"))

    implementation(libs.kotlinx.coroutinesCore)

    junit5()
    testImplementation(libs.kotlinx.coroutinesTest)
    testImplementation(libs.kotlinTest)
}
