plugins {
    id("makerplay.android.application")
    id("makerplay.android.compose")
}

android {
    namespace = "io.github.gdlbo.makerplay.app"

    defaultConfig {
        applicationId = "io.github.gdlbo.makerplay"
        versionCode = 3
        versionName = "0.1.2"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("debug")
        }
    }
}

dependencies {
    implementation(project(":core:diagnostics"))
    implementation(project(":feature:library"))
    implementation(project(":feature:importer"))
    implementation(project(":feature:player"))
    implementation(project(":feature:settings"))
    implementation(project(":runtime:api"))
    implementation(project(":runtime:wolf"))
    implementation(project(":runtime:webview"))
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.work.runtime)
}