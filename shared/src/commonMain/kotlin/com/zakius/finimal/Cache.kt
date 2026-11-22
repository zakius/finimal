package com.zakius.finimal

import finimal.core.catching
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ImageType

/**
 * Thread-safe, account-scoped in-memory cache and speculative preloader for lists, full items and media segments.
 *
 * Cleared on every login, logout and account switch so one account's data never appears under another.
 * Respects Sync rules: fresh network entries may skip redundant back-to-back fetches (e.g. Series -> Season),
 * while stale entries or disk snapshots still display on Frame 1 and refresh in the background.
 */
object AppCache {
    class ListEntry(
        val items: List<BaseItemDto>,
        val fromNetwork: Boolean,
        val fetchedAtMs: Long,
        val localChangesAtFetch: Int,
    )

    class ItemEntry(
        val item: BaseItemDto,
        val fetchedAtMs: Long,
        val localChangesAtFetch: Int,
    )

    private class SegmentsEntry(
        val segments: List<org.jellyfin.sdk.model.api.MediaSegmentDto>,
        val fetchedAtMs: Long,
    )

    private val lock = Any()
    private val generation = AtomicInteger(0)

    private val lists = object : LinkedHashMap<List<Any?>, ListEntry>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<List<Any?>, ListEntry>) = size > 64
    }
    private val items = object : LinkedHashMap<List<Any?>, ItemEntry>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<List<Any?>, ItemEntry>) = size > 64
    }
    private val segments = object : LinkedHashMap<List<Any?>, SegmentsEntry>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<List<Any?>, SegmentsEntry>) = size > 64
    }

    private val inFlightItems = ConcurrentHashMap<List<Any?>, Deferred<BaseItemDto>>()
    private val inFlightSegments = ConcurrentHashMap<List<Any?>, Deferred<List<org.jellyfin.sdk.model.api.MediaSegmentDto>>>()
    private val inFlightPrefetches = ConcurrentHashMap<Any, Job>()

    private fun accountPrefix(): Pair<String?, String?> = App.prefs.baseUrl to App.prefs.userId
    fun listKey(vararg parts: Any?): List<Any?> {
        val (base, user) = accountPrefix()
        return buildList(parts.size + 2) {
            add(base)
            add(user)
            for (p in parts) add(p)
        }
    }
    private fun itemKey(id: String): List<Any?> {
        val (base, user) = accountPrefix()
        return listOf(base, user, id)
    }

    fun getList(key: List<Any?>): ListEntry? = synchronized(lock) { lists[key] }

    fun putList(
        key: List<Any?>,
        list: List<BaseItemDto>,
        fromNetwork: Boolean,
        localChanges: Int = App.server.localChanges,
        fetchedAtMs: Long = System.currentTimeMillis(),
    ) {
        synchronized(lock) {
            val existing = lists[key]
            // Don't let an older disk snapshot overwrite a fresh network fetch.
            if (!fromNetwork && existing != null && existing.fromNetwork) return
            lists[key] = ListEntry(list, fromNetwork, fetchedAtMs, localChanges)
            // Series episodes contain every season's episodes: slice them so tapping any Season opens at 0 ms.
            if (key.size == 4 && key[3] == "episodes") {
                val base = key[0]
                val user = key[1]
                val bySeason = list.groupBy { it.seasonId }
                for ((seasonId, eps) in bySeason) {
                    if (seasonId == null) continue
                    val seasonKey = listOf(base, user, seasonId.toString())
                    val prevSeason = lists[seasonKey]
                    if (!fromNetwork && prevSeason != null && prevSeason.fromNetwork) continue
                    lists[seasonKey] = ListEntry(eps, fromNetwork, fetchedAtMs, localChanges)
                }
            }
        }
    }

    /** True when [entry] was just fetched from the network with no local state changes or pending sync since. */
    fun isListFresh(entry: ListEntry, maxAgeMs: Long = 5_000L): Boolean =
        entry.fromNetwork &&
            (System.currentTimeMillis() - entry.fetchedAtMs) < maxAgeMs &&
            entry.localChangesAtFetch == App.server.localChanges &&
            !App.server.hasPendingWork()

    /**
     * Derives a library view (different sort, unplayed filter, or in-library search) synchronously from an already
     * cached unfiltered copy of the same library, giving 0 ms UI feedback while the authoritative server fetch runs.
     */
    fun deriveLibraryList(
        baseUrl: String?,
        userId: String?,
        libraryId: String,
        sort: Sort,
        unplayed: Boolean,
        query: String,
    ): List<BaseItemDto>? {
        val source: List<BaseItemDto> = synchronized(lock) {
            var best: ListEntry? = null
            for ((k, v) in lists) {
                if (k.size == 6 && k[0] == baseUrl && k[1] == userId && k[2] == libraryId && k[4] == false && k[5] == "") {
                    if (best == null || (v.fromNetwork && !best.fromNetwork) || (v.fromNetwork == best.fromNetwork && v.fetchedAtMs > best.fetchedAtMs)) {
                        best = v
                    }
                }
            }
            best?.items
        } ?: return null

        val filtered = if (unplayed) {
            source.filter { it.localState().first?.played != true }
        } else {
            source
        }

        val trimmed = query.trim()
        if (trimmed.isNotEmpty()) {
            val matches = filtered.filter { it.name?.contains(trimmed, ignoreCase = true) == true }
            return matches.sortedWith(
                compareByDescending<BaseItemDto> { it.name?.startsWith(trimmed, ignoreCase = true) == true }
                    .thenBy { (it.sortName ?: it.name.orEmpty()).lowercase() }
            )
        }

        return when (sort) {
            Sort.NAME -> filtered.sortedBy { (it.sortName ?: it.name.orEmpty()).lowercase() }
            Sort.ADDED -> {
                if (filtered.isNotEmpty() && filtered.all { it.dateCreated == null }) return null
                filtered.sortedWith(
                    compareByDescending<BaseItemDto> { it.dateCreated }
                        .thenBy { (it.sortName ?: it.name.orEmpty()).lowercase() }
                )
            }
            Sort.RELEASED -> {
                if (filtered.isNotEmpty() && filtered.all { it.premiereDate == null && it.productionYear == null }) return null
                filtered.sortedWith(
                    compareByDescending<BaseItemDto> { it.premiereDate?.toString() ?: it.productionYear?.toString().orEmpty() }
                        .thenBy { (it.sortName ?: it.name.orEmpty()).lowercase() }
                )
            }
        }
    }

    fun getCachedItem(itemId: String): BaseItemDto? = synchronized(lock) { items[itemKey(itemId)]?.item }

    fun getFreshItem(itemId: String, maxAgeMs: Long = 15_000L): BaseItemDto? = synchronized(lock) {
        val entry = items[itemKey(itemId)] ?: return null
        if (System.currentTimeMillis() - entry.fetchedAtMs < maxAgeMs && entry.localChangesAtFetch == App.server.localChanges) {
            entry.item
        } else {
            null
        }
    }

    fun putItem(itemId: String, item: BaseItemDto, localChanges: Int = App.server.localChanges) {
        val key = itemKey(itemId)
        synchronized(lock) {
            items[key] = ItemEntry(item, System.currentTimeMillis(), localChanges)
        }
    }

    /**
     * Returns [itemId] immediately from cache when fresh, joins an in-flight prefetch if one is running, or fetches
     * once from the server and caches the result.
     */
    suspend fun fetchOrJoinItem(itemId: String, flushFirst: Boolean): BaseItemDto {
        val gen = generation.get()
        val key = itemKey(itemId)
        if (!flushFirst) {
            getFreshItem(itemId)?.let { return it }
            val existing = inFlightItems[key]
            if (existing != null && existing.isActive) {
                return try {
                    existing.await()
                } catch (e: CancellationException) {
                    if (generation.get() != gen) throw e
                    fetchOrJoinItem(itemId, flushFirst = false)
                }
            }
            val created = App.platform.scope.async(Dispatchers.IO, start = CoroutineStart.LAZY) {
                val changes = App.server.localChanges
                val loaded = App.server.item(itemId, flushFirst = false)
                if (generation.get() == gen) {
                    synchronized(lock) { items[key] = ItemEntry(loaded, System.currentTimeMillis(), changes) }
                }
                loaded
            }
            val active = inFlightItems.putIfAbsent(key, created) ?: created
            if (active === created) {
                created.invokeOnCompletion { inFlightItems.remove(key, created) }
                created.start()
            } else {
                created.cancel()
            }
            return active.await()
        }
        val changes = App.server.localChanges
        val loaded = App.server.item(itemId, flushFirst = true)
        if (generation.get() == gen) {
            synchronized(lock) { items[key] = ItemEntry(loaded, System.currentTimeMillis(), changes) }
        }
        return loaded
    }

    fun getCachedSegments(itemId: String, maxAgeMs: Long = 300_000L): List<org.jellyfin.sdk.model.api.MediaSegmentDto>? =
        synchronized(lock) {
            val entry = segments[itemKey(itemId)] ?: return null
            if (System.currentTimeMillis() - entry.fetchedAtMs < maxAgeMs) entry.segments else null
        }

    suspend fun fetchOrJoinSegments(itemId: String): List<org.jellyfin.sdk.model.api.MediaSegmentDto> {
        getCachedSegments(itemId)?.let { return it }
        val gen = generation.get()
        val key = itemKey(itemId)
        val existing = inFlightSegments[key]
        if (existing != null && existing.isActive) return existing.await()
        val created = App.platform.scope.async(Dispatchers.IO, start = CoroutineStart.LAZY) {
            val loaded = App.server.segments(itemId)
            if (generation.get() == gen) {
                synchronized(lock) { segments[key] = SegmentsEntry(loaded, System.currentTimeMillis()) }
            }
            loaded
        }
        val active = inFlightSegments.putIfAbsent(key, created) ?: created
        if (active === created) {
            created.invokeOnCompletion { inFlightSegments.remove(key, created) }
            created.start()
        } else {
            created.cancel()
        }
        return active.await()
    }

    /**
     * Speculatively reads saved disk snapshots for [libraryIds] into memory and pre-decodes their top posters into
     * Coil's memory cache (disk-only on metered networks), so tapping a library paints immediately with zero spinner.
     */
    fun prefetchLibrariesFromDisk(libraryIds: List<String>) {
        if (libraryIds.isEmpty() || !App.prefs.loggedIn) return
        val gen = generation.get()
        val (base, user) = accountPrefix()
        val key = listOf("libs-disk", base, user, libraryIds)
        launchSpeculative(key) {
            val posterUrls = ArrayList<String>(24)
            for ((index, id) in libraryIds.take(4).withIndex()) {
                if (generation.get() != gen) return@launchSpeculative
                val sort = App.prefs.librarySort(id)
                val unplayed = App.prefs.libraryUnplayed(id)
                val cacheKey = listOf(base, user, id, sort, unplayed, "")
                val existing = getList(cacheKey)
                val list = existing?.items ?: ListSnapshots.read(cacheKey)?.also { disk ->
                    if (generation.get() == gen) putList(cacheKey, disk, fromNetwork = false)
                }
                if (list != null) {
                    val limit = if (index == 0) 15 else 6
                    for (item in list.take(limit)) {
                        if (posterUrls.size >= 24) break
                        App.server.imageUrl(item)?.let { posterUrls += it }
                    }
                }
            }
            if (posterUrls.isNotEmpty() && generation.get() == gen) {
                App.platform.prefetchImages(posterUrls, diskOnly = App.platform.isMetered)
            }
        }
    }

    /**
     * On unmetered networks, speculatively refreshes the top libraries after Libraries itself has synced, so opening
     * a library has network-fresh data and warm posters already ready.
     */
    fun prefetchLibrariesNetwork(libraryIds: List<String>) {
        if (libraryIds.isEmpty() || !App.prefs.loggedIn || App.platform.isMetered) return
        val gen = generation.get()
        val (base, user) = accountPrefix()
        val key = listOf("libs-net", base, user, libraryIds.take(2))
        launchSpeculative(key) {
            for ((index, id) in libraryIds.take(2).withIndex()) {
                if (generation.get() != gen) return@launchSpeculative
                val sort = App.prefs.librarySort(id)
                val unplayed = App.prefs.libraryUnplayed(id)
                val cacheKey = listOf(base, user, id, sort, unplayed, "")
                val existing = getList(cacheKey)
                if (existing != null && isListFresh(existing, maxAgeMs = 30_000L)) continue
                val changes = App.server.localChanges
                val list = catching { App.server.items(id, sort, unplayed, "") }.getOrNull() ?: return@launchSpeculative
                if (generation.get() != gen) return@launchSpeculative
                putList(cacheKey, list, fromNetwork = true, localChanges = changes)
                ListSnapshots.writeIfChanged(cacheKey, list, existing?.items)
                if (index == 0) {
                    val urls = list.take(18).mapNotNull { App.server.imageUrl(it) }
                    if (urls.isNotEmpty()) App.platform.prefetchImages(urls, diskOnly = false)
                }
            }
        }
    }

    /** Speculatively loads a series's seasons & episodes (disk first, then network if unmetered). */
    fun prefetchSeries(seriesId: String) {
        if (!App.prefs.loggedIn) return
        val gen = generation.get()
        val (base, user) = accountPrefix()
        val seasonsKey = listOf(base, user, seriesId)
        val episodesKey = listOf(base, user, seriesId, "episodes")
        launchSpeculative(listOf("series", base, user, seriesId)) {
            if (getList(seasonsKey) == null) {
                ListSnapshots.read(seasonsKey)?.let { if (generation.get() == gen) putList(seasonsKey, it, fromNetwork = false) }
            }
            if (getList(episodesKey) == null) {
                ListSnapshots.read(episodesKey)?.let { if (generation.get() == gen) putList(episodesKey, it, fromNetwork = false) }
            }
            if (App.platform.isMetered) return@launchSpeculative
            val seasonsCached = getList(seasonsKey)
            val episodesCached = getList(episodesKey)
            val fetchSeasons = seasonsCached == null || !isListFresh(seasonsCached, maxAgeMs = 30_000L)
            val fetchEpisodes = episodesCached == null || !isListFresh(episodesCached, maxAgeMs = 30_000L)
            if (!fetchSeasons && !fetchEpisodes) return@launchSpeculative
            val changes = App.server.localChanges
            val sJob = if (fetchSeasons) async { catching { App.server.seasons(seriesId) }.getOrNull() } else null
            val eJob = if (fetchEpisodes) async { catching { App.server.episodes(seriesId) }.getOrNull() } else null
            val s = sJob?.await()
            val e = eJob?.await()
            if (generation.get() != gen) return@launchSpeculative
            if (s != null) {
                putList(seasonsKey, s, fromNetwork = true, localChanges = changes)
                ListSnapshots.writeIfChanged(seasonsKey, s, seasonsCached?.items)
            }
            if (e != null) {
                putList(episodesKey, e, fromNetwork = true, localChanges = changes)
                ListSnapshots.writeIfChanged(episodesKey, e, episodesCached?.items)
            }
        }
    }

    /**
     * Speculatively prefetches a playable item's full metadata, media segments, and (on unmetered Android) its
     * sideloaded subtitles so tapping Continue / Play / Play next starts playback without serial network stalls.
     */
    fun prefetchPlayable(itemId: String, includeSubtitles: Boolean = true) {
        if (!App.prefs.loggedIn) return
        val gen = generation.get()
        val (base, user) = accountPrefix()
        launchSpeculative(listOf("play", base, user, itemId)) {
            val segJob = launch { catching { fetchOrJoinSegments(itemId) } }
            val loaded = catching { fetchOrJoinItem(itemId, flushFirst = false) }.getOrNull()
            if (loaded != null && includeSubtitles && !App.platform.isMetered && generation.get() == gen) {
                catching { App.platform.prefetchSubtitles(loaded) }
            }
            segJob.join()
        }
    }

    /** Pre-warms episode thumbnails around [focusIndex] into Coil's cache. */
    fun prefetchEpisodeThumbnails(episodes: List<BaseItemDto>, focusIndex: Int = 0, count: Int = 10) {
        if (episodes.isEmpty()) return
        val start = (focusIndex - 2).coerceAtLeast(0)
        val end = (start + count).coerceAtMost(episodes.size)
        val urls = (start until end).mapNotNull { i -> App.server.imageUrl(episodes[i], ImageType.PRIMARY, 320) }
        if (urls.isNotEmpty()) App.platform.prefetchImages(urls, diskOnly = App.platform.isMetered)
    }

    private fun launchSpeculative(key: Any, block: suspend kotlinx.coroutines.CoroutineScope.() -> Unit) {
        if (inFlightPrefetches[key]?.isActive == true) return
        val job = App.platform.scope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
            try {
                block()
            } finally {
                inFlightPrefetches.remove(key)
            }
        }
        val prev = inFlightPrefetches.putIfAbsent(key, job)
        if (prev == null) job.start() else job.cancel()
    }

    /** Clears every cached entry and cancels all in-flight speculative requests (called on account switch / logout). */
    fun clear() {
        generation.incrementAndGet()
        synchronized(lock) {
            lists.clear()
            items.clear()
            segments.clear()
        }
        inFlightItems.values.forEach { it.cancel() }
        inFlightItems.clear()
        inFlightSegments.values.forEach { it.cancel() }
        inFlightSegments.clear()
        inFlightPrefetches.values.forEach { it.cancel() }
        inFlightPrefetches.clear()
    }
}
