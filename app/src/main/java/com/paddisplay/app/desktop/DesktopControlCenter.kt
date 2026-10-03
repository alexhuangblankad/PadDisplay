package com.paddisplay.app.desktop

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.app.ActivityOptions
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.paddisplay.app.ui.*
import com.paddisplay.app.shizuku.ShizukuManager
import kotlinx.coroutines.*
import org.json.JSONObject

/** Controls reflect real system volume. Display controls retain the existing confirmation workflow. */
@Composable
fun DesktopControlCenter(context: Context, state: DesktopSnapshot, dismiss: () -> Unit) {
    val audio = remember { context.getSystemService(AudioManager::class.java) }
    var volume by remember { mutableFloatStateOf(audio.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat()) }
    val maximum = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
    val appearance = rememberAppearance(context)
    val scope = rememberCoroutineScope()
    var brightness by remember { mutableFloatStateOf(.5f) }
    var confirmedBrightness by remember { mutableFloatStateOf(.5f) }
    var brightnessReady by remember { mutableStateOf(false) }
    var brightnessError by remember { mutableStateOf<String?>(null) }
    var brightnessBusy by remember { mutableStateOf(false) }
    suspend fun updateBrightness(value: Float) {
        brightnessBusy = true
        runCatching {
            val result = withContext(Dispatchers.IO) { JSONObject(ShizukuManager.service?.internalBrightness(value) ?: error("Shizuku 未连接")) }
            check(result.optBoolean("ok")) { result.optString("error", "亮度控制不可用") }
            brightness = result.getDouble("value").toFloat(); confirmedBrightness = brightness; brightnessReady = true; brightnessError = null
        }.onFailure { brightness = confirmedBrightness; brightnessError = it.message }
        brightnessBusy = false
    }
    LaunchedEffect(Unit) { updateBrightness(-1f) }
    Dialog(dismiss) {
        Surface(shape = androidx.compose.foundation.shape.RoundedCornerShape(28.dp)) {
            Column(Modifier.widthIn(max = 440.dp).heightIn(max = 700.dp).verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("控制中心", style = MaterialTheme.typography.headlineSmall)
                Text("媒体音量  ${volume.toInt()} / $maximum")
                Slider(volume, { volume = it }, valueRange = 0f..maximum.toFloat(), onValueChangeFinished = {
                    audio.setStreamVolume(AudioManager.STREAM_MUSIC, volume.toInt(), 0)
                    volume = audio.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat()
                })
                Text(if (brightnessReady) "平板亮度  ${(brightness * 100).toInt()}%" else "平板亮度 · 暂不可用")
                Slider(brightness, { brightness = it }, enabled = brightnessReady && !brightnessBusy,
                    onValueChangeFinished = { scope.launch { updateBrightness(brightness) } })
                brightnessError?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("Wi-Fi" to Settings.ACTION_WIFI_SETTINGS, "蓝牙" to Settings.ACTION_BLUETOOTH_SETTINGS).forEach { (label, action) ->
                        OutlinedButton({
                            runCatching { context.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), ActivityOptions.makeBasic().setLaunchDisplayId(state.displayId).toBundle()) }
                                .onFailure { brightnessError = "系统设置未打开：${it.message}" }
                        }) { Text(label) }
                    }
                }
                Text("外观", style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Appearance.entries.forEach { mode ->
                        FilterChip(mode == appearance, { ThemeSettings.write(context, mode) }, label = { Text(mode.label) })
                    }
                }
                Text("外屏 Display ${state.displayId}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedButton({
                    context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("advanced", true))
                    dismiss()
                }) { Text("显示缩放、分辨率与音频输出…") }
                TextButton(dismiss, Modifier.fillMaxWidth()) { Text("完成") }
            }
        }
    }
}
