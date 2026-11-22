package com.zakius.finimal

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * A login kept on this device. [pin] is "salt:hash" (see [Prefs.pinHash]). The PIN is only a household lock,
 * not security, because the token is stored right beside it.
 */
@Serializable
data class Account(val userId: String, val name: String, val token: String, val pin: String? = null)

class Prefs(private val p: KeyValueStore) {
    var baseUrl by string("baseUrl")
    var token by string("token")
    var userId by string("userId")
    /** Server clock minus device clock, from the last response; see [Server.now]. */
    var clockOffsetMs by long("clockOffsetMs")

    @Volatile private var accountsCache: List<Account>? = null

    /** The accounts on [baseUrl]; only one server is kept at a time. The current account is [userId], with its token in [token]. */
    fun accounts(): List<Account> {
        accountsCache?.let { return it }
        val saved = p["accounts"] as String?
        if (saved == null) {
            accountsCache = emptyList()
            return emptyList()
        }
        val parsed = runCatching { json.decodeFromString<List<Account>>(saved) }.getOrNull().orEmpty()
        accountsCache = parsed
        return parsed
    }

    val accountCount: Int
        get() {
            val saved = (p["accountCount"] as Number?)?.toInt()
            if (saved != null) return saved
            val count = accounts().size
            p.edit { put("accountCount", count) }
            return count
        }

    /** Display name of the current account, or null if none is logged in; avoids JSON parsing on cold start. */
    fun currentUserName(): String? {
        val uid = userId ?: return null
        val saved = p["userName"] as String?
        if (saved != null) return saved
        val found = accounts().firstOrNull { it.userId == uid }?.name ?: return null
        p.edit { put("userName", found) }
        return found
    }

    /**
     * Adds or refreshes [user]'s account and makes it current in one write, so a kill in between can't leave a token
     * without its user. Logging in to a different server replaces every account.
     */
    fun login(url: String, token: String, user: String, name: String) {
        val current = accounts()
        val kept = if (url == baseUrl) current.filter { it.userId != user } else emptyList()
        val pin = current.firstOrNull { it.userId == user && url == baseUrl }?.pin
        val updated = kept + Account(user, name, token, pin)
        accountsCache = updated
        p.edit {
            put("baseUrl", url)
            put("token", token)
            put("userId", user)
            put("userName", name)
            put("accountCount", updated.size)
            put("accounts", json.encodeToString(updated))
        }
    }

    fun switchTo(a: Account) = p.edit {
        put("token", a.token)
        put("userId", a.userId)
        put("userName", a.name)
    }

    fun setPin(userId: String, pin: String?) = update(userId) { account ->
        val hash = if (pin == null) null else pinHash(pin)
        account.copy(pin = hash)
    }
    /** Fills in the name of an account migrated from before names were stored (see init). */
    fun setName(userId: String, name: String) {
        update(userId) { it.copy(name = name) }
        if (userId == this.userId) p.edit { put("userName", name) }
    }
    private fun update(userId: String, change: (Account) -> Account) {
        val updated = accounts().map { account -> if (account.userId == userId) change(account) else account }
        accountsCache = updated
        p.edit {
            put("accountCount", updated.size)
            put("accounts", json.encodeToString(updated))
        }
    }

    fun pinMatches(a: Account, pin: String): Boolean {
        if (a.pin == null) return true
        val salt = a.pin.substringBefore(':')
        return pinHash(pin, salt) == a.pin
    }

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
    /** 0 picks by device ([Platform.autoTv]), 1 forces touch, 2 forces TV. */
    var interfaceMode by int("interfaceMode", 0)

    /** Direct play limits; see [finimal.core.playsDirectly]. 0 is Auto. */
    var maxHeight by userInt("maxHeight", 0)
    /** Index into [finimal.core.DirectPlayLimits.Codecs]. */
    var codecs by int("codecs", 0)

    /** Lowest decoder load, in pixels a second, at which the user chose a transcode for choppy [codec] playback. */
    fun learnedPixelRate(codec: String): Long? {
        val rate = (p["learnedRate.$codec"] as Number?)?.toLong()
        if (rate == null || rate <= 0) return null
        return rate
    }
    fun learnChoppy(codec: String, pixelRate: Long) {
        val learned = learnedPixelRate(codec) ?: Long.MAX_VALUE
        if (pixelRate < learned) p.edit { put("learnedRate.$codec", pixelRate) }
    }
    fun learnedCodecs() = p.keys().filter { it.startsWith("learnedRate.") }.map { it.removePrefix("learnedRate.") }
    fun forgetLearned() = p.edit {
        for (codec in learnedCodecs()) put("learnedRate.$codec", null)
    }

    /** 0 means "keep next N" is off for the series. */
    fun keepNext(seriesId: String) = (p[u("keepNext.$seriesId")] as Number?)?.toInt() ?: 0
    fun setKeepNext(seriesId: String, n: Int) = p.edit { put(u("keepNext.$seriesId"), n) }
    fun keepNextSeries(): Map<String, Int> {
        val prefix = u("keepNext.")
        return p.keys()
            .filter { key -> key.startsWith(prefix) }
            .associate { key ->
                val seriesId = key.removePrefix(prefix)
                seriesId to keepNext(seriesId)
            }
            .filterValues { n -> n > 0 }
    }

    /** Per-series track choice as language codes; "" means subtitles off. */
    fun audioLang(seriesId: String): String? = p[u("audio.$seriesId")] as String?
    fun subLang(seriesId: String): String? = p[u("sub.$seriesId")] as String?
    fun setTracks(seriesId: String, audio: String?, sub: String?) = p.edit {
        put(u("audio.$seriesId"), audio)
        put(u("sub.$seriesId"), sub)
    }

    /** Per-library sort and unplayed filter, kept per account. */
    fun librarySort(id: String): Sort {
        val name = p[u("librarySort.$id")] as String?
        return Sort.entries.firstOrNull { it.name == name } ?: Sort.NAME
    }
    fun setLibrarySort(id: String, sort: Sort) = p.edit { put(u("librarySort.$id"), sort.name) }
    fun libraryUnplayed(id: String): Boolean = p[u("libraryUnplayed.$id")] as Boolean? ?: false
    fun setLibraryUnplayed(id: String, unplayed: Boolean) = p.edit { put(u("libraryUnplayed.$id"), unplayed) }

    val loggedIn get() = token != null

    /** Removes the current account; its unsynced progress and settings stay for when it logs in again. */
    fun logout() {
        val remaining = accounts().filter { it.userId != userId }
        accountsCache = remaining
        p.edit {
            put("accounts", json.encodeToString(remaining))
            put("accountCount", remaining.size)
            put("token", null)
            put("userId", null)
            put("userName", null)
        }
    }

    /** The server ended the session: logged out, the account and its PIN kept for logging in again. */
    fun endSession() = p.edit {
        put("token", null)
        put("userId", null)
        put("userName", null)
    }

    private fun u(key: String) = "$userId/$key"

    init {
        val user = userId
        val token = token
        if (p["accounts"] == null && user != null && token != null) {
            val moved = p.keys()
                .filter { key -> key in USER_KEYS || USER_PREFIXES.any { prefix -> key.startsWith(prefix) } }
                .associateWith { key -> p[key] }
            val migrated = listOf(Account(user, "", token))
            accountsCache = migrated
            p.edit {
                put("accounts", json.encodeToString(migrated))
                put("accountCount", 1)
                put("userName", "")
                for ((key, value) in moved) {
                    put("$user/$key", value)
                    put(key, null)
                }
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

        fun pinHash(pin: String, salt: String = ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }): String {
            val digest = MessageDigest.getInstance("SHA-256").digest("$salt:$pin".toByteArray())
            return salt + ":" + digest.joinToString("") { "%02x".format(it) }
        }
    }
}
