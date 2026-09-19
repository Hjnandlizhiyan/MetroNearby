# 线路流水线（line-pipeline）

把「新增一条地铁线路」变成可重复的流水线：从 OpenStreetMap 取回站序与坐标，生成与
`line_beijing_1.json` 同构的线路 JSON，校验后登记进城市索引，最后在 App 里可切换。

一条线路从零到可切换，一共四条命令加两处人工决策。

## 交付物

| 脚本 | 作用 | 产物 |
| --- | --- | --- |
| `Get-OsmLineStations.ps1` | 按 relation 抓取有序站点与 WGS-84 坐标 | `out\<lineId>-osm.json` |
| `New-LineJson.ps1` | 生成线路 JSON 骨架（站表/线段/首末班/交路） | `app\src\main\assets\metro\line_<city>_<n>.json` |
| `Test-LineData.ps1` | 按 App 的真实消费方式校验线路 JSON | 退出码 0 / 1 / 2 |
| `Register-Line.ps1` | 把线路写入城市索引并设置 `dataFile` | 更新 `city_beijing.json` |

四个脚本都只写 UTF-8 无 BOM 文件，并且都能用 `-?` 看完整参数说明。

## 前置条件

- Windows PowerShell（`curl.exe` 随系统提供，脚本依赖它发起 HTTP 请求）。
- 能访问 Overpass API 的网络。Overpass 公共实例都在欧洲，代理出口选欧洲节点最稳。
- 不需要 JDK / Gradle；只有跑单测验证时才需要（见文末）。

## 端到端流程

以 2 号线（环线）为例。

### 第 1 步：找到 relation id

```powershell
cd tools\line-pipeline
.\Get-OsmLineStations.ps1 -FindRelation '地铁 2号线'
```

输出候选关系列表（id、名称、成员数）。**站序必须取自 relation 成员**——按名称或范围
过滤会把邻线站点和换乘站的重名节点一起捞进来。

### 第 2 步：抓取站序与坐标

```powershell
.\Get-OsmLineStations.ps1 1667236 -OutFile .\out\bj2-osm.json
```

脚本用两趟查询（`rel(id);out body;` 拿成员顺序，再分批 `node(id:...)` 拿坐标），
自动轮换端点、失败重试并节流。中间产物建议放在 `out\` 下（不要提交进仓库）。

> **人工决策点 1：核对站名与站序。** 打开 `out\bj2-osm.json` 逐条看：
> 站名是否与运营口径一致、有没有漏站、环线的首尾是否是同一站、换乘站是否取到了
> 正确的那一个节点。OSM 是众包数据，这一步不能省。

### 第 3 步：生成线路 JSON

```powershell
.\New-LineJson.ps1 -StationsFile .\out\bj2-osm.json -LineId bj2 -LineName '2号线' `
    -Color '#004B87' -FirstDeparture '05:10' -LastDeparture '23:05' `
    -DirectionLabels '内环','外环' `
    -OutFile ..\..\app\src\main\assets\metro\line_beijing_2.json
```

生成内容：

- `stationOrder` / `stations`：id 沿用 `<lineId>_<NN>` 约定；
- `aliases`：补 `<站名>站`（本身以「站」结尾的如「北京站」不重复追加）；
- `segments`：站间时长按 `-RunSecondsPerStop`（默认 130 秒）等值估算；
- `stationServiceTimes`：由「始发站首末班 + 逐段累计」推算；
- `patterns`：双向全程车，带占位 `headways`。

**这一步产出的时刻表全部是估算值**，`needsReview` 与 `note` 会写明这一点。

两个方向的始发首末班不同时，另传 `-ReverseFirstDeparture` 和 `-ReverseLastDeparture`；
省略时沿用正向值。脚本会分别生成两方向间隔边界和沿途估算首末班。
始发边界已核实也不代表沿途用时、全天间隔已核实，仍应保留 `needsReview=true` 并注明核对范围。

2026-09-18 新增 4 / 5 / 6 / 7 / 8 号线的 relation、站序复核和始发边界来源见
[`docs/beijing-lines-expansion.md`](../../docs/beijing-lines-expansion.md)。
同日新增 3 / 9 号线的数据范围、方向 relation 与官方端点边界见
[`docs/beijing-lines-3-9.md`](../../docs/beijing-lines-3-9.md)。

> **人工决策点 2：替换占位时刻表。** 用官方公开资料替换 `headways`（发车间隔）、
> `services[].firstDeparture/lastDeparture`（首末班）以及必要的
> `stationServiceTimes`，然后把 `needsReview` 改为 `false`。
> 不改也能跑，App 会按「预计」展示；这是刻意的设计，不是缺陷。

### 第 4 步：校验

```powershell
.\Test-LineData.ps1 ..\..\app\src\main\assets\metro\line_beijing_2.json `
    -CityIndexFile ..\..\app\src\main\assets\metro\city_beijing.json
```

此时会提示「城市索引里尚未登记」，这是预期的——注册后再复查一次即可。
`-Strict` 会把警告也视为失败；正式发布前建议加上。

### 第 5 步：登记进城市索引

```powershell
.\Register-Line.ps1 ..\..\app\src\main\assets\metro\line_beijing_2.json
```

脚本会先跑一遍 `Test-LineData.ps1`，通过后才改 `city_beijing.json`。
它按文本结构就地改写（不重建整个文件），所以中文名与两空格缩进都保持不变，
diff 只有新增的几行。

常用选项：

- `-Color '#004B87'`：索引里的主题色是 UI 的权威来源；不带该参数时保留索引已有颜色。
- `-InsertAfter bj17`：把新线路插到指定线路之后，便于维持线路号顺序。
- `-WhatIf`：只看要做什么，不落盘。
- 备份写在 `tools\line-pipeline\.backup\`，不会污染 `assets\`。

### 第 6 步：复查与运行

```powershell
.\Test-LineData.ps1 ..\..\app\src\main\assets\metro\line_beijing_2.json `
    -CityIndexFile ..\..\app\src\main\assets\metro\city_beijing.json
cd ..\..
$env:JAVA_HOME='D:\Android\Android Studio\jbr'
.\gradlew :app:testDebugUnitTest --rerun :app:assembleDebug
```

然后在模拟器/真机上切到新线路，确认站点列表、倒计时与配色。

## 校验脚本检查什么

`Test-LineData.ps1` 的每条规则都对应 App 里真实的消费点，不是泛泛的 schema 校验：

| 检查 | 为什么 |
| --- | --- |
| 必填字段 `lineId/lineName/stationOrder/stations/segments/patterns` | kotlinx.serialization 缺字段直接抛异常 |
| UTF-8 BOM | BOM 会让解析失败，且 `Out-File -Encoding UTF8` 默认就写 BOM |
| `stations` 与 `stationOrder` 集合一致、无重复 | `orderIndexOf` / `stationById` 是全部功能的入口 |
| 每对相邻站都有 `segments` 条目且 `runSeconds > 0` | `ArrivalEstimator.offsetBySegments` 累加失败会静默返回「无车」 |
| 别名非空且含「站名」或「站名站」 | 搜索依赖别名命中 |
| 每个交路起终点存在且不同、有 `services` | 起终点相同会被判定为「本站即终点」，永远不返回车 |
| `serviceType ∈ {weekday, weekend}`、末班不早于首班、分时段落在首末班区间内 | 发车序列生成的前提 |
| `stationServiceTimes` / `exactDepartures` 的键必须是已知站点与已知 `pattern.id` | 否则该条数据永远不生效 |
| 坐标与运行时长的一致性（站间距、隐含均速） | 抓错节点时最先暴露的地方 |
| `stationServiceTimes` 反推 offset 与 `segments` 累加相差过大 | 改了站间时长却忘了重算首末班 |

环线会多出一条从末站回到首站的闭合段，脚本会识别并只给提示不给错误。

## 环线怎么处理

环线的运营形态是「起点即终点」，但数据模型是线性的，`New-LineJson.ps1` 的做法是：

1. 检测首尾成员是否同一个 OSM 节点，是则去掉重复的收尾站，每站只出现一次；
2. `segments` 末尾补一条「末站 → 首站」的闭合段，把环描述完整；
3. 两个方向用 `-DirectionLabels` 指定运营口径的名称（如「内环」「外环」），
   不指定会退化成「开往 车公庄」这种对环线没有意义的标签，并给出警告。

`stationServiceTimes` 的两个方向分别按「从各自始发站起的累计时长」推算，闭合段不参与，
因为它不属于任何一个方向的运行交路。

## 已知踩坑

**Overpass 端点**

- `overpass-api.de` 是唯一确认能服务北京数据的公共实例，也是默认首选。
- 请求**必须**带 `Accept: application/json` 与 `User-Agent`，否则返回 406。
- 限流约每分钟 2 次查询，第 3 次会返回 504；脚本已内置重试与间隔，手工调试时要留意。
- `overpass.kumi.systems` 与 `overpass.private.coffee` 解析到同一个 IP（奥地利），
  不是互相备份；`overpass.osm.ch` 只载入瑞士数据，查北京会返回**空**结果而不是报错。
- Overpass 返回的节点坐标字段是 `lon`，不是 `lng`。

**数据与模型**

- `stationServiceTimes` 优先于 `segments` 生效（`ArrivalEstimator.resolveBaseOffset`）：
  两者不一致时以首末班为准，调站间时长必须同步重算，或删掉对应条目让它回落到 `segments`。
- 写 JSON 一律用 `[System.IO.File]::WriteAllText($path, $json, (New-Object System.Text.UTF8Encoding($false)))`，
  不要用 `Out-File -Encoding UTF8`（会写 BOM）。
- 备份文件不要留在 `assets/` 下，会被打包进 APK。

**App 侧**

- 城市索引里 `dataFile` 为空表示线路只有名称与主题色，App 不应尝试加载它。
- 主题色以 `city_beijing.json` 为准，线路文件里的 `color` 是冗余信息，两者应保持一致。

## 完成后的检查清单

- [ ] `Test-LineData.ps1` 报 0 错误（正式发布另加 `-Strict` 且 0 警告）
- [ ] `city_beijing.json` 中该线路的 `dataFile` 指向真实存在的文件
- [ ] 占位时刻表已替换为官方值，`needsReview` 已按实际情况调整
- [ ] `:app:testDebugUnitTest --rerun` 全绿
- [ ] `:app:assembleDebug` 通过
- [ ] 模拟器上能切到该线路，站点、方向、倒计时、配色正常
