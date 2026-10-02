package com.paddisplay.app.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.viewmodel.compose.viewModel

/** PadDisplay 深色主题（控制外接显示器时深色更省眼）。 */
private val PadDisplayColors = darkColorScheme(
    primary = Color(0xFF7FB2FF),
    onPrimary = Color(0xFF00284F),
    primaryContainer = Color(0xFF1B3A63),
    onPrimaryContainer = Color(0xFFD6E3FF),
    secondary = Color(0xFF6FD3C7),
    onSecondary = Color(0xFF00382F),
    tertiary = Color(0xFFFFB4A9),
    background = Color(0xFF0F1317),
    onBackground = Color(0xFFE2E2E6),
    surface = Color(0xFF171C21),
    onSurface = Color(0xFFE2E2E6),
    surfaceVariant = Color(0xFF232A31),
    onSurfaceVariant = Color(0xFFBFC7D1),
    error = Color(0xFFFFB4AB),
)

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = PadDisplayColors) {
                val vm: MainViewModel = viewModel()
                val ui by vm.ui.collectAsState()
                MainScreen(vm = vm, ui = ui)
            }
        }
    }
}
