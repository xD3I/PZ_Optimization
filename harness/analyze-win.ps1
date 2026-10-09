# Route-only frame statistics and validity evidence. Invalid/missing runs are never ranked.
# Usage: harness/analyze-win.ps1 <run-dir> [<run-dir> ...]; -PassThru returns objects to sweep-win.ps1.
[CmdletBinding()]
param(
  [Parameter(Mandatory = $true, ValueFromRemainingArguments = $true)][string[]]$Runs,
  [switch]$PassThru
)
$ErrorActionPreference = 'Stop'
function Pct([double[]]$Sorted, [double]$P) {
  if (-not $Sorted.Count) { return $null }
  $k = ($Sorted.Count - 1) * $P / 100; $lo = [int][math]::Floor($k); $hi = [math]::Min($lo + 1,$Sorted.Count - 1)
  return $Sorted[$lo] + ($Sorted[$hi] - $Sorted[$lo]) * ($k - $lo)
}
function Read-Values([string]$Path) {
  $values = @{}
  if (Test-Path -LiteralPath $Path) {
    foreach ($line in [IO.File]::ReadLines($Path)) { if ($line -match '^([^=]+)=(.*)$') { $values[$matches[1]] = $matches[2] } }
  }
  return $values
}
function Summarize([string]$Run) {
  $reasons = [Collections.Generic.List[string]]::new(); $warnings = [Collections.Generic.List[string]]::new()
  $r = [ordered]@{ run = (Split-Path -Leaf $Run); directory = $Run; valid = $false; invalid_reasons = @(); warnings = @() }
  $bench = Read-Values (Join-Path $Run 'pzopt-bench.out'); $r.scenario = $bench
  $metaPath = Join-Path $Run 'run.json'; $meta = $null
  if (Test-Path -LiteralPath $metaPath) {
    $meta = Get-Content -LiteralPath $metaPath -Raw | ConvertFrom-Json
    $r.requested = $meta.requested; $r.environment_fingerprint = $meta.environment.fingerprint
    $r.cpu = $meta.cpu; $r.cache_policy = $meta.cache_policy
    if ($meta.status -ne 'collected') { $reasons.Add("run status: $($meta.status) ($($meta.error))") }
    if ($meta.timed_out) { $reasons.Add('launched JVM timed out') }
    if ($meta.exit_code -ne 0) { $reasons.Add("JVM exit code: $($meta.exit_code)") }
    if ($meta.environment_unchanged -ne $true) { $reasons.Add('installed build, fixture, mod or seed changed during run') }
  } else { $reasons.Add('missing run.json; no setting/focus/environment provenance') }
  if ($bench['route_status'] -ne 'complete') { $reasons.Add("route not complete: $($bench['route_status']) $($bench['route_reason'])") }
  [double]$routeSeconds = 0; [long]$t0 = 0; [long]$t1 = 0
  $numbers = [Globalization.CultureInfo]::InvariantCulture
  if (-not [double]::TryParse($bench['route_seconds'],[Globalization.NumberStyles]::Float,$numbers,[ref]$routeSeconds) -or $routeSeconds -le 0) { $reasons.Add('missing/invalid measured route_seconds') }
  if (-not [long]::TryParse($bench['route_start_epoch_ms'],[ref]$t0) -or -not [long]::TryParse($bench['route_end_epoch_ms'],[ref]$t1) -or $t1 -le $t0) { $reasons.Add('missing/invalid route epoch bounds') }
  if ($meta -and $routeSeconds -gt 0 -and [math]::Abs($routeSeconds - $meta.requested.route_seconds) -gt [math]::Max(1.0,0.05 * $meta.requested.route_seconds)) { $reasons.Add("route duration $routeSeconds differs from expected $($meta.requested.route_seconds)s") }
  $r.route_status = $bench['route_status']; $r.route_seconds = $routeSeconds
  $r.zoom = $bench['zoom']; $r.resolution = $bench['resolution']; $r.chunks_loaded = $bench['chunks_loaded']
  $r.settings = $bench['settings']; $r.core_placement = $bench['core_placement']
  $actual = [ordered]@{}
  foreach ($key in @('frameThreads','charDrawThreads','corePlacement','fileThreads','workers')) {
    if ($bench['settings'] -match ("(?:^|\s)" + [regex]::Escape($key) + '=([^\s]+)')) { $actual[$key] = $matches[1] }
  }
  $consolePath = Join-Path $Run 'console.txt'
  $console = if (Test-Path -LiteralPath $consolePath) { [IO.File]::ReadAllText($consolePath) } else { '' }
  if ($console -match 'frameThreads: (\d+) worker threads') { $actual['frame_workers'] = [int]$matches[1] }
  if ($console -match 'charDrawPrep: (\d+) threads for') { $actual['draw_workers'] = [int]$matches[1] }
  $r.actual = $actual
  if ($meta) {
    foreach ($key in @('frameThreads','charDrawThreads')) {
      $requested = $meta.requested.properties.$key
      if ($null -ne $requested) {
        if (-not $actual.Contains($key) -or $actual[$key] -ne [string]$requested) { $reasons.Add("actual $key=$($actual[$key]) differs from requested $requested") }
        $workerKey = if ($key -eq 'frameThreads') { 'frame_workers' } else { 'draw_workers' }
        if (-not $actual.Contains($workerKey)) { $reasons.Add("missing actual $workerKey startup evidence") }
        elseif ($actual[$workerKey] -ne [int]$requested) { $reasons.Add("$key was clamped: requested $requested, effective $($actual[$workerKey])") }
      }
    }
    if ($meta.requested.properties.corePlacement) {
      $placement = [string]$meta.requested.properties.corePlacement
      if ($bench['core_placement'] -notmatch ("\bcores=" + [regex]::Escape($placement) + '\b')) { $reasons.Add("missing actual placement mode $placement") }
      if ($placement -eq 'dual-ccd' -and ($bench['core_placement'] -match 'inactive|unsupported|failed|unavailable' -or $bench['core_placement'] -notmatch 'dual-ccd')) { $reasons.Add('dual-CCD opt-in did not activate; do not compare an affinity no-op') }
    }
    foreach ($key in @('route','start')) {
      $requested = $meta.requested.flags.$key
      if ($null -ne $requested -and $bench[$key] -ne [string]$requested) { $reasons.Add("measured $key differs from requested route") }
    }
    if ($meta.requested.flags.speed -and [double]$bench['speed'] -ne [double]$meta.requested.flags.speed) { $reasons.Add('measured route speed differs') }
    if ($meta.requested.flags.zoom -eq 'max' -and $bench['zoom'] -ne $bench['max_zoom']) { $reasons.Add('route was not measured at max zoom') }
  }
  # No warm-up fallback: markers are mandatory for a measured route.
  $frames = [Collections.Generic.List[double]]::new(); $inside = $false; $startCount = 0; $endCount = 0
  $framePath = Join-Path $Run 'pzopt-frames.out'
  if (Test-Path -LiteralPath $framePath) {
    foreach ($line in [IO.File]::ReadLines($framePath)) {
      if ($line -match '^#\s+route-start\b') { $startCount++; $inside = $true; continue }
      if ($line -match '^#\s+route-end\b') { $endCount++; $inside = $false; continue }
      if (-not $inside -or $line.StartsWith('#') -or -not $line.Trim()) { continue }
      [long]$value = 0
      if ([long]::TryParse($line,[ref]$value) -and $value -gt 0) { $frames.Add($value) } else { $reasons.Add('invalid route frame sample') }
    }
  }
  if ($startCount -ne 1 -or $endCount -ne 1 -or $inside) { $reasons.Add("route markers missing/ambiguous ($startCount start, $endCount end)") }
  if (-not $frames.Count) { $reasons.Add('no measured route frames') }
  else {
    $sorted = [double[]]($frames | Sort-Object); $seconds = ($sorted | Measure-Object -Sum).Sum / 1e6
    $r.frames = [ordered]@{
      count = $sorted.Count; seconds = [math]::Round($seconds,3); fps_mean = [math]::Round($sorted.Count / $seconds,2)
      mean_ms = [math]::Round(($sorted | Measure-Object -Average).Average / 1000,3)
      p50_ms = [math]::Round((Pct $sorted 50) / 1000,3); p90_ms = [math]::Round((Pct $sorted 90) / 1000,3)
      p99_ms = [math]::Round((Pct $sorted 99) / 1000,3); p99_9_ms = [math]::Round((Pct $sorted 99.9) / 1000,3); max_ms = [math]::Round($sorted[-1] / 1000,3)
      over_33ms = @($sorted | Where-Object { $_ -gt 33333 }).Count; over_100ms = @($sorted | Where-Object { $_ -gt 100000 }).Count
    }
    if ($routeSeconds -gt 0 -and [math]::Abs($seconds - $routeSeconds) -gt [math]::Max(1.0,0.1 * $routeSeconds)) { $reasons.Add('frame window duration disagrees with measured route duration') }
    if ($frames.Count -lt 10000) { $warnings.Add('p99.9 has fewer than ten tail samples; repeat and retain individual runs') }
  }
  $r.focus = [ordered]@{ samples = 0; unfocused_samples = 0; max_gap_ms = $null }
  $focusPath = Join-Path $Run 'focus.csv'
  if ((Test-Path -LiteralPath $focusPath) -and $t1 -gt $t0) {
    $allFocus = @(Import-Csv -LiteralPath $focusPath)
    $rows = @($allFocus | Where-Object { [long]$_.epoch_ms -ge $t0 -and [long]$_.epoch_ms -le $t1 })
    $r.focus.samples = $rows.Count; $r.focus.unfocused_samples = @($rows | Where-Object { $_.focused -ne '1' }).Count
    $last = $t0; $gap = 0L
    foreach ($row in $rows) { $gap = [math]::Max($gap,[long]$row.epoch_ms - $last); $last = [long]$row.epoch_ms }
    $r.focus.max_gap_ms = [math]::Max($gap,$t1 - $last)
    if ($r.focus.unfocused_samples) { $reasons.Add('game lost foreground focus during route') }
    if (-not $rows.Count -or $r.focus.max_gap_ms -gt 2000) { $reasons.Add('route focus evidence missing or sampled gap exceeds 2s') }
  } else { $reasons.Add('missing route focus evidence') }
  [int]$zombies = 0
  if (-not [int]::TryParse($bench['zombies_loaded'],[ref]$zombies) -or $zombies -lt 0) { $reasons.Add('missing zombie count') }
  $r.zombies_loaded = $zombies; $r.crowd_spawned = $null; $r.zombies_at_route_start = $null
  if ($console -match 'harness: route start \(zombies loaded: (\d+)\)') { $r.zombies_at_route_start = [int]$matches[1] }
  if ($console -match 'harness: crowd: (\d+) zombies spawned') { $r.crowd_spawned = [int]$matches[1] }
  if ($meta -and $meta.requested.flags.crowd -and $r.crowd_spawned -ne [int]$meta.requested.flags.crowd) { $reasons.Add('seeded crowd did not spawn the requested number') }
  $r.waiting = [ordered]@{ scope = 'cumulative since boot; not route deltas'; frame = $null; draw = $null; raw_batches = $bench['zombie_batches'] }
  if ($bench['zombie_batches'] -match 'frame batches=(\d+) tasks=(\d+) work ms=(\d+) wait ms=(\d+) async=(\d+) helped=(\d+) async join ms=(\d+)') {
    $r.waiting.frame = [ordered]@{ batches = [long]$matches[1]; tasks = [long]$matches[2]; work_ms = [long]$matches[3]; wait_ms = [long]$matches[4]; async_batches = [long]$matches[5]; helped = [long]$matches[6]; async_join_ms = [long]$matches[7] }
  } else { $warnings.Add('frame wait counters unavailable') }
  $draw = [regex]::Match([string]$bench['char_draw'],'^char draw: frames=(\d+)[^\r\n]*?walk waits=(\d+)/(\d+) ms start ms=(\d+) join waits=(\d+) join ms=(\d+)( FAILED)?')
  if ($draw.Success) {
    $r.waiting.draw = [ordered]@{ scope = 'cumulative since boot at route end'; frames = [long]$draw.Groups[1].Value; walk_waits = [long]$draw.Groups[2].Value; walk_wait_ms = [long]$draw.Groups[3].Value; start_ms = [long]$draw.Groups[4].Value; join_waits = [long]$draw.Groups[5].Value; join_ms = [long]$draw.Groups[6].Value }
    if ($draw.Groups[7].Success) { $reasons.Add('character draw pre-pass failed') }
  } else { $warnings.Add('character draw wait counters unavailable (no route-end snapshot)') }
  if ($console -match 'charDrawPrep:.*failed|overrides disabled|guard.*mismatch') { $reasons.Add('console reports worker/override fallback') }
  $threadPath = Join-Path $Run 'pzopt-threads.out'
  if (Test-Path -LiteralPath $threadPath) { $r.threads = @([IO.File]::ReadAllLines($threadPath)) } else { $warnings.Add('route per-thread CPU output missing') }
  $sysPath = Join-Path $Run 'sysmon.csv'
  if ((Test-Path -LiteralPath $sysPath) -and $t1 -gt $t0) {
    $rows = @(Import-Csv -LiteralPath $sysPath | Where-Object { [long]$_.epoch_ms -ge $t0 -and [long]$_.epoch_ms -le $t1 })
    $r.sysmon = [ordered]@{ samples = $rows.Count }
    foreach ($key in @('cpu_pct','cpu_busiest_core_pct','game_cpu_pct','gpu_pct','gpu_sm_mhz','gpu_w','vram_mib')) {
      $values = @($rows | Where-Object { $_.$key -ne '' } | ForEach-Object { [double]::Parse($_.$key,$numbers) })
      $r.sysmon[$key] = if ($values.Count) { [math]::Round(($values | Measure-Object -Average).Average,2) } else { $null }
    }
  }
  $chunkPath = Join-Path $Run 'pzopt-chunks.out'
  if (Test-Path -LiteralPath $chunkPath) {
    $lines = [IO.File]::ReadAllLines($chunkPath)
    if ($lines.Count -gt 1) {
      $marks = @{}; $data = [Collections.Generic.List[string]]::new(); $data.Add($lines[0])
      foreach ($line in $lines | Select-Object -Skip 1) {
        if ($line -match '^#\s+(route-start|route-end)\s+(\d+)') { $marks[$matches[1]] = [long]$matches[2] }
        elseif (-not $line.StartsWith('#')) { $data.Add($line) }
      }
      if ($marks.ContainsKey('route-start') -and $marks.ContainsKey('route-end')) {
        $rows = @($data | ConvertFrom-Csv -Delimiter "`t" | Where-Object { [long]$_.tEnqueueUs -ge $marks['route-start'] -and [long]$_.tEnqueueUs -le $marks['route-end'] })
        $r.chunks = [ordered]@{ count = $rows.Count }
        foreach ($key in @('loadUs','recalcUs','queueWaitUs','recalcWaitUs')) {
          $values = [double[]]@($rows | ForEach-Object { [double]$_.$key } | Sort-Object)
          if ($values.Count) { $r.chunks[$key] = [ordered]@{ mean_ms = [math]::Round(($values | Measure-Object -Average).Average / 1000,3); p99_ms = [math]::Round((Pct $values 99) / 1000,3) } }
        }
      }
    }
  }
  if (@(Get-ChildItem -LiteralPath $Run -Filter 'hs_err_pid*.log').Count) { $reasons.Add('JVM crash log present') }
  $r.invalid_reasons = @($reasons | Select-Object -Unique); $r.warnings = @($warnings); $r.valid = $r.invalid_reasons.Count -eq 0
  return [pscustomobject]$r
}
foreach ($run in $Runs) {
  $resolved = (Resolve-Path -LiteralPath $run).Path
  try { $summary = Summarize $resolved }
  catch { $summary = [pscustomobject]@{ run = (Split-Path -Leaf $resolved); directory = $resolved; valid = $false; invalid_reasons = @("malformed/incomplete evidence: $($_.Exception.Message)") } }
  $json = $summary | ConvertTo-Json -Depth 10
  $json | Set-Content -LiteralPath (Join-Path $resolved 'analysis.json') -Encoding UTF8
  if ($PassThru) { $summary } else { $json }
}
