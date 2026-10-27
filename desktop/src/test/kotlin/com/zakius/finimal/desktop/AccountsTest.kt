package com.zakius.finimal.desktop

import com.zakius.finimal.KeyValueStore
import com.zakius.finimal.Prefs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Accounts on one server: settings follow the account, a PIN guards switching to it, another server starts over. */
class AccountsTest {
    private class Store(val m: MutableMap<String, Any?> = mutableMapOf()) : KeyValueStore {
        override fun get(key: String) = m[key]
        override fun keys() = m.keys.toSet()
        override fun edit(block: MutableMap<String, Any?>.() -> Unit) {
            val changes = mutableMapOf<String, Any?>().apply(block)
            changes.forEach { (k, v) -> if (v == null) m.remove(k) else m[k] = v }
        }
    }

    @Test fun aSingleLoginBecomesTheFirstAccountWithItsSettings() {
        val store = Store(mutableMapOf("baseUrl" to "https://s", "token" to "t", "userId" to "a", "maxHeight" to 720L, "sub.series" to "eng"))
        val p = Prefs(store)
        assertEquals(listOf("a"), p.accounts().map { it.userId })
        assertEquals(720, p.maxHeight)
        assertEquals("eng", p.subLang("series"))
        assertNull(store["maxHeight"])
    }

    @Test fun settingsFollowTheAccount() {
        val p = Prefs(Store())
        p.login("https://s", "ta", "a", "Ann")
        p.maxHeight = 720
        p.setTracks("series", "jpn", "eng")
        p.login("https://s", "tb", "b", "Bob")
        assertEquals(0, p.maxHeight)
        assertNull(p.subLang("series"))
        p.switchTo(p.accounts().first { it.userId == "a" })
        assertEquals(720, p.maxHeight)
        assertEquals("eng", p.subLang("series"))
        assertEquals("ta", p.token)
    }

    @Test fun pinGuardsTheAccount() {
        val p = Prefs(Store())
        p.login("https://s", "ta", "a", "Ann")
        p.setPin("a", "1234")
        val a = p.accounts().single()
        assertTrue(p.pinMatches(a, "1234"))
        assertFalse(p.pinMatches(a, "4321"))
        assertFalse("1234" in a.pin!!)
        p.login("https://s", "ta2", "a", "Ann")
        assertTrue(p.pinMatches(p.accounts().single(), "1234"), "logging in again keeps the PIN")
    }

    @Test fun anotherServerStartsOverAndLogoutRemovesOnlyTheCurrent() {
        val p = Prefs(Store())
        p.login("https://s", "ta", "a", "Ann")
        p.login("https://s", "tb", "b", "Bob")
        p.logout()
        assertEquals(listOf("a"), p.accounts().map { it.userId })
        assertFalse(p.loggedIn)
        p.login("https://other", "tc", "c", "Cy")
        assertEquals(listOf("c"), p.accounts().map { it.userId })
    }
}
