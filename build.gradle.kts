import com.github.benmanes.gradle.versions.updates.DependencyUpdatesTask

// Top-level build file where you can add configuration options common to all sub-projects/modules.
buildscript {
    repositories {
        maven {
            url = uri("https://krogertechnology.jfrog.io/artifactory/libs-release")
            credentials {
                username = System.getenv("KT_JFROG_USERID")
                password = System.getenv("KT_JFROG_TOKEN")
            }
        }
        maven {
            url = uri("https://krogertechnology.jfrog.io/artifactory/remote-repos")
            credentials {
                username = System.getenv("KT_JFROG_USERID")
                password = System.getenv("KT_JFROG_TOKEN")
            }
        }
    }
}

allprojects {
    group = "com.kroger.telemetry"
    version = "0.0.1"

    repositories {
        maven {
            url = uri("https://krogertechnology.jfrog.io/artifactory/libs-release")
            credentials {
                username = System.getenv("KT_JFROG_USERID")
                password = System.getenv("KT_JFROG_TOKEN")
            }
            metadataSources {
                mavenPom()
                artifact()
            }
        }
        maven {
            url = uri("https://krogertechnology.jfrog.io/artifactory/remote-repos")
            credentials {
                username = System.getenv("KT_JFROG_USERID")
                password = System.getenv("KT_JFROG_TOKEN")
            }
        }
    }
}

plugins {
    id("com.github.ben-manes.versions") version "0.36.0"
    id("org.jlleitschuh.gradle.ktlint") version "10.2.1"
}

subprojects {
    apply(plugin = "org.jlleitschuh.gradle.ktlint")

    configure<org.jlleitschuh.gradle.ktlint.KtlintExtension> {
        version.set("0.45.2")
        android.set(true)
        reporters {
            reporter(org.jlleitschuh.gradle.ktlint.reporter.ReporterType.CHECKSTYLE)
        }
        filter {
            exclude("**/generated/**", "**/src/test/**")
        }
    }
}

tasks.named("dependencyUpdates", DependencyUpdatesTask::class.java).configure {
    // optional parameters
    checkForGradleUpdate = true
    outputFormatter = "json"
    outputDir = "build/dependencyUpdates"
    reportfileName = "report"
}
