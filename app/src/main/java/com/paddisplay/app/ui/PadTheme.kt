package com.paddisplay.app.ui

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

enum class Appearance(val label: String) { SYSTEM("跟随系统"), LIGHT("亮色"), DARK("暗色") }

object ThemeSettings {
    fun prefs(context: Context) = context.getSharedPreferences("appearance", Context.MODE_PRIVATE)
    fun read(context: Context) = runCatching { Appearance.valueOf(prefs(context).getString("mode", "SYSTEM")!!) }.getOrDefault(Appearance.SYSTEM)
    fun write(context: Context, mode: Appearance) { prefs(context).edit().putString("mode", mode.name).apply() }
    fun dark(context: Context): Boolean = when (read(context)) {
        Appearance.DARK -> true
        Appearance.LIGHT -> false
        Appearance.SYSTEM -> context.resources.configuration.uiMode and 0x30 == 0x20
    }
}

@Composable
fun rememberAppearance(context: Context): Appearance {
    var value by remember { mutableStateOf(ThemeSettings.read(context)) }
    DisposableEffect(context) {
        val prefs = ThemeSettings.prefs(context)
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> value = ThemeSettings.read(context) }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    return value
}

@Composable
fun PadTheme(context: Context, content: @Composable () -> Unit) {
    val appearance = rememberAppearance(context)
    val dark = appearance == Appearance.DARK || (appearance == Appearance.SYSTEM && isSystemInDarkTheme())
    val colors = if (dark) darkColorScheme(
        primary = Color(0xFFA9C7FF), onPrimary = Color(0xFF122C54),
        background = Color(0xFF11151D), surface = Color(0xFF1A202A),
        surfaceVariant = Color(0xFF293341), onSurface = Color(0xFFE8EDF5),
        onSurfaceVariant = Color(0xFFB5C1D1), secondary = Color(0xFF95D7C5),
        secondaryContainer = Color(0xFF293E5A), onSecondaryContainer = Color(0xFFDAE7FA),
        tertiary = Color(0xFFF8CF79)
    ) else lightColorScheme(
        primary = Color(0xFF315EAA), onPrimary = Color.White,
        background = Color(0xFFF3F5FA), surface = Color.White,
        surfaceVariant = Color(0xFFE5EBF4), onSurface = Color(0xFF202B3D),
        onSurfaceVariant = Color(0xFF526177), secondary = Color(0xFF216A58),
        secondaryContainer = Color(0xFFE2EBFA), onSecondaryContainer = Color(0xFF244C88),
        tertiary = Color(0xFF99620C)
    )
    MaterialTheme(colorScheme = colors, shapes = Shapes(
        small = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
        medium = androidx.compose.foundation.shape.RoundedCornerShape(18.dp),
        large = androidx.compose.foundation.shape.RoundedCornerShape(26.dp)
    )) {
        Surface(color = colors.background, contentColor = colors.onSurface, content = content)
    }
}
