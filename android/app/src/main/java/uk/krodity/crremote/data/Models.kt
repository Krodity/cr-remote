package uk.krodity.crremote.data

/** Wire models. Field names mirror exactly what the browser extension emits,
 *  so Gson maps them without annotations. */

data class Series(
    val id: String = "",
    val title: String = "",
    val slug: String? = null,
    val description: String = "",
    val episodes: Int? = null,
    val seasons: Int? = null,
    val poster: String? = null,
)

data class Season(
    val id: String = "",
    val number: Int? = null,
    val title: String = "",
    val episodes: Int? = null,
    val subbed: Boolean = false,
    val dubbed: Boolean = false,
) {
    val label: String get() = number?.let { "S$it" } ?: title.take(8)
}

data class Episode(
    val id: String = "",
    val number: Int? = null,
    val season: Int? = null,
    val title: String = "",
    val slug: String? = null,
    val description: String = "",
    val durationMs: Long? = null,
    val premium: Boolean = false,
    val thumb: String? = null,
    val url: String = "",
) {
    val label: String get() = number?.let { "E$it" } ?: ""
}

/** Playback state as seen by the extension's content script. */
data class PlayerStatus(
    val hasVideo: Boolean = false,
    val playing: Boolean = false,
    val paused: Boolean = true,
    val ended: Boolean = false,
    val position: Double = 0.0,
    val duration: Double = 0.0,
    val buffered: Double = 0.0,
    val volume: Double = 1.0,
    val muted: Boolean = false,
    val fullscreen: Boolean = false,
    val episodeId: String? = null,
    val pageTitle: String? = null,
    val url: String? = null,
    val reason: String? = null,
) {
    val progress: Float
        get() = if (duration > 0) (position / duration).toFloat().coerceIn(0f, 1f) else 0f
}

data class HostInfo(
    val id: String = "",
    val name: String = "",
    val connected_at: Double = 0.0,
    val status: PlayerStatus? = null,
)

/** A broker call that failed, carrying a message already fit to show the user. */
class BrokerException(message: String, val recoverable: Boolean = true) : Exception(message)
