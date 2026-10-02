package com.paddisplay.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.paddisplay.app.display.DisplaySnapshot

/** 状态圆点 + 文本，例如「● Shizuku 已连接」。 */
@Composable
fun StatusRow(
    ok: Boolean,
    text: String,
    modifier: Modifier = Modifier,
    warn: Boolean = false,
) {
    val color = when {
        ok -> Color(0xFF6FD3C7)
        warn -> Color(0xFFFFC46B)
        else -> MaterialTheme.colorScheme.error
    }
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(9.dp)
                .clip(CircleShape)
                .background(color),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** 小节标题。 */
@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier,
    )
}

/** 键值对行，诊断信息用等宽字体。 */
@Composable
fun InfoRow(label: String, value: String, mono: Boolean = false) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1.4f),
        )
    }
}

/** 屏幕信息卡片（任务书第 11 节的「内置显示器 / 外接显示器」两块）。 */
@Composable
fun DisplayCard(
    display: DisplaySnapshot,
    isInternal: Boolean,
    modifier: Modifier = Modifier,
    onSetInternal: (() -> Unit)? = null,
    onSetExternal: (() -> Unit)? = null,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isInternal) {
                MaterialTheme.colorScheme.surfaceVariant
            } else {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
            },
        ),
        shape = RoundedCornerShape(14.dp),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = if (isInternal) "内置显示器" else "外接显示器",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.width(8.dp))
                if (!isInternal) {
                    Text(
                        text = display.typeLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.secondary,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = display.name,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = display.currentModeLabel,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(8.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f))
            Spacer(Modifier.height(8.dp))
            InfoRow("Display ID", display.displayId.toString(), mono = true)
            InfoRow("类型", "${display.typeName}", mono = true)
            InfoRow("状态", display.stateName, mono = true)
            InfoRow("逻辑尺寸", "${display.logicalWidth} × ${display.logicalHeight} @${display.logicalDensityDpi}dpi", mono = true)
            InfoRow("物理地址", display.address ?: "(不可读)", mono = true)
            InfoRow("物理屏 ID", display.physicalDisplayId?.toString() ?: "(不可读)", mono = true)
            InfoRow("物理 token", if (display.physicalTokenAvailable) "可用" else "不可用", mono = true)
            InfoRow("当前 Mode", display.currentModeLabel, mono = true)
            InfoRow("首选 Mode", display.preferredMode?.label ?: "(不可读)", mono = true)
            InfoRow("支持模式数", display.supportedModes.size.toString(), mono = true)
            InfoRow("判定依据", display.evidence.joinToString("|"), mono = true)
        }
    }
}

/** 支持的模式列表（只读展示，帮助用户确认硬件真实能力）。 */
@Composable
fun SupportedModesList(display: DisplaySnapshot, maxItems: Int = 12) {
    val modes = com.paddisplay.app.display.DisplayModeSelector.sortForDisplay(display.supportedModes)
    Column {
        SectionTitle("支持的模式（硬件上报）")
        Spacer(Modifier.height(4.dp))
        if (modes.isEmpty()) {
            Text(
                "该显示器未上报 supportedModes",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            modes.take(maxItems).forEach { m ->
                val isCurrent = com.paddisplay.app.display.DisplayModeSelector.isSameMode(
                    m,
                    display.currentMode,
                )
                Text(
                    text = "• modeId=${m.modeId}  ${m.label}${if (isCurrent) "   ← 当前" else ""}",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = if (isCurrent) {
                        MaterialTheme.colorScheme.secondary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            if (modes.size > maxItems) {
                Text(
                    "… 另有 ${modes.size - maxItems} 个模式，详见诊断信息",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
