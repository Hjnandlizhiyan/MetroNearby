<#
.SYNOPSIS
    Validate a MetroNearby line JSON against the contract the app actually relies on.

.DESCRIPTION
    Every rule encoded here mirrors a real consumer:

      * kotlinx.serialization requires lineId / lineName / stationOrder / stations /
        segments / patterns, and every Service requires serviceType + firstDeparture +
        lastDeparture. A missing one crashes the app at parse time, so those are errors.
      * ArrivalEstimator addresses everything through stationOrder + stationById, sums
        segments over adjacent station pairs, looks up stationServiceTimes[station]
        [patternId] and pattern.services[serviceType]. Anything it cannot resolve
        silently yields "no departures" — which looks like a bug, not like bad data.
      * MetroJsonParsingTest (the unit tests) additionally enforces non-empty aliases,
        positive runSeconds, self-consistent headway windows and known keys.

    Two levels are reported:
      ERROR - the app cannot or must not ship this file.
      WARN  - the file parses and runs, but the data is suspect or incomplete.
              Use -Strict to treat warnings as failure (recommended before registering).

    Beyond structure, the script cross-checks the timetable against the geography: a
    segment whose implied speed exceeds 100 km/h, a station pair hundreds of metres
    apart with a 30 s run time, or a station far away from both neighbours all point
    at a wrong OSM node rather than at a design choice.

.PARAMETER LineFile
    Path to the line JSON (e.g. app\src\main\assets\metro\line_beijing_1.json).

.PARAMETER CityIndexFile
    Optional city index (e.g. app\src\main\assets\metro\city_beijing.json). When given,
    the script also checks that this line is registered, that the registered colour
    matches, and that the index points at this very file.

.PARAMETER ServiceTimeToleranceSeconds
    Maximum tolerated difference between a stationServiceTime-derived offset and the
    offset implied by summing segments. Default 60 s: edit the segments but forget to
    recompute the times and this is what catches it.

.PARAMETER Strict
    Exit non-zero when only warnings were found.

.EXAMPLE
    .\Test-LineData.ps1 ..\..\app\src\main\assets\metro\line_beijing_1.json -CityIndexFile ..\..\app\src\main\assets\metro\city_beijing.json -Strict
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true, Position = 0)]
    [string]$LineFile,

    [string]$CityIndexFile,

    [double]$ServiceTimeToleranceSeconds = 60,

    [switch]$Strict
)

$ErrorActionPreference = 'Stop'

$errors = New-Object System.Collections.Generic.List[string]
$warnings = New-Object System.Collections.Generic.List[string]

function Add-Error {
    param([string]$Message)
    $errors.Add($Message) | Out-Null
    Write-Host "  [ERROR] $Message" -ForegroundColor Red
}

function Add-Warning {
    param([string]$Message)
    $warnings.Add($Message) | Out-Null
    Write-Host "  [WARN ] $Message" -ForegroundColor Yellow
}

function Add-Pass {
    param([string]$Message)
    if ($VerbosePreference -eq 'Continue') {
        Write-Host "  [ ok  ] $Message" -ForegroundColor DarkGreen
    }
}

function Add-Section {
    param([string]$Title)
    Write-Host ''
    Write-Host "-- $Title" -ForegroundColor Cyan
}

# Mirrors TimeUtils.parseToSecondsOfDay: "HH:mm" with hours allowed up to 47 so that
# a departure after midnight can be written as 24:21. Returns $null when unparsable.
function Try-ConvertToSeconds {
    param([string]$Clock)

    if ([string]::IsNullOrWhiteSpace($Clock)) { return $null }
    if ($Clock -notmatch '^(\d{1,2}):(\d{2})$') { return $null }
    $hours = [int]$Matches[1]
    $minutes = [int]$Matches[2]
    if ($hours -gt 47 -or $minutes -gt 59) { return $null }
    return $hours * 3600 + $minutes * 60
}

# Same convention as the generator: hours may exceed 24, so TimeSpan.ToString("hh:mm")
# is not usable here.
function Format-FromSeconds {
    param([int]$Seconds)

    $hours = [math]::Floor($Seconds / 3600)
    $minutes = [math]::Floor(($Seconds % 3600) / 60)
    return ('{0:00}:{1:00}' -f $hours, $minutes)
}

function Get-DistanceMeters {
    param([double]$Lat1, [double]$Lng1, [double]$Lat2, [double]$Lng2)

    $earthRadius = 6371000.0
    $toRadians = [math]::PI / 180.0
    $deltaLat = ($Lat2 - $Lat1) * $toRadians
    $deltaLng = ($Lng2 - $Lng1) * $toRadians
    $a = [math]::Sin($deltaLat / 2) * [math]::Sin($deltaLat / 2) +
    [math]::Cos($Lat1 * $toRadians) * [math]::Cos($Lat2 * $toRadians) *
    [math]::Sin($deltaLng / 2) * [math]::Sin($deltaLng / 2)
    $a = [math]::Min(1.0, [math]::Max(0.0, $a))
    return 2 * $earthRadius * [math]::Atan2([math]::Sqrt($a), [math]::Sqrt(1 - $a))
}

# ---------------------------------------------------------------------------
# Load
# ---------------------------------------------------------------------------
$path = (Resolve-Path -LiteralPath $LineFile).Path
$bytes = [System.IO.File]::ReadAllBytes($path)

if ($bytes.Length -ge 3 -and $bytes[0] -eq 0xEF -and $bytes[1] -eq 0xBB -and $bytes[2] -eq 0xBF) {
    Add-Error '文件带 UTF-8 BOM；kotlinx.serialization 会在解析时报错，请以「UTF-8 无 BOM」重新保存。'
}

$text = [System.Text.Encoding]::UTF8.GetString($bytes)
$text = $text.TrimStart([char]0xFEFF)

try {
    $line = $text | ConvertFrom-Json
} catch {
    Write-Host "无法解析 JSON：$($_.Exception.Message)" -ForegroundColor Red
    exit 1
}

Write-Host ''
Write-Host "校验 $path" -ForegroundColor White

# ---------------------------------------------------------------------------
# Required fields (mirrors the non-nullable kotlinx properties)
# ---------------------------------------------------------------------------
Add-Section '必需字段'

$propertyNames = @($line.PSObject.Properties.Name)
$required = @(
    'lineId', 'lineName', 'stationOrder', 'stations', 'segments', 'patterns'
)

$errorsBeforeRequired = $errors.Count
foreach ($field in $required) {
    if ($propertyNames -notcontains $field) {
        Add-Error "缺少必需字段 $field（kotlinx.serialization 会解析失败）"
    } elseif ($null -eq $line.$field) {
        Add-Error "字段 $field 为 null（该属性在数据模型里不可空）"
    }
}

# Only a missing required field makes the remaining sections meaningless; a BOM, for
# example, is fatal for the app but does not stop the rest of the file from being checked.
if ($errors.Count -gt $errorsBeforeRequired) {
    Write-Host ''
    Write-Host "字段缺失，跳过其余检查。" -ForegroundColor Red
    exit 1
}

Add-Pass 'lineId / lineName / stationOrder / stations / segments / patterns 齐备'

$lineId = [string]$line.lineId
$lineName = [string]$line.lineName
if ([string]::IsNullOrWhiteSpace($lineId)) { Add-Error 'lineId 不应为空' }
if ([string]::IsNullOrWhiteSpace($lineName)) { Add-Error 'lineName 不应为空' }

if ($propertyNames -notcontains 'coordSystem') {
    Add-Warning '未声明 coordSystem，将按数据模型默认值 wgs84 处理'
} elseif ([string]$line.coordSystem -ne 'wgs84') {
    Add-Warning "coordSystem = $($line.coordSystem)；定位比较依赖 WGS-84，其它坐标系需要先转换"
}

$stationOrder = @($line.stationOrder)
$stations = @($line.stations)
$segments = @($line.segments)
$patterns = @($line.patterns)

# ---------------------------------------------------------------------------
# Station order and station table
# ---------------------------------------------------------------------------
Add-Section '站序与站表'

if ($stationOrder.Count -lt 2) {
    Add-Error "stationOrder 只有 $($stationOrder.Count) 项，至少需要 2 站"
}

$blankOrderIds = @($stationOrder | Where-Object { [string]::IsNullOrWhiteSpace([string]$_) })
if ($blankOrderIds.Count -gt 0) { Add-Error 'stationOrder 中存在空 id' }

$duplicatedOrder = @($stationOrder | Group-Object | Where-Object { $_.Count -gt 1 } | ForEach-Object { $_.Name })
if ($duplicatedOrder.Count -gt 0) {
    Add-Error "stationOrder 存在重复车站：$($duplicatedOrder -join ', ')"
}

$stationIds = @($stations | ForEach-Object { [string]$_.id })
$duplicatedStations = @($stationIds | Group-Object | Where-Object { $_.Count -gt 1 } | ForEach-Object { $_.Name })
if ($duplicatedStations.Count -gt 0) {
    Add-Error "stations 存在重复 id：$($duplicatedStations -join ', ')"
}

if ($stationOrder.Count -ne $stations.Count) {
    Add-Error "stations（$($stations.Count)）与 stationOrder（$($stationOrder.Count)）数量不一致"
}

$orderSet = [System.Collections.Generic.HashSet[string]]::new()
foreach ($id in $stationOrder) { $orderSet.Add([string]$id) | Out-Null }
$stationSet = [System.Collections.Generic.HashSet[string]]::new()
foreach ($id in $stationIds) { $stationSet.Add($id) | Out-Null }

foreach ($station in $stations) {
    $id = [string]$station.id
    if (-not $orderSet.Contains($id)) { Add-Error "站点 $id 出现在 stations 但不在 stationOrder 中" }
}
foreach ($id in $stationOrder) {
    if (-not $stationSet.Contains([string]$id)) { Add-Error "站序中的 $id 在 stations 里找不到对应站点" }
}

if ($orderSet.Count -eq $stationSet.Count -and $orderSet.Count -gt 0) {
    $expectedNames = @($stationOrder | ForEach-Object { [string]$_ })
    if (($stationIds -join ',') -ne ($expectedNames -join ',')) {
        Add-Warning 'stations 的顺序与 stationOrder 不一致（app 按 id 查找，功能不受影响，但两份顺序建议一致以便人工比对）'
    } else {
        Add-Pass 'stations 与 stationOrder 一一对应且顺序一致'
    }
}

foreach ($station in $stations) {
    $id = [string]$station.id
    $name = [string]$station.name

    if ([string]::IsNullOrWhiteSpace($id)) { Add-Error '存在 id 为空的站点' ; continue }
    if ([string]::IsNullOrWhiteSpace($name)) { Add-Error "站点 $id 的名称为空" }

    if ($id -notmatch ('^' + [regex]::Escape($lineId) + '_\d+$')) {
        Add-Warning "站点 id $id 不符合 <lineId>_<序号> 约定（与 lineId=$lineId 不匹配）"
    }

    $lat = $station.lat
    $lng = $station.lng
    if ($lat -isnot [double] -and $lat -isnot [int] -and $lat -isnot [decimal] -and $lat -isnot [long]) {
        Add-Error "站点 $id 的 lat 不是数值：$lat"
        continue
    }
    if ($lng -isnot [double] -and $lng -isnot [int] -and $lng -isnot [decimal] -and $lng -isnot [long]) {
        Add-Error "站点 $id 的 lng 不是数值：$lng"
        continue
    }

    $latValue = [double]$lat
    $lngValue = [double]$lng
    if ($latValue -lt -90 -or $latValue -gt 90) { Add-Error "站点 $id 的纬度越界：$latValue" }
    if ($lngValue -lt -180 -or $lngValue -gt 180) { Add-Error "站点 $id 的经度越界：$lngValue" }
    if ($latValue -eq 0 -and $lngValue -eq 0) { Add-Error "站点 $id 的坐标是 (0,0)，显然是占位值" }

    $aliases = @($station.aliases)
    if ($aliases.Count -eq 0) {
        Add-Error "站点 $id（$name）没有任何别名，搜索将无法用「${name}站」命中"
    } else {
        # Braces are mandatory: PowerShell would otherwise read "$name站" as one variable name.
        $standard = "${name}站"
        if ($aliases -notcontains $standard -and $aliases -notcontains $name) {
            Add-Warning "站点 $id（$name）的别名 $($aliases -join '/') 既不含「$name」也不含「$standard」"
        }
    }
}

# Same coordinates for two different stations usually means one node was used twice.
$byCoordinate = @{}
foreach ($station in $stations) {
    $key = '{0:F7},{1:F7}' -f [double]$station.lat, [double]$station.lng
    if ($byCoordinate.ContainsKey($key)) {
        Add-Warning "站点 $($byCoordinate[$key]) 与 $($station.id) 坐标完全相同（$key），确认不是选错节点"
    } else {
        $byCoordinate[$key] = [string]$station.id
    }
}

# ---------------------------------------------------------------------------
# Segments
# ---------------------------------------------------------------------------
Add-Section '站间线段'

$segmentKeys = @{}
foreach ($segment in $segments) {
    $from = [string]$segment.from
    $to = [string]$segment.to

    if (-not $stationSet.Contains($from)) { Add-Error "线段起点 $from 不是已知站点" }
    if (-not $stationSet.Contains($to)) { Add-Error "线段终点 $to 不是已知站点" }

    $key = "$from->$to"
    if ($segmentKeys.ContainsKey($key)) {
        Add-Error "线段 $key 重复定义"
    } else {
        $segmentKeys[$key] = $true
    }

    $runSeconds = $segment.runSeconds
    if ($null -eq $runSeconds) {
        Add-Error "线段 $key 缺少 runSeconds"
    } else {
        $run = [int]$runSeconds
        if ($run -le 0) { Add-Error "线段 $key 的 runSeconds=$run 应为正数" }
        elseif ($run -gt 900) { Add-Warning "线段 $key 的 runSeconds=$run 超过 15 分钟，确认是否为相邻站" }
    }
}

for ($i = 0; $i -lt $stationOrder.Count - 1; $i++) {
    $key = "$($stationOrder[$i])->$($stationOrder[$i + 1])"
    if (-not $segmentKeys.ContainsKey($key)) {
        Add-Error "第 $($i + 1) 对相邻站缺少线段 $key（ArrivalEstimator 无法累加 offset）"
    }
}

# A ring line has one extra edge closing the loop; any other extra edge is suspicious.
$validPairs = [System.Collections.Generic.HashSet[string]]::new()
for ($i = 0; $i -lt $stationOrder.Count - 1; $i++) {
    $validPairs.Add("$($stationOrder[$i])->$($stationOrder[$i + 1])") | Out-Null
}
$closingPair = "$($stationOrder[$stationOrder.Count - 1])->$($stationOrder[0])"
foreach ($key in $segmentKeys.Keys) {
    if (-not $validPairs.Contains($key) -and $key -ne $closingPair) {
        Add-Warning "线段 $key 既不是相邻站，也不是环线闭合段（$closingPair），确认是否写错方向"
    }
}

$adjacentSegmentCount = $stationOrder.Count - 1
if ($segments.Count -eq $adjacentSegmentCount) {
    Add-Pass "共 $($segments.Count) 段，覆盖全部相邻站"
} elseif ($segments.Count -eq $adjacentSegmentCount + 1 -and $segmentKeys.ContainsKey($closingPair)) {
    Add-Warning "共 $($segments.Count) 段，比相邻站对多 1：被视为环线闭合段 $closingPair（若本线不是环线请删除）"
} else {
    Add-Warning "共 $($segments.Count) 段，相邻站对需要 $adjacentSegmentCount 段"
}

# ---------------------------------------------------------------------------
# Geography sanity: distance vs run time
# ---------------------------------------------------------------------------
Add-Section '坐标与运行时长的一致性'

$stationById = @{}
foreach ($station in $stations) { $stationById[[string]$station.id] = $station }

for ($i = 0; $i -lt $stationOrder.Count - 1; $i++) {
    $fromId = [string]$stationOrder[$i]
    $toId = [string]$stationOrder[$i + 1]
    $fromStation = $stationById[$fromId]
    $toStation = $stationById[$toId]
    if ($null -eq $fromStation -or $null -eq $toStation) { continue }
    if ($null -eq $fromStation.lat -or $null -eq $toStation.lat) { continue }

    $distance = Get-DistanceMeters -Lat1 ([double]$fromStation.lat) -Lng1 ([double]$fromStation.lng) `
        -Lat2 ([double]$toStation.lat) -Lng2 ([double]$toStation.lng)

    if ($distance -lt 100) {
        Add-Warning "$fromId -> $toId 直线距离仅 $([math]::Round($distance)) 米，疑似同一站被拆成两个节点"
    } elseif ($distance -gt 8000) {
        Add-Warning "$fromId -> $toId 直线距离 $([math]::Round($distance)) 米，远超常规站间距，疑似漏站"
    }

    if ($segmentKeys.ContainsKey("$fromId->$toId")) {
        $run = 0
        foreach ($segment in $segments) {
            if ([string]$segment.from -eq $fromId -and [string]$segment.to -eq $toId) {
                $run = [int]$segment.runSeconds
                break
            }
        }
        if ($run -gt 0) {
            $speed = $distance / $run * 3.6
            if ($speed -gt 100) {
                Add-Warning "$fromId -> $toId 按 $run 秒跑 $([math]::Round($distance)) 米推算均速 $([math]::Round($speed)) km/h，疑似坐标或时长有误"
            }
        }
    }
}

# ---------------------------------------------------------------------------
# Patterns and services
# ---------------------------------------------------------------------------
Add-Section '交路与时刻表'

if ($patterns.Count -eq 0) {
    Add-Error 'patterns 为空，app 不会有任何到站信息'
}

$patternIds = New-Object System.Collections.Generic.HashSet[string]
foreach ($pattern in $patterns) {
    $patternId = [string]$pattern.id
    $label = "交路 $patternId"

    if ([string]::IsNullOrWhiteSpace($patternId)) { Add-Error '存在 id 为空的交路' ; continue }
    if (-not $patternIds.Add($patternId)) { Add-Error "交路 id $patternId 重复" }

    $startId = [string]$pattern.startStationId
    $endId = [string]$pattern.endStationId
    if (-not $stationSet.Contains($startId)) { Add-Error "$label 的起点 $startId 不是已知站点" }
    if (-not $stationSet.Contains($endId)) { Add-Error "$label 的终点 $endId 不是已知站点" }
    if ($startId -eq $endId) { Add-Error "$label 的起终点相同（$startId），ArrivalEstimator 会直接判定为「本站即终点」而不返回任何车" }

    if ([string]::IsNullOrWhiteSpace([string]$pattern.directionId)) {
        Add-Warning "$label 未声明 directionId，UI 会把它归入 default 组"
    }
    if ($null -eq $pattern.directionLabel -or [string]::IsNullOrWhiteSpace([string]$pattern.directionLabel)) {
        Add-Warning "$label 未声明 directionLabel，将由该方向最远的终点站推导"
    }

    $services = @($pattern.services)
    if ($services.Count -eq 0) {
        Add-Error "$label 没有任何 service，该交路永远不会出车"
        continue
    }

    $serviceTypes = New-Object System.Collections.Generic.HashSet[string]
    foreach ($service in $services) {
        $serviceType = [string]$service.serviceType
        $serviceLabel = "$label/$serviceType"
        $serviceTypes.Add($serviceType) | Out-Null

        if ($serviceType -ne 'weekday' -and $serviceType -ne 'weekend') {
            Add-Error "$serviceLabel 的 serviceType 只能是 weekday 或 weekend"
        }

        $first = Try-ConvertToSeconds ([string]$service.firstDeparture)
        $last = Try-ConvertToSeconds ([string]$service.lastDeparture)
        if ($null -eq $first) { Add-Error "$serviceLabel 的 firstDeparture「$($service.firstDeparture)」无法解析为 HH:mm" ; continue }
        if ($null -eq $last) { Add-Error "$serviceLabel 的 lastDeparture「$($service.lastDeparture)」无法解析为 HH:mm" ; continue }
        if ($last -lt $first) { Add-Error "$serviceLabel 的末班（$($service.lastDeparture)）早于首班（$($service.firstDeparture)）" }

        $headways = @($service.headways)
        if ($headways.Count -eq 0) {
            Add-Error "$serviceLabel 没有 headways，发车序列为空"
            continue
        }

        $intervals = New-Object System.Collections.Generic.List[object]
        foreach ($rule in $headways) {
            $ruleFrom = Try-ConvertToSeconds ([string]$rule.from)
            $ruleTo = Try-ConvertToSeconds ([string]$rule.to)
            $ruleLabel = "$serviceLabel 时段 $($rule.from)-$($rule.to)"

            if ($null -eq $ruleFrom) { Add-Error "$ruleLabel 的起点无法解析" ; continue }
            if ($null -eq $ruleTo) { Add-Error "$ruleLabel 的终点无法解析" ; continue }
            if ($ruleTo -le $ruleFrom) { Add-Error "$ruleLabel 的起点不早于终点" }
            if ($ruleFrom -lt $first -or $ruleTo -gt $last) {
                Add-Error "$ruleLabel 超出首末班区间（$($service.firstDeparture)-$($service.lastDeparture)）"
            }

            $interval = $rule.intervalSeconds
            if ($null -eq $interval -or [int]$interval -le 0) {
                Add-Error "$ruleLabel 的 intervalSeconds 应为正数"
            }

            $intervals.Add([pscustomobject]@{ from = $ruleFrom; to = $ruleTo; interval = [int]$interval }) | Out-Null
        }

        # buildDepartureSeconds starts at firstDeparture and falls back to the last rule's
        # interval when the current minute matches no window — silently, hence the warning.
        $covered = $intervals | Where-Object { $first -ge $_.from -and $first -lt $_.to }
        if (-not $covered) {
            Add-Warning "$serviceLabel 的首班 $($service.firstDeparture) 不落在任何分时段内，实际会用最后一条间隔发车"
        }

        $sortedIntervals = @($intervals | Sort-Object -Property from)
        for ($i = 0; $i -lt $sortedIntervals.Count - 1; $i++) {
            if ($sortedIntervals[$i].to -lt $sortedIntervals[$i + 1].from) {
                Add-Warning ("$serviceLabel 在 {0}-{1} 之间没有分时段，将沿用前一段的间隔" -f `
                    (Format-FromSeconds $sortedIntervals[$i].to), `
                    (Format-FromSeconds $sortedIntervals[$i + 1].from))
            }
        }
    }

    if (-not $serviceTypes.Contains('weekday')) { Add-Warning "$label 缺少 weekday 方案" }
    if (-not $serviceTypes.Contains('weekend')) { Add-Warning "$label 缺少 weekend 方案（周末会查不到车）" }
}

foreach ($pattern in $patterns) {
    $hasCounterpart = $false
    foreach ($other in $patterns) {
        if ([string]$other.startStationId -eq [string]$pattern.endStationId -and
            [string]$other.endStationId -eq [string]$pattern.startStationId) {
            $hasCounterpart = $true
            break
        }
    }
    if (-not $hasCounterpart) {
        Add-Warning "交路 $($pattern.id) 没有反向交路（起终点互换），该方向乘客查不到车"
    }
}

# ---------------------------------------------------------------------------
# stationServiceTimes
# ---------------------------------------------------------------------------
Add-Section '各站首末班车（stationServiceTimes）'

$serviceTimes = $line.stationServiceTimes
if ($null -eq $serviceTimes) {
    Add-Warning 'stationServiceTimes 为空，offset 将完全依赖 segments 累加'
} else {
    foreach ($stationProperty in $serviceTimes.PSObject.Properties) {
        $stationId = $stationProperty.Name
        if (-not $stationSet.Contains($stationId)) {
            Add-Error "stationServiceTimes 出现未知站点 $stationId"
            continue
        }

        foreach ($patternProperty in $stationProperty.Value.PSObject.Properties) {
            $patternId = $patternProperty.Name
            $entryLabel = "$stationId/$patternId"

            if (-not $patternIds.Contains($patternId)) {
                Add-Error "stationServiceTimes 出现未知交路 $patternId（必须等于某个 pattern.id）"
                continue
            }

            $first = Try-ConvertToSeconds ([string]$patternProperty.Value.firstDeparture)
            if ($null -eq $first) {
                Add-Error "$entryLabel 的 firstDeparture「$($patternProperty.Value.firstDeparture)」无法解析"
                continue
            }
            $last = Try-ConvertToSeconds ([string]$patternProperty.Value.lastDeparture)
            if ($null -ne $patternProperty.Value.lastDeparture -and $null -eq $last) {
                Add-Error "$entryLabel 的 lastDeparture「$($patternProperty.Value.lastDeparture)」无法解析"
            }
            if ($null -ne $last -and $last -lt $first) {
                Add-Error "$entryLabel 的末班早于首班"
            }

            # Cross-check the published first departure against the segment sum. A gap
            # here means someone edited one side and forgot the other.
            $pattern = $patterns | Where-Object { [string]$_.id -eq $patternId } | Select-Object -First 1
            if ($null -eq $pattern) { continue }

            $service = @($pattern.services) | Select-Object -First 1
            if ($null -eq $service) { continue }
            $originFirst = Try-ConvertToSeconds ([string]$service.firstDeparture)
            if ($null -eq $originFirst) { continue }

            $startIndex = [array]::IndexOf($stationOrder, [string]$pattern.startStationId)
            $stationIndex = [array]::IndexOf($stationOrder, $stationId)
            if ($startIndex -lt 0 -or $stationIndex -lt 0) { continue }

            $segmentOffset = 0
            $reachable = $true
            if ($startIndex -lt $stationIndex) {
                for ($i = $startIndex; $i -lt $stationIndex; $i++) {
                    $key = "$($stationOrder[$i])->$($stationOrder[$i + 1])"
                    if (-not $segmentKeys.ContainsKey($key)) { $reachable = $false ; break }
                    foreach ($segment in $segments) {
                        if ([string]$segment.from -eq $stationOrder[$i] -and [string]$segment.to -eq $stationOrder[$i + 1]) {
                            $segmentOffset += [int]$segment.runSeconds
                            break
                        }
                    }
                }
            } elseif ($startIndex -gt $stationIndex) {
                for ($i = $startIndex - 1; $i -ge $stationIndex; $i--) {
                    $key = "$($stationOrder[$i])->$($stationOrder[$i + 1])"
                    if (-not $segmentKeys.ContainsKey($key)) { $reachable = $false ; break }
                    foreach ($segment in $segments) {
                        if ([string]$segment.from -eq $stationOrder[$i] -and [string]$segment.to -eq $stationOrder[$i + 1]) {
                            $segmentOffset += [int]$segment.runSeconds
                            break
                        }
                    }
                }
            }

            if (-not $reachable) { continue }

            $publishedOffset = $first - $originFirst
            $difference = [math]::Abs($publishedOffset - $segmentOffset)
            if ($difference -gt $ServiceTimeToleranceSeconds) {
                $message = ("$entryLabel 与 segments 推算相差 {0} 秒（首末班反推 offset {1}s，线段累加 {2}s）；" -f `
                        [int]$difference, $publishedOffset, $segmentOffset) +
                'stationServiceTimes 优先生效，调整站间时长后记得同步重算'
                Add-Warning $message
            }
        }
    }

    Add-Pass 'stationServiceTimes 的站点键与交路键均合法'
}

# ---------------------------------------------------------------------------
# exactDepartures
# ---------------------------------------------------------------------------
Add-Section '真实发车序列（exactDepartures）'

$exactDepartures = $line.exactDepartures
if ($null -eq $exactDepartures) {
    Add-Pass 'exactDepartures 未填，全部按推算展示'
} else {
    foreach ($stationProperty in $exactDepartures.PSObject.Properties) {
        $stationId = $stationProperty.Name
        if (-not $stationSet.Contains($stationId)) {
            Add-Error "exactDepartures 出现未知站点 $stationId"
            continue
        }
        foreach ($patternProperty in $stationProperty.Value.PSObject.Properties) {
            $patternId = $patternProperty.Name
            if (-not $patternIds.Contains($patternId)) {
                Add-Error "exactDepartures 出现未知交路 $patternId"
                continue
            }
            foreach ($time in @($patternProperty.Value)) {
                if ($null -eq (Try-ConvertToSeconds ([string]$time))) {
                    Add-Error "exactDepartures $stationId/$patternId 的时刻「$time」无法解析"
                }
            }
        }
    }
}

# ---------------------------------------------------------------------------
# City index registration
# ---------------------------------------------------------------------------
Add-Section '城市索引登记'

if ([string]::IsNullOrWhiteSpace($CityIndexFile)) {
    Add-Warning '未传 -CityIndexFile，跳过城市索引登记校验（注册前应带上该参数再跑一次）'
} else {
    $indexPath = (Resolve-Path -LiteralPath $CityIndexFile).Path
    try {
        $city = [System.IO.File]::ReadAllText($indexPath, [System.Text.Encoding]::UTF8) | ConvertFrom-Json
    } catch {
        Add-Error "城市索引无法解析：$($_.Exception.Message)"
        $city = $null
    }

    if ($null -ne $city) {
        $ref = @($city.lines) | Where-Object { [string]$_.lineId -eq $lineId } | Select-Object -First 1
        if ($null -eq $ref) {
            Add-Error "城市索引 $($city.cityId) 中尚未登记线路 $lineId，请先运行 Register-Line.ps1"
        } else {
            if ([string]$ref.name -ne $lineName) {
                Add-Warning "城市索引里 $lineId 的名称「$($ref.name)」与线路文件里的「$lineName」不一致"
            }
            if ($null -eq $ref.dataFile) {
                Add-Warning "城市索引里 $lineId 尚未指向数据文件（dataFile 为空），app 里看不到站点"
            } elseif ([string]$ref.dataFile -ne [System.IO.Path]::GetFileName($path)) {
                Add-Warning "城市索引里 $lineId 指向 $($ref.dataFile)，与当前校验的 $([System.IO.Path]::GetFileName($path)) 不是同一个文件"
            }

            $lineColor = [string]$line.color
            $refColor = [string]$ref.color
            if (-not [string]::IsNullOrWhiteSpace($lineColor) -and $lineColor -ne $refColor) {
                Add-Warning "线路文件的 color（$lineColor）与城市索引登记的主题色（$refColor）不一致；UI 以索引为准"
            }
            Add-Pass "城市索引已登记 $lineId（$($ref.name)）"
        }
    }
}

# ---------------------------------------------------------------------------
# Review flags
# ---------------------------------------------------------------------------
Add-Section '数据状态'

if ($line.needsReview -eq $true) {
    Add-Warning 'needsReview=true：首末班与发车间隔等仍是估算/占位值，发布前须逐项核对官方资料'
}
if ($null -eq $line.updatedAt -or [string]::IsNullOrWhiteSpace([string]$line.updatedAt)) {
    Add-Warning '未填写 updatedAt，UI 无法展示数据更新时间'
}
if ($null -eq $line.dataSource -or [string]::IsNullOrWhiteSpace([string]$line.dataSource)) {
    Add-Warning '未填写 dataSource，UI 的来源说明会为空'
}

# ---------------------------------------------------------------------------
# Summary
# ---------------------------------------------------------------------------
Write-Host ''
Write-Host ('-' * 60) -ForegroundColor DarkGray
Write-Host ("错误 {0} 项，警告 {1} 项" -f $errors.Count, $warnings.Count) `
    -ForegroundColor $(if ($errors.Count -gt 0) { 'Red' } elseif ($warnings.Count -gt 0) { 'Yellow' } else { 'Green' })

if ($errors.Count -gt 0) {
    Write-Host '校验未通过：请先修复上面列出的 ERROR。' -ForegroundColor Red
    exit 1
}
if ($Strict -and $warnings.Count -gt 0) {
    Write-Host '校验未通过（-Strict）：警告也必须清零。' -ForegroundColor Red
    exit 2
}
if ($warnings.Count -gt 0) {
    Write-Host '校验通过（仍有警告需人工确认）。' -ForegroundColor Yellow
} else {
    Write-Host '校验通过。' -ForegroundColor Green
}
exit 0