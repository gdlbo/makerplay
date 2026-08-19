plugins {
    id("makerplay.android.library")
    id("makerplay.android.compose")
}

android { namespace = "io.github.gdlbo.makerplay.runtime.webview" }

dependencies {
    implementation(project(":core:diagnostics"))
    implementation(project(":core:input"))
    implementation(project(":core:vfs"))
    implementation(project(":runtime:api"))
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.webkit)
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
    testImplementation(project(":fixtures"))
}
