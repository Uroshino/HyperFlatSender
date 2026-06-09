// Gradle's dependencyResolutionManagement DSL (repositoriesMode / FAIL_ON_PROJECT_REPOS) is still
// @Incubating, so the IDE flags it as UnstableApiUsage. It's the Android Studio default and works
// fine — suppress the inspection noise for the whole settings script.
@file:Suppress("UnstableApiUsage")

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

rootProject.name = "HyperionFlatSender"
include(":app")
