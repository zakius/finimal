// The desktop app: the shared UI in a window, playback through libmpv.
import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Arrays

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

// JNA's jar carries its native part for ~30 platforms; the app is built for Linux and Windows on x86-64 only.
val jna = configurations.create("jna")
val jnaTrimmed = tasks.register<Jar>("jnaTrimmed") {
    archiveFileName = "jna.jar"
    destinationDirectory = layout.buildDirectory.dir("jna")
    from({ zipTree(jna.singleFile) }) {
        exclude("META-INF/MANIFEST.MF")
        exclude { it.path.matches(Regex("com/sun/jna/[^/]+/(lib)?jnidispatch\\..*")) && !it.path.matches(Regex("com/sun/jna/(linux|win32)-x86-64/.*")) }
    }
}

dependencies {
    implementation(project(":shared"))
    implementation(compose.desktop.currentOs)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.11.0")
    jna("net.java.dev.jna:jna:5.19.1")
    implementation(files(jnaTrimmed))
    // Screensaver inhibition over D-Bus; the transport uses the JDK's own Unix sockets.
    implementation("com.github.hypfvieh:dbus-java-core:5.2.2")
    implementation("com.github.hypfvieh:dbus-java-transport-native-unixsocket:5.2.2")
    testImplementation(kotlin("test"))
}

tasks.test { useJUnitPlatform() }

val versionName = providers.gradleProperty("finimal.versionName").get()

compose.desktop {
    application {
        mainClass = "com.zakius.finimal.desktop.MainKt"
        jvmArgs += "-Dfinimal.version=$versionName"
        // The release distribution (createReleaseDistributable) drops what the app never calls: about half the jars.
        buildTypes.release.proguard {
            configurationFiles.from(project.file("proguard-rules.pro"))
            obfuscate = true
        }
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
            // Beyond java.base and the desktop modules: OkHttp's DNS and TLS, JNA, and dbus-java's login as the user's uid.
            modules("java.naming", "jdk.crypto.ec", "jdk.unsupported", "jdk.security.auth")
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

// Skia's library (Linux; on Windows its icudtl.dat) carries ICU's whole data (10.4 MB) and reads only its line, word
// and character breaking. The rest (collation, time zones, locale names, converters, the Chinese/Japanese word
// dictionary) goes: the blob keeps its place and size, its table of contents lists only what stays, and what's freed
// is zeros, which the packages' compression takes out. :desktop:skiaCheck shows text lays out as with the full data.
// Without the dictionary, word steps (Ctrl+arrow, double-click) in Chinese and Japanese go one character at a time.
fun trimSkiaIcu(lib: File) {
    val bytes = lib.readBytes()
    val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    // ICU's common data: a header ("CmnD" at 12), then the table: count, (name offset, data offset) pairs, the names,
    // the items in name order, each up to where the next begins. Offsets count from the table.
    val header = (0 until bytes.size - 16).single { i ->
        bytes[i + 2] == 0xda.toByte() && bytes[i + 3] == 0x27.toByte() && String(bytes, i + 12, 4) == "CmnD"
    }
    val toc = header + b.getShort(header).toInt()
    val count = b.getInt(toc)
    fun name(k: Int) = String(bytes, toc + b.getInt(toc + 4 + 8 * k), 64).substringBefore('\u0000').substringAfter('/')
    fun data(k: Int) = toc + b.getInt(toc + 8 + 8 * k)
    val keepTop = setOf("root", "pool", "res_index", "supplementalData", "metadata", "icuver", "icustd", "keyTypeData", "langInfo")
    val kept = (0 until count).filter { k ->
        val n = name(k)
        if (n.startsWith("brkitr/")) n != "brkitr/cjdict.dict"
        else '/' !in n && (n.endsWith(".icu") || n.endsWith(".nrm") || n.removeSuffix(".res") in keepTop)
    }
    // The last item's size isn't recorded; it's a locale's (zu_ZA.res), which goes.
    check(kept.size in 20 until count && count - 1 !in kept) { "${lib.name}: unexpected ICU data" }
    val out = bytes.copyOf()
    val o = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN)
    Arrays.fill(out, toc + 4 + 8 * kept.size, toc + 4 + 8 * count, 0)
    Arrays.fill(out, data(0), data(count - 1), 0)
    o.putInt(toc, kept.size)
    var at = data(0)
    kept.forEachIndexed { i, k ->
        o.putInt(toc + 4 + 8 * i, b.getInt(toc + 4 + 8 * k))
        o.putInt(toc + 8 + 8 * i, at - toc)
        System.arraycopy(bytes, data(k), out, at, data(k + 1) - data(k))
        at += data(k + 1) - data(k)
    }
    lib.writeBytes(out)
}
// Linux: also Skia's symbol table (3 MB). Its exported symbols stay; the Java runtime keeps its own for crash logs.
tasks.matching { it.name == "createDistributable" || it.name == "createReleaseDistributable" }.configureEach {
    doLast {
        val libs = outputs.files.asFileTree.matching { include("**/lib/app/*skiko*.so", "**/app/icudtl.dat") }.files
        check(libs.isNotEmpty()) { "no ICU data of Skia's in ${outputs.files.files}" }
        libs.forEach(::trimSkiaIcu)
        if (System.getProperty("os.name") != "Linux") return@doLast
        val strip = ProcessBuilder(listOf("strip", "--strip-unneeded") + libs.map { it.path }).inheritIO().start()
        check(strip.waitFor() == 0) { "strip failed" }
    }
}

// The original Skia files, for skiaCheck to compare the trimmed ones with, each in a JVM of its own.
val skiaFull = tasks.register<Copy>("skiaFull") {
    from({ configurations["runtimeClasspath"].filter { it.name.startsWith("skiko-awt-runtime-") }.map { zipTree(it) } })
    include("*.so", "*.dll", "icudtl.dat")
    into(layout.buildDirectory.dir("skia-full"))
}
tasks.register<JavaExec>("skiaCheck") {
    classpath = sourceSets["test"].runtimeClasspath
    mainClass = "com.zakius.finimal.desktop.SkiaCheckKt"
    val windows = System.getProperty("os.name").startsWith("Windows")
    val app = "compose/binaries/main-release/app/finimal/" + if (windows) "app" else "lib/app" // jpackage's layouts
    args(skiaFull.get().destinationDir.path, layout.buildDirectory.dir(app).get().asFile.path)
    dependsOn(skiaFull, "createReleaseDistributable")
}

// Compose's default ProGuard rules keep all of Kotlin's standard library, names and all, and with it much that the
// app never calls: without that one rule the release jars are 5.6 MB smaller. Skia's native code needs only Function0
// (proguard-rules.pro).
afterEvaluate {
    val proguard = tasks.withType<org.jetbrains.compose.desktop.application.tasks.AbstractProguardTask>()
    val stock = proguard.first().defaultComposeRulesFile.get().asFile
    val trimmed = layout.buildDirectory.file("compose-proguard-rules.pro")
    val trimRules = tasks.register("trimComposeProguardRules") {
        dependsOn("unpackDefaultComposeDesktopJvmApplicationResources")
        inputs.file(stock)
        outputs.file(trimmed)
        doLast {
            val rules = stock.readLines()
            val kept = rules.filterNot { it == "-keep class kotlin.** { *; }" }
            check(kept.size < rules.size) { "Compose's default rules no longer keep kotlin.**; this filter can go" }
            trimmed.get().asFile.writeText(kept.joinToString("\n", postfix = "\n"))
        }
    }
    proguard.configureEach {
        dependsOn(trimRules)
        defaultComposeRulesFile.set(trimmed)
    }
}
