package com.zakius.plainfin

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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Settings
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ImageType

/** Loads [block] after pushing unsynced progress, so the list never shows state older than this device's. */
@Composable
private fun <T> loadFresh(vararg keys: Any?, block: suspend () -> T): Result<T>? =
    produceState<Result<T>?>(null, *keys) {
        App.server.flush()
        value = runCatching { block() }
    }.value

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Bar(title: String, onBack: (() -> Unit)?, actions: @Composable () -> Unit = {}) = TopAppBar(
    title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
    navigationIcon = { onBack?.let { IconButton(onClick = it) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } } },
    actions = { actions() },
)

@Composable
private fun <T> Loaded(result: Result<T>?, onDownloads: (() -> Unit)? = null, content: @Composable (T) -> Unit) {
    when {
        result == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Loading…") }
        result.isFailure -> Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Server unreachable: ${result.exceptionOrNull()?.message}")
            onDownloads?.let { Button(onClick = it, modifier = Modifier.padding(top = 12.dp)) { Text("Downloads") } }
        }
        else -> content(result.getOrThrow())
    }
}

// --- login --------------------------------------------------------------------------------------

@Composable
fun LoginScreen(modifier: Modifier, onDone: () -> Unit) {
    var url by remember { mutableStateOf(App.prefs.baseUrl ?: "https://") }
    var user by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Column(modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("plainfin", style = MaterialTheme.typography.headlineMedium)
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
    val libs = loadFresh { App.server.libraries() }
    Column(modifier.fillMaxSize()) {
        Bar("plainfin", null) {
            TextButton(onClick = { push(Screen.Library(null, "Search")) }) { Text("Search") }
            TextButton(onClick = { push(Screen.Downloads) }) { Text("Downloads") }
            IconButton(onClick = { push(Screen.Settings) }) { Icon(Icons.Default.Settings, "Settings") }
        }
        Loaded(libs, onDownloads = { push(Screen.Downloads) }) { list ->
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
    var sort by remember { mutableStateOf(Sort.NAME) }
    var unplayed by remember { mutableStateOf(false) }
    var search by remember { mutableStateOf("") }
    var query by remember { mutableStateOf("") }
    LaunchedEffect(search) { delay(400); query = search }
    val searchOnly = screen.id == null
    val items = if (searchOnly && query.isBlank()) Result.success(emptyList())
        else loadFresh(screen.id, sort, unplayed, query) { App.server.items(screen.id, sort, unplayed, query) }
    Column(modifier.fillMaxSize()) {
        Bar(screen.name, pop)
        OutlinedTextField(search, { search = it }, label = { Text("Search") }, singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp))
        if (!searchOnly) Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Sort.entries.forEach { s -> FilterChip(sort == s, { sort = s }, { Text(s.label) }) }
            FilterChip(unplayed, { unplayed = !unplayed }, { Text("Unplayed") })
        }
        Loaded(items) { list ->
            LazyVerticalGrid(GridCells.Adaptive(110.dp), Modifier.fillMaxSize().padding(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(list, key = { it.id }) { item ->
                    Poster(item) {
                        if (item.type == BaseItemKind.SERIES) push(Screen.Series(item.id.toString(), item.name.orEmpty()))
                        else push(Screen.Play(item.id.toString()))
                    }
                }
            }
        }
    }
}

@Composable
private fun Poster(item: BaseItemDto, onClick: () -> Unit) {
    val (state, unsynced) = item.localState()
    Column(Modifier.combinedClickable(onClick = onClick)) {
        Box {
            AsyncImage(App.server.imageUrl(item.id), null, contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxWidth().aspectRatio(2f / 3).background(Color.DarkGray))
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
        }
        Text(item.name.orEmpty(), maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
fun SeriesScreen(modifier: Modifier, screen: Screen.Series, push: (Screen) -> Unit, pop: () -> Unit) {
    val seasons = loadFresh(screen.id) { App.server.seasons(screen.id) }
    var keepNext by remember { mutableIntStateOf(App.prefs.keepNext(screen.id)) }
    var downloading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    Column(modifier.fillMaxSize()) {
        Bar(screen.name, pop) {
            TextButton(enabled = !downloading, onClick = {
                downloading = true
                scope.launch { downloadAll(App.server.episodes(screen.id).filter { it.parentIndexNumber != 0 }); downloading = false }
            }) { Text("Download all") }
        }
        Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Keep next episodes downloaded:", Modifier.weight(1f))
            TextButton(onClick = { keepNext = (keepNext - 1).coerceAtLeast(0); App.prefs.setKeepNext(screen.id, keepNext) }) { Text("−") }
            Text(if (keepNext == 0) "off" else "$keepNext")
            TextButton(onClick = {
                keepNext = (keepNext + 1).coerceAtMost(20); App.prefs.setKeepNext(screen.id, keepNext); Housekeeping.enqueue(ctx)
            }) { Text("+") }
        }
        Loaded(seasons) { list ->
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

private suspend fun downloadAll(episodes: List<BaseItemDto>) {
    for (ep in episodes) if (Media.download(ep.id.toString()) == null) runCatching { Media.startDownload(App.server.item(ep.id.toString()), auto = false) }
}

@Composable
fun SeasonScreen(modifier: Modifier, screen: Screen.Season, push: (Screen) -> Unit, pop: () -> Unit) {
    var refresh by remember { mutableIntStateOf(0) }
    val episodes = loadFresh(screen.seasonId, refresh) { App.server.episodes(screen.seriesId, screen.seasonId) }
    val scope = rememberCoroutineScope()
    Column(modifier.fillMaxSize()) {
        Bar(screen.name, pop) {
            TextButton(onClick = { scope.launch { downloadAll(episodes?.getOrNull().orEmpty()); refresh++ } }) { Text("Download season") }
        }
        Loaded(episodes, onDownloads = { push(Screen.Downloads) }) { list ->
            LazyColumn {
                items(list, key = { it.id }) { ep ->
                    EpisodeRow(
                        ep,
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
    highlighted: Boolean = false,
    onPlay: () -> Unit,
    onStartOver: (() -> Unit)? = null,
    onChanged: () -> Unit = {},
) {
    val (state, unsynced) = ep.localState()
    val id = ep.id.toString()
    val download = Media.download(id)
    val downloaded = download != null
    val complete = download != null && Media.isComplete(Media.key(download.item))
    var menu by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
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
                val runtime = ep.runTimeTicks ?: 0
                if (state != null && state.positionTicks > 0 && runtime > 0) {
                    LinearProgressIndicator({ state.positionTicks.toFloat() / runtime }, Modifier.fillMaxWidth())
                }
            }
            Column(Modifier.weight(1f)) {
                Text("${ep.indexNumber ?: ""}. ${ep.name.orEmpty()}", style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                val bits = listOfNotNull(
                    ep.runTimeTicks?.let { formatMs(it / 10_000) },
                    "✓ played".takeIf { state?.played == true },
                    "downloaded".takeIf { complete },
                    "downloading".takeIf { downloaded && !complete },
                    "not synced".takeIf { unsynced },
                )
                Text(bits.joinToString(" · "), style = MaterialTheme.typography.labelSmall)
            }
        }
        DropdownMenu(menu, { menu = false }) {
            onStartOver?.let { DropdownMenuItem({ Text("Start over") }, { menu = false; it() }) }
            val played = state?.played == true
            DropdownMenuItem({ Text(if (played) "Mark unplayed" else "Mark played") }, {
                menu = false
                scope.launch { App.server.setPlayed(id, !played); onChanged() }
            })
            if (downloaded) DropdownMenuItem({ Text("Delete download") }, { menu = false; Media.deleteDownload(id); onChanged() })
            else DropdownMenuItem({ Text("Download") }, {
                menu = false
                scope.launch { runCatching { Media.startDownload(App.server.item(id), auto = false) }; onChanged() }
            })
        }
    }
}

// --- downloads ------------------------------------------------------------------------------------

@Composable
fun DownloadsScreen(modifier: Modifier, push: (Screen) -> Unit, pop: () -> Unit) {
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(1_000); tick++ } }
    val downloads = remember(tick) {
        Media.downloads().sortedWith(compareBy({ it.item.seriesName ?: it.item.name }, { it.item.parentIndexNumber }, { it.item.indexNumber }))
    }
    val scope = rememberCoroutineScope()
    val total = remember(tick) { downloads.sumOf { Media.cachedBytes(Media.key(it.item)) } }
    Column(modifier.fillMaxSize()) {
        Bar("Downloads", pop)
        Text("Using %.1f GB".format(total / 1e9), Modifier.padding(horizontal = 16.dp))
        LazyColumn {
            var lastGroup: String? = null
            for (d in downloads) {
                val group = d.item.seriesName ?: "Movies"
                if (group != lastGroup) {
                    lastGroup = group
                    item(key = "h-$group") { Text(group, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(16.dp, 12.dp, 16.dp, 4.dp)) }
                }
                item(key = d.item.id) {
                    val id = d.item.id.toString()
                    val key = Media.key(d.item)
                    val len = Media.contentLength(key)
                    val pct = if (len > 0) (Media.cachedBytes(key) * 100 / len).toInt() else 0
                    val done = Media.isComplete(key)
                    Row(Modifier.fillMaxWidth().combinedClickable(onClick = { push(Screen.Play(id)) }).padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(if (d.item.type == BaseItemKind.EPISODE) episodeLabel(d.item) else d.item.name.orEmpty())
                            Text((if (done) "done" else "$pct%") + if (d.auto) " · kept by keep-next" else "", style = MaterialTheme.typography.labelSmall)
                        }
                        if (!done) {
                            TextButton(onClick = { Media.pauseDownload(id) }) { Text("Pause") }
                            TextButton(onClick = {
                                scope.launch { Media.startDownload(runCatching { App.server.item(id) }.getOrDefault(d.item), d.auto) }
                            }) { Text("Resume") }
                        }
                        TextButton(onClick = { Media.deleteDownload(id); tick++ }) { Text("Delete") }
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
                    toggle("Download on metered networks", p.downloadOnMetered) { p.downloadOnMetered = it; Media.requeueDownloads() }
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
