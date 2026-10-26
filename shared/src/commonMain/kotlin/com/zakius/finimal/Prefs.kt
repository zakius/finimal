package com.zakius.finimal

class Prefs(private val p: KeyValueStore) {
    var baseUrl by string("baseUrl")
    var token by string("token")
    var userId by string("userId")
    /** Server clock minus device clock, from the last response; see [Server.now]. */
    var clockOffsetMs by long("clockOffsetMs")

    /** In one write, so a kill in between can't leave a token without its user. */
    fun login(url: String, token: String?, user: String) = p.edit { put("baseUrl", url); put("token", token); put("userId", user) }

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
    /** 0 picks by device ([Platform.autoTv]), 1 touch, 2 TV. */
    var interfaceMode by int("interfaceMode", 0)

    /** Direct play limits; see [finimal.core.playsDirectly]. 0 is Auto. */
    var maxHeight by int("maxHeight", 0)
    /** Index into [finimal.core.DirectPlayLimits.Codecs]. */
    var codecs by int("codecs", 0)

    /** Lowest decoder load, in pixels a second, at which the user chose a transcode for choppy [codec] playback. */
    fun learnedPixelRate(codec: String): Long? = (p["learnedRate.$codec"] as Number?)?.toLong()?.takeIf { it > 0 }
    fun learnChoppy(codec: String, pixelRate: Long) {
        if (pixelRate < (learnedPixelRate(codec) ?: Long.MAX_VALUE)) p.edit { put("learnedRate.$codec", pixelRate) }
    }
    fun learnedCodecs() = p.keys().filter { it.startsWith("learnedRate.") }.map { it.removePrefix("learnedRate.") }
    fun forgetLearned() = p.edit { learnedCodecs().forEach { put("learnedRate.$it", null) } }

    /** 0 means "keep next N" is off for the series. */
    fun keepNext(seriesId: String) = (p["keepNext.$seriesId"] as Number?)?.toInt() ?: 0
    fun setKeepNext(seriesId: String, n: Int) = p.edit { put("keepNext.$seriesId", n) }
    fun keepNextSeries(): Map<String, Int> =
        p.keys().filter { it.startsWith("keepNext.") }
            .associate { it.removePrefix("keepNext.") to keepNext(it.removePrefix("keepNext.")) }
            .filterValues { it > 0 }

    /** Per-series track choice as language codes; "" means subtitles off. */
    fun audioLang(seriesId: String): String? = p["audio.$seriesId"] as String?
    fun subLang(seriesId: String): String? = p["sub.$seriesId"] as String?
    fun setTracks(seriesId: String, audio: String?, sub: String?) = p.edit {
        put("audio.$seriesId", audio)
        put("sub.$seriesId", sub)
    }

    val loggedIn get() = token != null

    fun logout() = p.edit { put("token", null); put("userId", null) }

    private fun string(key: String) = object : kotlin.properties.ReadWriteProperty<Any, String?> {
        override fun getValue(thisRef: Any, property: kotlin.reflect.KProperty<*>) = p[key] as String?
        override fun setValue(thisRef: Any, property: kotlin.reflect.KProperty<*>, value: String?) =
            p.edit { put(key, value) }
    }

    private fun bool(key: String, default: Boolean) = object : kotlin.properties.ReadWriteProperty<Any, Boolean> {
        override fun getValue(thisRef: Any, property: kotlin.reflect.KProperty<*>) = p[key] as Boolean? ?: default
        override fun setValue(thisRef: Any, property: kotlin.reflect.KProperty<*>, value: Boolean) =
            p.edit { put(key, value) }
    }

    private fun long(key: String) = object : kotlin.properties.ReadWriteProperty<Any, Long> {
        override fun getValue(thisRef: Any, property: kotlin.reflect.KProperty<*>) = (p[key] as Number?)?.toLong() ?: 0
        override fun setValue(thisRef: Any, property: kotlin.reflect.KProperty<*>, value: Long) =
            p.edit { put(key, value) }
    }

    private fun int(key: String, default: Int) = object : kotlin.properties.ReadWriteProperty<Any, Int> {
        override fun getValue(thisRef: Any, property: kotlin.reflect.KProperty<*>) = (p[key] as Number?)?.toInt() ?: default
        override fun setValue(thisRef: Any, property: kotlin.reflect.KProperty<*>, value: Int) =
            p.edit { put(key, value) }
    }
}
