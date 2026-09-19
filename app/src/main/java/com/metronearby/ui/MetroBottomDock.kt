package com.metronearby.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.metronearby.domain.LineVisuals

/** 底部工具栏的稳定入口；后续新增常用素材或页面时从这里统一扩展。 */
enum class DockDestination(val label: String, val icon: DockIcon) {
    HOME("首页", DockIcon.HOME),
    SUBSCRIPTIONS("订阅", DockIcon.HEART),
    SCHEDULE("班次表", DockIcon.SCHEDULE),
    SHORT_TURN("区间车", DockIcon.SHORT_TURN),
    LINE_PICKER("选线路", DockIcon.LINES),
    NETWORK_MAP("线网图", DockIcon.MAP)
}

enum class DockIcon { HOME, HEART, SCHEDULE, SHORT_TURN, LINES, MAP }

@Composable
fun MetroBottomDock(
    selected: DockDestination?,
    onNavigate: (DockDestination) -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            // Scaffold 的底栏在全面屏设备上仍可能落进手势导航区；显式吃掉
            // navigation bar inset，再留一点视觉间距，避免与 Home 手势条相碰。
            .navigationBarsPadding()
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .shadow(10.dp, RoundedCornerShape(28.dp)),
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 6.dp
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            DockDestination.entries.forEach { destination ->
                DockItem(
                    destination = destination,
                    selected = destination == selected,
                    onClick = { onNavigate(destination) }
                )
            }
        }
    }
}

/** 顶栏“设置”使用的轻量齿轮；与底栏图标一样由 Canvas 绘制，不引入图标依赖。 */
@Composable
fun SettingsGlyph(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary
) {
    Canvas(modifier.size(20.dp)) {
        val strokeWidth = 1.8.dp.toPx()
        val stroke = Stroke(width = strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round)
        drawCircle(color = color, radius = size.minDimension * .29f, style = stroke)
        drawCircle(color = color, radius = size.minDimension * .10f, style = stroke)
        val spokes = listOf(
            Offset(.50f, .05f) to Offset(.50f, .20f),
            Offset(.50f, .80f) to Offset(.50f, .95f),
            Offset(.05f, .50f) to Offset(.20f, .50f),
            Offset(.80f, .50f) to Offset(.95f, .50f),
            Offset(.18f, .18f) to Offset(.29f, .29f),
            Offset(.71f, .71f) to Offset(.82f, .82f),
            Offset(.82f, .18f) to Offset(.71f, .29f),
            Offset(.29f, .71f) to Offset(.18f, .82f)
        )
        spokes.forEach { (start, end) ->
            drawLine(
                color,
                Offset(size.width * start.x, size.height * start.y),
                Offset(size.width * end.x, size.height * end.y),
                strokeWidth,
                StrokeCap.Round
            )
        }
    }
}

@Composable
private fun RowScope.DockItem(
    destination: DockDestination,
    selected: Boolean,
    onClick: () -> Unit
) {
    val contentColor = if (selected) MaterialTheme.colorScheme.primary
    else MaterialTheme.colorScheme.onSurfaceVariant
    val background = if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
    Column(
        modifier = Modifier
            .weight(1f)
            .clip(RoundedCornerShape(20.dp))
            .background(background)
            .clickable(onClick = onClick)
            .semantics {
                contentDescription = destination.label
                this.selected = selected
            }
            .padding(horizontal = 2.dp, vertical = 5.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        DockGlyph(destination.icon, contentColor)
        Spacer(Modifier.height(2.dp))
        Text(
            destination.label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            color = contentColor,
            maxLines = 1
        )
    }
}

@Composable
private fun DockGlyph(icon: DockIcon, color: Color) {
    Canvas(Modifier.size(25.dp)) {
        val w = size.width
        val h = size.height
        val stroke = Stroke(
            width = 2.dp.toPx(),
            cap = StrokeCap.Round,
            join = StrokeJoin.Round
        )
        when (icon) {
            DockIcon.HOME -> {
                val roof = Path().apply {
                    moveTo(w * .14f, h * .47f)
                    lineTo(w * .50f, h * .16f)
                    lineTo(w * .86f, h * .47f)
                }
                drawPath(roof, color, style = stroke)
                drawRoundRect(
                    color = color,
                    topLeft = Offset(w * .24f, h * .43f),
                    size = Size(w * .52f, h * .39f),
                    cornerRadius = CornerRadius(w * .05f),
                    style = stroke
                )
                drawLine(color, Offset(w * .50f, h * .82f), Offset(w * .50f, h * .63f), stroke.width, StrokeCap.Round)
            }

            DockIcon.HEART -> {
                val heart = Path().apply {
                    moveTo(w * .50f, h * .84f)
                    cubicTo(w * .42f, h * .75f, w * .15f, h * .58f, w * .15f, h * .35f)
                    cubicTo(w * .15f, h * .14f, w * .41f, h * .10f, w * .50f, h * .30f)
                    cubicTo(w * .59f, h * .10f, w * .85f, h * .14f, w * .85f, h * .35f)
                    cubicTo(w * .85f, h * .58f, w * .58f, h * .75f, w * .50f, h * .84f)
                    close()
                }
                drawPath(heart, color, style = stroke)
            }

            DockIcon.SCHEDULE -> {
                drawRoundRect(
                    color = color,
                    topLeft = Offset(w * .17f, h * .21f),
                    size = Size(w * .66f, h * .64f),
                    cornerRadius = CornerRadius(w * .07f),
                    style = stroke
                )
                drawLine(color, Offset(w * .17f, h * .39f), Offset(w * .83f, h * .39f), stroke.width, StrokeCap.Round)
                drawLine(color, Offset(w * .34f, h * .12f), Offset(w * .34f, h * .28f), stroke.width, StrokeCap.Round)
                drawLine(color, Offset(w * .66f, h * .12f), Offset(w * .66f, h * .28f), stroke.width, StrokeCap.Round)
                drawLine(color, Offset(w * .31f, h * .57f), Offset(w * .69f, h * .57f), stroke.width, StrokeCap.Round)
                drawLine(color, Offset(w * .31f, h * .70f), Offset(w * .60f, h * .70f), stroke.width, StrokeCap.Round)
            }

            DockIcon.SHORT_TURN -> {
                drawLine(color, Offset(w * .31f, h * .16f), Offset(w * .31f, h * .84f), stroke.width, StrokeCap.Round)
                val branch = Path().apply {
                    moveTo(w * .31f, h * .48f)
                    cubicTo(w * .48f, h * .48f, w * .51f, h * .72f, w * .76f, h * .72f)
                }
                drawPath(branch, color, style = stroke)
                drawCircle(color, radius = w * .075f, center = Offset(w * .31f, h * .18f), style = stroke)
                drawCircle(color, radius = w * .075f, center = Offset(w * .31f, h * .82f), style = stroke)
                drawCircle(color, radius = w * .075f, center = Offset(w * .78f, h * .72f), style = stroke)
            }

            DockIcon.LINES -> {
                listOf(.25f, .50f, .75f).forEachIndexed { index, y ->
                    val start = if (index == 1) .18f else .27f
                    val end = if (index == 1) .82f else .73f
                    drawLine(color, Offset(w * start, h * y), Offset(w * end, h * y), stroke.width, StrokeCap.Round)
                    drawCircle(color, radius = w * .06f, center = Offset(w * start, h * y), style = stroke)
                    drawCircle(color, radius = w * .06f, center = Offset(w * end, h * y), style = stroke)
                }
            }

            DockIcon.MAP -> {
                val map = Path().apply {
                    moveTo(w * .14f, h * .22f)
                    lineTo(w * .39f, h * .14f)
                    lineTo(w * .64f, h * .23f)
                    lineTo(w * .86f, h * .15f)
                    lineTo(w * .86f, h * .78f)
                    lineTo(w * .64f, h * .86f)
                    lineTo(w * .39f, h * .77f)
                    lineTo(w * .14f, h * .85f)
                    close()
                }
                drawPath(map, color, style = stroke)
                drawLine(color, Offset(w * .39f, h * .14f), Offset(w * .39f, h * .77f), stroke.width, StrokeCap.Round)
                drawLine(color, Offset(w * .64f, h * .23f), Offset(w * .64f, h * .86f), stroke.width, StrokeCap.Round)
                val route = Path().apply {
                    moveTo(w * .23f, h * .62f)
                    cubicTo(w * .38f, h * .39f, w * .52f, h * .65f, w * .76f, h * .36f)
                }
                drawPath(route, color, style = stroke)
                drawCircle(color, radius = w * .055f, center = Offset(w * .23f, h * .62f))
                drawCircle(color, radius = w * .055f, center = Offset(w * .76f, h * .36f))
            }
        }
    }
}

@Composable
fun LineSelectionDialog(
    options: List<SettingsLineOption>,
    selectedDataFile: String?,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var expandedCities by remember { mutableStateOf<Set<String>>(emptySet()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择我的线路") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    "选中的线路会在首页分组中置顶。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(6.dp))
                options.groupBy { it.cityName }.forEach { (cityName, cityOptions) ->
                    val expanded = cityName in expandedCities
                    val selectedInCity = cityOptions.firstOrNull { it.dataFile == selectedDataFile }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(if (selectedInCity != null) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                            .clickable {
                                expandedCities = if (expanded) expandedCities - cityName else expandedCities + cityName
                            }
                            .padding(horizontal = 12.dp, vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CityTag(cityName)
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text("${cityName}线路 · ${cityOptions.size} 条", fontWeight = FontWeight.SemiBold)
                            selectedInCity?.let {
                                Text("当前：${it.name}", style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        Text(if (expanded) "收起" else "展开", color = MaterialTheme.colorScheme.primary)
                    }
                    if (expanded) cityOptions.forEach { option ->
                        val selected = option.dataFile == selectedDataFile
                        Row(
                            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                                .background(if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                                .clickable { onSelect(option.dataFile) }
                                .padding(horizontal = 18.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            val parsed = LineVisuals.parseHexColor(option.colorHex)
                            val dotColor = parsed?.let { Color(it.toArgb()) } ?: MaterialTheme.colorScheme.primary
                            Canvas(Modifier.size(14.dp)) { drawCircle(dotColor) }
                            Spacer(Modifier.width(10.dp))
                            Text(option.name)
                            Spacer(Modifier.weight(1f))
                            if (selected) Text("当前", color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } }
    )
}
