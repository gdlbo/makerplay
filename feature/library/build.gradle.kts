plugins {
    id("makerplay.android.library")
    id("makerplay.android.compose")
}

android { namespace = "io.github.gdlbo.makerplay.feature.library" }

dependencies {
    api(project(":core:model"))
    implementation(project(":feature:importer"))
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.ui)
}