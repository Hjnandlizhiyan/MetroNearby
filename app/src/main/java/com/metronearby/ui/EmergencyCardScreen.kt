package com.metronearby.ui

import android.content.Intent
import android.net.Uri
import android.widget.Toast
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
import com.metronearby.data.model.MetroLine
import com.metronearby.data.source.EmergencyStore
import com.metronearby.domain.*
import com.metronearby.location.AndroidLocationProvider
import com.metronearby.location.NearbyStationCatalog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EmergencyCardScreen(
    lines: List<MetroLine>,
    captureError: Boolean = false,
    locationRevision: Long = 0,
    onBack: () -> Unit,
    onLocateHome: () -> Unit,
    bottomBar: @Composable () -> Unit = {}
) {
    val context = LocalContext.current
    val uri = LocalUriHandler.current
    val store = remember(context) { EmergencyStore(context) }
    val provider = remember(context) { AndroidLocationProvider(context.applicationContext) }
    val scope = rememberCoroutineScope()
    var personal by remember { mutableStateOf(EmergencyPersonal()) }
    var location by remember { mutableStateOf<UserLocation?>(null) }
    var route by remember { mutableStateOf<EmergencyRoute?>(null) }
    var personalReady by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var locationError by remember { mutableStateOf(false) }
    var routeError by remember { mutableStateOf(false) }
    var feedback by remember { mutableStateOf<String?>(null) }
    var retry by remember { mutableIntStateOf(0) }
    var refreshedHere by remember { mutableStateOf(false) }
    var editing by rememberSaveable { mutableStateOf(false) }
    var clearKind by rememberSaveable { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(15_000) } }
    LaunchedEffect(retry, locationRevision) {
        personalReady = false
        error = null
        val results = withContext(Dispatchers.IO) {
            Triple(runCatching { store.personal() }, runCatching { store.location() }, runCatching { store.route() })
        }
        results.first.onSuccess { personal = it; personalReady = true }
            .onFailure { error = "个人信息读取失败，重试前不会覆盖原记录。" }
        location = results.second.getOrNull()
        route = results.third.getOrNull()
        locationError = results.second.isFailure
        routeError = results.third.isFailure
    }
    val nearby = remember(location, lines) {
        location?.let { NearbyStationCatalog.find(lines, it.lat, it.lng, 3) }.orEmpty()
    }
    val dial: (String) -> Unit = { raw ->
        EmergencyCardPolicy.dialNumber(raw)?.let { number ->
            try { context.startActivity(Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", number, null))) }
            catch (e: Exception) { Toast.makeText(context, "无法打开拨号界面，可手动输入号码", Toast.LENGTH_LONG).show() }
        }
    }
    BackHandler(enabled = !editing && clearKind == null, onBack = onBack)
    if (editing && personalReady) EmergencyEditDialog(personal, busy, feedback,
        onDismiss = { if (!busy) { editing = false; feedback = null } },
        onSave = { value ->
            busy = true
            scope.launch {
                try {
                    withContext(Dispatchers.IO) { store.savePersonal(value) }
                    personal = EmergencyCardPolicy.clean(value)
                    editing = false
                    feedback = "个人信息已保存"
                } catch (e: Exception) { feedback = "保存失败，请重试" }
                finally { busy = false }
            }
        })
    if (clearKind != null) AlertDialog(
        onDismissRequest = { if (!busy) clearKind = null },
        title = { Text(if (clearKind == "personal") "清除联系人和备注？" else "清除位置和路线记录？") },
        text = { Text("仅清除应急卡中的这组信息，不影响收藏和已保存行程。") },
        confirmButton = { TextButton(enabled = !busy, onClick = {
            val kind = clearKind
            busy = true
            scope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        if (kind == "personal") store.clearPersonal() else store.clearSnapshots()
                    }
                    if (kind == "personal") { personal = EmergencyPersonal(); personalReady = true; error = null }
                    else { location = null; route = null; locationError = false; routeError = false }
                    clearKind = null
                    feedback = "已清除"
                } catch (e: Exception) { feedback = "清除失败，请重试"; clearKind = null }
                finally { busy = false }
            }
        }) { Text("清除") } },
        dismissButton = { TextButton(enabled = !busy, onClick = { clearKind = null }) { Text("取消") } }
    )
    Scaffold(
        topBar = { TopAppBar(title = { Text("离线应急卡") },
            navigationIcon = { TextButton(onClick = onBack) { Text("返回") } }) },
        bottomBar = bottomBar
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            item { Text("把位置、行程和联系信息集中展示。页面可离线查看；拨号仍由系统电话处理。",
                style = MaterialTheme.typography.bodyMedium) }
            feedback?.let { item { Text(it, color = MaterialTheme.colorScheme.primary) } }
            item {
                EmergencySection("最近一次定位") {
                    if (captureError && !refreshedHere) Text("首页最近一次定位未能保存，下方可能仍为旧记录。请重新定位。")
                    val point = location
                    if (locationError) Text("位置记录读取失败")
                    else if (point == null) Text("尚无定位记录。手动选择的车站不会当作你的位置。")
                    else {
                        Text(UserLocationPolicy.coordinatesText(point), style = MaterialTheme.typography.titleLarge)
                        Text("采集时间 · ${emergencyTime(point.capturedAtMillis)}")
                        Text(UserLocationPolicy.detailText(point, now))
                        Text(if (EmergencyCardPolicy.outdated(point, now))
                            "这是旧位置记录，不能代表你现在的位置，请重新定位。"
                            else "这是最近采集的位置，移动后请重新定位。",
                            color = MaterialTheme.colorScheme.primary)
                        Text("经纬度为用户定位点，不是车站或出口坐标。",
                            style = MaterialTheme.typography.bodySmall)
                    }
                    Row {
                        TextButton(enabled = !busy, onClick = {
                            if (!provider.hasPermission()) {
                                feedback = "请先回首页允许定位，再打开应急卡"
                            } else {
                                busy = true
                                feedback = "正在获取位置…"
                                scope.launch {
                                    try {
                                        val value = provider.resolveBest()
                                        if (value == null) feedback = "未获取到位置，保留原记录"
                                        else {
                                            withContext(Dispatchers.IO) { store.saveLocation(value) }
                                            location = value
                                            locationError = false
                                            refreshedHere = true
                                            now = System.currentTimeMillis()
                                            feedback = "位置已更新"
                                        }
                                    } catch (e: Exception) { feedback = "定位或保存失败，保留原记录" }
                                    finally { busy = false }
                                }
                            }
                        }) { Text(if (busy) "请稍候" else "重新定位") }
                        TextButton(onClick = onLocateHome) { Text("回首页定位") }
                    }
                }
            }
            item {
                EmergencySection("该定位点附近的车站") {
                    Text("距离为直线距离，不是步行距离。旧位置的附近站也可能已不适用。",
                        style = MaterialTheme.typography.bodySmall)
                    if (nearby.isEmpty()) Text("有定位和线路数据后显示附近站")
                    nearby.forEach { station ->
                        LineColorStrip(lines.filter { it.lineId in station.lineIds }.map { it.color })
                        Text("${station.stationName} · ${station.distanceMeters.toInt()} 米",
                            fontWeight = FontWeight.Bold)
                        Text("${station.cityName} · ${station.lineNames.joinToString(" / ")}")
                    }
                }
            }
            item {
                EmergencySection("最近成功规划的路线", route?.colors.orEmpty()) {
                    if (routeError) Text("路线记录读取失败")
                    else if (route == null) Text("尚无路线记录，可在底部“路线”中规划。")
                    else route?.let { saved ->
                        Text("规划时间 · ${emergencyTime(saved.capturedAtMillis)}")
                        Text("保存的静态路线，不代表你正在乘坐或当前运营状态。",
                            style = MaterialTheme.typography.bodySmall)
                        saved.instructions.forEach { Text(it) }
                    }
                }
            }
            item {
                EmergencySection("我的紧急联系信息") {
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    if (personalReady) {
                        Text(personal.contactName.ifBlank { "尚未填写联系人" }, fontWeight = FontWeight.Bold)
                        if (personal.phone.isNotBlank()) TextButton(onClick = { dial(personal.phone) }) {
                            Text("打开拨号 · ${personal.phone}")
                        }
                        if (personal.note.isNotBlank()) Text(personal.note)
                        Text("仅存本机，不读取通讯录、不自动联系他人；可随时清除。",
                            style = MaterialTheme.typography.bodySmall)
                        Row {
                            TextButton(enabled = !busy, onClick = { feedback = null; editing = true }) { Text("编辑联系信息") }
                            TextButton(enabled = !busy, onClick = { clearKind = "personal" }) { Text("清除个人信息") }
                        }
                    }
                }
            }
            item {
                EmergencySection("北京 · 运营服务热线") {
                    Text("北京地铁运营公司")
                    TextButton(onClick = { dial("010-96165") }) { Text("打开拨号 · 010-96165") }
                    Text("北京轨道交通路网乘客服务热线（含京港地铁咨询）")
                    TextButton(onClick = { dial("010-96123") }) { Text("打开拨号 · 010-96123") }
                    Text("资料核对：2026-09-25 · 非实时服务状态",
                        style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = {
                        runCatching { uri.openUri("https://www.bjsubway.com/contact/") }
                            .onFailure { feedback = "无法打开浏览器" }
                    }) { Text("北京地铁官方来源（需联网）") }
                    TextButton(onClick = {
                        runCatching { uri.openUri("https://www.mtr.bj.cn/article/65fd5e7b5c0701195db73815.html") }
                            .onFailure { feedback = "无法打开浏览器" }
                    }) { Text("京港地铁官方来源（需联网）") }
                }
            }
            item {
                Row {
                    TextButton(enabled = !busy, onClick = { retry++ }) { Text("重新读取记录") }
                    TextButton(enabled = !busy, onClick = { clearKind = "snapshots" }) { Text("清除位置与路线记录") }
                }
                Text("仅保留一份最近位置和一条最近路线。以后定位或规划成功会更新记录，不保存轨迹。",
                    style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

private fun emergencyTime(millis: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(Date(millis))

@Composable
private fun EmergencySection(title: String, colors: List<String?> = emptyList(),
    content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        if (colors.isNotEmpty()) LineColorStrip(colors)
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            content()
        }
    }
}

@Composable
private fun EmergencyEditDialog(initial: EmergencyPersonal, busy: Boolean, feedback: String?,
    onDismiss: () -> Unit, onSave: (EmergencyPersonal) -> Unit) {
    var name by rememberSaveable { mutableStateOf(initial.contactName) }
    var phone by rememberSaveable { mutableStateOf(initial.phone) }
    var note by rememberSaveable { mutableStateOf(initial.note) }
    val draft = EmergencyPersonal(name, phone, note)
    val error = EmergencyCardPolicy.error(draft)
    AlertDialog(onDismissRequest = onDismiss, title = { Text("紧急联系人与备注") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("联系人（选填，40字内）") }, enabled = !busy)
                OutlinedTextField(phone, { phone = it }, label = { Text("电话号码（选填）") }, enabled = !busy, singleLine = true)
                OutlinedTextField(note, { note = it }, label = { Text("紧急备注（选填，500字内）") }, enabled = !busy, minLines = 3)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                feedback?.let { Text(it) }
            }
        },
        confirmButton = { TextButton(enabled = !busy && error == null, onClick = { onSave(draft) }) { Text("保存") } },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("取消") } })
}
