# Metro Nearby

<p align="center">
  <img src="design/metro-nearby-app-icon.png" width="160" alt="Metro Nearby 应用图标">
</p>

Metro Nearby 是一款面向北京地铁的 Android 离线位置与换乘助手。应用打开后读取设备位置，在本地线网中列出最近及周边车站，并可离线规划少换乘或少经过站的路线。

> 路线结果来自离线站序与换乘关系，不能反映实时运营调整。旧到站预测、校准与时刻管理入口已移除。

## 功能

- **附近车站**：定位后展示最近站及多个附近备选站，同时显示直线距离、当前定位坐标、精度和更新时间。
- **北京完整线网**：收录北京官网当前列出的 28 条线路，同名换乘站会合并其线路关系。
- **离线路线**：按“少换乘”或“少经过站”规划线路、上车方向、站数和换乘站，不需要网络或实时班次。
- **方向助手**：按下一站指引乘车方向，展示每段上车站、站数、换乘站，可展开沿途站。
- **附近站雷达**：上北下南显示附近车站方位和直线距离，点击车站从此站规划。
- **通勤与行程**：保存并命名路线，设为常用通勤，支持编辑、删除、返程和重启恢复。
- **离线行程卡**：路线页直接查看，支持通过系统分享面板分享 PNG 图片。
- **出入口与设施**：首页和收藏可打开车站详情；首批收录复兴门、积水潭、阜成门的部分官方资料，展示来源日期，支持所有车站的个人备注。
- **未来规划**：第一阶段四项已实现；第二阶段出入口与设施已上线基础版，其余功能仍待推进。
- **自定义线路**：用户填写城市、线路颜色、站序和经纬度；创建后参与定位、搜索、雷达和路线规划。
- **常用站与通勤方向**：收藏多个站点，为每站保存线路和方向偏好。
- **线网图**：内置可缩放、拖动和分块高清解码的北京线网图，并提供可扩展的城市选择窗口。
- **主题与素材**：支持深色、浅色和跟随系统主题，包含应用图标、吉祥物、空状态插画和加载动画。

## 数据与升级兼容

路线仅基于本地站序及换乘关系；雷达是站点中心的直线距离，不是步行路线。
乘车方向按下一站识别，必须核对站台标识和列车实际终点。

旧预计班次、用户校准、间隔学习、班次表和区间车页面已经移除。旧时刻数据不会被主动清空，
历史模型、解析与测试保留用于兼容。收藏站、自定义线路和线网图继续可用。
底部导航为：首页、收藏、路线、雷达、线网图，线路管理仍在设置中。

- [第一阶段升级说明](docs/travel-upgrade.md)
- [出入口与站内设施](docs/station-facilities.md)
- [产品方向](docs/product-direction.md)
- [离线路线规划](docs/offline-route-planning.md)
- [北京线网数据范围](docs/beijing-lines-completion.md)

## 隐私与网络

- 应用只申请精确位置和大致位置权限。
- 正常运行不需要网络权限，没有后端或账号系统。
- 收藏、通勤行程与自定义线路只保存在设备本地；用户主动分享时仅向所选应用提供行程图片。
- 官网与 OpenStreetMap 访问只发生在开发期的数据导入脚本中，不发生在 App 运行时。

## 技术栈

- Kotlin
- Jetpack Compose + Material 3
- kotlinx.serialization
- SharedPreferences 与本地 JSON
- 纯 JVM 领域逻辑测试
- JaCoCo 覆盖率报告

项目保持单模块结构，并尽量减少第三方依赖。预测、校准、班次检查、站点搜索和地理计算均位于纯 Kotlin 逻辑层，Compose 只负责界面编排。

```text
app/src/main/java/com/metronearby/
├── data/       线路解析、仓库合并与本地持久化
├── domain/     到站预测、校准、班次、订阅等纯逻辑
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

当前基线为 **410 个单元测试，0 failures / 0 errors**。线路数据还可以使用项目脚本单独检查：

```powershell
./tools/line-pipeline/Test-LineData.ps1 `
  ./app/src/main/assets/metro/line_beijing_1.json `
  -CityIndexFile ./app/src/main/assets/metro/city_beijing.json
```

## 增加线路或城市

新增内置线路请按 [线路导入流水线](tools/line-pipeline/README.md) 操作，确保站序来自线路 relation 或运营方数据，而不是按名称范围搜索站点。所有生成的估算时刻必须保留 `needsReview=true`，直到逐项核对完成。

多城市相关扩展点：

- 线路数据通过 `cityId`、`cityName` 标记城市归属；
- “我的线路”和线路选择器自动按城市折叠；
- 用户可以直接创建其它城市的自定义线路；
- 线网图城市目录和资源接入方法见 [线网图城市选择](docs/network-map-cities.md)。

## 项目文档

| 文档 | 内容 |
| --- | --- |
| [AGENTS.md](AGENTS.md) | 工程结构、约定、验证工作流与环境说明 |
| [station-subscriptions.md](docs/station-subscriptions.md) | 常用站、通勤方向和订阅口径 |
| [custom-lines.md](docs/custom-lines.md) | 自定义线路与城市分组 |
| [network-map.md](docs/network-map.md) | 高清线网图查看器 |
| [visual-assets.md](docs/visual-assets.md) | 图标、吉祥物、插画和加载素材 |

## 当前限制

- 到站时间依赖离线时刻和用户观测，不代表列车实时位置。
- 部分新增线路只核对了官方站序、坐标和端点首末班，沿途运行时间与全天间隔仍是估算值。
- 自定义线路使用用户填写的 WGS-84 经纬度；错误坐标会影响附近站判断。
- 订阅是本地关注列表，目前不包含后台推送提醒。

## 名称

应用名称：**Metro Nearby**  
Android 包名：`com.metronearby`
