plugins {
    id("maven-publish")
}

val libraryGroup = "com.kroger"
val libraryName = "telemetry"
val libraryVersion = System.getenv("BUILD_VERSION") ?: "0.0.1"

publishing {
    publications {
        register<MavenPublication>("artifact") {
            val libraryGroup = project.group.toString()
            val libraryName = project.rootProject.name
            val projectName = project.name.toLowerCase()
            val libraryVersion = System.getenv("BUILD_VERSION") ?: project.version.toString()

            artifactId = if (libraryName == projectName) {
                projectName
            } else {
                "$libraryName-$projectName"
            }
            groupId = libraryGroup
            version = libraryVersion

            when {
                project.pluginManager.hasPlugin(Plugins.androidLibrary.id) -> {
                    project.afterEvaluate {
                        from(project.components["release"])
                    }
                }

                project.pluginManager.hasPlugin(Plugins.javaLibrary.id) -> from(project.components["java"])

                else -> Unit
            }
        }
    }

    repositories {
        maven {
            name = "Artifactory"
            url = uri("https://krogertechnology.jfrog.io/artifactory/kroger-dcp")
            credentials {
                username = System.getenv()["ARTIFACTORY_USERNAME"]
                password = System.getenv()["ARTIFACTORY_PASSWORD"]
            }
        }
    }
}
