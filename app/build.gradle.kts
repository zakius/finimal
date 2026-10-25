plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.zakius.plainfin"
    compileSdk = 37
    buildToolsVersion = "37.0.0"

    defaultConfig {
        applicationId = "com.zakius.plainfin"
        minSdk = 26
        targetSdk = 36
        versionCode = 9
        versionName = "0.3.0"
    }

    // CI passes the release key through environment variables; local release builds stay unsigned.
    val keystore = System.getenv("PLAINFIN_KEYSTORE")
    signingConfigs {
        if (keystore != null) create("release") {
            storeFile = file(keystore)
            storePassword = System.getenv("PLAINFIN_KEYSTORE_PASSWORD")
            keyAlias = System.getenv("PLAINFIN_KEY_ALIAS")
            keyPassword = System.getenv("PLAINFIN_KEY_PASSWORD")
        }
    }

    buildTypes {
        debug { applicationIdSuffix = ".debug" }
        release {
            if (keystore != null) signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    // Native libs (libass, ffmpeg) are most of the APK: one APK per ABI, libs compressed for a smaller download.
    splits.abi {
        isEnable = true
        reset()
        include("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
        isUniversalApk = false
    }
    packaging.jniLibs.useLegacyPackaging = true
    // The UI is English only; library translations for ~80 locales would just ship unused.
    androidResources { localeFilters += "en" }
    // Sideloaded through GitHub, so no Play-only dependency metadata blob.
    dependenciesInfo { includeInApk = false; includeInBundle = false }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    buildFeatures { compose = true; buildConfig = true }
}

dependencies {
    implementation(project(":core"))

    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.compose.ui:ui:1.12.1")
    implementation("androidx.compose.foundation:foundation:1.12.1")
    implementation("androidx.compose.material3:material3:1.4.0")
    implementation("androidx.compose.material:material-icons-core:1.7.8")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("androidx.lifecycle:lifecycle-process:2.11.0")
    implementation("androidx.work:work-runtime:2.12.0")

    implementation("androidx.media3:media3-exoplayer:1.11.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.11.1")
    implementation("androidx.media3:media3-datasource-okhttp:1.11.1")
    implementation("androidx.media3:media3-ui:1.11.1")
    implementation("org.jellyfin.media3:media3-ffmpeg-decoder:1.9.0+1")
    implementation("io.github.peerless2012:ass-media:0.5.1")

    implementation("org.jellyfin.sdk:jellyfin-core:1.9.3")
    implementation("org.slf4j:slf4j-api:2.0.20") // the SDK logs through it; without a binding it is a no-op
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    implementation("io.coil-kt.coil3:coil-compose:3.6.3")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.6.3")
}
