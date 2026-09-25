package com.metronearby.ui

import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.metronearby.data.model.MetroLine
import com.metronearby.domain.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoutePlannerScreen(
    lines: List<MetroLine>,
    initialOriginName: String?,
    initialOriginKey: String? = null,
    initialDestinationKey: String? = null,
    journeys: List<SavedJourney>,
    onJourneysChange: (List<SavedJourney>) -> Unit,
    bottomBar: @Composable () -> Unit = {},
    onBack: () -> Unit
) {
    val choices = remember(lines) { OfflineRoutePlanner.stationChoices(lines) }
    var originKey by rememberSaveable { mutableStateOf(initialOriginKey ?: initialOriginName?.let {
        OfflineRoutePlanner.findChoice(choices, it)?.key
    }) }
    var destinationKey by rememberSaveable { mutableStateOf(initialDestinationKey) }
    var preferenceName by rememberSaveable { mutableStateOf(OfflineRoutePlanner.Preference.FEWER_TRANSFERS.name) }
    val preference = OfflineRoutePlanner.Preference.valueOf(preferenceName)
    val origin = choices.firstOrNull { it.key == originKey }
    val destination = choices.firstOrNull { it.key == destinationKey }
    LaunchedEffect(choices, initialOriginName, initialOriginKey) {
        if (originKey == null) originKey = initialOriginKey ?: initialOriginName?.let {
            OfflineRoutePlanner.findChoice(choices, it)?.key
        }
    }
    var pickingOrigin by remember { mutableStateOf(false) }
    var pickingDestination by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<SavedJourney?>(null) }
    var deleting by remember { mutableStateOf<SavedJourney?>(null) }
    var sharing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val context = LocalContext.current
    BackHandler(onBack = onBack)
    val result by produceState<OfflineRoutePlanner.PlanResult?>(null, lines, originKey, destinationKey, preferenceName) {
        value = null
        if (originKey != null && destinationKey != null) {
            val from = originKey!!
            val to = destinationKey!!
            value = withContext(Dispatchers.Default) { OfflineRoutePlanner.plan(lines, from, to, preference) }
        }
    }

    if (pickingOrigin || pickingDestination) StationPickerDialog(
        title = if (pickingOrigin) "选择起点" else "选择终点",
        choices = choices,
        excludedKey = if (pickingOrigin) destinationKey else originKey,
        onSelect = {
            if (pickingOrigin) originKey = it.key else destinationKey = it.key
            pickingOrigin = false
            pickingDestination = false
        },
        onDismiss = { pickingOrigin = false; pickingDestination = false }
    )
    editing?.let { item ->
        JourneyEditDialog(item, onDismiss = { editing = null }, onSave = {
            onJourneysChange(JourneyPolicy.upsert(journeys, it))
            editing = null
        })
    }
    deleting?.let { item ->
        AlertDialog(onDismissRequest = { deleting = null }, title = { Text("删除行程？") },
            text = { Text(item.name) },
            confirmButton = { TextButton(onClick = {
                onJourneysChange(journeys.filterNot { it.id == item.id }); deleting = null
            }) { Text("删除") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("取消") } })
    }
    Scaffold(
        topBar = { TopAppBar(title = { Text("路线与通勤") },
            navigationIcon = { TextButton(onClick = onBack) { Text("返回") } }) },
        bottomBar = bottomBar
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), state = listState, contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                PlannerCard {
                    StationChoiceRow("起点", origin, "选择起点") { pickingOrigin = true }
                    TextButton(enabled = origin != null && destination != null, onClick = {
                        val old = originKey; originKey = destinationKey; destinationKey = old
                    }) { Text("交换起终点 · 返程") }
                    StationChoiceRow("终点", destination, "选择终点") { pickingDestination = true }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OfflineRoutePlanner.Preference.entries.forEach { option ->
                            FilterChip(selected = preference == option, onClick = { preferenceName = option.name },
                                label = { Text(option.displayName) })
                        }
                    }
                    Text("选好起终点后自动规划。方向按下一站识别，请核对站台标识和列车终点。",
                        style = MaterialTheme.typography.bodySmall)
                }
            }
            val current = result
            if (current is OfflineRoutePlanner.PlanResult.Found) {
                val route = current.route
                item {
                    Card(Modifier.fillMaxWidth()) {
                        LineColorStrip(route.legs.map { it.lineColor })
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("离线行程卡", style = MaterialTheme.typography.labelLarge)
                            Text("${route.originName} → ${route.destinationName}",
                                style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                            Text("共 ${route.stopCount} 站 · 换乘 ${route.transferCount} 次")
                            Row {
                                TextButton(onClick = {
                                    editing = SavedJourney(UUID.randomUUID().toString(),
                                        "${route.originName} → ${route.destinationName}",
                                        originKey!!, destinationKey!!, preferenceName)
                                }) { Text("收藏 / 设为通勤") }
                                TextButton(enabled = !sharing, onClick = {
                                    sharing = true
                                    scope.launch {
                                        try {
                                            val intent = withContext(Dispatchers.IO) { JourneyShare.createIntent(context, route) }
                                            context.startActivity(Intent.createChooser(intent, "分享离线行程卡"))
                                        } catch (e: Exception) {
                                            Toast.makeText(context, "无法分享图片，请稍后重试", Toast.LENGTH_LONG).show()
                                        } finally { sharing = false }
                                    }
                                }) { Text(if (sharing) "生成中…" else "分享图片") }
                            }
                        }
                    }
                }
                item { Text("乘车方向助手", style = MaterialTheme.typography.titleMedium) }
                items(TravelGuide.steps(route)) { step ->
                    Card(Modifier.fillMaxWidth()) {
                        LineColorStrip(listOf(step.lineColor))
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(step.lineName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Text("从 ${step.board} 上车")
                            Text(step.directionText, style = MaterialTheme.typography.titleLarge)
                            Text("乘坐 ${step.stopCount} 站")
                            if (step.transferTo != null) {
                                Surface(color = MaterialTheme.colorScheme.tertiaryContainer,
                                    shape = MaterialTheme.shapes.medium) {
                                    Text("换乘提醒 · ${step.finishText}", Modifier.fillMaxWidth().padding(12.dp),
                                        fontWeight = FontWeight.Bold)
                                }
                            } else Text(step.finishText)
                            var expanded by remember { mutableStateOf(false) }
                            TextButton(onClick = { expanded = !expanded }) {
                                Text(if (expanded) "收起沿途站" else "查看沿途站")
                            }
                            if (expanded) Text(route.legs.first { it.lineName == step.lineName &&
                                it.fromStation == step.board }.stationNames.joinToString(" → "),
                                style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                item {
                    Text("静态线网不反映封站、甩站或临时调整，请以现场公告为准。",
                        style = MaterialTheme.typography.bodySmall)
                }
            } else if (current is OfflineRoutePlanner.PlanResult.Rejected) {
                item { Text(current.reason, color = MaterialTheme.colorScheme.error) }
            } else if (originKey != null && destinationKey != null) {
                item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            }
            item { Text("我的通勤与行程", style = MaterialTheme.typography.titleLarge) }
            if (journeys.isEmpty()) item {
                Text("还没有保存的行程。先选择起终点，再点“收藏 / 设为通勤”；可命名为上班、回家等。")
            }
            items(journeys.sortedByDescending { it.commute }, key = { it.id }) { journey ->
                val journeyColors by produceState<List<String?>>(emptyList(), lines, journey) {
                    value = withContext(Dispatchers.Default) {
                        val plan = OfflineRoutePlanner.plan(lines, journey.originKey,
                            journey.destinationKey, JourneyPolicy.preference(journey))
                        (plan as? OfflineRoutePlanner.PlanResult.Found)?.route?.legs
                            ?.map { it.lineColor }.orEmpty()
                    }
                }
                val from = choices.firstOrNull { it.key == journey.originKey }
                val to = choices.firstOrNull { it.key == journey.destinationKey }
                Card(Modifier.fillMaxWidth()) {
                    LineColorStrip(journeyColors)
                    Column(Modifier.padding(14.dp)) {
                        Text((if (journey.commute) "通勤 · " else "行程 · ") + journey.name,
                            style = MaterialTheme.typography.titleMedium)
                        Text("${from?.stationName ?: "起点已移除"} → ${to?.stationName ?: "终点已移除"}")
                        Text(JourneyPolicy.preference(journey).displayName)
                        Row {
                            TextButton(enabled = from != null && to != null, onClick = {
                                originKey = journey.originKey; destinationKey = journey.destinationKey
                                preferenceName = JourneyPolicy.preference(journey).name
                                scope.launch { listState.animateScrollToItem(0) }
                            }) { Text("查看") }
                            TextButton(enabled = from != null && to != null, onClick = {
                                val reverse = JourneyPolicy.reversed(journey)
                                originKey = reverse.originKey; destinationKey = reverse.destinationKey
                                preferenceName = JourneyPolicy.preference(journey).name
                                scope.launch { listState.animateScrollToItem(0) }
                            }) { Text("返程") }
                            TextButton(onClick = { editing = journey }) { Text("编辑") }
                            TextButton(onClick = { deleting = journey }) { Text("删除") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun JourneyEditDialog(item: SavedJourney, onDismiss: () -> Unit, onSave: (SavedJourney) -> Unit) {
    var name by remember(item) { mutableStateOf(item.name) }
    var commute by remember(item) { mutableStateOf(item.commute) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("保存行程") }, text = {
        Column {
            OutlinedTextField(value = name, onValueChange = { name = it.take(40) }, singleLine = true,
                label = { Text("名称，例如上班、回家") })
            Row(Modifier.fillMaxWidth().toggleable(value = commute, role = Role.Checkbox,
                onValueChange = { commute = it }), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = commute, onCheckedChange = null)
                Text("设为常用通勤（优先显示）")
            }
            Text("只保存在本机。打开时根据当前线网重新规划。", style = MaterialTheme.typography.bodySmall)
        }
    }, confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = {
        onSave(item.copy(name = name.trim(), commute = commute))
    }) { Text("保存") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
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
internal fun StationPickerDialog(
    title: String,
    choices: List<OfflineRoutePlanner.StationChoice>,
    excludedKey: String?,
    onSelect: (OfflineRoutePlanner.StationChoice) -> Unit,
    onDismiss: () -> Unit
) {
    var query by remember { mutableStateOf("") }
    val searchIndex = remember(choices, excludedKey) { StationLookup.Index(choices.filter { it.key != excludedKey }) }
    val filtered = remember(searchIndex, query) { searchIndex.search(query, showAllWhenBlank = true) }
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
                    label = { Text("站名、拼音、首字母或线路") }
                )
                Spacer(Modifier.height(8.dp))
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                    if (filtered.isEmpty()) item { Text("没有找到匹配车站，请换个关键词") }
                    items(filtered, key = { it.station.key }) { hit ->
                        val choice = hit.station
                        Column {
                        LineColorStrip(choice.lineColors)
                        Row(
                            modifier = Modifier.fillMaxWidth()
                                .clickable { onSelect(choice) }
                                .padding(horizontal = 8.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                if (hit.suggestion) Text("可能想找 · 请确认", color = MaterialTheme.colorScheme.primary)
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
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } }
    )
}
