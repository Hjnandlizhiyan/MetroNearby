package com.metronearby.ui

import android.Manifest
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip

import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.metronearby.R
import com.metronearby.data.MetroRepository
import com.metronearby.data.model.ArrivalObservation
import com.metronearby.data.model.MetroLine
import com.metronearby.data.model.StationSubscription
import com.metronearby.domain.StationSubscriptions
import com.metronearby.data.model.Station
import com.metronearby.data.model.UserOverrides
import com.metronearby.data.source.AssetMetroDataSource
import com.metronearby.data.source.FileOverrideStore
import com.metronearby.domain.ArrivalEstimator
import com.metronearby.domain.CalibrationLearning
import com.metronearby.domain.ArrivalDisplayMode
import com.metronearby.domain.ArrivalItem
import com.metronearby.domain.ArrivalPresentation
import com.metronearby.domain.ArrivalSource
import com.metronearby.domain.DirectionSchedule
import com.metronearby.domain.LineVisuals
import com.metronearby.domain.ObservationTimeBand
import com.metronearby.domain.NetworkMapCatalog
import com.metronearby.domain.ServiceTypeResolver
import com.metronearby.domain.StationSearch
import com.metronearby.domain.StationServiceWindow
import com.metronearby.domain.ThemeMode
import com.metronearby.domain.TimeUtils
import com.metronearby.domain.TransferStationResolver
import com.metronearby.domain.UserLocation
import com.metronearby.domain.UserLocationPolicy
import com.metronearby.domain.present
import com.metronearby.domain.serviceTypeDisplayName
import com.metronearby.domain.userArrivalSecondsOfDay
import com.metronearby.location.AndroidLocationProvider
import com.metronearby.location.NearestStationFinder
import java.io.File
import java.util.Calendar
import kotlinx.coroutines.delay

/**
 * 「当前站在某条线路上对应的那条记录」。
 *
 * 换乘站在各条线路里是彼此独立的站点（1 号线复兴门 `bj1_12`、2 号线复兴门 `bj2_16`），
 * 站点 id 只在线路内唯一，所以跨线路展示时必须成对保存「线路 + 它自己的站点 id」。
 */
private data class StationOnLine(
    val line: MetroLine,
    val overrides: UserOverrides?,
    val stationId: String
)

private sealed interface ScreenState {
    data object Loading : ScreenState
    data object NeedPermission : ScreenState
    data class Failed(val message: String) : ScreenState
    data class Ready(
        /** 当前站在哪些已收录线路里也有；换乘站不止一条，列表顺序即分组的展示顺序 */
        val stationOnLines: List<StationOnLine>,
        val stationName: String,
        val distanceMeters: Double,
        val userLocation: UserLocation? = null,
        /** 站点由用户搜索选定，而非定位推荐的最近站 */
        val isManuallySelected: Boolean = false
    ) : ScreenState
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MetroNearbyScreen(
    arrivalDisplayMode: ArrivalDisplayMode = ArrivalDisplayMode.DEFAULT,
    onArrivalDisplayModeChange: (ArrivalDisplayMode) -> Unit = {},
    subscriptions: List<StationSubscription> = emptyList(),
    onSubscriptionsChange: (List<StationSubscription>) -> Unit = {},
    customLines: List<MetroLine> = emptyList(),
    onCustomLinesChange: (List<MetroLine>) -> Unit = {},
    networkMapCityId: String = NetworkMapCatalog.DEFAULT_CITY_ID,
    onNetworkMapCityChange: (String) -> Unit = {},
    lineDataFile: String = DEFAULT_LINE_DATA_FILE,
    onLineChange: (String) -> Unit = {},
    themeMode: ThemeMode = ThemeMode.DEFAULT,
    onThemeModeChange: (ThemeMode) -> Unit = {}
) {
    val context = LocalContext.current
    var showSubscriptions by rememberSaveable { mutableStateOf(subscriptions.isNotEmpty()) }
    var editingSubscription by remember { mutableStateOf<StationSubscription?>(null) }
    var headwayTarget by remember { mutableStateOf<Pair<String, ArrivalItem>?>(null) }
    val locationProvider = remember(context) { AndroidLocationProvider(context.applicationContext) }
    val repository = remember(context) {
        MetroRepository(
            dataSource = AssetMetroDataSource(context.applicationContext),
            overrideStore = FileOverrideStore(File(context.filesDir, OVERRIDES_FILE))
        )
    }

    // 设置界面的线路候选：来自城市索引，只保留已收录站点数据的线路。
    // 索引读取失败不影响主流程，此时退化成只加载「我的线路」这一条。
    var lineOptions by remember { mutableStateOf<List<SettingsLineOption>>(emptyList()) }
    var lineOptionsLoaded by remember { mutableStateOf(false) }
    LaunchedEffect(repository, customLines) {
        lineOptions = runCatching {
            val city = repository.loadCityIndex()
            val builtIn = city.lines.mapNotNull { ref ->
                ref.dataFile?.takeIf { it.isNotBlank() }
                    ?.let {
                        SettingsLineOption(
                            dataFile = it,
                            name = ref.name,
                            cityName = city.cityName,
                            colorHex = ref.color
                        )
                    }
            }
            builtIn + customLines.map { line ->
                SettingsLineOption(
                    dataFile = "custom:${line.lineId}",
                    name = line.lineName,
                    cityName = line.cityName ?: "自定义",
                    colorHex = line.color,
                    customLine = line
                )
            }
        }.getOrDefault(emptyList())
        lineOptionsLoaded = true
    }

    // 城市下所有已收录线路的数据。换乘站要同时展示多条线，所以这里一次性全加载，
    // 而不是只加载「我的线路」——后者只决定各分组在界面上的先后顺序。
    var loadedLines by remember { mutableStateOf<List<MetroRepository.ResolvedLine>>(emptyList()) }
    // 「我的线路」对应的那份数据，单独留引用：区间车设置只操作当前线路，
    // 而某条线路加载失败会让 loadedLines 的下标整体错位，不能用下标去猜。
    var selectedResolved by remember { mutableStateOf<MetroRepository.ResolvedLine?>(null) }
    var dataError by remember { mutableStateOf<String?>(null) }
    // 用户录入观测后以此触发一次重新解析，让新样本立刻参与下一次推算
    var reloadKey by remember { mutableStateOf(0) }

    LaunchedEffect(lineOptionsLoaded, lineDataFile, reloadKey) {
        if (!lineOptionsLoaded) return@LaunchedEffect
        dataError = null
        // 「我的线路」置顶，其余按城市索引顺序；单条线路数据损坏不该拖垮其它线路
        val files = (listOf(lineDataFile) + lineOptions.map { it.dataFile }).distinct()
        val loaded = files.mapNotNull { file ->
            val custom = lineOptions.firstOrNull { it.dataFile == file }?.customLine
            runCatching {
                file to if (custom != null) repository.resolveLine(custom) else repository.loadResolvedLine(file)
            }.getOrNull()
        }
        dataError = if (loaded.isEmpty()) "数据加载失败，请稍后重试" else null
        loadedLines = loaded.map { it.second }
        selectedResolved = loaded.firstOrNull { it.first == lineDataFile }?.second
    }

    var granted by remember { mutableStateOf(locationProvider.hasPermission()) }
    var retryKey by remember { mutableStateOf(0) }
    // 用户选定的站。跨线路的身份用「站名」而不是站点 id——id 只在线路内唯一
    var pickedStationName by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    var state by remember { mutableStateOf<ScreenState>(ScreenState.Loading) }

    val permissions = remember {
        arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
    }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        granted = result.values.any { it }
    }

    LaunchedEffect(showSubscriptions) {
        if (!showSubscriptions && !granted && pickedStationName == null) launcher.launch(permissions)
    }

    LaunchedEffect(loadedLines, dataError, granted, retryKey, pickedStationName) {
        val lines = loadedLines
        val error = dataError
        if (error != null) {
            state = ScreenState.Failed(error)
            return@LaunchedEffect
        }
        if (lines.isEmpty()) {
            state = ScreenState.Loading
            return@LaunchedEffect
        }

        // 用户从搜索里显式选站：无需定位权限，直接用站名把各条线路的落点找出来
        val picked = pickedStationName
        if (picked != null) {
            state = buildReady(lines, picked, distanceMeters = 0.0, isManuallySelected = true)
            return@LaunchedEffect
        }

        if (!granted) {
            state = ScreenState.NeedPermission
            return@LaunchedEffect
        }

        state = ScreenState.Loading
        state = try {
            val coordinates = locationProvider.resolveBest()
            if (coordinates == null) {
                ScreenState.Failed("没能获取到当前位置，请确认已开启定位服务")
            } else {
                // 最近站在「所有已收录线路的全部站点」里找；换乘站取几何上最近的那个落点即可
                val allStations = lines.flatMap { it.line.stations }
                val nearest = NearestStationFinder(allStations)
                    .findNearest(coordinates.lat, coordinates.lng, limit = 1)
                    .firstOrNull()

                if (nearest == null) {
                    ScreenState.Failed("附近没有找到已收录的地铁站")
                } else {
                    buildReady(
                        lines = lines,
                        stationName = nearest.station.name,
                        distanceMeters = nearest.distanceMeters,
                        userLocation = coordinates,
                        isManuallySelected = false
                    )
                }
            }
        } catch (e: Exception) {
            ScreenState.Failed("定位失败，请稍后重试")
        }
    }

    val focusManager = LocalFocusManager.current
    // 搜索范围是全部已收录线路；同名换乘站只留一条，避免「复兴门」重复出现
    val searchResults = remember(loadedLines, query) {
        StationSearch.matchDistinctByName(loadedLines.flatMap { it.line.stations }, query)
    }
    // 用 rememberSaveable：系统深浅切换等配置变更会重建 Activity，
    // 普通 remember 会把用户直接弹出设置界面
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var showScheduleManagement by rememberSaveable { mutableStateOf(false) }
    var showLinePicker by rememberSaveable { mutableStateOf(false) }
    var showNetworkMap by rememberSaveable { mutableStateOf(false) }
    var showShortTurnManagement by rememberSaveable { mutableStateOf(false) }
    var settingsDockDestination by remember { mutableStateOf<DockDestination?>(null) }

    val onDockNavigate: (DockDestination) -> Unit = { destination ->
        headwayTarget = null
        editingSubscription = null
        when (destination) {
            DockDestination.HOME -> {
                showSettings = false
                showScheduleManagement = false
                showNetworkMap = false
                showLinePicker = false
                showSubscriptions = false
                showShortTurnManagement = false
                settingsDockDestination = null
            }
            DockDestination.SUBSCRIPTIONS -> {
                showSettings = false
                showScheduleManagement = false
                showNetworkMap = false
                showLinePicker = false
                showSubscriptions = true
                showShortTurnManagement = false
                settingsDockDestination = null
                focusManager.clearFocus()
            }
            DockDestination.SCHEDULE -> {
                showSettings = false
                showScheduleManagement = true
                showNetworkMap = false
                showLinePicker = false
                showShortTurnManagement = false
                settingsDockDestination = null
            }
            DockDestination.SHORT_TURN -> {
                showSettings = false
                showScheduleManagement = false
                showNetworkMap = false
                showLinePicker = false
                showSubscriptions = false
                showShortTurnManagement = true
                settingsDockDestination = null
            }
            DockDestination.LINE_PICKER -> showLinePicker = true
            DockDestination.NETWORK_MAP -> {
                showSettings = false
                showScheduleManagement = false
                showLinePicker = false
                showNetworkMap = true
                showShortTurnManagement = false
                settingsDockDestination = null
            }
        }
    }

    if (showLinePicker) {
        LineSelectionDialog(
            options = lineOptions,
            selectedDataFile = lineDataFile,
            onSelect = { dataFile ->
                if (dataFile != lineDataFile) {
                    pickedStationName = null
                    query = ""
                    onLineChange(dataFile)
                }
                showLinePicker = false
                showSettings = false
                showScheduleManagement = false
                showNetworkMap = false
                showSubscriptions = false
                settingsDockDestination = null
            },
            onDismiss = { showLinePicker = false }
        )
    }

    if (showNetworkMap) {
        NetworkMapScreen(
            selectedCityId = networkMapCityId,
            onCitySelect = onNetworkMapCityChange,
            onBack = { showNetworkMap = false },
            bottomBar = {
                MetroBottomDock(
                    selected = if (showLinePicker) DockDestination.LINE_PICKER else DockDestination.NETWORK_MAP,
                    onNavigate = onDockNavigate
                )
            }
        )
        return
    }

    if (showShortTurnManagement) {
        ShortTurnManagementScreen(
            lines = loadedLines,
            initialLineId = selectedResolved?.line?.lineId,
            onAdd = { lineId, startStationId, endStationId, departures ->
                repository.addShortTurn(lineId, startStationId, endStationId, departures)
                reloadKey += 1
            },
            onRemove = { lineId, index ->
                repository.removeShortTurn(lineId, index)
                reloadKey += 1
            },
            onBatchUpdate = { lineId, shortTurns ->
                repository.setShortTurns(lineId, shortTurns)
                reloadKey += 1
            },
            bottomBar = {
                MetroBottomDock(
                    selected = if (showLinePicker) DockDestination.LINE_PICKER else DockDestination.SHORT_TURN,
                    onNavigate = onDockNavigate
                )
            },
            onBack = { showShortTurnManagement = false }
        )
        return
    }
    if (showScheduleManagement) {
        ScheduleManagementScreen(
            lines = loadedLines,
            initialLineId = selectedResolved?.line?.lineId,
            onSave = { line, patternId, serviceType, trips ->
                repository.setServiceTrips(line, patternId, serviceType, trips)
                reloadKey += 1
            },
            onRestoreSystem = { lineId, patternId, serviceType ->
                repository.clearServiceDepartures(lineId, patternId, serviceType)
                reloadKey += 1
            },
            onRestoreAllSystem = { lineId ->
                repository.clearAllServiceSchedules(lineId)
                reloadKey += 1
            },
            bottomBar = {
                MetroBottomDock(
                    selected = if (showLinePicker) DockDestination.LINE_PICKER else DockDestination.SCHEDULE,
                    onNavigate = onDockNavigate
                )
            },
            onBack = {
                showScheduleManagement = false
                showSettings = true
                settingsDockDestination = null
            }
        )
        return
    }

    // 设置界面整屏覆盖：提前返回可让上面的线路/定位/搜索状态原样保留，
    // 返回主界面时不必重新定位
    if (showSettings) {
        SettingsScreen(
            subscriptionCount = subscriptions.size,
            onManageSubscriptions = {
                showSettings = false
                showSubscriptions = true
                settingsDockDestination = null
            },
            onManageSchedules = {
                showSettings = false
                showScheduleManagement = true
                settingsDockDestination = null
            },
            themeMode = themeMode,
            onThemeModeChange = onThemeModeChange,
            lineOptions = lineOptions,
            selectedLineDataFile = lineDataFile,
            onLineSelect = { dataFile ->
                if (dataFile != lineDataFile) {
                    // 换「我的线路」只调整分组顺序，但当前选站与搜索词属于旧上下文，一并清掉
                    pickedStationName = null
                    query = ""
                    onLineChange(dataFile)
                }
            },
            onAddCustomLine = { line ->
                onCustomLinesChange(customLines + line)
                onLineChange("custom:${line.lineId}")
                pickedStationName = null
                query = ""
            },
            onRemoveCustomLine = { lineId ->
                val removedKey = "custom:$lineId"
                onCustomLinesChange(customLines.filterNot { it.lineId == lineId })
                onSubscriptionsChange(subscriptions.filterNot { it.lineId == lineId })
                if (lineDataFile == removedKey) onLineChange(DEFAULT_LINE_DATA_FILE)
            },
            bottomBar = {
                MetroBottomDock(
                    selected = if (showLinePicker) DockDestination.LINE_PICKER else settingsDockDestination,
                    onNavigate = onDockNavigate
                )
            },
            onBack = {
                showSettings = false
                settingsDockDestination = null
            }
        )
        return
    }

    headwayTarget?.let { (lineId, item) ->
        HeadwayLearningDialog(item,
            loadedLines.firstOrNull { it.line.lineId == lineId }?.overrides?.headwaySessions.orEmpty(),
            onSave = { repository.saveHeadwaySession(lineId, it); reloadKey += 1 },
            onRemove = { repository.removeHeadwaySession(lineId, it); reloadKey += 1 },
            onDismiss = { headwayTarget = null })
    }
    editingSubscription?.let { initial ->
        SubscriptionDialog(initial, loadedLines,
            onDismiss = { editingSubscription = null },
            onSave = {
                onSubscriptionsChange(StationSubscriptions.upsert(subscriptions, it))
                editingSubscription = null
                showSubscriptions = true
                query = ""
                focusManager.clearFocus()
            })
    }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = { Text(if (showSubscriptions) "我的订阅" else "Metro Nearby") },
                    actions = {
                        if (!showSubscriptions && pickedStationName != null) {
                            TextButton(onClick = {
                                pickedStationName = null
                                query = ""
                                granted = locationProvider.hasPermission()
                                retryKey += 1
                                focusManager.clearFocus()
                            }) { Text("返回最近站") }
                        }
                        TextButton(onClick = {
                            settingsDockDestination = null
                            showSettings = true
                        }) {
                            SettingsGlyph()
                            Spacer(Modifier.width(5.dp))
                            Text("设置")
                        }
                    }
                )
                ArrivalModeSelector(arrivalDisplayMode, onArrivalDisplayModeChange)
                if (!showSubscriptions) StationSearchField(
                    query = query,
                    showClear = query.isNotEmpty() || pickedStationName != null,
                    onQueryChange = { query = it },
                    onClear = {
                        query = ""
                        pickedStationName = null
                        focusManager.clearFocus()
                    }
                )
            }
        },
        bottomBar = {
            MetroBottomDock(
                selected = if (showLinePicker) DockDestination.LINE_PICKER
                else if (showSubscriptions) DockDestination.SUBSCRIPTIONS else DockDestination.HOME,
                onNavigate = onDockNavigate
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when {
                showSubscriptions && dataError != null -> MessageView(dataError!!, "重试", { reloadKey += 1 })
                showSubscriptions && loadedLines.isEmpty() -> LoadingView()
                showSubscriptions -> SubscriptionBoard(subscriptions, loadedLines, arrivalDisplayMode,
                    onAdd = { showSubscriptions = false; query = "" },
                    onOpen = { pickedStationName = it; query = ""; showSubscriptions = false },
                    onEdit = { editingSubscription = it },
                    onRemove = { onSubscriptionsChange(StationSubscriptions.remove(subscriptions, it.stationName)) })
                // 搜索态优先于定位结果：有输入就直接给候选，避免用户以为要等定位
                searchResults.isNotEmpty() -> StationSearchResults(
                    stations = searchResults,
                    onSelect = { station ->
                        pickedStationName = station.name
                        query = ""
                        focusManager.clearFocus()
                    }
                )

                query.isNotBlank() && loadedLines.isNotEmpty() -> MessageView(
                    message = "没有找到「${query.trim()}」",
                    actionText = "清空搜索",
                    onAction = { query = "" }
                )

                else -> when (val current = state) {
                    ScreenState.Loading -> LoadingView()

                    ScreenState.NeedPermission -> MessageView(
                        message = "需要定位权限才能找到附近地铁站",
                        actionText = "授予定位权限",
                        onAction = { launcher.launch(permissions) },
                        illustrationRes = R.drawable.empty_location
                    )

                    is ScreenState.Failed -> MessageView(
                        message = current.message,
                        actionText = "重新定位",
                        onAction = {
                            granted = locationProvider.hasPermission()
                            retryKey += 1
                        },
                        illustrationRes = R.drawable.empty_location
                    )

                    is ScreenState.Ready -> ArrivalBoard(
                        onStartContinuous = { onLine, item -> headwayTarget = onLine.line.lineId to item },
                        ready = current,
                        arrivalDisplayMode = arrivalDisplayMode,
                        onRelocate = {
                            pickedStationName = null
                            granted = locationProvider.hasPermission()
                            retryKey += 1
                        },
                        subscriptionLabel = if (StationSubscriptions.find(subscriptions, current.stationName) == null) "订阅本站" else "编辑订阅",
                        onSubscribe = { editingSubscription = StationSubscriptions.find(subscriptions, current.stationName)
                            ?: StationSubscription(current.stationName) },
                        onRecordObservation = { onLine, observation ->
                            repository.recordObservation(onLine.line.lineId, observation)
                            reloadKey += 1
                        },
                        onRemoveObservation = { onLine, item, index ->
                            repository.removeObservation(
                                lineId = onLine.line.lineId,
                                stationId = onLine.stationId,
                                patternId = item.patternId,
                                index = index
                            )
                            reloadKey += 1
                        },
                        onClearObservations = { onLine, item ->
                            repository.clearObservations(
                                lineId = onLine.line.lineId,
                                stationId = onLine.stationId,
                                patternId = item.patternId
                            )
                            reloadKey += 1
                        }
                    )
                }
            }
        }
    }
}

/**
 * 由「当前站」构造可展示状态：找出它在各条已收录线路里的落点。
 *
 * [lines] 已把「我的线路」排在最前，匹配结果沿用同一顺序，于是用户关注的线路自然置顶。
 * [TransferStationResolver] 只做站名归一化匹配，站名在数据里对不上时（例如数据缺站）
 * 返回空列表，此时给出可读的失败态而不是空白页。
 */
private fun buildReady(
    lines: List<MetroRepository.ResolvedLine>,
    stationName: String,
    distanceMeters: Double,
    userLocation: UserLocation? = null,
    isManuallySelected: Boolean
): ScreenState {
    val overridesByLineId = lines.associate { it.line.lineId to it.overrides }
    val stationOnLines = TransferStationResolver
        .match(lines.map { it.line }, stationName)
        .map { matched ->
            StationOnLine(
                line = matched.line,
                overrides = overridesByLineId[matched.line.lineId],
                stationId = matched.stationId
            )
        }

    if (stationOnLines.isEmpty()) {
        return ScreenState.Failed("没有找到「$stationName」的站点数据")
    }

    return ScreenState.Ready(
        stationOnLines = stationOnLines,
        stationName = stationName,
        distanceMeters = distanceMeters,
        userLocation = userLocation,
        isManuallySelected = isManuallySelected
    )
}

@Composable
private fun LoadingView() {
    val motion = rememberInfiniteTransition(label = "地铁加载动画")
    val travel by motion.animateFloat(
        initialValue = -30f,
        targetValue = 30f,
        animationSpec = infiniteRepeatable(
            animation = tween(1_500, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "列车移动"
    )
    val bob by motion.animateFloat(
        initialValue = -2f,
        targetValue = 2f,
        animationSpec = infiniteRepeatable(
            animation = tween(650, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "列车轻摆"
    )
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier.width(188.dp).height(126.dp),
            contentAlignment = Alignment.BottomCenter
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val trackY = size.height - 10.dp.toPx()
                drawLine(
                    color = Color(0xFF77D7E8),
                    start = androidx.compose.ui.geometry.Offset(12.dp.toPx(), trackY),
                    end = androidx.compose.ui.geometry.Offset(size.width - 12.dp.toPx(), trackY),
                    strokeWidth = 4.dp.toPx(),
                    cap = androidx.compose.ui.graphics.StrokeCap.Round
                )
                listOf(.18f, .50f, .82f).forEach { x ->
                    drawCircle(
                        color = Color.White,
                        radius = 5.dp.toPx(),
                        center = androidx.compose.ui.geometry.Offset(size.width * x, trackY)
                    )
                    drawCircle(
                        color = Color(0xFF18659B),
                        radius = 5.dp.toPx(),
                        center = androidx.compose.ui.geometry.Offset(size.width * x, trackY),
                        style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.dp.toPx())
                    )
                }
            }
            Image(
                painter = painterResource(R.drawable.loading_train),
                contentDescription = "列车正在加载",
                modifier = Modifier.size(104.dp).offset(x = travel.dp, y = bob.dp)
            )
        }
        Spacer(Modifier.height(8.dp))
        Text("正在定位并加载数据…", style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun MessageView(
    message: String,
    actionText: String,
    onAction: () -> Unit,
    illustrationRes: Int? = null
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        illustrationRes?.let {
            Image(
                painter = painterResource(it),
                contentDescription = null,
                modifier = Modifier.size(176.dp)
            )
            Spacer(Modifier.height(8.dp))
        }
        Text(message, style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(16.dp))
        Button(onClick = onAction) { Text(actionText) }
    }
}

/**
 * 从线路主题色推导出的三个用色，全线路共用同一套规则。
 *
 * - [base]：徽标与卡片色带的底色 = 本线主题色
 * - [onBase]：底色之上的文字色，自动在黑白之间取可读的那个
 * - [emphasis]：首班车高亮文字色，浅色线路色会先被压暗到在浅底上可读
 */
private data class LineTokens(val base: Color, val onBase: Color, val emphasis: Color)

/**
 * 由线路数据里的标识色推导出 [LineTokens]；色值缺失或格式非法时整体退回 [fallback]。
 */
private fun lineTokensOf(lineColorHex: String?, fallback: Color): LineTokens {
    val parsed = LineVisuals.parseHexColor(lineColorHex) ?: return LineTokens(
        base = fallback,
        onBase = Color.White,
        emphasis = fallback
    )
    return LineTokens(
        base = Color(parsed.toArgb()),
        onBase = Color(LineVisuals.foregroundOn(parsed).toArgb()),
        emphasis = Color(LineVisuals.readableOnLightSurface(parsed).toArgb())
    )
}

/**
 * 一条线路在当前站的分组：自己的配色、徽标、方向列表与当天首末班车时刻。
 *
 * [serviceWindow] 为空表示该站当天没有任何可推算班次；它与"班次都开走了"是两回事，
 * 空态文案据此分档。
 */
private data class LineBoard(
    val stationOnLine: StationOnLine,
    val lineTokens: LineTokens,
    val lineBadge: String?,
    val directions: List<DirectionSchedule>,
    val serviceWindow: StationServiceWindow?
)

@Composable
private fun ArrivalBoard(
    onStartContinuous: (StationOnLine, ArrivalItem) -> Unit,
    ready: ScreenState.Ready,
    arrivalDisplayMode: ArrivalDisplayMode,
    onRelocate: () -> Unit,
    subscriptionLabel: String,
    onSubscribe: () -> Unit,
    onRecordObservation: (StationOnLine, ArrivalObservation) -> Unit,
    onRemoveObservation: (StationOnLine, ArrivalItem, Int) -> Unit,
    onClearObservations: (StationOnLine, ArrivalItem) -> Unit
) {
    var nowEpochMillis by remember { mutableStateOf(System.currentTimeMillis()) }

    LaunchedEffect(Unit) {
        while (true) {
            delay(REFRESH_INTERVAL_MILLIS)
            nowEpochMillis = System.currentTimeMillis()
        }
    }

    val nowCalendar = remember(nowEpochMillis) { Calendar.getInstance().apply { timeInMillis = nowEpochMillis } }
    val nowSeconds = ServiceTypeResolver.secondsOfDay(nowCalendar)
    val serviceType = ServiceTypeResolver.from(nowCalendar)
    val fallbackColor = MaterialTheme.colorScheme.primary

    // 每条线路各算各的方向——换乘站会有多条，非换乘站只有一条
    val boards = remember(ready.stationOnLines, nowSeconds, nowEpochMillis, serviceType, fallbackColor, arrivalDisplayMode) {
        ready.stationOnLines.map { onLine ->
            val estimator = ArrivalEstimator(onLine.line, onLine.overrides)
            LineBoard(
                stationOnLine = onLine,
                lineTokens = lineTokensOf(onLine.line.color, fallbackColor),
                lineBadge = LineVisuals.badgeText(onLine.line.lineName),
                directions = estimator.directionSchedules(
                    onLine.stationId,
                    serviceType,
                    nowSeconds,
                    currentEpochMillis = nowEpochMillis,
                    displayMode = arrivalDisplayMode
                ),
                serviceWindow = estimator.serviceWindow(onLine.stationId, serviceType)
            )
        }
    }

    // 只记「被收起的线路 id」，默认全部展开；换乘站卡片多时可逐条收起
    var collapsedLineIds by rememberSaveable { mutableStateOf(emptyList<String>()) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            StationHeader(
                stationName = ready.stationName,
                distanceMeters = ready.distanceMeters,
                isManuallySelected = ready.isManuallySelected,
                userLocation = ready.userLocation,
                nowEpochMillis = nowEpochMillis,
                onRelocate = onRelocate
            )
            TextButton(onClick = onSubscribe) { Text(subscriptionLabel) }
        }

        boards.forEach { board ->
            val lineId = board.stationOnLine.line.lineId
            val collapsed = lineId in collapsedLineIds

            item(key = "header-$lineId") {
                LineGroupHeader(
                    lineName = board.stationOnLine.line.lineName,
                    cityName = board.stationOnLine.line.cityName,
                    lineBadge = board.lineBadge,
                    lineTokens = board.lineTokens,
                    collapsed = collapsed,
                    onToggle = {
                        collapsedLineIds = if (collapsed) {
                            collapsedLineIds - lineId
                        } else {
                            collapsedLineIds + lineId
                        }
                    }
                )
            }

            if (collapsed) return@forEach

            if (board.directions.isEmpty()) {
                item(key = "empty-$lineId") {
                    Text(
                        text = emptyBoardMessage(board, nowSeconds),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                }
            } else {
                // key 带线路前缀：不同线路的 directionId 各自独立，直接相加会撞 key
                items(board.directions, key = { "$lineId:${it.directionId}" }) { direction ->
                    DirectionSection(
                        onStartContinuous = { onStartContinuous(board.stationOnLine, it) },
                        direction = direction,
                        nowSeconds = nowSeconds,
                        arrivalDisplayMode = arrivalDisplayMode,
                        lineTokens = board.lineTokens,
                        observations = board.stationOnLine.overrides
                            ?.arrivalObservations.orEmpty(),
                        onRecordObservation = { observation ->
                            onRecordObservation(board.stationOnLine, observation)
                        },
                        onRemoveObservation = { item, index ->
                            onRemoveObservation(board.stationOnLine, item, index)
                        },
                        onClearObservations = { item ->
                            onClearObservations(board.stationOnLine, item)
                        }
                    )
                }
            }
        }

        item { DataDisclaimer(ready.stationOnLines.map { it.line }) }
        item { Spacer(Modifier.height(16.dp)) }
    }
}

/**
 * 换乘站里的线路分组头：线路徽标 + 线路名，整行可点以折叠/展开该线路的到站卡片。
 *
 * 刻意用中性底色而非线路色带——下方每个方向卡片本身就是线路色带，
 * 分组头再用同色会与卡片糊成一片，层次反而消失。
 */
@Composable
private fun LineGroupHeader(
    lineName: String,
    cityName: String?,
    lineBadge: String?,
    lineTokens: LineTokens,
    collapsed: Boolean,
    onToggle: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (lineBadge != null) {
            LineBadge(text = lineBadge, color = lineTokens.base, contentColor = lineTokens.onBase)
            Spacer(Modifier.width(8.dp))
        }
        Text(
            text = lineName,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        if (!cityName.isNullOrBlank()) {
            Spacer(Modifier.width(8.dp))
            CityTag(cityName)
        }
        Spacer(Modifier.weight(1f))
        Text(
            text = if (collapsed) "展开" else "收起",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * 站名标题。
 *
 * 线路信息不放在这里——它由各线路分组头承载，因为换乘站不止一条线路。
 */
@Composable
private fun StationHeader(
    stationName: String,
    distanceMeters: Double,
    isManuallySelected: Boolean,
    userLocation: UserLocation?,
    nowEpochMillis: Long,
    onRelocate: () -> Unit
) {
    Column(modifier = Modifier.padding(top = 16.dp, bottom = 4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "最近地铁站",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stationName,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = if (isManuallySelected) "手动选择" else "距离约 ${formatDistance(distanceMeters)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Image(
                painter = painterResource(R.drawable.loading_train),
                contentDescription = null,
                modifier = Modifier.size(82.dp)
            )
        }
        if (userLocation != null) {
            Spacer(Modifier.height(12.dp))
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Image(
                        painter = painterResource(R.drawable.location_status),
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.width(82.dp).height(55.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "当前定位",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = UserLocationPolicy.coordinatesText(userLocation),
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            text = UserLocationPolicy.detailText(userLocation, nowEpochMillis),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    TextButton(onClick = onRelocate) { Text("重新定位") }
                }
            }
        }
    }
}

/**
 * 线路号徽标：线路色实心圆 + 对比色数字，跟在「1号线」这类线路名后面。
 * 数字颜色随底色自适应，浅色线路（如 13 号线黄）用深色字而非白字。
 */
@Composable
private fun LineBadge(text: String, color: Color, contentColor: Color) {
    Box(
        modifier = Modifier
            .defaultMinSize(minWidth = 20.dp, minHeight = 20.dp)
            .background(color = color, shape = CircleShape)
            .padding(horizontal = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = contentColor
        )
    }
}

/**
 * 顶部搜索框。默认留空时首屏展示定位推荐的最近站；
 * 一旦输入内容，即切换为「按站名 / 别名筛选已收录车站」的候选列表。
 *
 * 搜索范围目前是当前加载线路的站点集合；将来接入多线路时，
 * 这里应改为遍历 CityIndex 中所有线路的站点并去重。
 */
@Composable
private fun StationSearchField(
    query: String,
    showClear: Boolean,
    onQueryChange: (String) -> Unit,
    onClear: () -> Unit
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        placeholder = { Text("搜索地铁站") },
        trailingIcon = {
            if (showClear) {
                IconButton(onClick = onClear) {
                    Text("×", style = MaterialTheme.typography.titleMedium)
                }
            }
        },
        singleLine = true
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StationSearchResults(
    stations: List<Station>,
    onSelect: (Station) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            Text(
                text = "匹配到 ${stations.size} 个已收录车站",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        items(stations, key = { it.id }) { station ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                onClick = { onSelect(station) },
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = station.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    if (station.aliases.isNotEmpty()) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = station.aliases.joinToString(" / "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ArrivalModeSelector(
    selected: ArrivalDisplayMode,
    onSelect: (ArrivalDisplayMode) -> Unit
) {
    Row(
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("到站时间", style = MaterialTheme.typography.labelLarge)
        ArrivalDisplayMode.entries.forEach { mode ->
            FilterChip(
                selected = mode == selected,
                onClick = { onSelect(mode) },
                label = { Text(mode.displayName) }
            )
        }
    }
}

@Composable
private fun DirectionSection(
    onStartContinuous: (ArrivalItem) -> Unit,
    direction: DirectionSchedule,
    nowSeconds: Int,
    arrivalDisplayMode: ArrivalDisplayMode,
    lineTokens: LineTokens,
    observations: List<ArrivalObservation>,
    onRecordObservation: (ArrivalObservation) -> Unit,
    onRemoveObservation: (ArrivalItem, Int) -> Unit,
    onClearObservations: (ArrivalItem) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column {
            Text(
                text = direction.directionLabel,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = lineTokens.onBase,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(lineTokens.base)
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            )

            Column(modifier = Modifier.padding(16.dp)) {
                if (direction.arrivals.isEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Image(
                            painter = painterResource(R.drawable.empty_no_service),
                            contentDescription = null,
                            modifier = Modifier.size(84.dp)
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                            text = emptyDirectionMessage(direction, nowSeconds),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f)
                        )
                    }
                } else {
                    direction.arrivals.forEachIndexed { index, item ->
                        if (index > 0) Spacer(Modifier.height(10.dp))
                        ArrivalRow(
                            onStartContinuous = onStartContinuous,
                            item = item,
                            nowSeconds = nowSeconds,
                            arrivalDisplayMode = arrivalDisplayMode,
                            isFirst = index == 0,
                            emphasisColor = lineTokens.emphasis,
                            observations = observations,
                            onRecordObservation = onRecordObservation,
                            onRemoveObservation = onRemoveObservation,
                            onClearObservations = onClearObservations
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ArrivalRow(
    onStartContinuous: (ArrivalItem) -> Unit,
    item: ArrivalItem,
    nowSeconds: Int,
    arrivalDisplayMode: ArrivalDisplayMode,
    isFirst: Boolean,
    emphasisColor: Color,
    observations: List<ArrivalObservation>,
    onRecordObservation: (ArrivalObservation) -> Unit,
    onRemoveObservation: (ArrivalItem, Int) -> Unit,
    onClearObservations: (ArrivalItem) -> Unit
) {
    var showRecordDialog by remember { mutableStateOf(false) }
    val prediction = remember(item.stationId, item.patternId, item.arrivalSecondsOfDay) {
        CalibrationLearning.snapshot(item, Calendar.getInstance())
    }
    var recordingItem by remember { mutableStateOf(item) }
    var recordingPrediction by remember { mutableStateOf(prediction) }
    val presentation = item.present(arrivalDisplayMode, nowSeconds)

    // 最近一班用本线主题色高亮（浅色线路色已被压暗到可读），与后续班次拉开区分。
    val textColor = when {
        isFirst -> emphasisColor
        presentation.waitSeconds < 0 -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurface
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = formatWait(presentation.waitSeconds),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = textColor,
            modifier = Modifier.width(96.dp)
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = formatArrivalLine(presentation),
                style = MaterialTheme.typography.bodyMedium,
                color = textColor
            )
            formatArrivalModeNote(presentation)?.let { averageLine ->
                Spacer(Modifier.height(2.dp))
                Text(
                    text = averageLine,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary
                )
            }
            if (isFirst && presentation.usesUserCalibration) {
                val basis = if (item.userAverage?.scope == com.metronearby.domain.UserCalibrationScope.LEARNED_HEADWAY)
                    "间隔依据 ${item.userAverage.sampleCount} 个连续间隔" else "校准依据 ${item.userAverage?.sampleCount ?: 0} 条"
                Text("$basis · 记录中查看详情",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (item.isShortTurn) {
                    Tag(
                        text = "区间车 · 终点${item.terminalStationName}",
                        container = MaterialTheme.colorScheme.error
                    )
                    Spacer(Modifier.width(6.dp))
                }
                boundaryTag(item)?.let { boundary ->
                    Tag(text = boundary, container = MaterialTheme.colorScheme.tertiary)
                    Spacer(Modifier.width(6.dp))
                }
                if (item.source == ArrivalSource.EXACT) {
                    Tag(
                        text = "已核对",
                        container = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
        TextButton(onClick = {
            recordingItem = item
            recordingPrediction = prediction
            showRecordDialog = true
        }) {
            Text("记录")
        }
    }

    if (showRecordDialog) {
        // 列表在弹窗打开期间仍会刷新，记录与删除始终绑定用户打开的交路。
        val recordingObservations = observations.filter {
            it.stationId == recordingItem.stationId && it.patternId == recordingItem.patternId
        }
        ObservationDialog(
            onContinuous = { showRecordDialog = false; onStartContinuous(recordingItem) },
            defaultTime = TimeUtils.formatSecondsOfDay(nowSeconds),
            baseTime = TimeUtils.formatSecondsOfDay(recordingPrediction.baseSeconds),
            learningReport = CalibrationLearning.report(recordingObservations, System.currentTimeMillis()),
            observations = recordingObservations,
            onDismiss = { showRecordDialog = false },
            onConfirm = { observedTime, confirmed ->
                onRecordObservation(CalibrationLearning.record(recordingItem, recordingPrediction,
                    Calendar.getInstance(), observedTime, confirmed))
                showRecordDialog = false
            },
            onDelete = { index -> onRemoveObservation(recordingItem, index) },
            onClearAll = { onClearObservations(recordingItem) }
        )
    }
}

/**
 * 半透明底色 + 纯色文字的标签，保证在卡片底色上有足够对比度。
 */
@Composable
private fun Tag(text: String, container: Color) {
    Box(
        modifier = Modifier
            .background(
                color = container.copy(alpha = 0.16f),
                shape = RoundedCornerShape(4.dp)
            )
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = container
        )
    }
}

@Composable
private fun DataDisclaimer(lines: List<MetroLine>) {
    // 换乘站任一线路尚未完全核实，就提示仍含估算数据。
    val note = if (lines.any { it.needsReview }) {
        "时刻及间隔含估算数据，仍待完整核对"
    } else {
        "数据更新于 ${lines.mapNotNull { it.updatedAt }.maxOrNull() ?: "未知"}"
    }
    Text(
        text = "到站时间为推算结果，仅供参考 · $note",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp)
    )
}

private fun formatWait(seconds: Int): String = when {
    seconds < 0 -> "已到站"
    seconds <= DWELL_HIGHLIGHT_SECONDS -> "即将进站"
    seconds < 60 -> "$seconds 秒"
    seconds < 3600 -> "${seconds / 60} 分钟"
    else -> "${seconds / 3600} 小时 ${(seconds % 3600) / 60} 分"
}

private fun formatArrivalLine(presentation: ArrivalPresentation): String =
    if (presentation.waitSeconds < 0) {
        "${presentation.sourceLabel} ${TimeUtils.formatSecondsOfDay(presentation.arrivalSecondsOfDay)} 已到站"
    } else if (presentation.usesUserCalibration) {
        "${presentation.sourceLabel} ${TimeUtils.formatSecondsOfDay(presentation.arrivalSecondsOfDay)} 到站"
    } else {
        "预计 ${TimeUtils.formatSecondsOfDay(presentation.arrivalSecondsOfDay)} 到站"
    }

/**
 * 线路卡片的空态文案。
 *
 * 只有「当天排了班、但当前已过末班车」才叫收车，此时顺带告知首班时刻——
 * 这正是乘客此刻最想知道的事（还要等到几点）。数据里没有当天的班次
 * （[LineBoard.serviceWindow] 为空）时保持中性说法，不谎报"收车"。
 */
private fun emptyBoardMessage(board: LineBoard, nowSeconds: Int): String {
    val window = board.serviceWindow
    return if (window != null && window.isFinishedAt(nowSeconds)) {
        "今日已收车 · 首班 ${TimeUtils.formatSecondsOfDay(window.firstSecondsOfDay)}"
    } else {
        "当前时段没有可乘班次"
    }
}

private fun emptyDirectionMessage(direction: DirectionSchedule, nowSeconds: Int): String {
    val window = direction.serviceWindow
    return if (window != null && window.isFinishedAt(nowSeconds)) {
        "今日已收车 · 首班 ${TimeUtils.formatSecondsOfDay(window.firstSecondsOfDay)}"
    } else {
        "当前方向暂无可乘班次"
    }
}

/**
 * 首班 / 末班标志。当天只发一班时合并成一个标志，避免两个标签并排。
 */
private fun boundaryTag(item: ArrivalItem): String? = when {
    item.isFirstDeparture && item.isLastDeparture -> "首末班"
    item.isFirstDeparture -> "首班"
    item.isLastDeparture -> "末班"
    else -> null
}

private fun formatArrivalModeNote(presentation: ArrivalPresentation): String? =
    if (presentation.fellBackToSystem) "暂无用户校准，已使用系统预计" else null

/**
 * 录入并管理某班车实际到站时刻的弹窗。默认填当前时刻，正对"车到了顺手记一笔"的场景。
 *
 * 上半部分是录入（校验交给 [TimeUtils.parseClockTime]），下半部分列出该站该交路已录入的
 * 观测，支持逐条删除与一键清空；清空属不可逆的批量操作，先经二次确认。
 */
@Composable
private fun ObservationDialog(
    onContinuous: () -> Unit,
    defaultTime: String,
    baseTime: String,
    learningReport: String,
    observations: List<ArrivalObservation>,
    onDismiss: () -> Unit,
    onConfirm: (String?, Boolean) -> Unit,
    onDelete: (Int) -> Unit,
    onClearAll: () -> Unit
) {
    var text by remember { mutableStateOf(defaultTime) }
    var confirmingClear by remember { mutableStateOf(false) }
    var confirmed by remember { mutableStateOf(false) }
    val valid = remember(text) { TimeUtils.parseClockTime(text) != null }

    if (confirmingClear) {
        AlertDialog(
            onDismissRequest = { confirmingClear = false },
            title = { Text("清空全部观测？") },
            text = {
                Text("将删除该站该方向已录入的 ${observations.size} 条实测记录，删除后无法恢复。")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmingClear = false
                        onClearAll()
                    }
                ) {
                    Text("清空")
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmingClear = false }) {
                    Text("取消")
                }
            }
        )
        return
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("记录实际到站时间") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Image(
                    painter = painterResource(R.drawable.calibration_guide),
                    contentDescription = "从系统预计到实际到站记录，再得到更准确校准的示意图",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxWidth().height(118.dp)
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "记录今天实际到站的这班车。现场点击“车已到站”精确到秒；确认同班后，15 分钟内手动保存也可修正当前班次。",
                    style = MaterialTheme.typography.bodySmall
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = confirmed, onCheckedChange = { confirmed = it })
                    Text("确认是系统预计 $baseTime 的这班车", style = MaterialTheme.typography.bodySmall)
                }
                Text("不确定可不勾选，记录将降低权重。", style = MaterialTheme.typography.bodySmall)
                Button(onClick = { onConfirm(null, confirmed) }) { Text("车已到站") }
                TextButton(onClick = onContinuous) { Text("连续记录间隔") }
                Text(learningReport, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    isError = !valid,
                    label = { Text("手动补录今天的到站时刻") },
                    placeholder = { Text("例如 08:35") }
                )
                if (!valid) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "请按 24 小时制 HH:mm 填写",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }

                if (observations.isNotEmpty()) {
                    Spacer(Modifier.height(16.dp))
                    Text(
                        text = "已录入 ${observations.size} 条",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Column(
                        modifier = Modifier
                            .heightIn(max = 168.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        observations.forEachIndexed { index, observation ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = buildString {
                                        append(observation.observedTime)
                                        ObservationTimeBand.fromStorageKey(observation.timeBand)?.let { append(" · ${it.displayName}") }
                                        serviceTypeDisplayName(observation.serviceType)?.let { append(" · $it") }
                                    },
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.weight(1f)
                                )
                                TextButton(onClick = { onDelete(index) }) {
                                    Text("删除")
                                }
                            }
                        }
                    }
                    TextButton(onClick = { confirmingClear = true }) {
                        Text("清空全部")
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid,
                onClick = { onConfirm(text.trim(), confirmed) }
            ) {
                Text("保存")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}

private fun formatDistance(meters: Double): String =
    if (meters < 1000) "${meters.toInt()} 米" else "%.1f 公里".format(meters / 1000.0)

/** 首次启动（用户还没选过线路）时加载的线路；MainActivity 也用它作为默认值。 */
internal const val DEFAULT_LINE_DATA_FILE = "line_beijing_1.json"
private const val OVERRIDES_FILE = "user_overrides.json"
private const val REFRESH_INTERVAL_MILLIS = 1_000L
private const val DWELL_HIGHLIGHT_SECONDS = ArrivalEstimator.DEFAULT_DWELL_SECONDS
