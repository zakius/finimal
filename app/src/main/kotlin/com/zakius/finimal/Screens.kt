package com.zakius.finimal

import finimal.core.writeAtomically
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import java.io.File
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.DisposableEffect
import android.os.Looper
import android.os.Handler
import android.net.Network
import android.net.ConnectivityManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import kotlinx.coroutines.CancellationException
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
    private val dir get() = File(App.instance.cacheDir, "lists")
    private fun file(key: List<Any?>) = File(dir, MessageDigest.getInstance("SHA-1").digest(key.joinToString("\u0000").toByteArray())
        .joinToString("") { "%02x".format(it) } + ".json")

    fun read(key: List<Any?>): List<BaseItemDto>? = runCatching { json.decodeFromString<List<BaseItemDto>>(file(key).readText()) }.getOrNull()
    fun write(key: List<Any?>, list: List<BaseItemDto>) { runCatching { dir.mkdirs(); file(key).writeAtomically(json.encodeToString(list)) } }
    fun clear() { dir.deleteRecursively() }
}

/** A list, and why the refresh behind it failed when it is an older copy. */
class Load(val result: Result<List<BaseItemDto>>?, val refreshError: Throwable?)

/**
 * Loads [block] after pushing unsynced progress, so the list never shows state older than this device's.
 * Until the load ends, the list from the last visit shows (from memory, else from disk when [persist]);
 * unsynced local state is drawn over it. A failed load keeps that list and says why above it.
 */
@Composable
private fun loadFresh(vararg keys: Any?, refresh: Int, persist: Boolean = true, block: suspend () -> List<BaseItemDto>): Load {
    val k = keys.toList()
    val cacheKey = listOf(App.prefs.baseUrl, App.prefs.userId) + k
    var last by remember { mutableStateOf(lastGood[cacheKey]?.let { k to Result.success(it) }) }
    var refreshError by remember { mutableStateOf<Throwable?>(null) }
    LaunchedEffect(k, refresh) {
        refreshError = null
        if (last?.second?.isFailure == true) last = null // a retry shows "Loading…" again
        if (last?.first != k) (lastGood[cacheKey] ?: if (persist) withContext(Dispatchers.IO) { ListSnapshots.read(cacheKey) } else null)
            ?.let { last = k to Result.success(it) }
        val pushed = App.server.flush()
        // An unreachable server would only time out a second time on the fetch.
        val result = if (pushed != null && unreachable(pushed)) Result.failure(pushed) else runCatching { block() }
            .onFailure { if (it is InvalidStatusException && it.status == 401) App.server.expire() }
        val shown = last
        when {
            result.isSuccess -> {
                val list = result.getOrThrow()
                lastGood[cacheKey] = list
                if (persist) launch(Dispatchers.IO) { ListSnapshots.write(cacheKey, list) }
                last = k to result
            }
            shown != null && shown.first == k && shown.second.isSuccess -> refreshError = result.exceptionOrNull()
            else -> last = k to result
        }
    }
    return Load(last?.second, refreshError)
}

/**
 * Download statuses while the screen is shown, null until first read. Re-read on every download
 * change, and polled every second while one runs, else every 5 s.
 */
@Composable
fun rememberDownloadStatuses(): Map<String, Media.DownloadStatus>? =
    produceState<Map<String, Media.DownloadStatus>?>(null, Media.changes) {
        while (true) {
            value = withContext(Dispatchers.IO) { Media.downloadStatuses() }
            delay(if (value.orEmpty().values.any { it.fill == Media.Fill.RUNNING }) 1_000 else 5_000)
        }
    }.value

/** Queues downloads and says how many were queued. */
private suspend fun downloadAll(context: Context, episodes: List<BaseItemDto>) {
    val noun = if (episodes.all { it.type == BaseItemKind.MOVIE }) "movie" else "episode"
    var queued = 0
    val failures = mutableListOf<Throwable>()
    withContext(Dispatchers.IO) {
        for (ep in episodes) {
            if (Media.download(ep.id.toString()) != null) continue
            // Episodes fetched with sources queue as local writes, so leaving the screen can't stop it partway.
            runCatching { Media.startDownload(ep.takeIf { it.mediaSources != null } ?: App.server.item(ep.id.toString()), auto = false) }
                .onSuccess { queued++ }.onFailure { if (it is CancellationException) throw it; failures += it }
        }
    }
    val text = when {
        queued == 0 -> failures.firstOrNull()?.let(::loadError) ?: "Nothing new to download"
        queued == 1 -> "Downloading 1 $noun"
        else -> "Downloading $queued ${noun}s"
    } + if (queued > 0 && failures.isNotEmpty()) ", ${failures.size} failed" else ""
    if (queued > 0) (context as? MainActivity)?.askNotifications()
    Toast.makeText(context, text + (Media.downloadsWaitingFor()?.takeIf { queued > 0 }?.let { " ($it)" } ?: ""), Toast.LENGTH_SHORT).show()
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
    val ctx = LocalContext.current
    pending?.let { eps ->
        val total = eps.mapNotNull { it.mediaSources?.firstOrNull()?.size }.takeIf { it.size == eps.size }?.sum()
        Confirm("Download ${eps.size} episode${if (eps.size == 1) "" else "s"}" + (total?.let { ", about ${formatBytes(it)}" } ?: "") + "?", "Download", {
            busy = true
            scope.launch { downloadAll(ctx, eps); busy = false }
        }, { pending = null })
    }
    return action@{
        if (busy) return@action
        busy = true
        scope.launch {
            runCatching { fetch() }
                .onSuccess { eps ->
                    val new = withContext(Dispatchers.IO) { eps.filter { Media.download(it.id.toString()) == null } }
                    if (new.isEmpty()) Toast.makeText(ctx, "Nothing new to download", Toast.LENGTH_SHORT).show() else pending = new
                }
                .onFailure { if (it is CancellationException) throw it; Toast.makeText(ctx, loadError(it), Toast.LENGTH_SHORT).show() }
            busy = false
        }
    }
}

/** A ⋮ button in the top bar, so actions don't push a long title off it. */
@Composable
private fun BarMenu(content: @Composable (close: () -> Unit) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Default.MoreVert, "More options") }
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
        scope.launch { withContext(Dispatchers.IO) { Media.deleteDownload(item.id.toString()) }; onDeleted() }
    }, onDismiss)
}

fun itemLabel(item: BaseItemDto) = if (item.type == BaseItemKind.EPISODE) episodeLabel(item) else item.name.orEmpty()

fun formatBytes(b: Long) = if (b >= 1e9) "%.1f GB".format(b / 1e9) else "%.0f MB".format(b / 1e6)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Bar(title: String, onBack: (() -> Unit)?, actions: @Composable () -> Unit = {}) = TopAppBar(
    title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
    navigationIcon = { onBack?.let { IconButton(onClick = it) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } } },
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

/**
 * Retries once a different network comes up (back on wifi, out of a dead zone). The network that
 * was already up is skipped: the system reports it at once, and retrying on it would only fail again.
 */
@Composable
private fun RetryOnNewNetwork(onRetry: () -> Unit) {
    val context = LocalContext.current
    val retry by rememberUpdatedState(onRetry)
    DisposableEffect(Unit) {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val initial = cm.activeNetwork
        val main = Handler(Looper.getMainLooper())
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { if (network != initial) main.post { retry() } }
        }
        cm.registerDefaultNetworkCallback(callback)
        onDispose { cm.unregisterNetworkCallback(callback) }
    }
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
                TextButton(onClick = onDownloads) { Text("Downloads") }
            }
        }
        else -> Column(Modifier.fillMaxSize()) {
            load.refreshError?.let { e ->
                if (unreachable(e)) RetryOnNewNetwork(onRetry)
                Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).padding(start = 16.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text("${loadError(e)}. Showing the last list.", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = onRetry) { Text("Retry") }
                }
            }
            content(result.getOrThrow())
        }
    }
}

/** Centred note for a list with nothing in it. */
@Composable
private fun Empty(text: String) = Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) { Text(text) }

// --- login --------------------------------------------------------------------------------------

@Composable
fun LoginScreen(modifier: Modifier, onDone: () -> Unit) {
    var url by rememberSaveable { mutableStateOf(App.prefs.baseUrl.orEmpty()) }
    var user by rememberSaveable { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<Throwable?>(null) }
    var busy by remember { mutableStateOf(false) }
    var showPass by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    fun submit() {
        if (busy) return
        busy = true
        scope.launch {
            runCatching { App.server.login(url, user, pass) }
                .onSuccess { onDone() }
                .onFailure { error = it }
            busy = false
        }
    }
    // The padded modifier already includes the IME inset; scrolling keeps the button reachable above it.
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("finimal", style = MaterialTheme.typography.headlineMedium)
        if (App.server.sessionExpired) Text("The server ended this session. Log in again; unsynced progress is kept.")
        OutlinedTextField(url, { url = it; error = null }, label = { Text("Server URL") }, placeholder = { Text("jellyfin.example.com:8096") }, singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next))
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
            else ErrorText(loadError(e) + if (unreachable(e)) " at ${url.trim()}" else "", e.message ?: e.javaClass.simpleName, MaterialTheme.colorScheme.error)
        }
        Button(enabled = !busy, onClick = ::submit) {
            if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text("Log in")
        }
    }
}

// --- browsing -------------------------------------------------------------------------------------

@Composable
fun LibrariesScreen(modifier: Modifier, push: (Screen) -> Unit) {
    var refresh by remember { mutableIntStateOf(0) }
    val libs = loadFresh(refresh = refresh) { App.server.libraries() }
    Column(modifier.fillMaxSize()) {
        Bar("finimal", null) {
            TextButton(onClick = { push(Screen.Library(null, "Search")) }) { Text("Search") }
            TextButton(onClick = { push(Screen.Downloads) }) { Text("Downloads") }
            IconButton(onClick = { push(Screen.Settings) }) { Icon(Icons.Default.Settings, "Settings") }
        }
        Loaded(libs, { refresh++ }, { push(Screen.Downloads) }) { list ->
            if (list.isEmpty()) Empty("No movie or TV libraries on this server")
            else LazyColumn {
                items(list, key = { it.id }) { lib ->
                    Text(lib.name.orEmpty(), style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.fillMaxWidth().combinedClickable(onClick = { push(Screen.Library(lib.id.toString(), lib.name.orEmpty())) }).padding(20.dp))
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
            Sort.entries.forEach { s -> FilterChip(sort == s, { sort = s }, { Text(s.label) }) }
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
            else LazyVerticalGrid(GridCells.Adaptive(110.dp), Modifier.fillMaxSize().padding(8.dp), gridState,
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
    download: Media.DownloadStatus?,
    onClick: () -> Unit,
    onStartOver: (() -> Unit)? = null,
    onChanged: (() -> Unit)? = null,
) {
    // Derived: a local change then recomposes only the row whose state it changed, not every row.
    val local by remember(item) { derivedStateOf { item.localState() } }
    val (state, unsynced) = local
    var menu by remember { mutableStateOf(false) }
    Column(Modifier.combinedClickable(onClick = onClick, onLongClickLabel = "Actions", onLongClick = onChanged?.let { { menu = true } })) {
        Box {
            AsyncImage(App.server.imageUrl(item), null, contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxWidth().aspectRatio(2f / 3).background(Color.DarkGray))
            download?.let {
                Text(if (it.fill == Media.Fill.DONE) "↓" else "${it.percent}%", color = Color.White,
                    modifier = Modifier.align(Alignment.TopStart).background(Color(0xAA000000)).padding(horizontal = 6.dp)
                        .semantics { contentDescription = if (it.fill == Media.Fill.DONE) "Downloaded" else "Downloaded ${it.percent}%" })
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
                modifier = Modifier.align(Alignment.BottomStart).background(Color(0xAA000000)))
            onChanged?.let { ItemMenu(item, state?.played == true, unsynced, menu, { menu = false }, download != null, onStartOver, it) }
        }
        ProgressBar(item, state)
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
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val downloadAll = downloadAllAction { App.server.episodes(screen.id, sources = true).filter { it.parentIndexNumber != 0 } }
    val markAll = markAllAction { refresh++ }
    val all = episodes.result?.getOrNull()
    val onlySeason = seasons.result?.getOrNull()?.singleOrNull()
    val shownEpisodes = onlySeason?.let { s -> all?.filter { it.seasonId == s.id } }
    Column(modifier.fillMaxSize()) {
        Bar(screen.name, pop) {
            BarMenu { close ->
                DropdownMenuItem({ Text("Download all") }, { close(); downloadAll() })
                shownEpisodes?.let { eps ->
                    val played = eps.all { it.localState().first?.played == true }
                    DropdownMenuItem({ Text(if (played) "Mark all unplayed" else "Mark all played") }, { close(); markAll(eps) })
                }
            }
        }
        all?.let(::upNext)?.let { ep -> ContinueButton(ep) { push(Screen.Play(ep.id.toString())) } }
        Stepper("Keep next episodes downloaded:", keepNext, (0..20).toList(), { if (it == 0) "off" else "$it" }) { n ->
            if (keepNext == 0) (ctx as? MainActivity)?.askNotifications()
            keepNext = n
            App.prefs.setKeepNext(screen.id, n)
            scope.launch(Dispatchers.IO) {
                if (n == 0) Media.releaseKeepNext(screen.id)
                Housekeeping.enqueue(ctx)
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
                    Row(Modifier.fillMaxWidth().combinedClickable(onClick = {
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
                DropdownMenuItem({ Text("Download season") }, { close(); downloadAll() })
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
        Icon(Icons.Default.PlayArrow, null)
        Text((if (position > 0) "Continue " else "Play ") + episodeLabel(ep) + (left?.let { " · ${it.coerceAtLeast(1)} min left" } ?: ""),
            Modifier.padding(start = 8.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Thumbnail, title, progress and download state. The ⋮ button or a long-press opens the item's actions. */
@Composable
fun EpisodeRow(
    ep: BaseItemDto,
    download: Media.DownloadStatus?,
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
            .combinedClickable(onClick = onPlay, onLongClickLabel = "Actions", onLongClick = { menu = true })
            .padding(8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.width(140.dp)) {
            AsyncImage(App.server.imageUrl(ep, ImageType.PRIMARY, 320), null, contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9).background(Color.DarkGray))
            ProgressBar(ep, state)
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
            IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "More options") }
            ItemMenu(ep, state?.played == true, unsynced, menu, { menu = false }, download != null, onStartOver, onChanged)
        }
    }
}

@Composable
private fun ProgressBar(item: BaseItemDto, state: PlayState?) {
    val runtime = item.runTimeTicks ?: 0
    if (state != null && state.positionTicks > 0 && runtime > 0) {
        LinearProgressIndicator({ state.positionTicks.toFloat() / runtime }, Modifier.fillMaxWidth())
    }
}

/** Actions of an episode or movie. */
@Composable
private fun ItemMenu(item: BaseItemDto, played: Boolean, unsynced: Boolean, expanded: Boolean, onDismiss: () -> Unit, downloaded: Boolean, onStartOver: (() -> Unit)?, onChanged: () -> Unit) {
    val id = item.id.toString()
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var confirm by remember { mutableStateOf(false) }
    ConfirmDelete(item.takeIf { confirm }, { confirm = false }, onChanged)
    DropdownMenu(expanded, onDismiss) {
        onStartOver?.let { DropdownMenuItem({ Text("Start over") }, { onDismiss(); it() }) }
        DropdownMenuItem({ Text(if (played) "Mark unplayed" else "Mark played") }, {
            onDismiss()
            scope.launch { App.server.setPlayed(listOf(id), !played); onChanged() }
        })
        if (downloaded) DropdownMenuItem({ Text("Delete download") }, { onDismiss(); confirm = true })
        else DropdownMenuItem({ Text("Download") }, {
            onDismiss()
            scope.launch { downloadAll(ctx, listOf(item)) }
        })
        if (unsynced) {
            HorizontalDivider()
            DropdownMenuItem({ Text("Progress not synced yet") }, {}, enabled = false)
            DropdownMenuItem({ Text("Sync now") }, {
                onDismiss()
                scope.launch {
                    val failure = App.server.flush()
                    Toast.makeText(ctx, failure?.let(::loadError) ?: "Synced", Toast.LENGTH_SHORT).show()
                    onChanged()
                }
            })
        }
    }
}

// --- downloads ------------------------------------------------------------------------------------

@Composable
fun DownloadsScreen(modifier: Modifier, push: (Screen) -> Unit, pop: () -> Unit) {
    val statuses = rememberDownloadStatuses()
    // (header key, header, status): unfinished first in queue order, then finished by series, movies last.
    val downloads = remember(statuses) {
        val (done, queue) = statuses.orEmpty().values.partition { it.fill == Media.Fill.DONE }
        queue.sortedWith(compareBy(Media.queueOrder) { it.download }).map { Triple("h-queue", "Queue", it) } +
            done.sortedWith(compareBy(Media.shelfOrder) { it.download })
                .map { s -> s.download.item.seriesId?.let { Triple("h-$it", s.download.item.seriesName.orEmpty(), s) } ?: Triple("h-movies", "Movies", s) }
    }
    val storage by produceState("", statuses) {
        val (buffered, free) = withContext(Dispatchers.IO) { Media.bufferedBytes() to Media.freeBytes() }
        value = "Downloads ${formatBytes(statuses.orEmpty().values.sumOf { it.bytes })} · buffer ${formatBytes(buffered)} of ${App.prefs.cacheLimitGb} GB · ${formatBytes(free)} free"
    }
    val scope = rememberCoroutineScope()
    var deleting by remember { mutableStateOf<BaseItemDto?>(null) }
    ConfirmDelete(deleting, { deleting = null })
    Column(modifier.fillMaxSize()) {
        Bar("Downloads", pop)
        Text(storage, Modifier.padding(horizontal = 16.dp))
        if (Media.storageMissing) Text("SD card not available. Downloads wait for it; restart the app after inserting it.",
            Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.error)
        if (statuses != null && downloads.isEmpty()) Empty("Nothing downloaded. Use ⋮ on an episode or a series, or long-press a movie.")
        else LazyColumn {
            var lastGroup: String? = null
            for ((group, title, status) in downloads) {
                if (group != lastGroup) {
                    lastGroup = group
                    val seriesId = status.download.item.seriesId?.takeIf { status.fill == Media.Fill.DONE }
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
                    Row(Modifier.fillMaxWidth().combinedClickable(onClick = { push(Screen.Play(id)) }).padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(itemLabel(d.item))
                            val size = status.bytes.takeIf { it > 0 }?.let { " · ${formatBytes(it)}" } ?: ""
                            Text(listOfNotNull(status.label + size, "kept by keep-next".takeIf { d.auto }, "✓ played".takeIf { state?.played == true },
                                "not synced".takeIf { unsynced }).joinToString(" · "), style = MaterialTheme.typography.labelSmall)
                            if (status.fill != Media.Fill.DONE) LinearProgressIndicator({ status.percent / 100f }, Modifier.fillMaxWidth().padding(top = 4.dp))
                            else ProgressBar(d.item, state)
                        }
                        when (status.fill) {
                            Media.Fill.RUNNING, Media.Fill.QUEUED -> TextButton(onClick = { scope.launch(Dispatchers.IO) { Media.pauseDownload(id) } }) { Text("Pause") }
                            Media.Fill.PAUSED -> TextButton(onClick = {
                                scope.launch(Dispatchers.IO) { Media.startDownload(runCatching { App.server.item(id) }.getOrDefault(d.item), d.auto) }
                            }) { Text(if (d.failed) "Retry" else "Resume") }
                            Media.Fill.DONE -> {}
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
fun SettingsScreen(modifier: Modifier, pop: () -> Unit, onLoggedOut: () -> Unit) {
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
        val count = remember { Media.downloads().size }
        val where = if (target > 0) "the SD card" else "internal storage"
        Confirm("Store on $where? This " + (if (count == 0) "" else "deletes your $count download${if (count == 1) "" else "s"} and ") +
            "clears the buffer cache; nothing is moved.", "Switch", {
            moving = true
            scope.launch { withContext(Dispatchers.IO) { Media.switchStorage(target) }; moving = false; refresh++ }
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
        Column(Modifier.verticalScroll(rememberScrollState())) {
            key(refresh) {
                Text("Network", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(16.dp))
                toggle("Buffer to end on metered networks", p.bufferOnMetered) { p.bufferOnMetered = it }
                toggle("Download on metered networks", p.downloadOnMetered) { p.downloadOnMetered = it; Media.kickDownloads(restart = true) }
                Stepper("Buffer cache limit (GB)", p.cacheLimitGb, listOf(1, 2, 5, 10, 20, 50, 100)) { p.cacheLimitGb = it; refresh++ }
                val roots = Media.storageRoots()
                if (roots.size > 1 || Media.storageMissing) { // missing: switching back is the way out of a lost card
                    toggle("Store on SD card (switching deletes downloads and the buffer)", p.storageIndex > 0, enabled = !moving) {
                        moveTo = if (it) 1 else 0
                    }
                }

                Text("Playback", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(16.dp))
                Stepper("Wireless audio delay (ms), for Bluetooth headphones and speakers", p.audioDelayMs, AUDIO_DELAYS_MS) { AudioDelay.set(it); refresh++ }

                Text("Gestures", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(16.dp))
                toggle("Brightness / volume swipes", p.gestureLevels) { p.gestureLevels = it }
                toggle("Brightness on the left (volume on the right)", p.brightnessOnLeft, enabled = p.gestureLevels) { p.brightnessOnLeft = it }
                toggle("Horizontal swipe seeks", p.gestureSeek) { p.gestureSeek = it }
                toggle("Double-tap skips", p.gestureDoubleTap) { p.gestureDoubleTap = it }
                toggle("Hold for 2× speed", p.gestureHold) { p.gestureHold = it }
                Stepper("Skip step (s)", p.doubleTapSeconds, (5..60 step 5).toList()) { p.doubleTapSeconds = it; refresh++ }
                toggle("Pinch to crop / fit", p.gesturePinch) { p.gesturePinch = it }
                toggle("Swipe up / down for fullscreen", p.gestureFullscreen) { p.gestureFullscreen = it }

                Text("Account", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(16.dp))
                Text(p.baseUrl.orEmpty(), Modifier.padding(horizontal = 16.dp))
                TextButton(onClick = { confirmLogout = true }, Modifier.padding(horizontal = 8.dp)) { Text("Log out") }
                Text("finimal ${BuildConfig.VERSION_NAME}", Modifier.padding(16.dp), style = MaterialTheme.typography.labelSmall)
                Spacer(Modifier.padding(24.dp))
            }
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
        Text(show(options[i]))
        TextButton(onClick = { set(options[i + 1]) }, enabled = i < options.lastIndex) { Text("+", Modifier.semantics { contentDescription = "Increase $label, now ${show(options[i])}" }) }
    }
