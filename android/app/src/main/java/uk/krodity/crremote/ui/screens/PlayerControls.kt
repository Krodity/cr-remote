package uk.krodity.crremote.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import uk.krodity.crremote.data.PlayerStatus
import uk.krodity.crremote.ui.formatTime

/**
 * The transport bar. Collapsed it is a one-line strip above the nav bar; tapped
 * it expands into the full control set. Kept in one composable so the collapsed
 * and expanded states can never disagree about what is playing.
 */
@Composable
fun PlayerControls(
    status: PlayerStatus,
    title: String,
    onControl: (String, Double?) -> Unit,
    onSeek: (Float) -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    // While the user drags, show their finger position, not the stale poll value.
    var scrubbing by remember { mutableStateOf(false) }
    var scrubValue by remember { mutableFloatStateOf(0f) }
    val shown = if (scrubbing) scrubValue else status.progress

    Surface(
        tonalElevation = 3.dp,
        shadowElevation = 8.dp,
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(Modifier.fillMaxWidth()) {
            // Thin progress line doubles as the collapsed-state scrubber readout.
            if (!expanded) {
                LinearProgressIndicator(
                    progress = { shown },
                    modifier = Modifier.fillMaxWidth().height(2.dp),
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                    drawStopIndicator = {},
                )
            }

            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        title.ifBlank { "Nothing playing" },
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        if (status.hasVideo)
                            "${formatTime(status.position)} / ${formatTime(status.duration)}"
                        else status.reason ?: "No episode open on the PC",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
                IconButton(
                    onClick = { onControl("toggle", null) },
                    enabled = status.hasVideo,
                ) {
                    Icon(
                        if (status.playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                        if (status.playing) "Pause" else "Play",
                        modifier = Modifier.size(30.dp),
                    )
                }
                Icon(
                    if (expanded) Icons.Default.ExpandMore else Icons.Default.ExpandLess,
                    null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(),
                exit = shrinkVertically(),
            ) {
                Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 12.dp)) {
                    Slider(
                        value = shown,
                        onValueChange = {
                            scrubbing = true
                            scrubValue = it
                        },
                        onValueChangeFinished = {
                            scrubbing = false
                            onSeek(scrubValue)
                        },
                        enabled = status.hasVideo && status.duration > 0,
                    )
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            formatTime(shown * status.duration),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            "-" + formatTime(status.duration - shown * status.duration),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    Spacer(Modifier.height(4.dp))
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ControlButton(Icons.Default.RestartAlt, "Restart", status.hasVideo) {
                            onControl("restart", null)
                        }
                        ControlButton(Icons.Default.Replay10, "Back 10s", status.hasVideo) {
                            onControl("back", 10.0)
                        }
                        FilledIconButton(
                            onClick = { onControl("toggle", null) },
                            enabled = status.hasVideo,
                            modifier = Modifier.size(56.dp),
                        ) {
                            Icon(
                                if (status.playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                                if (status.playing) "Pause" else "Play",
                                modifier = Modifier.size(30.dp),
                            )
                        }
                        ControlButton(Icons.Default.Forward10, "Forward 10s", status.hasVideo) {
                            onControl("forward", 10.0)
                        }
                        ControlButton(Icons.Default.SkipNext, "Next episode", true) {
                            onControl("next", null)
                        }
                    }

                    Spacer(Modifier.height(4.dp))
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ControlButton(
                            if (status.muted) Icons.AutoMirrored.Filled.VolumeOff
                            else Icons.AutoMirrored.Filled.VolumeUp,
                            "Mute", status.hasVideo,
                        ) { onControl("mute", null) }
                        ControlButton(
                            if (status.fullscreen) Icons.Default.FullscreenExit
                            else Icons.Default.Fullscreen,
                            "Fullscreen", status.hasVideo,
                        ) { onControl("fullscreen", null) }
                        ControlButton(Icons.Default.TouchApp, "Dismiss 'still watching'", true) {
                            onControl("dismiss", null)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ControlButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick, enabled = enabled) {
        Icon(icon, label, modifier = Modifier.size(26.dp))
    }
}
