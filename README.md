# Metro Nearby

<p align="center">
  <img src="design/metro-nearby-app-icon.png" width="160" alt="Metro Nearby 应用图标">
</p>

Metro Nearby 是一款面向北京地铁的 Android 离线位置与出行助手。应用可以根据设备位置查找最近及周边车站，使用本地线网规划路线，并在没有网络时继续使用收藏、足迹、随机探索、应急卡和线网图等功能。

> 路线结果来自离线站序与换乘关系，不能反映临时封站、越站、运营调整等实时情况。旧到站预测、用户校准和时刻管理入口已移除。

## 主要功能

### 附近车站与线路

- **附近车站**：定位后展示最近站及多个附近备选站，同时显示直线距离、当前定位坐标、定位精度和更新时间。
- **北京完整线网**：收录北京官网线网中的 28 条线路；同名换乘站会合并线路关系。
- **附近站雷达**：以上北下南的方式显示周边车站方位和直线距离；点击圆点可显示站名，车站列表可折叠，展开后可从任意站点开始规划路线。
- **站名搜索**：支持中文、拼音全拼、首字母、已有别名和需用户确认的错字建议，并标明城市和线路。
- **自定义线路**：用户可以填写城市、线路颜色、站序和经纬度；创建后参与定位、搜索、雷达与路线规划。

### 离线路线与通勤

- **离线路线规划**：按“少换乘”或“少经过站”计算线路、上车方向、经过站数和换乘站。
- **方向助手**：根据下一站说明每段应乘线路与方向，可展开查看沿途车站。
- **通勤与行程**：保存并命名路线，设为常用通勤，支持编辑、删除和反向规划。
- **离线行程卡**：在路线页整理乘车步骤，并可通过系统分享面板分享 PNG 图片。
- **常用站与通勤方向**：收藏多个站点，保存线路、方向、家／公司／学校标签、常走出口和个人备注，可一键规划到常用目的地。

### 足迹与探索

- **地铁足迹**：真实定位足够准确且靠近车站时自动点亮，也可手动补记；记录首次到访时间、线路探索进度和阶段徽章。
- **随机探索**：从尚未点亮且离线可达的车站中随机推荐目的地，可限制线路、5/10/20 公里直线距离以及最多换乘次数，并直接进入路线规划。
- 足迹只记录车站与首次到访时间，不保存移动轨迹；搜索选站和模拟位置不会自动点亮。

### 离线信息与个性化

- **离线应急卡**：集中查看最近一次真实定位及时间、附近站、最近规划路线、官方服务热线和自填紧急联系信息；电话号码只会打开拨号界面。
- **出入口与设施**：车站详情支持查看已收录的官方资料和核对日期；所有车站均可保存个人出口、设施与换乘备注。
- **高清线网图**：内置可缩放、拖动和分块解码的北京地铁线网图，并预留多城市选择能力。
- **主题与素材**：支持深色、浅色和跟随系统主题，包含应用图标、吉祥物、空状态插画和加载动画。
- **应用内升级规划**：设置页可查看已实现功能和后续探索方向。

## 产品方向与兼容

Metro Nearby 当前专注于离线位置、车站信息、路线方向、收藏和探索体验，不再把不具备实时数据支撑的到站时间作为主要能力。

旧预计班次、用户校准、间隔学习、班次表和区间车页面已经移除。旧数据不会被主动清空，相关模型和解析逻辑暂时保留，用于兼容旧版本数据。收藏站、自定义线路、足迹和线网图继续独立保存在本机。

底部工具栏目前提供：首页、收藏、路线、雷达和线网图；设置页承载线路管理、足迹与探索、应急卡、使用指引和未来规划。

进一步说明：

- [产品方向](docs/product-direction.md)
- [第一阶段升级说明](docs/travel-upgrade.md)
- [离线路线规划](docs/offline-route-planning.md)
- [出入口与站内设施](docs/station-facilities.md)
- [北京线网数据范围](docs/beijing-lines-completion.md)

## 隐私与网络

- 应用只申请精确位置和大致位置权限。
- 正常运行不需要网络权限，没有后端或账号系统。
- 收藏、通勤行程、自定义线路、个人车站备注、应急卡和足迹均保存在设备本地。
- 应用不记录连续位置轨迹，也不会根据订阅站点发送后台通知。
- 用户主动分享行程时，仅向所选应用提供生成的行程图片。
- 官网与 OpenStreetMap 访问只发生在开发期的数据导入流程中，不发生在 App 运行时。

## 技术栈

- Kotlin
- Jetpack Compose + Material 3
- kotlinx.serialization
- SharedPreferences 与本地 JSON
- 纯 JVM 领域逻辑测试
- JaCoCo 覆盖率报告

项目保持单模块结构并尽量减少第三方依赖。路线规划、站点搜索、足迹规则、随机推荐、订阅和地理计算位于纯 Kotlin 逻辑层，Compose 负责页面编排。旧预测领域逻辑仍留在工程中用于数据兼容，但对应产品入口已经下线。

```text
app/src/main/java/com/metronearby/
├── data/       线路解析、仓库合并与本地持久化
├── domain/     路线、搜索、足迹、探索、订阅及兼容逻辑
├── geo/        坐标距离和坐标系处理
├── location/   最近站计算与 Android 定位适配
└── ui/         Compose 页面、弹窗和地图查看器
```

## 构建

环境要求：

- Android Studio
- JDK 17 或 Android Studio 自带的 JBR
- Android SDK 37
- 最低 Android 7.0（API 24）

在 Windows PowerShell 中：

```powershell
./gradlew.bat :app:assembleDebug
```

生成的 APK 位于：

```text
app/build/outputs/apk/debug/app-debug.apk
```

macOS 或 Linux 使用：

```bash
./gradlew :app:assembleDebug
```

## 测试

```powershell
./gradlew.bat :app:testDebugUnitTest --rerun --console=plain
./gradlew.bat :app:jacocoUnitTestReport
```

仓库最近记录的基线为 **410 个单元测试，0 failures / 0 errors**。本轮足迹与随机探索改动尚未重新执行测试，需在验证后再更新基线。

线路数据可以使用项目脚本单独检查：

```powershell
./tools/line-pipeline/Test-LineData.ps1 `
  ./app/src/main/assets/metro/line_beijing_1.json `
  -CityIndexFile ./app/src/main/assets/metro/city_beijing.json
```

## 增加线路或城市

新增内置线路请按 [线路导入流水线](tools/line-pipeline/README.md) 操作，确保站序来自线路 relation 或运营方数据，而不是按名称范围搜索站点。脚本生成的估算时刻必须保留 `needsReview=true`，直到完成逐项核对。

多城市扩展点：

- 线路数据通过 `cityId`、`cityName` 标记城市归属。
- “我的线路”和线路选择器按城市折叠。
- 用户可以创建其他城市的自定义线路。
- 路线规划和随机探索不会跨城市连接。
- 线网图扩展方式见 [线网图城市选择](docs/network-map-cities.md)。

## 项目文档

| 文档 | 内容 |
| --- | --- |
| [AGENTS.md](AGENTS.md) | 工程结构、约定、验证工作流与环境说明 |
| [product-direction.md](docs/product-direction.md) | 当前产品方向和旧预测能力处置 |
| [travel-upgrade.md](docs/travel-upgrade.md) | 方向助手、雷达、通勤与行程卡 |
| [offline-route-planning.md](docs/offline-route-planning.md) | 离线路线算法与边界 |
| [station-footprints.md](docs/station-footprints.md) | 足迹自动点亮、隐私与进度规则 |
| [random-exploration.md](docs/random-exploration.md) | 未到访车站随机推荐、筛选与路线衔接 |
| [emergency-card.md](docs/emergency-card.md) | 离线应急卡、来源与本地记录规则 |
| [station-facilities.md](docs/station-facilities.md) | 出入口、设施和个人备注 |
| [station-search-tools.md](docs/station-search-tools.md) | 拼音、别名、错字建议和城市区分 |
| [station-subscriptions.md](docs/station-subscriptions.md) | 常用站、通勤方向和收藏口径 |
| [custom-lines.md](docs/custom-lines.md) | 自定义线路与城市分组 |
| [network-map.md](docs/network-map.md) | 高清线网图查看器 |
| [network-map-cities.md](docs/network-map-cities.md) | 线网图多城市扩展 |
| [visual-assets.md](docs/visual-assets.md) | 图标、吉祥物、插画和加载素材 |

## 当前限制

- 路线来自静态线网，不包含临时停运、封站、越站、施工或客流管制等实时变化。
- 直线距离不等于步行距离或实际乘车里程。
- 出入口与设施的官方资料目前只覆盖少量示范站；其他车站以用户个人备注为主。
- 自定义线路使用用户填写的 WGS-84 经纬度，错误坐标会影响附近站判断和距离筛选。
- 随机探索只从已加载且静态线网可达的站点中选择，不代表目的地当前开放或适合出行。
- 收藏属于本地关注列表，不包含后台提醒或推送。

## 名称

应用名称：**Metro Nearby**  
Android 包名：`com.metronearby`
