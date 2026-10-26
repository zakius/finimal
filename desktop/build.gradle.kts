// The desktop app: the shared UI in a window, playback through libmpv.
import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}
kotlin { compilerOptions { jvmTarget = JvmTarget.JVM_21 } }

dependencies {
    implementation(project(":shared"))
    implementation(compose.desktop.currentOs)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.11.0")
    implementation("net.java.dev.jna:jna:5.19.1")
    testImplementation(kotlin("test"))
}

tasks.test { useJUnitPlatform() }

val versionName = providers.gradleProperty("finimal.versionName").get()

compose.desktop {
    application {
        mainClass = "com.zakius.finimal.desktop.MainKt"
        jvmArgs += "-Dfinimal.version=$versionName"
        nativeDistributions {
            // Linux ships as a Flatpak (packaging/flatpak), built from createDistributable's app image.
            targetFormats(TargetFormat.Msi)
            packageName = "finimal"
            packageVersion = versionName
            description = "Jellyfin client for movies and TV"
            vendor = "zakius"
            licenseFile = rootProject.file("LICENSE")
            // windows/libmpv-2.dll from tools/fetch-libmpv-windows.sh; Linux uses the distribution's libmpv2.
            appResourcesRootDir = layout.projectDirectory.dir("resources")
            // What the code needs beyond java.base and the desktop modules: OkHttp's DNS and TLS, JNA.
            modules("java.naming", "jdk.crypto.ec", "jdk.unsupported")
            windows {
                iconFile = rootProject.file("packaging/finimal.ico")
                menuGroup = "finimal"
                perUserInstall = true
                shortcut = true
                // Fixed, so a new MSI replaces the installed version instead of installing beside it.
                upgradeUuid = "3f7c2a4e-8b1d-4e6a-9c2f-5d8e1a7b4c90"
            }
        }
    }
}
