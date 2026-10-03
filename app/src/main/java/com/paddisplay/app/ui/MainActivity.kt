package com.paddisplay.app.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.BackHandler
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Surface
import com.paddisplay.app.desktop.DesktopHub

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
    private var advancedRequested by mutableStateOf(false)
    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        advancedRequested = intent.getBooleanExtra("advanced", false)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PadTheme(this) {
                val vm: MainViewModel = viewModel()
                val ui by vm.ui.collectAsState()
                var controls by rememberSaveable { mutableStateOf(intent.getBooleanExtra("advanced", false)) }
                androidx.compose.runtime.LaunchedEffect(advancedRequested) {
                    if (advancedRequested) { controls = true; advancedRequested = false }
                }
                BackHandler(controls) { controls = false }
                val light = MaterialTheme.colorScheme.background.luminance() > .5f
                androidx.compose.runtime.SideEffect {
                    androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).apply {
                        isAppearanceLightStatusBars = light
                        isAppearanceLightNavigationBars = light
                    }
                }
                Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(top = 28.dp)) {
                if (controls) {
                    Column(Modifier.fillMaxSize()) {
                        TextButton({ controls = false }) { Text("← 返回桌面") }
                        Box(Modifier.weight(1f)) { MainScreen(vm = vm, ui = ui) }
                    }
                } else DesktopHub(vm, ui) { controls = true }
                }
            }
        }
    }
}
