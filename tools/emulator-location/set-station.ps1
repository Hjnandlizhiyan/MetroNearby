<#
.SYNOPSIS
    Move the Android emulator GPS position to a station of Beijing Metro Line 1.

.DESCRIPTION
    The station list is read at runtime from app/src/main/assets/metro/line_beijing_1.json,
    so it always stays in sync with the app's data (currently all 36 stations from
    Apple Orchard to Universal Resort). No coordinates are hard-coded here.

    A station can be selected by its id (bj1_13), its Chinese name (西单), one of the
    names in its aliases (军博), or a unique substring of the name (天安门).

    NOTE: on some emulator images (e.g. the 16KB-page sdk_gphone16k_* builds) the
    console injection channel is not wired up, and "adb emu geo fix" returns OK
    without actually moving the device. If the app keeps reporting the same
    station after switching, load metro_stations.gpx through
    Extended Controls -> Location -> GPX instead.

.EXAMPLE
    .\set-station.ps1 -List
    .\set-station.ps1 西单
    .\set-station.ps1 天安门
    .\set-station.ps1 bj1_36 -Serial emulator-5556
#>
[CmdletBinding()]
param(
    [Parameter(Position = 0)]
    [string]$Station,

    [switch]$List,

    [string]$Serial = "emulator-5554",

    [string]$DataFile
)

$ErrorActionPreference = "Stop"

if (-not $DataFile) {
    $DataFile = Join-Path $PSScriptRoot "..\..\app\src\main\assets\metro\line_beijing_1.json"
}

if (-not (Test-Path -LiteralPath $DataFile)) {
    Write-Host "Line data file not found: $DataFile" -ForegroundColor Red
    Write-Host "Pass -DataFile <path-to-line_beijing_1.json> to override." -ForegroundColor Yellow
    exit 1
}

$line = Get-Content -LiteralPath $DataFile -Raw -Encoding UTF8 | ConvertFrom-Json

function Get-StationLabel($entry, $index) {
    "{0}  {1,-8} lat={2}  lng={3}" -f $entry.id, $entry.name, $entry.lat, $entry.lng
}

if ($List -or [string]::IsNullOrWhiteSpace($Station)) {
    Write-Host ("{0} ({1}) - {2} stations" -f $line.lineName, $line.lineId, $line.stations.Count) -ForegroundColor Cyan
    $i = 0
    foreach ($s in $line.stations) {
        $i++
        Write-Host ("  {0,2}. {1}" -f $i, (Get-StationLabel $s $i))
    }
    Write-Host ""
    Write-Host "Usage: .\set-station.ps1 <id|name|alias|substring> [-Serial emulator-5554]" -ForegroundColor DarkGray
    return
}

$query = $Station.Trim()

$matches = @($line.stations | Where-Object {
    $_.id -ieq $query -or
    $_.name -eq $query -or
    ($_.aliases -contains $query) -or
    $_.name.Contains($query)
})

if ($matches.Count -eq 0) {
    Write-Host "Unknown station: $Station" -ForegroundColor Red
    Write-Host "Run with -List to see all stations." -ForegroundColor Yellow
    exit 1
}

if ($matches.Count -gt 1) {
    Write-Host "Ambiguous station '$Station', matches:" -ForegroundColor Yellow
    foreach ($m in $matches) {
        Write-Host ("  {0}  {1}" -f $m.id, $m.name)
    }
    exit 1
}

$target = $matches[0]

# Each root is checked before Join-Path: an unset ANDROID_HOME/ANDROID_SDK_ROOT would
# otherwise make Join-Path fail on a null -Path instead of simply being skipped.
$adbCandidates = @()
if ($env:LOCALAPPDATA) { $adbCandidates += Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools\adb.exe" }
if ($env:ANDROID_HOME) { $adbCandidates += Join-Path $env:ANDROID_HOME "platform-tools\adb.exe" }
if ($env:ANDROID_SDK_ROOT) { $adbCandidates += Join-Path $env:ANDROID_SDK_ROOT "platform-tools\adb.exe" }
$adbCandidates = @($adbCandidates | Where-Object { Test-Path $_ })

$adb = $adbCandidates | Select-Object -First 1
if (-not $adb) {
    Write-Host "adb.exe not found. Set ANDROID_HOME or add platform-tools to PATH." -ForegroundColor Red
    exit 1
}

Write-Host ("Injecting {0} ({1}) ..." -f $target.name, $target.id) -ForegroundColor Cyan
& $adb -s $Serial emu geo fix $target.lng $target.lat 50 | Out-Null

if ($LASTEXITCODE -ne 0) {
    Write-Host "Injection failed. Is $Serial running?" -ForegroundColor Red
    exit 1
}

Write-Host ("Sent: lng={0} lat={1}" -f $target.lng, $target.lat) -ForegroundColor Green
Write-Host ""
Write-Host "Verify the device actually moved:" -ForegroundColor DarkGray
Write-Host ("  & `"$adb`" -s $Serial shell dumpsys location | Select-String 'gps \d'") -ForegroundColor DarkGray
Write-Host ""
Write-Host "If the coordinates never change, this emulator image ignores geo fix." -ForegroundColor Yellow
Write-Host "Use Extended Controls -> Location -> GPX -> metro_stations.gpx instead." -ForegroundColor Yellow
