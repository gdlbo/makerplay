pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "makerplay"

includeBuild("build-logic")

include(
    ":app",
    ":core:codec",
    ":core:diagnostics",
    ":core:input",
    ":core:model",
    ":core:vfs",
    ":core:wolfformat",
    ":feature:importer",
    ":feature:library",
    ":feature:player",
    ":feature:settings",
    ":fixtures",
    ":runtime:api",
    ":runtime:wolf",
    ":runtime:webview",
)