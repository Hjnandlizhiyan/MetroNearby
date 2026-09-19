<#
.SYNOPSIS
    Register a line JSON in the city index (city_beijing.json) so the app can load it.

.DESCRIPTION
    The city index is a hand-maintained file: two-space indentation, one field per line,
    Chinese names kept verbatim. Round-tripping it through ConvertTo-Json would re-escape
    every Chinese character, reformat the whole file and bury the real change in a huge
    diff. So this script edits the text structurally instead:

      * locate the {"lineId": ...} object block,
      * rebuild its fields in their original order (dataFile last, like bj1),
      * preserve each block's own indentation and the file's CRLF line endings,
      * write UTF-8 without BOM.

    Registration is idempotent: pointing dataFile at the same file twice changes nothing.

    Before touching the index the line JSON is validated with Test-LineData.ps1 (without
    the city-index check, otherwise a not-yet-registered line could never be registered).
    The data file itself must already exist next to the index, because the unit tests
    resolve it relative to assets/metro.

.PARAMETER LineFile
    The line JSON to register, e.g. app\src\main\assets\metro\line_beijing_2.json.
    Its lineId / lineName / color are read from here.

.PARAMETER CityIndexFile
    The city index to edit. Defaults to app\src\main\assets\metro\city_beijing.json.

.PARAMETER DataFile
    File name to record in dataFile. Defaults to the file name of -LineFile.

.PARAMETER Color
    Override the colour written into the index. By default the index keeps its existing
    colour (it is the source of truth for the UI) and only falls back to the line file's.

.PARAMETER InsertAfter
    When the line is not registered yet, place it after this lineId. Default: append at
    the end of the lines array.

.PARAMETER SkipValidation
    Do not run Test-LineData.ps1 first.

.PARAMETER NoBackup
    Do not write a .bak next to the index before modifying it.

.EXAMPLE
    .\Register-Line.ps1 ..\..\app\src\main\assets\metro\line_beijing_2.json

.EXAMPLE
    .\Register-Line.ps1 ..\..\app\src\main\assets\metro\line_beijing_2.json -Color '#004B87' -InsertAfter bj1
#>
[CmdletBinding(SupportsShouldProcess = $true)]
param(
    [Parameter(Mandatory = $true, Position = 0)]
    [string]$LineFile,

    [string]$CityIndexFile,

    [string]$DataFile,

    [string]$Color,

    [string]$InsertAfter,

    [switch]$SkipValidation,

    [switch]$NoBackup
)

$ErrorActionPreference = 'Stop'

function ConvertTo-JsonString {
    param([string]$Value)
    return $Value.Replace('\', '\\').Replace('"', '\"')
}

function Get-Indent {
    param([string]$Line)
    return ([regex]::Match($Line, '^(\s*)')).Groups[1].Value
}

# Locates the object block that carries the given lineId. The index holds flat objects,
# so the enclosing braces are the nearest ones above and below the lineId field.
function Get-LineBlock {
    param([string[]]$Lines, [string]$LineId)

    $lineIdIndex = -1
    for ($i = 0; $i -lt $Lines.Count; $i++) {
        if ($Lines[$i] -match ('"lineId"\s*:\s*"' + [regex]::Escape($LineId) + '"\s*,?\s*$')) {
            $lineIdIndex = $i
            break
        }
    }
    if ($lineIdIndex -lt 0) { return $null }

    $start = $lineIdIndex
    while ($start -ge 0 -and $Lines[$start] -notmatch '\{') { $start-- }
    $end = $lineIdIndex
    while ($end -lt $Lines.Count -and $Lines[$end] -notmatch '\}') { $end++ }
    if ($start -lt 0 -or $end -ge $Lines.Count) { return $null }

    return [pscustomobject]@{ Start = $start; End = $end; FieldIndex = $lineIdIndex }
}

# ---------------------------------------------------------------------------
# Read the line file and figure out what to register
# ---------------------------------------------------------------------------
$linePath = (Resolve-Path -LiteralPath $LineFile).Path
$line = [System.IO.File]::ReadAllText($linePath, [System.Text.Encoding]::UTF8) | ConvertFrom-Json

$lineId = [string]$line.lineId
$lineName = [string]$line.lineName
$lineColor = [string]$line.color

if ([string]::IsNullOrWhiteSpace($lineId)) {
    Write-Host "线路文件缺少 lineId：$linePath" -ForegroundColor Red
    exit 1
}
if ([string]::IsNullOrWhiteSpace($lineName)) {
    Write-Host "线路文件缺少 lineName：$linePath" -ForegroundColor Red
    exit 1
}

if ([string]::IsNullOrWhiteSpace($DataFile)) {
    $DataFile = [System.IO.Path]::GetFileName($linePath)
}

if ([string]::IsNullOrWhiteSpace($CityIndexFile)) {
    $CityIndexFile = Join-Path $PSScriptRoot '..\..\app\src\main\assets\metro\city_beijing.json'
}
$indexPath = (Resolve-Path -LiteralPath $CityIndexFile).Path

if ($Color -and $Color -notmatch '^#[0-9A-Fa-f]{6}$') {
    Write-Host "颜色 '$Color' 不是 #RRGGBB 格式" -ForegroundColor Red
    exit 1
}

Write-Host ''
Write-Host "登记 $lineName（$lineId）到 $indexPath" -ForegroundColor White
Write-Host "  dataFile : $DataFile"

# The unit tests resolve dataFile relative to the index, so the file must be there.
$dataFilePath = Join-Path ([System.IO.Path]::GetDirectoryName($indexPath)) $DataFile
if (-not (Test-Path -LiteralPath $dataFilePath)) {
    Write-Host ''
    Write-Host "找不到数据文件 $dataFilePath" -ForegroundColor Red
    Write-Host "请先把线路 JSON 放到 assets\metro\ 下（索引按文件名解析，不会跟随临时目录里的副本）。" -ForegroundColor Yellow
    exit 1
}

# ---------------------------------------------------------------------------
# Validate the line first (city-index check skipped on purpose: chicken and egg)
# ---------------------------------------------------------------------------
if (-not $SkipValidation) {
    $validator = Join-Path $PSScriptRoot 'Test-LineData.ps1'
    Write-Host ''
    Write-Host '注册前校验线路数据：' -ForegroundColor Cyan
    & $validator -LineFile $linePath
    if ($LASTEXITCODE -ne 0) {
        Write-Host ''
        Write-Host '线路校验未通过，已中止注册。修复后重跑；确需强制注册请加 -SkipValidation。' -ForegroundColor Red
        exit 1
    }
}

# ---------------------------------------------------------------------------
# Rewrite the city index, textually
# ---------------------------------------------------------------------------
$text = [System.IO.File]::ReadAllText($indexPath, [System.Text.Encoding]::UTF8)
$newline = if ($text.Contains("`r`n")) { "`r`n" } else { "`n" }
$lines = @($text -split "`r?`n")

$block = Get-LineBlock -Lines $lines -LineId $lineId
$city = $text | ConvertFrom-Json
$existingRef = @($city.lines) | Where-Object { [string]$_.lineId -eq $lineId } | Select-Object -First 1

# Colour priority: explicit -Color > colour already registered > the line file's colour.
$colorToWrite = $Color
if (-not $colorToWrite -and $null -ne $existingRef -and -not [string]::IsNullOrWhiteSpace([string]$existingRef.color)) {
    $colorToWrite = [string]$existingRef.color
}
if (-not $colorToWrite) { $colorToWrite = $lineColor }

if ($colorToWrite -and $colorToWrite -notmatch '^#[0-9A-Fa-f]{6}$') {
    Write-Host "颜色 '$colorToWrite' 不是 #RRGGBB 格式，请用 -Color 显式指定" -ForegroundColor Red
    exit 1
}

if ($null -ne $block) {
    $blockLines = @($lines[$block.Start..$block.End])
    $fieldIndent = '    '
    $braceIndent = '  '

    $fieldLines = @($blockLines | Where-Object { $_ -notmatch '^\s*[{}]' -and $_.Trim() -ne '' })
    if ($fieldLines.Count -gt 0) { $fieldIndent = Get-Indent $fieldLines[0] }
    $braceIndent = Get-Indent $blockLines[0]

    # Rewrite the four fields this script owns (lineId/name/color/dataFile) so that a
    # renamed line, a corrected colour or -Color all take effect, then keep whatever
    # extra fields a future schema may have added.
    $managedPattern = '^\s*"(lineId|name|color|dataFile)"\s*:'
    $extras = @($fieldLines | Where-Object { $_ -notmatch $managedPattern })

    $kept = New-Object System.Collections.Generic.List[string]
    $kept.Add(('{0}"lineId": "{1}"' -f $fieldIndent, (ConvertTo-JsonString $lineId)))
    $kept.Add(('{0}"name": "{1}"' -f $fieldIndent, (ConvertTo-JsonString $lineName)))
    if ($colorToWrite) {
        $kept.Add(('{0}"color": "{1}"' -f $fieldIndent, (ConvertTo-JsonString $colorToWrite)))
    }
    $kept.Add(('{0}"dataFile": "{1}"' -f $fieldIndent, (ConvertTo-JsonString $DataFile)))
    foreach ($extra in $extras) { $kept.Add($extra) }

    if ($null -ne $existingRef -and [string]$existingRef.dataFile -eq $DataFile) {
        Write-Host ''
        Write-Host "城市索引中的 $lineId 已指向 $DataFile（刷新名称、颜色与字段顺序）" -ForegroundColor DarkGray
    }

    # The closing brace of every entry but the last one carries a comma; dropping it
    # would leave the array with two adjacent objects and break the whole file.
    $closingComma = if ($blockLines[$blockLines.Count - 1].TrimEnd() -match ',$') { ',' } else { '' }

    $rebuilt = New-Object System.Collections.Generic.List[string]
    $rebuilt.Add("$braceIndent{")
    for ($i = 0; $i -lt $kept.Count; $i++) {
        $fieldLine = $kept[$i].TrimEnd().TrimEnd(',')
        if ($i -lt $kept.Count - 1) { $fieldLine += ',' }
        $rebuilt.Add($fieldLine)
    }
    $rebuilt.Add("$braceIndent}$closingComma")

    $result = New-Object System.Collections.Generic.List[string]
    for ($i = 0; $i -lt $block.Start; $i++) { $result.Add($lines[$i]) }
    $result.AddRange($rebuilt)
    for ($i = $block.End + 1; $i -lt $lines.Count; $i++) { $result.Add($lines[$i]) }

    $action = "更新 lineId=$lineId 的 dataFile"
} else {
    # New entry. Infer indentation from the existing array so the file stays consistent.
    $linesArrayIndex = -1
    for ($i = 0; $i -lt $lines.Count; $i++) {
        if ($lines[$i] -match '"lines"\s*:\s*\[') { $linesArrayIndex = $i; break }
    }
    if ($linesArrayIndex -lt 0) {
        Write-Host '城市索引里找不到 "lines" 数组' -ForegroundColor Red
        exit 1
    }

    $braceIndent = '    '
    $fieldIndent = '      '
    $closingIndex = -1
    for ($i = $linesArrayIndex + 1; $i -lt $lines.Count; $i++) {
        if ($lines[$i] -match '^\s*\]') { $closingIndex = $i; break }
        if ($lines[$i] -match '^\s*\{') {
            $braceIndent = Get-Indent $lines[$i]
            $fieldIndent = $braceIndent + '  '

        }
    }
    if ($closingIndex -lt 0) {
        Write-Host '城市索引的 "lines" 数组没有正确的闭合括号' -ForegroundColor Red
        exit 1
    }

    $newBlock = New-Object System.Collections.Generic.List[string]
    $newBlock.Add("$braceIndent{")
    $newBlock.Add(('{0}"lineId": "{1}",' -f $fieldIndent, (ConvertTo-JsonString $lineId)))
    $newBlock.Add(('{0}"name": "{1}",' -f $fieldIndent, (ConvertTo-JsonString $lineName)))
    if ($colorToWrite) {
        $newBlock.Add(('{0}"color": "{1}",' -f $fieldIndent, (ConvertTo-JsonString $colorToWrite)))
        $newBlock.Add(('{0}"dataFile": "{1}"' -f $fieldIndent, (ConvertTo-JsonString $DataFile)))
    } else {
        $newBlock.Add(('{0}"dataFile": "{1}"' -f $fieldIndent, (ConvertTo-JsonString $DataFile)))
    }
    $newBlock.Add("$braceIndent}")

    $result = New-Object System.Collections.Generic.List[string]

    if ($InsertAfter) {
        $after = Get-LineBlock -Lines $lines -LineId $InsertAfter
        if ($null -eq $after) {
            Write-Host "-InsertAfter $InsertAfter 在城市索引中不存在" -ForegroundColor Red
            exit 1
        }
        # Inserted mid-array, so this entry needs a trailing comma of its own.
        $lastIndex = $newBlock.Count - 1
        $newBlock[$lastIndex] = $newBlock[$lastIndex].TrimEnd() + ','

        # Everything above the anchor has to be carried over as well.
        for ($i = 0; $i -lt $after.Start; $i++) { $result.Add($lines[$i]) }

        foreach ($i in $after.Start..$after.End) {
            $lineText = $lines[$i]
            if ($i -eq $after.End -and $lineText.TrimEnd() -notmatch ',\s*$') { $lineText = $lineText.TrimEnd() + ',' }
            $result.Add($lineText)
        }
        for ($i = $after.End + 1; $i -lt $lines.Count; $i++) {
            if ($i -eq $after.End + 1) { $result.AddRange($newBlock) }
            $result.Add($lines[$i])
        }
        $action = "在 $InsertAfter 之后新增线路 $lineId"
    } else {
        for ($i = 0; $i -lt $closingIndex; $i++) {
            $lineText = $lines[$i]
            if ($i -eq $closingIndex - 1) {
                # The previous last entry needs a comma now that another one follows.
                $lineText = $lineText.TrimEnd()
                if ($lineText -notmatch ',\s*$') { $lineText += ',' }
            }
            $result.Add($lineText)
        }
        $result.AddRange($newBlock)
        for ($i = $closingIndex; $i -lt $lines.Count; $i++) { $result.Add($lines[$i]) }
        $action = "在 lines 数组末尾新增线路 $lineId"
    }
}

$newText = ($result -join $newline)

# ---------------------------------------------------------------------------
# Write (with backup) and re-verify
# ---------------------------------------------------------------------------
if (-not $PSCmdlet.ShouldProcess($indexPath, $action)) {
    Write-Host ''
    Write-Host "WhatIf：$action" -ForegroundColor Yellow
    exit 0
}

try {
    [System.Text.Encoding]::UTF8.GetString([System.Text.Encoding]::UTF8.GetBytes($newText)) | Out-Null
} catch {
    Write-Host '编辑结果不是合法文本，已中止（请检查字段内容）' -ForegroundColor Red
    exit 1
}

# Parse before writing: a broken index would take the whole app down.
try {
    $null = $newText | ConvertFrom-Json
} catch {
    $rejectedDir = Join-Path $PSScriptRoot '.backup'
    New-Item -ItemType Directory -Force -Path $rejectedDir | Out-Null
    $rejectedPath = Join-Path $rejectedDir ('{0}.rejected.json' -f [System.IO.Path]::GetFileName($indexPath))
    [System.IO.File]::WriteAllText($rejectedPath, $newText, (New-Object System.Text.UTF8Encoding($false)))
    Write-Host "编辑后的 JSON 无法解析，已中止（原文件未改动）：$($_.Exception.Message)" -ForegroundColor Red
    Write-Host "被拒绝的中间结果（供排查）：$rejectedPath" -ForegroundColor Yellow
    exit 1
}

# Backups deliberately go outside assets/: anything left in there is packed into the APK.
if (-not $NoBackup) {
    $backupDir = Join-Path $PSScriptRoot '.backup'
    New-Item -ItemType Directory -Force -Path $backupDir | Out-Null
    $stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
    $backupPath = Join-Path $backupDir ('{0}.{1}.bak' -f [System.IO.Path]::GetFileName($indexPath), $stamp)
    [System.IO.File]::WriteAllText($backupPath, $text, (New-Object System.Text.UTF8Encoding($false)))
    Write-Host "  备份     : $backupPath" -ForegroundColor DarkGray
}

[System.IO.File]::WriteAllText($indexPath, $newText, (New-Object System.Text.UTF8Encoding($false)))

Write-Host ''
Write-Host "已$action" -ForegroundColor Green
Write-Host ''
Write-Host '下一步：' -ForegroundColor Yellow
Write-Host "  1. 复跑校验（这次带上城市索引）：Test-LineData.ps1 -LineFile `"$linePath`" -CityIndexFile `"$indexPath`"" -ForegroundColor Yellow
Write-Host '  2. 跑单测与构建：:app:testDebugUnitTest / :app:assembleDebug' -ForegroundColor Yellow
Write-Host '  3. 在设置界面切到新线路，确认站点、倒计时与配色都正常' -ForegroundColor Yellow
exit 0