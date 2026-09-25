package com.metronearby.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.metronearby.data.model.*
import com.metronearby.data.source.StationFacilityStore
import com.metronearby.domain.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StationDetailsScreen(
    stationKey: String,
    lines: List<MetroLine>,
    onBack: () -> Unit,
    bottomBar: @Composable () -> Unit = {}
) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val store = remember(context) { StationFacilityStore(context) }
    val scope = rememberCoroutineScope()
    val station = remember(lines, stationKey) {
        OfflineRoutePlanner.stationChoices(lines).firstOrNull { it.key == stationKey }
    }
    val stationLines = remember(lines, stationKey) {
        StationFacilityPolicy.linesFor(lines, stationKey)
    }
    var catalog by remember { mutableStateOf<StationFacilityCatalog?>(null) }
    var catalogError by remember { mutableStateOf(false) }
    var notes by remember { mutableStateOf<List<StationPersonalNotes>>(emptyList()) }
    var notesReady by remember { mutableStateOf(false) }
    var notesError by remember { mutableStateOf(false) }
    var retry by remember { mutableStateOf(0) }
    var editing by rememberSaveable(stationKey) { mutableStateOf(false) }
    var clearing by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var feedback by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(retry) {
        catalogError = false
        catalog = null
        try { catalog = withContext(Dispatchers.IO) { store.loadCatalog() } }
        catch (e: Exception) { catalogError = true }
    }
    LaunchedEffect(retry) {
        notesReady = false
        notesError = false
        try {
            notes = withContext(Dispatchers.IO) { store.loadNotes() }
            notesReady = true
        } catch (e: Exception) { notesError = true }
    }
    val personal = notes.firstOrNull { it.stationKey == stationKey } ?: StationPersonalNotes(stationKey)
    BackHandler(enabled = !editing && !clearing, onBack = onBack)

    if (editing && notesReady) StationNotesDialog(
        initial = personal,
        saving = saving,
        feedback = feedback,
        onDismiss = { if (!saving) editing = false },
        onSave = { draft ->
            saving = true
            feedback = null
            scope.launch {
                try {
                    notes = withContext(Dispatchers.IO) { store.save(draft) }
                    editing = false
                    feedback = "个人备注已保存"
                } catch (e: Exception) { feedback = "保存未完成，请重试。" }
                finally { saving = false }
            }
        }
    )
    if (clearing) AlertDialog(
        onDismissRequest = { if (!saving) clearing = false },
        title = { Text("清除本站个人备注？") },
        text = { Text("只清除你为本站填写的出口、设施和换乘备注，官方资料和其他站备注会保留。") },
        confirmButton = { TextButton(enabled = !saving, onClick = {
            saving = true
            scope.launch {
                try {
                    notes = withContext(Dispatchers.IO) { store.remove(stationKey) }
                    clearing = false
                    feedback = "本站个人备注已清除"
                } catch (e: Exception) {
                    clearing = false
                    feedback = "清除失败，请重试。"
                } finally { saving = false }
            }
        }) { Text(if (saving) "处理中…" else "清除") } },
        dismissButton = { TextButton(enabled = !saving, onClick = { clearing = false }) { Text("取消") } }
    )

    Scaffold(
        topBar = { TopAppBar(
            title = { Text("出入口与设施") },
            navigationIcon = { TextButton(onClick = onBack) { Text("返回") } }
        ) },
        bottomBar = bottomBar
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    LineColorStrip(stationLines.map { it.color })
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(station?.stationName ?: "车站暂不可用",
                            style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                        Text(station?.let { "${it.cityName} · ${it.lineNames.joinToString(" / ")}" }
                            ?: "车站可能已移除，请返回重新选择。")
                        Text("先收录少量示范站。未收录不代表没有设施；设施位置与开放状态请以现场为准。",
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            item {
                Card(Modifier.fillMaxWidth()) {
                    LineColorStrip(stationLines.map { it.color })
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("我的车站备注", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text("个人记录 · 仅存本机，不会覆盖官方资料", style = MaterialTheme.typography.bodySmall)
                        when {
                            notesError -> {
                                Text("个人备注读取失败，已保留原始数据；暂时不能编辑。", color = MaterialTheme.colorScheme.error)
                                TextButton(onClick = { retry++ }) { Text("重试读取") }
                            }
                            !notesReady -> LinearProgressIndicator(Modifier.fillMaxWidth())
                            else -> {
                                if (StationFacilityPolicy.hasNotes(personal)) {
                                    NoteSection("常走出口 / 周边地标", personal.exitNote)
                                    NoteSection("设施位置", personal.facilityNote)
                                    NoteSection("换乘提示", personal.transferNote)
                                } else {
                                    Text("尚未保存个人备注，可记录常走出口、设施位置和换乘提示。")
                                }
                                if (personal.updatedAtMillis > 0) Text(
                                    "个人记录更新：" + DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                                        .format(Date(personal.updatedAtMillis)),
                                    style = MaterialTheme.typography.bodySmall
                                )
                                Row {
                                    TextButton(enabled = station != null && !saving,
                                        onClick = { feedback = null; editing = true }) { Text("编辑我的备注") }
                                    if (StationFacilityPolicy.hasNotes(personal)) TextButton(enabled = !saving,
                                        onClick = { clearing = true }) { Text("清除本站备注") }
                                }
                            }
                        }
                        feedback?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }
            item { Text("官方资料 · 按线路查看", style = MaterialTheme.typography.titleLarge) }
            if (catalogError) item {
                Text("内置资料加载失败，请重试。", color = MaterialTheme.colorScheme.error)
                TextButton(onClick = { retry++ }) { Text("重试加载资料") }
            } else if (catalog == null) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            else stationLines.forEach { line ->
                val record = StationFacilityPolicy.recordsFor(catalog!!, stationKey, setOf(line.lineId)).firstOrNull()
                item(key = line.lineId) {
                    Card(Modifier.fillMaxWidth()) {
                        LineColorStrip(listOf(line.color))
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(line.lineName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            if (record == null) {
                                Text("该线路的出入口与设施资料暂未收录。")
                                Text("你可以先在“我的车站备注”里保存自己熟悉的信息。",
                                    style = MaterialTheme.typography.bodySmall)
                            } else {
                                Text("已收录出口（非完整清单）", fontWeight = FontWeight.SemiBold)
                                if (record.exits.isEmpty()) Text("暂未收录出口信息")
                                record.exits.forEach { exit ->
                                    Text("${exit.name}：${exit.description}")
                                }
                                Text("设施位置", fontWeight = FontWeight.SemiBold)
                                if (record.facilities.isEmpty()) Text("暂未收录设施信息")
                                record.facilities.forEach { facility ->
                                    Text("${facility.name}：${facility.location}")
                                }
                                if (record.facilities.none { it.name.contains("直梯") }) Text(
                                    "直梯信息暂未收录（不等于没有直梯）。", style = MaterialTheme.typography.bodySmall
                                )
                                Text("来源：${record.sourceTitle}", style = MaterialTheme.typography.bodySmall)
                                Text("原页面更新：${record.sourceUpdatedOn} · 资料核对：${record.checkedOn}",
                                    style = MaterialTheme.typography.bodySmall)
                                Text(record.evidenceNote, style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                TextButton(onClick = {
                                    try { uriHandler.openUri(record.sourceUrl) }
                                    catch (e: Exception) { feedback = "没有可用浏览器，来源网址见下方。" }
                                }) { Text("查看官网来源（需联网）") }
                                Text(record.sourceUrl, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NoteSection(label: String, value: String) {
    Text(label, style = MaterialTheme.typography.labelLarge)
    Text(value.ifBlank { "尚未填写" }, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun StationNotesDialog(
    initial: StationPersonalNotes,
    saving: Boolean,
    feedback: String?,
    onDismiss: () -> Unit,
    onSave: (StationPersonalNotes) -> Unit
) {
    var exit by rememberSaveable(initial.stationKey) { mutableStateOf(initial.exitNote) }
    var facility by rememberSaveable(initial.stationKey) { mutableStateOf(initial.facilityNote) }
    var transfer by rememberSaveable(initial.stationKey) { mutableStateOf(initial.transferNote) }
    val draft = initial.copy(exitNote = exit, facilityNote = facility, transferNote = transfer)
    val error = StationFacilityPolicy.error(draft)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("编辑我的车站备注") },
        text = {
            Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("个人记录，不是官方资料。每项最多 1000 字。")
                OutlinedTextField(exit, { exit = it }, enabled = !saving,
                    label = { Text("常走出口 / 周边地标") }, minLines = 2, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(facility, { facility = it }, enabled = !saving,
                    label = { Text("设施位置") }, minLines = 2, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(transfer, { transfer = it }, enabled = !saving,
                    label = { Text("换乘提示") }, minLines = 2, modifier = Modifier.fillMaxWidth())
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                feedback?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = { TextButton(enabled = error == null && !saving,
            onClick = { onSave(draft) }) { Text(if (saving) "保存中…" else "保存备注") } },
        dismissButton = { TextButton(enabled = !saving, onClick = onDismiss) { Text("取消") } }
    )
}
