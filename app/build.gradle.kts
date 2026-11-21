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

composeCompiler {
    includeSourceInformation = false
    includeTraceMarkers = false
}

// Demote third-party library startup ('S') and AppImage class ('L...;') rules from merged baseline profiles to hot-AOT
// ('HP'), so ART still compiles Compose/Material3/Coil/WorkManager/Media3 AOT for smooth runtime speed without
// bloating base.art (AppImage) during bindApplication on our native-View cold start.
tasks.matching { it.name == "mergeReleaseArtProfile" }.configureEach {
    doLast {
        val out = outputs.files.singleFile
        val keepStartupPrefixes = listOf(
            "Lcom/zakius/finimal/AndroidApp",
            "Lcom/zakius/finimal/MainActivity",
            "Lcom/zakius/finimal/LibrariesFastView",
            "Lcom/zakius/finimal/LoginFastView",
            "Lcom/zakius/finimal/Glyph",
            "Lcom/zakius/finimal/AvatarCircleDrawView",
            "Lcom/zakius/finimal/DividerDrawView",
            "Lcom/zakius/finimal/OutlinedGlyphBox",
            "Lcom/zakius/finimal/FastColors",
            "Lcom/zakius/finimal/FastStart",
            "Lcom/zakius/finimal/App",
            "Lcom/zakius/finimal/Prefs",
            "Lcom/zakius/finimal/ListSnapshots",
            "Lcom/zakius/finimal/LibraryEntry",
            "Lcom/zakius/finimal/Platform_android",
            "Lcom/zakius/finimal/Media",
            "Landroidx/activity/",
            "Landroidx/core/",
            "Landroidx/lifecycle/Lifecycle",
            "Landroidx/lifecycle/ReportFragment",
        )
        val trimmed = out.readLines().mapNotNull { line ->
            val t = line.trim()
            if (t.isEmpty() || t.startsWith("#")) return@mapNotNull null
            val classStart = t.indexOf('L')
            if (classStart < 0) return@mapNotNull t
            val desc = t.substring(classStart)
            val isStartup = keepStartupPrefixes.any { desc.startsWith(it) }
            if (isStartup) return@mapNotNull t
            if (classStart == 0) return@mapNotNull null // Drop non-startup AppImage class entry (L...;)
            val flags = t.substring(0, classStart).replace("S", "")
            if (flags.isEmpty()) null else flags + desc
        }
        out.writeText(trimmed.joinToString("\n") + "\n")
    }
}

dependencies {
    implementation(project(":shared"))

    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.compose.ui:ui:1.12.1")
    implementation("androidx.compose.foundation:foundation:1.12.1")
    implementation("androidx.compose.material3:material3:1.4.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")
    implementation("com.squareup.okhttp3:okhttp-android:5.5.1")
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
