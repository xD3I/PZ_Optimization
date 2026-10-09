# Interleaved worker/placement sweep, changing one worker-count axis at a time.
# All runs use the same read-only option seed, installed-build fingerprint and archived fixture.
[CmdletBinding()]
param(
  [string]$PZ = 'C:\Program Files (x86)\Steam\steamapps\common\ProjectZomboid',
  [string]$Classes = '',
  [string]$Label = '9950x3d',
  [string[]]$FrameThreads = @('2','4','6','8'),
  [string[]]$CharDrawThreads = @('2','4','6','8','12','14'),
  [ValidateRange(1,256)][int]$BaselineFrameThreads = 8,
  [ValidateRange(1,256)][int]$BaselineCharDrawThreads = 14,
  [ValidateSet('dual-ccd','off')][string[]]$CorePlacement = @('off','dual-ccd'),
  [ValidateRange(2,100)][int]$Repeats = 3,
  [ValidateSet('louisville','crowd')][string]$Scenario = 'louisville',
  [ValidateRange(1,600)][int]$Crowd = 40,
  [ValidateRange(0,100)][double]$ZombieTolerancePct = 10,
  [string[]]$Prop = @(),
  [string]$SeedDir = (Join-Path $env:USERPROFILE 'Zomboid'),
  [string]$OutputRoot = (Join-Path $env:LOCALAPPDATA 'pzopt-benchmark\runs'),
  [ValidateRange(30,7200)][int]$TimeoutSeconds = 600,
  [switch]$PrepareOnly
)
$ErrorActionPreference = 'Stop'
function Counts([string[]]$InputValues) {
  $counts = @($InputValues | ForEach-Object { $_ -split ',' } | ForEach-Object {
    [int]$n = 0
    if (-not [int]::TryParse($_,[ref]$n) -or $n -lt 1 -or $n -gt 256) { throw "invalid worker count: $_" }
    $n
  } | Sort-Object -Unique)
  if (-not $counts.Count) { throw 'worker-count lists must not be empty' }
  return $counts
}
function Median($Values) {
  $v = @($Values | Where-Object { $null -ne $_ } | Sort-Object)
  if (-not $v.Count) { return $null }
  $lo = [int][math]::Floor(($v.Count - 1) / 2); $hi = [int][math]::Ceiling(($v.Count - 1) / 2)
  return ([double]$v[$lo] + [double]$v[$hi]) / 2
}
if ($Label -notmatch '^[A-Za-z0-9][A-Za-z0-9_.-]*$') { throw 'Label must be a safe filename component' }
$frameCounts = @(Counts $FrameThreads); $drawCounts = @(Counts $CharDrawThreads)
$CorePlacement = @($CorePlacement | Select-Object -Unique)
if (-not $CorePlacement.Count) { throw 'CorePlacement must include at least one mode' }
foreach ($entry in $Prop) {
  if ($entry -match '(?:^|,)(frameThreads|charDrawThreads|corePlacement)=') { throw 'use sweep parameters for frameThreads, charDrawThreads and corePlacement, not -Prop' }
}
$PZ = [IO.Path]::GetFullPath($PZ).TrimEnd('\','/')
if ($Classes) { $Classes = [IO.Path]::GetFullPath($Classes).TrimEnd('\','/') }
$SeedDir = [IO.Path]::GetFullPath($SeedDir).TrimEnd('\','/')
$OutputRoot = [IO.Path]::GetFullPath($OutputRoot).TrimEnd('\','/')
foreach ($protected in @($PZ,$SeedDir)) {
  if ($OutputRoot.Equals($protected,[StringComparison]::OrdinalIgnoreCase) -or $OutputRoot.StartsWith($protected + '\',[StringComparison]::OrdinalIgnoreCase)) { throw '-OutputRoot must be outside the installed game and seed/user profile' }
}
$seedOptions = Join-Path $SeedDir 'options.ini'
if (-not (Test-Path -LiteralPath $seedOptions)) { throw "seed options.ini missing: $seedOptions" }
$id = "$Label-$Scenario-$(Get-Date -Format 'yyyyMMdd-HHmmss-fff')-$([Guid]::NewGuid().ToString('N').Substring(0,8))"
$out = Join-Path $OutputRoot $id
if (Test-Path -LiteralPath $out) { throw "sweep output conflict: $out" }
New-Item -ItemType Directory -Path $out,(Join-Path $out 'seed\pzopt'),(Join-Path $out 'runs') | Out-Null
$seed = Join-Path $out 'seed'
foreach ($rel in @('options.ini','pzopt\options.ini','pzopt\framecap.ini')) {
  $source = Join-Path $SeedDir $rel
  if (Test-Path -LiteralPath $source -PathType Leaf) { Copy-Item -LiteralPath $source -Destination (Join-Path $seed $rel) }
}
[IO.File]::WriteAllText((Join-Path $seed 'owned-seed.txt'), "Option-only frozen seed for sweep $id; no user caches, mods or saves copied.`n")
# Duration is derived from route distance / speed (+ hold), not a nonexistent route_seconds flag.
$flags = if ($Scenario -eq 'louisville') {
  @('start=12450,1280','population=max','settle=20','route=S:150','speed=6','turn=90','zoom=max')
} else {
  @('start=12450,1280','population=0','settle=20','route=S:1','speed=1','hold=24','turn=0','zoom=max',"crowd=$Crowd")
}
$points = [Collections.Generic.List[object]]::new(); $seen = @{}
foreach ($n in (@($BaselineFrameThreads) + $frameCounts)) {
  $key = "f$n-d$BaselineCharDrawThreads"
  if (-not $seen.ContainsKey($key)) { $points.Add([pscustomobject]@{ key = $key; frame = $n; draw = $BaselineCharDrawThreads; axis = 'frame' }); $seen[$key] = $true }
}
foreach ($n in $drawCounts) {
  $key = "f$BaselineFrameThreads-d$n"
  if (-not $seen.ContainsKey($key)) { $points.Add([pscustomobject]@{ key = $key; frame = $BaselineFrameThreads; draw = $n; axis = 'draw' }); $seen[$key] = $true }
}
$plan = [ordered]@{
  schema = 1; sweep_id = $id; game_install = $PZ; scenario = $Scenario; flags = $flags; expected_route_seconds = 25
  seed = $seed; cache_policy = 'fresh-private-cold'; repeats = $Repeats; placement = $CorePlacement; points = $points.ToArray()
  baseline_frame = $BaselineFrameThreads; baseline_draw = $BaselineCharDrawThreads; zombie_tolerance_pct = $ZombieTolerancePct
  order = 'rotate/reverse configurations per round; alternate adjacent placement pairs'; status = 'planned'; runs = @(); comparisons = @()
}
function Save-Plan {
  # A write failure must leave the previous evidence intact, not truncate sweep.json.
  $path = Join-Path $out 'sweep.json'
  $pending = "$path.pending-$PID"
  [IO.File]::WriteAllText($pending, ($plan | ConvertTo-Json -Depth 12), [Text.UTF8Encoding]::new($false))
  [IO.File]::Move($pending, $path, $true)
}
Save-Plan
$environment = ''; $results = [Collections.Generic.List[object]]::new()
try {
  if ($PrepareOnly) {
    $prepared = & (Join-Path $PSScriptRoot 'run-win.ps1') -PZ $PZ -Classes $Classes -Label 'preflight' -SeedDir $seed -OutputRoot (Join-Path $out 'runs') -Flag $flags -Prop (@('instrument=true') + $Prop) -Mode bench -RouteSeconds 25 -PrepareOnly
    $plan.status = 'prepared-only'; $plan.environment = $prepared.Environment; Save-Plan
    return [pscustomobject]@{ SweepDirectory = $out; PreparedOnly = $true }
  }
  for ($round = 0; $round -lt $Repeats; $round++) {
    $indices = @(0..($points.Count - 1) | ForEach-Object { ($_ + $round) % $points.Count })
    if ($round % 2) { [array]::Reverse($indices) }
    $placements = @($CorePlacement)
    if ($round % 2) { [array]::Reverse($placements) }
    foreach ($index in $indices) {
      $point = $points[$index]
      foreach ($placement in $placements) {
        $labelRun = "r$($round + 1)-$($point.key)-$placement"
        $bucket = Join-Path $out "runs\$labelRun"
        if (Test-Path -LiteralPath $bucket) { throw "owned run bucket conflict: $bucket" }
        New-Item -ItemType Directory -Path $bucket | Out-Null
        $props = @('instrument=true',"frameThreads=$($point.frame)","charDrawThreads=$($point.draw)","corePlacement=$placement") + $Prop
        $runFailure = $null; $runResult = $null
        try {
          $runResult = & (Join-Path $PSScriptRoot 'run-win.ps1') -PZ $PZ -Classes $Classes -Label $labelRun -SeedDir $seed -OutputRoot $bucket -Flag $flags -Prop $props -Mode bench -RouteSeconds 25 -TimeoutSeconds $TimeoutSeconds -ExpectedEnvironment $environment
        } catch { $runFailure = $_.Exception.Message; Write-Warning "$labelRun invalid: $runFailure" }
        $directories = @(Get-ChildItem -LiteralPath $bucket -Directory)
        if ($directories.Count -eq 1) {
          $summary = & (Join-Path $PSScriptRoot 'analyze-win.ps1') -Runs $directories[0].FullName -PassThru
          if (-not $environment -and $summary.environment_fingerprint) { $environment = $summary.environment_fingerprint }
        } else { $summary = [pscustomobject]@{ run = $labelRun; directory = $bucket; valid = $false; invalid_reasons = @($runFailure); environment_fingerprint = $environment } }
        $result = [pscustomobject]@{ round = $round + 1; axis = $point.axis; frame = $point.frame; draw = $point.draw; placement = $placement; analysis = $summary; comparison_valid = $summary.valid; comparison_reasons = @() }
        $results.Add($result); $plan.runs = $results.ToArray(); $plan.environment = $environment; $plan.status = 'running'; Save-Plan
        if ($runFailure -and $runFailure -match 'changed during|environment changed|game already running|game appeared|another benchmark|fixture requires|fixture dictionary|seed profile|terms screen|seed options') { throw $runFailure }
      }
    }
  }
  $valid = @($results | Where-Object { $_.analysis.valid })
  $baseline = @($valid | Where-Object { $_.frame -eq $BaselineFrameThreads -and $_.draw -eq $BaselineCharDrawThreads -and $_.placement -eq $CorePlacement[0] })
  if ($baseline.Count) {
    $reference = $baseline[0].analysis
    $zombieMedian = Median @($baseline | ForEach-Object { $_.analysis.zombies_loaded })
    $startMedian = Median @($baseline | ForEach-Object { $_.analysis.zombies_at_route_start })
    $plan.reference = [ordered]@{ placement = $CorePlacement[0]; zombies_loaded_median = $zombieMedian; zombies_at_route_start_median = $startMedian; zoom = $reference.zoom; resolution = $reference.resolution }
    foreach ($result in $valid) {
      $s = $result.analysis; $why = [Collections.Generic.List[string]]::new()
      if ($s.environment_fingerprint -ne $reference.environment_fingerprint) { $why.Add('different installed-build/fixture/option seed fingerprint') }
      if ($s.zoom -ne $reference.zoom -or $s.resolution -ne $reference.resolution) { $why.Add('different measured zoom or resolution') }
      if ([math]::Abs($s.zombies_loaded - $zombieMedian) -gt [math]::Max(1,$zombieMedian * $ZombieTolerancePct / 100)) { $why.Add('end zombie count outside baseline tolerance') }
      if ($null -eq $s.zombies_at_route_start) { $why.Add('missing route-start zombie count') }
      elseif ($null -ne $startMedian -and [math]::Abs($s.zombies_at_route_start - $startMedian) -gt [math]::Max(1,$startMedian * $ZombieTolerancePct / 100)) { $why.Add('route-start zombie count outside baseline tolerance') }
      if (-not $s.waiting.frame -or -not $s.waiting.draw) { $why.Add('missing frame/draw wait-counter evidence') }
      if (-not $s.threads -or $null -eq $s.sysmon.game_cpu_pct) { $why.Add('missing route thread/process CPU evidence') }
      $result.comparison_reasons = $why.ToArray(); $result.comparison_valid = $why.Count -eq 0
    }
  } else {
    foreach ($result in $results) { $result.comparison_valid = $false; $result.comparison_reasons = @('no valid reference configuration') }
  }
  $comparisons = @($results | Group-Object frame,draw,placement | ForEach-Object {
    $group = @($_.Group); $usable = @($group | Where-Object { $_.comparison_valid }); $first = $group[0]
    [pscustomobject]@{
      frame = $first.frame; draw = $first.draw; placement = $first.placement; valid_repeats = $usable.Count; invalid_repeats = $group.Count - $usable.Count
      eligible = $usable.Count -ge 2; fps_median = (Median @($usable | ForEach-Object { $_.analysis.frames.fps_mean }))
      p99_ms_median = (Median @($usable | ForEach-Object { $_.analysis.frames.p99_ms })); p99_9_ms_median = (Median @($usable | ForEach-Object { $_.analysis.frames.p99_9_ms }))
      frame_wait_ms_median = (Median @($usable | ForEach-Object { $_.analysis.waiting.frame.wait_ms })); frame_async_join_ms_median = (Median @($usable | ForEach-Object { $_.analysis.waiting.frame.async_join_ms }))
      draw_join_ms_median = (Median @($usable | ForEach-Object { $_.analysis.waiting.draw.join_ms })); game_cpu_pct_median = (Median @($usable | ForEach-Object { $_.analysis.sysmon.game_cpu_pct }))
      zombies_loaded_median = (Median @($usable | ForEach-Object { $_.analysis.zombies_loaded })); zombies_start_median = (Median @($usable | ForEach-Object { $_.analysis.zombies_at_route_start }))
      wait_counter_scope = 'cumulative since boot at route end; inspect individual runs and route thread CPU'
    }
  })
  $referenceGroup = @($comparisons | Where-Object { $_.frame -eq $BaselineFrameThreads -and $_.draw -eq $BaselineCharDrawThreads -and $_.placement -eq $CorePlacement[0] -and $_.eligible })
  foreach ($c in $comparisons) {
    if (-not $referenceGroup.Count) { $c.eligible = $false }
    $fpsDelta = $null; $p99Gain = $null; $p999Gain = $null
    if ($c.eligible) {
      $b = $referenceGroup[0]
      $fpsDelta = [math]::Round(100 * ($c.fps_median / $b.fps_median - 1),2)
      $p99Gain = [math]::Round(100 * (1 - $c.p99_ms_median / $b.p99_ms_median),2)
      $p999Gain = [math]::Round(100 * (1 - $c.p99_9_ms_median / $b.p99_9_ms_median),2)
    }
    $c | Add-Member -NotePropertyName fps_delta_pct -NotePropertyValue $fpsDelta
    $c | Add-Member -NotePropertyName p99_improvement_pct -NotePropertyValue $p99Gain
    $c | Add-Member -NotePropertyName p99_9_improvement_pct -NotePropertyValue $p999Gain
  }
  $plan.comparisons = $comparisons; $plan.status = 'complete'; Save-Plan
  $comparisons | Export-Csv -LiteralPath (Join-Path $out 'comparison.csv') -NoTypeInformation -Encoding UTF8
  $comparisons | Format-Table frame,draw,placement,valid_repeats,invalid_repeats,eligible,fps_median,p99_ms_median,p99_9_ms_median -AutoSize | Out-Host
  Write-Host "Results: $out\sweep.json and comparison.csv; invalid runs remain recorded and are excluded."
} catch {
  $original = $_
  $plan.status = 'failed'; $plan.error = $original.Exception.Message; $plan.runs = $results.ToArray()
  try { Save-Plan } catch { Write-Warning "could not persist failed sweep: $($_.Exception.Message)" }
  throw $original
}
[pscustomobject]@{ SweepDirectory = $out; PreparedOnly = $false }
