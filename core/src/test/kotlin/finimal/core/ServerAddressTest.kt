package finimal.core

import kotlin.test.Test
import kotlin.test.assertEquals

class ServerAddressTest {
    @Test fun hostTriesHttpsThenHttpOnThePort() = assertEquals(
        listOf("https://jf.example.com:8096", "http://jf.example.com:8096"), serverCandidates("jf.example.com", "8096"))

    @Test fun port443IsHttpsAlone() = assertEquals(listOf("https://jf.example.com"), serverCandidates("jf.example.com", "443"))

    @Test fun prefilledPortFollowsTheHost() {
        for (local in listOf("localhost", "nas", "NAS.local", "box.lan", "jf.home.arpa", "192.168.1.5", "10.0.0.2/jf", "fd00::5", "[fd00::5]", "http://nas"))
            assertEquals(DEFAULT_JELLYFIN_PORT, defaultPortFor(local), local)
        for (public in listOf("jf.example.com", "https://example.com/jellyfin", "media.duckdns.org"))
            assertEquals("443", defaultPortFor(public), public)
        for (withPort in listOf("nas:8097", "jf.example.com:8920", "[fd00::5]:8920", "https://x.org:443/jf"))
            assertEquals(null, defaultPortFor(withPort), withPort)
    }

    @Test fun otherPortTriesHttpsThenHttpOnIt() = assertEquals(
        listOf("https://192.168.1.5:8920", "http://192.168.1.5:8920"), serverCandidates("192.168.1.5", "8920"))

    @Test fun emptyPortMeansTheSchemeDefault() = assertEquals(
        listOf("https://jf.example.com", "http://jf.example.com"), serverCandidates("jf.example.com", ""))

    @Test fun portInTheServerFieldWins() = assertEquals(
        listOf("https://nas:8097", "http://nas:8097"), serverCandidates("nas:8097", "8096"))

    @Test fun explicitSchemeIsTriedAsWritten() {
        assertEquals(listOf("https://jf.example.com"), serverCandidates("https://jf.example.com", ""))
        assertEquals(listOf("http://nas:8096"), serverCandidates("http://nas", "8096"))
        assertEquals(listOf("http://nas:9000"), serverCandidates("http://nas:9000/", "8096"))
    }

    @Test fun pathStaysAfterThePort() = assertEquals(
        listOf("https://example.com:8096/jellyfin", "http://example.com:8096/jellyfin"),
        serverCandidates(" example.com/jellyfin/ ", "8096"))

    @Test fun ipv6Literals() {
        assertEquals(listOf("https://[fd00::5]:8096", "http://[fd00::5]:8096"), serverCandidates("[fd00::5]", "8096"))
        assertEquals(listOf("https://[fd00::5]:8920", "http://[fd00::5]:8920"), serverCandidates("[fd00::5]:8920", "8096"))
        assertEquals(listOf("https://[fd00::5]:8096", "http://[fd00::5]:8096"), serverCandidates("fd00::5", "8096"))
    }

    @Test fun savedAddressSplitsBackIntoFields() {
        assertEquals("http://localhost" to "8097", splitServerAddress("http://localhost:8097"))
        assertEquals("https://jf.example.com" to "", splitServerAddress("https://jf.example.com"))
        assertEquals("https://example.com/jellyfin" to "8096", splitServerAddress("https://example.com:8096/jellyfin"))
        assertEquals("" to DEFAULT_JELLYFIN_PORT, splitServerAddress(null))
    }

    @Test fun portValidation() {
        assertEquals(true, isValidPort(""))
        assertEquals(true, isValidPort("8096"))
        assertEquals(false, isValidPort("0"))
        assertEquals(false, isValidPort("70000"))
        assertEquals(false, isValidPort("80a"))
    }
}
