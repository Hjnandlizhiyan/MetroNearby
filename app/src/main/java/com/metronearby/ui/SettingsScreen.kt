package com.metronearby.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.metronearby.R
import com.metronearby.data.model.MetroLine
import com.metronearby.data.model.ShortTurn
import com.metronearby.data.model.Station
import com.metronearby.domain.LineVisuals
import com.metronearby.domain.ShortTurnPlanner
import com.metronearby.domain.ThemeMode

/**
 * 「我的线路」的一个候选项。
 *
 * 只收录「已有站点数据」的线路：未收录的线路即使列出来也切不过去，
 * 因此由调用方在构造时就过滤掉 dataFile 为空的条目。
 *
 * 该选项只决定主界面各线路分组的先后顺序（选中的置顶）——主界面始终自动展示
 * 当前站在所有已收录线路的班次，因此它不会限制可见的线路范围。
 */
data class SettingsLineOption(
    val dataFile: String,
    val name: String,
    val cityName: String,
    val colorHex: String? = null,
    val customLine: MetroLine? = null
)

/**
 * 设置界面。
 *
 * 按「分组标题 + 卡片 + 可点选项」组织，后续新增设置项直接往
 * [SettingsSection] 里追加即可，不必改动外层结构。
 *
 * 主题模式本身的状态由上层持有（见 MainActivity），本界面只负责展示与上报，
 * 这样切换后能直接驱动根部的 MetroNearbyTheme 重建。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
    lineOptions: List<SettingsLineOption> = emptyList(),
    selectedLineDataFile: String? = null,
    onLineSelect: (String) -> Unit = {},
    onAddCustomLine: (MetroLine) -> Unit = {},
    onRemoveCustomLine: (String) -> Unit = {},
    onManageSchedules: () -> Unit = {},
    subscriptionCount: Int = 0,
    onManageSubscriptions: () -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    onBack: () -> Unit
) {
    val uriHandler = LocalUriHandler.current
    var showCustomLineDialog by remember { mutableStateOf(false) }
    var showUserGuide by remember { mutableStateOf(false) }
    var customLineToDelete by remember { mutableStateOf<MetroLine?>(null) }
    var expandedCities by remember { mutableStateOf<Set<String>>(emptySet()) }

    if (showUserGuide) {
        UserGuideDialog(onDismiss = { showUserGuide = false })
    }
    if (showCustomLineDialog) {
        CustomLineDialog(
            onDismiss = { showCustomLineDialog = false },
            onConfirm = {
                onAddCustomLine(it)
                showCustomLineDialog = false
            }
        )
    }
    customLineToDelete?.let { line ->
        AlertDialog(
            onDismissRequest = { customLineToDelete = null },
            title = { Text("删除自定义线路？") },
            text = { Text("将删除 ${line.cityName.orEmpty()} · ${line.lineName}。已有用户班次修正会保留在本机，重新创建同一线路不会自动关联。") },
            confirmButton = {
                TextButton(onClick = {
                    onRemoveCustomLine(line.lineId)
                    customLineToDelete = null
                }) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { customLineToDelete = null }) { Text("取消") } }
        )
    }

    Scaffold(
        bottomBar = bottomBar,
        topBar = {
            TopAppBar(
                title = { Text("设置") },
                navigationIcon = {
                    TextButton(onClick = onBack) { Text("返回") }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Image(
                        painter = painterResource(R.drawable.mascot_metro_conductor),
                        contentDescription = "Metro Nearby 地铁乘务员吉祥物",
                        modifier = Modifier.size(92.dp)
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Metro Nearby", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text(
                            "你的小小地铁助手",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            Spacer(Modifier.height(16.dp))

            SettingsSection(title = "常用站与通勤方向") {
                TextButton(onClick = onManageSubscriptions) { Text("管理订阅站点（$subscriptionCount）") }
            }
            Spacer(Modifier.height(16.dp))
            SettingsSection(title = "我的线路") {
                Text(
                    text = "按城市折叠线路。选中的线路会在首页分组中置顶；自定义线路也会参与定位、搜索和订阅。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
                lineOptions.groupBy { it.cityName }.forEach { (cityName, options) ->
                    val expanded = cityName in expandedCities
                    val selected = options.firstOrNull { it.dataFile == selectedLineDataFile }
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable {
                            expandedCities = if (expanded) expandedCities - cityName else expandedCities + cityName
                        }.padding(horizontal = 16.dp, vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CityTag(cityName)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text("${cityName}线路 · ${options.size} 条", fontWeight = FontWeight.SemiBold)
                            selected?.let {
                                Text("当前：${it.name}", style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        Text(if (expanded) "收起" else "展开", color = MaterialTheme.colorScheme.primary)
                    }
                    if (expanded) options.forEach { option ->
                        SettingsRadioRow(
                            label = option.name,
                            selected = option.dataFile == selectedLineDataFile,
                            onClick = { onLineSelect(option.dataFile) },
                            leading = { LineColorDot(option.colorHex) },
                            trailing = option.customLine?.let { line ->
                                { TextButton(onClick = { customLineToDelete = line }) { Text("删除") } }
                            }
                        )
                    }
                }
                TextButton(onClick = { showCustomLineDialog = true }, modifier = Modifier.padding(horizontal = 8.dp)) {
                    Text("＋ 添加自定义线路")
                }
            }
            Spacer(Modifier.height(16.dp))

            SettingsSection(title = "班次表") {
                Text(
                    text = "按线路、全程车或区间车、工作日或周末分别管理总班次数和逐班时刻。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
                TextButton(onClick = onManageSchedules, modifier = Modifier.padding(horizontal = 8.dp)) {
                    Text("管理班次表")
                }
            }
            Spacer(Modifier.height(16.dp))

            SettingsSection(title = "外观") {
                ThemeMode.entries.forEach { mode ->
                    SettingsRadioRow(
                        label = mode.displayName,
                        selected = mode == themeMode,
                        onClick = { onThemeModeChange(mode) }
                    )
                }
            }
            Spacer(Modifier.height(16.dp))

            SettingsSection(title = "关于") {
                Text(
                    text = "查看 Metro Nearby 的源代码、使用说明与开发进度。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
                TextButton(
                    onClick = { showUserGuide = true },
                    modifier = Modifier.padding(horizontal = 8.dp)
                ) {
                    Text("使用指引")
                }
                Text(
                    text = "官方QQ群：305402575",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                )
                TextButton(
                    onClick = { uriHandler.openUri(METRO_NEARBY_REPOSITORY_URL) },
                    modifier = Modifier.padding(horizontal = 8.dp)
                ) {
                    Text("查看 GitHub 代码仓库 ↗")
                }
            }
            Spacer(Modifier.height(16.dp))

        }
    }
}

@Composable
private fun UserGuideDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Metro Nearby 使用指引") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                GuideStep("1", "定位与选站", "允许定位后，首页会寻找附近地铁站；也可以用搜索手动选择任意已收录站点。")
                GuideStep("2", "查看预计到站", "同站多线路和两个方向会分别展示。时间均为离线预计，不代表运营方实时数据。")
                GuideStep("3", "订阅通勤站", "在“我的订阅”关注常用站，并为每个站指定线路和通勤方向。")
                GuideStep("4", "记录到站锚点", "看到列车实际到站时记录锚点；积累同线路、方向、日型和时段的样本后，用户校准会逐步改善。")
                GuideStep("5", "学习发车间隔", "确认没有漏车后连续记录至少三次到站，系统才能学习该时段的发车间隔。")
                GuideStep("6", "管理班次表", "可按线路、交路、工作日或周末修改班次和沿途站时刻；需要撤销时可恢复当前表或整条线路默认值。")
                GuideStep("7", "切换预测口径", "首页可选择系统预计或用户校准；没有适用样本时，用户校准会明确回退到系统预计。")
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("知道了") } }
    )
}

@Composable
private fun GuideStep(number: String, title: String, description: String) {
    Text("$number. $title", fontWeight = FontWeight.SemiBold)
    Text(
        description,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(Modifier.height(12.dp))
}
private const val METRO_NEARBY_REPOSITORY_URL =
    "https://github.com/Hjnandlizhiyan/MetroNearby"

/**
 * 已记录的一条区间车：起终点、已填时刻与删除入口。
 *
 * 落库前用过的校验在展示时再跑一次，这样手工改坏的修正文件会以「哪里不对」的形式
 * 呈现出来，而不是悄无声息地不生效。
 */
@Composable
internal fun ShortTurnRow(
    line: MetroLine,
    shortTurn: ShortTurn,
    onRemove: () -> Unit
) {
    val startName = line.stationById(shortTurn.startStationId)?.name ?: shortTurn.startStationId
    val endName = line.stationById(shortTurn.endStationId)?.name ?: shortTurn.endStationId
    val rejection = (ShortTurnPlanner.plan(line, shortTurn) as? ShortTurnPlanner.Plan.Rejected)?.reason

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = "$startName → $endName", style = MaterialTheme.typography.bodyLarge)
            Text(
                text = shortTurn.departures.joinToString("、"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2
            )
            if (rejection != null) {
                Text(
                    text = rejection,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
        TextButton(onClick = onRemove) { Text("删除") }
    }
}

/**
 * 新增区间车的弹窗：选起终点 + 逐行填起点站的发车时刻。
 *
 * 方向不提供选项——它由起终点在站序中的先后自动决定，让用户选只会多一个出错的机会。
 * 保存按钮只在 [ShortTurnPlanner] 判定合法时可用，因此落库的数据一定是能算出班次的。
 */
@Composable
internal fun ShortTurnDialog(
    line: MetroLine,
    onDismiss: () -> Unit,
    onConfirm: (startStationId: String, endStationId: String, departures: List<String>) -> Unit
) {
    val stations = line.stations
    var startStationId by remember { mutableStateOf(stations.firstOrNull()?.id.orEmpty()) }
    var endStationId by remember { mutableStateOf(stations.lastOrNull()?.id.orEmpty()) }
    var departuresText by remember { mutableStateOf("") }

    val departures = departuresText.split("\n")
    val plan = ShortTurnPlanner.plan(
        line,
        ShortTurn(
            startStationId = startStationId,
            endStationId = endStationId,
            departures = departures
        )
    )
    val rejection = (plan as? ShortTurnPlanner.Plan.Rejected)?.reason
    val canSave = plan is ShortTurnPlanner.Plan.Planned

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加区间车") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    text = "填你在起点站看到的发车时刻，每行一个，用 24 小时制。",
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.height(12.dp))
                StationDropdown(
                    label = "起点站",
                    stations = stations,
                    selectedStationId = startStationId,
                    onSelect = { startStationId = it }
                )
                Spacer(Modifier.height(8.dp))
                StationDropdown(
                    label = "终点站",
                    stations = stations,
                    selectedStationId = endStationId,
                    onSelect = { endStationId = it }
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = departuresText,
                    onValueChange = { departuresText = it },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                    isError = rejection != null && departuresText.isNotBlank(),
                    label = { Text("发车时刻") },
                    placeholder = { Text("08:00\n08:10\n08:25") }
                )
                // 一打开就报错太吵，只在用户开始填之后才提示
                if (rejection != null && departuresText.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = rejection,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = canSave,
                onClick = { onConfirm(startStationId, endStationId, departures) }
            ) {
                Text("保存")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

/**
 * 站点下拉选择。
 *
 * 用下拉菜单而不是横向 chip 列表：一条线动辄三四十站，菜单自带滚动与搜索余量，
 * 也不会把弹窗撑高。
 */
@Composable
private fun StationDropdown(
    label: String,
    stations: List<Station>,
    selectedStationId: String,
    onSelect: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedName = stations.firstOrNull { it.id == selectedStationId }?.name ?: "请选择"

    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Box {
            TextButton(onClick = { expanded = true }) {
                Text(text = selectedName, style = MaterialTheme.typography.bodyLarge)
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                stations.forEach { station ->
                    DropdownMenuItem(
                        text = { Text(station.name) },
                        onClick = {
                            onSelect(station.id)
                            expanded = false
                        }
                    )
                }
            }
        }
    }
}

/** 一个设置分组：分组标题 + 承载若干选项的卡片。 */
@Composable
private fun SettingsSection(title: String, content: @Composable () -> Unit) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
    )
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(vertical = 8.dp)) {
            content()
        }
    }
}

/**
 * 单选型设置项：整行可点，避免只能戳中小圆点。
 *
 * [leading] 用于在文字前放额外的视觉标识（例如线路主题色圆点）。
 */
@Composable
private fun SettingsRadioRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(12.dp))
        leading?.invoke()
        Text(text = label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        trailing?.invoke()
    }
}

/**
 * 线路主题色圆点。
 *
 * 颜色与列表页、首页同源（城市索引里的 color），因此这里同样不能硬编码；
 * 解析失败时退回中性灰，避免因为一条脏数据让整行不可读。
 */
@Composable
private fun LineColorDot(colorHex: String?) {
    // LineVisuals 刻意不依赖 android.graphics.Color，所以这里自己包装成 Compose 的 Color
    val dotColor = LineVisuals.parseHexColor(colorHex)?.let { Color(it.toArgb()) } ?: Color.Gray

    Box(
        modifier = Modifier
            .size(14.dp)
            .background(dotColor, CircleShape)
    )
    Spacer(Modifier.width(10.dp))
}
