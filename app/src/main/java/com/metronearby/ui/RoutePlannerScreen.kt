package com.metronearby.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.metronearby.data.model.MetroLine
import com.metronearby.domain.LineVisuals
import com.metronearby.domain.OfflineRoutePlanner

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoutePlannerScreen(
    lines: List<MetroLine>,
    initialOriginName: String?,
    bottomBar: @Composable () -> Unit = {},
    onBack: () -> Unit
) {
    val choices = remember(lines) { OfflineRoutePlanner.stationChoices(lines) }
    var origin by remember(lines, initialOriginName) {
        mutableStateOf(initialOriginName?.let { OfflineRoutePlanner.findChoice(choices, it) })
    }
    var destination by remember(lines) { mutableStateOf<OfflineRoutePlanner.StationChoice?>(null) }
    var preference by remember { mutableStateOf(OfflineRoutePlanner.Preference.FEWER_TRANSFERS) }
    var pickingOrigin by remember { mutableStateOf(false) }
    var pickingDestination by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<OfflineRoutePlanner.PlanResult?>(null) }

    if (pickingOrigin) {
        StationPickerDialog(
            title = "选择起点",
            choices = choices,
            excludedKey = destination?.key,
            onSelect = {
                origin = it
                pickingOrigin = false
                result = null
            },
            onDismiss = { pickingOrigin = false }
        )
    }
    if (pickingDestination) {
        StationPickerDialog(
            title = "选择终点",
            choices = choices,
            excludedKey = origin?.key,
            onSelect = {
                destination = it
                pickingDestination = false
                result = null
            },
            onDismiss = { pickingDestination = false }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("离线路线") },
                navigationIcon = { TextButton(onClick = onBack) { Text("返回") } }
            )
        },
        bottomBar = bottomBar
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(innerPadding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Text(
                    "只使用离线站序和换乘关系，给出乘坐线路、方向、站数与换乘站，不依赖实时班次。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            item {
                PlannerCard {
                    StationChoiceRow("起点", origin, "选择起点") { pickingOrigin = true }
                    StationChoiceRow("终点", destination, "选择终点") { pickingDestination = true }
                    Text("路线偏好", style = MaterialTheme.typography.labelMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OfflineRoutePlanner.Preference.entries.forEach { option ->
                            FilterChip(
                                selected = preference == option,
                                onClick = {
                                    preference = option
                                    result = null
                                },
                                label = { Text(option.displayName) }
                            )
                        }
                    }
                    Button(
                        enabled = origin != null && destination != null,
                        onClick = {
                            result = OfflineRoutePlanner.plan(
                                lines = lines,
                                originKey = origin!!.key,
                                destinationKey = destination!!.key,
                                preference = preference
                            )
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("规划路线") }
                }
            }

            when (val current = result) {
                is OfflineRoutePlanner.PlanResult.Found -> {
                    item {
                        val route = current.route
                        Text(
                            "${route.originName} → ${route.destinationName}",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            "共 ${route.stopCount} 站 · 换乘 ${route.transferCount} 次",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    items(current.route.legs) { leg ->
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    val parsed = LineVisuals.parseHexColor(leg.lineColor)
                                    val color = parsed?.let { Color(it.toArgb()) } ?: MaterialTheme.colorScheme.primary
                                    Canvas(Modifier.size(14.dp)) { drawCircle(color) }
                                    Spacer(Modifier.width(10.dp))
                                    Text(leg.lineName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                    Spacer(Modifier.weight(1f))
                                    Text("${leg.stopCount} 站")
                                }
                                Text("从 ${leg.fromStation} 上车 · 朝 ${leg.stationNames.getOrNull(1) ?: leg.toStation} 方向")
                                Text("本段到 ${leg.toStation}")
                                Text(
                                    leg.stationNames.joinToString(" · "),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                if (leg != current.route.legs.last()) {
                                    Text("在 ${leg.toStation} 换乘", color = MaterialTheme.colorScheme.primary)
                                }
                            }
                        }
                    }
                    item {
                        Text(
                            "路线仅供线网关系参考；封站、临时甩站和施工调整请以运营方现场信息为准。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                is OfflineRoutePlanner.PlanResult.Rejected -> item {
                    Text(current.reason, color = MaterialTheme.colorScheme.error)
                }
                null -> Unit
            }
        }
    }
}

@Composable
private fun PlannerCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content
        )
    }
}

@Composable
private fun StationChoiceRow(
    label: String,
    choice: OfflineRoutePlanner.StationChoice?,
    actionLabel: String,
    onClick: () -> Unit
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                choice?.stationName ?: "尚未选择",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            choice?.let {
                Text(
                    "${it.cityName} · ${it.lineNames.joinToString(" / ")}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        OutlinedButton(onClick = onClick) { Text(actionLabel) }
    }
}

@Composable
private fun StationPickerDialog(
    title: String,
    choices: List<OfflineRoutePlanner.StationChoice>,
    excludedKey: String?,
    onSelect: (OfflineRoutePlanner.StationChoice) -> Unit,
    onDismiss: () -> Unit
) {
    var query by remember { mutableStateOf("") }
    val filtered = remember(choices, query, excludedKey) {
        val keyword = TransferText.normalize(query)
        choices.filter { choice ->
            choice.key != excludedKey &&
                (keyword.isEmpty() ||
                    TransferText.normalize(choice.stationName).contains(keyword) ||
                    choice.lineNames.any { TransferText.normalize(it).contains(keyword) })
        }.take(80)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("搜索站名或线路") }
                )
                Spacer(Modifier.height(8.dp))
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                    items(filtered, key = { it.key }) { choice ->
                        Row(
                            modifier = Modifier.fillMaxWidth()
                                .clickable { onSelect(choice) }
                                .padding(horizontal = 8.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(choice.stationName, fontWeight = FontWeight.SemiBold)
                                Text(
                                    "${choice.cityName} · ${choice.lineNames.joinToString(" / ")}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } }
    )
}

private object TransferText {
    fun normalize(value: String): String = value.trim().lowercase().replace(" ", "")
}