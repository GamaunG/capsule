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

plugins {
    // https://github.com/ben-manes/gradle-versions-plugin
    id("io.github.ben-manes.versions.settings") version "0.62.0"
}

rootProject.name = "Capsule"

include(":app")
