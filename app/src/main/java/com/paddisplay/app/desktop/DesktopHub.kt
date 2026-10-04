package com.paddisplay.app.desktop

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.paddisplay.app.system.SystemDisplayService
import com.paddisplay.app.ui.MainViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

fun overlaySettings(context: Context) {
    context.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

@Composable
fun AppIcon(app: SystemDisplayService.LaunchableApp, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val bitmap by produceState<ImageBitmap?>(null, app.packageName) {
        value = withContext(Dispatchers.IO) {
            runCatching { context.packageManager.getApplicationIcon(app.packageName).toBitmap(96, 96).asImageBitmap() }.getOrNull()
        }
    }
    if (bitmap != null) Image(bitmap!!, app.label, modifier.size(48.dp))
    else Box(modifier.size(48.dp).background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(14.dp)), Alignment.Center) {
        Text(app.label.take(1), style = MaterialTheme.typography.headlineSmall)
    }
}

@Composable
fun AppGrid(apps: List<SystemDisplayService.LaunchableApp>, favorites: Set<String>, enabled: Boolean,
            onLaunch: (SystemDisplayService.LaunchableApp) -> Unit, onFavorite: (String) -> Unit,
            modifier: Modifier = Modifier) {
    var search by remember { mutableStateOf("") }
    var onlyFavorites by remember { mutableStateOf(false) }
    val visible = apps.distinctBy { it.component }.filter { it.packageName != "com.paddisplay.app" }.filter {
        (!onlyFavorites || it.packageName in favorites) &&
            (it.label.contains(search, true) || it.packageName.contains(search, true))
    }.sortedWith(compareByDescending<SystemDisplayService.LaunchableApp> { it.packageName in favorites }.thenBy { it.label })
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(search, { search = it }, singleLine = true, label = { Text("搜索应用") }, modifier = Modifier.weight(1f))
            FilterChip(onlyFavorites, { onlyFavorites = !onlyFavorites }, label = { Text("收藏") })
            Text("${visible.size} 个", style = MaterialTheme.typography.labelMedium)
        }
        if (visible.isEmpty()) Text(if (apps.isEmpty()) "连接 Shizuku 后刷新应用列表" else "没有匹配的应用")
        LazyVerticalGrid(GridCells.Adaptive(130.dp), modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(visible, key = { it.component }) { app ->
                Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Column(Modifier.fillMaxWidth().clickable(enabled) { onLaunch(app) }.padding(12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            Text(if (app.packageName in favorites) "★" else "☆", color = MaterialTheme.colorScheme.tertiary,
                                modifier = Modifier.clickable { onFavorite(app.packageName) }.padding(6.dp))
                        }
                        AppIcon(app)
                        Spacer(Modifier.height(10.dp))
                        Text(app.label, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                        Text("在外屏打开", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
fun TaskPanel(state: DesktopSnapshot, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    LazyColumn(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text("外屏窗口", style = MaterialTheme.typography.headlineSmall)
            Text("系统自由窗口可调整位置和尺寸；全屏应用仍可切换或关闭。", style = MaterialTheme.typography.bodySmall)
            state.taskError?.let { Text("任务读取失败：$it", color = MaterialTheme.colorScheme.error) }
            if (state.running && state.message != "主机模式未启动") {
                Text(state.message, style = MaterialTheme.typography.bodySmall,
                    color = if (state.message.contains("RESULT_OK=false")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (state.tasks.isEmpty() && state.taskError == null) Text("暂无外屏应用窗口")
        }
        items(state.tasks, key = { it.id }) { task ->
            val app = state.apps.firstOrNull { it.packageName == task.packageName }
            Card {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(app?.label ?: task.packageName, fontWeight = FontWeight.Bold)
                    Text("任务 ${task.id} · ${if (task.mode == 5) "自由窗口模式" else "系统窗口模式 ${task.mode}"} · (${task.left}, ${task.top})–(${task.right}, ${task.bottom})",
                        style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Button({ DesktopService.send(context, "focus", taskId = task.id) }, enabled = state.running) { Text("切换") }
                        OutlinedButton({ DesktopService.send(context, "close", taskId = task.id) }, enabled = state.running) { Text("关闭窗口") }
                        OutlinedButton({ DesktopService.send(context, if (task.mode == 5) "fullscreen" else "window", taskId = task.id) }, enabled = state.running) {
                            Text(if (task.mode == 5) "全屏" else "恢复窗口")
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("left" to "左半屏", "right" to "右半屏", "center" to "居中", "fill" to "铺满").forEach { (place, label) ->
                            TextButton({ DesktopService.send(context, "place", taskId = task.id, placement = place) }, enabled = state.running && task.mode == 5) { Text(label) }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        listOf("moveLeft" to "←", "up" to "↑", "down" to "↓", "moveRight" to "→", "larger" to "+", "smaller" to "−").forEach { (place, label) ->
                            TextButton({ DesktopService.send(context, "place", taskId = task.id, placement = place) },
                                enabled = state.running && task.mode == 5,
                                contentPadding = PaddingValues(horizontal = 8.dp), modifier = Modifier.weight(1f)) { Text(label) }
                        }
                    }
                }
            }
        }
    }
}
