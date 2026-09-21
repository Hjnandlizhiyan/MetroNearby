# MetroNearby 工程交接与协作约定

本文给**后续参与本工程的 AI / 开发者**。命令均假定工作目录为仓库根目录，Windows + PowerShell 5.1。
凡是能从代码/脚本读出来的细节，本文只给指针，不重复抄写。

---

## 1. 项目概览

Android 离线地铁位置与换乘 App：打开即定位 → 展示最近及附近车站 → 用静态线网规划少换乘或少经过站路线。

- 包名 `com.metronearby`，单模块 `:app`，Kotlin + Jetpack Compose(Material3) + kotlinx.serialization。
- **全离线**：没有网络层、没有 `INTERNET` 权限、没有后端。没有 ViewModel、没有 DataStore、没有 material-icons 依赖。
- 产品默认主线是附近车站、常用站和离线路线；到站推算降为默认关闭的**实验功能**。开启后 UI 仍必须显式写「预计」，不得伪装成实时。
- 当前覆盖北京官网线网中的全部 28 条线路（`app/src/main/assets/metro/line_beijing_*.json`）。每个线路文件带 `cityId=beijing`、`cityName=北京`，界面显示城市标签；新增 16 条线路的数据范围见 `docs/beijing-lines-completion.md`。

已完成的主要能力（均已在模拟器实测通过）：

| 能力 | 关键落点 |
| --- | --- |
| 最近站 + 多个附近备选站（合并换乘站） | `location/NearbyStationCatalog.kt`、`ui/MetroNearbyScreen.kt` |
| 离线路线规划（少换乘 / 少经过站、跨城市隔离） | `domain/OfflineRoutePlanner.kt`、`ui/RoutePlannerScreen.kt`、`docs/offline-route-planning.md` |
| 预计班次实验开关（默认关闭） | `data/source/SettingsStore.kt`、`ui/SettingsScreen.kt` |
| 最近站 + 同站多线路双方向倒计时 | `domain/ArrivalEstimator.kt`、`ui/MetroNearbyScreen.kt` |
| 换乘站多线路合并展示、按线路折叠 | `domain/TransferStationResolver.kt` |
| 停站时间窗（已到站仍保留片刻） | `ArrivalEstimator.dwellSeconds`，`waitSeconds` 允许为负 |
| 环线（2/10 号线）终点站方向不被误过滤 | `ArrivalEstimator.isRingLine`（靠 `segments` 的「末站→首站」闭合段判定） |
| 线路主题色 + 自适应前景色 | `domain/LineVisuals.kt`，权威色在 `city_beijing.json` 的 `LineRef.color` |
| 用户分时段校准 + 两小时到站锚点 | `domain/UserCalibration.kt`、`data/model/UserOverrides.kt#ArrivalObservation` |
| 系统预计 / 用户校准口径切换（全局记忆、无样本回退） | `domain/ArrivalDisplayMode.kt`、`data/source/SettingsStore.kt` |
| 用户自填区间车 | `domain/ShortTurnPlanner.kt`、`domain/ShortTurnBatchEditor.kt` + `ui/ShortTurnManagementScreen.kt` 独立页面，规则见 `docs/short-turn-management.md` |
| 分线路/交路/日型管理总班次数、同班沿途站修正、批量平移/删除与防超车 | `domain/DepartureSchedulePlanner.kt`、`domain/TripStationPlanner.kt`、`domain/ScheduleBatchEditor.kt`、`ui/ScheduleManagementScreen.kt`、`docs/departure-schedule-management.md` |
| 首班 / 末班 / 首末班标志、收车空态 | `ArrivalEstimator.serviceWindow()`、`StationServiceWindow.isFinishedAt` |
| 深/浅/跟随系统主题、我的线路按城市折叠与置顶 | `ui/SettingsScreen.kt`、`ui/MetroBottomDock.kt`、`data/source/SettingsStore.kt` |
| 用户自定义线路（城市/站序/坐标/双方向）并复用班次表管理 | `domain/CustomLineBuilder.kt`、`ui/CustomLineDialog.kt`，规则见 `docs/custom-lines.md` |
| 线网图按城市选择并记忆 | `domain/NetworkMapCatalog.kt`、`ui/NetworkMapScreen.kt`，扩展方法见 `docs/network-map-cities.md` |
| 吉祥物、空状态、定位/校准引导、订阅卡背景与加载动画 | `res/drawable-nodpi/`、`ui/SettingsScreen.kt`、`ui/MetroNearbyScreen.kt`、`ui/SubscriptionComponents.kt`、`docs/visual-assets.md` |
| 避让系统手势区的悬浮工具栏 + 离线高清线网图 | `ui/MetroBottomDock.kt`、`ui/NetworkMapScreen.kt`、`docs/network-map.md` |
| 主界面搜索任意「有数据的站」 | `domain/StationSearch.kt`、`ui/MetroNearbyScreen.kt` |
| 用户观测的单条删除 / 清空全部（二次确认） | `ui/MetroNearbyScreen.kt`、`data/source/OverrideStore.kt` |

---

## 2. 环境与工具链位置（本机实测，换机器需改）

| 用途 | 路径 |
| --- | --- |
| JDK（Gradle 用） | `D:\Android\Android Studio\jbr`（Android Studio 自带 JBR 25；本机**无**独立系统 JDK） |
| Android SDK | `C:\Users\ASUS\AppData\Local\Android\Sdk`（记录在 `local.properties` 的 `sdk.dir`） |
| adb | `%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe`（本机即 `C:\Users\ASUS\AppData\Local\...`） |
| 目标模拟器 | `emulator-5554`，镜像 `sdk_gphone16k_x86_64`（Android 16KB 页实验镜像） |

模拟器的两条硬约束（踩过坑，别再试探）：

- `ro.debuggable=0`、镜像内**无 `su`** → `adb root` 与 `adb shell date` 改系统时间都不可用。需要 root 权限读应用私有数据时用 **`run-as com.metronearby`**（APK 是 debug 构建，`run-as` 可用）。
- 时区**已修正为 `Asia/Shanghai` 并且一直生效**（`adb shell date` 返回 CST，与北京时间一致）。不要再假定它是 GMT，也不必每次重设。

---

## 3. 构建 / 测试 / 装机命令

```powershell
# 跑 Gradle 前必须先指 JDK（否则找不到 java）
$env:JAVA_HOME='D:\Android\Android Studio\jbr'

.\gradlew.bat :app:testDebugUnitTest --rerun --console=plain   # 单元测试
.\gradlew.bat :app:jacocoUnitTestReport                        # 覆盖率（逻辑层口径）
.\gradlew.bat :app:compileDebugKotlin                          # 只编译
.\gradlew.bat :app:assembleDebug                               # 出 APK
```

产物与报告位置：

| 内容 | 路径 |
| --- | --- |
| debug APK | `app\build\outputs\apk\debug\app-debug.apk` |
| 单测结果（含用例数） | `app\build\test-results\testDebugUnitTest\*.xml` |
| 覆盖率 HTML | `app\build\reports\jacoco\jacocoUnitTestReport\html\index.html` |

统计当前用例总数（PowerShell 读这些 XML 要用 `[System.IO.File]::ReadAllText` + 正则，直接 `[xml]` 转换会因编码报错）：

```powershell
$dir='app\build\test-results\testDebugUnitTest'
Get-ChildItem $dir -Filter *.xml | ForEach-Object {
  $raw=[System.IO.File]::ReadAllText($_.FullName)
  $tag=[regex]::Match($raw,'(?s)<testsuite\s[^>]*?>').Value
  "{0,-48} {1}" -f [regex]::Match($tag,'name="([^"]*)"').Groups[1].Value,
                   [regex]::Match($tag,'tests="(\d+)"').Groups[1].Value
}
```

**测试基线：410 个用例全绿（0 failures / 0 errors，2026-09-21 产品方向调整与离线路线规划后）。** 任何改动后该数字只能升、不能有红。

用户观测支持秒级现场记录、明确班次、历史加权学习与两小时衰减锚点融合，并用到站前冻结的预测评估误差；仅有旧版数据时兼容原分时段平均。规则和验证条件见 `docs/user-calibration.md`、`domain/CalibrationLearning.kt`。用户可选择“系统预计”或“用户校准”，后者无适用样本时明确回退到系统预计。不能用拟合误差宣称真实准确率。

常用站、通勤方向与订阅的确定口径、入口和持久化说明见 `docs/station-subscriptions.md`。订阅即关注，不含后台提醒；每站一组线路/方向偏好，主页集中查看，保持离线。

连续到站间隔学习见 `docs/headway-learning.md`、`domain/HeadwayLearning.kt`。必须确认无漏车，至少三个现场到站点；学习组按线路保存到 `headwaySessions`，不能从零散观测猜测相邻班次。下一班预测提前冻结后才能算验证误差。

预测修正、14 号线官方核对范围和后续准确度问题见 `docs/prediction-accuracy.md`。新增 4 / 5 / 6 / 7 / 8 号线的来源见 `docs/beijing-lines-expansion.md`；3 / 9 号线见 `docs/beijing-lines-3-9.md`；13 号线见 `docs/beijing-line-13.md`；其余 16 条线路及城市标签见 `docs/beijing-lines-completion.md`。13 号线已用官网逐站全程末班校准运行偏移；本次 16 条线路只核实官方站序、坐标和端点首末班，逐段运行时间、沿途时间与全天间隔仍为估算。勿将整条线路标记为已完全核实。

装机与启动：

```powershell
$adb="$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
& $adb -s emulator-5554 install -r app\build\outputs\apk\debug\app-debug.apk
& $adb -s emulator-5554 shell am start -n com.metronearby/.MainActivity
```

---

## 4. 代码结构与分层约定（必须遵守）

```
app/src/main/java/com/metronearby/
  MainActivity.kt              入口
  data/                        MetroRepository（合并内置数据与用户修正）、MetroJson（解析）
    model/MetroModels.kt       MetroLine / Pattern / Service / HeadwayRule / Segment ...
    model/UserOverrides.kt     用户修正的落盘模型
    source/                    MetroDataSource(接口)、AssetMetroDataSource、OverrideStore、SettingsStore
  domain/                      全部可测纯逻辑（无 Android 依赖，可跑纯 JVM 单测）
    ArrivalEstimator.kt        推算核心：班次生成、方向分组、首末班、收车窗口
    ShortTurnPlanner.kt        用户区间车 → pattern 的校验与折算
    TimeUtils / LineVisuals / StationSearch / TransferStationResolver / ServiceTypeResolver / ThemeMode
  geo/                         GeoUtils（纯逻辑：坐标距离/坐标系统）
  location/                    NearestStationFinder（纯逻辑）、AndroidLocationProvider（胶水）
  ui/                          Compose：MetroNearbyScreen、SettingsScreen、theme/（只做编排，不写业务逻辑）
```

硬性约定：

1. **可测逻辑一律下沉 `domain/`**，写成纯 JVM 可跑的代码并配单测；Compose 文件里不写业务逻辑。
2. **JSON 解析必须能在纯 JVM 跑**：禁用 Android 的 `org.json`，统一走 kotlinx.serialization。
3. **少加依赖**：缺能力优先零依赖/原生实现。持久化用 SharedPreferences（`SettingsStore.kt`），不要引 DataStore；不要引 ViewModel/material-icons。
4. **保留 JaCoCo**：不得以「精简构建 / 加快启动」为由移除。
5. 覆盖率统计口径刻意排除 UI 与胶水类（见 `app/build.gradle.kts` 的 `unitTestCoverageExcludes`），不要往口径里塞业务类。
6. 单测风格：**一个用例只验一件事**、命名直白，不要绕。
7. UI 文案用中文；模拟器字体缺 `✕`(U+2715)，图标字符用 `×`(U+00D7，Latin-1 乘号)。
8. **设置页是长期承载入口**：后续配置项会持续加进去，必须保持「可扩展的独立界面」形态，不要因当前项少就简化或改成弹窗。

---

## 5. 数据模型与优先级

内置线路数据**永远只读**；用户改动单独落盘合并，二者不混写。

- 线路数据：`app/src/main/assets/metro/line_beijing_<n>.json`
  站点 id 约定 `<lineId>_<NN>`；环线在 `segments` 末尾多一条「末站 → 首站」闭合段。
- 城市索引：`app/src/main/assets/metro/city_beijing.json`
  登记的线路名与**主题色**（`LineRef.color`，UI 的权威来源）；`dataFile` 为空表示该线只有名称+主题色、暂无站点数据，App 不得尝试加载。
- 用户修正：`filesDir/user_overrides.json`（设备上 `/data/data/com.metronearby/files/user_overrides.json`）
  字段：`stationCoordFix` / `segmentRunSecondsFix` / `headwayFix` / `exactDepartures` / `serviceDepartures` / `serviceTrips` / `arrivalObservations` / `shortTurns` / `patternEnabled`。
  当前线路修正保存在顶层；其他线路独立保存在 `otherLines`，内部不嵌套。仓库按 `lineId` 读取，跨线路录入不会覆盖原记录。
- 用户自定义线路：`SharedPreferences(metro_settings)` 的 `custom_lines` JSON 列表；创建规则见 `docs/custom-lines.md`。选中项用 `custom:<lineId>` 标识，加载时不得当成 assets 文件名读取。

生效优先级（高 → 低）：

1. `serviceTrips`（稳定班次编号、起点逐班表与中间站锚点）
2. `serviceDepartures`（兼容的用户起点逐班表）
3. `exactDepartures`（用户）
4. `exactDepartures`（内置）
5. `headwayFix` + `stationServiceTimes` / `segments` 推算

两个反直觉但重要的点：

- **`stationServiceTimes` 优先于 `segments`**：调站间时长必须同步重算前者，否则改了不生效。
- **用户自填的区间车会被追加进 `line.patterns`**（`ShortTurnPlanner`），因此自动获得方向混排、终点标签、首末班标志、`serviceWindow` 汇总等全部能力——不需要为它写任何特殊分支。
- 一期**刻意不做**「区间车与全程车时刻去重」：两条交路各算各的，共线段可能显示成两班。这是已知取舍，不是 bug。

---

## 6. 新增线路（数据从哪来）

数据源是 **OpenStreetMap Overpass API**，抓取脚本与完整 SOP 在 **`tools/line-pipeline/README.md`**（四条命令 + 两处人工决策点），照着做即可，不要在本文里另起一套。

要点（细节见该 README）：

- 站序**必须**取自 OSM relation 成员，按名称/范围过滤会捞进邻线站与换乘重名节点。
- `overpass-api.de` 是唯一确认能服务北京数据的公共实例；请求必须带 `Accept: application/json` + `User-Agent`（否则 406），限流约 2 次/分钟。
- 脚本产出的时刻表全是**占位估算**（`needsReview=true`），需人工替换为官方值。
- 写 JSON 一律 `[System.IO.File]::WriteAllText(... UTF8Encoding($false))`；**`Out-File -Encoding UTF8` 会写 BOM 导致解析失败**。
- 线路主题色取自中文维基百科「北京地铁」条目的「颜色↔线路名」映射，最后落到 `city_beijing.json`。

---

## 7. 模拟器验证工作流

App 内调试选站入口已按产品要求移除。界面验证优先使用主界面搜索选站；必须验证真实定位链路时再用外部注入。

1. **切站**：展示层验证直接用主界面“搜索地铁站”选择任意已收录站点。验证真实定位链路时使用 `tools/emulator-location/set-station.ps1 <站名|站id>`（从线路 JSON 读取站点，不硬编码）。注入后用 `dumpsys location` 确认 gps 坐标确实变化；若镜像忽略 console 注入，则只能验证搜索选站路径，并在交付说明中记录定位链路未实测。
2. **读界面**：`adb shell uiautomator dump /sdcard/ui.xml` + `adb pull`，再解析。要点：
   - XML 是 UTF-8，PowerShell 必须 `[System.IO.File]::ReadAllText($path)`；用默认 `Get-Content` 会乱码误判成「界面上没有文字」。
   - Compose 的 `Text` 是 `clickable="false"` 叶子节点，**不能按它定位点击点**；控件坐标取 `clickable="true"` 节点的 `bounds`。
   - `enabled` 属性对 Compose 不可靠，判断按钮是否禁用要**看行为**（点了弹窗不关/不落盘才是禁用）。
3. **点击**：瞬时 `input tap` 对 Compose（尤其 `FilterChip`）**无效**，必须用
   `input motionevent DOWN <x> <y>` → 停 ~150ms → `input motionevent UP <x> <y>`。
4. **输入文字**（两条实测结论，与旧笔记不同，以此为准）：
   - `adb shell input text` 注入的是**全角标点**（`20:00` 会变成 `20：00`，导致 App 的时刻校验不通过）→ **含半角标点的文本必须走 ADBKeyboard**：
     ```powershell
     & $adb -s emulator-5554 shell ime set com.android.adbkeyboard/.AdbIME
     & $adb -s emulator-5554 shell am broadcast -a ADB_INPUT_TEXT --es msg "20:00"
     # 多行输入：先 input keyevent 66 换行，再发下一条广播
     ```
   - `ADB_INPUT_TEXT` **可以直接注入中文**（`--es msg "朝阳门"` 实测上屏）。唯一必要条件是 ADBKeyboard 是**当前生效的 IME**（会被 Gboard 抢回，用前重设一次即可）。
   - 用完切回 Gboard：`ime set com.google.android.inputmethod.latin/com.android.inputmethod.latin.LatinIME`。
   - 仓库里的 `tools/emulator-input/type-text.ps1` 仍可用（它走 `ADB_INPUT_B64`），但 B64 已非必须。
5. **验证依赖「当前时刻」的 UI**（首末班标志、收车空态）：App **没有**调试时钟，模拟器也改不了系统时间，只能**改数据**绕过时钟：
   - 先用独立“区间车”页面正常录入一条区间车，再 `run-as` 覆写 `files/user_overrides.json`；
   - `departures` 设成**未来**时刻 → 能看到班次行与首末班标签；设成**已过去**的时刻 → 触发「今日已收车 · 首班 XX:XX」；
   - 必须同时用 `"patternEnabled": {"forward": false, "reverse": false}` **关掉内置全程车交路**，否则被测班次会被「每方向最近 3 班」截断、根本看不见；
   - 改完 `am force-stop` + `am start` 生效；
   - **改前务必备份原文件，验完还原**：
     ```powershell
     $adb="C:\Users\ASUS\AppData\Local\Android\Sdk\platform-tools\adb.exe"
     & $adb -s emulator-5554 shell run-as com.metronearby cat files/user_overrides.json > "$env:TEMP\ov.bak.json"
     Get-Content "$env:TEMP\ov.json" -Raw | & $adb -s emulator-5554 shell "run-as com.metronearby sh -c 'cat > files/user_overrides.json'"
     ```
6. **截图**：必须 `screencap -p /sdcard/x.png` 再 `adb pull`；用 PowerShell `>` 重定向会破坏 PNG。

---

## 8. 已知环境坑（速查）

| 现象 | 真因 / 处置 |
| --- | --- |
| 改完线路 JSON，App 报「数据加载失败」 | 文件被写了 UTF-8 **BOM**；改用 `WriteAllText(..., UTF8Encoding($false))` |
| 输入框里「内容明明对」但校验不过 | `input text` 注入的是**全角**标点，改用 ADBKeyboard 广播 |
| 点按钮没反应 | 用了瞬时 `input tap`；改用 `motionevent DOWN/UP` |
| 界面文字全乱码 / 看起来没文字 | 读 dump XML 没用 `ReadAllText`（UTF-8） |
| 输入框点了没键盘 / 键盘是左侧一条竖框 | 当前 IME 是 ADBKeyboard（无标准软键盘）；或 Gboard 开着「手写输入」。与 App 无关 |
| 改完 `user_overrides.json` 界面没变 | 未重启 App（`am force-stop` + `am start`），或该文件的 `lineId` 与当前线路不一致 |
| 首末班标签/区间车看不见 | 被「每方向最近 3 班」截断了；用 `patternEnabled` 关掉内置交路 |
| 启动后界面迟迟不出现 | debug 包冷启动慢（逐类校验 + 无 JIT profile），不是崩溃；等几秒再 dump |
| 截图打不开 | 用了 `>` 重定向 |

---

## 9. 需求推进工作流（本项目已验证有效的协作方式）

1. **先结论后动手**：用户说「先不要改，就说可不可行」时，只给可行性 / 影响面判断，等确认。
2. **口径必须问清再开工**：涉及取舍（平均口径、判定粒度、时间口径）时先给选项并推荐，用户拍板后再实现。已拍板的口径记在协作记忆里（见下），不要重问。
3. **实现顺序**：`domain`（纯逻辑 + 单测）→ `data`（仓库合并 / 落盘）→ `ui`（Compose 编排）→ 接线。
4. **验证**：`testDebugUnitTest --rerun` 全绿 → `assembleDebug` → 装模拟器 → 按第 7 节在**真机界面**上实测（测试数据用完还原）。
5. **交付**：报告「改了什么 / 怎么验的 / 还剩什么」，不要只说「已完成」。

跨会话的协作记忆（口径、踩坑、环境结论）在 CodeArts 记忆目录：
`C:\Users\ASUS\.codeartsdoer\memory\D--AndroidStudioProjects-MetroNearby\`（`MEMORY.md` 为索引）。换机器或换 AI 工具时，这份 `AGENTS.md` 是自足的最小交接面。

---

## 10. 交付前检查清单

- [ ] `:app:testDebugUnitTest --rerun` 全绿，用例数 ≥ 410
- [ ] `:app:compileDebugKotlin` / `:app:assembleDebug` 通过
- [ ] 新增的可测逻辑在 `domain/` 且有对应单测
- [ ] 未新增第三方依赖（或已向用户说明必要性）
- [ ] 未移除 JaCoCo
- [ ] 新增/修改线路数据后跑过 `tools/line-pipeline/Test-LineData.ps1`
- [ ] 模拟器上实测过改动路径，测试数据（`files/user_overrides.json`、IME）已复原
