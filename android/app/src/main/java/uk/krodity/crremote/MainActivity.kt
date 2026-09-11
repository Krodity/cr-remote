package uk.krodity.crremote

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import uk.krodity.crremote.ui.RemoteViewModel
import uk.krodity.crremote.ui.screens.BrowseScreen
import uk.krodity.crremote.ui.screens.PlayerControls
import uk.krodity.crremote.ui.screens.SeriesScreen
import uk.krodity.crremote.ui.theme.CrRemoteTheme

class MainActivity : ComponentActivity() {

    private val vm: RemoteViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handlePairLink(intent)
        setContent {
            CrRemoteTheme { App(vm) }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handlePairLink(intent)
    }

    /** crremote://pair?broker=aepc:8774&token=… — the broker's /pair page links here
     *  so the token never has to be typed on a phone keyboard. */
    private fun handlePairLink(intent: Intent?) {
        val uri = intent?.data ?: return
        if (uri.scheme != "crremote" || uri.host != "pair") return
        val broker = uri.getQueryParameter("broker") ?: return
        val token = uri.getQueryParameter("token") ?: return
        vm.pair(broker, token)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun App(vm: RemoteViewModel) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var showHosts by remember { mutableStateOf(false) }

    LaunchedEffect(ui.error) {
        ui.error?.let {
            snackbar.showSnackbar(it)
            vm.dismissError()
        }
    }

    if (ui.checkingPairing) {
        Box(Modifier.fillMaxSize(), androidx.compose.ui.Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    if (!ui.paired) {
        uk.krodity.crremote.ui.screens.PairScreen("", "") { b, t -> vm.pair(b, t) }
        return
    }

    BackHandler(enabled = ui.series != null) { vm.clearSeries() }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        ui.series?.title ?: "cr-remote",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    if (ui.series != null) {
                        IconButton(onClick = { vm.clearSeries() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                        }
                    }
                },
                actions = {
                    IconButton(onClick = {
                        vm.refreshHosts()
                        showHosts = true
                    }) {
                        BadgedBox(badge = {
                            if (ui.hosts.isEmpty()) {
                                Badge(containerColor = MaterialTheme.colorScheme.error)
                            }
                        }) { Icon(Icons.Default.Computer, "Choose PC") }
                    }
                },
            )
        },
        bottomBar = {
            PlayerControls(
                status = ui.status,
                title = ui.status.pageTitle
                    ?: ui.nowPlaying?.let { "${it.label} ${it.title}".trim() }
                    ?: "",
                onControl = vm::control,
                onSeek = vm::seekTo,
            )
        },
    ) { pad ->
        Box(Modifier.padding(pad)) {
            val series = ui.series
            if (series == null) {
                BrowseScreen(
                    query = ui.query,
                    results = ui.results,
                    searching = ui.searching,
                    searched = ui.searched,
                    trending = ui.trending,
                    newEpisodes = ui.newEpisodes,
                    loadingTrending = ui.loadingTrending,
                    onQuery = vm::setQuery,
                    onSearch = vm::search,
                    onOpen = vm::openSeries,
                    contentPadding = PaddingValues(bottom = 12.dp),
                )
            } else {
                SeriesScreen(
                    series = series,
                    seasons = ui.seasons,
                    selected = ui.season,
                    episodes = ui.episodes,
                    loading = ui.loadingSeries,
                    nowPlayingId = ui.status.episodeId,
                    onSeason = vm::selectSeason,
                    onPlay = vm::play,
                    contentPadding = PaddingValues(bottom = 12.dp),
                )
            }
        }
    }

    if (showHosts) {
        HostPicker(
            hosts = ui.hosts,
            active = ui.activeHost,
            onPick = {
                vm.selectHost(it)
                showHosts = false
            },
            onUnpair = {
                showHosts = false
                vm.unpair()
            },
            onDismiss = { showHosts = false },
        )
    }
}

@Composable
private fun HostPicker(
    hosts: List<uk.krodity.crremote.data.HostInfo>,
    active: String,
    onPick: (String) -> Unit,
    onUnpair: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Which PC?") },
        text = {
            Column {
                if (hosts.isEmpty()) {
                    Text(
                        "No PC is connected. Open a browser with the cr-remote " +
                            "extension installed and signed in to Crunchyroll.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                } else {
                    // An empty selection means "whichever connected most recently",
                    // which is right almost always — offer it as a first-class choice.
                    HostRow("Most recent", active.isBlank()) { onPick("") }
                    hosts.forEach { h ->
                        HostRow(h.name.ifBlank { h.id }, h.id == active) { onPick(h.id) }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
        dismissButton = { TextButton(onClick = onUnpair) { Text("Unpair") } },
    )
}

@Composable
private fun HostRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Spacer(Modifier.width(4.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}
