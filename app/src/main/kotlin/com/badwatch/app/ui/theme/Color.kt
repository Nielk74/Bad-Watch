package com.badwatch.app.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Bad Watch palette — "court at night".
 *
 * OLED-first: the background is near-black so pixels stay off during play. The electric
 * mint primary is the brand color (the shuttle under hall lights); every other hue is a
 * semantic signal — heart-rate zones, shot families, insight severities — so color always
 * means something and decorates nothing.
 */

// Brand
val MintPrimary = Color(0xFF3EF2BE)
val MintPrimaryDim = Color(0xFF29C99A)
val MintContainer = Color(0xFF0B3D31)
val OnMint = Color(0xFF00291F)
val OnMintContainer = Color(0xFF9FFFE0)

// Secondary — court blue
val CourtBlue = Color(0xFF9CCBFF)
val CourtBlueDim = Color(0xFF6FA8E8)
val CourtBlueContainer = Color(0xFF12304D)
val OnCourtBlue = Color(0xFF062032)
val OnCourtBlueContainer = Color(0xFFD2E8FF)

// Tertiary — net-cord violet
val NetViolet = Color(0xFFCDBDFF)
val NetVioletDim = Color(0xFFA48FE8)
val NetVioletContainer = Color(0xFF2B2050)
val OnNetViolet = Color(0xFF1B1140)
val OnNetVioletContainer = Color(0xFFE6DEFF)

// Surfaces — layered near-blacks over an OLED base
val CourtNight = Color(0xFF05080B)
val SurfaceLow = Color(0xFF0A0F14)
val SurfaceMid = Color(0xFF10171F)
val SurfaceHigh = Color(0xFF17222C)
val OnCourtSurface = Color(0xFFE2EAF2)
val OnCourtSurfaceVariant = Color(0xFFA7B4C2)
val CourtOutline = Color(0xFF3D4B5A)
val CourtOutlineVariant = Color(0xFF232D38)

// Error / destructive
val ErrorRed = Color(0xFFFF6E7A)
val ErrorRedDim = Color(0xFFD94A57)
val ErrorContainerRed = Color(0xFF4A1018)
val OnErrorRed = Color(0xFF3A060C)
val OnErrorContainerRed = Color(0xFFFFD9DC)

/** Semantic colors that sit outside the Material scheme. */
object CourtColors {
    // Heart-rate zones, easy to redline
    val Zone1 = Color(0xFF7AC7FF)
    val Zone2 = Color(0xFF3EF2BE)
    val Zone3 = Color(0xFFFFD60A)
    val Zone4 = Color(0xFFFF9F45)
    val Zone5 = Color(0xFFFF5470)

    // Insight severities
    val Success = Color(0xFF53FFAB)
    val Warning = Color(0xFFFFB967)
    val Critical = ErrorRed

    // Shot families
    val Smash = Color(0xFFFF6E7A)
    val Clear = Color(0xFF9CCBFF)
    val Drop = Color(0xFFFFB967)
    val Drive = Color(0xFF3EF2BE)
    val Backhand = Color(0xFFCDBDFF)
    val UnknownShot = Color(0xFFA7B4C2)
}
