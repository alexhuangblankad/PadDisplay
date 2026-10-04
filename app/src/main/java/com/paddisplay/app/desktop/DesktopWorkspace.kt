package com.paddisplay.app.desktop

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.paddisplay.app.ui.PadTheme
import kotlinx.coroutines.delay

/** The desktop is a full-screen background task, separate from freeform application windows. */
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
            val dock = remember(state.apps, state.favorites) { DockApps.select(state.apps, state.favorites) }
            BackHandler(!launchpad && !taskSwitcher && !controlCenter) { }
            LaunchedEffect(state.running) { if (!state.running) finishAndRemoveTask() }
            val clock by produceState("") {
                while (true) { value = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date()); delay(15000) }
            }
            val colors = MaterialTheme.colorScheme
            val dark = colors.background.luminance() < .5f
            SideEffect {
                androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).apply {
                    isAppearanceLightStatusBars = !dark; isAppearanceLightNavigationBars = !dark
                }
            }
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val wide = maxWidth >= 780.dp
                Canvas(Modifier.fillMaxSize()) {
                    drawRect(Brush.linearGradient(if (dark) listOf(Color(0xFF14243D),Color(0xFF264F69),Color(0xFF346A71)) else listOf(Color(0xFFECF2F9),Color(0xFFD7E5F0),Color(0xFFBAD6DC))))
                    drawCircle(Color.White.copy(alpha = if(dark) .04f else .23f), size.width*.44f, Offset(size.width*.87f,size.height*.08f))
                    val wave = Path().apply {
                        moveTo(0f,size.height*.75f)
                        cubicTo(size.width*.25f,size.height*.37f,size.width*.49f,size.height*1.08f,size.width,size.height*.45f)
                        lineTo(size.width,size.height); lineTo(0f,size.height); close()
                    }
                    drawPath(wave, Brush.linearGradient(listOf(Color(0xFF87BEC6).copy(alpha=.16f),Color(0xFF304F7A).copy(alpha=.18f))))
                }
                Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                    Row(Modifier.fillMaxWidth().padding(horizontal=28.dp,vertical=20.dp), verticalAlignment=Alignment.CenterVertically) {
                        Text("PadDisplay", fontWeight=FontWeight.SemiBold, style=MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.weight(1f))
                        Text("桌面工作空间", color=colors.onSurfaceVariant, maxLines=1, style=MaterialTheme.typography.labelMedium)
                    }
                    Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal=24.dp,vertical=12.dp)) {
                        LazyVerticalGrid(GridCells.Fixed(if(wide) 2 else 3),
                            Modifier.widthIn(max=if(wide) 272.dp else 600.dp).fillMaxHeight(),
                            verticalArrangement=Arrangement.spacedBy(16.dp),horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                            items(dock, key={it.component}) { app ->
                                Column(Modifier.clickable { DesktopService.send(this@DesktopActivity,"launch",app.component) }.padding(12.dp),horizontalAlignment=Alignment.CenterHorizontally) {
                                    Surface(shape=RoundedCornerShape(16.dp),color=colors.surface.copy(alpha=.65f)) {
                                        Box(Modifier.padding(10.dp)) { AppIcon(app) }
                                    }
                                    Spacer(Modifier.height(8.dp))
                                    Text(app.label,maxLines=2,overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.labelMedium)
                                }
                            }
                        }
                        if(wide) Column(Modifier.align(Alignment.TopEnd).padding(top=16.dp,end=16.dp),horizontalAlignment=Alignment.End) {
                            Text(clock,style=MaterialTheme.typography.displayMedium,fontWeight=FontWeight.Light)
                            Text(java.text.SimpleDateFormat("M月d日 EEEE",java.util.Locale.CHINA).format(java.util.Date()),color=colors.onSurfaceVariant,style=MaterialTheme.typography.bodyMedium)
                        }
                    }
                    // This footer remains useful without any overlay permission or Google services.
                    Surface(color=colors.surface.copy(alpha=.94f),shadowElevation=10.dp) {
                        Row(Modifier.fillMaxWidth().height(76.dp).padding(horizontal=12.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(4.dp)) {
                            TextButton({launchpad=true}) { Text(if(wide) "▦  应用" else "▦",style=MaterialTheme.typography.titleMedium) }
                            Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(4.dp),verticalAlignment=Alignment.CenterVertically) {
                                dock.forEach { app ->
                                    val task=state.tasks.firstOrNull { it.packageName==app.packageName }
                                    Column(Modifier.width(60.dp).clickable {
                                        if(task==null) DesktopService.send(this@DesktopActivity,"launch",app.component) else DesktopService.send(this@DesktopActivity,"focus",taskId=task.id)
                                    }.padding(top=6.dp),horizontalAlignment=Alignment.CenterHorizontally) {
                                        AppIcon(app)
                                        Text(if(task==null) " " else "●",color=colors.primary,style=MaterialTheme.typography.labelSmall)
                                    }
                                }
                                state.tasks.filter { t -> dock.none { it.packageName==t.packageName } }.forEach { task ->
                                    TextButton({DesktopService.send(this@DesktopActivity,"focus",taskId=task.id)}) {
                                        Text(state.apps.firstOrNull{it.packageName==task.packageName}?.label ?: task.packageName.substringAfterLast('.'),maxLines=1)
                                    }
                                }
                            }
                            TextButton({controlCenter=true}) { Text("☷") }
                            if(wide) Text(clock,style=MaterialTheme.typography.labelLarge,modifier=Modifier.padding(horizontal=8.dp))
                            TextButton({DesktopService.send(this@DesktopActivity,"back")},Modifier.width(48.dp)) { Text("‹") }
                            TextButton({launchpad=false;taskSwitcher=false;controlCenter=false},Modifier.width(48.dp)) { Text("⌂") }
                            TextButton({taskSwitcher=true},Modifier.width(48.dp)) { Text("▣") }
                        }
                    }
                }
            }
            if(taskSwitcher) Dialog({taskSwitcher=false},properties=DialogProperties(usePlatformDefaultWidth=false)) {
                Surface(Modifier.fillMaxWidth(.8f).fillMaxHeight(.8f),shape=RoundedCornerShape(24.dp)) {
                    Column(Modifier.padding(24.dp)) {
                        Row(verticalAlignment=Alignment.CenterVertically) {
                            Text("多任务",style=MaterialTheme.typography.headlineSmall,modifier=Modifier.weight(1f))
                            TextButton({taskSwitcher=false}) {Text("完成")}
                        }
                        TaskPanel(state,Modifier.weight(1f))
                    }
                }
            }
            if(controlCenter) DesktopControlCenter(this@DesktopActivity,state) {controlCenter=false}
            if(launchpad) Dialog({launchpad=false},properties=DialogProperties(usePlatformDefaultWidth=false)) {
                Surface(Modifier.fillMaxWidth(.85f).widthIn(max=1200.dp).fillMaxHeight(.85f),shape=RoundedCornerShape(24.dp),color=colors.background) {
                    Column(Modifier.padding(24.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
                        Row(verticalAlignment=Alignment.CenterVertically) {
                            Text("全部应用",style=MaterialTheme.typography.headlineSmall,modifier=Modifier.weight(1f))
                            TextButton({launchpad=false}) {Text("关闭")}
                        }
                        AppGrid(state.apps,state.favorites,state.running,
                            {launchpad=false;DesktopService.send(this@DesktopActivity,"launch",it.component)},
                            {DesktopService.send(this@DesktopActivity,"favorite",it)},Modifier.weight(1f))
                    }
                }
            }
        } }
    }
}
