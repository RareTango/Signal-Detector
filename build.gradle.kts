// Plugins are put on the root build classpath so every module shares one
// classloader. The Android plugin is skipped with -PcoreOnly, which lets the
// pure-Kotlin core be built and tested on machines without Google's Maven.
buildscript {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
    dependencies {
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.1.0")
        if (!project.hasProperty("coreOnly")) {
            classpath("com.android.tools.build:gradle:8.7.3")
            classpath("org.jetbrains.kotlin:compose-compiler-gradle-plugin:2.1.0")
        }
    }
}
