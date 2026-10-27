# Shrinking and renaming, which takes a fifth off the jars; no optimizing (ProGuard 7.8 fails on Kotlin 2.4's code).
# Stack traces keep line numbers and read through build/proguard-mapping.txt (retrace; each release attaches it).
# Exception names stay, as the UI shows them.
-dontoptimize
-keepattributes *Annotation*, InnerClasses, Signature, Exceptions, EnclosingMethod, SourceFile, LineNumberTable
-renamesourcefileattribute SourceFile
-printmapping build/proguard-mapping.txt
-keepnames class * extends java.lang.Throwable
# ServiceLoader finds implementations by the names in these files.
-adaptresourcefilenames META-INF/services/*
-adaptresourcefilecontents META-INF/services/*

# Skia's native code calls back through Function0 by name.
-keep interface kotlin.jvm.functions.Function0 { *; }

# JNA binds libmpv and libc through these interfaces by reflection.
-keep class com.sun.jna.** { *; }
-keep interface * extends com.sun.jna.Library { *; }
-keep interface * extends com.sun.jna.Callback { *; }

# dbus-java builds proxies of the screensaver interfaces and finds its transport by ServiceLoader.
-keep class org.freedesktop.dbus.** { *; }
-keep interface * extends org.freedesktop.dbus.interfaces.DBusInterface { *; }

# What jars register in META-INF/services must stay: ProGuard, unlike R8, doesn't keep it on its own.
-keep class * implements coil3.util.FetcherServiceLoaderTarget { *; }
-keep class * implements coil3.util.DecoderServiceLoaderTarget { *; }
-keep class * implements kotlinx.coroutines.internal.MainDispatcherFactory { *; }

# kotlinx.serialization: its own rules (META-INF/proguard in its jar, which ProGuard doesn't read), keeping the
# serializers of the classes the app reaches, not every Jellyfin model.
-keepclassmembers @kotlinx.serialization.Serializable class ** { static ** Companion; }
-if @kotlinx.serialization.internal.NamedCompanion class *
-keepclassmembers class * { static <1> *; }
-if @kotlinx.serialization.Serializable class ** { static **$* *; }
-keepclassmembers class <2>$<3> { kotlinx.serialization.KSerializer serializer(...); }
-if @kotlinx.serialization.Serializable class ** { public static ** INSTANCE; }
-keepclassmembers class <1> { public static <1> INSTANCE; kotlinx.serialization.KSerializer serializer(...); }
-keepclassmembers public class **$$serializer { private ** descriptor; }

# Optional dependencies of OkHttp, kotlinx and the SDK that the app doesn't use.
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-dontwarn org.slf4j.**
-dontwarn kotlinx.datetime.**
-dontwarn io.ktor.**
-dontwarn org.jetbrains.annotations.**
# kotlin-logging (the SDK's) supports Logback and GraalVM native images; neither is here.
-dontwarn ch.qos.logback.**
-dontwarn com.oracle.svm.**
-dontwarn org.graalvm.**
-dontwarn kotlinx.coroutines.slf4j.**
-dontnote **
