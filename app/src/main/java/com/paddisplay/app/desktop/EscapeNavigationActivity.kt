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
        window.attributes = window.attributes.apply { y = (24 * resources.displayMetrics.density).toInt() }
        setContent { PadTheme(this) {
            val state by DesktopState.state.collectAsState()
            LaunchedEffect(state.running) { if (!state.running) finish() }
            LaunchedEffect(Unit) { delay(15000); finish() }
            Surface(shape = RoundedCornerShape(24.dp), shadowElevation = 12.dp) {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    Text("全屏导航", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(start = 12.dp))
                    Row {
                        TextButton({ finish(); DesktopService.send(this@EscapeNavigationActivity, "back") }) { Text("返回") }
                        TextButton({ finish(); DesktopService.send(this@EscapeNavigationActivity, "home") }) { Text("Home") }
                        TextButton({ finish(); DesktopService.send(this@EscapeNavigationActivity, "tasks") }) { Text("多任务") }
                    }
                }
            }
        } }
        window.setLayout(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT)
    }
}
