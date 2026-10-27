package com.zakius.finimal

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import java.security.SecureRandom

/** A login kept on this device. [pin] is "salt:hash" ([Prefs.pinHash]); a household lock, not security: the token sits beside it. */
@Serializable
data class Account(val userId: String, val name: String, val token: String, val pin: String? = null)

class Prefs(private val p: KeyValueStore) {
    var baseUrl by string("baseUrl")
    var token by string("token")
    var userId by string("userId")
    /** Server clock minus device clock, from the last response; see [Server.now]. */
    var clockOffsetMs by long("clockOffsetMs")

    /** The accounts on [baseUrl]: one server, its users. The current one is [userId], with its token in [token]. */
    fun accounts(): List<Account> = (p["accounts"] as String?)?.let { runCatching { json.decodeFromString<List<Account>>(it) }.getOrNull() }.orEmpty()

    /**
     * Adds or refreshes [user]'s account and makes it current, in one write, so a kill in between can't leave a token
     * without its user. Another server replaces every account: one server at a time.
     */
    fun login(url: String, token: String, user: String, name: String) {
        val kept = if (url == baseUrl) accounts().filter { it.userId != user } else emptyList()
        val pin = accounts().firstOrNull { it.userId == user && url == baseUrl }?.pin
        p.edit { put("baseUrl", url); put("token", token); put("userId", user); put("accounts", json.encodeToString(kept + Account(user, name, token, pin))) }
    }

    fun switchTo(a: Account) = p.edit { put("token", a.token); put("userId", a.userId) }

    fun setPin(userId: String, pin: String?) = update(userId) { it.copy(pin = pin?.let(::pinHash)) }
    /** Accounts from before names were kept get theirs from the server. */
    fun setName(userId: String, name: String) = update(userId) { it.copy(name = name) }
    private fun update(userId: String, change: (Account) -> Account) =
        p.edit { put("accounts", json.encodeToString(accounts().map { if (it.userId == userId) change(it) else it })) }

    fun pinMatches(a: Account, pin: String) = a.pin == null || a.pin.substringBefore(':').let { pinHash(pin, it) == a.pin }

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
    var doubleTapSeconds by userInt("doubleTapSeconds", 10)
    var subtitleScale by userInt("subtitleScale", 100)
    /** Applied only while a wireless output is connected; see [AudioDelay]. */
    var audioDelayMs by int("audioDelayMs", 0)
    var storageIndex by int("storageIndex", 0)
    /** 0 picks by device ([Platform.autoTv]), 1 touch, 2 TV. */
    var interfaceMode by int("interfaceMode", 0)

    /** Direct play limits; see [finimal.core.playsDirectly]. 0 is Auto. */
    var maxHeight by userInt("maxHeight", 0)
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
    fun keepNext(seriesId: String) = (p[u("keepNext.$seriesId")] as Number?)?.toInt() ?: 0
    fun setKeepNext(seriesId: String, n: Int) = p.edit { put(u("keepNext.$seriesId"), n) }
    fun keepNextSeries(): Map<String, Int> =
        p.keys().filter { it.startsWith(u("keepNext.")) }
            .associate { it.removePrefix(u("keepNext.")) to keepNext(it.removePrefix(u("keepNext."))) }
            .filterValues { it > 0 }

    /** Per-series track choice as language codes; "" means subtitles off. */
    fun audioLang(seriesId: String): String? = p[u("audio.$seriesId")] as String?
    fun subLang(seriesId: String): String? = p[u("sub.$seriesId")] as String?
    fun setTracks(seriesId: String, audio: String?, sub: String?) = p.edit {
        put(u("audio.$seriesId"), audio)
        put(u("sub.$seriesId"), sub)
    }

    val loggedIn get() = token != null

    /** Removes the current account; its unsynced progress and settings stay for when it logs in again. */
    fun logout() = p.edit { put("accounts", json.encodeToString(accounts().filter { it.userId != userId })); put("token", null); put("userId", null) }

    /** The server ended the session: logged out, the account and its PIN kept for logging in again. */
    fun endSession() = p.edit { put("token", null); put("userId", null) }

    /** Settings that follow the account rather than the device: under "<userId>/". */
    private fun u(key: String) = "$userId/$key"

    init {
        // Before accounts, one login and its settings unprefixed: they become the first account's.
        val user = userId
        val token = token
        if (p["accounts"] == null && user != null && token != null) {
            val moved = p.keys().filter { it in USER_KEYS || USER_PREFIXES.any(it::startsWith) }.associateWith { p[it] }
            p.edit {
                put("accounts", json.encodeToString(listOf(Account(user, "", token))))
                moved.forEach { (k, v) -> put("$user/$k", v); put(k, null) }
            }
        }
    }

    private fun userInt(key: String, default: Int) = object : kotlin.properties.ReadWriteProperty<Any, Int> {
        override fun getValue(thisRef: Any, property: kotlin.reflect.KProperty<*>) = (p[u(key)] as Number?)?.toInt() ?: default
        override fun setValue(thisRef: Any, property: kotlin.reflect.KProperty<*>, value: Int) =
            p.edit { put(u(key), value) }
    }

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

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
        val USER_KEYS = setOf("maxHeight", "subtitleScale", "doubleTapSeconds")
        val USER_PREFIXES = listOf("keepNext.", "audio.", "sub.")

        fun pinHash(pin: String, salt: String = ByteArray(16).also(SecureRandom()::nextBytes).joinToString("") { "%02x".format(it) }) =
            salt + ":" + MessageDigest.getInstance("SHA-256").digest("$salt:$pin".toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
