plugins {
    `kotlin-dsl`
}

dependencies {
    implementation(libs.android.gradle.plugin)
    implementation(libs.kotlin.gradle.plugin)
    implementation(libs.kotlin.compose.compiler.plugin)
}

gradlePlugin {
    plugins {
        register("androidApplication") {
            id = "makerplay.android.application"
            implementationClass = "MakerPlayAndroidApplicationPlugin"
        }
        register("androidLibrary") {
            id = "makerplay.android.library"
            implementationClass = "MakerPlayAndroidLibraryPlugin"
        }
        register("androidCompose") {
            id = "makerplay.android.compose"
            implementationClass = "MakerPlayAndroidComposePlugin"
        }
        register("jvmLibrary") {
            id = "makerplay.jvm.library"
            implementationClass = "MakerPlayJvmLibraryPlugin"
        }
    }
}
