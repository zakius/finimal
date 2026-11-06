package finimal.core

data class EpisodeRef(val id: String, val state: PlayState?)

/**
 * Episodes "keep next N" wants on disk. [episodes] are in season order with specials removed.
 * The anchor is the most recently played episode; it stays in the window while unfinished.
 * With nothing played, the window is the first [n] episodes.
 */
fun keepNextWindow(episodes: List<EpisodeRef>, n: Int): List<String> {
    val everPlayed = episodes.withIndex().filter { indexed -> indexed.value.state?.lastPlayedMs != null }
    // Ties go to the later episode, because marking a season played on the server gives every episode the same date.
    val anchor = everPlayed.maxWithOrNull(
        compareBy({ indexed -> indexed.value.state!!.lastPlayedMs!! }, { indexed -> indexed.index }),
    )
    if (anchor == null) return episodes.take(n).map { it.id }
    val window = mutableListOf<String>()
    if (!anchor.value.state!!.played) window += anchor.value.id
    for (episode in episodes.drop(anchor.index + 1).take(n)) window += episode.id
    return window
}

/** Auto-downloaded episodes to delete: played and outside the window. */
fun keepNextDeletions(autoDownloaded: Set<String>, episodes: List<EpisodeRef>, window: List<String>): List<String> {
    val toDelete = episodes.filter { episode ->
        episode.id in autoDownloaded && episode.id !in window && episode.state?.played == true
    }
    return toDelete.map { it.id }
}
