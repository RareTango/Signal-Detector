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
rootProject.name = "SignalDetector"
include(":core")
// `-PcoreOnly` builds/tests the pure-Kotlin core without the Android SDK.
if (!providers.gradleProperty("coreOnly").isPresent) {
    include(":app")
}
