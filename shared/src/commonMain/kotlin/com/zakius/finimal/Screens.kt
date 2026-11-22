package com.zakius.finimal

import finimal.core.writeAtomically
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import java.io.File
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.foundation.background
import finimal.core.defaultPortFor
import finimal.core.isValidPort
import finimal.core.splitServerAddress
import finimal.core.serverCandidates
import finimal.core.untrustedCertificate
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.round
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.input.VisualTransformation
import org.jellyfin.sdk.model.api.LocationType
import finimal.core.EpisodeRef
import finimal.core.keepNextWindow
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.composed
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import coil3.size.Size
import androidx.compose.runtime.State
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import org.jellyfin.sdk.api.client.exception.InvalidStatusException
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ImageType
import finimal.core.PlayState
import finimal.core.TICKS_PER_MS
import kotlin.math.abs

/** Seeds the in-memory libraries cache (called by Android's fast-start view before handing off to Compose). */
fun seedLibrariesLastGood(list: List<BaseItemDto>, fromNetwork: Boolean = true) {
    AppCache.putList(listOf(App.prefs.baseUrl, App.prefs.userId), list, fromNetwork = fromNetwork)
}

data class LibraryEntry(val id: String, val name: String)

/**
 * The same lists on disk, so a cold start shows the last list at once instead of "Loading…".
 * For display only: pending local play state is drawn over it, and a fresh fetch replaces it.
 */
object ListSnapshots {
    private val json by lazy { Json { ignoreUnknownKeys = true } }
    private val dir get() = File(App.platform.appCache, "lists")
    private fun hexHash(key: List<Any?>): String {
        val digest = MessageDigest.getInstance("SHA-1").digest(key.joinToString("\u0000").toByteArray())
        return digest.joinToString("") { byte -> "%02x".format(byte) }
    }
    private fun file(key: List<Any?>) = File(dir, "${hexHash(key)}.json")
    private fun fastLibrariesFile(key: List<Any?>) = File(dir, "${hexHash(key)}.tsv")

    fun read(key: List<Any?>): List<BaseItemDto>? {
        if (key.size == 2) readFastLibraryEntries(key)?.let { entries ->
            return entries.map { e -> BaseItemDto(id = java.util.UUID.fromString(e.id), type = BaseItemKind.COLLECTION_FOLDER, name = e.name) }
        }
        val decoded = runCatching { json.decodeFromString<List<BaseItemDto>>(file(key).readText()) }.getOrNull()
        if (decoded != null && key.size == 2) writeFastLibraries(key, decoded)
        return decoded
    }

    fun write(key: List<Any?>, list: List<BaseItemDto>) {
        runCatching {
            dir.mkdirs()
            if (key.size == 2) writeFastLibraries(key, list)
            file(key).writeAtomically(json.encodeToString(list), sync = false)
        }
    }

    /** Writes only when the list actually changed, skipping multi-MB JSON serialization on identical refreshes. */
    fun writeIfChanged(key: List<Any?>, list: List<BaseItemDto>, previous: List<BaseItemDto>?) {
        if (previous != null && previous == list && file(key).exists()) return
        write(key, list)
    }

    fun readFastLibraryEntries(key: List<Any?>): List<LibraryEntry>? = runCatching {
        val f = fastLibrariesFile(key)
        if (!f.exists()) return null
        val text = f.readText()
        if (text.isEmpty()) return emptyList()
        text.lineSequence().filter { it.isNotEmpty() }.map { line ->
            val tab = line.indexOf('\t')
            LibraryEntry(line.substring(0, tab), line.substring(tab + 1))
        }.toList()
    }.getOrNull()

    private fun writeFastLibraries(key: List<Any?>, list: List<BaseItemDto>) {
        runCatching {
            dir.mkdirs()
            val encoded = list.joinToString("\n") { lib ->
                "${lib.id}\t${lib.name.orEmpty().replace('\t', ' ').replace('\n', ' ')}"
            }
            fastLibrariesFile(key).writeAtomically(encoded, sync = false)
        }
    }

    fun clear() { dir.deleteRecursively() }

    /** Reads Libraries (lightweight TSV only, zero SDK/JSON classes) off the main thread at app start. */
    fun prefetchFastLibraries() {
        val key = listOf(App.prefs.baseUrl, App.prefs.userId)
        val entries = readFastLibraryEntries(key)
        if (entries != null) prefetchedEntries = Pair(key, entries)
    }
    @Volatile private var prefetchedEntries: Pair<List<Any?>, List<LibraryEntry>>? = null

    fun takePrefetchedFastLibraries(key: List<Any?>): List<LibraryEntry>? {
        val found = prefetchedEntries
        if (found == null || found.first != key) return null
        prefetchedEntries = null
        return found.second
    }

    fun takePrefetched(key: List<Any?>): List<BaseItemDto>? {
        val entries = takePrefetchedFastLibraries(key) ?: return null
        return entries.map { e -> BaseItemDto(id = java.util.UUID.fromString(e.id), type = BaseItemKind.COLLECTION_FOLDER, name = e.name) }
    }
}

private class FocusMemoryState(val focusedKey: MutableState<String?>) {
    val requesters = HashMap<String, FocusRequester>()
}

private val LocalFocusMemory = compositionLocalOf<FocusMemoryState?> { null }

@Composable
fun FocusMemory(content: @Composable () -> Unit) {
    val focusedKey = rememberSaveable { mutableStateOf<String?>(null) }
    val state = remember(focusedKey) { FocusMemoryState(focusedKey) }
    val shown = LocalShown.current
    LaunchedEffect(state) {
        snapshotFlow { shown.value }.collect { visible ->
            if (visible) {
                val key = state.focusedKey.value ?: return@collect
                runCatching { state.requesters[key]?.requestFocus() }
            }
        }
    }
    CompositionLocalProvider(LocalFocusMemory provides state, content = content)
}

@Composable
private fun Modifier.focusMemory(key: Any): Modifier {
    val memory = LocalFocusMemory.current ?: return this
    val k = remember(key) { key.toString() }
    val requester = remember { FocusRequester() }
    DisposableEffect(memory, k, requester) {
        memory.requesters[k] = requester
        onDispose { if (memory.requesters[k] === requester) memory.requesters.remove(k) }
    }
    return this.focusRequester(requester).onFocusChanged { focusState ->
        if (focusState.isFocused) memory.focusedKey.value = k
    }
}

/**
 * Builds a Coil [ImageRequest] at [Size.ORIGINAL] so Coil never waits for a layout pass before starting a fetch or
 * checking RAM cache, and matches [Platform.prefetchImages]'s cache keys 1:1.
 */
@Composable
private fun rememberImageRequest(url: String?): ImageRequest? {
    val context = LocalPlatformContext.current
    return remember(url, context) {
        url?.let {
            ImageRequest.Builder(context)
                .data(it)
                .size(Size.ORIGINAL)
                .build()
        }
    }
}

/** A list, plus the refresh error when the list shown is an older copy because the refresh failed. */
class Load(val result: Result<List<BaseItemDto>>?, val refreshError: Throwable?)

@Composable
private fun loadFresh(
    vararg keys: Any?,
    refresh: Int,
    persist: Boolean = true,
    fallback: (() -> List<BaseItemDto>?)? = null,
    block: suspend () -> List<BaseItemDto>,
): Load {
    val k = keys.toList()
    val cacheKey = listOf(App.prefs.baseUrl, App.prefs.userId) + k
    var last by remember { mutableStateOf<Pair<List<Any?>, Result<List<BaseItemDto>>>?>(null) }
    val syncResult = remember(cacheKey, last) {
        val current = last
        if (current != null && current.first == k) {
            current.second
        } else {
            val cached = AppCache.getList(cacheKey)?.items
                ?: ListSnapshots.takePrefetched(cacheKey)?.also { AppCache.putList(cacheKey, it, fromNetwork = false) }
                ?: fallback?.invoke()
            if (cached != null) Result.success(cached) else current?.second
        }
    }
    var refreshError by remember { mutableStateOf<Throwable?>(null) }
    val shown = LocalShown.current
    LaunchedEffect(k, refresh) {
        snapshotFlow { shown.value }.collectLatest { visible ->
            if (visible) coroutineScope {
                refreshError = null
                if (last?.second?.isFailure == true) last = null
                var existingEntry = AppCache.getList(cacheKey)
                if (last?.first != k) {
                    var cached = existingEntry?.items
                    if (cached == null && persist) {
                        cached = withContext(Dispatchers.IO) { ListSnapshots.read(cacheKey) }
                        if (cached != null) {
                            AppCache.putList(cacheKey, cached, fromNetwork = false)
                            existingEntry = AppCache.getList(cacheKey)
                        }
                    }
                    if (cached != null) {
                        last = Pair(k, Result.success(cached))
                    } else {
                        val derived = fallback?.invoke()
                        if (derived != null) last = Pair(k, Result.success(derived))
                    }
                }
                if (refresh == 0 && existingEntry != null && AppCache.isListFresh(existingEntry)) {
                    if (last?.first != k) last = Pair(k, Result.success(existingEntry.items))
                    return@coroutineScope
                }
                val changesBefore = App.server.localChanges
                val pushed = App.server.flush()
                val result = if (pushed != null && unreachable(pushed)) {
                    Result.failure(pushed)
                } else {
                    runCatching { block() }.onFailure { e ->
                        if (e is InvalidStatusException && e.status == 401) App.server.expire()
                    }
                }
                ensureActive()
                val prev = last
                when {
                    result.isSuccess -> {
                        val list = result.getOrThrow()
                        val prevList = existingEntry?.items
                        AppCache.putList(cacheKey, list, fromNetwork = true, localChanges = changesBefore)
                        if (persist) launch(Dispatchers.IO) { ListSnapshots.writeIfChanged(cacheKey, list, prevList) }
                        last = Pair(k, result)
                    }
                    prev != null && prev.first == k && prev.second.isSuccess -> refreshError = result.exceptionOrNull()
                    else -> last = Pair(k, result)
                }
            }
        }
    }
    return Load(syncResult, refreshError)
}

/**
 * Download statuses as a [State] so item rows read only their own entry via [derivedStateOf] instead of recomposing
 * the entire screen every second. Polls every 1 s while running, every 5 s while queued/paused, and stops polling
 * completely when every download is done (waking immediately on [Downloads.changes]).
 */
@Composable
fun rememberDownloadStatusesState(): State<Map<String, DownloadStatus>?> {
    val downloads = App.downloads ?: return remember { mutableStateOf(null) }
    val shown = LocalShown.current
    return produceState<Map<String, DownloadStatus>?>(null, downloads.changes) {
        snapshotFlow { shown.value }.collectLatest { visible ->
            while (visible) {
                val map = withContext(Dispatchers.IO) { downloads.downloadStatuses() }
                value = map
                val running = map.values.any { status -> status.fill == Fill.RUNNING }
                val unfinished = running || map.values.any { status -> status.fill != Fill.DONE }
                if (!unfinished) break
                delay(if (running) 1_000 else 5_000)
            }
        }
    }
}

@Composable
fun rememberDownloadStatuses(): Map<String, DownloadStatus>? = rememberDownloadStatusesState().value

private suspend fun downloadAll(episodes: List<BaseItemDto>) {
    val downloads = App.downloads ?: return
    val noun = if (episodes.all { it.type == BaseItemKind.MOVIE }) "movie" else "episode"
    var queued = 0
    val failures = mutableListOf<Throwable>()
    withContext(Dispatchers.IO) {
        for (ep in episodes) {
            if (downloads.download(ep.id.toString()) != null) continue
            runCatching {
                val full = if (ep.mediaSources != null) ep else App.server.item(ep.id.toString())
                downloads.startDownload(full, auto = false)
            }
                .onSuccess { queued++ }
                .onFailure { e ->
                    if (e is CancellationException) throw e
                    failures += e
                }
        }
    }
    val firstFailure = failures.firstOrNull()
    var text = when {
        queued == 0 -> if (firstFailure != null) loadError(firstFailure) else "Nothing new to download"
        queued == 1 -> "Downloading 1 $noun"
        else -> "Downloading $queued ${noun}s"
    }
    if (queued > 0 && failures.isNotEmpty()) text += ", ${failures.size} failed"
    if (queued > 0) App.platform.askNotifications()
    val waitingFor = downloads.downloadsWaitingFor()
    if (queued > 0 && waitingFor != null) text += " ($waitingFor)"
    toast(text)
}

@Composable
private fun downloadAllAction(fetch: suspend () -> List<BaseItemDto>): () -> Unit {
    var busy by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf<List<BaseItemDto>?>(null) }
    val scope = rememberCoroutineScope()
    val eps = pending
    if (eps != null && LocalShown.current.value) {
        val sizes = eps.mapNotNull { ep -> ep.mediaSources?.firstOrNull()?.size }
        val total = if (sizes.size == eps.size) sizes.sum() else null
        val plural = if (eps.size == 1) "" else "s"
        val about = if (total != null) ", about ${formatBytes(total)}" else ""
        Confirm(
            "Download ${eps.size} episode$plural$about?",
            "Download",
            onConfirm = {
                busy = true
                scope.launch {
                    downloadAll(eps)
                    busy = false
                }
            },
            onDismiss = { pending = null },
        )
    }
    return action@{
        if (busy) return@action
        busy = true
        toast("Checking episodes…")
        scope.launch {
            runCatching { fetch() }
                .onSuccess { fetched ->
                    val new = withContext(Dispatchers.IO) {
                        fetched.filter { ep -> App.downloads?.download(ep.id.toString()) == null }
                    }
                    if (new.isEmpty()) toast("Nothing new to download") else pending = new
                }
                .onFailure { e ->
                    if (e is CancellationException) throw e
                    toast(loadError(e))
                }
            busy = false
        }
    }
}

@Composable
private fun BarMenu(content: @Composable (close: () -> Unit) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { GlyphIcon(AppIcons.MORE_VERT, "More options") }
        if (open) DropdownMenu(true, { open = false }) { content { open = false } }
    }
}

@Composable
private fun markAllAction(onChanged: () -> Unit): (List<BaseItemDto>) -> Unit {
    var asking by remember { mutableStateOf<List<BaseItemDto>?>(null) }
    val scope = rememberCoroutineScope()
    val eps = asking
    if (eps != null) {
        val played = !eps.all { ep -> ep.localState().first?.played == true }
        val plural = if (eps.size == 1) "" else "s"
        val playedWord = if (played) "played" else "unplayed"
        Confirm(
            "Mark ${eps.size} episode$plural $playedWord?",
            "Mark",
            onConfirm = {
                scope.launch {
                    App.server.setPlayed(eps.map { ep -> ep.id.toString() }, played)
                    onChanged()
                }
            },
            onDismiss = { asking = null },
        )
    }
    return { list -> asking = list }
}

/** Where to pick up a series: the unfinished episode last played, else the first unplayed one after it, else the first. Null once all is played. */
fun upNext(episodes: List<BaseItemDto>): BaseItemDto? {
    val playable = ArrayList<BaseItemDto>(episodes.size)
    val refs = ArrayList<EpisodeRef>(episodes.size)
    val playedById = HashMap<String, Boolean>(episodes.size)
    for (ep in episodes) {
        if (ep.parentIndexNumber == 0 || ep.locationType == LocationType.VIRTUAL) continue
        val id = ep.id.toString()
        val state = ep.localState().first
        playable += ep
        refs += EpisodeRef(id, state)
        playedById[id] = state?.played == true
    }
    if (playable.isEmpty()) return null
    val window = keepNextWindow(refs, playable.size).toHashSet()
    return playable.firstOrNull { ep ->
        val id = ep.id.toString()
        id in window && playedById[id] != true
    }
}

private suspend fun startOver(item: BaseItemDto, push: (Screen) -> Unit) {
    App.server.startOver(item)
    push(Screen.Play(item.id.toString()))
}

@Composable
private fun Confirm(text: String, confirmLabel: String, onConfirm: () -> Unit, onDismiss: () -> Unit) = AlertDialog(onDismiss,
    confirmButton = {
        TextButton(onClick = {
            onDismiss()
            onConfirm()
        }) { Text(confirmLabel) }
    },
    dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    text = { Text(text) })

@Composable
private fun ConfirmDelete(item: BaseItemDto?, onDismiss: () -> Unit, onDeleted: () -> Unit = {}) {
    val scope = rememberCoroutineScope()
    if (item == null) return
    Confirm("Delete download of ${itemLabel(item)}?", "Delete", {
        scope.launch {
            withContext(Dispatchers.IO) { App.downloads?.deleteDownload(item.id.toString()) }
            onDeleted()
        }
    }, onDismiss)
}

fun itemLabel(item: BaseItemDto) = if (item.type == BaseItemKind.EPISODE) episodeLabel(item) else item.name.orEmpty()

fun formatBytes(b: Long) = if (b >= 1e9) "%.1f GB".format(b / 1e9) else "%.0f MB".format(b / 1e6)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Bar(title: String, onBack: (() -> Unit)?, actions: @Composable () -> Unit = {}) = TopAppBar(
    title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
    navigationIcon = {
        if (onBack != null) IconButton(onClick = onBack) { GlyphIcon(AppIcons.ARROW_BACK, "Back", autoMirror = true) }
    },
    actions = { actions() },
)

fun loadError(e: Throwable) = when {
    e is InvalidStatusException -> "The server returned an error (${e.status})"
    untrustedCertificate(e) -> "The server's certificate isn't trusted"
    unreachable(e) -> "Can't reach the server"
    else -> "Something went wrong"
}

@Composable
fun ErrorText(sentence: String, raw: String?, color: Color = Color.Unspecified) {
    Text(sentence, color = color)
    if (raw != null) Text(raw, color = color, style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun Loaded(load: Load, onRetry: () -> Unit, onDownloads: () -> Unit, content: @Composable (List<BaseItemDto>) -> Unit) {
    val result = load.result
    when {
        result == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        result.isFailure -> Column(
            Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val e = result.exceptionOrNull()!!
            if (unreachable(e)) RetryOnNewNetwork(onRetry)
            ErrorText(loadError(e), e.message ?: e.javaClass.simpleName)
            Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = onRetry) { Text("Retry") }
                if (App.downloads != null) TextButton(onClick = onDownloads) { Text("Downloads") }
            }
        }
        else -> Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f)) { content(result.getOrThrow()) }
            val e = load.refreshError
            if (e != null) {
                if (unreachable(e)) RetryOnNewNetwork(onRetry)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(start = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("${loadError(e)}. Showing the last list.", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = onRetry) { Text("Retry") }
                }
            }
        }
    }
}

@Composable
private fun Empty(text: String) = Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) { Text(text) }

@Composable
fun LoginScreen(
    modifier: Modifier,
    onBack: (() -> Unit)? = null,
    title: String? = null,
    subtitle: String? = null,
    initialUser: String? = null,
    onSwitchAccount: (() -> Unit)? = null,
    onDone: () -> Unit,
) {
    val adding = onBack != null
    val saved = remember { splitServerAddress(App.prefs.baseUrl) }
    var url by rememberSaveable { mutableStateOf(saved.first) }
    var port by rememberSaveable { mutableStateOf(saved.second) }
    var portEdited by rememberSaveable { mutableStateOf(false) }
    val prefilledUser = initialUser ?: if (App.sessionExpired && !adding) App.expiredUserName.orEmpty() else ""
    var user by rememberSaveable { mutableStateOf(prefilledUser) }
    var pass by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<Throwable?>(null) }
    var busy by remember { mutableStateOf(false) }
    var showPass by remember { mutableStateOf(false) }
    val userFocus = remember { FocusRequester() }
    val passFocus = remember { FocusRequester() }
    var focusedOnce by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (!focusedOnce) {
            focusedOnce = true
            if (prefilledUser.isNotBlank()) runCatching { passFocus.requestFocus() }
            else if (adding) runCatching { userFocus.requestFocus() }
        }
    }
    val scope = rememberCoroutineScope()
    val portOk = isValidPort(port.trim())
    val canSubmit = portOk && url.isNotBlank() && user.isNotBlank()
    fun submit() {
        if (busy || !canSubmit) return
        busy = true
        scope.launch {
            runCatching { App.server.login(url, port, user, pass) }
                .onSuccess { onDone() }
                .onFailure { error = it }
            busy = false
        }
    }
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
    Column(
        Modifier
            .widthIn(max = 560.dp)
            .fillMaxHeight()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (onBack != null) IconButton(onClick = onBack) { GlyphIcon(AppIcons.ARROW_BACK, "Back", autoMirror = true) }
            Text(title ?: if (adding) "Add account" else "finimal", style = MaterialTheme.typography.headlineMedium)
        }
        if (subtitle != null) Text(subtitle)
        else if (App.sessionExpired && !adding) Text("The server ended this session. Log in again; unsynced progress is kept.")
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                url,
                { text ->
                    url = text
                    error = null
                    if (!portEdited) {
                        val guess = defaultPortFor(text)
                        if (guess != null) port = guess
                    }
                },
                label = { Text("Server") },
                placeholder = { Text("jellyfin.example.com") },
                singleLine = true,
                enabled = !busy && !adding,
                modifier = Modifier.weight(1f),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
            )
            OutlinedTextField(
                port,
                { text ->
                    port = text.filter(Char::isDigit).take(5)
                    portEdited = true
                    error = null
                },
                label = { Text("Port") },
                singleLine = true,
                enabled = !busy && !adding,
                isError = !portOk,
                modifier = Modifier.width(112.dp),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
            )
        }
        if (!portOk) Text("The port is a number from 1 to 65535, or empty for the default of https or http.", color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(
            user,
            { text ->
                user = text
                error = null
            },
            label = { Text("Username") },
            singleLine = true,
            enabled = !busy,
            modifier = Modifier.fillMaxWidth().focusRequester(userFocus).semantics { contentType = ContentType.Username },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
        )
        OutlinedTextField(
            pass,
            { text ->
                pass = text
                error = null
            },
            label = { Text("Password") },
            singleLine = true,
            enabled = !busy,
            modifier = Modifier.fillMaxWidth().focusRequester(passFocus).semantics { contentType = ContentType.Password },
            visualTransformation = if (showPass) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = { TextButton(onClick = { showPass = !showPass }) { Text(if (showPass) "Hide" else "Show") } },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { submit() }),
        )
        val e = error
        if (e != null) {
            if (e is InvalidStatusException && e.status == 401) {
                Text("Wrong username or password", color = MaterialTheme.colorScheme.error)
            } else {
                val message = loadError(e)
                val where = if (unreachable(e)) " at ${serverCandidates(url, port).joinToString(" or ")}" else ""
                ErrorText(message + where, e.message ?: e.javaClass.simpleName, MaterialTheme.colorScheme.error)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(enabled = !busy && canSubmit, onClick = ::submit) {
                if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text("Log in")
            }
            if (App.sessionExpired && !adding && onSwitchAccount != null && App.prefs.accounts().isNotEmpty()) {
                TextButton(enabled = !busy, onClick = onSwitchAccount) { Text("Switch account") }
            }
        }
    }
    }
}

@Composable
fun LibrariesScreen(modifier: Modifier, push: (Screen) -> Unit) {
    var refresh by remember { mutableIntStateOf(0) }
    val libs = loadFresh(refresh = refresh) { App.server.libraries() }
    val libList = libs.result?.getOrNull()
    LaunchedEffect(libList) {
        val ids = libList?.map { it.id.toString() } ?: return@LaunchedEffect
        AppCache.prefetchLibrariesFromDisk(ids)
        val entry = AppCache.getList(AppCache.listKey())
        if (entry?.fromNetwork == true) AppCache.prefetchLibrariesNetwork(ids)
    }
    var userName by remember { mutableStateOf(App.prefs.currentUserName()) }
    LaunchedEffect(Unit) {
        val uid = App.prefs.userId
        if (uid == null || !userName.isNullOrEmpty()) return@LaunchedEffect
        val name = runCatching { App.server.userName() }.getOrNull()
        if (!name.isNullOrEmpty()) {
            App.prefs.setName(uid, name)
            userName = name
        }
    }
    Column(
        modifier
            .fillMaxSize()
            .screenShortcuts(
                onRefresh = { refresh++ },
                onSearch = { push(Screen.Library(null, "Search")) },
            )
    ) {
        Bar("finimal", null) {
            IconButton(onClick = { push(Screen.Library(null, "Search")) }) { GlyphIcon(AppIcons.SEARCH, "Search") }
            if (App.downloads != null) IconButton(onClick = { push(Screen.Downloads) }) { GlyphIcon(AppIcons.DOWNLOAD, "Downloads") }
            IconButton(onClick = { push(Screen.Settings) }) { GlyphIcon(AppIcons.SETTINGS, "Settings") }
            IconButton(onClick = { push(Screen.Accounts) }) { Avatar(userName.orEmpty(), Modifier.semantics { contentDescription = "Accounts" }) }
            BarMenu { close ->
                DropdownMenuItem({ Text("Refresh") }, {
                    close()
                    refresh++
                })
            }
        }
        Loaded(libs, { refresh++ }, { push(Screen.Downloads) }) { list ->
            if (list.isEmpty()) Empty("No movie or TV libraries on this server")
            else Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                for (lib in list) {
                    key(lib.id) {
                        Text(
                            lib.name.orEmpty(),
                            style = MaterialTheme.typography.titleLarge,
                            modifier = Modifier
                                .fillMaxWidth()
                                .focusMemory(lib.id)
                                .combinedClickable(onClick = { push(Screen.Library(lib.id.toString(), lib.name.orEmpty())) })
                                .padding(20.dp),
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

/** A library's poster grid; with a null id it searches every library. */
@Composable
fun LibraryScreen(modifier: Modifier, screen: Screen.Library, push: (Screen) -> Unit, pop: () -> Unit) {
    val searchOnly = screen.id == null
    var sort by rememberSaveable { mutableStateOf(if (screen.id != null) App.prefs.librarySort(screen.id) else Sort.NAME) }
    var unplayed by rememberSaveable { mutableStateOf(if (screen.id != null) App.prefs.libraryUnplayed(screen.id) else false) }
    var search by rememberSaveable { mutableStateOf("") }
    var query by rememberSaveable { mutableStateOf("") }
    var searchOpen by rememberSaveable { mutableStateOf(searchOnly) }
    val showSearch = searchOnly || searchOpen || search.isNotEmpty()
    var searchFocused by remember { mutableStateOf(false) }
    var searchFocusTrigger by remember { mutableIntStateOf(0) }
    var refresh by remember { mutableIntStateOf(0) }
    LaunchedEffect(search) {
        if (search == query) return@LaunchedEffect
        if (search.isEmpty()) {
            query = ""
        } else {
            delay(250)
            query = search
        }
    }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    var focusedOnce by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (searchOnly && !focusedOnce) {
            focusedOnce = true
            runCatching { focus.requestFocus() }
            keyboard?.show()
        }
    }
    LaunchedEffect(searchFocusTrigger) {
        if (searchFocusTrigger > 0 && showSearch) {
            runCatching { focus.requestFocus() }
            keyboard?.show()
        }
    }
    fun openSearch() {
        searchOpen = true
        searchFocusTrigger++
    }
    val libraryId = screen.id
    val items = if (searchOnly && query.isBlank()) {
        Load(Result.success(emptyList()), null)
    } else {
        loadFresh(
            libraryId,
            sort,
            unplayed,
            query,
            refresh = refresh,
            persist = query.isBlank(),
            fallback = if (libraryId != null) {
                { AppCache.deriveLibraryList(App.prefs.baseUrl, App.prefs.userId, libraryId, sort, unplayed, query) }
            } else {
                null
            },
        ) { App.server.items(libraryId, sort, unplayed, query) }
    }
    val livePreview = remember(libraryId, sort, unplayed, search, query) {
        if (libraryId != null && search.isNotBlank() && search != query) {
            AppCache.deriveLibraryList(App.prefs.baseUrl, App.prefs.userId, libraryId, sort, unplayed, search)
        } else {
            null
        }
    }
    val shownLoad = if (livePreview != null && (livePreview.isNotEmpty() || items.result == null)) {
        Load(Result.success(livePreview), items.refreshError)
    } else {
        items
    }
    Column(
        modifier
            .fillMaxSize()
            .screenShortcuts(
                onRefresh = { refresh++ },
                onSearch = ::openSearch,
                searchFocused = searchFocused,
                focusOnShow = !showSearch,
            )
    ) {
        Bar(screen.name, pop) {
            if (!searchOnly) {
                IconButton(onClick = {
                    if (showSearch) {
                        searchOpen = false
                        search = ""
                        query = ""
                        keyboard?.hide()
                    } else {
                        openSearch()
                    }
                }) { GlyphIcon(AppIcons.SEARCH, "Search") }
            }
            BarMenu { close ->
                DropdownMenuItem({ Text("Refresh") }, {
                    close()
                    refresh++
                })
            }
        }
        if (showSearch) OutlinedTextField(
            search,
            { search = it },
            label = { Text("Search") },
            singleLine = true,
            trailingIcon = if (search.isNotEmpty()) {
                {
                    IconButton(onClick = {
                        search = ""
                        query = ""
                    }) { GlyphIcon(AppIcons.CLOSE, "Clear search") }
                }
            } else {
                null
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
                .focusRequester(focus)
                .onFocusChanged { searchFocused = it.isFocused },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = {
                query = search
                keyboard?.hide()
            }),
        )
        if (libraryId != null) Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // The server orders search results by how well they match, whatever the sort.
            Sort.entries.forEach { s ->
                FilterChip(sort == s, {
                    sort = s
                    App.prefs.setLibrarySort(libraryId, s)
                }, { Text(s.label) }, enabled = query.isBlank())
            }
            FilterChip(unplayed, {
                unplayed = !unplayed
                App.prefs.setLibraryUnplayed(libraryId, unplayed)
            }, { Text("Unplayed") })
        }
        val statusesState = rememberDownloadStatusesState()
        val scope = rememberCoroutineScope()
        val gridState = rememberSaveable(sort, unplayed, query, saver = LazyGridState.Saver) { LazyGridState() }
        var hoveredItem by remember { mutableStateOf<BaseItemDto?>(null) }
        LaunchedEffect(hoveredItem) {
            val target = hoveredItem ?: return@LaunchedEffect
            delay(150)
            if (target.type == BaseItemKind.SERIES) {
                AppCache.prefetchSeries(target.id.toString())
            } else if (target.type == BaseItemKind.MOVIE) {
                AppCache.prefetchPlayable(target.id.toString(), includeSubtitles = false)
            }
        }
        Loaded(shownLoad, { refresh++ }, { push(Screen.Downloads) }) { list ->
            if (list.isEmpty()) Empty(when {
                search.isNotBlank() && search != query -> "Searching…"
                query.isNotBlank() -> "No results for “${query.trim()}”"
                searchOnly -> "Type to search"
                unplayed -> "Nothing unplayed here"
                else -> "This library is empty"
            })
            else {
                LaunchedEffect(list, gridState) {
                    var prefetchedThrough = -1
                    snapshotFlow { gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
                        .distinctUntilChanged()
                        .collect { lastVisible ->
                            val end = (lastVisible + 18).coerceAtMost(list.lastIndex)
                            if (end > prefetchedThrough) {
                                val start = (prefetchedThrough + 1).coerceAtLeast(lastVisible + 1)
                                prefetchedThrough = end
                                if (start <= end) {
                                    val urls = (start..end).mapNotNull { i -> App.server.imageUrl(list[i]) }
                                    if (urls.isNotEmpty()) App.platform.prefetchImages(urls, diskOnly = App.platform.isMetered)
                                }
                            }
                        }
                }
                val cellWidth = when {
                    App.tv -> 150.dp
                    smallestWidthDp() >= 600 -> 140.dp
                    else -> 110.dp
                }
                LazyVerticalGrid(
                    GridCells.Adaptive(cellWidth),
                    Modifier.fillMaxSize().padding(8.dp),
                    gridState,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(list, key = { it.id }) { item ->
                        if (item.type == BaseItemKind.SERIES) {
                            Poster(
                                item,
                                statusesState = null,
                                onClick = { push(Screen.Series(item.id.toString(), item.name.orEmpty())) },
                                onFocusOrHover = { hoveredItem = item },
                            )
                        } else {
                            Poster(
                                item,
                                statusesState = statusesState,
                                onClick = { push(Screen.Play(item.id.toString())) },
                                onStartOver = { scope.launch { startOver(item, push) } },
                                onChanged = { refresh++ },
                                onFocusOrHover = { hoveredItem = item },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Poster(
    item: BaseItemDto,
    statusesState: State<Map<String, DownloadStatus>?>?,
    onClick: () -> Unit,
    onStartOver: (() -> Unit)? = null,
    onChanged: (() -> Unit)? = null,
    onFocusOrHover: (() -> Unit)? = null,
) {
    val itemId = remember(item.id) { item.id.toString() }
    val local by remember(item) { derivedStateOf { item.localState() } }
    val (state, unsynced) = local
    val download by remember(itemId, statusesState) {
        derivedStateOf { statusesState?.value?.get(itemId) }
    }
    var menu by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var showInfo by remember { mutableStateOf(false) }
    var focused by remember { mutableStateOf(false) }
    var menuAt by remember { mutableStateOf<Offset?>(null) }
    val openMenu: (() -> Unit)? = if (onChanged != null) remember { { menuAt = null; menu = true } } else null
    val openMenuAt: ((Offset) -> Unit)? = if (onChanged != null) remember { { at: Offset -> menuAt = at; menu = true } } else null
    val latestHover by rememberUpdatedState(onFocusOrHover)
    val imageTag = item.imageTags?.get(ImageType.PRIMARY)
    val imageUrl = remember(item.id, imageTag) { App.server.imageUrl(item) }
    val imageRequest = rememberImageRequest(imageUrl)
    val meta = remember(item.id, item.type, item.productionYear, item.runTimeTicks) {
        if (item.type == BaseItemKind.MOVIE) {
            listOfNotNull(
                item.productionYear?.toString(),
                item.runTimeTicks?.takeIf { it > 0 }?.let(::formatRuntime),
            ).joinToString(" · ")
        } else {
            ""
        }
    }
    Column(
        Modifier
            .focusMemory(item.id)
            .onFocusChanged { focusState ->
                focused = focusState.isFocused
                if (focusState.isFocused) latestHover?.invoke()
            }
            .then(
                if (!App.platform.android) {
                    Modifier.pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                val e = awaitPointerEvent()
                                if (e.type == PointerEventType.Enter) latestHover?.invoke()
                            }
                        }
                    }
                } else {
                    Modifier
                }
            )
            .onSecondaryClick(openMenuAt)
            .combinedClickable(onClick = onClick, onLongClickLabel = "Actions", onLongClick = openMenu)
    ) {
        Box(if (focused) Modifier.border(3.dp, MaterialTheme.colorScheme.primary) else Modifier) {
            AsyncImage(imageRequest, null, contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxWidth().aspectRatio(2f / 3).background(Color.DarkGray))
            val dl = download
            if (dl != null) {
                Text(
                    if (dl.fill == Fill.DONE) "↓" else "${dl.percent}%",
                    color = Color.White,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .background(Color(0xAA000000))
                        .padding(horizontal = 6.dp)
                        .semantics {
                            contentDescription = if (dl.fill == Fill.DONE) "Downloaded" else "Downloaded ${dl.percent}%"
                        },
                )
            }
            val unplayedCount = item.userData?.unplayedItemCount ?: 0
            val badge = when {
                state?.played == true -> "✓"
                unplayedCount > 0 -> unplayedCount.toString()
                else -> null
            }
            if (badge != null) {
                Text(
                    badge,
                    color = Color.White,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .background(Color(0xAA000000))
                        .padding(horizontal = 6.dp)
                        .semantics { contentDescription = if (badge == "✓") "Played" else "$badge unplayed" },
                )
            }
            if (unsynced) Text("not synced", color = Color.White, style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.align(Alignment.BottomStart).padding(bottom = if (focused) 7.dp else 4.dp).background(Color(0xAA000000)))
            ProgressBar(item, state, Modifier.align(Alignment.BottomStart).padding(if (focused) 3.dp else 0.dp))
            if (onChanged != null && menu) MenuAt(menuAt) {
                ItemMenu(
                    item,
                    state?.played == true,
                    unsynced,
                    onDismiss = { menu = false },
                    downloaded = dl != null,
                    onStartOver = onStartOver,
                    onRequestDelete = { confirmDelete = true },
                    onRequestInfo = { showInfo = true },
                    onChanged = onChanged,
                )
            }
        }
        if (onChanged != null && confirmDelete) ConfirmDelete(item, { confirmDelete = false }, onChanged)
        if (onChanged != null && showInfo) ItemInfoDialog(item) { showInfo = false }
        Text(item.name.orEmpty(), maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
        if (meta.isNotEmpty()) {
            Text(
                meta,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
fun SeriesScreen(modifier: Modifier, screen: Screen.Series, push: (Screen) -> Unit, pop: () -> Unit) {
    var refresh by remember { mutableIntStateOf(0) }
    val seasons = loadFresh(screen.id, refresh = refresh) { App.server.seasons(screen.id) }
    val episodes = loadFresh(screen.id, "episodes", refresh = refresh) { App.server.episodes(screen.id) }
    val statusesState = rememberDownloadStatusesState()
    var keepNext by remember { mutableIntStateOf(App.prefs.keepNext(screen.id)) }
    var showKeepNextDialog by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val downloadAll = downloadAllAction { App.server.episodes(screen.id, sources = true).filter { it.parentIndexNumber != 0 } }
    val markAll = markAllAction { refresh++ }
    val all = episodes.result?.getOrNull()
    val onlySeason = seasons.result?.getOrNull()?.singleOrNull()
    val shownEpisodes = remember(all, onlySeason) {
        if (onlySeason != null) all?.filter { ep -> ep.seasonId == onlySeason.id } else null
    }
    val next by remember(all) { derivedStateOf { if (all != null) upNext(all) else null } }
    val seasonNext by remember(shownEpisodes) {
        derivedStateOf {
            if (shownEpisodes != null && shownEpisodes.any { ep -> ep.localState().first?.lastPlayedMs != null }) {
                upNext(shownEpisodes)
            } else {
                null
            }
        }
    }
    val unplayedBySeason by remember(all) {
        derivedStateOf {
            if (all == null) null
            else {
                val counts = HashMap<java.util.UUID, Int>()
                for (ep in all) {
                    val sid = ep.seasonId ?: continue
                    if (ep.locationType != LocationType.VIRTUAL && ep.localState().first?.played != true) {
                        counts[sid] = (counts[sid] ?: 0) + 1
                    }
                }
                counts
            }
        }
    }
    LaunchedEffect(next?.id) {
        val target = next ?: return@LaunchedEffect
        AppCache.prefetchPlayable(target.id.toString())
        val seasonEps = all?.filter { it.seasonId == target.seasonId }.orEmpty()
        val idx = seasonEps.indexOfFirst { it.id == target.id }.coerceAtLeast(0)
        AppCache.prefetchEpisodeThumbnails(seasonEps, focusIndex = idx, count = 10)
    }
    val listState = rememberLazyListState()
    var scrolled by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(seasonNext != null) {
        val i = shownEpisodes.orEmpty().indexOf(seasonNext)
        if (!scrolled && i > 0) {
            scrolled = true
            listState.scrollToItem(i - 1)
        }
    }
    fun updateKeepNext(n: Int) {
        if (keepNext == 0 && n > 0) App.platform.askNotifications()
        keepNext = n
        App.prefs.setKeepNext(screen.id, n)
        scope.launch(Dispatchers.IO) {
            if (n == 0) App.downloads?.releaseKeepNext(screen.id)
            App.platform.scheduleHousekeeping()
        }
    }
    if (showKeepNextDialog) {
        AlertDialog(
            onDismissRequest = { showKeepNextDialog = false },
            title = { Text("Keep next episodes") },
            text = {
                Stepper(
                    "Episodes downloaded",
                    keepNext,
                    (0..20).toList(),
                    { count -> if (count == 0) "off" else "$count" },
                    Modifier.fillMaxWidth(),
                    ::updateKeepNext,
                )
            },
            confirmButton = { TextButton(onClick = { showKeepNextDialog = false }) { Text("Done") } },
        )
    }
    val markable = remember(shownEpisodes, all) {
        val candidates = shownEpisodes ?: all?.filter { ep -> ep.parentIndexNumber != 0 && ep.locationType != LocationType.VIRTUAL }
        if (candidates.isNullOrEmpty()) null else candidates
    }
    Column(modifier.fillMaxSize().screenShortcuts(onRefresh = { refresh++ })) {
        Bar(screen.name, pop) {
            BarMenu { close ->
                DropdownMenuItem({ Text("Refresh") }, {
                    close()
                    refresh++
                })
                if (App.downloads != null) {
                    DropdownMenuItem({ Text("Download all") }, {
                        close()
                        downloadAll()
                    })
                    DropdownMenuItem({ Text("Keep next episodes…") }, {
                        close()
                        showKeepNextDialog = true
                    })
                }
                if (markable != null) {
                    val played = markable.all { ep -> ep.localState().first?.played == true }
                    DropdownMenuItem({ Text(if (played) "Mark all unplayed" else "Mark all played") }, {
                        close()
                        markAll(markable)
                    })
                }
            }
        }
        val continueEp = next
        if (continueEp != null) ContinueButton(continueEp) { push(Screen.Play(continueEp.id.toString())) }
        if (App.downloads != null && keepNext > 0) {
            val plural = if (keepNext == 1) "" else "s"
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { showKeepNextDialog = true }
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Keeping next $keepNext episode$plural downloaded",
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text("Change", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            }
        }
        Loaded(seasons, { refresh++ }, { push(Screen.Downloads) }) { list ->
            if (list.isEmpty()) Empty("This series has no seasons")
            else if (shownEpisodes != null) {
                LaunchedEffect(shownEpisodes, listState) {
                    var prefetchedThrough = -1
                    snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
                        .distinctUntilChanged()
                        .collect { lastVisible ->
                            val end = (lastVisible + 8).coerceAtMost(shownEpisodes.lastIndex)
                            if (end > prefetchedThrough) {
                                val start = (prefetchedThrough + 1).coerceAtLeast(lastVisible + 1)
                                prefetchedThrough = end
                                if (start <= end) {
                                    val urls = (start..end).mapNotNull { i -> App.server.imageUrl(shownEpisodes[i], ImageType.PRIMARY, 320) }
                                    if (urls.isNotEmpty()) App.platform.prefetchImages(urls, diskOnly = App.platform.isMetered)
                                }
                            }
                        }
                }
                LazyColumn(state = listState) {
                    items(shownEpisodes, key = { it.id }) { ep ->
                        EpisodeRow(
                            ep,
                            statusesState = statusesState,
                            highlighted = ep.id == seasonNext?.id,
                            onPlay = { push(Screen.Play(ep.id.toString())) },
                            onStartOver = { scope.launch { startOver(ep, push) } },
                            onChanged = { refresh++ },
                        )
                    }
                }
            }
            else LazyColumn(state = listState) {
                items(list, key = { it.id }) { season ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .focusMemory(season.id)
                            .combinedClickable(onClick = {
                                push(Screen.Season(screen.id, season.id.toString(), "${screen.name} · ${season.name}"))
                            })
                            .padding(16.dp)
                    ) {
                        Text(season.name.orEmpty(), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                        val left = unplayedBySeason?.let { it[season.id] ?: 0 } ?: (season.userData?.unplayedItemCount ?: 0)
                        Text(
                            if (left == 0) "✓" else "$left unplayed",
                            Modifier.semantics { if (left == 0) contentDescription = "Played" },
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
fun SeasonScreen(modifier: Modifier, screen: Screen.Season, push: (Screen) -> Unit, pop: () -> Unit) {
    var refresh by remember { mutableIntStateOf(0) }
    val episodes = loadFresh(screen.seasonId, refresh = refresh) { App.server.episodes(screen.seriesId, screen.seasonId) }
    val statusesState = rememberDownloadStatusesState()
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val downloadAll = downloadAllAction { App.server.episodes(screen.seriesId, screen.seasonId, sources = true) }
    val markAll = markAllAction { refresh++ }
    val list = episodes.result?.getOrNull()
    val next by remember(list) {
        derivedStateOf {
            if (list != null && list.any { ep -> ep.localState().first?.lastPlayedMs != null }) upNext(list) else null
        }
    }
    LaunchedEffect(next?.id, list) {
        val target = next ?: list?.firstOrNull { it.locationType != LocationType.VIRTUAL } ?: return@LaunchedEffect
        AppCache.prefetchPlayable(target.id.toString())
    }
    var scrolled by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(next != null) {
        val i = list.orEmpty().indexOf(next)
        if (!scrolled && i > 0) {
            scrolled = true
            listState.scrollToItem(i - 1)
        }
    }
    Column(modifier.fillMaxSize().screenShortcuts(onRefresh = { refresh++ })) {
        Bar(screen.name, pop) {
            BarMenu { close ->
                DropdownMenuItem({ Text("Refresh") }, {
                    close()
                    refresh++
                })
                if (App.downloads != null) {
                    DropdownMenuItem({ Text("Download season") }, {
                        close()
                        downloadAll()
                    })
                }
                if (!list.isNullOrEmpty()) {
                    val played = list.all { ep -> ep.localState().first?.played == true }
                    DropdownMenuItem({ Text(if (played) "Mark season unplayed" else "Mark season played") }, {
                        close()
                        markAll(list)
                    })
                }
            }
        }
        Loaded(episodes, { refresh++ }, { push(Screen.Downloads) }) { eps ->
            if (eps.isEmpty()) Empty("This season has no episodes")
            else {
                LaunchedEffect(eps, listState) {
                    var prefetchedThrough = -1
                    snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
                        .distinctUntilChanged()
                        .collect { lastVisible ->
                            val end = (lastVisible + 8).coerceAtMost(eps.lastIndex)
                            if (end > prefetchedThrough) {
                                val start = (prefetchedThrough + 1).coerceAtLeast(lastVisible + 1)
                                prefetchedThrough = end
                                if (start <= end) {
                                    val urls = (start..end).mapNotNull { i -> App.server.imageUrl(eps[i], ImageType.PRIMARY, 320) }
                                    if (urls.isNotEmpty()) App.platform.prefetchImages(urls, diskOnly = App.platform.isMetered)
                                }
                            }
                        }
                }
                LazyColumn(state = listState) {
                    items(eps, key = { it.id }) { ep ->
                        EpisodeRow(
                            ep,
                            statusesState = statusesState,
                            highlighted = ep.id == next?.id,
                            onPlay = { push(Screen.Play(ep.id.toString())) },
                            onStartOver = { scope.launch { startOver(ep, push) } },
                            onChanged = { refresh++ },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ContinueButton(ep: BaseItemDto, onClick: () -> Unit) {
    val local by remember(ep) { derivedStateOf { ep.localState() } }
    val position = local.first?.positionTicks ?: 0
    val runtime = ep.runTimeTicks
    val minutesLeft = if (runtime != null && position > 0) (runtime - position) / TICKS_PER_MS / 60_000 else null
    Button(onClick, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        GlyphIcon(AppIcons.PLAY, null)
        val verb = if (position > 0) "Continue " else "Play "
        Text(
            verb + episodeLabel(ep),
            Modifier.padding(start = 8.dp).weight(1f, fill = false),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (minutesLeft != null) {
            Text(" · ${minutesLeft.coerceAtLeast(1)} min left", maxLines = 1)
        }
    }
}

@Composable
fun EpisodeRow(
    ep: BaseItemDto,
    download: DownloadStatus? = null,
    statusesState: State<Map<String, DownloadStatus>?>? = null,
    highlighted: Boolean = false,
    onPlay: () -> Unit,
    onStartOver: (() -> Unit)? = null,
    onChanged: () -> Unit = {},
) {
    val epId = remember(ep.id) { ep.id.toString() }
    val local by remember(ep) { derivedStateOf { ep.localState() } }
    val (state, unsynced) = local
    val resolvedDownload by remember(epId, download, statusesState) {
        derivedStateOf { download ?: statusesState?.value?.get(epId) }
    }
    var menu by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var showInfo by remember { mutableStateOf(false) }
    var menuAt by remember { mutableStateOf<Offset?>(null) }
    val openMenu = remember { { menuAt = null; menu = true } }
    val openMenuAt = remember { { at: Offset -> menuAt = at; menu = true } }
    val imageTag = ep.imageTags?.get(ImageType.PRIMARY)
    val imageUrl = remember(ep.id, imageTag) { App.server.imageUrl(ep, ImageType.PRIMARY, 320) }
    val imageRequest = rememberImageRequest(imageUrl)
    val title = remember(ep.id, ep.indexNumber, ep.name) {
        val number = ep.indexNumber
        listOfNotNull(if (number != null) "$number." else null, ep.name).joinToString(" ")
    }
    val runtimeText = remember(ep.runTimeTicks) {
        ep.runTimeTicks?.let { formatMs(it / TICKS_PER_MS) }
    }
    val itemMenu = @Composable {
        ItemMenu(
            ep,
            state?.played == true,
            unsynced,
            onDismiss = { menu = false },
            downloaded = resolvedDownload != null,
            onStartOver = onStartOver,
            onRequestDelete = { confirmDelete = true },
            onRequestInfo = { showInfo = true },
            onChanged = onChanged,
        )
    }
    Box {
        Row(
            Modifier.fillMaxWidth()
                .background(if (highlighted) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
                .focusMemory(ep.id)
                .onSecondaryClick(openMenuAt)
                .combinedClickable(onClick = onPlay, onLongClickLabel = "Actions", onLongClick = openMenu)
                .padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(Modifier.width(140.dp)) {
                AsyncImage(imageRequest, null, contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9).background(Color.DarkGray))
                ProgressBar(ep, state, Modifier.align(Alignment.BottomStart))
            }
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                val dl = resolvedDownload
                val bits = listOfNotNull(
                    runtimeText,
                    if (state?.played == true) "✓ played" else null,
                    dl?.label,
                    if (unsynced) "not synced" else null,
                )
                Text(bits.joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Box {
                IconButton(onClick = openMenu) { GlyphIcon(AppIcons.MORE_VERT, "More options") }
                if (menu && menuAt == null) itemMenu()
            }
        }
        if (menu && menuAt != null) MenuAt(menuAt) { itemMenu() }
    }
    if (confirmDelete) ConfirmDelete(ep, { confirmDelete = false }, onChanged)
    if (showInfo) ItemInfoDialog(ep) { showInfo = false }
}

@Composable
private fun ProgressBar(item: BaseItemDto, state: PlayState?, modifier: Modifier = Modifier) {
    val runtime = item.runTimeTicks ?: 0
    if (state != null && state.positionTicks > 0 && runtime > 0) {
        LinearProgressIndicator({ state.positionTicks.toFloat() / runtime }, modifier.fillMaxWidth())
    }
}

private fun formatRuntime(ticks: Long): String {
    val totalMin = (ticks / TICKS_PER_MS / 60_000).coerceAtLeast(1)
    val hours = totalMin / 60
    val mins = totalMin % 60
    return when {
        hours > 0 && mins > 0 -> "${hours}h ${mins}m"
        hours > 0 -> "${hours}h"
        else -> "${mins}m"
    }
}

@Composable
private fun ItemInfoDialog(item: BaseItemDto, onDismiss: () -> Unit) {
    val itemId = remember(item.id) { item.id.toString() }
    val cachedFull = remember(itemId) {
        if (item.overview != null) item else AppCache.getCachedItem(itemId)?.takeIf { it.overview != null }
    }
    var full by remember(item.id) { mutableStateOf(cachedFull) }
    var loading by remember(item.id) { mutableStateOf(cachedFull == null) }
    LaunchedEffect(item.id) {
        if (full == null) {
            full = runCatching { AppCache.fetchOrJoinItem(itemId, flushFirst = false) }.getOrNull() ?: item
            loading = false
        }
    }
    val shown = full ?: item
    val subtitle = listOfNotNull(
        shown.productionYear?.toString(),
        shown.runTimeTicks?.takeIf { it > 0 }?.let(::formatRuntime),
    ).joinToString(" · ")
    val title = if (shown.type == BaseItemKind.EPISODE) {
        val series = shown.seriesName
        if (series.isNullOrBlank()) episodeLabel(shown) else "$series · ${episodeLabel(shown)}"
    } else {
        shown.name.orEmpty()
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (subtitle.isNotEmpty()) {
                    Text(subtitle, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (loading) {
                    Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                    }
                } else {
                    val overview = shown.overview?.trim().orEmpty()
                    Text(
                        overview.ifEmpty { "No description available" },
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (overview.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant else Color.Unspecified,
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
private fun ItemMenu(
    item: BaseItemDto,
    played: Boolean,
    unsynced: Boolean,
    onDismiss: () -> Unit,
    downloaded: Boolean,
    onStartOver: (() -> Unit)?,
    onRequestDelete: () -> Unit,
    onRequestInfo: () -> Unit,
    onChanged: () -> Unit,
) {
    val id = remember(item.id) { item.id.toString() }
    val scope = rememberCoroutineScope()
    DropdownMenu(true, onDismiss) {
        if (onStartOver != null) {
            DropdownMenuItem({ Text("Start over") }, {
                onDismiss()
                onStartOver()
            })
        }
        DropdownMenuItem({ Text(if (played) "Mark unplayed" else "Mark played") }, {
            onDismiss()
            scope.launch {
                App.server.setPlayed(listOf(id), !played)
                onChanged()
            }
        })
        DropdownMenuItem({ Text("Info") }, {
            onDismiss()
            onRequestInfo()
        })
        if (App.downloads != null && downloaded) {
            DropdownMenuItem({ Text("Delete download") }, {
                onDismiss()
                onRequestDelete()
            })
        } else if (App.downloads != null) DropdownMenuItem({ Text("Download") }, {
            onDismiss()
            scope.launch { downloadAll(listOf(item)) }
        })
        if (unsynced) {
            HorizontalDivider()
            DropdownMenuItem({ Text("Progress not synced yet") }, {}, enabled = false)
            DropdownMenuItem({ Text("Sync now") }, {
                onDismiss()
                scope.launch {
                    val failure = App.server.flush()
                    toast(if (failure != null) loadError(failure) else "Synced")
                    onChanged()
                }
            })
        }
    }
}

@Composable
private fun DownloadRow(
    group: String,
    status: DownloadStatus,
    media: Downloads,
    push: (Screen) -> Unit,
) {
    val d = status.download
    val id = remember(d.item.id) { d.item.id.toString() }
    val local by remember(d.item) { derivedStateOf { d.item.localState() } }
    val (state, unsynced) = local
    val scope = rememberCoroutineScope()
    var menu by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var showInfo by remember { mutableStateOf(false) }
    var menuAt by remember { mutableStateOf<Offset?>(null) }
    val openMenu = remember { { menuAt = null; menu = true } }
    val openMenuAt = remember { { at: Offset -> menuAt = at; menu = true } }
    val rowTitle = remember(group, d.item) {
        if (group == "h-queue" && d.item.type == BaseItemKind.EPISODE && !d.item.seriesName.isNullOrBlank()) {
            "${d.item.seriesName} · ${episodeLabel(d.item)}"
        } else {
            itemLabel(d.item)
        }
    }
    val itemMenu = @Composable {
        ItemMenu(
            d.item,
            state?.played == true,
            unsynced,
            onDismiss = { menu = false },
            downloaded = true,
            onStartOver = { scope.launch { startOver(d.item, push) } },
            onRequestDelete = { confirmDelete = true },
            onRequestInfo = { showInfo = true },
            onChanged = {},
        )
    }
    Box {
        Row(
            Modifier
                .fillMaxWidth()
                .focusMemory(id)
                .onSecondaryClick(openMenuAt)
                .combinedClickable(onClick = { push(Screen.Play(id)) }, onLongClickLabel = "Actions", onLongClick = openMenu)
                .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(rowTitle)
                val size = when {
                    status.fill != Fill.DONE -> if (status.totalBytes > 0) {
                        " · ${formatBytes(status.bytes)} / ${formatBytes(status.totalBytes)}"
                    } else {
                        " · ${formatBytes(status.bytes)}"
                    }
                    status.bytes > 0 -> " · ${formatBytes(status.bytes)}"
                    else -> ""
                }
                val bits = listOfNotNull(
                    status.label + size,
                    if (d.auto) "kept by keep-next" else null,
                    if (state?.played == true) "✓ played" else null,
                    if (unsynced) "not synced" else null,
                )
                Text(bits.joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (status.fill != Fill.DONE) LinearProgressIndicator({ status.percent / 100f }, Modifier.fillMaxWidth().padding(top = 4.dp))
                else ProgressBar(d.item, state)
            }
            when (status.fill) {
                Fill.RUNNING, Fill.QUEUED -> TextButton(onClick = {
                    scope.launch(Dispatchers.IO) { media.pauseDownload(id) }
                }) { Text("Pause") }
                Fill.PAUSED -> TextButton(onClick = {
                    scope.launch(Dispatchers.IO) {
                        val fresh = runCatching { AppCache.fetchOrJoinItem(id, flushFirst = false) }.getOrDefault(d.item)
                        media.startDownload(fresh, d.auto)
                    }
                }) { Text(if (d.failed) "Retry" else "Resume") }
                Fill.DONE -> {}
            }
            Box {
                IconButton(onClick = openMenu) { GlyphIcon(AppIcons.MORE_VERT, "More options") }
                if (menu && menuAt == null) itemMenu()
            }
        }
        if (menu && menuAt != null) MenuAt(menuAt) { itemMenu() }
    }
    if (confirmDelete) ConfirmDelete(d.item, { confirmDelete = false })
    if (showInfo) ItemInfoDialog(d.item) { showInfo = false }
}

@Composable
fun DownloadsScreen(modifier: Modifier, push: (Screen) -> Unit, pop: () -> Unit) {
    val media = App.downloads!!
    val statuses = rememberDownloadStatuses()
    val queueItems = remember(statuses) {
        statuses.orEmpty().values
            .filter { status -> status.fill != Fill.DONE }
            .sortedWith(compareBy(queueOrder) { status -> status.download })
    }
    val doneGroups = remember(statuses) {
        statuses.orEmpty().values
            .filter { status -> status.fill == Fill.DONE }
            .sortedWith(compareBy(shelfOrder) { status -> status.download })
            .groupBy { status ->
                val seriesId = status.download.item.seriesId
                if (seriesId != null) "h-$seriesId" else "h-movies"
            }
    }
    val storage by produceState("", statuses) {
        val (buffered, free, others) = withContext(Dispatchers.IO) {
            Triple(media.bufferedBytes(), media.freeBytes(), media.otherAccountsBytes())
        }
        val downloaded = statuses.orEmpty().values.sumOf { status -> status.bytes }
        var text = "Downloads ${formatBytes(downloaded)}"
        if (others > 0) text += " · other accounts ${formatBytes(others)}"
        text += " · buffer ${formatBytes(buffered)} of ${App.prefs.cacheLimitGb} GB · ${formatBytes(free)} free"
        value = text
    }
    val scope = rememberCoroutineScope()
    var deletingSeries by remember { mutableStateOf<Pair<String, List<BaseItemDto>>?>(null) }
    val delSeries = deletingSeries
    if (delSeries != null) {
        val (seriesTitle, seriesItems) = delSeries
        Confirm(
            "Delete all ${seriesItems.size} downloaded episodes of $seriesTitle?",
            "Delete",
            onConfirm = {
                scope.launch(Dispatchers.IO) {
                    for (item in seriesItems) media.deleteDownload(item.id.toString())
                }
            },
            onDismiss = { deletingSeries = null },
        )
    }
    Column(modifier.fillMaxSize()) {
        Bar("Downloads", pop)
        Text(storage, Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (media.storageMissing) Text("SD card not available. Downloads wait for it; restart the app after inserting it.",
            Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.error)
        if (statuses != null && queueItems.isEmpty() && doneGroups.isEmpty()) Empty("Nothing downloaded. Use ⋮ on an episode or a series, or long-press a movie.")
        else LazyColumn {
            if (queueItems.isNotEmpty()) {
                item(key = "h-queue") {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Queue", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                        if (queueItems.size >= 2) {
                            val anyActive = queueItems.any { it.fill == Fill.RUNNING || it.fill == Fill.QUEUED }
                            if (anyActive) {
                                TextButton(onClick = {
                                    scope.launch(Dispatchers.IO) {
                                        val ordered = queueItems.filter { it.fill == Fill.QUEUED } + queueItems.filter { it.fill == Fill.RUNNING }
                                        for (s in ordered) media.pauseDownload(s.download.item.id.toString())
                                    }
                                }) { Text("Pause all") }
                            } else {
                                TextButton(onClick = {
                                    scope.launch(Dispatchers.IO) {
                                        for (s in queueItems) media.startDownload(s.download.item, s.download.auto)
                                    }
                                }) { Text("Resume all") }
                            }
                        }
                    }
                }
                items(queueItems, key = { it.download.item.id }) { status -> DownloadRow("h-queue", status, media, push) }
            }
            for ((group, groupItems) in doneGroups) {
                val first = groupItems.first().download.item
                val seriesId = first.seriesId
                val title = if (seriesId != null) first.seriesName.orEmpty() else "Movies"
                item(key = group) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            title,
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier
                                .weight(1f)
                                .then(if (seriesId != null) Modifier.clickable { push(Screen.Series(seriesId.toString(), title)) } else Modifier),
                        )
                        if (seriesId != null && groupItems.size >= 2) {
                            TextButton(onClick = { deletingSeries = Pair(title, groupItems.map { it.download.item }) }) {
                                Text("Delete all")
                            }
                        }
                    }
                }
                items(groupItems, key = { it.download.item.id }) { status -> DownloadRow(group, status, media, push) }
            }
        }
    }
}

@Composable
fun SettingsScreen(modifier: Modifier, pop: () -> Unit, onAccounts: () -> Unit, onLoggedOut: () -> Unit) {
    val p = App.prefs
    var refresh by remember { mutableIntStateOf(0) }
    var bufferRefresh by remember { mutableIntStateOf(0) }
    var clearingBuffer by remember { mutableStateOf(false) }
    val bufferUsed by produceState<Long?>(null, bufferRefresh) {
        if (!App.platform.android && App.downloads == null) {
            value = withContext(Dispatchers.IO) { App.platform.bufferBytes() }
        }
    }
    @Composable
    fun toggle(label: String, value: Boolean, enabled: Boolean = true, set: (Boolean) -> Unit) = Row(
        Modifier
            .fillMaxWidth()
            .toggleable(value, enabled = enabled, role = Role.Switch) { checked ->
                set(checked)
                refresh++
            }
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, Modifier.weight(1f), color = if (enabled) Color.Unspecified else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f))
        Switch(value, null, enabled = enabled)
    }
    val scope = rememberCoroutineScope()
    var moveTo by remember { mutableStateOf<Int?>(null) }
    var moving by remember { mutableStateOf(false) }
    val target = moveTo
    if (target != null) {
        val count by produceState<Int?>(null, target) {
            value = withContext(Dispatchers.IO) { App.downloads?.downloads()?.size ?: 0 }
        }
        val n = count ?: 0
        val where = if (target > 0) "the SD card" else "internal storage"
        val plural = if (n == 1) "" else "s"
        val deletes = if (n == 0) "" else "deletes your $n download$plural and "
        Confirm("Store on $where? This " + deletes + "clears the buffer cache; nothing is moved.", "Switch", {
            moving = true
            scope.launch {
                withContext(Dispatchers.IO) { App.downloads?.switchStorage(target) }
                moving = false
                refresh++
            }
        }, { moveTo = null })
    }
    var confirmLogout by remember { mutableStateOf(false) }
    if (confirmLogout) {
        val unsynced by produceState(0) {
            value = withContext(Dispatchers.IO) { App.pending.all().size }
        }
        val itemsHave = if (unsynced == 1) "item has" else "items have"
        val note = if (unsynced == 0) "" else " $unsynced $itemsHave progress not synced yet. It is sent after you log back in to the same server."
        Confirm("Log out?$note", "Log out", {
            App.server.logout()
            onLoggedOut()
        }, { confirmLogout = false })
    }
    val decoders by produceState<String?>(null) {
        value = withContext(Dispatchers.IO) { App.platform.decoderSummary() }
    }
    val hasRemovableStorage by produceState(false) {
        val d = App.downloads
        value = if (d != null) withContext(Dispatchers.IO) { d.storageRoots().size > 1 } else false
    }
    @Suppress("UNUSED_VARIABLE")
    val tick = refresh
    Column(modifier.fillMaxSize()) {
        Bar("Settings", pop)
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .wrapContentWidth(Alignment.Start)
                .widthIn(max = 720.dp)
        ) {
            val android = App.platform.android
            val downloads = App.downloads
            val storageHeader = when {
                android -> "Network and storage"
                downloads == null -> "Cache"
                else -> "Network"
            }
            Text(storageHeader, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(16.dp))
            if (android) toggle("Buffer to end on metered networks", p.bufferOnMetered) { p.bufferOnMetered = it }
            if (android && downloads != null) toggle("Download on metered networks", p.downloadOnMetered) { on ->
                p.downloadOnMetered = on
                downloads.kickDownloads(restart = true)
            }
            Stepper("Buffer cache limit (GB)", p.cacheLimitGb, listOf(1, 2, 5, 10, 20, 50, 100)) { gb ->
                p.cacheLimitGb = gb
                refresh++
                scope.launch {
                    withContext(Dispatchers.IO) { App.platform.evictBuffer() }
                    bufferRefresh++
                }
            }
            if (!android && downloads == null) {
                val used = bufferUsed
                Row(
                    Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        if (used != null) "Buffer used: ${formatBytes(used)}" else "Buffer used: …",
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(
                        enabled = !clearingBuffer && (used == null || used > 0),
                        onClick = {
                            clearingBuffer = true
                            scope.launch {
                                withContext(Dispatchers.IO) { App.platform.clearBuffer() }
                                clearingBuffer = false
                                bufferRefresh++
                            }
                        },
                    ) { Text("Clear buffer") }
                }
            }
            if (downloads != null && (hasRemovableStorage || downloads.storageMissing)) {
                toggle("Store on SD card (switching deletes downloads and the buffer)", p.storageIndex > 0, enabled = !moving) { on ->
                    moveTo = if (on) 1 else 0
                }
            }

            Text("Playback", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(16.dp))
            val audioDelayLabel = if (android) "Wireless audio delay (ms), for Bluetooth headphones and speakers" else "Audio delay (ms)"
            Stepper(audioDelayLabel, p.audioDelayMs, AUDIO_DELAYS_MS) { ms ->
                App.platform.setAudioDelay(ms)
                refresh++
            }
            Stepper("Skip step (s)", p.doubleTapSeconds, (5..60 step 5).toList()) { seconds ->
                p.doubleTapSeconds = seconds
                refresh++
            }
            if (decoders != null) {
                Text(
                    "Hardware decoders: $decoders",
                    Modifier.padding(horizontal = 16.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text("Direct play up to", Modifier.padding(16.dp, 8.dp, 16.dp, 0.dp))
            if (!android) {
                Text(
                    "Transcodes taller videos to save bandwidth",
                    Modifier.padding(horizontal = 16.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val autoLabel = if (android) "Auto" else "No limit"
                listOf(Pair(0, autoLabel), Pair(2160, "4K"), Pair(1080, "1080p"), Pair(720, "720p")).forEach { (h, label) ->
                    FilterChip(p.maxHeight == h, {
                        p.maxHeight = h
                        refresh++
                    }, { Text(label) })
                }
            }
            if (android) Text("Direct play codecs", Modifier.padding(16.dp, 8.dp, 16.dp, 0.dp))
            if (android) Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Auto", "H.264 only", "All").forEachIndexed { i, label ->
                    FilterChip(p.codecs == i, {
                        p.codecs = i
                        refresh++
                    }, { Text(label) })
                }
            }
            val learned = if (android) p.learnedCodecs() else emptyList()
            if (learned.isNotEmpty()) {
                val codecs = learned.joinToString { codec -> codec.uppercase() }
                Text(
                    "Transcoding sooner for $codecs after choppy playback",
                    Modifier.padding(16.dp, 8.dp, 16.dp, 0.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = {
                    p.forgetLearned()
                    refresh++
                }, Modifier.padding(horizontal = 8.dp)) { Text("Forget") }
            }

            if (android) Text("Interface", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(16.dp))
            if (android) Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val autoLabel = if (App.autoTv) "Auto (TV)" else "Auto (touch)"
                listOf(autoLabel, "Touch", "TV").forEachIndexed { i, label ->
                    FilterChip(p.interfaceMode == i, {
                        p.interfaceMode = i
                        refresh++
                    }, { Text(label) })
                }
            }

            if (android && !App.tv) {
                Text("Gestures", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(16.dp))
                toggle("Brightness / volume swipes", p.gestureLevels) { p.gestureLevels = it }
                toggle("Brightness on the left (volume on the right)", p.brightnessOnLeft, enabled = p.gestureLevels) { p.brightnessOnLeft = it }
                toggle("Horizontal swipe seeks", p.gestureSeek) { p.gestureSeek = it }
                toggle("Double-tap skips", p.gestureDoubleTap) { p.gestureDoubleTap = it }
                toggle("Hold for 2× speed", p.gestureHold) { p.gestureHold = it }
                toggle("Pinch to crop / fit", p.gesturePinch) { p.gesturePinch = it }
                toggle("Swipe up / down for fullscreen", p.gestureFullscreen) { p.gestureFullscreen = it }
            }

            Text("Account", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(16.dp))
            val me = p.accounts().firstOrNull { account -> account.userId == p.userId }
            val myName = me?.name
            val shownName = if (myName.isNullOrEmpty()) null else myName
            Text(listOfNotNull(shownName, p.baseUrl).joinToString(" · "), Modifier.padding(horizontal = 16.dp))
            Row(Modifier.padding(horizontal = 8.dp)) {
                TextButton(onClick = onAccounts) { Text("Accounts and PIN") }
                TextButton(onClick = { confirmLogout = true }) { Text("Log out") }
            }
            Text(
                "finimal ${App.platform.versionName}",
                Modifier.padding(16.dp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.padding(24.dp))
        }
    }
}

private fun Modifier.screenShortcuts(
    onRefresh: () -> Unit,
    onSearch: (() -> Unit)? = null,
    searchFocused: Boolean = false,
    focusOnShow: Boolean = true,
): Modifier = composed {
    val latestRefresh by rememberUpdatedState(onRefresh)
    val latestSearch by rememberUpdatedState(onSearch)
    val latestSearchFocused by rememberUpdatedState(searchFocused)
    val latestFocusOnShow by rememberUpdatedState(focusOnShow)
    val focusModifier = if (!App.platform.android) {
        val requester = remember { FocusRequester() }
        val shown = LocalShown.current
        LaunchedEffect(Unit) {
            snapshotFlow { shown.value }.collect { visible ->
                if (visible && latestFocusOnShow) runCatching { requester.requestFocus() }
            }
        }
        Modifier.focusRequester(requester).focusable()
    } else {
        Modifier
    }
    this.onPreviewKeyEvent { e ->
        if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
        val ctrl = e.isCtrlPressed || e.isMetaPressed
        val noMods = !e.isCtrlPressed && !e.isMetaPressed && !e.isAltPressed
        when {
            e.key == Key.F5 || (ctrl && e.key == Key.R) -> {
                latestRefresh()
                true
            }
            latestSearch != null && ctrl && e.key == Key.F -> {
                latestSearch?.invoke()
                true
            }
            latestSearch != null && !latestSearchFocused && noMods && !e.isShiftPressed && e.key == Key.Slash -> {
                latestSearch?.invoke()
                true
            }
            else -> false
        }
    }.then(focusModifier)
}

private fun Modifier.onSecondaryClick(action: ((Offset) -> Unit)?): Modifier = if (action == null) this else pointerInput(action) {
    awaitPointerEventScope {
        while (true) {
            val e = awaitPointerEvent()
            if (e.type == PointerEventType.Press && e.buttons.isSecondaryPressed) {
                e.changes.forEach { change -> change.consume() }
                action(e.changes.first().position)
            }
        }
    }
}

@Composable
private fun MenuAt(at: Offset?, menu: @Composable () -> Unit) {
    if (at == null) menu() else Box(Modifier.offset { at.round() }) { menu() }
}

@Composable
private fun Stepper(
    label: String,
    value: Int,
    options: List<Int>,
    show: (Int) -> String = Int::toString,
    modifier: Modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
    set: (Int) -> Unit,
) =
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        val i = options.indices.minBy { index -> abs(options[index] - value) }
        Text(label, Modifier.weight(1f))
        TextButton(onClick = { set(options[i - 1]) }, enabled = i > 0) {
            Text("−", Modifier.semantics { contentDescription = "Decrease $label, now ${show(options[i])}" })
        }
        Text(show(options[i]), Modifier.widthIn(min = 48.dp), textAlign = TextAlign.Center, maxLines = 1)
        TextButton(onClick = { set(options[i + 1]) }, enabled = i < options.lastIndex) {
            Text("+", Modifier.semantics { contentDescription = "Increase $label, now ${show(options[i])}" })
        }
    }

/** Draws chapter starts as marks under a seek bar laid over them. The bar's ends sit [inset] in from its sides. */
@Composable
fun ChapterMarks(chapters: List<Chapter>, durationMs: Long, modifier: Modifier, inset: androidx.compose.ui.unit.Dp = 10.dp) =
    androidx.compose.foundation.Canvas(modifier) {
        if (durationMs <= 0) return@Canvas
        val left = inset.toPx()
        val half = 5.dp.toPx()
        for (chapter in chapters) {
            if (chapter.startMs < 1 || chapter.startMs >= durationMs) continue
            val x = left + (size.width - 2 * left) * chapter.startMs / durationMs
            val top = androidx.compose.ui.geometry.Offset(x, center.y - half)
            val bottom = androidx.compose.ui.geometry.Offset(x, center.y + half)
            drawLine(Color.White, top, bottom, 2.dp.toPx())
        }
    }

@Composable
private fun Avatar(name: String, modifier: Modifier = Modifier) {
    val halfSp = with(androidx.compose.ui.platform.LocalDensity.current) { 16.dp.toSp() }
    Box(modifier.size(32.dp), contentAlignment = Alignment.Center) {
        androidx.compose.foundation.text.BasicText(
            text = AppIcons.CIRCLE_2X2,
            style = androidx.compose.ui.text.TextStyle(
                color = MaterialTheme.colorScheme.primaryContainer,
                fontSize = halfSp,
                lineHeight = halfSp,
                fontFamily = iconFontFamily(),
            ),
            maxLines = 2,
            softWrap = false,
            modifier = Modifier.clearAndSetSemantics {},
        )
        Text(name.firstOrNull()?.uppercase() ?: "?", color = MaterialTheme.colorScheme.onPrimaryContainer, style = MaterialTheme.typography.titleSmall)
    }
}

/**
 * The accounts saved on this device, all on one server. Picking one switches to it, after its PIN when it has one. As
 * the first screen ([pop] null) it is "Who's watching?". The current account sets or removes its own PIN here.
 */
@Composable
fun AccountsScreen(
    modifier: Modifier,
    pop: (() -> Unit)?,
    onSwitched: () -> Unit,
    onAdd: () -> Unit,
    onForgotPin: (String) -> Unit = {},
) {
    val p = App.prefs
    var refresh by remember { mutableIntStateOf(0) }
    val accounts = remember(refresh) { p.accounts() }
    var asking by remember { mutableStateOf<Account?>(null) }
    var settingPin by remember { mutableStateOf(false) }
    var confirmPin by remember { mutableStateOf<String?>(null) }
    val picking = pop == null
    fun open(a: Account) {
        if (a.userId != p.userId) App.server.switchTo(a)
        onSwitched()
    }
    val askingFor = asking
    if (askingFor != null) {
        PinDialog(
            "PIN for ${askingFor.name.ifEmpty { "this account" }}",
            onDismiss = { asking = null },
            onForgot = { onForgotPin(askingFor.name) },
        ) { pin ->
            val matches = p.pinMatches(askingFor, pin)
            if (matches) {
                asking = null
                open(askingFor)
            }
            matches
        }
    }
    if (settingPin) {
        val firstPin = confirmPin
        key(firstPin != null) {
            if (firstPin == null) {
                PinDialog("New PIN", onDismiss = { settingPin = false; confirmPin = null }) { pin ->
                    confirmPin = pin
                    true
                }
            } else {
                PinDialog(
                    "Confirm PIN",
                    onDismiss = { settingPin = false; confirmPin = null },
                    wrongText = "PINs don't match",
                ) { pin ->
                    if (pin == firstPin) {
                        p.setPin(p.userId!!, pin)
                        settingPin = false
                        confirmPin = null
                        refresh++
                        true
                    } else {
                        false
                    }
                }
            }
        }
    }
    Column(modifier.fillMaxSize()) {
        Bar(if (picking) "Who's watching?" else "Accounts", pop)
        Column(Modifier.widthIn(max = 720.dp).verticalScroll(rememberScrollState())) {
            accounts.forEach { a ->
                val current = a.userId == p.userId && !picking
                Row(
                    Modifier
                        .fillMaxWidth()
                        .focusMemory(a.userId)
                        .clickable {
                            if (a.pin != null && !current) asking = a else open(a)
                        }
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Avatar(a.name)
                    val suffix = if (current) " (current)" else ""
                    Text(a.name.ifEmpty { "Account" } + suffix, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    if (a.pin != null) Text("PIN", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            TextButton(onClick = onAdd, Modifier.padding(horizontal = 8.dp)) { Text("Add account") }
            if (!picking) {
                val me = accounts.firstOrNull { account -> account.userId == p.userId }
                HorizontalDivider()
                Text(
                    "Your PIN is asked when switching to your account. It keeps your titles and history from others on this device.",
                    Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(Modifier.padding(horizontal = 8.dp)) {
                    TextButton(onClick = { settingPin = true; confirmPin = null }) { Text(if (me?.pin == null) "Set PIN" else "Change PIN") }
                    if (me?.pin != null) {
                        TextButton(onClick = {
                            p.setPin(me.userId, null)
                            refresh++
                        }) { Text("Remove PIN") }
                    }
                }
            }
        }
    }
}

@Composable
private fun PinDialog(
    title: String,
    onDismiss: () -> Unit,
    wrongText: String = "Wrong PIN",
    onForgot: (() -> Unit)? = null,
    onPin: (String) -> Boolean,
) {
    var pin by remember { mutableStateOf("") }
    var wrong by remember { mutableStateOf(false) }
    val ok = pin.length in 4..8
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    fun submit() {
        if (!onPin(pin)) {
            wrong = true
            pin = ""
            runCatching { focus.requestFocus() }
        }
    }
    AlertDialog(onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    pin,
                    { text ->
                        pin = text.filter(Char::isDigit).take(8)
                        wrong = false
                    },
                    singleLine = true,
                    isError = wrong,
                    label = { Text("PIN") },
                    supportingText = { Text(if (wrong) wrongText else "4 to 8 digits") },
                    modifier = Modifier.focusRequester(focus),
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { if (ok) submit() }),
                )
                if (onForgot != null) {
                    TextButton(onClick = {
                        onDismiss()
                        onForgot()
                    }) { Text("Forgot PIN?") }
                }
            }
        },
        confirmButton = { TextButton(onClick = ::submit, enabled = ok) { Text("OK") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}
