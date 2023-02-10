plugins {
    `kotlin-dsl`
}

allprojects {
    repositories {
        mavenCentral()
        google()
    }
}

dependencies {
    implementation("com.android.tools.build:gradle:7.4.1")
    implementation("com.squareup:javapoet:1.13.0")
    implementation("de.mannodermaus.gradle.plugins:android-junit5:1.8.1.0")
    implementation("org.jetbrains.kotlin:kotlin-gradle-plugin:1.8.10")
    implementation("org.jetbrains.dokka:dokka-gradle-plugin:1.7.20")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.4.1")
    implementation("com.vanniktech:gradle-maven-publish-plugin:0.22.0")
}
