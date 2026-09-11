package uk.krodity.crremote.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import uk.krodity.crremote.data.Broker
import uk.krodity.crremote.data.BrokerException
import uk.krodity.crremote.data.Episode
import uk.krodity.crremote.data.HostInfo
import uk.krodity.crremote.data.PlayerStatus
import uk.krodity.crremote.data.Season
import uk.krodity.crremote.data.Series
import uk.krodity.crremote.data.SettingsRepo

data class UiState(
    val paired: Boolean = false,
    val checkingPairing: Boolean = true,
    val brokerLabel: String = "",
    val hosts: List<HostInfo> = emptyList(),
    val activeHost: String = "",

    val query: String = "",
    val searching: Boolean = false,
    val results: List<Series> = emptyList(),
    val searched: Boolean = false,

    val series: Series? = null,
    val seasons: List<Season> = emptyList(),
    val season: Season? = null,
    val episodes: List<Episode> = emptyList(),
    val loadingSeries: Boolean = false,

    val status: PlayerStatus = PlayerStatus(),
    val nowPlaying: Episode? = null,
    val busy: Boolean = false,
    val error: String? = null,
)

class RemoteViewModel(app: Application) : AndroidViewModel(app) {

    private val settings = SettingsRepo(app)
    private var cfg = SettingsRepo.Config()
    private val broker = Broker(settings) { cfg }

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    private var pollJob: Job? = null
    /** Ignore status pushes briefly after a seek so the scrubber doesn't snap back
     *  to the pre-seek position while the player catches up. */
    private var seekGuardUntil = 0L

    init {
        viewModelScope.launch {
            settings.config.collect { c ->
                cfg = c
                _ui.value = _ui.value.copy(
                    paired = c.paired,
                    checkingPairing = false,
                    activeHost = c.host,
                    brokerLabel = c.broker.substringAfter("://").substringBefore(":"),
                )
                if (c.paired) {
                    refreshHosts()
                    startPolling()
                } else {
                    stopPolling()
                }
            }
        }
    }

    // ── pairing ──────────────────────────────────────────────────────────────
    fun pair(broker: String, token: String) = viewModelScope.launch {
        settings.pair(broker, token)
        _ui.value = _ui.value.copy(error = null)
    }

    fun unpair() = viewModelScope.launch {
        stopPolling()
        settings.clear()
        _ui.value = UiState(checkingPairing = false)
    }

    fun selectHost(id: String) = viewModelScope.launch { settings.selectHost(id) }

    fun refreshHosts() = viewModelScope.launch {
        runCatching { broker.hosts() }
            .onSuccess { _ui.value = _ui.value.copy(hosts = it, error = null) }
            .onFailure { fail(it) }
    }

    // ── browsing ─────────────────────────────────────────────────────────────
    fun setQuery(q: String) { _ui.value = _ui.value.copy(query = q) }

    fun search() = viewModelScope.launch {
        val q = _ui.value.query.trim()
        if (q.isEmpty()) return@launch
        _ui.value = _ui.value.copy(searching = true, error = null)
        runCatching { broker.search(q) }
            .onSuccess { _ui.value = _ui.value.copy(results = it, searching = false, searched = true) }
            .onFailure {
                _ui.value = _ui.value.copy(searching = false, searched = true)
                fail(it)
            }
    }

    fun openSeries(s: Series) = viewModelScope.launch {
        _ui.value = _ui.value.copy(
            series = s, loadingSeries = true, seasons = emptyList(),
            episodes = emptyList(), season = null, error = null,
        )
        runCatching {
            val seasons = broker.seasons(s.id)
            val first = seasons.firstOrNull()
            val eps = first?.let { broker.episodes(it.id) } ?: emptyList()
            seasons to (first to eps)
        }.onSuccess { (seasons, rest) ->
            _ui.value = _ui.value.copy(
                seasons = seasons, season = rest.first,
                episodes = rest.second, loadingSeries = false,
            )
        }.onFailure {
            _ui.value = _ui.value.copy(loadingSeries = false)
            fail(it)
        }
    }

    fun selectSeason(s: Season) = viewModelScope.launch {
        _ui.value = _ui.value.copy(season = s, loadingSeries = true, error = null)
        runCatching { broker.episodes(s.id) }
            .onSuccess { _ui.value = _ui.value.copy(episodes = it, loadingSeries = false) }
            .onFailure {
                _ui.value = _ui.value.copy(loadingSeries = false)
                fail(it)
            }
    }

    fun clearSeries() {
        _ui.value = _ui.value.copy(series = null, seasons = emptyList(), episodes = emptyList())
    }

    // ── playback ─────────────────────────────────────────────────────────────
    fun play(ep: Episode) = viewModelScope.launch {
        _ui.value = _ui.value.copy(busy = true, nowPlaying = ep, error = null)
        runCatching { broker.play(ep) }
            .onFailure { fail(it) }
        _ui.value = _ui.value.copy(busy = false)
        // The page needs a moment to load before it reports a video.
        delay(2500)
        pollOnce()
    }

    fun control(action: String, value: Double? = null) = viewModelScope.launch {
        if (action == "seek" || action == "forward" || action == "back") {
            seekGuardUntil = System.currentTimeMillis() + 1200
        }
        // Reflect play/pause immediately; the poll will correct us if it failed.
        when (action) {
            "play" -> optimistic(playing = true)
            "pause" -> optimistic(playing = false)
            "toggle" -> optimistic(playing = !_ui.value.status.playing)
        }
        runCatching { broker.control(action, value) }
            .onSuccess { s -> if (s.hasVideo) _ui.value = _ui.value.copy(status = s, error = null) }
            .onFailure { fail(it) }
    }

    fun seekTo(fraction: Float) {
        val d = _ui.value.status.duration
        if (d > 0) {
            _ui.value = _ui.value.copy(status = _ui.value.status.copy(position = d * fraction))
            control("seek", d * fraction)
        }
    }

    private fun optimistic(playing: Boolean) {
        _ui.value = _ui.value.copy(status = _ui.value.status.copy(playing = playing, paused = !playing))
    }

    private fun startPolling() {
        if (pollJob?.isActive == true) return
        pollJob = viewModelScope.launch {
            while (isActive) {
                pollOnce()
                // Slow down when nothing is playing; there is nothing to animate.
                delay(if (_ui.value.status.hasVideo) 1500 else 5000)
            }
        }
    }

    private fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
    }

    private suspend fun pollOnce() {
        if (!cfg.paired) return
        runCatching { broker.status() }
            .onSuccess { s ->
                if (System.currentTimeMillis() < seekGuardUntil && s.hasVideo) {
                    // Keep our optimistic position, take everything else.
                    _ui.value = _ui.value.copy(
                        status = s.copy(position = _ui.value.status.position), error = null
                    )
                } else {
                    _ui.value = _ui.value.copy(status = s, error = null)
                }
            }
            .onFailure { e ->
                // A polling failure is background noise; don't blank the screen for it.
                if (e is BrokerException && !e.recoverable) fail(e)
            }
    }

    fun dismissError() { _ui.value = _ui.value.copy(error = null) }

    private fun fail(e: Throwable) {
        _ui.value = _ui.value.copy(error = e.message ?: "Something went wrong")
    }
}
