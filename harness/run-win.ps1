# Repeatable Windows game benchmark, using a fresh owned profile and the installed bundled JVM.
# No user saves, mods, caches, options, flags, logs, launcher JSON or installed classes are changed.
# -PrepareOnly performs preflight/fixture preparation without starting the game.
[CmdletBinding()]
param(
  [Parameter(Mandatory = $true)][ValidatePattern('^[A-Za-z0-9][A-Za-z0-9_.-]*$')][string]$Label,
  [ValidateSet('bench','drive','verify')][string]$Mode = 'bench',
  [string[]]$Flag = @(),
  [string[]]$Prop = @('instrument=true'),
  # Expected measured duration only; bench motion is controlled by Flag route/speed/hold.
  [ValidateRange(1,3600)][int]$RouteSeconds = 100,
  [string]$QuitAfter = '',
  [switch]$Dashboard,
  [string]$DashboardMod = '',
  [string]$PZ = 'C:\Program Files (x86)\Steam\steamapps\common\ProjectZomboid',
  # Optional read-only development classes before installed "." and game jar; no install needed.
  [string]$Classes = '',
  [string]$SeedDir = (Join-Path $env:USERPROFILE 'Zomboid'),
  [string]$OutputRoot = (Join-Path $env:LOCALAPPDATA 'pzopt-benchmark\runs'),
  [ValidateRange(30,7200)][int]$TimeoutSeconds = 600,
  [string]$ExpectedEnvironment = '',
  [switch]$PrepareOnly
)
$ErrorActionPreference = 'Stop'
$PZ = [IO.Path]::GetFullPath($PZ).TrimEnd('\','/')
$SeedDir = [IO.Path]::GetFullPath($SeedDir).TrimEnd('\','/')
$OutputRoot = [IO.Path]::GetFullPath($OutputRoot).TrimEnd('\','/')
$ModSrc = Join-Path $PSScriptRoot 'mod\pzopt-harness'
$Archive = Join-Path $PSScriptRoot 'bench-save\pzopt-bench-template.tar.zst'
$Java = Join-Path $PZ 'jre64\bin\java.exe'
$LauncherPath = Join-Path $PZ 'ProjectZomboid64.json'
if ($Classes) { $Classes = [IO.Path]::GetFullPath($Classes).TrimEnd('\','/') }
$SeedFiles = @('options.ini','pzopt\options.ini','pzopt\framecap.ini')

function Hash-Text([string]$Text) {
  $sha = [Security.Cryptography.SHA256]::Create()
  try { return ([BitConverter]::ToString($sha.ComputeHash([Text.Encoding]::UTF8.GetBytes($Text)))).Replace('-','').ToLowerInvariant() }
  finally { $sha.Dispose() }
}
function Hash-File([string]$Path) {
  if (Test-Path -LiteralPath $Path -PathType Leaf) { return (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant() }
  return 'absent'
}
function Read-Assignments([string[]]$Lines) {
  $values = [ordered]@{}
  foreach ($line in $Lines) {
    # Split -File comma-joined arguments only at a new key; keep coordinates/routes intact.
    foreach ($entry in ($line -split ',(?=[A-Za-z_][A-Za-z0-9_.]*=)')) {
      if ($entry -notmatch '^([A-Za-z_][A-Za-z0-9_.]*)=([^\r\n]*)$') { throw "expected key=value, got: $entry" }
      $key = $matches[1]; $value = $matches[2]
      if ($values.Contains($key)) { throw "duplicate key: $key" }
      $values[$key] = $value
    }
  }
  return ,$values
}
function Quote-Argument([string]$Value) {
  if ($Value -notmatch '[\s"]') { return $Value }
  $b = [Text.StringBuilder]::new(); [void]$b.Append('"'); $slashes = 0
  foreach ($ch in $Value.ToCharArray()) {
    if ($ch -eq '\') { $slashes++; continue }
    if ($ch -eq '"') { [void]$b.Append(('\' * (2 * $slashes + 1))); [void]$b.Append('"'); $slashes = 0; continue }
    if ($slashes) { [void]$b.Append(('\' * $slashes)); $slashes = 0 }
    [void]$b.Append($ch)
  }
  if ($slashes) { [void]$b.Append(('\' * (2 * $slashes))) }
  [void]$b.Append('"'); return $b.ToString()
}
function Get-InstallProcesses {
  $candidates = @(Get-CimInstance Win32_Process -Filter "Name='ProjectZomboid64.exe' OR Name='ProjectZomboid32.exe' OR Name='java.exe' OR Name='javaw.exe'")
  foreach ($p in $candidates) {
    $isGame = $p.Name -like 'ProjectZomboid*' -or $p.CommandLine -match 'zombie[./]gameStates[./]MainScreenState'
    if (-not $isGame) { continue }
    if (-not $p.ExecutablePath) { throw "cannot determine install location of game PID $($p.ProcessId); close it before benchmarking" }
    if ($p.ExecutablePath.StartsWith($PZ + '\', [StringComparison]::OrdinalIgnoreCase) -or
        ($p.CommandLine -and $p.CommandLine.IndexOf($PZ, [StringComparison]::OrdinalIgnoreCase) -ge 0)) { $p }
  }
}
function Get-EnvironmentSnapshot {
  $files = [ordered]@{}
  foreach ($rel in @('projectzomboid.jar','ProjectZomboid64.json','pzopt.properties','pzopt-installed.txt','pzopt-files.txt','jre64\bin\java.exe')) {
    $files["game/$rel"] = Hash-File (Join-Path $PZ $rel)
  }
  if ($Classes) {
    foreach ($f in (Get-ChildItem -LiteralPath $Classes -File -Recurse | Sort-Object FullName)) {
      $files['classes/' + $f.FullName.Substring($Classes.Length + 1)] = Hash-File $f.FullName
    }
  }
  foreach ($path in $installedClasspath | Select-Object -Skip 1) {
    if (-not $path.StartsWith($PZ + '\', [StringComparison]::OrdinalIgnoreCase) -or -not (Test-Path -LiteralPath $path -PathType Leaf)) {
      throw "benchmark classpath must use files inside the supplied game installation: $path"
    }
    $files['classpath/' + $path.Substring($PZ.Length + 1)] = Hash-File $path
  }
  $manifest = Join-Path $PZ 'pzopt-installed.txt'
  $fileList = Join-Path $PZ 'pzopt-files.txt'
  if (Test-Path -LiteralPath $manifest) {
    $installed = @(Get-Content -LiteralPath $manifest | Where-Object { $_ -and -not $_.StartsWith('#') } | ForEach-Object { ($_ -split '\s+',2)[0] })
  } elseif (Test-Path -LiteralPath $fileList) {
    $installed = @(Get-Content -LiteralPath $fileList | Where-Object { $_ -and -not $_.StartsWith('#') })
  } else { throw 'installed override manifest missing (pzopt-installed.txt or pzopt-files.txt); cannot freeze the installed build' }
  foreach ($rel in ($installed | Sort-Object -Unique)) {
    $path = [IO.Path]::GetFullPath((Join-Path $PZ $rel))
    if (-not $path.StartsWith($PZ + '\', [StringComparison]::OrdinalIgnoreCase)) { throw "unsafe installed manifest path: $rel" }
    $files["game/$rel"] = Hash-File $path
    if ($files["game/$rel"] -eq 'absent') { throw "installed manifest file missing: $path" }
  }
  $files['fixture'] = Hash-File $Archive
  foreach ($rel in $SeedFiles) { $files["seed/$rel"] = Hash-File (Join-Path $SeedDir $rel) }
  foreach ($f in (Get-ChildItem -LiteralPath $ModSrc -File -Recurse | Sort-Object FullName)) {
    $files['mod/' + $f.FullName.Substring($ModSrc.Length + 1)] = Hash-File $f.FullName
  }
  if ($Dashboard) {
    foreach ($f in (Get-ChildItem -LiteralPath $DashboardMod -File -Recurse | Sort-Object FullName)) {
      $files['dashboard/' + $f.FullName.Substring($DashboardMod.Length + 1)] = Hash-File $f.FullName
    }
  }
  return [ordered]@{ fingerprint = (Hash-Text ($files | ConvertTo-Json -Compress -Depth 3)); files = $files }
}
function Write-Metadata { $metadata | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $out 'run.json') -Encoding UTF8 }

if ($env:OS -ne 'Windows_NT') { throw 'run-win.ps1 requires Windows' }
foreach ($path in @($PZ,$Java,$LauncherPath,$Archive,$ModSrc,(Join-Path $PZ 'pzopt\Overrides.class'))) {
  if (-not (Test-Path -LiteralPath $path)) { throw "required path missing: $path" }
}
if ($Classes -and -not (Test-Path -LiteralPath (Join-Path $Classes 'pzopt\Overrides.class') -PathType Leaf)) {
  throw "development classes missing pzopt\Overrides.class: $Classes"
}
if ($OutputRoot.Equals($SeedDir, [StringComparison]::OrdinalIgnoreCase) -or
    $OutputRoot.StartsWith($SeedDir + '\', [StringComparison]::OrdinalIgnoreCase) -or
    $OutputRoot.Equals($PZ, [StringComparison]::OrdinalIgnoreCase) -or
    $OutputRoot.StartsWith($PZ + '\', [StringComparison]::OrdinalIgnoreCase)) { throw '-OutputRoot must be outside the installed game and seed/user profile' }
if ($env:JAVA_TOOL_OPTIONS -or $env:_JAVA_OPTIONS -or $env:JDK_JAVA_OPTIONS) { throw 'unset JAVA_TOOL_OPTIONS, _JAVA_OPTIONS and JDK_JAVA_OPTIONS before benchmarking; inherited JVM settings would invalidate comparison' }
$props = Read-Assignments $Prop
$flags = Read-Assignments $Flag
foreach ($reserved in @('consumed','started','launcher_read_only','mode','dashboard')) {
  if ($flags.Contains($reserved)) { throw "harness owns flag $reserved; use the corresponding script parameter" }
}
$props['instrument'] = 'true'; $props['updateCheck'] = 'false'; $props['devUpdateDrive'] = 'false'
$flags['mode'] = $Mode; $flags['dashboard'] = if ($Dashboard) { 'enabled' } else { 'disabled' }
$flags['launcher_read_only'] = '1'
if (-not $flags.Contains('settle')) { $flags['settle'] = '5' }
if ($QuitAfter) { $flags['quit_after'] = $QuitAfter }
if ($Dashboard) {
  if (-not $DashboardMod) { $DashboardMod = Join-Path $SeedDir 'mods\PZDashboard' }
  $DashboardMod = [IO.Path]::GetFullPath($DashboardMod).TrimEnd('\','/')
  if (-not (Test-Path -LiteralPath $DashboardMod -PathType Container)) { throw "-Dashboard requires a mod source directory (-DashboardMod): $DashboardMod" }
}
$launcher = Get-Content -LiteralPath $LauncherPath -Raw | ConvertFrom-Json
$installedClasspath = @($launcher.classpath | ForEach-Object { [IO.Path]::GetFullPath((Join-Path $PZ $_)) })
if (-not $installedClasspath.Count -or $installedClasspath[0].TrimEnd('\','/') -ne $PZ) { throw 'launcher classpath must begin with loose installed classes ("."); AOT-only launchers are not a comparable loose-build benchmark' }
$classpath = if ($Classes) { @($Classes) + $installedClasspath } else { $installedClasspath }
$vmArgs = @($launcher.vmArgs)
if ($launcher.windows) {
  $os = [Environment]::OSVersion.Version
  $variant = @($launcher.windows.PSObject.Properties | Where-Object { [version]$_.Name -le $os } | Sort-Object { [version]$_.Name } -Descending | Select-Object -First 1)
  if ($variant.Count) { $vmArgs += @($variant[0].Value.vmArgs) }
}
# Never record/use an AOT file in the installation or write a JVM crash dump there.
$vmArgs = @($vmArgs | Where-Object { $_ -notmatch '^-XX:(AOT|ArchiveClassesAtExit|SharedArchiveFile)|^-Xlog:.*file=' })
$mutex = [Threading.Mutex]::new($false, ('Local\pzopt-benchmark-' + (Hash-Text $PZ.ToLowerInvariant())))
$locked = $false; $proc = $null; $failure = $null; $out = $null
try {
  try { $locked = $mutex.WaitOne(0) } catch [Threading.AbandonedMutexException] { $locked = $true }
  if (-not $locked) { throw 'another benchmark owns this installation; no state was changed' }
  $existing = @(Get-InstallProcesses)
  if ($existing.Count) { throw "game already running from $PZ (PID(s) $($existing.ProcessId -join ', ')); no process was changed" }
  $snapshot = Get-EnvironmentSnapshot
  if ($ExpectedEnvironment -and $snapshot.fingerprint -ne $ExpectedEnvironment) { throw 'installed build, fixture, mod or seed settings changed during the sweep; refusing incomparable run' }
  $id = "$Label-$(Get-Date -Format 'yyyyMMdd-HHmmss-fff')-$([Guid]::NewGuid().ToString('N').Substring(0,8))"
  $out = Join-Path $OutputRoot $id
  if (Test-Path -LiteralPath $out) { throw "output conflict: $out" }
  New-Item -ItemType Directory -Path $out | Out-Null
  $profile = Join-Path $out 'profile'
  $metadata = [ordered]@{
    schema = 2; run_id = $id; game_install = $PZ; development_classes = $Classes; launcher = 'bundled-java-installed-json'; steam = 'off'
    profile = $profile; seed_dir = $SeedDir; cache_policy = 'fresh-private-cold'; environment = $snapshot
    requested = [ordered]@{ mode = $Mode; route_seconds = $RouteSeconds; flags = $flags; properties = $props
      private_option_overrides = [ordered]@{ showSurvivalGuide = 'false' } }
    cpu = @(Get-CimInstance Win32_Processor | Select-Object Name,NumberOfCores,NumberOfLogicalProcessors)
    os = [Environment]::OSVersion.VersionString; status = 'preparing'; error = $null
    started_epoch_ms = $null; ended_epoch_ms = $null; pid = $null; exit_code = $null; timed_out = $false
    environment_unchanged = $null; focus_interval_ms = 250
  }
  Write-Metadata
  New-Item -ItemType Directory -Path $profile,(Join-Path $profile 'Lua'),(Join-Path $profile 'mods'),(Join-Path $profile 'pzopt'),(Join-Path $profile 'Saves\Sandbox') | Out-Null
  [IO.File]::WriteAllText((Join-Path $profile 'owned-profile.txt'), "Owned only by run-win.ps1 invocation $id. Never used as a user profile.`n")
  foreach ($rel in $SeedFiles) {
    $source = Join-Path $SeedDir $rel
    if (Test-Path -LiteralPath $source -PathType Leaf) { Copy-Item -LiteralPath $source -Destination (Join-Path $profile $rel) }
  }
  $options = Join-Path $profile 'options.ini'
  if (-not (Test-Path -LiteralPath $options)) { throw "seed options.ini missing: $SeedDir; use a profile whose first-run screens were completed manually" }
  $optionText = [IO.File]::ReadAllText($options)
  if ($optionText -notmatch '(?m)^termsOfServiceVersion=1\r?$') { throw 'seed profile has not completed the native terms screen; the benchmark cannot accept legal terms for the user' }
  # The stock survival guide opens on every new profile and calls setGameSpeed(0).
  # Suppress it only in the owned copy; never change the player's options.ini.
  if ($optionText -notmatch '(?m)^showSurvivalGuide=(?:true|false)\r?$') { throw 'seed options.ini lacks showSurvivalGuide; cannot guarantee an unpaused benchmark' }
  [IO.File]::WriteAllText($options, [regex]::Replace($optionText, '(?m)^(showSurvivalGuide=)(?:true|false)(?=\r?$)', '${1}false'))
  Copy-Item -LiteralPath $ModSrc -Destination (Join-Path $profile 'mods\pzopt-harness') -Recurse
  if ($Dashboard) { Copy-Item -LiteralPath $DashboardMod -Destination (Join-Path $profile 'mods\PZDashboard') -Recurse }
  $modLines = @('VERSION = 1,','','mods','{','    mod = pzopt-harness,')
  if ($Dashboard) { $modLines += '    mod = PZDashboard,' }
  $modLines += @('}','','maps','{','}')
  [IO.File]::WriteAllLines((Join-Path $profile 'mods\default.txt'), $modLines)
  [IO.File]::WriteAllText((Join-Path $profile 'mods\reset-mods-42_00.txt'), 'Private B42 benchmark profile; no previous-version mods to reset.')
  # List before extraction: no absolute/traversing entries or links may escape the owned directory.
  $members = @(& tar -tf $Archive 2>&1)
  if ($LASTEXITCODE -ne 0) { throw "cannot list zstd benchmark fixture with tar: $($members -join ' ')" }
  foreach ($member in $members) {
    if ($member -notmatch '^pzopt-bench-template(/|$)' -or $member -match '(^|/)\.\.(/|$)|[:\\]') { throw "unsafe fixture member: $member" }
  }
  $listing = @(& tar -tvf $Archive 2>&1)
  if ($LASTEXITCODE -ne 0 -or @($listing | Where-Object { $_ -notmatch '^[-d]' }).Count) { throw 'fixture contains links/special entries or tar could not inspect it; extraction refused' }
  $sandbox = Join-Path $profile 'Saves\Sandbox'
  & tar -xf $Archive -C $sandbox
  if ($LASTEXITCODE -ne 0) { throw 'tar failed to extract fixture into the owned profile' }
  $bench = Join-Path $sandbox 'pzopt-bench-template'
  $fixtureMods = Join-Path $bench 'mods.txt'
  if (-not (Test-Path -LiteralPath $fixtureMods)) { throw 'fixture missing mods.txt' }
  $dependencies = @([regex]::Matches([IO.File]::ReadAllText($fixtureMods), '(?m)^\s*mod\s*=\s*([^,\r\n]+),') | ForEach-Object { $_.Groups[1].Value.Trim() })
  $unsupported = @($dependencies | Where-Object { $_ -notin @('pzopt-harness','PZDashboard') })
  if ($unsupported.Count) { throw "fixture requires unavailable mods: $($unsupported -join ', '); no user mod or save was touched" }
  $dictionary = Join-Path $bench 'WorldDictionaryReadable.lua'
  if (-not (Test-Path -LiteralPath $dictionary)) { throw 'fixture missing readable WorldDictionary; cannot establish mod independence' }
  $modIds = @([regex]::Matches([IO.File]::ReadAllText($dictionary), 'modID\s*=\s*"([^"]+)"') | ForEach-Object { $_.Groups[1].Value } | Sort-Object -Unique)
  $nonVanilla = @($modIds | Where-Object { $_ -ne 'pz-vanilla' })
  if ($nonVanilla.Count) { throw "fixture dictionary contains non-vanilla items from: $($nonVanilla -join ', '); a clean fixture is required" }
  [IO.File]::WriteAllLines($fixtureMods,$modLines)
  [IO.File]::WriteAllText((Join-Path $profile 'latestSave.ini'), "pzopt-bench-template`r`nSandbox`r`n")
  [IO.File]::WriteAllLines((Join-Path $profile 'Lua\pzopt-harness.txt'), @($flags.Keys | ForEach-Object { "$_=$($flags[$_])" }))
  [IO.File]::WriteAllLines((Join-Path $out 'pzopt.properties'), @($props.Keys | ForEach-Object { "$_=$($props[$_])" }))
  Copy-Item -LiteralPath $LauncherPath -Destination (Join-Path $out 'ProjectZomboid64.json')
  if ($PrepareOnly) { $metadata.status = 'prepared-only'; Write-Metadata; return [pscustomobject]@{ RunDirectory = $out; Environment = $snapshot.fingerprint; PreparedOnly = $true } }
  if ((Get-EnvironmentSnapshot).fingerprint -ne $snapshot.fingerprint) { throw 'environment changed while preparing fixture; launch refused' }
  if (@(Get-InstallProcesses).Count) { throw 'game appeared during preflight; launch refused' }
  if (-not ('PzOptBenchFocus' -as [type])) {
    Add-Type -TypeDefinition @'
using System;
using System.Runtime.InteropServices;
public static class PzOptBenchFocus {
 [DllImport("user32.dll")] public static extern IntPtr GetForegroundWindow();
 [DllImport("user32.dll")] public static extern uint GetWindowThreadProcessId(IntPtr window, out uint pid);
}
'@
  }
  $args = $vmArgs + @('-Dzomboid.steam=0',"-Dpzopt.userOptionsFile=$profile\pzopt\options.ini", "-Djava.library.path=$PZ\win64;$PZ", "-XX:ErrorFile=$out\hs_err_pid%p.log")
  $args += @($props.Keys | ForEach-Object { "-Dpzopt.$_=$($props[$_])" })
  $args += @('-cp',($classpath -join ';'),($launcher.mainClass -replace '/','.'),"-cachedir=$profile",'-nosteam')
  [IO.File]::WriteAllText((Join-Path $out 'command.txt'), "$Java " + (($args | ForEach-Object { Quote-Argument $_ }) -join ' ') + "`n")
  $metadata.status = 'running'; $metadata.started_epoch_ms = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds(); Write-Metadata
  Write-Host "launching private benchmark ($Mode) -> $out; keep the game focused during the route"
  $proc = Start-Process -FilePath $Java -WorkingDirectory $PZ -ArgumentList (($args | ForEach-Object { Quote-Argument $_ }) -join ' ') -RedirectStandardOutput (Join-Path $out 'stdout.log') -RedirectStandardError (Join-Path $out 'stderr.log') -PassThru
  $metadata.pid = $proc.Id; Write-Metadata
  $focusWriter = [IO.StreamWriter]::new((Join-Path $out 'focus.csv'),$false,[Text.Encoding]::ASCII)
  $cpuWriter = [IO.StreamWriter]::new((Join-Path $out 'sysmon.csv'),$false,[Text.Encoding]::ASCII)
  try {
    $focusWriter.WriteLine('epoch_ms,foreground_pid,focused')
    $cpuWriter.WriteLine('epoch_ms,cpu_pct,cpu_busiest_core_pct,gpu_pct,gpu_sm_mhz,gpu_mem_mhz,gpu_w,gpu_c,vram_mib,game_cpu_pct,bat_w')
    $lastCpu = 0.0; $lastEpoch = $metadata.started_epoch_ms; $nextSample = $lastEpoch
    $gpuTool = Get-Command nvidia-smi -ErrorAction SilentlyContinue
    while (-not $proc.HasExited) {
      $now = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
      [uint32]$foreground = 0
      [void][PzOptBenchFocus]::GetWindowThreadProcessId([PzOptBenchFocus]::GetForegroundWindow(),[ref]$foreground)
      $focusWriter.WriteLine("$now,$foreground,$([int]($foreground -eq $proc.Id))")
      if ($now -ge $nextSample) {
        try {
          $proc.Refresh(); $cpu = $proc.TotalProcessorTime.TotalMilliseconds
          $pct = if ($now -gt $lastEpoch) { 100.0 * ($cpu - $lastCpu) / ($now - $lastEpoch) } else { 0 }
          $lastCpu = $cpu; $lastEpoch = $now
          $gpu = ',,,,,'
          if ($gpuTool) {
            $sample = @(& $gpuTool.Source --query-gpu=utilization.gpu,clocks.sm,clocks.mem,power.draw,temperature.gpu,memory.used --format=csv,noheader,nounits 2>$null)
            if ($LASTEXITCODE -eq 0 -and $sample.Count) { $gpu = $sample[0] -replace ' ','' }
          }
          $cpuWriter.WriteLine("$now,,,$gpu,$($pct.ToString('F1',[Globalization.CultureInfo]::InvariantCulture)),")
        } catch { Write-Warning "sampler: $_" }
        $nextSample = $now + 1000
      }
      if ($now - $metadata.started_epoch_ms -gt $TimeoutSeconds * 1000L) {
        $metadata.timed_out = $true
        throw "benchmark exceeded ${TimeoutSeconds}s; stopping only launched JVM PID $($proc.Id)"
      }
      Start-Sleep -Milliseconds 250
    }
    $proc.WaitForExit(); $metadata.exit_code = $proc.ExitCode
    $metadata.status = 'collected'
  } finally { $focusWriter.Dispose(); $cpuWriter.Dispose() }
} catch {
  $failure = $_
  if ($out -and $metadata) { $metadata.status = 'failed'; $metadata.error = $_.Exception.Message }
} finally {
  if ($proc) {
    if (-not $proc.HasExited) { $proc.Kill(); $proc.WaitForExit() }
    if ($metadata) { $metadata.exit_code = $proc.ExitCode }
    $proc.Dispose()
  }
  if ($out -and $metadata) {
    $metadata.ended_epoch_ms = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
    if (Test-Path -LiteralPath $profile) {
      foreach ($f in (Get-ChildItem -LiteralPath $profile -File | Where-Object { $_.Name -eq 'console.txt' -or $_.Name -like 'pzopt-*.out' -or $_.Name -like 'hs_err_pid*.log' })) {
        Copy-Item -LiteralPath $f.FullName -Destination $out
      }
    }
    try { $metadata.environment_unchanged = ((Get-EnvironmentSnapshot).fingerprint -eq $snapshot.fingerprint) }
    catch { $metadata.environment_unchanged = $false; if (-not $metadata.error) { $metadata.error = $_.Exception.Message } }
    Write-Metadata
    @('layout=windows',"mode=$Mode","route_seconds=$RouteSeconds",'launcher=bundled-java-installed-json','cache_policy=fresh-private-cold',"status=$($metadata.status)","crashed=$([int](@(Get-ChildItem -LiteralPath $out -Filter 'hs_err_pid*.log').Count -gt 0))") |
      Set-Content -LiteralPath (Join-Path $out 'run.opts') -Encoding ASCII
  }
  if ($locked) { $mutex.ReleaseMutex() }; $mutex.Dispose()
}
if ($failure) { throw $failure }
[pscustomobject]@{ RunDirectory = $out; Environment = $snapshot.fingerprint; PreparedOnly = $false }
