package com.zakius.plainfin

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil
import java.io.File
import okhttp3.Dispatcher
import kotlin.concurrent.thread
import kotlinx.coroutines.Dispatchers
import plainfin.core.PendingStore

/** Process-wide singletons. There is one server and one user, so nothing here needs scoping. */
class App : Application(), SingletonImageLoader.Factory {
    override fun onCreate() {
        super.onCreate()
        instance = this
        Media.onAppStart(this)
        thread(name = "warm-up") {
            server // builds the API client before the first frame asks for it
            warmDecoders()
        }
    }

    // Shared connections, own dispatcher: a screen of thumbnails must not queue behind or ahead of API calls (5 per host each).
    override fun newImageLoader(context: PlatformContext) = ImageLoader.Builder(context)
        .components { add(OkHttpNetworkFetcherFactory(callFactory = { Media.http.newBuilder().dispatcher(Dispatcher()).build() })) }
        .build()

    /** The first codec-list query in a process takes up to a few hundred ms; Media3 caches it, so the first play skips that. */
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private fun warmDecoders() = listOf(MimeTypes.VIDEO_H264, MimeTypes.VIDEO_H265, MimeTypes.VIDEO_AV1, MimeTypes.AUDIO_AAC)
        .forEach { runCatching { MediaCodecUtil.getDecoderInfos(it, false, false) } }

    companion object {
        lateinit var instance: App
            private set

        val prefs by lazy { Prefs(instance.getSharedPreferences("plainfin", Context.MODE_PRIVATE)) }
        val server by lazy { Server(instance) }
        /**
         * Unsynced play states of the logged-in account. Each server and user has its own file, so
         * progress kept over a logout waits for that account and never reaches another.
         */
        val pending: PendingStore get() = synchronized(this) {
            val account = prefs.baseUrl to prefs.userId
            pendingStore?.takeIf { pendingAccount == account } ?: run {
                val name = java.security.MessageDigest.getInstance("SHA-1").digest("${account.first}\u0000${account.second}".toByteArray())
                    .take(8).joinToString("") { "%02x".format(it) }
                val file = File(instance.filesDir, "pending-$name.json")
                // From before per-account files: it belongs to whoever logs in next, as it always did.
                val legacy = File(instance.filesDir, "pending.json")
                if (account.second != null && legacy.exists() && !file.exists()) legacy.renameTo(file)
                PendingStore(file).also { pendingStore = it; pendingAccount = account }
            }
        }
        private var pendingStore: PendingStore? = null
        private var pendingAccount: Pair<String?, String?>? = null

        /**
         * IO one task at a time, in the order submitted. Play-state records and [Server.flush]'s read
         * share it, so a stop recorded on leaving the player lands before the next list flushes.
         */
        val disk = Dispatchers.IO.limitedParallelism(1)

        /** Buffer start and stop, in order, apart from [disk] so cache and download locks never hold up play-state records. */
        val buffers = Dispatchers.IO.limitedParallelism(1)
    }
}

class Prefs(private val p: SharedPreferences) {
    var baseUrl by string("baseUrl")
    var token by string("token")
    var userId by string("userId")
    /** Server clock minus device clock, from the last response; see [Server.now]. */
    var clockOffsetMs by long("clockOffsetMs")

    /** In one write, so a kill in between can't leave a token without its user. */
    fun login(url: String, token: String?, user: String) = p.edit { putString("baseUrl", url); putString("token", token); putString("userId", user) }

    var bufferOnMetered by bool("bufferOnMetered", false)
    var downloadOnMetered by bool("downloadOnMetered", false)
    var cacheLimitGb by int("cacheLimitGb", 5)
    var brightnessOnLeft by bool("brightnessOnLeft", true)
    var gestureLevels by bool("gestureLevels", true)
    var gestureSeek by bool("gestureSeek", true)
    var gestureDoubleTap by bool("gestureDoubleTap", true)
    var gestureHold by bool("gestureHold", true)
    var gesturePinch by bool("gesturePinch", true)
    var gestureFullscreen by bool("gestureFullscreen", true)
    var doubleTapSeconds by int("doubleTapSeconds", 10)
    var subtitleScale by int("subtitleScale", 100)
    /** Applied only while a wireless output is connected; see [AudioDelay]. */
    var audioDelayMs by int("audioDelayMs", 0)
    var storageIndex by int("storageIndex", 0)

    /** 0 means "keep next N" is off for the series. */
    fun keepNext(seriesId: String) = p.getInt("keepNext.$seriesId", 0)
    fun setKeepNext(seriesId: String, n: Int) = p.edit { putInt("keepNext.$seriesId", n) }
    fun keepNextSeries(): Map<String, Int> =
        p.all.filterKeys { it.startsWith("keepNext.") }
            .mapKeys { it.key.removePrefix("keepNext.") }
            .mapValues { it.value as Int }
            .filterValues { it > 0 }

    /** Per-series track choice as language codes; "" means subtitles off. */
    fun audioLang(seriesId: String): String? = p.getString("audio.$seriesId", null)
    fun subLang(seriesId: String): String? = p.getString("sub.$seriesId", null)
    fun setTracks(seriesId: String, audio: String?, sub: String?) = p.edit {
        putString("audio.$seriesId", audio)
        putString("sub.$seriesId", sub)
    }

    fun logout() = p.edit { remove("token"); remove("userId") }

    private fun string(key: String) = object : kotlin.properties.ReadWriteProperty<Any, String?> {
        override fun getValue(thisRef: Any, property: kotlin.reflect.KProperty<*>) = p.getString(key, null)
        override fun setValue(thisRef: Any, property: kotlin.reflect.KProperty<*>, value: String?) =
            p.edit { putString(key, value) }
    }

    private fun bool(key: String, default: Boolean) = object : kotlin.properties.ReadWriteProperty<Any, Boolean> {
        override fun getValue(thisRef: Any, property: kotlin.reflect.KProperty<*>) = p.getBoolean(key, default)
        override fun setValue(thisRef: Any, property: kotlin.reflect.KProperty<*>, value: Boolean) =
            p.edit { putBoolean(key, value) }
    }

    private fun long(key: String) = object : kotlin.properties.ReadWriteProperty<Any, Long> {
        override fun getValue(thisRef: Any, property: kotlin.reflect.KProperty<*>) = p.getLong(key, 0)
        override fun setValue(thisRef: Any, property: kotlin.reflect.KProperty<*>, value: Long) =
            p.edit { putLong(key, value) }
    }

    private fun int(key: String, default: Int) = object : kotlin.properties.ReadWriteProperty<Any, Int> {
        override fun getValue(thisRef: Any, property: kotlin.reflect.KProperty<*>) = p.getInt(key, default)
        override fun setValue(thisRef: Any, property: kotlin.reflect.KProperty<*>, value: Int) =
            p.edit { putInt(key, value) }
    }
}
