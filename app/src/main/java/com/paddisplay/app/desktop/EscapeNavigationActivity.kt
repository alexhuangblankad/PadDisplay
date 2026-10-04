package com.paddisplay.app.desktop

import android.os.Bundle
import android.view.Gravity
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.paddisplay.app.ui.PadTheme
import kotlinx.coroutines.delay

/** A real, focusable dialog activity: temporarily frees the stream client's pointer capture through focus loss. */
class EscapeNavigationActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        window.setGravity(Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
        // A single bottom taskbar replaces the separate rescue dialog and overlays.
        window.attributes = window.attributes.apply { y = 0 }
        setContent { PadTheme(this) {
            val state by DesktopState.state.collectAsState()
            LaunchedEffect(state.running) { if (!state.running) finishAndRemoveTask() }
            LaunchedEffect(Unit) { delay(15000); finish() }
            Surface(color = MaterialTheme.colorScheme.surface, shadowElevation = 6.dp) {
                Row(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 16.dp),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    fun act(action: String, component: String? = null) {
                        finish(); DesktopService.send(this@EscapeNavigationActivity, action, component)
                    }
                    TextButton({ act("launchpad") }) { Text("应用") }
                    DockApps.select(state.apps, state.favorites, 8).forEach { app ->
                        androidx.compose.material3.IconButton({ act("launch", app.component) }) { AppIcon(app, Modifier.size(34.dp)) }
                    }
                    Spacer(Modifier.weight(1f))
                    TextButton({ act("controls") }) { Text("☷") }
                    Text(java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date()), style = MaterialTheme.typography.labelMedium)
                    TextButton({ act("back") }) { Text("‹") }
                    TextButton({ act("home") }) { Text("⌂") }
                    TextButton({ act("tasks") }) { Text("▣") }
                }
            }
        } }
        window.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT)
    }
}
