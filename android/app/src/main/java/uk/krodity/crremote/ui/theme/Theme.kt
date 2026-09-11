package uk.krodity.crremote.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Crunchyroll orange, on near-black. Dynamic colour is deliberately not used:
// a remote should look the same on every phone it is installed on.
private val Orange = Color(0xFFF47521)
private val OrangeDim = Color(0xFFB8560F)

private val Dark = darkColorScheme(
    primary = Orange,
    onPrimary = Color(0xFF1A0B00),
    primaryContainer = OrangeDim,
    onPrimaryContainer = Color(0xFFFFE6D4),
    secondary = Color(0xFFE0C4AE),
    background = Color(0xFF0A0A0A),
    onBackground = Color(0xFFECECEC),
    surface = Color(0xFF121212),
    onSurface = Color(0xFFECECEC),
    surfaceVariant = Color(0xFF1E1E1E),
    onSurfaceVariant = Color(0xFFB4B4B4),
    outline = Color(0xFF3A3A3A),
    error = Color(0xFFFF8A80),
)

private val Light = lightColorScheme(
    primary = OrangeDim,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFDCC6),
    onPrimaryContainer = Color(0xFF331200),
    background = Color(0xFFFAFAFA),
    surface = Color.White,
    surfaceVariant = Color(0xFFF0EFEE),
    outline = Color(0xFFD4D4D4),
)

@Composable
fun CrRemoteTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(colorScheme = if (darkTheme) Dark else Light, content = content)
}
