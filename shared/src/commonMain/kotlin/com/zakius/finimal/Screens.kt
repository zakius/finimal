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
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.ui.text.input.VisualTransformation
import org.jellyfin.sdk.model.api.LocationType
import finimal.core.EpisodeRef
import finimal.core.keepNextWindow
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.Icons
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
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collectLatest
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

/** The last good list per server, user and list, so a list you come back to shows at once while it reloads. Main thread only. */
private val lastGood = object : LinkedHashMap<List<Any?>, List<BaseItemDto>>(16, 0.75f, true) {
    // Every search query and sort is its own entry, so keep only the most recent ones.
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<List<Any?>, List<BaseItemDto>>) = size > 20
}

/**
 * The same lists on disk, so a cold start shows the last list at once instead of "Loading…".
 * For display only: pending local play state is drawn over it, and a fresh fetch replaces it.
 */
object ListSnapshots {
    private val json = Json { ignoreUnknownKeys = true }
    private val dir get() = File(App.platform.appCache, "lists")
    private fun file(key: List<Any?>): File {
        val parent = dir
        val digest = MessageDigest.getInstance("SHA-1").digest(key.joinToString("\u0000").toByteArray())
        val hex = digest.joinToString("") { byte -> "%02x".format(byte) }
        return File(parent, "$hex.json")
    }

    fun read(key: List<Any?>): List<BaseItemDto>? = runCatching { json.decodeFromString<List<BaseItemDto>>(file(key).readText()) }.getOrNull()

    fun write(key: List<Any?>, list: List<BaseItemDto>) {
        runCatching {
            dir.mkdirs()
            file(key).writeAtomically(json.encodeToString(list))
        }
    }

    fun clear() { dir.deleteRecursively() }

    /** Reads Libraries, the usual first screen, off the main thread at app start, so the first frame can show it. */
    fun prefetchLibraries() {
        val key = listOf(App.prefs.baseUrl, App.prefs.userId)
        val list = read(key)
        if (list != null) prefetched = Pair(key, list)
    }
    @Volatile private var prefetched: Pair<List<Any?>, List<BaseItemDto>>? = null

    fun takePrefetched(key: List<Any?>): List<BaseItemDto>? {
        val found = prefetched
        if (found == null || found.first != key) return null
        prefetched = null
        return found.second
    }
}

/** The item last focused with the D-pad on this screen, by key, so coming back to the screen puts focus on it again. */
private val LocalFocusedKey = compositionLocalOf<MutableState<String?>> { mutableStateOf(null) }

@Composable
fun FocusMemory(content: @Composable () -> Unit) {
    val focusedKey = rememberSaveable { mutableStateOf<String?>(null) }
    CompositionLocalProvider(LocalFocusedKey provides focusedKey, content = content)
}

/** Put before the item's clickable. Records D-pad focus under [key] and takes it back when the item shows again. Touch never focuses. */
private fun Modifier.focusMemory(key: Any): Modifier = composed {
    val memory = LocalFocusedKey.current
    val requester = remember { FocusRequester() }
    val k = key.toString()
    val shown = LocalShown.current // Root clears focus while another screen covers this one.
    LaunchedEffect(Unit) {
        snapshotFlow { shown.value }.collect { visible ->
            if (visible && memory.value == k) requester.requestFocus()
        }
    }
    focusRequester(requester).onFocusChanged { focusState -> if (focusState.isFocused) memory.value = k }
}

/** A list, plus the refresh error when the list shown is an older copy because the refresh failed. */
class Load(val result: Result<List<BaseItemDto>>?, val refreshError: Throwable?)

/**
 * Loads [block] after pushing unsynced progress, so the list never shows state older than this device's.
 * Loads again each time the screen comes back on top. Until the load ends, the list from the last visit
 * shows (from memory, else from disk when [persist]), with unsynced local state drawn over it. A failed load
 * keeps that list and says why below it.
 */
@Composable
private fun loadFresh(vararg keys: Any?, refresh: Int, persist: Boolean = true, block: suspend () -> List<BaseItemDto>): Load {
    val k = keys.toList()
    val cacheKey = listOf(App.prefs.baseUrl, App.prefs.userId) + k
    var last by remember {
        val cached = lastGood[cacheKey] ?: ListSnapshots.takePrefetched(cacheKey)
        val initial = if (cached != null) Pair(k, Result.success(cached)) else null
        mutableStateOf(initial)
    }
    var refreshError by remember { mutableStateOf<Throwable?>(null) }
    val shown = LocalShown.current
    LaunchedEffect(k, refresh) {
        snapshotFlow { shown.value }.collectLatest { visible ->
            if (visible) coroutineScope {
                refreshError = null
                if (last?.second?.isFailure == true) last = null // A retry shows "Loading…" again.
                if (last?.first != k) {
                    var cached = lastGood[cacheKey]
                    if (cached == null && persist) cached = withContext(Dispatchers.IO) { ListSnapshots.read(cacheKey) }
                    if (cached != null) last = Pair(k, Result.success(cached))
                }
                val pushed = App.server.flush()
                // If the flush found the server unreachable, the fetch would only time out a second time.
                val result = if (pushed != null && unreachable(pushed)) {
                    Result.failure(pushed)
                } else {
                    runCatching { block() }.onFailure { e ->
                        if (e is InvalidStatusException && e.status == 401) App.server.expire()
                    }
                }
                ensureActive() // If the screen was covered meanwhile, this isn't a failure; the next visit loads again.
                val prev = last
                when {
                    result.isSuccess -> {
                        val list = result.getOrThrow()
                        lastGood[cacheKey] = list
                        if (persist) launch(Dispatchers.IO) { ListSnapshots.write(cacheKey, list) }
                        last = Pair(k, result)
                    }
                    prev != null && prev.first == k && prev.second.isSuccess -> refreshError = result.exceptionOrNull()
                    else -> last = Pair(k, result)
                }
            }
        }
    }
    return Load(last?.second, refreshError)
}

/**
 * Download statuses while the screen is shown, null until first read. Re-read on every download
 * change, and polled every second while one runs, else every 5 s. Polling stops while another screen covers this one.
 */
@Composable
fun rememberDownloadStatuses(): Map<String, DownloadStatus>? {
    val downloads = App.downloads ?: return null
    val shown = LocalShown.current
    return produceState<Map<String, DownloadStatus>?>(null, downloads.changes) {
        snapshotFlow { shown.value }.collectLatest { visible ->
            while (visible) {
                value = withContext(Dispatchers.IO) { downloads.downloadStatuses() }
                val running = value.orEmpty().values.any { status -> status.fill == Fill.RUNNING }
                delay(if (running) 1_000 else 5_000)
            }
        }
    }.value
}

/** Queues downloads and says how many were queued. */
private suspend fun downloadAll(episodes: List<BaseItemDto>) {
    val downloads = App.downloads ?: return
    val noun = if (episodes.all { it.type == BaseItemKind.MOVIE }) "movie" else "episode"
    var queued = 0
    val failures = mutableListOf<Throwable>()
    withContext(Dispatchers.IO) {
        for (ep in episodes) {
            if (downloads.download(ep.id.toString()) != null) continue
            // Episodes already fetched with sources queue as purely local writes, so leaving the screen can't stop it partway.
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

/**
 * An action that fetches episodes and asks before queuing the ones not downloaded or queued yet,
 * with their total size when every file's size is known.
 */
@Composable
private fun downloadAllAction(fetch: suspend () -> List<BaseItemDto>): () -> Unit {
    var busy by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf<List<BaseItemDto>?>(null) }
    val scope = rememberCoroutineScope()
    // If the fetch ends after the screen was covered, the question waits until the screen is back on top.
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

/** A ⋮ button in the top bar, so actions don't push a long title off it. */
@Composable
private fun BarMenu(content: @Composable (close: () -> Unit) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(LocalIcons.current.more, "More options") }
        DropdownMenu(open, { open = false }) { content { open = false } }
    }
}

/** "Mark played" for many episodes at once. Asks first, because it drops their resume points. */
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
    val playable = episodes.filter { it.parentIndexNumber != 0 && it.locationType != LocationType.VIRTUAL }
    val refs = playable.map { ep -> EpisodeRef(ep.id.toString(), ep.localState().first) }
    val window = keepNextWindow(refs, playable.size).toSet()
    return playable.firstOrNull { ep -> ep.id.toString() in window && ep.localState().first?.played != true }
}

/** Plays [item] from the start: the position is saved as 0 first, so nothing can resume it later. */
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

/** Asks before deleting [item]'s download; shows nothing while [item] is null. */
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
        if (onBack != null) IconButton(onClick = onBack) { Icon(LocalIcons.current.back, "Back") }
    },
    actions = { actions() },
)

fun loadError(e: Throwable) = when {
    e is InvalidStatusException -> "The server returned an error (${e.status})"
    untrustedCertificate(e) -> "The server's certificate isn't trusted"
    unreachable(e) -> "Can't reach the server"
    else -> "Something went wrong"
}

/** A plain [sentence], with the [raw] error under it in small text. */
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
        // The note about a failed refresh goes under the list, so the list shrinks from below. A note above it would push
        // down whatever is under the pointer.
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

/** Centred note for a list with nothing in it. */
@Composable
private fun Empty(text: String) = Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) { Text(text) }

@Composable
fun LoginScreen(modifier: Modifier, onBack: (() -> Unit)? = null, onDone: () -> Unit) {
    // Adding an account logs in another user of the same server, so the server fields are locked.
    val adding = onBack != null
    val saved = remember { splitServerAddress(App.prefs.baseUrl) }
    var url by rememberSaveable { mutableStateOf(saved.first) }
    var port by rememberSaveable { mutableStateOf(saved.second) }
    // Typing the server refills the port with defaultPortFor's guess until the user writes their own.
    var portEdited by rememberSaveable { mutableStateOf(false) }
    var user by rememberSaveable { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<Throwable?>(null) }
    var busy by remember { mutableStateOf(false) }
    var showPass by remember { mutableStateOf(false) }
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
    // The padded modifier already includes the IME inset; scrolling keeps the button reachable above it.
    // A phone-wide form on tablets and desktop windows: full-width fields there are hard to read.
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
            if (onBack != null) IconButton(onClick = onBack) { Icon(LocalIcons.current.back, "Back") }
            Text(if (adding) "Add account" else "finimal", style = MaterialTheme.typography.headlineMedium)
        }
        if (App.sessionExpired && !adding) Text("The server ended this session. Log in again; unsynced progress is kept.")
        // The port has its own field, prefilled from the host. A port typed into the server field wins (see serverCandidates).
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
            modifier = Modifier.fillMaxWidth().semantics { contentType = ContentType.Username },
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
            modifier = Modifier.fillMaxWidth().semantics { contentType = ContentType.Password },
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
        Button(enabled = !busy && canSubmit, onClick = ::submit) {
            if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text("Log in")
        }
    }
    }
}

@Composable
fun LibrariesScreen(modifier: Modifier, push: (Screen) -> Unit) {
    var refresh by remember { mutableIntStateOf(0) }
    val libs = loadFresh(refresh = refresh) { App.server.libraries() }
    var me by remember { mutableStateOf(App.prefs.accounts().firstOrNull { it.userId == App.prefs.userId }) }
    // Accounts migrated from before names were stored have an empty name. Fetch it from the server once.
    LaunchedEffect(Unit) {
        val m = me
        if (m == null || m.name.isNotEmpty()) return@LaunchedEffect
        val name = runCatching { App.server.userName() }.getOrNull()
        if (!name.isNullOrEmpty()) {
            App.prefs.setName(m.userId, name)
            me = m.copy(name = name)
        }
    }
    Column(modifier.fillMaxSize()) {
        Bar("finimal", null) {
            TextButton(onClick = { push(Screen.Library(null, "Search")) }) { Text("Search") }
            if (App.downloads != null) TextButton(onClick = { push(Screen.Downloads) }) { Text("Downloads") }
            IconButton(onClick = { push(Screen.Settings) }) { Icon(LocalIcons.current.settings, "Settings") }
            IconButton(onClick = { push(Screen.Accounts) }) { Avatar(me?.name.orEmpty(), Modifier.semantics { contentDescription = "Accounts" }) }
        }
        Loaded(libs, { refresh++ }, { push(Screen.Downloads) }) { list ->
            if (list.isEmpty()) Empty("No movie or TV libraries on this server")
            else LazyColumn {
                items(list, key = { it.id }) { lib ->
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

/** A library's poster grid; with a null id it searches every library. */
@Composable
fun LibraryScreen(modifier: Modifier, screen: Screen.Library, push: (Screen) -> Unit, pop: () -> Unit) {
    var sort by rememberSaveable { mutableStateOf(Sort.NAME) }
    var unplayed by rememberSaveable { mutableStateOf(false) }
    var search by rememberSaveable { mutableStateOf("") }
    var query by rememberSaveable { mutableStateOf("") }
    var refresh by remember { mutableIntStateOf(0) }
    LaunchedEffect(search) {
        delay(400)
        query = search
    }
    val searchOnly = screen.id == null
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    var focusedOnce by rememberSaveable { mutableStateOf(false) } // Don't grab focus again when coming back.
    LaunchedEffect(Unit) {
        if (searchOnly && !focusedOnce) {
            focusedOnce = true
            focus.requestFocus()
            keyboard?.show()
        }
    }
    val items = if (searchOnly && query.isBlank()) {
        Load(Result.success(emptyList()), null)
    } else {
        loadFresh(screen.id, sort, unplayed, query, refresh = refresh, persist = query.isBlank()) { App.server.items(screen.id, sort, unplayed, query) }
    }
    Column(modifier.fillMaxSize()) {
        Bar(screen.name, pop)
        OutlinedTextField(search, { search = it }, label = { Text("Search") }, singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).focusRequester(focus),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = {
                query = search
                keyboard?.hide()
            }))
        if (!searchOnly) Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // The server orders search results by how well they match, whatever the sort.
            Sort.entries.forEach { s -> FilterChip(sort == s, { sort = s }, { Text(s.label) }, enabled = query.isBlank()) }
            FilterChip(unplayed, { unplayed = !unplayed }, { Text("Unplayed") })
        }
        val statuses = rememberDownloadStatuses()
        val scope = rememberCoroutineScope()
        // Outside Loaded, so the scroll survives its "Loading…" pass; a new sort, filter or query starts at the top.
        val gridState = rememberSaveable(sort, unplayed, query, saver = LazyGridState.Saver) { LazyGridState() }
        Loaded(items, { refresh++ }, { push(Screen.Downloads) }) { list ->
            if (list.isEmpty()) Empty(when {
                query.isNotBlank() -> "No results for “${query.trim()}”"
                searchOnly -> "Type to search"
                unplayed -> "Nothing unplayed here"
                else -> "This library is empty"
            })
            else {
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
                        if (item.type == BaseItemKind.SERIES) Poster(item, null, onClick = { push(Screen.Series(item.id.toString(), item.name.orEmpty())) })
                        else Poster(item, statuses?.get(item.id.toString()),
                            onClick = { push(Screen.Play(item.id.toString())) },
                            onStartOver = { scope.launch { startOver(item, push) } },
                            onChanged = { refresh++ })
                    }
                }
            }
        }
    }
}

/** Movies (non-null [onChanged]) also get progress, download state and the long-press menu. */
@Composable
private fun Poster(
    item: BaseItemDto,
    download: DownloadStatus?,
    onClick: () -> Unit,
    onStartOver: (() -> Unit)? = null,
    onChanged: (() -> Unit)? = null,
) {
    // Derived, so a local change recomposes only the row whose state it changed, not every row.
    val local by remember(item) { derivedStateOf { item.localState() } }
    val (state, unsynced) = local
    var menu by remember { mutableStateOf(false) }
    // The focus highlight doesn't show over artwork; a border does.
    var focused by remember { mutableStateOf(false) }
    var openMenu: (() -> Unit)? = null
    if (onChanged != null) openMenu = { menu = true }
    Column(
        Modifier
            .focusMemory(item.id)
            .onFocusChanged { focusState -> focused = focusState.isFocused }
            .onSecondaryClick(openMenu)
            .combinedClickable(onClick = onClick, onLongClickLabel = "Actions", onLongClick = openMenu)
    ) {
        Box(if (focused) Modifier.border(3.dp, MaterialTheme.colorScheme.primary) else Modifier) {
            AsyncImage(App.server.imageUrl(item), null, contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxWidth().aspectRatio(2f / 3).background(Color.DarkGray))
            if (download != null) {
                Text(
                    if (download.fill == Fill.DONE) "↓" else "${download.percent}%",
                    color = Color.White,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .background(Color(0xAA000000))
                        .padding(horizontal = 6.dp)
                        .semantics {
                            contentDescription = if (download.fill == Fill.DONE) "Downloaded" else "Downloaded ${download.percent}%"
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
            ProgressBar(item, state, Modifier.align(Alignment.BottomStart).padding(if (focused) 3.dp else 0.dp)) // Stays inside the focus border.
            if (onChanged != null) {
                ItemMenu(item, state?.played == true, unsynced, menu, { menu = false }, download != null, onStartOver, onChanged)
            }
        }
        Text(item.name.orEmpty(), maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
fun SeriesScreen(modifier: Modifier, screen: Screen.Series, push: (Screen) -> Unit, pop: () -> Unit) {
    var refresh by remember { mutableIntStateOf(0) }
    val seasons = loadFresh(screen.id, refresh = refresh) { App.server.seasons(screen.id) }
    // Every episode, for Continue. A show with one season lists its episodes here instead of a one-row season list.
    val episodes = loadFresh(screen.id, "episodes", refresh = refresh) { App.server.episodes(screen.id) }
    val statuses = rememberDownloadStatuses()
    var keepNext by remember { mutableIntStateOf(App.prefs.keepNext(screen.id)) }
    val scope = rememberCoroutineScope()
    val downloadAll = downloadAllAction { App.server.episodes(screen.id, sources = true).filter { it.parentIndexNumber != 0 } }
    val markAll = markAllAction { refresh++ }
    val all = episodes.result?.getOrNull()
    val onlySeason = seasons.result?.getOrNull()?.singleOrNull()
    val shownEpisodes = if (onlySeason != null) {
        all?.filter { ep -> ep.seasonId == onlySeason.id }
    } else {
        null
    }
    // With one season, "all" means its episodes as shown. With several, it means every episode outside the specials.
    val markCandidates = shownEpisodes ?: all?.filter { ep -> ep.parentIndexNumber != 0 && ep.locationType != LocationType.VIRTUAL }
    val markable = if (markCandidates.isNullOrEmpty()) null else markCandidates
    Column(modifier.fillMaxSize()) {
        Bar(screen.name, pop) {
            if (App.downloads != null || markable != null) BarMenu { close ->
                if (App.downloads != null) {
                    DropdownMenuItem({ Text("Download all") }, {
                        close()
                        downloadAll()
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
        val next = if (all != null) upNext(all) else null
        if (next != null) ContinueButton(next) { push(Screen.Play(next.id.toString())) }
        if (App.downloads != null) Stepper(
            "Keep next episodes downloaded:",
            keepNext,
            (0..20).toList(),
            { count -> if (count == 0) "off" else "$count" },
        ) { n ->
            if (keepNext == 0) App.platform.askNotifications()
            keepNext = n
            App.prefs.setKeepNext(screen.id, n)
            scope.launch(Dispatchers.IO) {
                if (n == 0) App.downloads?.releaseKeepNext(screen.id)
                App.platform.scheduleHousekeeping()
            }
        }
        val listState = rememberLazyListState() // Outside Loaded, so the scroll survives its "Loading…" pass.
        Loaded(seasons, { refresh++ }, { push(Screen.Downloads) }) { list ->
            if (list.isEmpty()) Empty("This series has no seasons")
            else if (shownEpisodes != null) LazyColumn(state = listState) {
                items(shownEpisodes, key = { it.id }) { ep ->
                    EpisodeRow(ep, statuses?.get(ep.id.toString()),
                        onPlay = { push(Screen.Play(ep.id.toString())) },
                        onStartOver = { scope.launch { startOver(ep, push) } },
                        onChanged = { refresh++ })
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
                        val left = season.userData?.unplayedItemCount ?: 0
                        // This count comes from the server, so it lags local plays until the next sync. Episode rows show local state.
                        Text(if (left == 0) "✓" else "$left unplayed", Modifier.semantics { if (left == 0) contentDescription = "Played" })
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
    val statuses = rememberDownloadStatuses()
    val listState = rememberLazyListState() // Outside Loaded, so the scroll survives its "Loading…" pass when coming back.
    val scope = rememberCoroutineScope()
    val downloadAll = downloadAllAction { App.server.episodes(screen.seriesId, screen.seasonId, sources = true) }
    val markAll = markAllAction { refresh++ }
    val list = episodes.result?.getOrNull()
    // Only highlight up-next within a season someone has started; an untouched season has nothing to point at.
    val next = if (list != null && list.any { ep -> ep.localState().first?.lastPlayedMs != null }) upNext(list) else null
    var scrolled by rememberSaveable { mutableStateOf(false) } // Scroll to up-next only once; coming back keeps the user's scroll.
    LaunchedEffect(next != null) {
        val i = list.orEmpty().indexOf(next)
        if (!scrolled && i > 0) {
            scrolled = true
            listState.scrollToItem(i - 1)
        }
    }
    Column(modifier.fillMaxSize()) {
        Bar(screen.name, pop) {
            if (App.downloads != null || !list.isNullOrEmpty()) BarMenu { close ->
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
        Loaded(episodes, { refresh++ }, { push(Screen.Downloads) }) { list ->
            if (list.isEmpty()) Empty("This season has no episodes")
            else LazyColumn(state = listState) {
                items(list, key = { it.id }) { ep ->
                    EpisodeRow(
                        ep,
                        statuses?.get(ep.id.toString()),
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

/** "Continue S2E5 · Title" with the time left, or "Play" for an episode not started. */
@Composable
private fun ContinueButton(ep: BaseItemDto, onClick: () -> Unit) {
    val position = ep.localState().first?.positionTicks ?: 0
    val runtime = ep.runTimeTicks
    val minutesLeft = if (runtime != null && position > 0) (runtime - position) / TICKS_PER_MS / 60_000 else null
    Button(onClick, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Icon(LocalIcons.current.play, null)
        val verb = if (position > 0) "Continue " else "Play "
        val timeLeft = if (minutesLeft != null) " · ${minutesLeft.coerceAtLeast(1)} min left" else ""
        Text(verb + episodeLabel(ep) + timeLeft, Modifier.padding(start = 8.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Thumbnail, title, progress and download state. The ⋮ button or a long-press opens the item's actions. */
@Composable
fun EpisodeRow(
    ep: BaseItemDto,
    download: DownloadStatus?,
    highlighted: Boolean = false,
    onPlay: () -> Unit,
    onStartOver: (() -> Unit)? = null,
    onChanged: () -> Unit = {},
) {
    // Derived, so a local change recomposes only the row whose state it changed, not every row.
    val local by remember(ep) { derivedStateOf { ep.localState() } }
    val (state, unsynced) = local
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth()
            .background(if (highlighted) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
            .focusMemory(ep.id)
            .onSecondaryClick { menu = true }
            .combinedClickable(onClick = onPlay, onLongClickLabel = "Actions", onLongClick = { menu = true })
            .padding(8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.width(140.dp)) {
            AsyncImage(App.server.imageUrl(ep, ImageType.PRIMARY, 320), null, contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9).background(Color.DarkGray))
            ProgressBar(ep, state, Modifier.align(Alignment.BottomStart))
        }
        Column(Modifier.weight(1f)) {
            val number = ep.indexNumber
            val title = listOfNotNull(if (number != null) "$number." else null, ep.name).joinToString(" ")
            Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val runtime = ep.runTimeTicks
            val bits = listOfNotNull(
                if (runtime != null) formatMs(runtime / TICKS_PER_MS) else null,
                if (state?.played == true) "✓ played" else null,
                download?.label,
                if (unsynced) "not synced" else null,
            )
            Text(bits.joinToString(" · "), style = MaterialTheme.typography.labelSmall)
        }
        Box {
            IconButton(onClick = { menu = true }) { Icon(LocalIcons.current.more, "More options") }
            ItemMenu(ep, state?.played == true, unsynced, menu, { menu = false }, download != null, onStartOver, onChanged)
        }
    }
}

@Composable
private fun ProgressBar(item: BaseItemDto, state: PlayState?, modifier: Modifier = Modifier) {
    val runtime = item.runTimeTicks ?: 0
    if (state != null && state.positionTicks > 0 && runtime > 0) {
        LinearProgressIndicator({ state.positionTicks.toFloat() / runtime }, modifier.fillMaxWidth())
    }
}

/** Actions of an episode or movie. */
@Composable
private fun ItemMenu(item: BaseItemDto, played: Boolean, unsynced: Boolean, expanded: Boolean, onDismiss: () -> Unit, downloaded: Boolean, onStartOver: (() -> Unit)?, onChanged: () -> Unit) {
    val id = item.id.toString()
    val scope = rememberCoroutineScope()
    var confirm by remember { mutableStateOf(false) }
    ConfirmDelete(if (confirm) item else null, { confirm = false }, onChanged)
    DropdownMenu(expanded, onDismiss) {
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
        if (App.downloads != null && downloaded) {
            DropdownMenuItem({ Text("Delete download") }, {
                onDismiss()
                confirm = true
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
fun DownloadsScreen(modifier: Modifier, push: (Screen) -> Unit, pop: () -> Unit) {
    val media = App.downloads!! // The screen is only offered where there are downloads.
    val statuses = rememberDownloadStatuses()
    // Triples of (header key, header, status): unfinished first in queue order, then finished by series, with movies last.
    val downloads = remember(statuses) {
        val (done, queue) = statuses.orEmpty().values.partition { status -> status.fill == Fill.DONE }
        val queueRows = queue
            .sortedWith(compareBy(queueOrder) { status -> status.download })
            .map { status -> Triple("h-queue", "Queue", status) }
        val doneRows = done
            .sortedWith(compareBy(shelfOrder) { status -> status.download })
            .map { status ->
                val seriesId = status.download.item.seriesId
                if (seriesId != null) Triple("h-$seriesId", status.download.item.seriesName.orEmpty(), status)
                else Triple("h-movies", "Movies", status)
            }
        queueRows + doneRows
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
    var deleting by remember { mutableStateOf<BaseItemDto?>(null) }
    ConfirmDelete(deleting, { deleting = null })
    Column(modifier.fillMaxSize()) {
        Bar("Downloads", pop)
        Text(storage, Modifier.padding(horizontal = 16.dp))
        if (media.storageMissing) Text("SD card not available. Downloads wait for it; restart the app after inserting it.",
            Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.error)
        if (statuses != null && downloads.isEmpty()) Empty("Nothing downloaded. Use ⋮ on an episode or a series, or long-press a movie.")
        else LazyColumn {
            var lastGroup: String? = null
            for ((group, title, status) in downloads) {
                if (group != lastGroup) {
                    lastGroup = group
                    val seriesId = if (status.fill == Fill.DONE) status.download.item.seriesId else null
                    item(key = group) {
                        Text(
                            title,
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier
                                .fillMaxWidth()
                                .then(if (seriesId != null) Modifier.clickable { push(Screen.Series(seriesId.toString(), title)) } else Modifier)
                                .padding(16.dp, 12.dp, 16.dp, 4.dp),
                        )
                    }
                }
                val d = status.download
                item(key = d.item.id) {
                    val id = d.item.id.toString()
                    val (state, unsynced) = d.item.localState()
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .focusMemory(id)
                            .combinedClickable(onClick = { push(Screen.Play(id)) })
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(itemLabel(d.item))
                            val size = if (status.bytes > 0) " · ${formatBytes(status.bytes)}" else ""
                            val bits = listOfNotNull(
                                status.label + size,
                                if (d.auto) "kept by keep-next" else null,
                                if (state?.played == true) "✓ played" else null,
                                if (unsynced) "not synced" else null,
                            )
                            Text(bits.joinToString(" · "), style = MaterialTheme.typography.labelSmall)
                            if (status.fill != Fill.DONE) LinearProgressIndicator({ status.percent / 100f }, Modifier.fillMaxWidth().padding(top = 4.dp))
                            else ProgressBar(d.item, state)
                        }
                        when (status.fill) {
                            Fill.RUNNING, Fill.QUEUED -> TextButton(onClick = {
                                scope.launch(Dispatchers.IO) { media.pauseDownload(id) }
                            }) { Text("Pause") }
                            Fill.PAUSED -> TextButton(onClick = {
                                scope.launch(Dispatchers.IO) {
                                    // A fresh copy of the item when the server answers, else the one saved with the download.
                                    val fresh = runCatching { App.server.item(id) }.getOrDefault(d.item)
                                    media.startDownload(fresh, d.auto)
                                }
                            }) { Text(if (d.failed) "Retry" else "Resume") }
                            Fill.DONE -> {}
                        }
                        TextButton(onClick = { deleting = d.item }) { Text("Delete") }
                    }
                }
            }
        }
    }
}

@Composable
fun SettingsScreen(modifier: Modifier, pop: () -> Unit, onAccounts: () -> Unit, onLoggedOut: () -> Unit) {
    val p = App.prefs
    var refresh by remember { mutableIntStateOf(0) } // Bumped to redraw after a pref changes.
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
        val count = remember { App.downloads?.downloads()?.size ?: 0 }
        val where = if (target > 0) "the SD card" else "internal storage"
        val plural = if (count == 1) "" else "s"
        val deletes = if (count == 0) "" else "deletes your $count download$plural and "
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
        val unsynced = App.pending.all().size
        val itemsHave = if (unsynced == 1) "item has" else "items have"
        val note = if (unsynced == 0) "" else " $unsynced $itemsHave progress not synced yet. It is sent after you log back in to the same server."
        Confirm("Log out?$note", "Log out", {
            App.server.logout()
            onLoggedOut()
        }, { confirmLogout = false })
    }
    Column(modifier.fillMaxSize()) {
        Bar("Settings", pop)
        // A readable width on tablets and desktop windows, where a label and its control would sit a screen apart.
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .wrapContentWidth(Alignment.Start)
                .widthIn(max = 720.dp)
        ) {
            key(refresh) {
                val android = App.platform.android
                val downloads = App.downloads
                Text("Network", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(16.dp))
                if (android) toggle("Buffer to end on metered networks", p.bufferOnMetered) { p.bufferOnMetered = it }
                if (android && downloads != null) toggle("Download on metered networks", p.downloadOnMetered) { on ->
                    p.downloadOnMetered = on
                    downloads.kickDownloads(restart = true)
                }
                Stepper("Buffer cache limit (GB)", p.cacheLimitGb, listOf(1, 2, 5, 10, 20, 50, 100)) { gb ->
                    p.cacheLimitGb = gb
                    refresh++
                }
                if (downloads != null && (downloads.storageRoots().size > 1 || downloads.storageMissing)) { // When the card is missing, switching back to internal storage is the way out.
                    toggle("Store on SD card (switching deletes downloads and the buffer)", p.storageIndex > 0, enabled = !moving) { on ->
                        moveTo = if (on) 1 else 0
                    }
                }

                Text("Playback", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(16.dp))
                // Android applies the delay only while a wireless output is connected. The desktop can't tell, so it always applies it.
                val audioDelayLabel = if (android) "Wireless audio delay (ms), for Bluetooth headphones and speakers" else "Audio delay (ms)"
                Stepper(audioDelayLabel, p.audioDelayMs, AUDIO_DELAYS_MS) { ms ->
                    App.platform.setAudioDelay(ms)
                    refresh++
                }
                Stepper("Skip step (s)", p.doubleTapSeconds, (5..60 step 5).toList()) { seconds ->
                    p.doubleTapSeconds = seconds
                    refresh++
                }
                // Files past these limits start as a transcode. Auto asks the hardware decoders per file.
                val decoders = remember { App.platform.decoderSummary() }
                if (decoders != null) {
                    Text("Hardware decoders: $decoders", Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.labelSmall)
                }
                Text("Direct play up to", Modifier.padding(16.dp, 8.dp, 16.dp, 0.dp))
                Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(Pair(0, "Auto"), Pair(2160, "4K"), Pair(1080, "1080p"), Pair(720, "720p")).forEach { (h, label) ->
                        FilterChip(p.maxHeight == h, {
                            p.maxHeight = h
                            refresh++
                        }, { Text(label) })
                    }
                }
                // The desktop player decodes every codec, so there the height limit above only serves bandwidth.
                if (android) Text("Direct play codecs", Modifier.padding(16.dp, 8.dp, 16.dp, 0.dp))
                if (android) Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("Auto", "H.264 only", "All").forEachIndexed { i, label ->
                        FilterChip(p.codecs == i, {
                            p.codecs = i
                            refresh++
                        }, { Text(label) })
                    }
                }
                if (android && p.learnedCodecs().isNotEmpty()) {
                    val codecs = p.learnedCodecs().joinToString { codec -> codec.uppercase() }
                    Text("Transcoding sooner for $codecs after choppy playback",
                        Modifier.padding(16.dp, 8.dp, 16.dp, 0.dp), style = MaterialTheme.typography.labelSmall)
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
                Text("finimal ${App.platform.versionName}", Modifier.padding(16.dp), style = MaterialTheme.typography.labelSmall)
                Spacer(Modifier.padding(24.dp))
            }
        }
    }
}

/** A right click does what a long press does, opening an item's menu with the mouse. Touch has no secondary button. */
private fun Modifier.onSecondaryClick(action: (() -> Unit)?): Modifier = if (action == null) this else pointerInput(action) {
    awaitPointerEventScope {
        while (true) {
            val e = awaitPointerEvent()
            if (e.type == PointerEventType.Press && e.buttons.isSecondaryPressed) {
                e.changes.forEach { change -> change.consume() }
                action()
            }
        }
    }
}

/** A "− value +" control that steps through [options]. A stored value not in the list shows as the nearest option. */
@Composable
private fun Stepper(label: String, value: Int, options: List<Int>, show: (Int) -> String = Int::toString, set: (Int) -> Unit) =
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        val i = options.indices.minBy { index -> abs(options[index] - value) }
        Text(label, Modifier.weight(1f))
        TextButton(onClick = { set(options[i - 1]) }, enabled = i > 0) {
            Text("−", Modifier.semantics { contentDescription = "Decrease $label, now ${show(options[i])}" })
        }
        // A fixed width, so "−" doesn't move under the pointer when the value gets a digit longer.
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

/** The account's initial in a circle. */
@Composable
private fun Avatar(name: String, modifier: Modifier = Modifier) =
    Box(
        modifier
            .size(32.dp)
            .background(MaterialTheme.colorScheme.primaryContainer, androidx.compose.foundation.shape.CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(name.firstOrNull()?.uppercase() ?: "?", color = MaterialTheme.colorScheme.onPrimaryContainer, style = MaterialTheme.typography.titleSmall)
    }

/**
 * The accounts saved on this device, all on one server. Picking one switches to it, after its PIN when it has one. As
 * the first screen ([pop] null) it is "Who's watching?". The current account sets or removes its own PIN here.
 */
@Composable
fun AccountsScreen(modifier: Modifier, pop: (() -> Unit)?, onSwitched: () -> Unit, onAdd: () -> Unit) {
    val p = App.prefs
    var refresh by remember { mutableIntStateOf(0) }
    val accounts = remember(refresh) { p.accounts() }
    var asking by remember { mutableStateOf<Account?>(null) }
    var settingPin by remember { mutableStateOf(false) }
    val picking = pop == null
    fun open(a: Account) {
        if (a.userId != p.userId) App.server.switchTo(a)
        onSwitched()
    }
    val askingFor = asking
    if (askingFor != null) {
        PinDialog("PIN for ${askingFor.name.ifEmpty { "this account" }}", { asking = null }) { pin ->
            val matches = p.pinMatches(askingFor, pin)
            if (matches) {
                asking = null
                open(askingFor)
            }
            matches
        }
    }
    if (settingPin) PinDialog("New PIN", { settingPin = false }) { pin ->
        p.setPin(p.userId!!, pin)
        settingPin = false
        refresh++
        true
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
                    if (a.pin != null) Text("PIN", style = MaterialTheme.typography.labelSmall)
                }
            }
            TextButton(onClick = onAdd, Modifier.padding(horizontal = 8.dp)) { Text("Add account") }
            if (!picking) {
                val me = accounts.firstOrNull { account -> account.userId == p.userId }
                HorizontalDivider()
                Text("Your PIN is asked when switching to your account. It keeps your titles and history from others on this device.",
                    Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall)
                Row(Modifier.padding(horizontal = 8.dp)) {
                    TextButton(onClick = { settingPin = true }) { Text(if (me?.pin == null) "Set PIN" else "Change PIN") }
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

/** Asks for a PIN of 4 to 8 digits; [onPin] answers whether it was accepted. */
@Composable
private fun PinDialog(title: String, onDismiss: () -> Unit, onPin: (String) -> Boolean) {
    var pin by remember { mutableStateOf("") }
    var wrong by remember { mutableStateOf(false) }
    val ok = pin.length in 4..8
    // Typing goes straight in, from a keyboard or a TV remote's number keys.
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    // A wrong PIN clears the field, and focus goes back to it, because the now disabled OK button can't keep focus.
    fun submit() {
        if (!onPin(pin)) {
            wrong = true
            pin = ""
            focus.requestFocus()
        }
    }
    AlertDialog(onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                pin,
                { text ->
                    pin = text.filter(Char::isDigit).take(8)
                    wrong = false
                },
                singleLine = true,
                isError = wrong,
                label = { Text("PIN") },
                supportingText = { Text(if (wrong) "Wrong PIN" else "4 to 8 digits") },
                modifier = Modifier.focusRequester(focus),
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (ok) submit() }),
            )
        },
        confirmButton = { TextButton(onClick = ::submit, enabled = ok) { Text("OK") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}
