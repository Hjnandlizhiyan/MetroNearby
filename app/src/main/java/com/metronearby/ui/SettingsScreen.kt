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
import androidx.compose.material3.Switch
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
    subscriptionCount: Int = 0,
    onManageSubscriptions: () -> Unit = {},
    onOpenFutureRoadmap: () -> Unit = {},
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
            text = { Text("将删除 ${line.cityName.orEmpty()} · ${line.lineName}。删除后不再参与定位和路线规划；已经保存的行程会保留，相关站点不可用时会提示。") },
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
                TextButton(onClick = onManageSubscriptions) { Text("管理收藏站点（$subscriptionCount）") }
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
                TextButton(
                    onClick = onOpenFutureRoadmap,
                    modifier = Modifier.padding(horizontal = 8.dp)
                ) {
                    Text("未来规划")
                }
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
                GuideStep("1", "定位与附近站", "允许定位后，首页会列出最近车站和多个附近备选站；距离是站点中心之间的直线距离。")
                GuideStep("2", "规划离线路线", "从底部“路线”选择起终点，可按少换乘或少经过站规划乘车线路、站数与换乘站。")
                GuideStep("搜索", "更方便地找站", "首页、路线和收藏目的地可输入中文、拼音全拼、首字母或已收录别名。没找到时可能显示错字建议，请核对城市和线路再选择。")
                GuideStep("3", "保存常用站", "在底部“收藏”编辑常用站，保存通勤方向、家或公司标签、常走出口和备注。选择常用目的地后，可从收藏卡一键规划路线；内容仅存本机。")
                GuideStep("4", "查看线网与线路", "线网图支持缩放移动；选择线路和搜索站点均可离线使用。")
                GuideStep("5", "附近站雷达", "上北下南查看周边车站的方位和直线距离，点击车站可从此站规划路线。")
                GuideStep("6", "保存通勤与行程", "路线页规划后可收藏行程、设为常用通勤、反向规划，并分享行程图片。")
                GuideStep("7", "出入口与设施", "首页或收藏卡点击“出入口与设施”，查看已收录资料和来源日期；未收录站可先保存个人备注。")
                GuideStep("8", "添加自定义线路", "可按城市添加自己的线路、站序和坐标，参与定位、搜索与路线规划。")
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
