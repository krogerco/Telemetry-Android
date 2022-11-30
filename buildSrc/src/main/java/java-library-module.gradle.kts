import org.gradle.kotlin.dsl.invoke

plugins {
    id("org.jetbrains.kotlin.jvm")
    `java-library`
}

java {
    withSourcesJar()
}

tasks {
    test {
        useJUnitPlatform()
    }
}
