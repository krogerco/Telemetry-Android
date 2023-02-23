plugins {
    id("org.jetbrains.kotlin.jvm")
    `java-library`
}

tasks {
    test {
        useJUnitPlatform()
    }
}
