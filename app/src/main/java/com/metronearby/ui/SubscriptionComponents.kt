package com.metronearby.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.metronearby.R
import com.metronearby.data.MetroRepository
import com.metronearby.data.model.StationSubscription
import com.metronearby.domain.*
import kotlinx.coroutines.delay
import java.util.Calendar

@Composable
fun SubscriptionBoard(
    subscriptions: List<StationSubscription>, lines: List<MetroRepository.ResolvedLine>,
    arrivalDisplayMode: ArrivalDisplayMode,
    onAdd: () -> Unit, onOpen: (String) -> Unit,
    onEdit: (StationSubscription) -> Unit, onRemove: (StationSubscription) -> Unit
) {
    var clock by remember { mutableStateOf(Calendar.getInstance()) }
    LaunchedEffect(Unit) { while (true) { delay(1000); clock = Calendar.getInstance() } }
    val models = remember(lines) { lines.map { it.line } }
    val overrides = remember(lines) { lines.associate { it.line.lineId to it.overrides } }
    val now = ServiceTypeResolver.secondsOfDay(clock)
    val service = ServiceTypeResolver.from(clock)
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            if (subscriptions.isEmpty()) {
                Text("常用站与通勤方向", style = MaterialTheme.typography.titleLarge)
                Text("关注的站点集中查看，保存在本机。", style = MaterialTheme.typography.bodyMedium)
                Column(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Image(
                        painter = painterResource(R.drawable.empty_subscriptions),
                        contentDescription = null,
                        modifier = Modifier.size(176.dp)
                    )
                    Text("还没有订阅站点", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "搜索常用站，进入站点后点击“订阅本站”。",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    TextButton(onClick = onAdd) { Text("添加订阅站点") }
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("常用站与通勤方向", style = MaterialTheme.typography.titleLarge)
                        Text("关注的站点集中查看，保存在本机。", style = MaterialTheme.typography.bodyMedium)
                        TextButton(onClick = onAdd) { Text("添加订阅站点") }
                    }
                    Image(
                        painter = painterResource(R.drawable.empty_subscriptions),
                        contentDescription = null,
                        modifier = Modifier.size(88.dp)
                    )
                }
            }
        }
        items(subscriptions, key = { TransferStationResolver.normalize(it.stationName) }) { subscription ->
            val previews = remember(subscription, lines, now, service, arrivalDisplayMode) {
                StationSubscriptions.preview(subscription, models, overrides, service, now, clock.timeInMillis, arrivalDisplayMode)
            }
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(subscription.stationName, style = MaterialTheme.typography.titleLarge)
                    Text(StationSubscriptions.description(subscription, models), style = MaterialTheme.typography.bodyMedium)
                    previews.forEach { preview ->
                        if (previews.size > 1) Text("${preview.lineName} · ${preview.direction}", style = MaterialTheme.typography.labelLarge)
                        Text(StationSubscriptions.arrivalText(preview, now, arrivalDisplayMode), color = MaterialTheme.colorScheme.primary)
                        StationSubscriptions.arrivalModeNote(preview, now, arrivalDisplayMode)?.let {
                            Text(it, color = MaterialTheme.colorScheme.tertiary, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    Row {
                        TextButton(onClick = { onOpen(subscription.stationName) }) { Text("查看全站") }
                        TextButton(onClick = { onEdit(subscription) }) { Text("编辑") }
                        TextButton(onClick = { onRemove(subscription) }) { Text("取消订阅") }
                    }
                }
            }
        }
        item { Text("到站时间为离线时刻表推算，仅供参考。当前口径：${arrivalDisplayMode.displayName}。", style = MaterialTheme.typography.bodySmall) }
        item { Spacer(Modifier.height(16.dp)) }
    }
}

@Composable
fun SubscriptionDialog(
    initial: StationSubscription, lines: List<MetroRepository.ResolvedLine>,
    onDismiss: () -> Unit, onSave: (StationSubscription) -> Unit
) {
    val models = remember(lines) { lines.map { it.line } }
    val choices = remember(models, initial.stationName) { StationSubscriptions.choices(models, initial.stationName) }
    var lineId by remember(initial) { mutableStateOf(initial.lineId) }
    var directionId by remember(initial) { mutableStateOf(initial.directionId) }
    val selected = choices.firstOrNull { it.line.lineId == lineId }
    val directions = remember(selected) { selected?.let { ArrivalEstimator(it.line).boardingDirections(it.stationId) }.orEmpty() }
    val draft = initial.copy(lineId = lineId, directionId = directionId)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("订阅 · ${initial.stationName}") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("通勤线路")
                ChoiceMenu(choices.firstOrNull { it.line.lineId == lineId }?.line?.lineName ?: if (lineId == null) "全部线路" else "请选择线路",
                    listOf(null to "全部线路") + choices.map { it.line.lineId to it.line.lineName }) {
                    lineId = it; directionId = null
                }
                if (lineId != null) {
                    Text("通勤方向")
                    ChoiceMenu(directions[directionId] ?: if (directionId == null) "全部方向" else "请选择方向",
                        listOf(null to "全部方向") + directions.map { it.key to it.value }) { directionId = it }
                }
                Text("每站保存一组通勤偏好，也可选择全部线路或全部方向。订阅后可在主页随时查看。")
                if (!StationSubscriptions.valid(draft, models)) Text("当前站点、线路或方向不可用，请重新选择。")
            }
        },
        confirmButton = { TextButton(enabled = StationSubscriptions.valid(draft, models), onClick = { onSave(draft) }) { Text("保存订阅") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
private fun ChoiceMenu(label: String, options: List<Pair<String?, String>>, onSelect: (String?) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }) { Text("$label ▾") }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { (id, name) -> DropdownMenuItem(text = { Text(name) }, onClick = { onSelect(id); expanded = false }) }
        }
    }
}
