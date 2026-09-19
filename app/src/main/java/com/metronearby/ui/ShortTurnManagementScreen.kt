package com.metronearby.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.metronearby.data.MetroRepository
import com.metronearby.domain.LineVisuals

/** 独立的区间车管理页：先明确选择线路，再维护该线路的区间车。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShortTurnManagementScreen(
    lines: List<MetroRepository.ResolvedLine>,
    initialLineId: String?,
    onAdd: (lineId: String, startStationId: String, endStationId: String, departures: List<String>) -> Unit,
    onRemove: (lineId: String, index: Int) -> Unit,
    bottomBar: @Composable () -> Unit = {},
    onBack: () -> Unit
) {
    var selectedLineId by rememberSaveable {
        mutableStateOf(initialLineId?.takeIf { id -> lines.any { it.line.lineId == id } }
            ?: lines.firstOrNull()?.line?.lineId.orEmpty())
    }
    val selected = lines.firstOrNull { it.line.lineId == selectedLineId } ?: lines.firstOrNull()
    var showLinePicker by remember { mutableStateOf(false) }
    var showAddDialog by remember { mutableStateOf(false) }

    if (showLinePicker) {
        ShortTurnLineDialog(
            lines = lines,
            selectedLineId = selected?.line?.lineId,
            onSelect = {
                selectedLineId = it
                showLinePicker = false
            },
            onDismiss = { showLinePicker = false }
        )
    }
    if (showAddDialog && selected != null) {
        ShortTurnDialog(
            line = selected.line,
            onDismiss = { showAddDialog = false },
            onConfirm = { startStationId, endStationId, departures ->
                onAdd(selected.line.lineId, startStationId, endStationId, departures)
                showAddDialog = false
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("区间车") },
                navigationIcon = { TextButton(onClick = onBack) { Text("返回") } }
            )
        },
        bottomBar = bottomBar
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(innerPadding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Text(
                    "先选择区间车所属线路，再填写起点、终点和起点站发车时刻。每条线路独立保存。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
            if (selected == null) {
                item { Text("没有可管理的线路") }
            } else {
                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("所属线路", style = MaterialTheme.typography.labelLarge)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                LineColorCircle(selected.line.color)
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(selected.line.lineName, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                                    Text(selected.line.cityName ?: "未设置城市", style = MaterialTheme.typography.bodySmall)
                                }
                                TextButton(onClick = { showLinePicker = true }) { Text("选择线路") }
                            }
                        }
                    }
                }
                item {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("已添加的区间车", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        Button(onClick = { showAddDialog = true }) { Text("添加区间车") }
                    }
                }
                val shortTurns = selected.overrides?.shortTurns.orEmpty()
                if (shortTurns.isEmpty()) {
                    item {
                        Card(Modifier.fillMaxWidth()) {
                            Text("这条线路还没有区间车记录", modifier = Modifier.padding(18.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                } else {
                    items(shortTurns.indices.toList(), key = { it }) { index ->
                        Card(Modifier.fillMaxWidth()) {
                            ShortTurnRow(
                                line = selected.line,
                                shortTurn = shortTurns[index],
                                onRemove = { onRemove(selected.line.lineId, index) }
                            )
                        }
                    }
                }
            }
            item { Spacer(Modifier.size(16.dp)) }
        }
    }
}

@Composable
private fun ShortTurnLineDialog(
    lines: List<MetroRepository.ResolvedLine>,
    selectedLineId: String?,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择区间车所属线路") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                lines.groupBy { it.line.cityName ?: "未设置城市" }.forEach { (city, cityLines) ->
                    Text(city, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
                    cityLines.forEach { resolved ->
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable { onSelect(resolved.line.lineId) }
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(selected = resolved.line.lineId == selectedLineId, onClick = null)
                            Spacer(Modifier.width(8.dp))
                            LineColorCircle(resolved.line.color)
                            Spacer(Modifier.width(10.dp))
                            Text(resolved.line.lineName)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } }
    )
}

@Composable
private fun LineColorCircle(colorHex: String?) {
    val color = LineVisuals.parseHexColor(colorHex)?.let { Color(it.toArgb()) }
        ?: MaterialTheme.colorScheme.primary
    Canvas(Modifier.size(14.dp)) { drawCircle(color) }
}