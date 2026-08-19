plugins { id("makerplay.android.library") }

android { namespace = "io.github.gdlbo.makerplay.diagnostics" }

dependencies {
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
}