pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        // maven.google.com rather than google(): the latter resolves to dl.google.com, which some environments block.
        maven("https://maven.google.com") {
            content {
                includeGroupByRegex("androidx.*")
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
            }
        }
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
        // maven.google.com rather than google(): the latter resolves to dl.google.com, which some environments block.
        maven("https://maven.google.com") {
            content {
                includeGroupByRegex("androidx.*")
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
            }
        }
    }
}

rootProject.name = "spanish-reader"

include(":core")
// TODO: include(":app") once the Android SDK is available in the build environment.
