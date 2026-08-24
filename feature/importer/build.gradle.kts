plugins {
    id("makerplay.android.library")
    id("makerplay.android.compose")
}

android { namespace = "io.github.gdlbo.makerplay.feature.importer" }

dependencies {
    api(project(":core:model"))
    implementation(project(":core:vfs"))
    implementation(project(":core:wolfformat"))
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.documentfile)
    implementation(libs.androidx.work.runtime)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.core)
    testImplementation(project(":fixtures"))
}
