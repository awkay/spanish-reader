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
        // NewPipe Extractor (YouTube audio download on the phone) and its nanojson fork.
        maven("https://jitpack.io") {
            content { includeGroupByRegex("com\\.github\\.(?i)teamnewpipe") }
        }
    }
}

rootProject.name = "spanish-reader"

include(":core")
include(":app")
