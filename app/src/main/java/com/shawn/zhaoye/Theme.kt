package com.shawn.zhaoye

import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color

/**
 * The darkroom's palette. Split out of MainActivity when the settings page
 * arrived: both screens draw on it, and two copies of a colour constant is one
 * copy too many.
 */
internal val Amber = Color(0xFFD8A657)
internal val Ink = Color(0xFF12100E)
internal val Panel = Color(0xFF1B1815)
internal val SafeRed = Color(0xFF8E2B22)
internal val Bone = Color(0xFFEDE6DC)
internal val Faint = Color(0xFF8A8175)
internal val Edge = Color(0xFF2A241E)

internal val DarkroomColours = darkColorScheme(
    primary = Amber,
    onPrimary = Ink,
    background = Ink,
    onBackground = Bone,
    surface = Panel,
    onSurface = Bone,
    surfaceVariant = Color(0xFF26211C),
    onSurfaceVariant = Faint,
    error = SafeRed,
)
