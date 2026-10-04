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
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.paddisplay.app.ui.PadTheme
import com.paddisplay.app.system.SystemDisplayService
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
                    drawRect(Brush.linearGradient(if (dark) listOf(Color(0xFF0C1020),Color(0xFF20233E)) else listOf(Color(0xFFF3F0F7),Color(0xFFE7EAF3))))
                    listOf(Triple(.72f,.30f,Color(0xFF7977DA)),Triple(.90f,.76f,Color(0xFF79B7C9)),Triple(.43f,.86f,Color(0xFFD8A4B5))).forEach { (x,y,tint) ->
                        val center=Offset(size.width*x,size.height*y)
                        val radius=size.width*.47f
                        drawCircle(Brush.radialGradient(listOf(tint.copy(alpha=if(dark) .24f else .27f),Color.Transparent),center,radius),radius,center)
                    }
                }
                Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                    Row(Modifier.fillMaxWidth().padding(horizontal=28.dp,vertical=20.dp), verticalAlignment=Alignment.CenterVertically) {
                        Column {
                            Text("PadDisplay", fontWeight=FontWeight.SemiBold, style=MaterialTheme.typography.titleMedium)
                            Text("桌面",color=colors.onSurfaceVariant,style=MaterialTheme.typography.labelSmall)
                        }
                        Spacer(Modifier.weight(1f))
                        Column(horizontalAlignment=Alignment.End) {
                            Text(clock,style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.Medium)
                            Text(java.text.SimpleDateFormat("M月d日 EEEE",java.util.Locale.CHINA).format(java.util.Date()),color=colors.onSurfaceVariant,style=MaterialTheme.typography.labelSmall)
                        }
                    }
                    Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal=24.dp,vertical=12.dp)) {
                        LazyVerticalGrid(GridCells.Fixed(if(wide) 2 else 3),
                            Modifier.widthIn(max=if(wide) 240.dp else 600.dp).fillMaxHeight(),
                            verticalArrangement=Arrangement.spacedBy(16.dp),horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                            items(dock, key={it.component}) { app ->
                                DesktopShortcut(app,false,false) { DesktopService.send(this@DesktopActivity,"launch",app.component) }
                            }
                        }
                    }
                    // This footer remains useful without any overlay permission or Google services.
                    Surface(color=colors.surface.copy(alpha=.96f),shadowElevation=4.dp) {
                        Row(Modifier.fillMaxWidth().height(64.dp).padding(horizontal=16.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                            DesktopTool("apps","全部应用") {launchpad=true}
                            Box(Modifier.width(1.dp).height(24.dp).background(colors.outlineVariant))
                            Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(4.dp),verticalAlignment=Alignment.CenterVertically) {
                                dock.forEach { app ->
                                    val task=state.tasks.firstOrNull { it.packageName==app.packageName }
                                    DesktopShortcut(app,true,task!=null) {
                                        if(task==null) DesktopService.send(this@DesktopActivity,"launch",app.component) else DesktopService.send(this@DesktopActivity,"focus",taskId=task.id)
                                    }
                                }
                                state.tasks.filter { t -> dock.none { it.packageName==t.packageName } }.forEach { task ->
                                    TextButton({DesktopService.send(this@DesktopActivity,"focus",taskId=task.id)}) {
                                        Text(state.apps.firstOrNull{it.packageName==task.packageName}?.label ?: task.packageName.substringAfterLast('.'),maxLines=1)
                                    }
                                }
                            }
                            DesktopTool("controls","控制中心") {controlCenter=true}
                            if(wide) Text(clock,style=MaterialTheme.typography.labelLarge,modifier=Modifier.padding(horizontal=8.dp))
                            Box(Modifier.width(1.dp).height(24.dp).background(colors.outlineVariant))
                            DesktopTool("back","返回") {DesktopService.send(this@DesktopActivity,"back")}
                            DesktopTool("home","桌面") {launchpad=false;taskSwitcher=false;controlCenter=false}
                            DesktopTool("tasks","多任务") {taskSwitcher=true}
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

@Composable
private fun DesktopShortcut(app: SystemDisplayService.LaunchableApp, compact: Boolean, running: Boolean, action: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val colors = MaterialTheme.colorScheme
    Column(Modifier.width(if(compact) 52.dp else 104.dp)
        .background(if(hovered) colors.primary.copy(alpha=.10f) else Color.Transparent,RoundedCornerShape(12.dp))
        .hoverable(interaction).clickable(interactionSource=interaction,indication=null,onClick=action)
        .padding(horizontal=8.dp,vertical=if(compact) 6.dp else 14.dp),horizontalAlignment=Alignment.CenterHorizontally) {
        AppIcon(app,Modifier.size(if(compact) 34.dp else 48.dp))
        if(compact) Box(Modifier.padding(top=4.dp).width(14.dp).height(3.dp)
            .background(if(running) colors.primary else Color.Transparent,RoundedCornerShape(2.dp)))
        else {
            Spacer(Modifier.height(10.dp))
            Text(app.label,maxLines=1,overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.labelMedium)
        }
    }
}

/** Consistent vector controls, independent of the ROM's text-symbol font. */
@Composable
private fun DesktopTool(kind: String, label: String, action: () -> Unit) {
    val tint=MaterialTheme.colorScheme.onSurfaceVariant
    IconButton(onClick=action,modifier=Modifier.size(40.dp).semantics {contentDescription=label}) {
        Canvas(Modifier.size(20.dp)) {
            val s=size.width; val stroke=Stroke(1.7.dp.toPx())
            fun line(x:Float,y:Float,x2:Float,y2:Float)=drawLine(tint,Offset(s*x,s*y),Offset(s*x2,s*y2),stroke.width)
            when(kind) {
                "apps" -> for(x in 0..2) for(y in 0..2) drawRoundRect(tint,Offset(s*(.08f+x*.32f),s*(.08f+y*.32f)),androidx.compose.ui.geometry.Size(s*.17f,s*.17f),androidx.compose.ui.geometry.CornerRadius(s*.025f))
                "back" -> {line(.65f,.15f,.30f,.50f);line(.30f,.50f,.65f,.85f)}
                "home" -> {line(.1f,.45f,.5f,.1f);line(.5f,.1f,.9f,.45f);line(.23f,.36f,.23f,.88f);line(.23f,.88f,.77f,.88f);line(.77f,.88f,.77f,.36f)}
                "tasks" -> {drawRoundRect(tint,Offset(s*.1f,s*.1f),androidx.compose.ui.geometry.Size(s*.56f,s*.56f),androidx.compose.ui.geometry.CornerRadius(s*.07f),style=stroke);line(.82f,.35f,.82f,.85f);line(.82f,.85f,.35f,.85f)}
                else -> for(i in 0..2) {val y=.2f+i*.3f;line(.08f,y,.92f,y);drawCircle(tint,s*.09f,Offset(s*(if(i==1) .35f else .65f),s*y))}
            }
        }
    }
}
