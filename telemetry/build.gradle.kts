plugins {
    id(Plugins.javaLibrary.id)
    id(Plugins.release.id)
}

dependencies {
    implementation(libs.coroutines)
    implementation(libs.stdLib)

    testImplementation(libs.coroutinesTest)
    testImplementation(libs.kotlinTest)
}
