<#
.SYNOPSIS
    Fetch the ordered stop list (with WGS-84 coordinates) of a metro line from OpenStreetMap.

.DESCRIPTION
    Uses the two-pass Overpass strategy that is known to work on the public instances:

      pass 1  rel(<id>);out body;            -> ordered node members (the real station order)
      pass 2  node(id:a,b,c);out body;       -> coordinates, in small batches

    A single recursive node() query is far too heavy and times out, so it is never used.
    The station order MUST come from the relation members: filtering by name or by a
    bounding box pulls in neighbouring lines and duplicate transfer nodes.

    Endpoints are tried in order and rotated on failure. overpass-api.de is the only
    public instance confirmed to serve Beijing data, but it:
      * rejects requests without an explicit Accept/User-Agent header (HTTP 406), and
      * throttles to roughly two queries per minute (a third one returns HTTP 504),
    hence the conservative defaults and the retry delay.

.EXAMPLE
    .\Get-OsmLineStations.ps1 1667236 -OutFile ..\..\out\bj2-osm.json

.EXAMPLE
    # List candidate relations for a line before fetching its stops
    .\Get-OsmLineStations.ps1 -FindRelation '地铁 2号线' -Bbox '39.8,116.2,40.1,116.6'
#>
[CmdletBinding(DefaultParameterSetName = 'ById')]
param(
    [Parameter(Mandatory = $true, Position = 0, ParameterSetName = 'ById')]
    [string]$RelationId,

    [Parameter(Mandatory = $true, ParameterSetName = 'Find')]
    [string]$FindRelation,

    [string]$Bbox = '39.7,116.0,40.2,116.9',

    [string]$OutFile,

    [string[]]$Endpoints = @(
        'overpass-api.de',
        'overpass.kumi.systems',
        'overpass.private.coffee'
    ),

    [int]$Retry = 4,
    [int]$RetryDelaySeconds = 30,
    [int]$ThrottleSeconds = 3,
    [int]$BatchSize = 25
)

$ErrorActionPreference = 'Stop'

# overpass-api.de answers HTTP 406 without these; keep them on every request.
$CommonCurlArgs = @(
    '-H', 'Accept: application/json',
    '-H', 'User-Agent: MetroNearby-line-pipeline/1.0 (offline asset build)'
)

function Invoke-Overpass {
    param(
        [string]$Query,
        [string]$Label
    )

    $encoded = [uri]::EscapeDataString($Query)
    $lastError = 'no endpoint tried'

    for ($attempt = 1; $attempt -le $Retry; $attempt++) {
        foreach ($endpoint in $Endpoints) {
            $temp = Join-Path $env:TEMP ('overpass-' + [guid]::NewGuid().ToString('N') + '.json')
            $code = curl.exe -s -w '%{http_code}' --max-time 120 @CommonCurlArgs -o $temp "https://$endpoint/api/interpreter?data=$encoded"

            if ($LASTEXITCODE -eq 0 -and $code -eq '200') {
                try {
                    $parsed = Get-Content -LiteralPath $temp -Raw -Encoding UTF8 | ConvertFrom-Json
                    Remove-Item -LiteralPath $temp -Force -ErrorAction SilentlyContinue
                    return $parsed
                } catch {
                    $lastError = "$endpoint returned invalid JSON"
                }
            } else {
                $lastError = "$endpoint returned http=$code"
            }

            Remove-Item -LiteralPath $temp -Force -ErrorAction SilentlyContinue
            Write-Host ("  [{0}] {1} failed: {2}" -f $Label, $endpoint, $lastError) -ForegroundColor DarkYellow
        }

        if ($attempt -lt $Retry) {
            Write-Host ("  [{0}] all endpoints failed, waiting {1}s before retry {2}/{3}" -f $Label, $RetryDelaySeconds, ($attempt + 1), $Retry) -ForegroundColor Yellow
            Start-Sleep -Seconds $RetryDelaySeconds
        }
    }

    throw "Overpass query '$Label' failed after $Retry attempts: $lastError"
}

# ---------------------------------------------------------------------------
# Helper mode: find candidate relations (both directions show up, plus环线 duplicates)
# ---------------------------------------------------------------------------
if ($PSCmdlet.ParameterSetName -eq 'Find') {
    $query = "[out:json][timeout:90];rel[`"route`"=`"subway`"][`"name`"~`"$FindRelation`"]($Bbox);out ids;"
    $json = Invoke-Overpass -Query $query -Label 'find'

    if (@($json.elements).Count -eq 0) {
        Write-Host "No subway relation matched '$FindRelation' in $Bbox" -ForegroundColor Red
        exit 1
    }

    Write-Host ("{0} candidate relation(s) for '{1}':" -f @($json.elements).Count, $FindRelation) -ForegroundColor Cyan
    foreach ($element in $json.elements) {
        Write-Host ("  {0}  https://www.openstreetmap.org/relation/{0}" -f $element.id)
    }
    Write-Host ''
    Write-Host 'Fetch the stops of the right one with:' -ForegroundColor DarkGray
    Write-Host ("  .\Get-OsmLineStations.ps1 <relationId> -OutFile <path>") -ForegroundColor DarkGray
    return
}

# ---------------------------------------------------------------------------
# Pass 1: ordered stop members of the relation
# ---------------------------------------------------------------------------
Write-Host "Fetching relation $RelationId members ..." -ForegroundColor Cyan
$relationJson = Invoke-Overpass -Query "[out:json][timeout:90];rel($RelationId);out body;" -Label 'relation'

if (@($relationJson.elements).Count -eq 0) {
    throw "relation $RelationId returned no data. A stub instance (e.g. overpass.osm.ch, Switzerland only) answers 200 with an empty result set."
}

$relation = $relationJson.elements[0]
$stopMembers = @($relation.members | Where-Object { $_.type -eq 'node' })

if ($stopMembers.Count -eq 0) {
    throw "relation $RelationId has no node members; it may not be a subway route relation."
}

Write-Host ("  relation : {0}" -f $relation.tags.name) -ForegroundColor Green
Write-Host ("  route    : {0}   ref={1}" -f $relation.tags.route, $relation.tags.ref) -ForegroundColor Green
Write-Host ("  from/to  : {0} -> {1}" -f $relation.tags.from, $relation.tags.to) -ForegroundColor Green
Write-Host ("  stop members: {0}" -f $stopMembers.Count) -ForegroundColor Green

# ---------------------------------------------------------------------------
# Pass 2: coordinates, in batches
# ---------------------------------------------------------------------------
Start-Sleep -Seconds $ThrottleSeconds

$nodeById = @{}
for ($offset = 0; $offset -lt $stopMembers.Count; $offset += $BatchSize) {
    $end = [math]::Min($offset + $BatchSize - 1, $stopMembers.Count - 1)
    $batch = @($stopMembers[$offset..$end])
    $ids = ($batch | ForEach-Object { $_.ref }) -join ','

    Write-Host ("Resolving nodes {0}..{1} of {2} ..." -f ($offset + 1), ($end + 1), $stopMembers.Count) -ForegroundColor Cyan
    $nodeJson = Invoke-Overpass -Query "[out:json][timeout:90];node(id:$ids);out body;" -Label "nodes[$offset]"

    foreach ($element in $nodeJson.elements) {
        $nodeById[[string]$element.id] = $element
    }

    if ($end -lt $stopMembers.Count - 1) {
        Start-Sleep -Seconds $ThrottleSeconds
    }
}

$stops = New-Object System.Collections.Generic.List[object]
$index = 0
$missing = 0

foreach ($member in $stopMembers) {
    $index++
    $node = $nodeById[[string]$member.ref]
    if (-not $node) {
        Write-Warning ("stop #{0} (node {1}) missing from the node response" -f $index, $member.ref)
        $missing++
        continue
    }

    $stops.Add([pscustomobject]@{
            index     = $index
            osmNodeId = [string]$node.id
            role      = $member.role
            name      = $node.tags.name
            lat       = $node.lat
            lng       = $node.lon
        })
}

if ($missing -gt 0) {
    Write-Warning "$missing stop(s) could not be resolved; review before generating the line JSON."
}

$result = [pscustomobject]@{
    relationId   = [string]$RelationId
    relationName = $relation.tags.name
    ref          = $relation.tags.ref
    from         = $relation.tags.from
    to           = $relation.tags.to
    fetchedAt    = (Get-Date).ToString('yyyy-MM-dd')
    stopCount    = $stops.Count
    stops        = $stops
}

if (-not $OutFile) {
    $OutFile = Join-Path $PSScriptRoot ("osm-$RelationId.json")
}

# Never write JSON with a UTF-8 BOM: the app parses these files as plain UTF-8.
$json = $result | ConvertTo-Json -Depth 8
[System.IO.File]::WriteAllText($OutFile, $json, (New-Object System.Text.UTF8Encoding($false)))

Write-Host ''
Write-Host ("Wrote {0} stops to {1}" -f $stops.Count, $OutFile) -ForegroundColor Green
Write-Host 'Next: review station names/order, then run New-LineJson.ps1' -ForegroundColor DarkGray