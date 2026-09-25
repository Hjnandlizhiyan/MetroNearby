package com.metronearby.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.metronearby.data.model.MetroLine
import com.metronearby.domain.OfflineRoutePlanner
import com.metronearby.domain.RandomExplorer
import com.metronearby.domain.StationFootprint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RandomExploreScreen(
    lines: List<MetroLine>,
    footprints: List<StationFootprint>,
    initialOriginKey: String?,
    onPlan: (originKey: String, destinationKey: String) -> Unit,
    bottomBar: @Composable () -> Unit = {},
    onBack: () -> Unit
) {
    val choices = remember(lines) { OfflineRoutePlanner.stationChoices(lines) }
    var originKey by rememberSaveable(initialOriginKey) { mutableStateOf(initialOriginKey) }
    var lineId by rememberSaveable { mutableStateOf<String?>(null) }
    var maxDistanceKm by rememberSaveable { mutableStateOf<Double?>(null) }
    var maxTransfers by rememberSaveable { mutableStateOf<Int?>(null) }
    var showOriginPicker by remember { mutableStateOf(false) }
    var showLinePicker by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<RandomExplorer.Result?>(null) }
    var loading by remember { mutableStateOf(false) }
    var seed by rememberSaveable { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    val origin = choices.firstOrNull { it.key == originKey }
    val lineOptions = remember(lines, origin?.cityName) {
        lines.filter {
            (it.cityName ?: it.cityId ?: "未设置城市") == origin?.cityName
        }.distinctBy { it.lineId }.sortedBy { it.lineName }
    }
    val selectedLine = lineOptions.firstOrNull { it.lineId == lineId }

    if (showOriginPicker) {
        StationPickerDialog(
            title = "选择探索起点",
            choices = choices,
            excludedKey = null,
            onSelect = {
                originKey = it.key
                lineId = null
                result = null
                showOriginPicker = false
            },
            onDismiss = { showOriginPicker = false }
        )
    }
    if (showLinePicker) {
        AlertDialog(
            onDismissRequest = { showLinePicker = false },
            title = { Text("限制目标线路") },
            text = {
                LazyColumn {
                    item {
                        Text(
                            "不限线路",
                            modifier = Modifier.fillMaxWidth().clickable {
                                lineId = null
                                result = null
                                showLinePicker = false
                            }.padding(vertical = 12.dp),
                            fontWeight = if (lineId == null) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                    items(lineOptions, key = { it.lineId }) { line ->
                        Column(
                            modifier = Modifier.fillMaxWidth().clickable {
                                lineId = line.lineId
                                result = null
                                showLinePicker = false
                            }
                        ) {
                            LineColorStrip(listOf(line.color))
                            Text(
                                line.lineName,
                                modifier = Modifier.padding(vertical = 12.dp),
                                fontWeight = if (line.lineId == lineId) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showLinePicker = false }) { Text("关闭") } }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("随机探索") },
                navigationIcon = { TextButton(onClick = onBack) { Text("返回") } }
            )
        },
        bottomBar = bottomBar
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(innerPadding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                    Column(Modifier.fillMaxWidth().padding(16.dp)) {
                        Text("去一个还没点亮的车站", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "全部计算都在本机完成。距离是从所选起点站中心计算的直线距离，路线来自离线线网。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
            }
            item {
                Text("探索起点", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                OutlinedButton(onClick = { showOriginPicker = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(origin?.let { "${it.stationName} · ${it.cityName}" } ?: "选择起点站")
                }
            }
            item {
                Text("目标线路", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                OutlinedButton(
                    onClick = { showLinePicker = true },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = origin != null
                ) { Text(selectedLine?.lineName ?: "不限线路") }
            }
            item {
                Text("最远直线距离", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(null to "不限", 5.0 to "5 km", 10.0 to "10 km", 20.0 to "20 km").forEach { option ->
                        FilterChip(
                            selected = maxDistanceKm == option.first,
                            onClick = { maxDistanceKm = option.first; result = null },
                            label = { Text(option.second) }
                        )
                    }
                }
            }
            item {
                Text("最多换乘", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(null to "不限", 0 to "直达", 1 to "1次", 2 to "2次").forEach { option ->
                        FilterChip(
                            selected = maxTransfers == option.first,
                            onClick = { maxTransfers = option.first; result = null },
                            label = { Text(option.second) }
                        )
                    }
                }
            }
            item {
                Button(
                    onClick = {
                        val key = originKey ?: return@Button
                        loading = true
                        result = null
                        seed += 1
                        scope.launch {
                            result = withContext(Dispatchers.Default) {
                                RandomExplorer.recommend(
                                    lines = lines,
                                    footprints = footprints,
                                    originKey = key,
                                    filters = RandomExplorer.Filters(lineId, maxDistanceKm, maxTransfers),
                                    seed = seed xor System.currentTimeMillis().toInt()
                                )
                            }
                            loading = false
                        }
                    },
                    enabled = origin != null && !loading,
                    modifier = Modifier.fillMaxWidth()
                ) { Text(if (loading) "正在寻找…" else "随机推荐一个车站") }
            }
            when (val current = result) {
                is RandomExplorer.Result.Empty -> item {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                        Text(current.reason, modifier = Modifier.fillMaxWidth().padding(16.dp))
                    }
                }
                is RandomExplorer.Result.Found -> item {
                    RecommendationCard(
                        recommendation = current.recommendation,
                        onPlan = {
                            originKey?.let { from -> onPlan(from, current.recommendation.station.key) }
                        }
                    )
                }
                null -> Unit
            }
        }
    }
}

@Composable
private fun RecommendationCard(
    recommendation: RandomExplorer.Recommendation,
    onPlan: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
    ) {
        Column(Modifier.fillMaxWidth()) {
            LineColorStrip(recommendation.station.lineColors)
            Column(Modifier.padding(16.dp)) {
                Text("这次去这里", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                Text(
                    recommendation.station.stationName,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    "${recommendation.station.cityName} · ${recommendation.station.lineNames.joinToString(" / ")}",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    "${formatExploreDistance(recommendation.distanceMeters)} · ${recommendation.route.stopCount}站 · " +
                        if (recommendation.route.transferCount == 0) "无需换乘" else "换乘${recommendation.route.transferCount}次",
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(8.dp))
                recommendation.route.legs.forEachIndexed { index, leg ->
                    Text(
                        "${index + 1}. ${leg.lineName}：${leg.fromStation} → ${leg.toStation}",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Spacer(Modifier.height(14.dp))
                Button(onClick = onPlan, modifier = Modifier.fillMaxWidth()) {
                    Text("用这个目的地规划路线")
                }
            }
        }
    }
}

private fun formatExploreDistance(meters: Double): String =
    if (meters < 1_000) "${meters.roundToInt()}米"
    else "${(meters / 100.0).roundToInt() / 10.0}公里"
