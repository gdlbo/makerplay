package io.github.gdlbo.makerplay.feature.player.runtime.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val RuntimeOverlayColorScheme = darkColorScheme(
    primary = Color(0xFFA5C8FF),
    onPrimary = Color(0xFF00315B),
    primaryContainer = Color(0xFF004A77),
    onPrimaryContainer = Color(0xFFD4E3FF),
    secondary = Color(0xFFBBC7DA),
    onSecondary = Color(0xFF253140),
    secondaryContainer = Color(0xFF3B4858),
    onSecondaryContainer = Color(0xFFD7E3F7),
    surface = Color(0xFF101419),
    onSurface = Color(0xFFE1E2E8),
    surfaceVariant = Color(0xFF43474E),
    onSurfaceVariant = Color(0xFFC3C7CF),
    surfaceContainer = Color(0xFF1C2026),
    surfaceContainerHigh = Color(0xFF272B31),
    surfaceContainerHighest = Color(0xFF32363C),
    outline = Color(0xFF8D9199),
    outlineVariant = Color(0xFF43474E),
)

/** A stable, high-contrast Material surface for controls drawn over game content. */
@Composable
internal fun RuntimeOverlayTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = RuntimeOverlayColorScheme,
        typography = MaterialTheme.typography,
        shapes = MaterialTheme.shapes,
        content = content,
    )
}
