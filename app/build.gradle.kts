plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Set by CI (.github/workflows/android.yml); local builds default to 1.
val buildNumber = (System.getenv("BUILD_NUMBER") ?: "1").toInt()
val buildAuthor = System.getenv("BUILD_AUTHOR") ?: "local build"
val buildCommit = System.getenv("BUILD_COMMIT") ?: "dev"

fun esc(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

android {
    namespace = "com.raretango.signaldetector"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.raretango.signaldetector"
        minSdk = 26
        targetSdk = 35
        versionCode = buildNumber
        versionName = "1.0.$buildNumber"
        buildConfigField("int", "BUILD_NUMBER", buildNumber.toString())
        buildConfigField("String", "BUILD_AUTHOR", esc(buildAuthor))
        buildConfigField("String", "BUILD_COMMIT", esc(buildCommit))
    }

    signingConfigs {
        // Every build must be signed with the SAME key or Android refuses to
        // install the update over the old version. CI can override with
        // repository secrets; otherwise the key in app/signing is used.
        create("update") {
            val ks = System.getenv("SIGNING_KEYSTORE_PATH")
            storeFile = if (ks != null) file(ks) else file("signing/update.keystore")
            storePassword = System.getenv("SIGNING_STORE_PASSWORD") ?: "signaldetector"
            keyAlias = System.getenv("SIGNING_KEY_ALIAS") ?: "signaldetector"
            keyPassword = System.getenv("SIGNING_KEY_PASSWORD") ?: "signaldetector"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("update")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("update")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    lint {
        abortOnError = false
    }
}

dependencies {
    implementation(project(":core"))
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
}
