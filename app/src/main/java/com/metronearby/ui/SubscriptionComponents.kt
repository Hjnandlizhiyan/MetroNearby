package com.metronearby.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
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
    onAdd: () -> Unit, onOpen: (String) -> Unit,
    onDetails: (String) -> Unit,
    onPlan: (String, String) -> Unit,
    onEdit: (StationSubscription) -> Unit, onRemove: (StationSubscription) -> Unit
) {
    val models = remember(lines) { lines.map { it.line } }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("常用站与通勤方向", style = MaterialTheme.typography.titleLarge)
                    Text(if (subscriptions.isEmpty()) "还没有收藏站点" else "常用站随时查看，保存在本机。")
                    TextButton(onClick = onAdd) { Text("添加收藏站点") }
                }
                Image(painterResource(R.drawable.empty_subscriptions), null, Modifier.size(88.dp))
            }
        }
        items(subscriptions, key = { TransferStationResolver.normalize(it.stationName) }) { subscription ->
            val choices = StationSubscriptions.choices(models, subscription.stationName)
            val selected = choices.firstOrNull { it.line.lineId == subscription.lineId }?.line
                ?: choices.firstOrNull()?.line
            Card(Modifier.fillMaxWidth()) {
                LineColorStrip(choices.map { it.line.color })
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SubscriptionLineBadge(selected?.lineName, selected?.color)
                        Spacer(Modifier.width(10.dp))
                        Text(subscription.stationName, style = MaterialTheme.typography.titleLarge)
                    }
                    if (subscription.tag.isNotBlank()) {
                        Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(8.dp)) {
                            Text(subscription.tag, Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                style = MaterialTheme.typography.labelMedium)
                        }
                    }
                    Text(StationSubscriptions.description(subscription, models))
                    if (subscription.preferredExit.isNotBlank()) Text("常走出口 · ${subscription.preferredExit}")
                    if (subscription.note.isNotBlank()) Text(subscription.note,
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (subscription.destinationKey != null) {
                        val origin = SubscriptionPreferences.originKey(subscription, models)
                        val destination = SubscriptionPreferences.destinations(subscription, models)
                            .firstOrNull { it.key == subscription.destinationKey }
                        if (origin != null && destination != null) {
                            FilledTonalButton(onClick = { onPlan(origin, destination.key) }) {
                                Text("去常用目的地 · ${destination.stationName}")
                            }
                            Text("从本站出发 · 路线按少换乘规划，可在路线页调整",
                                style = MaterialTheme.typography.bodySmall)
                        } else Text("常用目的地已失效，请编辑收藏", color = MaterialTheme.colorScheme.error)
                    }
                    selected?.let { line ->
                        TextButton(onClick = { onDetails(OfflineRoutePlanner.stationKey(line, subscription.stationName)) }) {
                            Text("出入口与设施")
                        }
                    }
                    Row {
                        TextButton(onClick = { onOpen(subscription.stationName) }) { Text("查看全站") }
                        TextButton(onClick = { onEdit(subscription) }) { Text("编辑") }
                        TextButton(onClick = { onRemove(subscription) }) { Text("取消收藏") }
                    }
                }
            }
        }
        item { Spacer(Modifier.height(16.dp)) }
    }
}

@Composable
private fun SubscriptionLineBadge(lineName: String?, colorHex: String?) {
    val label = LineVisuals.badgeText(lineName) ?: "线"
    val parsed = LineVisuals.parseHexColor(colorHex)
    val background = parsed?.let { Color(it.toArgb()) } ?: MaterialTheme.colorScheme.primary
    val foreground = parsed?.let { Color(LineVisuals.foregroundOn(it).toArgb()) } ?: Color.White
    Box(
        modifier = Modifier.defaultMinSize(minWidth = 28.dp, minHeight = 28.dp)
            .background(background, CircleShape).padding(horizontal = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = foreground, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun subscriptionColor(colorHex: String?): Color =
    LineVisuals.parseHexColor(colorHex)?.let { Color(it.toArgb()) } ?: MaterialTheme.colorScheme.primary

@Composable
private fun subscriptionReadableColor(colorHex: String?): Color =
    LineVisuals.parseHexColor(colorHex)?.let { Color(LineVisuals.readableOnLightSurface(it).toArgb()) }
        ?: MaterialTheme.colorScheme.primary

@Composable
fun SubscriptionDialog(
    initial: StationSubscription, lines: List<MetroRepository.ResolvedLine>,
    onDismiss: () -> Unit, onSave: (StationSubscription) -> Unit
) {
    val models = remember(lines) { lines.map { it.line } }
    val choices = remember(models, initial.stationName) { StationSubscriptions.choices(models, initial.stationName) }
    var lineId by rememberSaveable(initial) { mutableStateOf(initial.lineId) }
    var directionId by rememberSaveable(initial) { mutableStateOf(initial.directionId) }
    val selected = choices.firstOrNull { it.line.lineId == lineId }
    val directions = remember(selected) { selected?.let { ArrivalEstimator(it.line).boardingDirections(it.stationId) }.orEmpty() }
    var tag by rememberSaveable(initial) { mutableStateOf(initial.tag) }
    var destinationKey by rememberSaveable(initial) { mutableStateOf(initial.destinationKey) }
    var preferredExit by rememberSaveable(initial) { mutableStateOf(initial.preferredExit) }
    var note by rememberSaveable(initial) { mutableStateOf(initial.note) }
    var pickingDestination by rememberSaveable { mutableStateOf(false) }
    val draft = initial.copy(lineId = lineId, directionId = directionId, tag = tag,
        destinationKey = destinationKey, preferredExit = preferredExit, note = note)
    val destinations = remember(models, lineId, initial.stationName) {
        SubscriptionPreferences.destinations(draft, models)
    }
    val error = SubscriptionPreferences.error(draft, models)
    if (pickingDestination) {
        StationPickerDialog("常用目的地", destinations, null,
            onSelect = { destinationKey = it.key; pickingDestination = false },
            onDismiss = { pickingDestination = false })
        return
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("收藏 · ${initial.stationName}") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("标签")
                ChoiceMenu(tag.ifEmpty { "不设标签" },
                    listOf(null to "不设标签") + SubscriptionPreferences.tags.map { it to it }) { tag = it.orEmpty() }
                Text("通勤线路")
                ChoiceMenu(
                    choices.firstOrNull { it.line.lineId == lineId }?.line?.lineName
                        ?: if (lineId == null) "全部线路" else "请选择线路",
                    listOf(null to "全部线路") + choices.map { it.line.lineId to it.line.lineName }
                ) { lineId = it; directionId = null }
                if (lineId != null) {
                    Text("通勤方向")
                    ChoiceMenu(
                        directions[directionId] ?: if (directionId == null) "全部方向" else "请选择方向",
                        listOf(null to "全部方向") + directions.map { it.key to it.value }
                    ) { directionId = it }
                }
                HorizontalDivider()
                Text("常用目的地")
                OutlinedButton(enabled = destinations.isNotEmpty(), onClick = { pickingDestination = true }) {
                    Text(destinations.firstOrNull { it.key == destinationKey }?.stationName
                        ?: if (destinationKey == null) "选择目的地车站" else "目的地已失效，重新选择")
                }
                if (destinationKey != null) TextButton(onClick = { destinationKey = null }) { Text("清除目的地") }
                Text("从本站一键规划到目的地；此设置不限制规划时使用的线路和方向。",
                    style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(value = preferredExit, onValueChange = { preferredExit = it },
                    modifier = Modifier.fillMaxWidth(), label = { Text("常走出口（选填）") },
                    supportingText = { Text("${preferredExit.length}/${SubscriptionPreferences.MAX_EXIT}") },
                    isError = preferredExit.length > SubscriptionPreferences.MAX_EXIT)
                OutlinedTextField(value = note, onValueChange = { note = it },
                    modifier = Modifier.fillMaxWidth(), label = { Text("收藏备注（选填）") }, minLines = 2,
                    supportingText = { Text("${note.length}/${SubscriptionPreferences.MAX_NOTE}") },
                    isError = note.length > SubscriptionPreferences.MAX_NOTE)
                Text("信息仅保存在本机。出口为个人记录，不代表实时开放情况。",
                    style = MaterialTheme.typography.bodySmall)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (!StationSubscriptions.valid(draft, models)) Text("当前站点、线路或方向不可用，请重新选择。")
            }
        },
        confirmButton = {
            TextButton(enabled = StationSubscriptions.valid(draft, models) && error == null, onClick = { onSave(draft) }) {
                Text("保存收藏")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
private fun ChoiceMenu(label: String, options: List<Pair<String?, String>>, onSelect: (String?) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }) { Text("$label ▾") }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { (id, name) ->
                DropdownMenuItem(text = { Text(name) }, onClick = { onSelect(id); expanded = false })
            }
        }
    }
}