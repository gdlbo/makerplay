plugins {
    id("makerplay.android.library")
    id("makerplay.android.compose")
}

android { namespace = "io.github.gdlbo.makerplay.feature.player" }

dependencies {
    implementation(libs.androidx.activity.compose)
    implementation(project(":core:input"))
    implementation(project(":runtime:api"))
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.ui)
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
}