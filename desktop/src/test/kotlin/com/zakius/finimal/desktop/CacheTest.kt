package com.zakius.finimal.desktop

import com.zakius.finimal.AppCache
import com.zakius.finimal.Sort
import java.time.LocalDateTime
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind

class CacheTest {
    @AfterTest fun cleanup() {
        AppCache.clear()
    }

    private fun movie(
        name: String,
        sortName: String = name,
        added: LocalDateTime? = null,
        released: LocalDateTime? = null,
    ) = BaseItemDto(
        id = UUID.randomUUID(),
        type = BaseItemKind.MOVIE,
        name = name,
        sortName = sortName,
        dateCreated = added,
        premiereDate = released,
    )

    @Test fun seriesEpisodesAutomaticallySeedEverySeason() {
        val base = "https://example.test"
        val user = "user-1"
        val seriesId = UUID.randomUUID()
        val s1 = UUID.randomUUID()
        val s2 = UUID.randomUUID()
        val ep1 = BaseItemDto(id = UUID.randomUUID(), type = BaseItemKind.EPISODE, name = "E1", seriesId = seriesId, seasonId = s1)
        val ep2 = BaseItemDto(id = UUID.randomUUID(), type = BaseItemKind.EPISODE, name = "E2", seriesId = seriesId, seasonId = s2)
        val ep3 = BaseItemDto(id = UUID.randomUUID(), type = BaseItemKind.EPISODE, name = "E3", seriesId = seriesId, seasonId = s2)

        AppCache.putList(listOf(base, user, seriesId.toString(), "episodes"), listOf(ep1, ep2, ep3), fromNetwork = true, localChanges = 0)

        val season1 = AppCache.getList(listOf(base, user, s1.toString()))
        val season2 = AppCache.getList(listOf(base, user, s2.toString()))
        assertNotNull(season1)
        assertNotNull(season2)
        assertEquals(listOf(ep1), season1.items)
        assertEquals(listOf(ep2, ep3), season2.items)
        assertEquals(true, season2.fromNetwork)
    }

    @Test fun deriveLibraryListSortsAndSearchesInstantly() {
        val base = "https://example.test"
        val user = "user-1"
        val libId = "lib-movies"
        val a = movie("Alpha", added = LocalDateTime.of(2024, 1, 1, 0, 0), released = LocalDateTime.of(2020, 1, 1, 0, 0))
        val b = movie("Beta Matrix", added = LocalDateTime.of(2025, 6, 1, 0, 0), released = LocalDateTime.of(2023, 5, 1, 0, 0))
        val c = movie("Matrix Reloaded", added = LocalDateTime.of(2024, 12, 1, 0, 0), released = LocalDateTime.of(2003, 5, 1, 0, 0))

        AppCache.putList(listOf(base, user, libId, Sort.NAME, false, ""), listOf(a, b, c), fromNetwork = true, localChanges = 0)

        val byAdded = AppCache.deriveLibraryList(base, user, libId, Sort.ADDED, unplayed = false, query = "")
        assertEquals(listOf(b, c, a), byAdded)

        val byReleased = AppCache.deriveLibraryList(base, user, libId, Sort.RELEASED, unplayed = false, query = "")
        assertEquals(listOf(b, a, c), byReleased)

        val searched = AppCache.deriveLibraryList(base, user, libId, Sort.NAME, unplayed = false, query = "matrix")
        assertEquals(listOf(c, b), searched)

        AppCache.clear()
        assertNull(AppCache.deriveLibraryList(base, user, libId, Sort.ADDED, unplayed = false, query = ""))
    }
}
