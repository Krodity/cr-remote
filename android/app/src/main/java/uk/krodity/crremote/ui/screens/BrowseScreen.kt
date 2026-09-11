package uk.krodity.crremote.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import uk.krodity.crremote.data.Series

@Composable
fun BrowseScreen(
    query: String,
    results: List<Series>,
    searching: Boolean,
    searched: Boolean,
    trending: List<Series>,
    newEpisodes: List<Series>,
    loadingTrending: Boolean,
    onQuery: (String) -> Unit,
    onSearch: () -> Unit,
    onOpen: (Series) -> Unit,
    contentPadding: PaddingValues,
) {
    val keyboard = LocalSoftwareKeyboardController.current

    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = query,
            onValueChange = onQuery,
            placeholder = { Text("Search Crunchyroll") },
            leadingIcon = { Icon(Icons.Default.Search, null) },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { onQuery("") }) { Icon(Icons.Default.Close, "Clear") }
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(28.dp),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = {
                keyboard?.hide()
                onSearch()
            }),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        )

        when {
            searching -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }

            results.isEmpty() && searched -> EmptyState(
                icon = Icons.Default.SearchOff,
                title = "No shows found",
                body = "Try a shorter or differently spelled title.",
            )

            // No query yet: the home screen is the trending rail.
            results.isEmpty() && loadingTrending ->
                Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }

            results.isEmpty() && trending.isEmpty() && newEpisodes.isEmpty() -> EmptyState(
                icon = Icons.Default.Search,
                title = "Find something to watch",
                body = "Search for a show, pick an episode, then control playback from here.",
            )

            // Search results replace the home rails; with no query we show both.
            results.isNotEmpty() -> LazyColumn(contentPadding = contentPadding) {
                section("Results", results, onOpen)
            }

            else -> LazyColumn(contentPadding = contentPadding) {
                section("Trending now", trending, onOpen)
                section("New episodes", newEpisodes, onOpen)
            }
        }
    }
}

private fun LazyListScope.section(
    heading: String,
    items: List<Series>,
    onOpen: (Series) -> Unit,
) {
    if (items.isEmpty()) return
    item(key = "hdr-$heading") {
        Text(
            heading,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 16.dp, top = 10.dp, bottom = 6.dp),
        )
    }
    items(items, key = { "$heading-${it.id}" }) { s ->
        SeriesRow(s) { onOpen(s) }
        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = .3f))
    }
}

@Composable
private fun SeriesRow(s: Series, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = s.poster,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(width = 92.dp, height = 52.dp)
                .clip(RoundedCornerShape(6.dp)),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                s.title,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            val meta = s.latest ?: listOfNotNull(
                s.seasons?.let { if (it > 1) "$it seasons" else null },
                s.episodes?.let { "$it episodes" },
            ).joinToString(" · ")
            if (meta.isNotEmpty()) {
                Text(
                    meta,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
fun EmptyState(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    body: String,
) {
    Column(
        Modifier.fillMaxSize().padding(40.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            icon, null,
            modifier = Modifier.size(40.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .6f),
        )
        Spacer(Modifier.height(12.dp))
        Text(title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}
