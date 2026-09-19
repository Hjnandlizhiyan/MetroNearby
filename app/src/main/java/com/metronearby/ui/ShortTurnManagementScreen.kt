package com.metronearby.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.metronearby.data.MetroRepository
import com.metronearby.data.model.ShortTurn
import com.metronearby.domain.LineVisuals
import com.metronearby.domain.ShortTurnBatchEditor

/** 独立的区间车管理页：先明确选择线路，再维护或批量修改该线路的区间车。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShortTurnManagementScreen(
    lines: List<MetroRepository.ResolvedLine>,
    initialLineId: String?,
    onAdd: (lineId: String, startStationId: String, endStationId: String, departures: List<String>) -> Unit,
    onRemove: (lineId: String, index: Int) -> Unit,
    onBatchUpdate: (lineId: String, shortTurns: List<ShortTurn>) -> Unit,
    bottomBar: @Composable () -> Unit = {},
    onBack: () -> Unit
) {
    var selectedLineId by rememberSaveable {
        mutableStateOf(initialLineId?.takeIf { id -> lines.any { it.line.lineId == id } }
            ?: lines.firstOrNull()?.line?.lineId.orEmpty())
    }
    val selected = lines.firstOrNull { it.line.lineId == selectedLineId } ?: lines.firstOrNull()
    val shortTurns = selected?.overrides?.shortTurns.orEmpty()
    var showLinePicker by remember { mutableStateOf(false) }
    var showAddDialog by remember { mutableStateOf(false) }
    var batchMode by remember(selectedLineId) { mutableStateOf(false) }
    var selectedIndices by remember(selectedLineId) { mutableStateOf<Set<Int>>(emptySet()) }
    var batchAction by remember { mutableStateOf<BatchAction?>(null) }
    var batchMessage by remember(selectedLineId) { mutableStateOf<String?>(null) }

    fun applyBatch(result: ShortTurnBatchEditor.Result, successMessage: String) {
        when (result) {
            is ShortTurnBatchEditor.Result.Updated -> {
                selected?.let { onBatchUpdate(it.line.lineId, result.shortTurns) }
                selectedIndices = emptySet()
                batchMessage = successMessage
                batchAction = null
            }
            is ShortTurnBatchEditor.Result.Rejected -> batchMessage = result.reason
        }
    }

    if (showLinePicker) {
        ShortTurnLineDialog(
            lines = lines,
            selectedLineId = selected?.line?.lineId,
            onSelect = {
                selectedLineId = it
                selectedIndices = emptySet()
                batchMode = false
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
    if (batchAction == BatchAction.SHIFT && selected != null) {
        BatchShiftDialog(
            selectedCount = selectedIndices.size,
            onDismiss = { batchAction = null },
            onConfirm = { minutes ->
                applyBatch(
                    ShortTurnBatchEditor.shift(selected.line, shortTurns, selectedIndices, minutes),
                    "已将 ${selectedIndices.size} 条区间车整体调整 ${minutes} 分钟"
                )
            }
        )
    }
    if (batchAction == BatchAction.REPLACE && selected != null) {
        BatchReplaceDialog(
            selectedCount = selectedIndices.size,
            onDismiss = { batchAction = null },
            onConfirm = { departures ->
                applyBatch(
                    ShortTurnBatchEditor.replaceDepartures(selected.line, shortTurns, selectedIndices, departures),
                    "已统一替换 ${selectedIndices.size} 条区间车的发车时刻"
                )
            }
        )
    }
    if (batchAction == BatchAction.DELETE && selected != null) {
        AlertDialog(
            onDismissRequest = { batchAction = null },
            title = { Text("删除选中的区间车？") },
            text = { Text("将删除 ${selectedIndices.size} 条区间车，删除后无法恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    val count = selectedIndices.size
                    onBatchUpdate(
                        selected.line.lineId,
                        ShortTurnBatchEditor.remove(shortTurns, selectedIndices)
                    )
                    selectedIndices = emptySet()
                    batchMode = false
                    batchMessage = "已删除 $count 条区间车"
                    batchAction = null
                }) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { batchAction = null }) { Text("取消") } }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (batchMode) "批量管理区间车" else "区间车") },
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
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("已添加的区间车", style = MaterialTheme.typography.titleMedium)
                        if (batchMode) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    "勾选要一起修改的区间车",
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.weight(1f)
                                )
                                TextButton(onClick = {
                                    selectedIndices = shortTurns.indices.toSet()
                                }) { Text("全选") }
                                TextButton(onClick = {
                                    batchMode = false
                                    selectedIndices = emptySet()
                                    batchMessage = null
                                }) { Text("取消") }
                            }
                        } else {
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Button(
                                    onClick = { showAddDialog = true },
                                    modifier = Modifier.weight(1f)
                                ) { Text("添加区间车") }
                                OutlinedButton(
                                    onClick = { batchMode = true },
                                    enabled = shortTurns.isNotEmpty(),
                                    modifier = Modifier.weight(1f)
                                ) { Text("批量修改（${shortTurns.size}）") }
                            }
                            if (shortTurns.isEmpty()) {
                                Text(
                                    "添加至少一条区间车后即可批量修改",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
                if (batchMode) {
                    item {
                        BatchToolbar(
                            selectedCount = selectedIndices.size,
                            onShift = { batchAction = BatchAction.SHIFT; batchMessage = null },
                            onReplace = { batchAction = BatchAction.REPLACE; batchMessage = null },
                            onDelete = { batchAction = BatchAction.DELETE; batchMessage = null }
                        )
                    }
                }
                batchMessage?.let { message ->
                    item {
                        Text(message, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                if (shortTurns.isEmpty()) {
                    item {
                        Card(Modifier.fillMaxWidth()) {
                            Text("这条线路还没有区间车记录", modifier = Modifier.padding(18.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                } else {
                    items(shortTurns.indices.toList(), key = { it }) { index ->
                        Card(
                            modifier = Modifier.fillMaxWidth().then(
                                if (batchMode) Modifier.clickable {
                                    selectedIndices = if (index in selectedIndices) selectedIndices - index else selectedIndices + index
                                } else Modifier
                            ),
                            colors = if (index in selectedIndices) CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer
                            ) else CardDefaults.cardColors()
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (batchMode) {
                                    Checkbox(
                                        checked = index in selectedIndices,
                                        onCheckedChange = {
                                            selectedIndices = if (it) selectedIndices + index else selectedIndices - index
                                        }
                                    )
                                }
                                Box(Modifier.weight(1f)) {
                                    ShortTurnRow(
                                        line = selected.line,
                                        shortTurn = shortTurns[index],
                                        onRemove = { onRemove(selected.line.lineId, index) },
                                        showRemove = !batchMode
                                    )
                                }
                            }
                        }
                    }
                }
            }
            item { Spacer(Modifier.size(16.dp)) }
        }
    }
}

private enum class BatchAction { SHIFT, REPLACE, DELETE }

@Composable
private fun BatchToolbar(
    selectedCount: Int,
    onShift: () -> Unit,
    onReplace: () -> Unit,
    onDelete: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("已选择 $selectedCount 条", fontWeight = FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(enabled = selectedCount > 0, onClick = onShift) { Text("提前/延后") }
                TextButton(enabled = selectedCount > 0, onClick = onReplace) { Text("统一时刻") }
                TextButton(enabled = selectedCount > 0, onClick = onDelete) { Text("批量删除") }
            }
        }
    }
}

@Composable
private fun BatchShiftDialog(selectedCount: Int, onDismiss: () -> Unit, onConfirm: (Int) -> Unit) {
    var text by remember { mutableStateOf("") }
    val minutes = text.trim().toIntOrNull()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("整体调整发车时刻") },
        text = {
            Column {
                Text("将同时调整 $selectedCount 条区间车。输入正数表示延后，负数表示提前。")
                Spacer(Modifier.size(10.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.filter { char -> char.isDigit() || char == '-' } },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    label = { Text("调整分钟数") },
                    placeholder = { Text("例如 -3 或 5") },
                    isError = text.isNotBlank() && (minutes == null || minutes == 0)
                )
            }
        },
        confirmButton = {
            TextButton(enabled = minutes != null && minutes != 0, onClick = { minutes?.let(onConfirm) }) {
                Text("应用")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
private fun BatchReplaceDialog(
    selectedCount: Int,
    onDismiss: () -> Unit,
    onConfirm: (List<String>) -> Unit
) {
    var text by remember { mutableStateOf("") }
    val departures = text.lines().map(String::trim).filter(String::isNotEmpty)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("统一替换发车时刻") },
        text = {
            Column {
                Text("这组时刻会替换 $selectedCount 条区间车原有的全部发车时刻，每行一个。")
                Spacer(Modifier.size(10.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 4,
                    label = { Text("发车时刻") },
                    placeholder = { Text("08:00\n08:10\n08:25") }
                )
            }
        },
        confirmButton = {
            TextButton(enabled = departures.isNotEmpty(), onClick = { onConfirm(departures) }) { Text("应用") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
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