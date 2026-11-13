pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "xcertplay"
include(":common")
include(":mobile")
// include(":automotive")
include(":shared")
include(":maphost")
project(":maphost").projectDir = file("samples/maphost")
include(":home")
project(":home").projectDir = file("samples/home")
// The simulator sources are local-only (see .gitignore); a clean checkout and CI build without them.
if (file("windows-simulator").isDirectory) include(":windows-simulator")
