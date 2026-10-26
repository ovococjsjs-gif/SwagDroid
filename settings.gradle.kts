pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}

rootProject.name = "SwagDroid"

include(":app")
include(":core:model")
include(":core:designsystem")
include(":core:privilege")
include(":core:vault")
include(":feature:dashboard")
