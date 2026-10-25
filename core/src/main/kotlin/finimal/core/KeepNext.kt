package finimal.core

data class EpisodeRef(val id: String, val state: PlayState?)

/**
 * Episodes "keep next N" wants on disk. [episodes] are in season order with specials removed.
 * The anchor is the most recently played episode; it stays in the window while unfinished.
 * With nothing played, the window is the first [n] episodes.
 */
fun keepNextWindow(episodes: List<EpisodeRef>, n: Int): List<String> {
    val anchor = episodes.withIndex()
        .filter { it.value.state?.lastPlayedMs != null }
        // Ties go to the later episode: marking a season played on the server gives every episode one date.
        .maxWithOrNull(compareBy({ it.value.state!!.lastPlayedMs!! }, { it.index }))
    if (anchor == null) return episodes.take(n).map { it.id }
    val current = anchor.value.takeUnless { it.state!!.played }
    val after = episodes.drop(anchor.index + 1).take(n)
    return listOfNotNull(current).plus(after).map { it.id }
}

/** Auto-downloaded episodes to delete: played and outside the window. */
fun keepNextDeletions(autoDownloaded: Set<String>, episodes: List<EpisodeRef>, window: List<String>): List<String> =
    episodes.filter { it.id in autoDownloaded && it.id !in window && it.state?.played == true }.map { it.id }
