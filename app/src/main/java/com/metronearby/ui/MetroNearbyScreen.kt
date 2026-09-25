package com.metronearby.ui

import android.Manifest
import androidx.activity.compose.BackHandler
import com.metronearby.domain.SavedJourney
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
import com.metronearby.domain.StationLookup
import com.metronearby.domain.OfflineRoutePlanner
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
import com.metronearby.location.NearbyStationCatalog
import com.metronearby.location.NearbyStationSummary
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
        val nearbyStations: List<NearbyStationSummary> = emptyList(),
        /** 站点由用户搜索选定，而非定位推荐的最近站 */
        val isManuallySelected: Boolean = false
    ) : ScreenState
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MetroNearbyScreen(
    journeys: List<SavedJourney> = emptyList(),
    onJourneysChange: (List<SavedJourney>) -> Unit = {},
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
    var showSubscriptions by rememberSaveable { mutableStateOf(false) }
    var editingSubscription by remember { mutableStateOf<StationSubscription?>(null) }
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
    var pickedStationKey by remember { mutableStateOf<String?>(null) }
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

    LaunchedEffect(loadedLines, dataError, granted, retryKey, pickedStationName, pickedStationKey) {
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
            state = buildReady(lines, picked, distanceMeters = 0.0, isManuallySelected = true, stationKey = pickedStationKey)
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
                val nearby = NearbyStationCatalog.find(
                    lines = lines.map { it.line },
                    lat = coordinates.lat,
                    lng = coordinates.lng,
                    limit = 5
                )
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
                        nearbyStations = nearby,
                        isManuallySelected = false,
                        stationKey = lines.firstOrNull { row -> row.line.stations.any { it === nearest.station } }?.let {
                            OfflineRoutePlanner.stationKey(it.line, nearest.station.name)
                        }
                    )
                }
            }
        } catch (e: Exception) {
            ScreenState.Failed("定位失败，请稍后重试")
        }
    }

    val focusManager = LocalFocusManager.current
    // 搜索按城市和站名合并换乘线路，跨城市同名站分别展示。
    val searchIndex = remember(loadedLines) {
        StationLookup.Index(OfflineRoutePlanner.stationChoices(loadedLines.map { it.line }))
    }
    val searchResults = remember(searchIndex, query) { searchIndex.search(query) }
    // 用 rememberSaveable：系统深浅切换等配置变更会重建 Activity，
    // 普通 remember 会把用户直接弹出设置界面
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var showNetworkMap by rememberSaveable { mutableStateOf(false) }
    var showRoutePlanner by rememberSaveable { mutableStateOf(false) }
    var showRadar by rememberSaveable { mutableStateOf(false) }
    var showFutureRoadmap by rememberSaveable { mutableStateOf(false) }
    var facilityStationKey by rememberSaveable { mutableStateOf<String?>(null) }
    var routeOriginKey by rememberSaveable { mutableStateOf<String?>(null) }
    var routeDestinationKey by rememberSaveable { mutableStateOf<String?>(null) }
    val onDockNavigate: (DockDestination) -> Unit = { destination ->
        editingSubscription = null
        facilityStationKey = null
        showSettings = false
        showFutureRoadmap = false
        showSubscriptions = destination == DockDestination.SUBSCRIPTIONS
        showNetworkMap = destination == DockDestination.NETWORK_MAP
        showRadar = destination == DockDestination.RADAR
        showRoutePlanner = destination == DockDestination.ROUTE
        routeOriginKey = null
        routeDestinationKey = null
        focusManager.clearFocus()
    }
    BackHandler(enabled = facilityStationKey != null || showSettings || showFutureRoadmap || showNetworkMap ||
        showRoutePlanner || showRadar || showSubscriptions) {
        if (facilityStationKey != null) facilityStationKey = null
        else if (showFutureRoadmap) { showFutureRoadmap = false; showSettings = true }
        else if (showRoutePlanner) {
            showRoutePlanner = false
            routeOriginKey = null
            routeDestinationKey = null
        }
        else onDockNavigate(DockDestination.HOME)
    }
    facilityStationKey?.let { key ->
        StationDetailsScreen(
            stationKey = key,
            lines = loadedLines.map { it.line },
            onBack = { facilityStationKey = null },
            bottomBar = { MetroBottomDock(null, onDockNavigate) }
        )
        return
    }
    if (showRadar) {
        StationRadarScreen(
            lines = loadedLines.map { it.line },
            onPlanFrom = { key ->
                routeOriginKey = key
                showRadar = false
                showRoutePlanner = true
            },
            onBack = { showRadar = false },
            bottomBar = { MetroBottomDock(DockDestination.RADAR, onDockNavigate) }
        )
        return
    }
    if (showRoutePlanner) {
        RoutePlannerScreen(
            lines = loadedLines.map { it.line },
            initialOriginName = (state as? ScreenState.Ready)?.stationName,
            initialOriginKey = routeOriginKey ?: (state as? ScreenState.Ready)?.let { ready ->
                ready.stationOnLines.firstOrNull()?.let { OfflineRoutePlanner.stationKey(it.line, ready.stationName) }
            },
            initialDestinationKey = routeDestinationKey,
            journeys = journeys,
            onJourneysChange = onJourneysChange,
            onBack = { showRoutePlanner = false; routeOriginKey = null; routeDestinationKey = null },
            bottomBar = { MetroBottomDock(DockDestination.ROUTE, onDockNavigate) }
        )
        return
    }
    if (showNetworkMap) {
        NetworkMapScreen(
            selectedCityId = networkMapCityId,
            onCitySelect = onNetworkMapCityChange,
            onBack = { showNetworkMap = false },
            bottomBar = { MetroBottomDock(DockDestination.NETWORK_MAP, onDockNavigate) }
        )
        return
    }
    if (showFutureRoadmap) {
        FutureRoadmapScreen(
            bottomBar = { MetroBottomDock(null, onDockNavigate) },
            onBack = { showFutureRoadmap = false; showSettings = true }
        )
        return
    }
    if (showSettings) {
        SettingsScreen(
            subscriptionCount = subscriptions.size,
            onManageSubscriptions = { showSettings = false; showSubscriptions = true },
            onOpenFutureRoadmap = { showSettings = false; showFutureRoadmap = true },
            themeMode = themeMode,
            onThemeModeChange = onThemeModeChange,
            lineOptions = lineOptions,
            selectedLineDataFile = lineDataFile,
            onLineSelect = { onLineChange(it) },
            onAddCustomLine = { line ->
                onCustomLinesChange(customLines + line)
                onLineChange("custom:${line.lineId}")
                pickedStationName = null; pickedStationKey = null
                query = ""
            },
            onRemoveCustomLine = { lineId ->
                onCustomLinesChange(customLines.filterNot { it.lineId == lineId })
                onSubscriptionsChange(subscriptions.filterNot { it.lineId == lineId })
                if (lineDataFile == "custom:$lineId") onLineChange(DEFAULT_LINE_DATA_FILE)
            },
            bottomBar = { MetroBottomDock(null, onDockNavigate) },
            onBack = { showSettings = false }
        )
        return
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
                    title = { Text(if (showSubscriptions) "我的收藏" else "Metro Nearby") },
                    actions = {
                        if (!showSubscriptions && pickedStationName != null) {
                            TextButton(onClick = {
                                pickedStationName = null; pickedStationKey = null
                                query = ""
                                granted = locationProvider.hasPermission()
                                retryKey += 1
                                focusManager.clearFocus()
                            }) { Text("返回最近站") }
                        }
                        TextButton(onClick = {
                            showSettings = true
                        }) {
                            SettingsGlyph()
                            Spacer(Modifier.width(5.dp))
                            Text("设置")
                        }
                    }
                )
                if (!showSubscriptions) StationSearchField(
                    query = query,
                    showClear = query.isNotEmpty() || pickedStationName != null,
                    onQueryChange = { query = it },
                    onClear = {
                        query = ""
                        pickedStationName = null; pickedStationKey = null
                        focusManager.clearFocus()
                    }
                )
            }
        },
        bottomBar = {
            MetroBottomDock(
                selected = if (showSubscriptions) DockDestination.SUBSCRIPTIONS else DockDestination.HOME,
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
                showSubscriptions -> SubscriptionBoard(subscriptions, loadedLines,
                    onDetails = { facilityStationKey = it },
                    onPlan = { origin, destination ->
                        routeOriginKey = origin
                        routeDestinationKey = destination
                        showRoutePlanner = true
                    },
                    onAdd = { showSubscriptions = false; query = "" },
                    onOpen = { name ->
                        pickedStationName = name
                        pickedStationKey = com.metronearby.domain.StationSubscriptions.find(subscriptions, name)?.let {
                            com.metronearby.domain.SubscriptionPreferences.originKey(it, loadedLines.map { row -> row.line })
                        }
                        query = ""; showSubscriptions = false
                    },
                    onEdit = { editingSubscription = it },
                    onRemove = { onSubscriptionsChange(StationSubscriptions.remove(subscriptions, it.stationName)) })
                // 搜索态优先于定位结果：有输入就直接给候选，避免用户以为要等定位
                searchResults.isNotEmpty() -> StationSearchResults(
                    stations = searchResults,
                    onSelect = { station ->
                        pickedStationName = station.stationName
                        pickedStationKey = station.key
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

                    is ScreenState.Ready -> {
                        val subscriptionLabel =
                            if (StationSubscriptions.find(subscriptions, current.stationName) == null) {
                                "收藏本站"
                            } else {
                                "编辑收藏"
                            }
                        val relocate = {
                            pickedStationName = null; pickedStationKey = null
                            granted = locationProvider.hasPermission()
                            retryKey += 1
                        }
                        val subscribe = {
                            editingSubscription = StationSubscriptions.find(subscriptions, current.stationName)
                                ?: StationSubscription(current.stationName)
                        }
                            StationOverviewBoard(
                                lineColors = loadedLines.associate { it.line.lineId to it.line.color },
                                ready = current,
                                subscriptionLabel = subscriptionLabel,
                                onSubscribe = subscribe,
                                onRelocate = relocate,
                                onStationSelect = { nearby ->
                                    pickedStationName = nearby.stationName
                                    pickedStationKey = loadedLines.firstOrNull { it.line.lineId in nearby.lineIds }?.let {
                                        OfflineRoutePlanner.stationKey(it.line, nearby.stationName)
                                    }
                                    query = ""
                                },
                                onPlanRoute = {
                                    routeOriginKey = current.stationOnLines.firstOrNull()?.let {
                                        OfflineRoutePlanner.stationKey(it.line, current.stationName)
                                    }
                                    showRoutePlanner = true
                                },
                                onStationDetails = {
                                    current.stationOnLines.firstOrNull()?.let {
                                        facilityStationKey = com.metronearby.domain.OfflineRoutePlanner.stationKey(
                                            it.line, current.stationName)
                                    }
                                }
                            )
                    }                }
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
    nearbyStations: List<NearbyStationSummary> = emptyList(),
    isManuallySelected: Boolean,
    stationKey: String? = null
): ScreenState {
    val overridesByLineId = lines.associate { it.line.lineId to it.overrides }
    val stationOnLines = TransferStationResolver
        .match(lines.map { it.line }, stationName)
        .filter { stationKey == null || OfflineRoutePlanner.stationKey(it.line, stationName) == stationKey }
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
        nearbyStations = nearbyStations,
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
@Composable
private fun StationOverviewBoard(
    lineColors: Map<String, String?>,
    ready: ScreenState.Ready,
    subscriptionLabel: String,
    onSubscribe: () -> Unit,
    onRelocate: () -> Unit,
    onStationSelect: (NearbyStationSummary) -> Unit,
    onPlanRoute: () -> Unit,
    onStationDetails: () -> Unit
) {
    var nowEpochMillis by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000L)
            nowEpochMillis = System.currentTimeMillis()
        }
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
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
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onPlanRoute) { Text("从本站规划路线") }
                TextButton(onClick = onSubscribe) { Text(subscriptionLabel) }
            }
            OutlinedStationDetailsButton(onStationDetails)
        }
        if (ready.nearbyStations.isNotEmpty() && !ready.isManuallySelected) {
            item {
                Text("附近车站", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    "按站点中心的直线距离排序，不代表实际步行距离。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            items(ready.nearbyStations, key = { it.cityName + ":" + it.stationName }) { nearby ->
                Card(
                    modifier = Modifier.fillMaxWidth().clickable { onStationSelect(nearby) },
                    colors = CardDefaults.cardColors(
                        containerColor = if (nearby.stationName == ready.stationName) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        }
                    )
                ) {
                    LineColorStrip(nearby.lineIds.map { lineColors[it] })
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(nearby.stationName, fontWeight = FontWeight.SemiBold)
                            Text(
                                nearby.lineNames.joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Text(formatDistance(nearby.distanceMeters), color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
        item {
            Text("本站线路", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }
        items(ready.stationOnLines, key = { it.line.lineId }) { onLine ->
            val tokens = lineTokensOf(onLine.line.color, MaterialTheme.colorScheme.primary)
            Card(modifier = Modifier.fillMaxWidth()) {
                LineColorStrip(listOf(onLine.line.color))
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    LineVisuals.badgeText(onLine.line.lineName)?.let {
                        LineBadge(it, tokens.base, tokens.onBase)
                        Spacer(Modifier.width(10.dp))
                    }
                    Column(Modifier.weight(1f)) {
                        Text(onLine.line.lineName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text(
                            "${onLine.line.cityName ?: "未设置城市"} · 可从本站换乘或开始规划",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
        item { Spacer(Modifier.height(12.dp)) }
    }
}
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
        placeholder = { Text("站名 / 拼音 / 首字母 / 线路") },
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
    stations: List<StationLookup.Hit>,
    onSelect: (OfflineRoutePlanner.StationChoice) -> Unit
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
        items(stations, key = { it.station.key }) { hit ->
            val station = hit.station
            Card(
                modifier = Modifier.fillMaxWidth(),
                onClick = { onSelect(station) },
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
            ) {
                LineColorStrip(station.lineColors)
                Column(modifier = Modifier.padding(16.dp)) {
                    if (hit.suggestion) Text("可能想找 · 请确认", color = MaterialTheme.colorScheme.primary)
                    Text(
                        text = station.stationName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text("${station.cityName} · ${station.lineNames.joinToString(" / ")}",
                        style = MaterialTheme.typography.bodySmall)
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

private fun formatDistance(meters: Double): String =
    if (meters < 1000) "${meters.toInt()} 米" else "%.1f 公里".format(meters / 1000.0)

/** 首次启动（用户还没选过线路）时加载的线路；MainActivity 也用它作为默认值。 */
internal const val DEFAULT_LINE_DATA_FILE = "line_beijing_1.json"
private const val OVERRIDES_FILE = "user_overrides.json"
private const val REFRESH_INTERVAL_MILLIS = 1_000L

@Composable
private fun OutlinedStationDetailsButton(onClick: () -> Unit) {
    androidx.compose.material3.OutlinedButton(
        onClick = onClick, modifier = Modifier.fillMaxWidth()
    ) { Text("出入口与设施 · 我的车站备注") }
}
