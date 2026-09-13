import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// Release signing credentials are resolved in priority order:
//   1. environment variables (MONIVITALS_STORE_FILE / _STORE_PASSWORD / _KEY_ALIAS / _KEY_PASSWORD)
//   2. Gradle properties (monivitals.storeFile / .storePassword / .keyAlias / .keyPassword),
//      typically set in ~/.gradle/gradle.properties so secrets never live in the repo
//   3. keystore.properties in the project root (legacy fallback — this file holds plaintext
//      passwords, so it should live OUTSIDE the repo; prefer options 1 or 2 and delete it)
// If nothing resolves, the release build falls back to the debug keystore so the project
// always builds a runnable (though not Play-signable) APK. Never commit real secrets.
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
}

fun signingSecret(envKey: String, gradleKey: String, fileKey: String): String? =
    System.getenv(envKey)?.takeIf { it.isNotBlank() }
        ?: (project.findProperty(gradleKey) as? String)?.takeIf { it.isNotBlank() }
        ?: keystoreProps.getProperty(fileKey)

val releaseStorePath = signingSecret("MONIVITALS_STORE_FILE", "monivitals.storeFile", "storeFile")
val releaseStorePassword = signingSecret("MONIVITALS_STORE_PASSWORD", "monivitals.storePassword", "storePassword")
val releaseKeyAlias = signingSecret("MONIVITALS_KEY_ALIAS", "monivitals.keyAlias", "keyAlias")
val releaseKeyPassword = signingSecret("MONIVITALS_KEY_PASSWORD", "monivitals.keyPassword", "keyPassword")
// Resolve storeFile relative to the project root (not the :app module dir); absolute paths pass through.
val releaseStoreFile = releaseStorePath?.let { rootProject.file(it) }
val hasReleaseKeystore = releaseStoreFile?.exists() == true &&
    releaseStorePassword != null && releaseKeyAlias != null && releaseKeyPassword != null

// App Check debug token (Firebase console → App Check → Manage debug tokens), read from
// the gitignored local.properties (`appcheck.debug.token`) so it never lives in committed
// source. Injected into DEBUG builds only; release always compiles in an empty string.
val localPropsFile = rootProject.file("local.properties")
val localProps = Properties().apply {
    if (localPropsFile.exists()) localPropsFile.inputStream().use { load(it) }
}
val appCheckDebugToken = localProps.getProperty("appcheck.debug.token")?.trim().orEmpty()

android {
    namespace = "com.monivitals"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.monivitals.app"
        minSdk = 26
        // Play requires targetSdk 36 (Android 16) for new submissions/updates.
        targetSdk = 36
        versionCode = 2
        versionName = "1.0.1"
    }

    // Cloud API-key Android restriction: register BOTH the debug AND release keystore SHA-1s
    // (applicationId com.monivitals.app) or debug cloud calls 403 once the key is enforced.
    signingConfigs {
        if (hasReleaseKeystore) {
            create("release") {
                storeFile = releaseStoreFile
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        debug {
            // Console-issued App Check debug token (AppCheckService); empty when unset.
            buildConfigField("String", "APP_CHECK_DEBUG_TOKEN", "\"$appCheckDebugToken\"")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (hasReleaseKeystore) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
            // Never ship the debug token: release builds get an empty constant.
            buildConfigField("String", "APP_CHECK_DEBUG_TOKEN", "\"\"")
            // Package native debug symbols into the bundle so Play can
            // symbolicate crashes/ANRs in the androidx .so dependencies.
            ndk {
                debugSymbolLevel = "FULL"
            }
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
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.09.03")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // CameraX: fingertip pulse capture via the rear camera (torch on) + per-frame analysis.
    implementation("androidx.camera:camera-core:1.3.4")
    implementation("androidx.camera:camera-camera2:1.3.4")
    implementation("androidx.camera:camera-lifecycle:1.3.4")

    // Auth tokens at rest: EncryptedSharedPreferences (master key in the Android Keystore).
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // Federated sign-in (docs/CLOUD_OAUTH.md): Google via Credential Manager, Apple via
    // Custom Tabs. No Firebase SDK — the provider id_token is exchanged over REST.
    implementation("androidx.credentials:credentials:1.3.0")
    implementation("androidx.credentials:credentials-play-services-auth:1.3.0")
    implementation("com.google.android.libraries.identity.googleid:googleid:1.1.1")
    implementation("androidx.browser:browser:1.8.0")

    // Play Integrity attestation for Firebase App Check (AppCheckService classic flow).
    implementation("com.google.android.play:integrity:1.4.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
}
