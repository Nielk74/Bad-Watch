package com.badwatch.app.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material.Colors
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Shapes

private val colorPalette = Colors(
    primary = PrimaryColor,
    primaryVariant = PrimaryVariantColor,
    secondary = SecondaryColor,
    secondaryVariant = SecondaryVariantColor,
    background = BackgroundColor,
    surface = SurfaceColor,
    error = Color(0xFFFF5470),
    onPrimary = OnPrimaryColor,
    onSecondary = Color.Black,
    onBackground = OnBackgroundColor,
    onSurface = OnSurfaceColor,
    onError = Color.Black
)

private val shapes = Shapes(
    small = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
    medium = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
    large = androidx.compose.foundation.shape.RoundedCornerShape(28.dp)
)

@Composable
fun BadWatchTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colors = colorPalette,
        typography = BadWatchTypography,
        shapes = shapes,
        content = content
    )
}

object BadWatchThemeDefaults {
    val colors: Colors
        @ReadOnlyComposable
        @Composable
        get() = MaterialTheme.colors

    val typography
        @ReadOnlyComposable
        @Composable
        get() = MaterialTheme.typography

    val shapes
        @ReadOnlyComposable
        @Composable
        get() = MaterialTheme.shapes
}
