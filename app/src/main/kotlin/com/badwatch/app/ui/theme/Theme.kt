package com.badwatch.app.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.wear.compose.material.Colors
import androidx.wear.compose.material.MaterialTheme

private val colorPalette = Colors(
    primary = PrimaryColor,
    primaryVariant = PrimaryVariantColor,
    secondary = SecondaryColor,
    background = BackgroundColor,
    surface = SurfaceColor,
    error = Color(0xFFFF5470),
    onPrimary = OnPrimaryColor,
    onSecondary = Color.Black,
    onBackground = OnBackgroundColor,
    onSurface = OnSurfaceColor,
    onError = Color.Black
)

@Composable
fun BadWatchTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colors = colorPalette,
        content = content
    )
}

object BadWatchThemeDefaults {
    val colors: Colors
        @ReadOnlyComposable
        @Composable
        get() = MaterialTheme.colors
}
