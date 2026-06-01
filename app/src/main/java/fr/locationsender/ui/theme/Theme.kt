package fr.locationsender.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Indigo,
    onPrimary = Color.White,
    primaryContainer = IndigoContainerLight,
    onPrimaryContainer = IndigoOnContainerLight,
    secondary = Teal,
    onSecondary = Color.White,
    secondaryContainer = TealContainerLight,
    onSecondaryContainer = TealOnContainerLight,
    tertiary = Violet,
    background = SurfaceLight,
    onBackground = OnSurfaceLight,
    surface = SurfaceLight,
    onSurface = OnSurfaceLight,
    surfaceVariant = SurfaceContainerLight,
    onSurfaceVariant = Color(0xFF45464F),
    surfaceContainer = SurfaceContainerLight,
    outline = OutlineLight,
    outlineVariant = Color(0xFFD9DBE7),
)

private val DarkColors = darkColorScheme(
    primary = IndigoLight,
    onPrimary = Color(0xFF00208E),
    primaryContainer = IndigoContainerDark,
    onPrimaryContainer = IndigoOnContainerDark,
    secondary = TealLightDark,
    onSecondary = Color(0xFF003735),
    secondaryContainer = Color(0xFF004F4C),
    onSecondaryContainer = Color(0xFFB9F0EC),
    tertiary = Color(0xFFCBBEFF),
    background = SurfaceDark,
    onBackground = OnSurfaceDark,
    surface = SurfaceDark,
    onSurface = OnSurfaceDark,
    surfaceVariant = SurfaceContainerDark,
    onSurfaceVariant = Color(0xFFC5C6D4),
    surfaceContainer = SurfaceContainerDark,
    outline = OutlineDark,
    outlineVariant = Color(0xFF44464F),
)

@Composable
fun LocationSenderTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = Typography,
        content = content,
    )
}
