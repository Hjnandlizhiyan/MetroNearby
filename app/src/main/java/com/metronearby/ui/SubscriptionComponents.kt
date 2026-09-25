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
                    Text(StationSubscriptions.description(subscription, models))
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
    var lineId by remember(initial) { mutableStateOf(initial.lineId) }
    var directionId by remember(initial) { mutableStateOf(initial.directionId) }
    val selected = choices.firstOrNull { it.line.lineId == lineId }
    val directions = remember(selected) { selected?.let { ArrivalEstimator(it.line).boardingDirections(it.stationId) }.orEmpty() }
    val draft = initial.copy(lineId = lineId, directionId = directionId)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("收藏 · ${initial.stationName}") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
                Text("每站保存一组通勤偏好，也可选择全部线路或全部方向。收藏后可在主页随时查看。")
                if (!StationSubscriptions.valid(draft, models)) Text("当前站点、线路或方向不可用，请重新选择。")
            }
        },
        confirmButton = {
            TextButton(enabled = StationSubscriptions.valid(draft, models), onClick = { onSave(draft) }) {
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