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

compose.desktop {
    application {
        mainClass = "com.zakius.finimal.desktop.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Deb, TargetFormat.Msi)
            packageName = "finimal"
            packageVersion = "0.7.0"
        }
    }
}
