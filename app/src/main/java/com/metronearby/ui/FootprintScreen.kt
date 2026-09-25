package com.metronearby.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.metronearby.data.model.MetroLine
import com.metronearby.domain.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FootprintScreen(
    footprints: List<StationFootprint>,
    lines: List<MetroLine>,
    loadError: Boolean,
    onRemove: (StationFootprint) -> Unit,
    onRetry: () -> Unit,
    onBack: () -> Unit,
    bottomBar: @Composable () -> Unit = {}
) {
    val progress = remember(footprints, lines) { StationFootprints.progress(footprints, lines) }
    val badges = remember(footprints, progress) { StationFootprints.badges(footprints, progress) }
    val stationByKey = remember(lines) { OfflineRoutePlanner.stationChoices(lines).associateBy { it.key } }
    var pendingRemove by remember { mutableStateOf<StationFootprint?>(null) }
    BackHandler(onBack = onBack)

    pendingRemove?.let { footprint ->
        AlertDialog(
            onDismissRequest = { pendingRemove = null },
            title = { Text("撤销${footprint.stationName}足迹？") },
            text = { Text("将删除这座站的首次到访记录，线路进度和徽章会随之更新。") },
            confirmButton = {
                TextButton(onClick = { onRemove(footprint); pendingRemove = null }) { Text("撤销点亮") }
            },
            dismissButton = { TextButton(onClick = { pendingRemove = null }) { Text("取消") } }
        )
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("我的地铁足迹") },
            navigationIcon = { TextButton(onClick = onBack) { Text("返回") } }) },
        bottomBar = bottomBar
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("已点亮 ${footprints.size} 座车站", style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold)
                        Text("接近车站且定位精度足够时自动点亮；也可在首页手动补记。只保存首次到访，不记录移动轨迹。")
                    }
                }
            }
            if (loadError) item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Column(Modifier.padding(16.dp)) {
                        Text("足迹读取失败。为防止覆盖原记录，当前不能新增或撤销。")
                        TextButton(onClick = onRetry) { Text("重新读取") }
                    }
                }
            }
            item { Text("探索徽章", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
            if (badges.isEmpty()) item { Text("点亮第一座车站后获得“第一站”徽章。") }
            items(badges, key = { it.title + it.description }) { badge ->
                Card(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(14.dp)) {
                        Text("◆", color = MaterialTheme.colorScheme.tertiary)
                        Spacer(Modifier.width(10.dp))
                        Column { Text(badge.title, fontWeight = FontWeight.Bold); Text(badge.description) }
                    }
                }
            }
            item { Text("线路进度", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
            items(progress, key = { it.cityName + "|" + it.lineId }) { line ->
                Card(Modifier.fillMaxWidth()) {
                    LineColorStrip(listOf(line.color))
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(Modifier.fillMaxWidth()) {
                            Text("${line.cityName} · ${line.lineName}", Modifier.weight(1f), fontWeight = FontWeight.Bold)
                            Text("${line.visited}/${line.total}")
                        }
                        LinearProgressIndicator(
                            progress = { line.visited.toFloat() / line.total },
                            modifier = Modifier.fillMaxWidth()
                        )
                        if (line.complete) Text("已点亮全线", color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
            item { Text("到访车站", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
            if (footprints.isEmpty() && !loadError) item { Text("还没有足迹。到达车站附近或在首页手动点亮吧。") }
            items(footprints.sortedByDescending { it.firstVisitedAtMillis }, key = { it.stationKey }) { footprint ->
                val station = stationByKey[footprint.stationKey]
                Card(Modifier.fillMaxWidth()) {
                    LineColorStrip(station?.lineColors.orEmpty())
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(Modifier.fillMaxWidth()) {
                            Column(Modifier.weight(1f)) {
                                Text(footprint.stationName, style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold)
                                Text("${footprint.cityName} · ${station?.lineNames?.joinToString(" / ") ?: "线路数据已变更"}")
                            }
                            TextButton(enabled = !loadError, onClick = { pendingRemove = footprint }) { Text("撤销") }
                        }
                        Text("首次到访 · ${footprintTime(footprint.firstVisitedAtMillis)}")
                        Text(if (footprint.source == StationFootprint.Source.NEARBY_LOCATION.name)
                            "由附近定位自动点亮" else "手动点亮",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

private fun footprintTime(millis: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(Date(millis))
