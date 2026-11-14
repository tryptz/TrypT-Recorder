import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Debug builds use this module's committed debug key, recorder/debug.keystore
// (the standard android/androiddebugkey values — not a secret), so every
// machine's debug APK installs over every other's. Release builds use this
// app's own key, described by recorder/keystore.properties (gitignored; see
// keystore.properties.example next to it). It lives in the module, not the
// repository root, so that the module never picks up another app's key if it
// is ever built alongside one: a build signed with a different key will not
// install over an existing one.
val keystoreProperties = Properties()
val keystorePropertiesFile = file("keystore.properties")
if (keystorePropertiesFile.exists()) {
    keystorePropertiesFile.inputStream().use(keystoreProperties::load)
}
val hasCompleteReleaseSigning = listOf("storeFile", "storePassword", "keyAlias", "keyPassword")
    .all { !keystoreProperties.getProperty(it).isNullOrBlank() }

android {
    namespace = "tf.monochrome.recorder"
    compileSdk = 36
    ndkVersion = "28.2.13676358"

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    defaultConfig {
        applicationId = "tf.monotrypt.recorder"
        // 29: AudioPlaybackCapture (device audio) is Android 10+, and the
        // recorder this grew out of was already @RequiresApi(Q).
        minSdk = 29
        targetSdk = 36
        versionCode = 12
        versionName = "2.0.0-preview.5"
    }

    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        if (hasCompleteReleaseSigning) {
            create("release") {
                storeFile = file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            if (hasCompleteReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
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
    }
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.compose.activity)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.lifecycle.runtime)
    implementation(libs.lifecycle.viewmodel)
    implementation(libs.core.ktx)
    implementation(libs.kotlinx.coroutines)
    implementation(libs.kotlinx.serialization)

    // Editing: Transformer renders the export, CompositionPlayer previews the
    // very same Composition, effect supplies the GL video effects.
    implementation(libs.media3.editor.transformer)
    implementation(libs.media3.editor.effect)
    implementation(libs.media3.editor.common)
    implementation(libs.media3.editor.exoplayer)

    testImplementation(libs.junit)
}
