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
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "SwagDroid"

include(":api")
include(":core-rollback")
include(":server")
include(":adb-pairing")
include(":backend-root")
include(":feature-antivirus")
include(":feature-cleaner")
include(":feature-adblock")
include(":feature-optimizer")
include(":feature-fixer")
include(":app")
