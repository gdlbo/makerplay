plugins { id("makerplay.jvm.library") }

dependencies {
    implementation(project(":core:codec"))
    implementation(libs.kotlinx.serialization.json)
    testImplementation(project(":fixtures"))
    testImplementation(libs.junit)
}