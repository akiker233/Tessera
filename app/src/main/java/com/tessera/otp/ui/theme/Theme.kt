package com.tessera.otp.ui.theme

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
import com.tessera.otp.data.prefs.ThemeMode

private val GraphiteBlue = Color(0xFF3B5BDB)
private val GraphiteBlueDark = Color(0xFF9DB2FF)

private val LightColors = lightColorScheme(
    primary = GraphiteBlue,
    onPrimary = Color.White,
    secondary = Color(0xFF546E7A),
    surface = Color(0xFFFDFDFE),
    background = Color(0xFFFBFBFD)
)

private val DarkColors = darkColorScheme(
    primary = GraphiteBlueDark,
    onPrimary = Color(0xFF0B1533),
    secondary = Color(0xFFB0BEC5),
    surface = Color(0xFF16181D),
    background = Color(0xFF101216)
)

@Composable
fun TesseraTheme(
    themeMode: ThemeMode = ThemeMode.AUTO,
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val darkTheme = when (themeMode) {
        ThemeMode.AUTO -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }
    MaterialTheme(colorScheme = colorScheme, content = content)
}
