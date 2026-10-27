// The UI and server code the Android and desktop apps share. Each app supplies a [com.zakius.finimal.Platform].
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("com.android.kotlin.multiplatform.library")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

kotlin {
    android {
        namespace = "com.zakius.finimal.shared"
        compileSdk = 37
        minSdk = 26
        compilerOptions { jvmTarget = JvmTarget.JVM_21 }
    }
    jvm("desktop") { compilerOptions { jvmTarget = JvmTarget.JVM_21 } }

    sourceSets {
        commonMain.dependencies {
            api(project(":core"))
            api("org.jetbrains.compose.runtime:runtime:1.12.2")
            api("org.jetbrains.compose.foundation:foundation:1.12.2")
            api("org.jetbrains.compose.ui:ui:1.12.2")
            api("org.jetbrains.androidx.navigationevent:navigationevent-compose:1.1.0")
            api("org.jetbrains.compose.material3:material3:1.9.0")
            api("org.jetbrains.compose.material:material-icons-core:1.7.3")
            api("org.jellyfin.sdk:jellyfin-core:1.9.3")
            api("org.slf4j:slf4j-api:2.0.20") // the SDK logs through it; without a binding it is a no-op
            api("io.coil-kt.coil3:coil-compose:3.6.3")
            api("io.coil-kt.coil3:coil-network-okhttp:3.6.3")
            api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
        }
    }
}
