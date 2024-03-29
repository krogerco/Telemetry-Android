plugins {
    `kotlin-dsl`
}

buildscript {
    repositories {
        mavenCentral()
        google()
    }
}

allprojects {
    repositories {
        mavenCentral()
        google()
    }
}

dependencies {
    implementation("com.android.tools.build:gradle:7.3.1")
    implementation("com.squareup:javapoet:1.13.0")
    implementation("de.mannodermaus.gradle.plugins:android-junit5:1.8.0.0")
    implementation("org.jetbrains.kotlin:kotlin-gradle-plugin:1.5.31")
    implementation("org.jetbrains.dokka:dokka-gradle-plugin:1.5.31")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.3.3")
    implementation("com.vanniktech:gradle-maven-publish-plugin:0.22.0")
}
