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

/** Last good list per server, user and list, so a list you come back to shows at once while it reloads. Main thread only. */
private val lastGood = object : LinkedHashMap<List<Any?>, List<BaseItemDto>>(16, 0.75f, true) {
    // Every search query and sort is its own entry; keep the recent ones.
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<List<Any?>, List<BaseItemDto>>) = size > 20
}

/**
 * The same lists on disk, so a cold start shows the last list at once instead of "Loading…".
 * Display only: play state is drawn from pending local state over it, and a fresh fetch replaces it.
 */
object ListSnapshots {
    private val json = Json { ignoreUnknownKeys = true }
    private val dir get() = File(App.platform.appCache, "lists")
    private fun file(key: List<Any?>) = File(dir, MessageDigest.getInstance("SHA-1").digest(key.joinToString("\u0000").toByteArray())
        .joinToString("") { "%02x".format(it) } + ".json")

    fun read(key: List<Any?>): List<BaseItemDto>? = runCatching { json.decodeFromString<List<BaseItemDto>>(file(key).readText()) }.getOrNull()
    fun write(key: List<Any?>, list: List<BaseItemDto>) { runCatching { dir.mkdirs(); file(key).writeAtomically(json.encodeToString(list)) } }
    fun clear() { dir.deleteRecursively() }

    /** Libraries, the usual first screen, read at app start off the main thread, so the first frame can show it. */
    fun prefetchLibraries() {
        val key = listOf(App.prefs.baseUrl, App.prefs.userId)
        read(key)?.let { prefetched = key to it }
    }
    @Volatile private var prefetched: Pair<List<Any?>, List<BaseItemDto>>? = null
    fun takePrefetched(key: List<Any?>) = prefetched?.takeIf { it.first == key }?.second?.also { prefetched = null }
}

/** The item last focused with the D-pad on this screen, by key, so coming back to the screen puts focus on it again. */
private val LocalFocusedKey = compositionLocalOf<MutableState<String?>> { mutableStateOf(null) }

@Composable
fun FocusMemory(content: @Composable () -> Unit) =
    CompositionLocalProvider(LocalFocusedKey provides rememberSaveable { mutableStateOf<String?>(null) }, content = content)

/** Before the item's clickable: records D-pad focus under [key] and takes it back when the item shows again. Touch never focuses. */
private fun Modifier.focusMemory(key: Any): Modifier = composed {
    val memory = LocalFocusedKey.current
    val requester = remember { FocusRequester() }
    val k = key.toString()
    val shown = LocalShown.current // Root clears focus while another screen covers this one
    LaunchedEffect(Unit) { snapshotFlow { shown.value }.collect { if (it && memory.value == k) requester.requestFocus() } }
    focusRequester(requester).onFocusChanged { if (it.isFocused) memory.value = k }
}

/** A list, and why the refresh behind it failed when it is an older copy. */
class Load(val result: Result<List<BaseItemDto>>?, val refreshError: Throwable?)

/**
 * Loads [block] after pushing unsynced progress, so the list never shows state older than this device's.
 * Loads again each time the screen comes back on top. Until the load ends, the list from the last visit
 * shows (from memory, else from disk when [persist]); unsynced local state is drawn over it. A failed load
 * keeps that list and says why above it.
 */
@Composable
private fun loadFresh(vararg keys: Any?, refresh: Int, persist: Boolean = true, block: suspend () -> List<BaseItemDto>): Load {
    val k = keys.toList()
    val cacheKey = listOf(App.prefs.baseUrl, App.prefs.userId) + k
    var last by remember { mutableStateOf((lastGood[cacheKey] ?: ListSnapshots.takePrefetched(cacheKey))?.let { k to Result.success(it) }) }
    var refreshError by remember { mutableStateOf<Throwable?>(null) }
    val shown = LocalShown.current
    LaunchedEffect(k, refresh) { snapshotFlow { shown.value }.collectLatest { if (it) coroutineScope {
        refreshError = null
        if (last?.second?.isFailure == true) last = null // a retry shows "Loading…" again
        if (last?.first != k) (lastGood[cacheKey] ?: if (persist) withContext(Dispatchers.IO) { ListSnapshots.read(cacheKey) } else null)
            ?.let { last = k to Result.success(it) }
        val pushed = App.server.flush()
        // An unreachable server would only time out a second time on the fetch.
        val result = if (pushed != null && unreachable(pushed)) Result.failure(pushed) else runCatching { block() }
            .onFailure { if (it is InvalidStatusException && it.status == 401) App.server.expire() }
        ensureActive() // covered meanwhile: not a failure, and the next visit loads again
        val prev = last
        when {
            result.isSuccess -> {
                val list = result.getOrThrow()
                lastGood[cacheKey] = list
                if (persist) launch(Dispatchers.IO) { ListSnapshots.write(cacheKey, list) }
                last = k to result
            }
            prev != null && prev.first == k && prev.second.isSuccess -> refreshError = result.exceptionOrNull()
            else -> last = k to result
        }
    } } }
    return Load(last?.second, refreshError)
}

/**
 * Download statuses while the screen is shown, null until first read. Re-read on every download
 * change, and polled every second while one runs, else every 5 s. Not while covered by another screen.
 */
@Composable
fun rememberDownloadStatuses(): Map<String, DownloadStatus>? {
    val downloads = App.downloads ?: return null
    val shown = LocalShown.current
    return produceState<Map<String, DownloadStatus>?>(null, downloads.changes) {
        snapshotFlow { shown.value }.collectLatest { visible ->
            while (visible) {
                value = withContext(Dispatchers.IO) { downloads.downloadStatuses() }
                delay(if (value.orEmpty().values.any { it.fill == Fill.RUNNING }) 1_000 else 5_000)
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
            // Episodes fetched with sources queue as local writes, so leaving the screen can't stop it partway.
            runCatching { downloads.startDownload(ep.takeIf { it.mediaSources != null } ?: App.server.item(ep.id.toString()), auto = false) }
                .onSuccess { queued++ }.onFailure { if (it is CancellationException) throw it; failures += it }
        }
    }
    val text = when {
        queued == 0 -> failures.firstOrNull()?.let(::loadError) ?: "Nothing new to download"
        queued == 1 -> "Downloading 1 $noun"
        else -> "Downloading $queued ${noun}s"
    } + if (queued > 0 && failures.isNotEmpty()) ", ${failures.size} failed" else ""
    if (queued > 0) App.platform.askNotifications()
    toast(text + (downloads.downloadsWaitingFor()?.takeIf { queued > 0 }?.let { " ($it)" } ?: ""))
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
    // A fetch that ends after the screen was covered asks once it is back on top, not over another screen.
    pending?.takeIf { LocalShown.current.value }?.let { eps ->
        val total = eps.mapNotNull { it.mediaSources?.firstOrNull()?.size }.takeIf { it.size == eps.size }?.sum()
        Confirm("Download ${eps.size} episode${if (eps.size == 1) "" else "s"}" + (total?.let { ", about ${formatBytes(it)}" } ?: "") + "?", "Download", {
            busy = true
            scope.launch { downloadAll(eps); busy = false }
        }, { pending = null })
    }
    return action@{
        if (busy) return@action
        busy = true
        scope.launch {
            runCatching { fetch() }
                .onSuccess { eps ->
                    val new = withContext(Dispatchers.IO) { eps.filter { App.downloads?.download(it.id.toString()) == null } }
                    if (new.isEmpty()) toast("Nothing new to download") else pending = new
                }
                .onFailure { if (it is CancellationException) throw it; toast(loadError(it)) }
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

/** "Mark played" for many episodes at once; asks first, as it drops their resume points. */
@Composable
private fun markAllAction(onChanged: () -> Unit): (List<BaseItemDto>) -> Unit {
    var asking by remember { mutableStateOf<List<BaseItemDto>?>(null) }
    val scope = rememberCoroutineScope()
    asking?.let { eps ->
        val played = !eps.all { it.localState().first?.played == true }
        Confirm("Mark ${eps.size} episode${if (eps.size == 1) "" else "s"} ${if (played) "played" else "unplayed"}?", "Mark", {
            scope.launch { App.server.setPlayed(eps.map { it.id.toString() }, played); onChanged() }
        }, { asking = null })
    }
    return { asking = it }
}

/** Where to pick up a series: the unfinished episode last played, else the first unplayed one after it, else the first. Null once all is played. */
fun upNext(episodes: List<BaseItemDto>): BaseItemDto? {
    val playable = episodes.filter { it.parentIndexNumber != 0 && it.locationType != LocationType.VIRTUAL }
    val window = keepNextWindow(playable.map { EpisodeRef(it.id.toString(), it.localState().first) }, playable.size).toSet()
    return playable.firstOrNull { it.id.toString() in window && it.localState().first?.played != true }
}

/** Plays [item] from the start: the position is saved as 0 first, so nothing can resume it later. */
private suspend fun startOver(item: BaseItemDto, push: (Screen) -> Unit) {
    App.server.startOver(item)
    push(Screen.Play(item.id.toString()))
}

@Composable
private fun Confirm(text: String, confirmLabel: String, onConfirm: () -> Unit, onDismiss: () -> Unit) = AlertDialog(onDismiss,
    confirmButton = { TextButton(onClick = { onDismiss(); onConfirm() }) { Text(confirmLabel) } },
    dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    text = { Text(text) })

/** Asks before deleting [item]'s download; shows nothing while [item] is null. */
@Composable
private fun ConfirmDelete(item: BaseItemDto?, onDismiss: () -> Unit, onDeleted: () -> Unit = {}) {
    val scope = rememberCoroutineScope()
    item ?: return
    Confirm("Delete download of ${itemLabel(item)}?", "Delete", {
        scope.launch { withContext(Dispatchers.IO) { App.downloads?.deleteDownload(item.id.toString()) }; onDeleted() }
    }, onDismiss)
}

fun itemLabel(item: BaseItemDto) = if (item.type == BaseItemKind.EPISODE) episodeLabel(item) else item.name.orEmpty()

fun formatBytes(b: Long) = if (b >= 1e9) "%.1f GB".format(b / 1e9) else "%.0f MB".format(b / 1e6)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Bar(title: String, onBack: (() -> Unit)?, actions: @Composable () -> Unit = {}) = TopAppBar(
    title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
    navigationIcon = { onBack?.let { IconButton(onClick = it) { Icon(LocalIcons.current.back, "Back") } } },
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
    raw?.let { Text(it, color = color, style = MaterialTheme.typography.bodySmall) }
}

@Composable
private fun Loaded(load: Load, onRetry: () -> Unit, onDownloads: () -> Unit, content: @Composable (List<BaseItemDto>) -> Unit) {
    val result = load.result
    when {
        result == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        result.isFailure -> Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            val e = result.exceptionOrNull()!!
            if (unreachable(e)) RetryOnNewNetwork(onRetry)
            ErrorText(loadError(e), e.message ?: e.javaClass.simpleName)
            Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = onRetry) { Text("Retry") }
                if (App.downloads != null) TextButton(onClick = onDownloads) { Text("Downloads") }
            }
        }
        // The note about a failed refresh lies over the list's bottom: pushing the list down would move what is under the pointer.
        else -> Box(Modifier.fillMaxSize()) {
            content(result.getOrThrow())
            load.refreshError?.let { e ->
                if (unreachable(e)) RetryOnNewNetwork(onRetry)
                Row(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).padding(start = 16.dp),
                    verticalAlignment = Alignment.CenterVertically) {
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

// --- login --------------------------------------------------------------------------------------

@Composable
fun LoginScreen(modifier: Modifier, onBack: (() -> Unit)? = null, onDone: () -> Unit) {
    // Adding an account: another user of the same server, which stays as it is.
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
    Column(Modifier.widthIn(max = 560.dp).fillMaxHeight().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            onBack?.let { IconButton(onClick = it) { Icon(LocalIcons.current.back, "Back") } }
            Text(if (adding) "Add account" else "finimal", style = MaterialTheme.typography.headlineMedium)
        }
        if (App.sessionExpired && !adding) Text("The server ended this session. Log in again; unsynced progress is kept.")
        // The port apart, prefilled from the host; one written into the server field wins (see serverCandidates).
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(url, { url = it; error = null; if (!portEdited) defaultPortFor(it)?.let { p -> port = p } }, label = { Text("Server") }, placeholder = { Text("jellyfin.example.com") }, singleLine = true, enabled = !busy && !adding,
                modifier = Modifier.weight(1f), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next))
            OutlinedTextField(port, { port = it.filter(Char::isDigit).take(5); portEdited = true; error = null }, label = { Text("Port") }, singleLine = true, enabled = !busy && !adding,
                isError = !portOk, modifier = Modifier.width(112.dp),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next))
        }
        if (!portOk) Text("The port is a number from 1 to 65535, or empty for the default of https or http.", color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(user, { user = it; error = null }, label = { Text("Username") }, singleLine = true, enabled = !busy,
            modifier = Modifier.fillMaxWidth().semantics { contentType = ContentType.Username },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next))
        OutlinedTextField(pass, { pass = it; error = null }, label = { Text("Password") }, singleLine = true, enabled = !busy,
            modifier = Modifier.fillMaxWidth().semantics { contentType = ContentType.Password },
            visualTransformation = if (showPass) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = { TextButton(onClick = { showPass = !showPass }) { Text(if (showPass) "Hide" else "Show") } },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { submit() }))
        error?.let { e ->
            if (e is InvalidStatusException && e.status == 401) Text("Wrong username or password", color = MaterialTheme.colorScheme.error)
            else ErrorText(loadError(e) + if (unreachable(e)) " at ${serverCandidates(url, port).joinToString(" or ")}" else "", e.message ?: e.javaClass.simpleName, MaterialTheme.colorScheme.error)
        }
        Button(enabled = !busy && canSubmit, onClick = ::submit) {
            if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text("Log in")
        }
    }
    }
}

// --- browsing -------------------------------------------------------------------------------------

@Composable
fun LibrariesScreen(modifier: Modifier, push: (Screen) -> Unit) {
    var refresh by remember { mutableIntStateOf(0) }
    val libs = loadFresh(refresh = refresh) { App.server.libraries() }
    var me by remember { mutableStateOf(App.prefs.accounts().firstOrNull { it.userId == App.prefs.userId }) }
    // Accounts from before names were kept: the name comes from the server once.
    LaunchedEffect(Unit) {
        val m = me?.takeIf { it.name.isEmpty() } ?: return@LaunchedEffect
        runCatching { App.server.userName() }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { App.prefs.setName(m.userId, it); me = m.copy(name = it) }
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
                    Text(lib.name.orEmpty(), style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.fillMaxWidth().focusMemory(lib.id).combinedClickable(onClick = { push(Screen.Library(lib.id.toString(), lib.name.orEmpty())) }).padding(20.dp))
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
    LaunchedEffect(search) { delay(400); query = search }
    val searchOnly = screen.id == null
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    var focusedOnce by rememberSaveable { mutableStateOf(false) } // not again when coming back
    LaunchedEffect(Unit) { if (searchOnly && !focusedOnce) { focusedOnce = true; focus.requestFocus(); keyboard?.show() } }
    val items = if (searchOnly && query.isBlank()) Load(Result.success(emptyList()), null)
        else loadFresh(screen.id, sort, unplayed, query, refresh = refresh, persist = query.isBlank()) { App.server.items(screen.id, sort, unplayed, query) }
    Column(modifier.fillMaxSize()) {
        Bar(screen.name, pop)
        OutlinedTextField(search, { search = it }, label = { Text("Search") }, singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).focusRequester(focus),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { query = search; keyboard?.hide() }))
        if (!searchOnly) Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
            else LazyVerticalGrid(GridCells.Adaptive(if (App.tv) 150.dp else if (smallestWidthDp() >= 600) 140.dp else 110.dp), Modifier.fillMaxSize().padding(8.dp), gridState,
                horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
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

/** Movies (non-null [onChanged]) also get progress, download state and the long-press menu. */
@Composable
private fun Poster(
    item: BaseItemDto,
    download: DownloadStatus?,
    onClick: () -> Unit,
    onStartOver: (() -> Unit)? = null,
    onChanged: (() -> Unit)? = null,
) {
    // Derived: a local change then recomposes only the row whose state it changed, not every row.
    val local by remember(item) { derivedStateOf { item.localState() } }
    val (state, unsynced) = local
    var menu by remember { mutableStateOf(false) }
    // The focus highlight doesn't show over artwork; a border does.
    var focused by remember { mutableStateOf(false) }
    Column(Modifier.focusMemory(item.id).onFocusChanged { focused = it.isFocused }
        .onSecondaryClick(onChanged?.let { { menu = true } })
        .combinedClickable(onClick = onClick, onLongClickLabel = "Actions", onLongClick = onChanged?.let { { menu = true } })) {
        Box(if (focused) Modifier.border(3.dp, MaterialTheme.colorScheme.primary) else Modifier) {
            AsyncImage(App.server.imageUrl(item), null, contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxWidth().aspectRatio(2f / 3).background(Color.DarkGray))
            download?.let {
                Text(if (it.fill == Fill.DONE) "↓" else "${it.percent}%", color = Color.White,
                    modifier = Modifier.align(Alignment.TopStart).background(Color(0xAA000000)).padding(horizontal = 6.dp)
                        .semantics { contentDescription = if (it.fill == Fill.DONE) "Downloaded" else "Downloaded ${it.percent}%" })
            }
            val badge = when {
                state?.played == true -> "✓"
                (item.userData?.unplayedItemCount ?: 0) > 0 -> item.userData!!.unplayedItemCount.toString()
                else -> null
            }
            badge?.let {
                Text(it, color = Color.White, modifier = Modifier.align(Alignment.TopEnd).background(Color(0xAA000000)).padding(horizontal = 6.dp)
                    .semantics { contentDescription = if (it == "✓") "Played" else "$it unplayed" })
            }
            if (unsynced) Text("not synced", color = Color.White, style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.align(Alignment.BottomStart).padding(bottom = 4.dp).background(Color(0xAA000000)))
            ProgressBar(item, state, Modifier.align(Alignment.BottomStart))
            onChanged?.let { ItemMenu(item, state?.played == true, unsynced, menu, { menu = false }, download != null, onStartOver, it) }
        }
        Text(item.name.orEmpty(), maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
fun SeriesScreen(modifier: Modifier, screen: Screen.Series, push: (Screen) -> Unit, pop: () -> Unit) {
    var refresh by remember { mutableIntStateOf(0) }
    val seasons = loadFresh(screen.id, refresh = refresh) { App.server.seasons(screen.id) }
    // Every episode, for Continue; a show with one season lists them here instead of a one-row season list.
    val episodes = loadFresh(screen.id, "episodes", refresh = refresh) { App.server.episodes(screen.id) }
    val statuses = rememberDownloadStatuses()
    var keepNext by remember { mutableIntStateOf(App.prefs.keepNext(screen.id)) }
    val scope = rememberCoroutineScope()
    val downloadAll = downloadAllAction { App.server.episodes(screen.id, sources = true).filter { it.parentIndexNumber != 0 } }
    val markAll = markAllAction { refresh++ }
    val all = episodes.result?.getOrNull()
    val onlySeason = seasons.result?.getOrNull()?.singleOrNull()
    val shownEpisodes = onlySeason?.let { s -> all?.filter { it.seasonId == s.id } }
    // With one season the rows are its episodes; with several, "all" is every episode of the series.
    val markable = (shownEpisodes ?: all)?.takeIf { it.isNotEmpty() }
    Column(modifier.fillMaxSize()) {
        Bar(screen.name, pop) {
            if (App.downloads != null || markable != null) BarMenu { close ->
                if (App.downloads != null) DropdownMenuItem({ Text("Download all") }, { close(); downloadAll() })
                markable?.let { eps ->
                    val played = eps.all { it.localState().first?.played == true }
                    DropdownMenuItem({ Text(if (played) "Mark all unplayed" else "Mark all played") }, { close(); markAll(eps) })
                }
            }
        }
        all?.let(::upNext)?.let { ep -> ContinueButton(ep) { push(Screen.Play(ep.id.toString())) } }
        if (App.downloads != null) Stepper("Keep next episodes downloaded:", keepNext, (0..20).toList(), { if (it == 0) "off" else "$it" }) { n ->
            if (keepNext == 0) App.platform.askNotifications()
            keepNext = n
            App.prefs.setKeepNext(screen.id, n)
            scope.launch(Dispatchers.IO) {
                if (n == 0) App.downloads?.releaseKeepNext(screen.id)
                App.platform.scheduleHousekeeping()
            }
        }
        val listState = rememberLazyListState() // outside Loaded, so the scroll survives its "Loading…" pass
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
                    Row(Modifier.fillMaxWidth().focusMemory(season.id).combinedClickable(onClick = {
                        push(Screen.Season(screen.id, season.id.toString(), "${screen.name} · ${season.name}"))
                    }).padding(16.dp)) {
                        Text(season.name.orEmpty(), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                        val left = season.userData?.unplayedItemCount ?: 0
                        // From the server, so it lags local plays until the next sync; episode rows show local state.
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
    val listState = rememberLazyListState() // outside Loaded, so the scroll survives its "Loading…" pass when coming back
    val scope = rememberCoroutineScope()
    val downloadAll = downloadAllAction { App.server.episodes(screen.seriesId, screen.seasonId, sources = true) }
    val markAll = markAllAction { refresh++ }
    val list = episodes.result?.getOrNull()
    // Within this season only: a season nobody started has no up-next to point at.
    val next = list?.takeIf { l -> l.any { it.localState().first?.lastPlayedMs != null } }?.let(::upNext)
    var scrolled by rememberSaveable { mutableStateOf(false) } // once; coming back keeps the scroll
    LaunchedEffect(next != null) {
        val i = list.orEmpty().indexOf(next)
        if (!scrolled && i > 0) { scrolled = true; listState.scrollToItem(i - 1) }
    }
    Column(modifier.fillMaxSize()) {
        Bar(screen.name, pop) {
            BarMenu { close ->
                if (App.downloads != null) DropdownMenuItem({ Text("Download season") }, { close(); downloadAll() })
                list?.takeIf { it.isNotEmpty() }?.let { eps ->
                    val played = eps.all { it.localState().first?.played == true }
                    DropdownMenuItem({ Text(if (played) "Mark season unplayed" else "Mark season played") }, { close(); markAll(eps) })
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
    val left = ep.runTimeTicks?.takeIf { position > 0 }?.let { (it - position) / TICKS_PER_MS / 60_000 }
    Button(onClick, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Icon(LocalIcons.current.play, null)
        Text((if (position > 0) "Continue " else "Play ") + episodeLabel(ep) + (left?.let { " · ${it.coerceAtLeast(1)} min left" } ?: ""),
            Modifier.padding(start = 8.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
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
    // Derived: a local change then recomposes only the row whose state it changed, not every row.
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
            Text(listOfNotNull(ep.indexNumber?.let { "$it." }, ep.name).joinToString(" "), style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val bits = listOfNotNull(
                ep.runTimeTicks?.let { formatMs(it / TICKS_PER_MS) },
                "✓ played".takeIf { state?.played == true },
                download?.label,
                "not synced".takeIf { unsynced },
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
    ConfirmDelete(item.takeIf { confirm }, { confirm = false }, onChanged)
    DropdownMenu(expanded, onDismiss) {
        onStartOver?.let { DropdownMenuItem({ Text("Start over") }, { onDismiss(); it() }) }
        DropdownMenuItem({ Text(if (played) "Mark unplayed" else "Mark played") }, {
            onDismiss()
            scope.launch { App.server.setPlayed(listOf(id), !played); onChanged() }
        })
        if (App.downloads != null && downloaded) DropdownMenuItem({ Text("Delete download") }, { onDismiss(); confirm = true })
        else if (App.downloads != null) DropdownMenuItem({ Text("Download") }, {
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
                    toast(failure?.let(::loadError) ?: "Synced")
                    onChanged()
                }
            })
        }
    }
}

// --- downloads ------------------------------------------------------------------------------------

@Composable
fun DownloadsScreen(modifier: Modifier, push: (Screen) -> Unit, pop: () -> Unit) {
    val media = App.downloads!! // the screen is only offered where there are downloads
    val statuses = rememberDownloadStatuses()
    // (header key, header, status): unfinished first in queue order, then finished by series, movies last.
    val downloads = remember(statuses) {
        val (done, queue) = statuses.orEmpty().values.partition { it.fill == Fill.DONE }
        queue.sortedWith(compareBy(queueOrder) { it.download }).map { Triple("h-queue", "Queue", it) } +
            done.sortedWith(compareBy(shelfOrder) { it.download })
                .map { s -> s.download.item.seriesId?.let { Triple("h-$it", s.download.item.seriesName.orEmpty(), s) } ?: Triple("h-movies", "Movies", s) }
    }
    val storage by produceState("", statuses) {
        val (buffered, free, others) = withContext(Dispatchers.IO) { Triple(media.bufferedBytes(), media.freeBytes(), media.otherAccountsBytes()) }
        value = "Downloads ${formatBytes(statuses.orEmpty().values.sumOf { it.bytes })}" +
            (if (others > 0) " · other accounts ${formatBytes(others)}" else "") +
            " · buffer ${formatBytes(buffered)} of ${App.prefs.cacheLimitGb} GB · ${formatBytes(free)} free"
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
                    val seriesId = status.download.item.seriesId?.takeIf { status.fill == Fill.DONE }
                    item(key = group) {
                        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.fillMaxWidth()
                            .then(if (seriesId != null) Modifier.clickable { push(Screen.Series(seriesId.toString(), title)) } else Modifier)
                            .padding(16.dp, 12.dp, 16.dp, 4.dp))
                    }
                }
                val d = status.download
                item(key = d.item.id) {
                    val id = d.item.id.toString()
                    val (state, unsynced) = d.item.localState()
                    Row(Modifier.fillMaxWidth().focusMemory(id).combinedClickable(onClick = { push(Screen.Play(id)) }).padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(itemLabel(d.item))
                            val size = status.bytes.takeIf { it > 0 }?.let { " · ${formatBytes(it)}" } ?: ""
                            Text(listOfNotNull(status.label + size, "kept by keep-next".takeIf { d.auto }, "✓ played".takeIf { state?.played == true },
                                "not synced".takeIf { unsynced }).joinToString(" · "), style = MaterialTheme.typography.labelSmall)
                            if (status.fill != Fill.DONE) LinearProgressIndicator({ status.percent / 100f }, Modifier.fillMaxWidth().padding(top = 4.dp))
                            else ProgressBar(d.item, state)
                        }
                        when (status.fill) {
                            Fill.RUNNING, Fill.QUEUED -> TextButton(onClick = { scope.launch(Dispatchers.IO) { media.pauseDownload(id) } }) { Text("Pause") }
                            Fill.PAUSED -> TextButton(onClick = {
                                scope.launch(Dispatchers.IO) { media.startDownload(runCatching { App.server.item(id) }.getOrDefault(d.item), d.auto) }
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

// --- settings -------------------------------------------------------------------------------------

@Composable
fun SettingsScreen(modifier: Modifier, pop: () -> Unit, onAccounts: () -> Unit, onLoggedOut: () -> Unit) {
    val p = App.prefs
    var refresh by remember { mutableIntStateOf(0) } // bumps to redraw after a pref changes
    @Composable
    fun toggle(label: String, value: Boolean, enabled: Boolean = true, set: (Boolean) -> Unit) = Row(
        Modifier.fillMaxWidth().toggleable(value, enabled = enabled, role = Role.Switch) { set(it); refresh++ }.padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, Modifier.weight(1f), color = if (enabled) Color.Unspecified else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f))
        Switch(value, null, enabled = enabled)
    }
    val scope = rememberCoroutineScope()
    var moveTo by remember { mutableStateOf<Int?>(null) }
    var moving by remember { mutableStateOf(false) }
    moveTo?.let { target ->
        val count = remember { App.downloads?.downloads()?.size ?: 0 }
        val where = if (target > 0) "the SD card" else "internal storage"
        Confirm("Store on $where? This " + (if (count == 0) "" else "deletes your $count download${if (count == 1) "" else "s"} and ") +
            "clears the buffer cache; nothing is moved.", "Switch", {
            moving = true
            scope.launch { withContext(Dispatchers.IO) { App.downloads?.switchStorage(target) }; moving = false; refresh++ }
        }, { moveTo = null })
    }
    var confirmLogout by remember { mutableStateOf(false) }
    if (confirmLogout) {
        val unsynced = App.pending.all().size
        Confirm("Log out?" + if (unsynced == 0) "" else
            " $unsynced item${if (unsynced == 1) " has" else "s have"} progress not synced yet. It is sent after you log back in to the same server.",
            "Log out", { App.server.logout(); onLoggedOut() }, { confirmLogout = false })
    }
    Column(modifier.fillMaxSize()) {
        Bar("Settings", pop)
        // A readable width on tablets and desktop windows, where a label and its control would sit a screen apart.
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).wrapContentWidth(Alignment.Start).widthIn(max = 720.dp)) {
            key(refresh) {
                val android = App.platform.android
                val downloads = App.downloads
                Text("Network", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(16.dp))
                if (android) toggle("Buffer to end on metered networks", p.bufferOnMetered) { p.bufferOnMetered = it }
                if (android && downloads != null) toggle("Download on metered networks", p.downloadOnMetered) { p.downloadOnMetered = it; downloads.kickDownloads(restart = true) }
                Stepper("Buffer cache limit (GB)", p.cacheLimitGb, listOf(1, 2, 5, 10, 20, 50, 100)) { p.cacheLimitGb = it; refresh++ }
                if (downloads != null && (downloads.storageRoots().size > 1 || downloads.storageMissing)) { // missing: switching back is the way out of a lost card
                    toggle("Store on SD card (switching deletes downloads and the buffer)", p.storageIndex > 0, enabled = !moving) {
                        moveTo = if (it) 1 else 0
                    }
                }

                Text("Playback", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(16.dp))
                // Android applies it only while a wireless output is connected; the desktop can't tell, so always.
                Stepper(if (android) "Wireless audio delay (ms), for Bluetooth headphones and speakers" else "Audio delay (ms)", p.audioDelayMs, AUDIO_DELAYS_MS) { App.platform.setAudioDelay(it); refresh++ }
                Stepper("Skip step (s)", p.doubleTapSeconds, (5..60 step 5).toList()) { p.doubleTapSeconds = it; refresh++ }
                // Files past these limits start as a transcode. Auto asks the hardware decoders per file.
                remember { App.platform.decoderSummary() }?.let {
                    Text("Hardware decoders: $it", Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.labelSmall)
                }
                Text("Direct play up to", Modifier.padding(16.dp, 8.dp, 16.dp, 0.dp))
                Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(0 to "Auto", 2160 to "4K", 1080 to "1080p", 720 to "720p").forEach { (h, label) ->
                        FilterChip(p.maxHeight == h, { p.maxHeight = h; refresh++ }, { Text(label) })
                    }
                }
                // The desktop player decodes everything; the limit above is for bandwidth there.
                if (android) Text("Direct play codecs", Modifier.padding(16.dp, 8.dp, 16.dp, 0.dp))
                if (android) Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("Auto", "H.264 only", "All").forEachIndexed { i, label ->
                        FilterChip(p.codecs == i, { p.codecs = i; refresh++ }, { Text(label) })
                    }
                }
                if (android && p.learnedCodecs().isNotEmpty()) {
                    Text("Transcoding sooner for ${p.learnedCodecs().joinToString { it.uppercase() }} after choppy playback",
                        Modifier.padding(16.dp, 8.dp, 16.dp, 0.dp), style = MaterialTheme.typography.labelSmall)
                    TextButton(onClick = { p.forgetLearned(); refresh++ }, Modifier.padding(horizontal = 8.dp)) { Text("Forget") }
                }

                if (android) Text("Interface", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(16.dp))
                if (android) Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("Auto (${if (App.autoTv) "TV" else "touch"})", "Touch", "TV").forEachIndexed { i, label ->
                        FilterChip(p.interfaceMode == i, { p.interfaceMode = i; refresh++ }, { Text(label) })
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
                val me = p.accounts().firstOrNull { it.userId == p.userId }
                Text(listOfNotNull(me?.name?.ifEmpty { null }, p.baseUrl).joinToString(" · "), Modifier.padding(horizontal = 16.dp))
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

/** A right click does what a long press does: the mouse's way to an item's menu. Touch has no secondary button. */
private fun Modifier.onSecondaryClick(action: (() -> Unit)?): Modifier = if (action == null) this else pointerInput(action) {
    awaitPointerEventScope {
        while (true) {
            val e = awaitPointerEvent()
            if (e.type == PointerEventType.Press && e.buttons.isSecondaryPressed) { e.changes.forEach { it.consume() }; action() }
        }
    }
}

/** − value + over [options]; a stored value off the list shows as the nearest. */
@Composable
private fun Stepper(label: String, value: Int, options: List<Int>, show: (Int) -> String = Int::toString, set: (Int) -> Unit) =
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        val i = options.indices.minBy { abs(options[it] - value) }
        Text(label, Modifier.weight(1f))
        TextButton(onClick = { set(options[i - 1]) }, enabled = i > 0) { Text("−", Modifier.semantics { contentDescription = "Decrease $label, now ${show(options[i])}" }) }
        // A fixed width: "−" must not move under the pointer when the value gets a digit longer.
        Text(show(options[i]), Modifier.width(48.dp), textAlign = TextAlign.Center)
        TextButton(onClick = { set(options[i + 1]) }, enabled = i < options.lastIndex) { Text("+", Modifier.semantics { contentDescription = "Increase $label, now ${show(options[i])}" }) }
    }

/** Chapter starts as marks across a seek bar laid over them; the bar's ends sit [inset] in from its sides. */
@Composable
fun ChapterMarks(chapters: List<Chapter>, durationMs: Long, modifier: Modifier, inset: androidx.compose.ui.unit.Dp = 10.dp) =
    androidx.compose.foundation.Canvas(modifier) {
        if (durationMs <= 0) return@Canvas
        val left = inset.toPx()
        val half = 5.dp.toPx()
        chapters.filter { it.startMs in 1 until durationMs }.forEach {
            val x = left + (size.width - 2 * left) * it.startMs / durationMs
            drawLine(Color.White, androidx.compose.ui.geometry.Offset(x, center.y - half), androidx.compose.ui.geometry.Offset(x, center.y + half), 2.dp.toPx())
        }
    }

// --- accounts -------------------------------------------------------------------------------------

/** The account's initial in a circle. */
@Composable
private fun Avatar(name: String, modifier: Modifier = Modifier) =
    Box(modifier.size(32.dp).background(MaterialTheme.colorScheme.primaryContainer, androidx.compose.foundation.shape.CircleShape), contentAlignment = Alignment.Center) {
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
    asking?.let { a ->
        PinDialog("PIN for ${a.name.ifEmpty { "this account" }}", { asking = null }) { pin -> p.pinMatches(a, pin).also { if (it) { asking = null; open(a) } } }
    }
    if (settingPin) PinDialog("New PIN", { settingPin = false }) { pin -> p.setPin(p.userId!!, pin); settingPin = false; refresh++; true }
    Column(modifier.fillMaxSize()) {
        Bar(if (picking) "Who's watching?" else "Accounts", pop)
        Column(Modifier.widthIn(max = 720.dp).verticalScroll(rememberScrollState())) {
            accounts.forEach { a ->
                val current = a.userId == p.userId && !picking
                Row(Modifier.fillMaxWidth().focusMemory(a.userId).clickable { if (a.pin != null && !current) asking = a else open(a) }.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Avatar(a.name)
                    Text(a.name.ifEmpty { "Account" } + if (current) " (current)" else "", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    if (a.pin != null) Text("PIN", style = MaterialTheme.typography.labelSmall)
                }
            }
            TextButton(onClick = onAdd, Modifier.padding(horizontal = 8.dp)) { Text("Add account") }
            if (!picking) {
                val me = accounts.firstOrNull { it.userId == p.userId }
                HorizontalDivider()
                Text("Your PIN is asked when switching to your account. It keeps your titles and history from others on this device.",
                    Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall)
                Row(Modifier.padding(horizontal = 8.dp)) {
                    TextButton(onClick = { settingPin = true }) { Text(if (me?.pin == null) "Set PIN" else "Change PIN") }
                    if (me?.pin != null) TextButton(onClick = { p.setPin(me.userId, null); refresh++ }) { Text("Remove PIN") }
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
    fun submit() { if (!onPin(pin)) { wrong = true; pin = "" } }
    // Typing goes straight in, from a keyboard or a TV remote's number keys.
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    AlertDialog(onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(pin, { pin = it.filter(Char::isDigit).take(8); wrong = false }, singleLine = true, isError = wrong,
                label = { Text("PIN") }, supportingText = { Text(if (wrong) "Wrong PIN" else "4 to 8 digits") }, modifier = Modifier.focusRequester(focus),
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (ok) submit() }))
        },
        confirmButton = { TextButton(onClick = ::submit, enabled = ok) { Text("OK") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}
