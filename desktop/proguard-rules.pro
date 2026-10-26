# Shrinking only: no renaming or optimizing, so stack traces read as written and nothing changes behaviour.
-dontobfuscate
-dontoptimize
-keepattributes *Annotation*, InnerClasses, Signature, Exceptions, EnclosingMethod

# JNA binds libmpv and libc through these interfaces by reflection.
-keep class com.sun.jna.** { *; }
-keep interface * extends com.sun.jna.Library { *; }
-keep interface * extends com.sun.jna.Callback { *; }

# dbus-java builds proxies of the screensaver interfaces and finds its transport by ServiceLoader.
-keep class org.freedesktop.dbus.** { *; }
-keep interface * extends org.freedesktop.dbus.interfaces.DBusInterface { *; }

# kotlinx.serialization: the generated serializers of our classes and the Jellyfin SDK's.
-keep,includedescriptorclasses class **$$serializer { *; }
-keepclassmembers class * { *** Companion; }
-keepclasseswithmembers class * { kotlinx.serialization.KSerializer serializer(...); }
-keep class org.jellyfin.sdk.model.** { *; }

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
