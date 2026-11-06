plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.zakius.finimal"
    compileSdk = 37
    buildToolsVersion = "37.0.0"

    defaultConfig {
        applicationId = "com.zakius.finimal"
        minSdk = 26
        targetSdk = 36
        versionCode = providers.gradleProperty("finimal.versionCode").get().toInt()
        versionName = providers.gradleProperty("finimal.versionName").get()
    }

    buildTypes {
        debug { applicationIdSuffix = ".debug" }
        // Left unsigned: tools/release.sh embeds the baseline profile first and signs afterwards.
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // The SDK's keep rules preserve all of its ~1,300 API models, used or not. kotlinx.serialization doesn't need
            // them, because its own rules keep the serializers of the models the app actually uses.
            optimization { keepRules { ignoreFrom("org.jellyfin.sdk:jellyfin-core-android") } }
        }
    }

    // Native libraries (libass, ffmpeg) make up most of the APK, so build one APK per ABI and compress the libraries
    // for a smaller download.
    splits.abi {
        isEnable = true
        reset()
        include("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
        isUniversalApk = false
    }
    packaging.jniLibs.useLegacyPackaging = true
    // ass-kt ships libc++_shared, but neither libass nor libasskt links against it (they import only libc), so it is
    // never loaded.
    packaging.jniLibs.excludes += "**/libc++_shared.so"
    // Only kotlin-reflect (not a dependency) and the coroutines debug agent read these.
    packaging.resources.excludes += listOf("kotlin/**", "DebugProbesKt.bin")
    // The UI is English only, so library translations for ~80 locales would ship unused.
    // OkHttp reads its public suffix list only to parse cookies, and no client here has a cookie jar.
    androidResources { localeFilters += "en"; ignoreAssetsPatterns += "PublicSuffixDatabase.list" }
    // The app is distributed through GitHub, so the Play-only dependency metadata blob is useless.
    dependenciesInfo { includeInApk = false; includeInBundle = false }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    buildFeatures { compose = true; buildConfig = true }
}

dependencies {
    implementation(project(":shared"))

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
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    implementation("io.coil-kt.coil3:coil-compose:3.6.3")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.6.3")
}
