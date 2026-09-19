<#
.SYNOPSIS
    Turn a raw OSM stop list into a MetroNearby line JSON that matches the schema of line_beijing_1.json.

.DESCRIPTION
    Input : the file produced by Get-OsmLineStations.ps1 (ordered stops with WGS-84 coordinates).
    Output: assets/metro/line_<city>_<n>.json, ready to drop into the app.

    What is generated automatically:
      * stationOrder + stations (ids follow the existing <lineId>_<NN> convention)
      * aliases ("<name>站" unless the name already ends with 站)
      * segments (equal runSeconds估算, purely a fallback source)
      * stationServiceTimes (departure of the start station shifted by the accumulated run time)
      * patterns (one per direction, with placeholder headways)

    What CANNOT be generated and must be reviewed by a human:
      * the real headways / first & last departure times, and
      * the pattern modelling of a RING line (Ring lines are detected and reported instead
        of being guessed: pass -DirectionLabels to describe them explicitly).

    Two shape differences from a normal line are handled here:
      * Ring lines: the last OSM stop repeats the first one (same node id). The duplicate is
        dropped so stationOrder holds each station exactly once.
      * Terminal stations: for a linear line the last station of one direction is the first of
        the other, exactly as in line_beijing_1.json.

.EXAMPLE
    .\New-LineJson.ps1 -StationsFile ..\..\out\bj2-osm.json -LineId bj2 -LineName '2号线' `
        -Color '#004B87' -FirstDeparture '05:10' -LastDeparture '23:05' `
        -DirectionLabels '内环','外环' -OutFile ..\..\out\line_beijing_2.json
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$StationsFile,

    [Parameter(Mandatory = $true)]
    [string]$LineId,

    [Parameter(Mandatory = $true)]
    [string]$LineName,

    [string]$CityId = 'beijing',

    [string]$CityName = '北京',

    [Parameter(Mandatory = $true)]
    [string]$Color,

    [Parameter(Mandatory = $true)]
    [string]$FirstDeparture,

    [Parameter(Mandatory = $true)]
    [string]$LastDeparture,

    # Optional independent origin times for the reverse direction.
    [string]$ReverseFirstDeparture,

    [string]$ReverseLastDeparture,

    [string]$OutFile,

    [int]$RunSecondsPerStop = 130,

    [string]$DataSource,

    # Ring lines only: two labels, e.g. '内环','外环'. Order = station order, reverse order.
    [string[]]$DirectionLabels
)

$ErrorActionPreference = 'Stop'

if (-not $ReverseFirstDeparture) { $ReverseFirstDeparture = $FirstDeparture }
if (-not $ReverseLastDeparture) { $ReverseLastDeparture = $LastDeparture }

function ConvertTo-Seconds {
    param([string]$Clock)

    if ($Clock -notmatch '^(\d{1,2}):(\d{2})$') {
        throw "Invalid time '$Clock'; expected HH:mm."
    }
    $hours = [int]$Matches[1]
    $minutes = [int]$Matches[2]
    if ($minutes -gt 59) { throw "Invalid time '$Clock'; minutes must be 00-59." }
    return $hours * 3600 + $minutes * 60
}

# Hours may exceed 24 (e.g. 24:21) to describe a departure after midnight, exactly like
# line_beijing_1.json does.
function ConvertFrom-Seconds {
    param([int]$Seconds)

    while ($Seconds -lt 0) { $Seconds += 24 * 3600 }
    $hours = [math]::Floor($Seconds / 3600)
    $minutes = [math]::Floor(($Seconds % 3600) / 60)
    return ('{0:00}:{1:00}' -f $hours, $minutes)
}

$stationsFile = (Resolve-Path -LiteralPath $StationsFile).Path
$osm = Get-Content -LiteralPath $stationsFile -Raw -Encoding UTF8 | ConvertFrom-Json
$stops = @($osm.stops)

if ($stops.Count -lt 2) {
    throw "$StationsFile contains fewer than two stops; nothing to build."
}

# ---------------------------------------------------------------------------
# Ring detection: the relation closes the loop by repeating the first node.
# ---------------------------------------------------------------------------
$isRing = [string]$stops[0].osmNodeId -eq [string]$stops[-1].osmNodeId
if ($isRing) {
    Write-Host "Ring line detected (first and last stop are node $($stops[0].osmNodeId)); dropping the duplicate closing stop." -ForegroundColor Yellow
    $stops = @($stops[0..($stops.Count - 2)])
}

$orders = New-Object System.Collections.Generic.List[string]
$stationObjects = New-Object System.Collections.Generic.List[object]
$index = 0

foreach ($stop in $stops) {
    $index++
    $stationId = '{0}_{1:00}' -f $LineId, $index
    $name = ([string]$stop.name).Trim()

    if ([string]::IsNullOrWhiteSpace($name)) {
        throw "Stop #$index (node $($stop.osmNodeId)) has no name; OSM data must be reviewed before generating."
    }

    $orders.Add($stationId)

    # The app的搜索依赖“<站名>站”这一别名；本身已以「站」结尾的站名（如北京站）不再追加。
    $aliases = @()
    $aliases += $name + '站'
    if ($name.EndsWith('站')) { $aliases = @($name) }

    $stationObjects.Add([pscustomobject]@{
            id      = $stationId
            name    = $name
            lat     = [math]::Round([double]$stop.lat, 7)
            lng     = [math]::Round([double]$stop.lng, 7)
            aliases = $aliases
        })
}

$segmentSeconds = $RunSecondsPerStop
$adjacent = New-Object System.Collections.Generic.List[object]
for ($i = 0; $i -lt $orders.Count - 1; $i++) {
    $adjacent.Add([pscustomobject]@{
            from       = $orders[$i]
            to         = $orders[$i + 1]
            runSeconds = $segmentSeconds
        })
}

# A ring line has one edge more than a linear one: besides the n-1 edges between
# consecutive stops, the last stop runs back to the first. A segment is emitted for
# that closing edge too so the loop is fully described. The timetable maths below
# deliberately uses $adjacent only: the closing edge belongs to neither direction.
$segments = New-Object System.Collections.Generic.List[object]
$segments.AddRange($adjacent)
if ($isRing) {
    $segments.Add([pscustomobject]@{
            from       = $orders[$orders.Count - 1]
            to         = $orders[0]
            runSeconds = $segmentSeconds
        })
}

# ---------------------------------------------------------------------------
# Patterns: forward + reverse. Ring lines need explicit labels, because neither
# direction "terminates" in the usual sense.
# ---------------------------------------------------------------------------
$firstStationId = $orders[0]
$lastStationId = $orders[$orders.Count - 1]

$forwardLabel = "开往 $($stationObjects[-1].name)"
$reverseLabel = "开往 $($stationObjects[0].name)"

if ($isRing) {
    if ($DirectionLabels -and $DirectionLabels.Count -ge 2) {
        $forwardLabel = $DirectionLabels[0]
        $reverseLabel = $DirectionLabels[1]
    } else {
        Write-Warning 'Ring line without -DirectionLabels: the two directions will be labelled "开往 <站名>", which is misleading for a loop. Re-run with -DirectionLabels or edit the file by hand.'
    }
}

# Placeholder timetable. The real headways come from official sources and MUST be replaced;
# needsReview/note below say so explicitly.
function New-PlaceholderHeadways {
    param([string]$First, [string]$Last)
    return @(
    [pscustomobject]@{ from = $First; to = '07:00'; intervalSeconds = 420 },
    [pscustomobject]@{ from = '07:00'; to = '09:30'; intervalSeconds = 120 },
    [pscustomobject]@{ from = '09:30'; to = '17:00'; intervalSeconds = 300 },
    [pscustomobject]@{ from = '17:00'; to = '19:30'; intervalSeconds = 120 },
    [pscustomobject]@{ from = '19:30'; to = $Last; intervalSeconds = 420 }
)
}
$placeholderHeadways = New-PlaceholderHeadways $FirstDeparture $LastDeparture
$reverseHeadways = New-PlaceholderHeadways $ReverseFirstDeparture $ReverseLastDeparture

function New-Services {
    param([string]$From, [string]$To, [object[]]$Headways)

    # Keep every rule inside [From, To]; a late first departure can push the first slot out.
    $usable = @($Headways | Where-Object {
            (ConvertTo-Seconds $_.from) -ge (ConvertTo-Seconds $From) -and
            (ConvertTo-Seconds $_.to) -le (ConvertTo-Seconds $To) -and
            (ConvertTo-Seconds $_.from) -lt (ConvertTo-Seconds $_.to)
        })

    if ($usable.Count -eq 0) {
        $usable = @([pscustomobject]@{ from = $From; to = $To; intervalSeconds = 300 })
    }

    return @(
        [pscustomobject]@{
            serviceType     = 'weekday'
            firstDeparture  = $From
            lastDeparture   = $To
            headways        = $usable
        },
        [pscustomobject]@{
            serviceType     = 'weekend'
            firstDeparture  = $From
            lastDeparture   = $To
            headways        = $usable
        }
    )
}

$patterns = @(
    [pscustomobject]@{
        id              = 'forward'
        name            = '全程车'
        directionId     = 'forward'
        directionLabel  = $forwardLabel
        startStationId  = $firstStationId
        endStationId    = $lastStationId
        isShortTurn     = $false
        services        = (New-Services -From $FirstDeparture -To $LastDeparture -Headways $placeholderHeadways)
    },
    [pscustomobject]@{
        id              = 'reverse'
        name            = '全程车'
        directionId     = 'reverse'
        directionLabel  = $reverseLabel
        startStationId  = $lastStationId
        endStationId    = $firstStationId
        isShortTurn     = $false
        services        = (New-Services -From $ReverseFirstDeparture -To $ReverseLastDeparture -Headways $reverseHeadways)
    }
)

# ---------------------------------------------------------------------------
# stationServiceTimes: start-station departure shifted by the accumulated run time.
# Mind the warning in the line note: these entries SHADOW segments in the estimator.
# ---------------------------------------------------------------------------
$firstSeconds = ConvertTo-Seconds $FirstDeparture
$lastSeconds = ConvertTo-Seconds $LastDeparture
$reverseFirstSeconds = ConvertTo-Seconds $ReverseFirstDeparture
$reverseLastSeconds = ConvertTo-Seconds $ReverseLastDeparture

$serviceTimes = [ordered]@{}
$accumulated = 0

for ($i = 0; $i -lt $orders.Count; $i++) {
    $stationId = $orders[$i]

    # Forward direction: offset counted from the first station.
    $forwardFirst = ConvertFrom-Seconds ($firstSeconds + $accumulated)
    $forwardLast = ConvertFrom-Seconds ($lastSeconds + $accumulated)

    # Reverse direction: the train leaves the far end first, so the offset is measured
    # from the end of the line.
    $reverseOffset = $adjacent | Select-Object -Skip $i | Measure-Object -Property runSeconds -Sum
    $reverseAccumulated = if ($reverseOffset.Sum) { [int]$reverseOffset.Sum } else { 0 }
    $reverseFirst = ConvertFrom-Seconds ($reverseFirstSeconds + $reverseAccumulated)
    $reverseLast = ConvertFrom-Seconds ($reverseLastSeconds + $reverseAccumulated)

    $serviceTimes[$stationId] = [ordered]@{
        forward = [ordered]@{ firstDeparture = $forwardFirst; lastDeparture = $forwardLast }
        reverse = [ordered]@{ firstDeparture = $reverseFirst; lastDeparture = $reverseLast }
    }

    if ($i -lt $adjacent.Count) {
        $accumulated += $adjacent[$i].runSeconds
    }
}

$note = @(
    'stationOrder/segments/别名由 tools/line-pipeline/New-LineJson.ps1 自动生成。'
    'stationServiceTimes 由「始发站首末班 + 逐段累计运行时长」推算，非官方公布值。'
    '发车间隔（headways）与首末班时间为占位/估算值，须按官方公开资料逐项替换后再发布。'
    'stationServiceTimes 优先于 segments 生效（ArrivalEstimator.resolveBaseOffset），'
    '因此调整站间时长时须同步重算对应条目，或删除该条目让它回落到 segments。'
)
if ($isRing) {
    $note += '本线为环线：OSM 关系中首尾为同一站，已去掉重复的收尾站；segments 末尾额外补出「末站→首站」的闭合段（环线的最后一条边，不参与两个方向的时刻推算）；方向标签建议按运营口径（如内环/外环）核对。'
}
$note += "数据源：$($osm.relationName)（OSM relation $($osm.relationId)），坐标系统 WGS-84。"

if (-not $DataSource) {
    $DataSource = "站点坐标来自 OpenStreetMap($($osm.relationName), relation $($osm.relationId))；站间时长、首末班与发车间隔为公开资料估算，未经官方核对"
}

$line = [pscustomobject]@{
    schemaVersion      = 1
    cityId             = $CityId
    cityName           = $CityName
    lineId             = $LineId
    lineName           = $LineName
    color              = $Color
    coordSystem        = 'wgs84'
    updatedAt          = (Get-Date).ToString('yyyy-MM-dd')
    dataSource         = $DataSource
    accuracyLevel      = 'estimated'
    needsReview        = $true
    note               = ($note -join '')
    stationOrder       = $orders
    stations           = $stationObjects
    stationServiceTimes = $serviceTimes
    segments           = $segments
    patterns           = $patterns
    exactDepartures    = @{}
}

if (-not $OutFile) {
    $OutFile = Join-Path $PSScriptRoot ("line_$LineId.json")
}

$json = $line | ConvertTo-Json -Depth 10
[System.IO.File]::WriteAllText($OutFile, $json, (New-Object System.Text.UTF8Encoding($false)))

$totalRun = ($adjacent | Measure-Object -Property runSeconds -Sum).Sum

Write-Host ''
Write-Host ("Line {0} ({1}) written to {2}" -f $LineName, $LineId, $OutFile) -ForegroundColor Green
Write-Host ("  stations : {0}" -f $orders.Count) -ForegroundColor Green
Write-Host ("  segments : {0} (n-1 adjacent, run time {1}s)" -f $adjacent.Count, $totalRun) -ForegroundColor Green
Write-Host ("  ring     : {0}" -f $isRing) -ForegroundColor Green
if ($isRing) {
    Write-Host ("  loop edge: {0} -> {1} (extra segment closing the ring)" -f $orders[$orders.Count - 1], $orders[0]) -ForegroundColor Green
}
Write-Host ''
Write-Host 'MANDATORY manual steps before this file can be registered:' -ForegroundColor Yellow
Write-Host '  1. replace the placeholder headways / first & last departures with official values' -ForegroundColor Yellow
Write-Host '  2. review station names and the ring-direction labels' -ForegroundColor Yellow
Write-Host '  3. run Test-LineData.ps1, then Register-Line.ps1' -ForegroundColor Yellow
