import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// Version and repo come from Gradle properties so CI can override them with -P flags.
// Defaults live in ../gradle.properties; the fallbacks here apply if that file is missing them.
val appVersionName: String = (findProperty("appVersionName") as String?)?.takeIf { it.isNotBlank() } ?: "0.1.0"
val appVersionCode: Int = (findProperty("appVersionCode") as String?)?.toIntOrNull() ?: 100
val githubRepo: String = (findProperty("githubRepo") as String?)?.takeIf { it.isNotBlank() } ?: "mastifdogo/devotion_app"

// Release signing from the environment; without it the release build is signed with the debug key.
val keystorePath: String? = System.getenv("ANDROID_KEYSTORE_FILE")?.takeIf { it.isNotBlank() }

android {
    namespace = "com.mastifdogo.devotion"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.mastifdogo.devotion"
        minSdk = 26
        targetSdk = 35
        versionCode = appVersionCode
        versionName = appVersionName

        buildConfigField("String", "GITHUB_REPO", "\"$githubRepo\"")
    }

    signingConfigs {
        if (keystorePath != null) {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("ANDROID_KEY_ALIAS")
                keyPassword = System.getenv("ANDROID_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = if (keystorePath != null) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    lint {
        // Lint must never be the reason a release APK is not produced.
        checkReleaseBuilds = false
        abortOnError = false
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.06.00")
    implementation(composeBom)
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material:material-icons-core")

    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.1")
    implementation("androidx.media3:media3-exoplayer:1.7.1")
    // Background playback: MediaSessionService + MediaController, media notification.
    implementation("androidx.media3:media3-session:1.7.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // Wallpaper thumbnails and previews streamed from the server (memory cache only).
    implementation("io.coil-kt:coil-compose:2.7.0")
    // Background sync (Every hour / Every 6 hours / Once a day).
    implementation("androidx.work:work-runtime-ktx:2.10.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    testImplementation("junit:junit:4.13.2")
}
