// Plain JVM module: no Android imports, so a desktop client could reuse it.
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}
kotlin { compilerOptions { jvmTarget = JvmTarget.JVM_21 } }

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    implementation("com.squareup.okhttp3:okhttp:5.5.1") // the version the app's libraries already pull in
    testImplementation(kotlin("test"))
}

tasks.test { useJUnitPlatform() }
