package com.metronearby

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.metronearby.data.source.SharedPreferencesSettingsStore
import com.metronearby.ui.DEFAULT_LINE_DATA_FILE
import com.metronearby.ui.MetroNearbyScreen
import com.metronearby.ui.theme.MetroNearbyTheme
import com.metronearby.domain.ArrivalDisplayMode
import com.metronearby.domain.NetworkMapCatalog

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // 设置存储只依赖 ApplicationContext，放在 setContent 外创建，避免重组时重复构造
        val settingsStore = SharedPreferencesSettingsStore(this)

        setContent {
            // 主题模式提到根部持有：改动后直接驱动 MetroNearbyTheme 重建
            var themeMode by remember { mutableStateOf(settingsStore.loadThemeMode()) }
            var subscriptions by remember { mutableStateOf(settingsStore.loadSubscriptions()) }
            var arrivalDisplayMode by remember { mutableStateOf(settingsStore.loadArrivalDisplayMode()) }
            var showArrivalEstimates by remember { mutableStateOf(settingsStore.loadShowArrivalEstimates()) }
            var customLines by remember { mutableStateOf(settingsStore.loadCustomLines()) }
            var networkMapCityId by remember {
                mutableStateOf(settingsStore.loadNetworkMapCityId() ?: NetworkMapCatalog.DEFAULT_CITY_ID)
            }

            // 当前线路同样提到根部：切换后整棵界面按新线路重新加载数据。
            // 从未选过线路时回落到默认线路（默认值是界面层的约定，存储层不关心）
            var lineDataFile by remember {
                mutableStateOf(settingsStore.loadLineDataFile() ?: DEFAULT_LINE_DATA_FILE)
            }

            MetroNearbyTheme(darkTheme = themeMode.resolveDarkTheme(isSystemInDarkTheme())) {
                MetroNearbyScreen(
                    arrivalDisplayMode = arrivalDisplayMode,
                    onArrivalDisplayModeChange = { mode ->
                        arrivalDisplayMode = mode
                        settingsStore.saveArrivalDisplayMode(mode)
                    },
                    showArrivalEstimates = showArrivalEstimates,
                    onShowArrivalEstimatesChange = { show ->
                        showArrivalEstimates = show
                        settingsStore.saveShowArrivalEstimates(show)
                    },                    subscriptions = subscriptions,
                    onSubscriptionsChange = { subscriptions = it; settingsStore.saveSubscriptions(it) },
                    customLines = customLines,
                    onCustomLinesChange = { lines ->
                        customLines = lines
                        settingsStore.saveCustomLines(lines)
                    },
                    networkMapCityId = networkMapCityId,
                    onNetworkMapCityChange = { cityId ->
                        networkMapCityId = cityId
                        settingsStore.saveNetworkMapCityId(cityId)
                    },
                    lineDataFile = lineDataFile,
                    onLineChange = { dataFile ->
                        lineDataFile = dataFile
                        settingsStore.saveLineDataFile(dataFile)
                    },
                    themeMode = themeMode,
                    onThemeModeChange = { mode ->
                        themeMode = mode
                        settingsStore.saveThemeMode(mode)
                    }
                )
            }
        }
    }
}
