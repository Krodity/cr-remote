package uk.krodity.crremote.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import uk.krodity.crremote.data.Episode
import uk.krodity.crremote.data.Season
import uk.krodity.crremote.data.Series
import uk.krodity.crremote.ui.formatDuration

@Composable
fun SeriesScreen(
    series: Series,
    seasons: List<Season>,
    selected: Season?,
    episodes: List<Episode>,
    loading: Boolean,
    nowPlayingId: String?,
    onSeason: (Season) -> Unit,
    onPlay: (Episode) -> Unit,
    contentPadding: PaddingValues,
) {
    Column(Modifier.fillMaxSize()) {
        // Only worth a season picker when there is more than one.
        if (seasons.size > 1) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(seasons, key = { it.id }) { s ->
                    FilterChip(
                        selected = s.id == selected?.id,
                        onClick = { onSeason(s) },
                        label = { Text(s.label) },
                    )
                }
            }
        }

        if (loading) {
            Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
        } else {
            LazyColumn(contentPadding = contentPadding) {
                items(episodes, key = { it.id }) { ep ->
                    EpisodeRow(ep, playing = ep.id == nowPlayingId) { onPlay(ep) }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = .3f))
                }
            }
        }
    }
}

@Composable
private fun EpisodeRow(ep: Episode, playing: Boolean, onPlay: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onPlay)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            AsyncImage(
                model = ep.thumb,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(width = 108.dp, height = 61.dp)
                    .clip(RoundedCornerShape(6.dp)),
            )
            if (playing) {
                Box(
                    Modifier
                        .matchParentSize()
                        .clip(RoundedCornerShape(6.dp))
                        .background(MaterialTheme.colorScheme.scrim.copy(alpha = .5f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Default.PlayArrow, null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                listOfNotNull(ep.label.ifEmpty { null }, ep.title).joinToString(" · "),
                style = MaterialTheme.typography.titleSmall,
                color = if (playing) MaterialTheme.colorScheme.primary else Color.Unspecified,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (ep.premium) {
                    Icon(
                        Icons.Default.Lock, "Premium",
                        modifier = Modifier.size(12.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(4.dp))
                }
                Text(
                    formatDuration(ep.durationMs),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
