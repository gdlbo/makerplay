plugins {
    id("makerplay.android.library")
    id("makerplay.android.compose")
}

val ndkVersionValue = "28.2.13676358"

android {
    namespace = "io.github.gdlbo.makerplay.runtime.wolf"

    defaultConfig {
        consumerProguardFiles("consumer-rules.pro")
        ndkVersion = ndkVersionValue
        ndk {
            abiFilters += listOf("armeabi-v7a", "arm64-v8a", "x86_64")
        }
        externalNativeBuild {
            cmake {
                arguments += listOf("-DANDROID_STL=c++_shared")
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
}

dependencies {
    implementation(project(":core:diagnostics"))
    implementation(project(":core:input"))
    implementation(project(":core:wolfformat"))
    implementation(project(":runtime:api"))
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.core)
    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
}
