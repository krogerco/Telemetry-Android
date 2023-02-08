import com.vanniktech.maven.publish.SonatypeHost

plugins {
    id("com.vanniktech.maven.publish")
}

val libraryVersion = System.getenv("BUILD_VERSION") ?: "0.0.1"

mavenPublishing {
    version = libraryVersion
}
