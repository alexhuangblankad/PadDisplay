package com.paddisplay.app.desktop

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.paddisplay.app.ui.PadTheme
import kotlinx.coroutines.delay

/** Real external desktop: wallpaper, menu strip, shortcuts and Dock, with an optional Launchpad. */
class DesktopActivity : ComponentActivity() {
    private var launchpad by mutableStateOf(false)
    private var taskSwitcher by mutableStateOf(false)
    private var controlCenter by mutableStateOf(false)
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        launchpad = intent.getBooleanExtra("launchpad", false)
        taskSwitcher = intent.getBooleanExtra("tasks", false)
        controlCenter = intent.getBooleanExtra("controls", false)
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); enableEdgeToEdge()
        launchpad = intent.getBooleanExtra("launchpad", false)
        taskSwitcher = intent.getBooleanExtra("tasks", false)
        controlCenter = intent.getBooleanExtra("controls", false)
        setContent { PadTheme(this) {
            val state by DesktopState.state.collectAsState()
            BackHandler(!launchpad && !taskSwitcher && !controlCenter) { /* Desktop is the root of this external workspace. */ }
            LaunchedEffect(state.running) { if (!state.running) finish() }
            val clock by produceState("") {
                while (true) { value = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date()); delay(15000) }
            }
            val colors = MaterialTheme.colorScheme
            SideEffect {
                androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).apply {
                    isAppearanceLightStatusBars = colors.background.luminance() > .5f
                    isAppearanceLightNavigationBars = colors.background.luminance() > .5f
                }
            }
            BoxWithConstraints(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(colors.primaryContainer, colors.background, colors.secondaryContainer)))) {
                val dockWidth = (maxWidth - 360.dp).coerceIn(120.dp, 840.dp)
                Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(top = 28.dp, bottom = 96.dp)) {
                    Surface(color = colors.surface.copy(alpha = .88f)) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("◈  PadDisplay", fontWeight = FontWeight.Bold)
                            TextButton({ launchpad = true }) { Text("应用") }
                            TextButton({ taskSwitcher = true }) { Text("窗口") }
                            Spacer(Modifier.weight(1f))
                            TextButton({ controlCenter = true }) { Text("☷  控制中心") }
                            Text("Display ${state.displayId}  ·  $clock", style = MaterialTheme.typography.labelLarge)
                        }
                    }
                    Box(Modifier.weight(1f).fillMaxWidth().padding(36.dp)) {
                        Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(clock, style = MaterialTheme.typography.displayLarge, fontWeight = FontWeight.Light)
                            Text("你的外屏工作空间", color = colors.onSurfaceVariant)
                        }
                        Column(Modifier.align(Alignment.TopEnd), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            state.apps.distinctBy { it.packageName }.filter { it.packageName in state.favorites }.take(6).forEach { app ->
                                Surface(shape = RoundedCornerShape(18.dp), color = colors.surface.copy(alpha = .7f)) {
                                    Column(Modifier.width(106.dp).clickable { DesktopService.send(this@DesktopActivity, "launch", app.component) }.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                        AppIcon(app); Spacer(Modifier.height(8.dp)); Text(app.label, maxLines = 1, style = MaterialTheme.typography.labelMedium)
                                    }
                                }
                            }
                        }
                    }
                }
                // The desktop Dock must not depend on the overlay permission.
                if (!state.overlayReady) Surface(
                    Modifier.align(Alignment.BottomCenter).padding(bottom = 18.dp).widthIn(max = dockWidth),
                    shape = RoundedCornerShape(26.dp), color = colors.surface.copy(alpha = .94f), shadowElevation = 12.dp,
                ) {
                    Row(Modifier.horizontalScroll(rememberScrollState()).padding(10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton({ launchpad = true }) { Text("▦  应用") }
                        val dockApps = state.apps.distinctBy { it.packageName }.filter { it.packageName in state.favorites || state.tasks.any { t -> t.packageName == it.packageName } }.take(8)
                        dockApps.forEach { app ->
                            val task = state.tasks.firstOrNull { it.packageName == app.packageName }
                            Column(Modifier.width(64.dp).clickable {
                                if (task == null) DesktopService.send(this@DesktopActivity, "launch", app.component)
                                else DesktopService.send(this@DesktopActivity, "focus", taskId = task.id)
                            }, horizontalAlignment = Alignment.CenterHorizontally) {
                                AppIcon(app)
                                Text(if (task == null) " " else "●", style = MaterialTheme.typography.labelSmall, color = colors.primary)
                            }
                        }
                        TextButton({ taskSwitcher = true }) { Text("窗口") }
                    }
                }
                if (!state.overlayReady) Surface(Modifier.align(Alignment.BottomEnd).padding(16.dp), shape = RoundedCornerShape(20.dp), color = colors.surface.copy(alpha = .94f)) {
                    Row {
                        TextButton({ DesktopService.send(this@DesktopActivity, "back") }, Modifier.width(48.dp)) { Text("‹") }
                        TextButton({ launchpad = false; taskSwitcher = false; controlCenter = false }, Modifier.width(48.dp)) { Text("⌂") }
                        TextButton({ taskSwitcher = true }, Modifier.width(48.dp)) { Text("▣") }
                    }
                }
            }
            if (taskSwitcher) Dialog({ taskSwitcher = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
                Surface(Modifier.fillMaxWidth(.75f).fillMaxHeight(.8f), shape = RoundedCornerShape(28.dp)) {
                    Column(Modifier.padding(24.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("多任务", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                            TextButton({ taskSwitcher = false }) { Text("完成") }
                        }
                        TaskPanel(state, Modifier.weight(1f))
                    }
                }
            }
            if (controlCenter) DesktopControlCenter(this@DesktopActivity, state) { controlCenter = false }
            if (launchpad) Dialog({ launchpad = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
                Surface(Modifier.fillMaxWidth(.88f).fillMaxHeight(.85f), shape = RoundedCornerShape(28.dp), color = colors.background) {
                    Column(Modifier.padding(28.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("应用", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                            TextButton({ launchpad = false }) { Text("关闭") }
                        }
                        AppGrid(state.apps, state.favorites, state.running,
                            { launchpad = false; DesktopService.send(this@DesktopActivity, "launch", it.component) },
                            { DesktopService.send(this@DesktopActivity, "favorite", it) }, Modifier.weight(1f))
                    }
                }
            }
        } }
    }
}
