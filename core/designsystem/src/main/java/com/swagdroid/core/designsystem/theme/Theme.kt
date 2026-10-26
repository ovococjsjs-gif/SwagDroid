package com.swagdroid.core.designsystem.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val SwagDarkColorScheme = darkColorScheme(
    primary = SwagEmerald80,
    onPrimary = Color(0xFF042F2E),
    primaryContainer = SwagEmeraldContainerDark,
    onPrimaryContainer = Color(0xFF99F6E4),
    secondary = SwagCyan80,
    onSecondary = Color(0xFF082F49),
    secondaryContainer = Color(0xFF0C4A6E),
    onSecondaryContainer = Color(0xFFE0F2FE),
    tertiary = SwagViolet80,
    onTertiary = Color(0xFF2E1065),
    background = DarkBackground,
    onBackground = Color(0xFFE6EDF5),
    surface = DarkSurface,
    onSurface = Color(0xFFE6EDF5),
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = Color(0xFF9FB0C4),
    outline = DarkOutline,
)

private val SwagLightColorScheme = lightColorScheme(
    primary = SwagEmerald40,
    onPrimary = Color.White,
    primaryContainer = SwagEmeraldContainerLight,
    onPrimaryContainer = Color(0xFF042F2E),
    secondary = SwagCyan40,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE0F2FE),
    onSecondaryContainer = Color(0xFF082F49),
    tertiary = SwagViolet40,
    onTertiary = Color.White,
    background = LightBackground,
    onBackground = Color(0xFF0F172A),
    surface = LightSurface,
    onSurface = Color(0xFF0F172A),
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = Color(0xFF475569),
    outline = LightOutline,
)

@Composable
fun SwagDroidTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> SwagDarkColorScheme
        else -> SwagLightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content,
    )
}
