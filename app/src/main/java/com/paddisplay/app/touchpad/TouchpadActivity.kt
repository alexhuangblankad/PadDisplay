package com.paddisplay.app.touchpad

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.paddisplay.app.shizuku.ShizukuManager
import com.paddisplay.app.system.SystemDisplayService

/**
 * 触控板页面：用平板当外接屏的触控板。
 *
 * ## 为什么需要它（也为什么不做"鼠标跨屏"）
 *
 * 本机 `DisplayTopology` 的 feature flag 被 ColorOS 关闭，
 * 而"鼠标自由跨两块屏"**只**依赖那个能力（AOSP 里
 * `mInputManagerInternal.setDisplayTopology(graph)` 才是指针跨屏的机制）。
 * 非 root 打不开它。
 *
 * 所以走已跑通的另一条路（参考项目 AdaptiveScreenPlus 的架构）：
 * - **光标自己画**在外接屏上（[CursorOverlay]）—— 系统只给真实鼠标画指针；
 * - **点击/移动靠注入**到外接屏（`IInputManager.injectInputEvent` +
 *   `MotionEvent.setDisplayId`）；
 * - **活动范围从系统侧量**（`dumpsys window displays` 的 `cur=WxH`），
 *   不能用 `Display.getRealSize()`（会被应用兼容缩放污染）。
 *
 * 结果：手指在平板上滑动 → 外接屏上的光标跟着走 → 轻点就是单击。
 */
class TouchpadActivity : ComponentActivity() {

    companion object {
        /** 悬浮窗权限请求码 */
        private const val REQ_OVERLAY = 0x7A01

        fun start(context: Context) {
            context.startActivity(
                Intent(context, TouchpadActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    private var shellRunner: ((String) -> String)? = null
    private var externalDisplayId: Int = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val service = SystemDisplayService(applicationContext)
        // 触控板页面的目标是外接屏
        externalDisplayId = service.primaryExternal()?.displayId ?: -1

        setContent {
            TouchpadScreen(
                externalDisplayId = externalDisplayId,
                onNeedOverlay = { requestOverlayPermission() },
                onStart = { startCursorAndTest() },
                onStop = { CursorOverlay.hide() },
                onClick = { x, y -> injectClick(x, y) },
                onMove = { x, y -> moveCursor(x, y) },
                onPress = { CursorOverlay.setHot(it) },
                onRightClick = { x, y -> injectRightClick(x, y) },
            )
        }
    }

    // ------------------------------------------------------------------
    // 权限
    // ------------------------------------------------------------------

    private fun hasOverlayPermission(): Boolean = Settings.canDrawOverlays(this)

    private fun requestOverlayPermission() {
        if (hasOverlayPermission()) return
        runCatching {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName"),
                ),
            )
        }
    }

    // ------------------------------------------------------------------
    // 光标与注入
    // ------------------------------------------------------------------

    private fun shell(): (String) -> String = shellRunner ?: run {
        // 用 Shizuku UserService 执行 shell
        val run: (String) -> String = { cmd ->
            val svc = ShizukuManager.service
            if (svc == null) {
                ""
            } else {
                runCatching { svc.execCommand(cmd) }.getOrDefault("")
            }
        }
        shellRunner = run
        run
    }

    private fun startCursorAndTest() {
        if (externalDisplayId < 0) {
            toast("未检测到外接屏，先把显示器接上")
            return
        }
        if (!hasOverlayPermission()) {
            toast("需要「显示在其他应用上层」权限才能画光标")
            requestOverlayPermission()
            return
        }
        val ok = CursorOverlay.show(applicationContext, externalDisplayId, shell())
        toast(
            if (ok) "光标已挂到外接屏，滑动试试" else "挂光标失败（见日志）",
        )
    }

    private fun moveCursor(x: Int, y: Int) {
        if (!CursorOverlay.isOn()) return
        CursorOverlay.move(x, y)
        // 拖动时高频注入移动事件，让外屏上的应用真正收到指针
        val svc = ShizukuManager.service ?: return
        runCatching { svc.injectPointerMove(externalDisplayId, CursorOverlay.x(), CursorOverlay.y()) }
    }

    private fun injectClick(x: Int, y: Int) {
        val svc = ShizukuManager.service
        if (svc == null) {
            toast("Shizuku 未连接，无法注入点击")
            return
        }
        CursorOverlay.setHot(true)
        runCatching {
            svc.injectPointer(externalDisplayId, 0, x, y, 1) // DOWN + 左键
            svc.injectPointer(externalDisplayId, 1, x, y, 1) // UP
        }
        CursorOverlay.setHot(false)
    }

    private fun injectRightClick(x: Int, y: Int) {
        val svc = ShizukuManager.service ?: return
        runCatching {
            svc.injectPointer(externalDisplayId, 0, x, y, 2) // DOWN + 右键
            svc.injectPointer(externalDisplayId, 1, x, y, 2) // UP
        }
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}

@Composable
private fun TouchpadScreen(
    externalDisplayId: Int,
    onNeedOverlay: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onClick: (Int, Int) -> Unit,
    onMove: (Int, Int) -> Unit,
    onPress: (Boolean) -> Unit,
    onRightClick: (Int, Int) -> Unit,
) {
    val context = LocalContext.current
    val canControl = ShizukuManager.state.value.canControl
    var sensitivity by remember { mutableStateOf(2.2f) }
    var status by remember { mutableStateOf("未启动") }

    Column(
        Modifier
            .fillMaxSize()
            .padding(12.dp),
    ) {
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(Modifier.padding(12.dp)) {
                Text("触控板（控制外接屏）", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Text(
                    "外接屏 id=$externalDisplayId　状态：$status　Shizuku: " +
                        if (canControl) "已连接" else "未连接",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        onStart()
                        status = if (CursorOverlay.isOn()) "光标已启用" else "启动失败（检查悬浮窗权限）"
                    }) { Text("启用光标") }
                    OutlinedButton(onClick = onNeedOverlay) { Text("悬浮窗权限") }
                    OutlinedButton(onClick = {
                        onStop()
                        status = "已停止"
                    }) { Text("停止") }
                }
            }
        }

        Spacer(Modifier.height(10.dp))

        Card(
            Modifier
                .fillMaxWidth()
                .weight(1f),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        ) {
            AndroidView(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(8.dp),
                factory = { ctx ->
                    TouchpadView(ctx).apply {
                        this.sensitivity = sensitivity
                        this.onMove = { x, y -> onMove(x, y) }
                        this.onTap = { x, y -> onClick(x, y) }
                        this.onPress = { onPress(it) }
                    }
                },
                update = { v -> v.sensitivity = sensitivity },
            )
        }

        Spacer(Modifier.height(10.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { onClick(CursorOverlay.x(), CursorOverlay.y()) }) {
                Text("单击")
            }
            OutlinedButton(onClick = { onRightClick(CursorOverlay.x(), CursorOverlay.y()) }) {
                Text("右键")
            }
            OutlinedButton(onClick = { sensitivity = (sensitivity - 0.4f).coerceAtLeast(0.6f) }) {
                Text("慢")
            }
            OutlinedButton(onClick = { sensitivity = (sensitivity + 0.4f).coerceAtMost(6f) }) {
                Text("快")
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "灵敏度 ${"%.1f".format(sensitivity)}　（滑动=移动光标，轻点=单击）",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
