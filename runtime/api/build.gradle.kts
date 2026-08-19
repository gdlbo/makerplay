plugins {
    id("makerplay.android.library")
    id("makerplay.android.compose")
}

android { namespace = "io.github.gdlbo.makerplay.runtime.api" }

dependencies {
    implementation(project(":core:input"))
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    testImplementation(libs.junit)
}