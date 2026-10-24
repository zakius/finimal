package com.zakius.plainfin

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import java.io.File
import kotlin.concurrent.thread
import kotlinx.coroutines.Dispatchers
import plainfin.core.PendingStore

/** Process-wide singletons. There is one server and one user, so nothing here needs scoping. */
class App : Application(), SingletonImageLoader.Factory {
    override fun onCreate() {
        super.onCreate()
        instance = this
        Media.onAppStart(this)
        thread(name = "warm-server") { server } // builds the API client before the first frame asks for it
    }

    override fun newImageLoader(context: PlatformContext) = ImageLoader.Builder(context)
        .components { add(OkHttpNetworkFetcherFactory(callFactory = { Media.http })) }
        .build()

    companion object {
        lateinit var instance: App
            private set

        val prefs by lazy { Prefs(instance.getSharedPreferences("plainfin", Context.MODE_PRIVATE)) }
        val server by lazy { Server(instance) }
        val pending by lazy { PendingStore(File(instance.filesDir, "pending.json")) }

        /**
         * IO one task at a time, in the order submitted. Play-state records and [Server.flush]'s read
         * share it, so a stop recorded on leaving the player lands before the next list flushes.
         */
        val disk = Dispatchers.IO.limitedParallelism(1)
    }
}

class Prefs(private val p: SharedPreferences) {
    var baseUrl by string("baseUrl")
    var token by string("token")
    var userId by string("userId")

    var bufferOnMetered by bool("bufferOnMetered", false)
    var downloadOnMetered by bool("downloadOnMetered", false)
    var cacheLimitGb by int("cacheLimitGb", 5)
    var brightnessOnLeft by bool("brightnessOnLeft", true)
    var gestureLevels by bool("gestureLevels", true)
    var gestureSeek by bool("gestureSeek", true)
    var gestureDoubleTap by bool("gestureDoubleTap", true)
    var gesturePinch by bool("gesturePinch", true)
    var gestureFullscreen by bool("gestureFullscreen", true)
    var doubleTapSeconds by int("doubleTapSeconds", 10)
    var subtitleScale by int("subtitleScale", 100)
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

    private fun int(key: String, default: Int) = object : kotlin.properties.ReadWriteProperty<Any, Int> {
        override fun getValue(thisRef: Any, property: kotlin.reflect.KProperty<*>) = p.getInt(key, default)
        override fun setValue(thisRef: Any, property: kotlin.reflect.KProperty<*>, value: Int) =
            p.edit { putInt(key, value) }
    }
}
