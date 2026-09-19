package com.metronearby.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.metronearby.data.MetroRepository
import com.metronearby.data.model.ManagedTrip
import com.metronearby.data.model.MetroLine
import com.metronearby.data.model.Pattern
import com.metronearby.data.model.ServiceTypes
import com.metronearby.domain.DepartureSchedulePlanner
import com.metronearby.domain.TimeUtils
import com.metronearby.domain.TripStationPlanner

/** 逐线路、逐交路、逐日型维护班次及同一趟车的沿途站点时刻。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduleManagementScreen(
    lines: List<MetroRepository.ResolvedLine>,
    initialLineId: String?,
    onSave: (line: MetroLine, patternId: String, serviceType: String, trips: List<ManagedTrip>) -> Unit,
    onRestoreSystem: (lineId: String, patternId: String, serviceType: String) -> Unit,
    onRestoreAllSystem: (lineId: String) -> Unit,
    bottomBar: @Composable () -> Unit = {},
    onBack: () -> Unit
) {
    var selectedLineId by rememberSaveable {
        mutableStateOf(initialLineId?.takeIf { id -> lines.any { it.line.lineId == id } }
            ?: lines.firstOrNull()?.line?.lineId.orEmpty())
    }
    val selectedLine = lines.firstOrNull { it.line.lineId == selectedLineId } ?: lines.firstOrNull()
    val patterns = selectedLine?.line?.patterns.orEmpty()
    var selectedPatternId by rememberSaveable(selectedLineId) {
        mutableStateOf(patterns.firstOrNull()?.id.orEmpty())
    }
    val pattern = patterns.firstOrNull { it.id == selectedPatternId } ?: patterns.firstOrNull()
    var serviceType by rememberSaveable { mutableStateOf(ServiceTypes.WEEKDAY) }

    val systemTimes = if (selectedLine != null && pattern != null) {
        DepartureSchedulePlanner.systemDepartures(selectedLine.line, pattern, serviceType)
    } else emptyList()
    val storedTrips = selectedLine?.overrides?.serviceTrips?.get(pattern?.id)?.get(serviceType)
    val legacyText = selectedLine?.overrides?.serviceDepartures?.get(pattern?.id)?.get(serviceType)
    val legacyTimes = legacyText?.let { DepartureSchedulePlanner.normalize(it) }.orEmpty()
    val storageKey = storedTrips?.joinToString("|") {
        "${it.id}:${it.departureTime}:${it.stationTimes.toSortedMap()}"
    } ?: legacyText?.joinToString("|").orEmpty()
    var trips by remember(selectedLineId, pattern?.id, serviceType, storageKey, systemTimes) {
        val seed = if (legacyText != null) legacyTimes else systemTimes
        mutableStateOf(storedTrips ?: pattern?.let {
            TripStationPlanner.tripsFromDepartures(it.id, serviceType, seed)
        }.orEmpty())
    }
    val departures = trips.mapNotNull { TimeUtils.parseClockTime(it.departureTime) }
    var countText by remember(trips.size) { mutableStateOf(trips.size.toString()) }
    var message by remember(selectedLineId, pattern?.id, serviceType) { mutableStateOf<String?>(null) }
    var addingTrip by remember { mutableStateOf(false) }
    var addTripError by remember { mutableStateOf<String?>(null) }
    var openedTripId by remember { mutableStateOf<String?>(null) }
    var showRestoreAllConfirmation by remember { mutableStateOf(false) }
    val hasAnyManualSchedule = selectedLine?.overrides?.let {
        it.serviceTrips.isNotEmpty() || it.serviceDepartures.isNotEmpty()
    } == true

    if (showRestoreAllConfirmation && selectedLine != null) {
        AlertDialog(
            onDismissRequest = { showRestoreAllConfirmation = false },
            title = { Text("恢复本线路全部默认班次？") },
            text = {
                Text("将清除 ${selectedLine.line.lineName} 所有交路在工作日和周末的手工班次表，并重新采用系统内置时刻或间隔估算。到站校准、区间车和其它线路不会受影响。")
            },
            confirmButton = {
                TextButton(onClick = {
                    onRestoreAllSystem(selectedLine.line.lineId)
                    trips = pattern?.let {
                        TripStationPlanner.tripsFromDepartures(it.id, serviceType, systemTimes)
                    }.orEmpty()
                    countText = systemTimes.size.toString()
                    message = "已恢复本线路全部默认班次"
                    showRestoreAllConfirmation = false
                }) { Text("恢复默认") }
            },
            dismissButton = {
                TextButton(onClick = { showRestoreAllConfirmation = false }) { Text("取消") }
            }
        )
    }
    val openedTrip = trips.firstOrNull { it.id == openedTripId }
    if (openedTrip != null && selectedLine != null && pattern != null) {
        TripStationDetailScreen(
            line = selectedLine.line,
            pattern = pattern,
            serviceType = serviceType,
            trips = trips,
            selectedTripId = openedTrip.id,
            onTripsChange = { updated ->
                trips = updated
                onSave(selectedLine.line, pattern.id, serviceType, updated)
            },
            bottomBar = bottomBar,
            onBack = { openedTripId = null }
        )
        return
    }

    if (addingTrip && selectedLine != null && pattern != null) {
        ScheduleTimeDialog(
            title = "添加班次",
            initialTime = "",
            supportingText = "填写交路起点站的发车时刻。",
            errorMessage = addTripError,
            onDismiss = { addingTrip = false; addTripError = null },
            onConfirm = { seconds ->
                val candidate = (trips + ManagedTrip(
                    id = "manual-${System.nanoTime()}",
                    departureTime = TimeUtils.formatSecondsOfDay(seconds)
                )).sortedBy { TimeUtils.parseClockTime(it.departureTime) }
                when (val validation = TripStationPlanner.validate(
                    selectedLine.line, pattern, serviceType, candidate
                )) {
                    TripStationPlanner.Validation.Valid -> {
                        trips = candidate
                        message = null
                        addTripError = null
                        addingTrip = false
                    }
                    is TripStationPlanner.Validation.Rejected -> addTripError = validation.reason
                }
            }
        )
    }

    Scaffold(
        bottomBar = bottomBar,
        topBar = {
            TopAppBar(
                title = { Text("班次表管理") },
                navigationIcon = { TextButton(onClick = onBack) { Text("返回") } }
            )
        }
    ) { innerPadding ->
        if (selectedLine == null || pattern == null) {
            Box(Modifier.fillMaxSize().padding(innerPadding), contentAlignment = Alignment.Center) {
                Text("没有可管理的线路数据")
            }
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(innerPadding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Text(
                    "每一行是一趟运行班次。点“沿途站”可修改同一趟车的中间站时间，保存时会检查倒置和超车。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            item {
                SelectorCard {
                    ScheduleDropdown(
                        label = "线路",
                        value = lineLabel(selectedLine.line),
                        options = lines.map { it.line.lineId to lineLabel(it.line) },
                        onSelect = { selectedLineId = it; selectedPatternId = ""; message = null }
                    )
                    ScheduleDropdown(
                        label = "交路",
                        value = patternLabel(selectedLine.line, pattern),
                        options = patterns.map { it.id to patternLabel(selectedLine.line, it) },
                        onSelect = { selectedPatternId = it; message = null }
                    )
                    Text("运营日", style = MaterialTheme.typography.labelSmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = serviceType == ServiceTypes.WEEKDAY,
                            onClick = { serviceType = ServiceTypes.WEEKDAY; message = null },
                            label = { Text("工作日") }
                        )
                        FilterChip(
                            selected = serviceType == ServiceTypes.WEEKEND,
                            onClick = { serviceType = ServiceTypes.WEEKEND; message = null },
                            label = { Text("周末") }
                        )
                    }
                    Text(
                        "起点为${stationName(selectedLine.line, pattern.startStationId)}；全程车、区间车都可逐站修正。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            item {
                SelectorCard {
                    val manuallySaved = storedTrips != null || legacyText != null
                    Text(
                        "系统 ${systemTimes.size} 班 · 当前 ${trips.size} 班" +
                            if (manuallySaved) " · 已手动覆盖" else " · 使用系统表",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = countText,
                            onValueChange = { countText = it.filter(Char::isDigit) },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            label = { Text("总班次数") }
                        )
                        Spacer(Modifier.width(8.dp))
                        Button(onClick = {
                            val target = countText.toIntOrNull()
                            val result = if (target == null) {
                                DepartureSchedulePlanner.ResizeResult.Rejected("请输入有效班次数")
                            } else DepartureSchedulePlanner.resize(departures.ifEmpty { systemTimes }, target)
                            when (result) {
                                is DepartureSchedulePlanner.ResizeResult.Resized -> {
                                    trips = TripStationPlanner.tripsFromDepartures(
                                        pattern.id, serviceType, result.departures
                                    )
                                    message = "已生成 ${result.departures.size} 班；中间站修正已清除，请检查后保存"
                                }
                                is DepartureSchedulePlanner.ResizeResult.Rejected -> message = result.reason
                            }
                        }) { Text("生成") }
                    }
                    Text(
                        "改变总数会重新建立班次编号并清除中间站修正，避免修正对应到错误车辆。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    message?.let { StatusText(it) }
                    Text("恢复默认班次", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text(
                        "恢复后重新使用内置时刻或间隔估算，不影响到站校准和区间车。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedButton(
                        enabled = manuallySaved,
                        onClick = {
                            onRestoreSystem(selectedLine.line.lineId, pattern.id, serviceType)
                            trips = TripStationPlanner.tripsFromDepartures(pattern.id, serviceType, systemTimes)
                            countText = systemTimes.size.toString()
                            message = "已恢复当前交路的${if (serviceType == ServiceTypes.WEEKDAY) "工作日" else "周末"}默认班次"
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("恢复当前交路 · ${if (serviceType == ServiceTypes.WEEKDAY) "工作日" else "周末"}")
                    }
                    OutlinedButton(
                        enabled = hasAnyManualSchedule,
                        onClick = { showRestoreAllConfirmation = true },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("恢复整条线路全部默认班次") }
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Button(
                            enabled = trips.isNotEmpty(),
                            onClick = {
                                when (val validation = TripStationPlanner.validate(
                                    selectedLine.line, pattern, serviceType, trips
                                )) {
                                    TripStationPlanner.Validation.Valid -> {
                                        onSave(selectedLine.line, pattern.id, serviceType, trips)
                                        message = "已保存，预测将采用这份班次表"
                                    }
                                    is TripStationPlanner.Validation.Rejected -> message = validation.reason
                                }
                            }
                        ) { Text("保存班次表") }
                        TextButton(onClick = { addTripError = null; addingTrip = true }) { Text("添加一班") }
                    }
                }
            }
            if (trips.isEmpty()) {
                item {
                    Text(
                        "该交路在这个运营日没有系统班次。可以逐班添加，区间车也支持这样补充。",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                itemsIndexed(trips, key = { _, trip -> trip.id }) { index, trip ->
                    DepartureRow(
                        index = index,
                        trip = trip,
                        systemSeconds = systemTimes.getOrNull(index),
                        onStations = { openedTripId = trip.id },
                        onDelete = {
                            trips = trips.toMutableList().apply { removeAt(index) }
                            message = null
                        }
                    )
                }
            }
        }
    }
}

private fun lineLabel(line: MetroLine): String =
    line.cityName?.takeIf { it.isNotBlank() }?.let { "$it · ${line.lineName}" } ?: line.lineName

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TripStationDetailScreen(
    line: MetroLine,
    pattern: Pattern,
    serviceType: String,
    trips: List<ManagedTrip>,
    selectedTripId: String,
    onTripsChange: (List<ManagedTrip>) -> Unit,
    bottomBar: @Composable () -> Unit,
    onBack: () -> Unit
) {
    val trip = trips.firstOrNull { it.id == selectedTripId } ?: return
    val timeline = TripStationPlanner.timeline(line, pattern, serviceType, trip)
    var editingStationId by remember { mutableStateOf<String?>(null) }
    var editError by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }

    val editingStop = timeline.firstOrNull { it.stationId == editingStationId }
    if (editingStop != null) {
        ScheduleTimeDialog(
            title = "修改${editingStop.stationName}",
            initialTime = TimeUtils.formatSecondsOfDay(editingStop.finalSeconds),
            supportingText = if (editingStop.stationId == pattern.startStationId) {
                "修改起点会让同一趟车及已有中间站锚点整体平移。"
            } else {
                "新时间从本站起作用，并顺延到后续站。"
            },
            errorMessage = editError,
            onDismiss = { editingStationId = null; editError = null },
            onConfirm = { seconds ->
                val updatedTrip = TripStationPlanner.updateStation(
                    line, pattern, serviceType, trip, editingStop.stationId, seconds
                )
                val candidate = trips.map { if (it.id == trip.id) updatedTrip ?: it else it }
                val validation = if (updatedTrip == null) {
                    TripStationPlanner.Validation.Rejected("这个时刻无法应用到该班次")
                } else TripStationPlanner.validate(line, pattern, serviceType, candidate)
                when (validation) {
                    TripStationPlanner.Validation.Valid -> {
                        onTripsChange(candidate)
                        message = "已保存${editingStop.stationName}修正"
                        editError = null
                        editingStationId = null
                    }
                    is TripStationPlanner.Validation.Rejected -> editError = validation.reason
                }
            }
        )
    }

    Scaffold(
        bottomBar = bottomBar,
        topBar = {
            TopAppBar(
                title = { Text("${trip.departureTime} 班次沿途站") },
                navigationIcon = { TextButton(onClick = onBack) { Text("返回") } }
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(innerPadding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                Text(
                    "班次编号保持不变。中间站修正会传给后续站；若造成站序倒置或追上前车，系统会拒绝保存。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                message?.let { StatusText(it) }
            }
            items(timeline, key = { it.stationId }) { stop ->
                Card(Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(stop.stationName, fontWeight = FontWeight.SemiBold)
                            Text(
                                "基础 ${TimeUtils.formatSecondsOfDay(stop.systemSeconds)} · " +
                                    "最终 ${TimeUtils.formatSecondsOfDay(stop.finalSeconds)}" +
                                    if (stop.isAnchor) " · 本站锚点" else "",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        TextButton(onClick = { editError = null; editingStationId = stop.stationId }) { Text("修改") }
                        if (stop.isAnchor) {
                            TextButton(onClick = {
                                val cleared = TripStationPlanner.clearStation(pattern, trip, stop.stationId)
                                val candidate = trips.map { if (it.id == trip.id) cleared else it }
                                when (val validation = TripStationPlanner.validate(line, pattern, serviceType, candidate)) {
                                    TripStationPlanner.Validation.Valid -> {
                                        onTripsChange(candidate)
                                        message = "已恢复${stop.stationName}"
                                    }
                                    is TripStationPlanner.Validation.Rejected -> message = validation.reason
                                }
                            }) { Text("恢复") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SelectorCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
    }
}

@Composable
private fun StatusText(message: String) {
    Text(
        message,
        style = MaterialTheme.typography.bodySmall,
        color = if (message.startsWith("已")) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.error
    )
}

@Composable
private fun ScheduleDropdown(
    label: String,
    value: String,
    options: List<Pair<String, String>>,
    onSelect: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall)
        Box {
            TextButton(onClick = { expanded = true }) { Text(value) }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                options.forEach { (id, text) ->
                    DropdownMenuItem(text = { Text(text) }, onClick = {
                        onSelect(id)
                        expanded = false
                    })
                }
            }
        }
    }
}

@Composable
private fun DepartureRow(
    index: Int,
    trip: ManagedTrip,
    systemSeconds: Int?,
    onStations: () -> Unit,
    onDelete: () -> Unit
) {
    val finalSeconds = TimeUtils.parseClockTime(trip.departureTime) ?: 0
    Card(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("${index + 1}", modifier = Modifier.width(36.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Column(Modifier.weight(1f)) {
                Text(trip.departureTime, style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold)
                val note = when {
                    systemSeconds == null -> "新增班次"
                    systemSeconds == finalSeconds -> "与系统预计一致"
                    else -> "系统 ${TimeUtils.formatSecondsOfDay(systemSeconds)} · " +
                        offsetLabel(finalSeconds - systemSeconds)
                }
                Text(
                    note + if (trip.stationTimes.isNotEmpty()) " · ${trip.stationTimes.size} 个站点锚点" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            TextButton(onClick = onStations) { Text("沿途站") }
            TextButton(onClick = onDelete) { Text("删除") }
        }
    }
}

@Composable
private fun ScheduleTimeDialog(
    title: String,
    initialTime: String,
    supportingText: String,
    errorMessage: String? = null,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit
) {
    var text by remember(initialTime) { mutableStateOf(initialTime) }
    val parsed = TimeUtils.parseClockTime(text)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                Text(supportingText, style = MaterialTheme.typography.bodySmall)
                errorMessage?.let {
                    Spacer(Modifier.height(6.dp))
                    Text(it, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error)
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    isError = text.isNotBlank() && parsed == null,
                    label = { Text("时刻") },
                    placeholder = { Text("08:30") }
                )
            }
        },
        confirmButton = {
            TextButton(enabled = parsed != null, onClick = { parsed?.let(onConfirm) }) { Text("确定") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

private fun patternLabel(line: MetroLine, pattern: Pattern): String {
    val kind = if (pattern.isShortTurn) "区间车" else pattern.name
    val direction = pattern.directionLabel?.trim()?.takeIf { it.isNotEmpty() }
    return if (direction != null) {
        "$kind · $direction · ${stationName(line, pattern.startStationId)}发车"
    } else {
        "$kind · ${stationName(line, pattern.startStationId)} → ${stationName(line, pattern.endStationId)}"
    }
}

private fun stationName(line: MetroLine, stationId: String): String =
    line.stationById(stationId)?.name ?: stationId

private fun offsetLabel(seconds: Int): String {
    val sign = if (seconds >= 0) "+" else "−"
    val absolute = kotlin.math.abs(seconds)
    val minutes = absolute / 60
    val remain = absolute % 60
    return if (remain == 0) "$sign${minutes} 分钟" else "$sign${minutes}分${remain}秒"
}
