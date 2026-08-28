import java.util.Properties

plugins {
    id("makerplay.android.library")
    id("makerplay.android.compose")
}

val rustCrateDir = file("src/main/rust/rpgm_native")
val rustJniLibsDir = file("src/main/jniLibs")
val androidSdkDir: String? = (project.findProperty("android.sdk.dir") as String?)
    ?: rootProject.file("local.properties").takeIf { it.exists() }?.let { propsFile ->
        propsFile.inputStream().use { stream ->
            val props = Properties()
            props.load(stream)
            props.getProperty("sdk.dir")
        }
    }
val ndkVersionValue = "28.2.13676358"

tasks.register("cargoNdkRpgmNative") {
    group = "build"
    description = "Build librpgm_native.so with cargo-ndk (Rust M1)"
    inputs.dir(rustCrateDir.resolve("src"))
    inputs.file(rustCrateDir.resolve("Cargo.toml"))
    outputs.dir(rustJniLibsDir)
    notCompatibleWithConfigurationCache("cargo-ndk uses local NDK path from local.properties")
    doLast {
        val sdk = androidSdkDir ?: error("Android SDK not found (local.properties sdk.dir)")
        val ndk = file("$sdk/ndk/$ndkVersionValue")
        require(ndk.exists()) { "NDK $ndkVersionValue missing at ${ndk.absolutePath}" }
        rustJniLibsDir.mkdirs()
        val builder = ProcessBuilder(
            "cargo", "ndk",
            "-t", "arm64-v8a",
            "-t", "armeabi-v7a",
            "-t", "x86_64",
            "-o", rustJniLibsDir.absolutePath,
            "build", "--release",
        )
        builder.directory(rustCrateDir)
        builder.redirectErrorStream(true)
        builder.environment()["ANDROID_NDK_HOME"] = ndk.absolutePath
        val process = builder.start()
        process.inputStream.bufferedReader().lines().forEach { println(it) }
        val code = process.waitFor()
        require(code == 0) { "cargo-ndk failed with exit code $code" }
    }
}

android {
    namespace = "io.github.gdlbo.makerplay.runtime.webview"

    defaultConfig {
        consumerProguardFiles("consumer-rules.pro")
        ndkVersion = ndkVersionValue
        ndk {
            abiFilters += listOf("armeabi-v7a", "arm64-v8a", "x86_64")
        }
    }

    sourceSets {
        getByName("main") {
            jniLibs.directories.add(rustJniLibsDir.path)
        }
    }
}

tasks.named("preBuild").configure {
    dependsOn("cargoNdkRpgmNative")
}

dependencies {
    implementation(project(":core:codec"))
    implementation(project(":core:diagnostics"))
    implementation(project(":core:input"))
    implementation(project(":core:vfs"))
    implementation(project(":runtime:api"))
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.webkit)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
    testImplementation(project(":fixtures"))
    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
}
