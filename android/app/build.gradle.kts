import java.io.FileInputStream
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Stable APK signing: CI writes android/signing.properties from GitHub
// secrets, so every build shares one signature and updates install cleanly.
val signingPropsFile = rootProject.file("signing.properties")
val signingProps = Properties()
if (signingPropsFile.exists()) {
    signingProps.load(FileInputStream(signingPropsFile))
}

fun signingValue(envName: String, propName: String): String? =
    System.getenv(envName)?.takeIf { it.isNotBlank() }
        ?: signingProps.getProperty(propName)?.takeIf { it.isNotBlank() }

val signingKeystorePath = signingValue("ANDROID_KEYSTORE_FILE", "keystoreFile")
val signingStorePassword = signingValue("ANDROID_KEYSTORE_PASSWORD", "keystorePassword")
val signingKeyAlias = signingValue("ANDROID_KEY_ALIAS", "keyAlias")
val signingKeyPassword = signingValue("ANDROID_KEY_PASSWORD", "keyPassword")
val signingKeystoreFile = signingKeystorePath?.let { rootProject.file(it) }
val hasSigning = signingKeystoreFile != null && signingKeystoreFile.exists() &&
    !signingStorePassword.isNullOrBlank() &&
    !signingKeyAlias.isNullOrBlank() &&
    !signingKeyPassword.isNullOrBlank()

android {
    namespace = "com.codex.mobile"
    compileSdk = 35

    buildFeatures {
        buildConfig = true
    }

    defaultConfig {
        applicationId = "com.codex.mobile.op7"
        minSdk = 24
        // targetSdk 28 allows executing binaries from app data directory.
        // Android 10+ (targetSdk 29+) enforces W^X which blocks this via SELinux.
        // Termux (F-Droid) uses the same approach.
        targetSdk = 28
        // CI auto-increments versionCode (GITHUB_RUN_NUMBER) so every
        // build is installable over the previous one. Local builds fall
        // back to 3.
        versionCode = maxOf(3, System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 3)
        versionName = "0.4.0"
    }

    signingConfigs {
        if (hasSigning) {
            create("release") {
                storeFile = signingKeystoreFile ?: rootProject.file("anyclaw-release.p12")
                storePassword = signingStorePassword
                keyAlias = signingKeyAlias
                keyPassword = signingKeyPassword
            }
        }
    }

    buildTypes {
        debug {
            if (hasSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        release {
            if (hasSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        // java.time (Instant.now() in CodexServerManager) on API 24-25 devices.
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    // targetSdk 28 is intentional: Android 10+ (targetSdk 29+) enforces
    // W^X which blocks executing binaries from the app data directory.
    // This APK is sideloaded, not distributed via Google Play.
    lint {
        disable += "ExpiredTargetSdkVersion"
    }

    // Don't compress bootstrap zip, preinstalled prefix or server bundle
    androidResources {
        noCompress += listOf("zip", "tar.gz")
    }
}

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.4")

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("com.google.android.material:material:1.12.0")

    // Native PTY terminal (Termux terminal-emulator + terminal-view, JitPack)
    implementation("com.github.termux.termux-app:terminal-emulator:v0.118.0")
    implementation("com.github.termux.termux-app:terminal-view:v0.118.0")
}
