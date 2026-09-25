package com.metronearby.ui

import android.Manifest
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.metronearby.data.model.MetroLine
import com.metronearby.domain.*
import com.metronearby.location.AndroidLocationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StationRadarScreen(
    lines: List<MetroLine>,
    onPlanFrom: (String) -> Unit,
    onBack: () -> Unit,
    bottomBar: @Composable () -> Unit = {}
) {
    val context = LocalContext.current
    val provider = remember { AndroidLocationProvider(context.applicationContext) }
    var location by remember { mutableStateOf<UserLocation?>(null) }
    var retry by remember { mutableStateOf(0) }
    var message by remember { mutableStateOf("正在定位…") }
    var locating by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { retry++ }
    BackHandler(onBack = onBack)
    LaunchedEffect(retry) {
        if (!provider.hasPermission()) {
            message = "需要定位权限，才能显示你与车站的相对位置。"
        } else {
            locating = true
            message = "正在更新位置…"
            location = try { provider.resolveBest() } catch (e: Exception) { null }
            message = if (location == null) "未获得位置，请开启手机定位后重试。" else ""
            locating = false
        }
    }
    val stations by produceState<List<RadarStation>>(emptyList(), lines, location) {
        value = location?.let { position ->
            withContext(Dispatchers.Default) { StationRadar.find(lines, position) }
        }.orEmpty()
    }
    Scaffold(
        topBar = { TopAppBar(title = { Text("附近站雷达") },
            navigationIcon = { TextButton(onClick = onBack) { Text("返回") } }) },
        bottomBar = bottomBar
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Text("上北下南 · 直线距离", style = MaterialTheme.typography.titleMedium)
                Text("圆点编号与下方车站对应。此图不随手机旋转，不能代替步行导航。")
                location?.let {
                    Text("当前位置：${UserLocationPolicy.coordinatesText(it)}")
                    Text(UserLocationPolicy.detailText(it, System.currentTimeMillis()),
                        style = MaterialTheme.typography.bodySmall)
                }
                if (message.isNotEmpty()) Text(message)
                Button(enabled = !locating, onClick = {
                    if (provider.hasPermission()) retry++ else launcher.launch(arrayOf(
                        Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                }) { Text(if (locating) "定位中…" else "更新位置") }
            }
            if (stations.isNotEmpty()) {
                item { RadarPlot(stations) }
                itemsIndexed(stations, key = { _, station -> station.key }) { index, station ->
                    Card(Modifier.fillMaxWidth().clickable { onPlanFrom(station.key) }) {
                        LineColorStrip(station.colors)
                        Column(Modifier.padding(14.dp)) {
                            Text("${index + 1}. ${station.name}", style = MaterialTheme.typography.titleMedium)
                            Text("${StationRadar.positionLabel(station.distanceMeters, station.bearing, location?.accuracyMeters)} · ${station.distanceMeters.roundToInt()} 米")
                            Text(station.lineNames.joinToString(" / "))
                            Text("从此站规划路线", color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            } else if (location != null && lines.isNotEmpty()) {
                item { Text("附近暂无已收录的车站。") }
            }
        }
    }
}

@Composable
private fun RadarPlot(stations: List<RadarStation>) {
    val ink = MaterialTheme.colorScheme.onSurface
    val grid = MaterialTheme.colorScheme.outlineVariant
    val accent = MaterialTheme.colorScheme.primary
    Canvas(Modifier.fillMaxWidth().aspectRatio(1f)) {
        val c = center
        val r = size.minDimension * 0.40f
        for (fraction in listOf(0.33f, 0.66f, 1f)) drawCircle(grid, r * fraction, c, style = Stroke(1.dp.toPx()))
        drawLine(grid, Offset(c.x-r,c.y), Offset(c.x+r,c.y))
        drawLine(grid, Offset(c.x,c.y-r), Offset(c.x,c.y+r))
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.argb(255,(ink.red*255).toInt(),(ink.green*255).toInt(),(ink.blue*255).toInt())
            textSize = 14.sp.toPx()
            textAlign = android.graphics.Paint.Align.CENTER
        }
        drawContext.canvas.nativeCanvas.drawText("北", c.x, c.y-r-12.dp.toPx(), paint)
        drawContext.canvas.nativeCanvas.drawText("南", c.x, c.y+r+24.dp.toPx(), paint)
        drawContext.canvas.nativeCanvas.drawText("西", c.x-r-18.dp.toPx(), c.y, paint)
        drawContext.canvas.nativeCanvas.drawText("东", c.x+r+18.dp.toPx(), c.y, paint)
        val maxDistance = stations.maxOf { it.distanceMeters }.coerceAtLeast(100.0)
        stations.forEachIndexed { index, station ->
            val angle = Math.toRadians(station.bearing)
            val distance = (station.distanceMeters / maxDistance * r).toFloat()
            val point = Offset(c.x + sin(angle).toFloat()*distance, c.y-cos(angle).toFloat()*distance)
            val color = LineVisuals.parseHexColor(station.colors.firstOrNull())?.let { Color(it.toArgb()) } ?: accent
            drawCircle(color, 8.dp.toPx(), point)
            drawContext.canvas.nativeCanvas.drawText("${index+1}", point.x+12.dp.toPx(),point.y-10.dp.toPx(), paint)
        }
        drawCircle(accent, 5.dp.toPx(), c)
    }
}
