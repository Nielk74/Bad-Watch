package com.badwatch.app.ui.theme

import androidx.compose.runtime.Composable
import androidx.wear.compose.material3.ColorScheme
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.MotionScheme

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
    MaterialTheme(
        colorScheme = BadWatchColorScheme,
        typography = BadWatchTypography,
        motionScheme = MotionScheme.expressive(),
        content = content
    )
}
