plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.kotlin.jvm) apply false
}

tasks.register("initCheck") {
    group = "verification"
    description = "Builds the debug app and runs the repository verification gates."
    dependsOn(
        ":app:assembleDebug",
        ":app:lintDebug",
        ":core:codec:test",
        ":core:diagnostics:testDebugUnitTest",
        ":core:input:test",
        ":core:model:classes",
        ":core:vfs:test",
        ":feature:importer:testDebugUnitTest",
        ":feature:player:testDebugUnitTest",
        ":fixtures:classes",
        ":runtime:api:assembleDebug",
        ":runtime:api:testDebugUnitTest",
        ":runtime:webview:testDebugUnitTest",
    )
}

tasks.register("importerTest") {
    group = "verification"
    description = "Runs M1 detection, staged-copy, cancellation, and catalog tests."
    dependsOn(":feature:importer:testDebugUnitTest")
}

tasks.register("vfsTest") {
    group = "verification"
    description = "Runs VFS path, index, resolution, MIME, validator, and range tests."
    dependsOn(":core:vfs:test")
}

tasks.register("codecTest") {
    group = "verification"
    description = "Runs bounded asset-codec vectors and validation tests."
    dependsOn(":core:codec:test")
}

tasks.register("inputTest") {
    group = "verification"
    description = "Runs logical input source and pointer lifecycle tests."
    dependsOn(":core:input:test")
}

tasks.register("runtimeWebViewSmokeTest") {
    group = "verification"
    description = "Runs local-origin, session-isolation, and WebView backend smoke tests."
    dependsOn(
        ":feature:player:testDebugUnitTest",
        ":core:diagnostics:testDebugUnitTest",
        ":runtime:webview:testDebugUnitTest",
    )
}

tasks.register("saveTest") {
    group = "verification"
    description = "Runs save codecs, atomic persistence, and native WebView bridge tests."
    dependsOn(
        ":core:codec:test",
        ":runtime:api:testDebugUnitTest",
        ":runtime:webview:testDebugUnitTest",
    )
}