package com.badwatch.app.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.wear.compose.material3.ColorScheme
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.MotionScheme
import androidx.wear.compose.material3.dynamicColorScheme

private val BadWatchColorScheme = ColorScheme().copy(
    primary = MintPrimary,
    primaryDim = MintPrimaryDim,
    primaryContainer = MintContainer,
    onPrimary = OnMint,
    onPrimaryContainer = OnMintContainer,
    secondary = CourtBlue,
    secondaryDim = CourtBlueDim,
    secondaryContainer = CourtBlueContainer,
    onSecondary = OnCourtBlue,
    onSecondaryContainer = OnCourtBlueContainer,
    tertiary = NetViolet,
    tertiaryDim = NetVioletDim,
    tertiaryContainer = NetVioletContainer,
    onTertiary = OnNetViolet,
    onTertiaryContainer = OnNetVioletContainer,
    background = CourtNight,
    onBackground = OnCourtSurface,
    surfaceContainerLow = SurfaceLow,
    surfaceContainer = SurfaceMid,
    surfaceContainerHigh = SurfaceHigh,
    onSurface = OnCourtSurface,
    onSurfaceVariant = OnCourtSurfaceVariant,
    outline = CourtOutline,
    outlineVariant = CourtOutlineVariant,
    error = ErrorRed,
    errorDim = ErrorRedDim,
    errorContainer = ErrorContainerRed,
    onError = OnErrorRed,
    onErrorContainer = OnErrorContainerRed
)

@Composable
fun BadWatchTheme(content: @Composable () -> Unit) {
    // On Wear OS 6 the system hands us the watch face's own palette — the app then feels
    // like part of the face the player chose. Everywhere else the brand scheme applies.
    // Semantic colors (HR zones, shot families, severities) stay fixed regardless: they
    // carry meaning and must not drift with the watch face.
    val colorScheme = dynamicColorScheme(LocalContext.current) ?: BadWatchColorScheme
    MaterialTheme(
        colorScheme = colorScheme,
        typography = BadWatchTypography,
        motionScheme = MotionScheme.expressive(),
        content = content
    )
}
