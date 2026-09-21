# Metro Nearby

<p align="center">
  <img src="design/metro-nearby-app-icon.png" width="160" alt="Metro Nearby 应用图标">
</p>

Metro Nearby 是一款面向北京地铁的 Android 离线位置与换乘助手。应用打开后读取设备位置，在本地线网中列出最近及周边车站，并可离线规划少换乘或少经过站的路线。

> 路线结果来自离线站序与换乘关系。预计班次属于默认关闭的实验功能，不代表运营方实时列车位置。

## 功能

- **附近车站**：定位后展示最近站及多个附近备选站，同时显示直线距离、当前定位坐标、精度和更新时间。
- **北京完整线网**：收录北京官网当前列出的 28 条线路，同名换乘站会合并其线路关系。
- **离线路线**：按“少换乘”或“少经过站”规划线路、上车方向、站数和换乘站，不需要网络或实时班次。
- **未来规划**：设置页可查看优先规划、后续完善和有趣探索方向，功能完成前会明确标记为规划内容。
- **实验预计**：可在设置中开启离线班次推算；使用首末班、间隔和用户数据，默认关闭。
- **用户校准**：按工作日、周末和通勤时段学习现场到站记录，支持两小时到站锚点和系统预计/用户校准切换。
- **发车间隔学习**：连续记录至少三班确认无漏车的到站点，用实际观测学习间隔。
- **班次表管理**：按线路、交路和运营日修改总班次数、逐趟及沿途站时间；支持多选班次整体平移或批量删除，并检查时间倒置和列车超车。
- **自定义线路**：用户可填写城市、线路颜色、站序、经纬度、首末班和初始间隔；创建后参与定位、搜索、订阅和班次管理。
- **区间车**：提供独立管理页面，可先选择线路，再补录区间交路；支持勾选多条后整体提前/延后、统一发车时刻或批量删除。
- **常用站与通勤方向**：订阅多个站点，为每站保存线路和方向偏好；默认作为常用站信息展示。
- **线网图**：内置可缩放、拖动和分块高清解码的北京线网图，并提供可扩展的城市选择窗口。
- **主题与素材**：支持深色、浅色和跟随系统主题，包含应用图标、吉祥物、空状态插画和加载动画。

## 数据准确度

应用有意区分“官方字段”“估算字段”和“用户观测”，不会把推算结果描述成实时数据。

| 数据 | 当前来源与口径 |
| --- | --- |
| 站序、站名、经纬度 | 北京地铁官网公开数据；官网零坐标的少数站点使用复核过的 OpenStreetMap 坐标 |
| 首末班 | 部分线路已逐站或端点核对，其余保留待核对标记 |
| 站间运行时间 | 已核对线路使用公开数据，其余按站距估算 |
| 发车间隔 | 支持工作日、周末和高峰/平峰规则；未核实部分明确标为估算 |
| 用户数据 | 现场观测、到站锚点、连续间隔学习和用户维护的逐班表 |

用户修正不会改写内置 assets。内置数据只读，所有观测、班次覆盖和区间车都单独保存在应用私有目录中。

更详细的口径见：

- [产品方向与功能分层](docs/product-direction.md)
- [离线路线规划](docs/offline-route-planning.md)
- [预测准确度（实验功能）](docs/prediction-accuracy.md)
- [用户分时段校准](docs/user-calibration.md)
- [发车间隔学习](docs/headway-learning.md)
- [班次表管理](docs/departure-schedule-management.md)
- [区间车管理](docs/short-turn-management.md)
- [北京线网补齐](docs/beijing-lines-completion.md)

## 隐私与网络

- 应用只申请精确位置和大致位置权限。
- 正常运行不需要网络权限，没有后端或账号系统。
- 定位信息、订阅、用户观测和自定义线路只保存在设备本地。
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
