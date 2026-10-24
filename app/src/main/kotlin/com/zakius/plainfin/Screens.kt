package com.zakius.plainfin

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import org.jellyfin.sdk.api.client.exception.InvalidStatusException
import org.jellyfin.sdk.api.client.exception.TimeoutException
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ImageType
import plainfin.core.PlayState

/** Loads [block] after pushing unsynced progress, so the list never shows state older than this device's. */
@Composable
private fun <T> loadFresh(vararg keys: Any?, block: suspend () -> T): Result<T>? =
    produceState<Result<T>?>(null, *keys) {
        if (value?.isFailure == true) value = null // a retry shows "Loading…" again
        App.server.flush()
        value = runCatching { block() }
            .onFailure { if (it is InvalidStatusException && it.status == 401) App.server.expire() }
    }.value

/** Download statuses, refreshed every second while the screen is shown. */
@Composable
fun rememberDownloadStatuses(): Map<String, Media.DownloadStatus> =
    produceState(emptyMap()) {
        while (true) {
            value = withContext(Dispatchers.IO) { Media.downloadStatuses() }
            delay(1_000)
        }
    }.value

/** Queues downloads and says how many were queued. */
private suspend fun downloadAll(context: Context, episodes: List<BaseItemDto>) {
    val noun = if (episodes.all { it.type == BaseItemKind.MOVIE }) "movie" else "episode"
    val queued = episodes.count { ep ->
        Media.download(ep.id.toString()) == null &&
            runCatching { Media.startDownload(App.server.item(ep.id.toString()), auto = false) }.isSuccess
    }
    val text = when {
        queued == 0 -> "Nothing new to download"
        queued == 1 -> "Downloading 1 $noun"
        else -> "Downloading $queued ${noun}s"
    }
    if (queued > 0) (context as? MainActivity)?.askNotifications()
    Toast.makeText(context, text + (Media.downloadsWaitingFor()?.takeIf { queued > 0 }?.let { " ($it)" } ?: ""), Toast.LENGTH_SHORT).show()
}

/**
 * Fetches episodes on tap and asks before queuing the ones not downloaded or queued yet, with
 * their total size when every file's size is known.
 */
@Composable
private fun DownloadAllButton(label: String, enabled: Boolean, fetch: suspend () -> List<BaseItemDto>) {
    var busy by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf<List<BaseItemDto>?>(null) }
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    TextButton(enabled = enabled && !busy, onClick = {
        busy = true
        scope.launch {
            runCatching { fetch() }
                .onSuccess { eps ->
                    val new = eps.filter { Media.download(it.id.toString()) == null }
                    if (new.isEmpty()) Toast.makeText(ctx, "Nothing new to download", Toast.LENGTH_SHORT).show() else pending = new
                }
                .onFailure { Toast.makeText(ctx, "Couldn't load the episode list", Toast.LENGTH_SHORT).show() }
            busy = false
        }
    }) { Text(if (busy) "Queuing…" else label) }
    pending?.let { eps ->
        val total = eps.mapNotNull { it.mediaSources?.firstOrNull()?.size }.takeIf { it.size == eps.size }?.sum()
        AlertDialog({ pending = null },
            confirmButton = { TextButton(onClick = {
                pending = null
                busy = true
                scope.launch { downloadAll(ctx, eps); busy = false }
            }) { Text("Download") } },
            dismissButton = { TextButton(onClick = { pending = null }) { Text("Cancel") } },
            text = { Text("Download ${eps.size} episode${if (eps.size == 1) "" else "s"}" + (total?.let { ", about ${formatBytes(it)}" } ?: "") + "?") })
    }
}

/** Asks before deleting [item]'s download; shows nothing while [item] is null. */
@Composable
private fun ConfirmDelete(item: BaseItemDto?, onDismiss: () -> Unit, onDeleted: () -> Unit = {}) {
    item ?: return
    AlertDialog(onDismiss,
        confirmButton = { TextButton(onClick = { onDismiss(); Media.deleteDownload(item.id.toString()); onDeleted() }) { Text("Delete") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        text = { Text("Delete download of ${itemLabel(item)}?") })
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

@Composable
private fun loadError(e: Throwable) = when {
    e is InvalidStatusException -> "The server returned an error (${e.status})"
    e is TimeoutException || generateSequence(e) { it.cause }.any { it is IOException } -> "Can't reach the server"
    else -> "Something went wrong loading this"
}

@Composable
private fun <T> Loaded(result: Result<T>?, onRetry: () -> Unit, onDownloads: () -> Unit, content: @Composable (T) -> Unit) {
    when {
        result == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Loading…") }
        result.isFailure -> Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            val e = result.exceptionOrNull()!!
            Text(loadError(e))
            Text(e.message ?: e.javaClass.simpleName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = onRetry) { Text("Retry") }
                TextButton(onClick = onDownloads) { Text("Downloads") }
            }
        }
        else -> content(result.getOrThrow())
    }
}

// --- login --------------------------------------------------------------------------------------

@Composable
fun LoginScreen(modifier: Modifier, onDone: () -> Unit) {
    var url by rememberSaveable { mutableStateOf(App.prefs.baseUrl ?: "https://") }
    var user by rememberSaveable { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Column(modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("plainfin", style = MaterialTheme.typography.headlineMedium)
        if (App.server.sessionExpired) Text("The server ended this session. Log in again; unsynced progress is kept.")
        OutlinedTextField(url, { url = it }, label = { Text("Server URL") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
        OutlinedTextField(user, { user = it }, label = { Text("Username") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(pass, { pass = it }, label = { Text("Password") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Button(enabled = !busy, onClick = {
            busy = true
            scope.launch {
                runCatching { App.server.login(url, user, pass) }
                    .onSuccess { onDone() }
                    .onFailure { error = it.message ?: it.toString() }
                busy = false
            }
        }) { Text("Log in") }
    }
}

// --- browsing -------------------------------------------------------------------------------------

@Composable
fun LibrariesScreen(modifier: Modifier, push: (Screen) -> Unit) {
    var retry by remember { mutableIntStateOf(0) }
    val libs = loadFresh(retry) { App.server.libraries() }
    Column(modifier.fillMaxSize()) {
        Bar("plainfin", null) {
            TextButton(onClick = { push(Screen.Library(null, "Search")) }) { Text("Search") }
            TextButton(onClick = { push(Screen.Downloads) }) { Text("Downloads") }
            IconButton(onClick = { push(Screen.Settings) }) { Icon(Icons.Default.Settings, "Settings") }
        }
        Loaded(libs, { retry++ }, { push(Screen.Downloads) }) { list ->
            LazyColumn {
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
    val items = if (searchOnly && query.isBlank()) Result.success(emptyList())
        else loadFresh(screen.id, sort, unplayed, query, refresh) { App.server.items(screen.id, sort, unplayed, query) }
    Column(modifier.fillMaxSize()) {
        Bar(screen.name, pop)
        OutlinedTextField(search, { search = it }, label = { Text("Search") }, singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp))
        if (!searchOnly) Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Sort.entries.forEach { s -> FilterChip(sort == s, { sort = s }, { Text(s.label) }) }
            FilterChip(unplayed, { unplayed = !unplayed }, { Text("Unplayed") })
        }
        val statuses = rememberDownloadStatuses()
        Loaded(items, { refresh++ }, { push(Screen.Downloads) }) { list ->
            LazyVerticalGrid(GridCells.Adaptive(110.dp), Modifier.fillMaxSize().padding(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(list, key = { it.id }) { item ->
                    if (item.type == BaseItemKind.SERIES) Poster(item, null, onClick = { push(Screen.Series(item.id.toString(), item.name.orEmpty())) })
                    else Poster(item, statuses[item.id.toString()],
                        onClick = { push(Screen.Play(item.id.toString())) },
                        onStartOver = { push(Screen.Play(item.id.toString(), fromStart = true)) },
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
    val (state, unsynced) = item.localState()
    var menu by remember { mutableStateOf(false) }
    Column(Modifier.combinedClickable(onClick = onClick, onLongClick = onChanged?.let { { menu = true } })) {
        Box {
            AsyncImage(App.server.imageUrl(item.id), null, contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxWidth().aspectRatio(2f / 3).background(Color.DarkGray))
            download?.let {
                Text(if (it.fill == Media.Fill.DONE) "↓" else "${it.percent}%", color = Color.White,
                    modifier = Modifier.align(Alignment.TopStart).background(Color(0xAA000000)).padding(horizontal = 6.dp))
            }
            val badge = when {
                state?.played == true -> "✓"
                (item.userData?.unplayedItemCount ?: 0) > 0 -> item.userData!!.unplayedItemCount.toString()
                else -> null
            }
            badge?.let {
                Text(it, color = Color.White, modifier = Modifier.align(Alignment.TopEnd).background(Color(0xAA000000)).padding(horizontal = 6.dp))
            }
            if (unsynced) Text("not synced", color = Color.White, style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.align(Alignment.BottomStart).background(Color(0xAA000000)))
            onChanged?.let { ItemMenu(item, menu, { menu = false }, download != null, onStartOver, it) }
        }
        ProgressBar(item, state)
        Text(item.name.orEmpty(), maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
fun SeriesScreen(modifier: Modifier, screen: Screen.Series, push: (Screen) -> Unit, pop: () -> Unit) {
    var retry by remember { mutableIntStateOf(0) }
    val seasons = loadFresh(screen.id, retry) { App.server.seasons(screen.id) }
    var keepNext by remember { mutableIntStateOf(App.prefs.keepNext(screen.id)) }
    val ctx = LocalContext.current
    Column(modifier.fillMaxSize()) {
        Bar(screen.name, pop) {
            DownloadAllButton("Download all", true) { App.server.episodes(screen.id, sources = true).filter { it.parentIndexNumber != 0 } }
        }
        Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Keep next episodes downloaded:", Modifier.weight(1f))
            TextButton(onClick = { keepNext = (keepNext - 1).coerceAtLeast(0); App.prefs.setKeepNext(screen.id, keepNext) }) { Text("−") }
            Text(if (keepNext == 0) "off" else "$keepNext")
            TextButton(onClick = {
                keepNext = (keepNext + 1).coerceAtMost(20); App.prefs.setKeepNext(screen.id, keepNext); Housekeeping.enqueue(ctx)
            }) { Text("+") }
        }
        Loaded(seasons, { retry++ }, { push(Screen.Downloads) }) { list ->
            LazyColumn {
                items(list, key = { it.id }) { season ->
                    Row(Modifier.fillMaxWidth().combinedClickable(onClick = {
                        push(Screen.Season(screen.id, season.id.toString(), "${screen.name} · ${season.name}"))
                    }).padding(16.dp)) {
                        Text(season.name.orEmpty(), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                        val left = season.userData?.unplayedItemCount ?: 0
                        Text(if (left == 0) "✓" else "$left unplayed")
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
    val episodes = loadFresh(screen.seasonId, refresh) { App.server.episodes(screen.seriesId, screen.seasonId) }
    val statuses = rememberDownloadStatuses()
    val listState = rememberLazyListState() // outside Loaded, so a refresh after "Mark played" keeps the scroll
    Column(modifier.fillMaxSize()) {
        Bar(screen.name, pop) {
            DownloadAllButton("Download season", episodes?.isSuccess == true) { App.server.episodes(screen.seriesId, screen.seasonId, sources = true) }
        }
        Loaded(episodes, { refresh++ }, { push(Screen.Downloads) }) { list ->
            LazyColumn(state = listState) {
                items(list, key = { it.id }) { ep ->
                    EpisodeRow(
                        ep,
                        statuses[ep.id.toString()],
                        onPlay = { push(Screen.Play(ep.id.toString())) },
                        onStartOver = { push(Screen.Play(ep.id.toString(), fromStart = true)) },
                        onChanged = { refresh++ },
                    )
                }
            }
        }
    }
}

/** Thumbnail, title, progress and download state. Long-press opens the item's actions. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun EpisodeRow(
    ep: BaseItemDto,
    download: Media.DownloadStatus?,
    highlighted: Boolean = false,
    onPlay: () -> Unit,
    onStartOver: (() -> Unit)? = null,
    onChanged: () -> Unit = {},
) {
    val (state, unsynced) = ep.localState()
    var menu by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier.fillMaxWidth()
                .background(if (highlighted) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
                .combinedClickable(onClick = onPlay, onLongClick = { menu = true })
                .padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.width(140.dp)) {
                AsyncImage(App.server.imageUrl(ep.id, ImageType.PRIMARY, 320), null, contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9).background(Color.DarkGray))
                ProgressBar(ep, state)
            }
            Column(Modifier.weight(1f)) {
                Text("${ep.indexNumber ?: ""}. ${ep.name.orEmpty()}", style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                val bits = listOfNotNull(
                    ep.runTimeTicks?.let { formatMs(it / 10_000) },
                    "✓ played".takeIf { state?.played == true },
                    download?.label,
                    "not synced".takeIf { unsynced },
                )
                Text(bits.joinToString(" · "), style = MaterialTheme.typography.labelSmall)
            }
        }
        ItemMenu(ep, menu, { menu = false }, download != null, onStartOver, onChanged)
    }
}

@Composable
private fun ProgressBar(item: BaseItemDto, state: PlayState?) {
    val runtime = item.runTimeTicks ?: 0
    if (state != null && state.positionTicks > 0 && runtime > 0) {
        LinearProgressIndicator({ state.positionTicks.toFloat() / runtime }, Modifier.fillMaxWidth())
    }
}

/** Long-press actions of an episode or movie. */
@Composable
private fun ItemMenu(item: BaseItemDto, expanded: Boolean, onDismiss: () -> Unit, downloaded: Boolean, onStartOver: (() -> Unit)?, onChanged: () -> Unit) {
    val id = item.id.toString()
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var confirm by remember { mutableStateOf(false) }
    ConfirmDelete(item.takeIf { confirm }, { confirm = false }, onChanged)
    DropdownMenu(expanded, onDismiss) {
        onStartOver?.let { DropdownMenuItem({ Text("Start over") }, { onDismiss(); it() }) }
        val played = item.localState().first?.played == true
        DropdownMenuItem({ Text(if (played) "Mark unplayed" else "Mark played") }, {
            onDismiss()
            scope.launch { App.server.setPlayed(id, !played); onChanged() }
        })
        if (downloaded) DropdownMenuItem({ Text("Delete download") }, { onDismiss(); confirm = true })
        else DropdownMenuItem({ Text("Download") }, {
            onDismiss()
            scope.launch { downloadAll(ctx, listOf(item)) }
        })
    }
}

// --- downloads ------------------------------------------------------------------------------------

@Composable
fun DownloadsScreen(modifier: Modifier, push: (Screen) -> Unit, pop: () -> Unit) {
    val statuses = rememberDownloadStatuses()
    // (group, download): unfinished first in queue order, then finished by series.
    val downloads = remember(statuses.mapValues { it.value.fill }) {
        val (done, queue) = Media.downloads().partition { Media.isComplete(Media.key(it.item)) }
        queue.sortedWith(Media.queueOrder).map { "Queue" to it } +
            done.sortedWith(compareBy({ it.item.seriesName ?: it.item.name }, { it.item.parentIndexNumber }, { it.item.indexNumber }))
                .map { (it.item.seriesName ?: "Movies") to it }
    }
    val storage by produceState("", statuses) {
        val (buffered, free) = withContext(Dispatchers.IO) { Media.bufferedBytes() to Media.freeBytes() }
        value = "Downloads ${formatBytes(statuses.values.sumOf { it.bytes })} · buffer ${formatBytes(buffered)} of ${App.prefs.cacheLimitGb} GB · ${formatBytes(free)} free"
    }
    val scope = rememberCoroutineScope()
    var deleting by remember { mutableStateOf<BaseItemDto?>(null) }
    ConfirmDelete(deleting, { deleting = null })
    Column(modifier.fillMaxSize()) {
        Bar("Downloads", pop)
        Text(storage, Modifier.padding(horizontal = 16.dp))
        if (downloads.isEmpty()) Text("Nothing downloaded. Long-press an episode or movie, or use Download season.", Modifier.padding(16.dp))
        LazyColumn {
            var lastGroup: String? = null
            for ((group, d) in downloads) {
                if (group != lastGroup) {
                    lastGroup = group
                    item(key = "h-$group") { Text(group, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(16.dp, 12.dp, 16.dp, 4.dp)) }
                }
                item(key = d.item.id) {
                    val id = d.item.id.toString()
                    val status = statuses[id]
                    val done = status?.fill == Media.Fill.DONE
                    Row(Modifier.fillMaxWidth().combinedClickable(onClick = { push(Screen.Play(id)) }).padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(itemLabel(d.item))
                            val size = status?.bytes?.takeIf { it > 0 }?.let { " · ${formatBytes(it)}" } ?: ""
                            Text((status?.label ?: "") + size + if (d.auto) " · kept by keep-next" else "", style = MaterialTheme.typography.labelSmall)
                            if (status != null && !done) LinearProgressIndicator({ status.percent / 100f }, Modifier.fillMaxWidth().padding(top = 4.dp))
                        }
                        when (status?.fill) {
                            Media.Fill.RUNNING, Media.Fill.QUEUED -> TextButton(onClick = { Media.pauseDownload(id) }) { Text("Pause") }
                            Media.Fill.PAUSED -> TextButton(onClick = {
                                scope.launch { Media.startDownload(runCatching { App.server.item(id) }.getOrDefault(d.item), d.auto) }
                            }) { Text("Resume") }
                            else -> {}
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
    var version by remember { mutableIntStateOf(0) } // bumps to redraw after a pref changes
    @Composable
    fun toggle(label: String, value: Boolean, set: (Boolean) -> Unit) = Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(value, { set(it); version++ })
    }
    @Composable
    fun stepper(label: String, value: Int, range: IntRange, step: Int, set: (Int) -> Unit) = Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        TextButton(onClick = { set((value - step).coerceIn(range)); version++ }) { Text("−") }
        Text("$value")
        TextButton(onClick = { set((value + step).coerceIn(range)); version++ }) { Text("+") }
    }
    Column(modifier.fillMaxSize()) {
        Bar("Settings", pop)
        LazyColumn {
            item(version) {
                Column {
                    Text("Network", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(16.dp))
                    toggle("Buffer to end on metered networks", p.bufferOnMetered) { p.bufferOnMetered = it }
                    toggle("Download on metered networks", p.downloadOnMetered) { p.downloadOnMetered = it; Media.kickDownloads(restart = true) }
                    stepper("Buffer cache limit (GB)", p.cacheLimitGb, 1..100, 1) { p.cacheLimitGb = it }
                    val roots = Media.storageRoots()
                    if (roots.size > 1) {
                        toggle("Store on SD card (after restart; existing downloads stay where they are)", p.storageIndex > 0) {
                            p.storageIndex = if (it) 1 else 0
                        }
                    }

                    Text("Gestures", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(16.dp))
                    toggle("Brightness / volume swipes", p.gestureLevels) { p.gestureLevels = it }
                    toggle("Brightness on the left (volume on the right)", p.brightnessOnLeft) { p.brightnessOnLeft = it }
                    toggle("Horizontal swipe seeks", p.gestureSeek) { p.gestureSeek = it }
                    toggle("Double-tap skips", p.gestureDoubleTap) { p.gestureDoubleTap = it }
                    stepper("Double-tap skip (s)", p.doubleTapSeconds, 5..60, 5) { p.doubleTapSeconds = it }
                    toggle("Pinch to crop / fit", p.gesturePinch) { p.gesturePinch = it }
                    toggle("Swipe up / down for fullscreen", p.gestureFullscreen) { p.gestureFullscreen = it }

                    Text("Account", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(16.dp))
                    Text(p.baseUrl.orEmpty(), Modifier.padding(horizontal = 16.dp))
                    TextButton(onClick = { App.server.logout(); onLoggedOut() }, Modifier.padding(horizontal = 8.dp)) { Text("Log out") }
                    Spacer(Modifier.padding(24.dp))
                }
            }
        }
    }
}
