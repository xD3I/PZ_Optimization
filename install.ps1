<#
.SYNOPSIS
Install, remove or inspect the PZ_Optimization class overrides on Windows from a release zip.

.DESCRIPTION
Standalone: needs Windows PowerShell 5.1 or newer. Nothing is compiled. The zip is fetched
from the GitHub releases ($env:GITHUB_TOKEN is used if set; the gh CLI if logged in);
-Zip skips the download. -From installs the same tree from an unpacked folder (no network);
a pzopt-classes folder next to this script (the Steam Workshop item layout), else the
Workshop item's copy in the Steam library that holds the game, is used automatically. A running
game is waited for: quit it and the install (or -Uninstall) goes on.

  .\install.ps1                                  # find the game, download the zip for its revision, install
  .\install.ps1 -Zip "$env:USERPROFILE\Downloads\pzopt-b0bbce05d5-classes.zip"
  .\install.ps1 -From "D:\SteamLibrary\steamapps\workshop\content\108600\<id>\mods\PZ_Optimization\42\pzopt-classes"
  .\install.ps1 -Dir "D:\SteamLibrary\steamapps\common\ProjectZomboid"
  .\install.ps1 -Status
  .\install.ps1 -Uninstall
  .\install.ps1 -Force       # replace class files another Java mod put into the game folder (moved aside first)

Without downloading anything first (PowerShell, any folder; the Steam Workshop copy is used when present):
  irm https://github.com/xD3I/PZ_Optimization/releases/latest/download/install.ps1 | iex
  irm https://github.com/xD3I/PZ_Optimization/releases/latest/download/uninstall.ps1 | iex

Every install also leaves Uninstall-PZ-Optimization.cmd in the game folder (Steam: Manage > Browse local files):
double-click it to remove PZ Optimization without starting the game; it runs the copy of this script kept in
pzopt\uninstall\ with -Uninstall -Pause (-Pause: wait for Enter before the window closes).

Files written are recorded in <game dir>\pzopt-installed.txt (same format as the Linux
tools). projectzomboid.jar is never modified; the runtime guard turns the classes off, with
one console.txt line, if the game revision differs.

If scripts are blocked: powershell -ExecutionPolicy Bypass -File .\install.ps1
#>
[CmdletBinding()]
param(
  [string]$Dir,
  [string]$Zip,
  [string]$From,
  [string]$Tag,
  [switch]$Uninstall,
  [switch]$Status,
  [switch]$Force,
  [switch]$Pause
)
if (-not $PSCommandPath) {
  # piped into Invoke-Expression or run as a script block (the one-liners above): no file of its own, and `exit`
  # would close the player's PowerShell window. Run the same script as a file in a child PowerShell instead, so
  # exit codes, $PSScriptRoot and the parameters behave as with -File.
  [Net.ServicePointManager]::SecurityProtocol = [Net.ServicePointManager]::SecurityProtocol -bor [Net.SecurityProtocolType]::Tls12
  $self = Join-Path ([IO.Path]::GetTempPath()) 'pzopt-install.ps1'
  Invoke-WebRequest -UseBasicParsing -Uri 'https://github.com/xD3I/PZ_Optimization/releases/latest/download/install.ps1' -OutFile $self
  $argv = @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', $self)
  foreach ($k in $PSBoundParameters.Keys) {
    $v = $PSBoundParameters[$k]
    if ($v -is [switch]) { if ($v) { $argv += "-$k" } } else { $argv += "-$k"; $argv += "$v" }
  }
  & (Get-Process -Id $PID).Path @argv
  return
}
$ErrorActionPreference = 'Stop'
# GitHub needs TLS 1.2; Windows PowerShell 5.1 on older Windows offers 1.0 / 1.1 only by default
[Net.ServicePointManager]::SecurityProtocol = [Net.ServicePointManager]::SecurityProtocol -bor [Net.SecurityProtocolType]::Tls12
$RepoSlug = 'xD3I/PZ_Optimization'
$WorkshopId = '3805285544'
Add-Type -AssemblyName System.IO.Compression.FileSystem

# -Pause (the double-click uninstaller): the window stays until Enter, so the result can be read
function Done($code) { if ($Pause) { [void](Read-Host 'Press Enter to close') }; exit $code }
function Fail($msg) { Write-Host "error: $msg" -ForegroundColor Red; Done 1 }

# --- locate the game -------------------------------------------------------------------

function Find-GameDir {
  $libs = @()
  $steam = (Get-ItemProperty 'HKCU:\Software\Valve\Steam' -ErrorAction SilentlyContinue).SteamPath
  if ($steam) { $libs += $steam }
  $libs += "${env:ProgramFiles(x86)}\Steam", "$env:ProgramFiles\Steam"
  # [IO.Path]::Combine, not Join-Path: libraryfolders.vdf keeps libraries on unplugged drives
  # and Join-Path throws DriveNotFoundException for those instead of returning a path
  foreach ($lib in $libs) {
    $vdf = [IO.Path]::Combine($lib, 'steamapps\libraryfolders.vdf')
    if (Test-Path -LiteralPath $vdf) {
      foreach ($m in [regex]::Matches((Get-Content -LiteralPath $vdf -Raw), '"path"\s+"([^"]+)"')) {
        $libs += $m.Groups[1].Value.Replace('\\', '\')
      }
    }
  }
  foreach ($lib in $libs | Select-Object -Unique) {
    if (-not (Test-Path -LiteralPath $lib)) { continue }
    foreach ($c in @([IO.Path]::Combine($lib, 'steamapps\common\ProjectZomboid'), [IO.Path]::Combine($lib, 'steamapps\common\ProjectZomboid\projectzomboid'))) {
      if ((Test-Path -LiteralPath ([IO.Path]::Combine($c, 'projectzomboid.jar'))) -and (Test-Path -LiteralPath ([IO.Path]::Combine($c, 'ProjectZomboid64.json')))) { return $c }
    }
  }
  return $null
}

if (-not $Dir) { $Dir = Find-GameDir }
if (-not $Dir) { Fail 'game folder not found; pass -Dir <folder containing projectzomboid.jar>' }
# "...\ProjectZomboid\." (the double-click uninstaller's %~dp0.) and the like: one spelling, so the running-game check matches
$Dir = [IO.Path]::GetFullPath($Dir).TrimEnd('\', '/')
$Jar = Join-Path $Dir 'projectzomboid.jar'
if (-not (Test-Path $Jar)) { Fail "no projectzomboid.jar in $Dir" }
$Json = Join-Path $Dir 'ProjectZomboid64.json'
$Manifest = Join-Path $Dir 'pzopt-installed.txt'

function Read-ZipEntry($zipPath, $entryName) {
  $z = [System.IO.Compression.ZipFile]::OpenRead($zipPath)
  try {
    $e = $z.GetEntry($entryName)
    if (-not $e) { return $null }
    $s = $e.Open(); $ms = New-Object System.IO.MemoryStream; $s.CopyTo($ms); $s.Dispose()
    return $ms.ToArray()
  } finally { $z.Dispose() }
}
function Get-JarRevision {
  # zombie.GitVersion holds REVISION as a constant-pool string; no JDK needed
  $bytes = Read-ZipEntry $Jar 'zombie/GitVersion.class'
  if (-not $bytes) { return $null }
  $m = [regex]::Match([System.Text.Encoding]::ASCII.GetString($bytes), '\b[0-9a-f]{10}\b')
  if ($m.Success) { $m.Value } else { $null }
}
function Get-Sha256($path) { (Get-FileHash -Algorithm SHA256 -LiteralPath $path).Hash.ToLower() }
function Test-GameRunning {
  [bool](Get-Process ProjectZomboid64 -ErrorAction SilentlyContinue | Where-Object { $_.Path -and (Split-Path $_.Path -Parent).TrimEnd('\','/') -eq $Dir.TrimEnd('\','/') })
}
# The files must not change under a running game: wait for it (the in-game helper's flow is paste, then quit)
function Wait-GameClosed {
  if (-not (Test-GameRunning)) { return }
  Write-Host "the game is running from ${Dir}: quit it (QUIT in the main menu); this goes on once it has closed (Ctrl+C cancels)"
  while (Test-GameRunning) { Start-Sleep -Seconds 1 }
  Start-Sleep -Seconds 1
}
# The newest copy of the Steam Workshop item for this game revision, or $null: Steam keeps an app's Workshop content
# in the library of the app itself, <library>\steamapps\workshop\content\108600\<item>\mods\PZ_Optimization\<version>.
# Complete only when every file its pzopt-files.txt lists is there (Steam replaces an item's files one by one).
function Find-WorkshopCopy([switch]$AnyRevision) {
  $d = $Dir
  while ($d -and ((Split-Path $d -Leaf) -ne 'steamapps')) {
    $up = Split-Path $d -Parent
    if ($up -eq $d) { $d = $null; break }
    $d = $up
  }
  if (-not $d) { return $null }
  $mod = [IO.Path]::Combine($d, 'workshop', 'content', '108600', $WorkshopId, 'mods', 'PZ_Optimization')
  if (-not (Test-Path -LiteralPath $mod)) { return $null }
  $best = $null; $bestBuilt = [long]-1
  foreach ($v in Get-ChildItem -LiteralPath $mod -Directory) {
    $c = Join-Path $v.FullName 'pzopt-classes'
    $info = Join-Path $c 'pzopt\build-info.properties'
    $list = Join-Path $c 'pzopt-files.txt'
    if (-not (Test-Path -LiteralPath $info) -or -not (Test-Path -LiteralPath $list)) { continue }
    $p = @{}
    foreach ($l in Get-Content -LiteralPath $info) { if ($l -match '^([^#=]+)=(.*)$') { $p[$Matches[1].Trim()] = $Matches[2].Trim() } }
    if (-not $AnyRevision -and $p['revision'] -ne $Rev) { continue }
    $complete = $true
    foreach ($rel in Get-Content -LiteralPath $list) {
      if ($rel -and -not (Test-Path -LiteralPath (Join-Path $c ($rel -replace '/', '\')))) { $complete = $false; break }
    }
    if (-not $complete) { Write-Host "skipping $c`: Steam is still updating it"; continue }
    $built = [long]0
    [void][long]::TryParse([string]$p['built'], [ref]$built)
    if ($built -gt $bestBuilt) { $best = $c; $bestBuilt = $built }
  }
  return $best
}

# A launcher JSON that pzopt's AOT-cache mode (pzopt.AotCache) switched to its jar form goes back to the loose
# classes ("." first, no AOT options), and the jar and cache go: the loose files are about to change.
function Reset-Aot {
  # The game is closed here. A launcher edit pzopt staged while it ran (ProjectZomboid64.json.pzopt-pending: the running
  # game holds the JSON) or a leftover .pzopt-tmp goes first, so neither can land over this reset afterwards.
  foreach ($f in @("$Json.pzopt-pending", "$Json.pzopt-tmp")) {
    if (Test-Path -LiteralPath $f) { Remove-Item -LiteralPath $f -Force; Write-Host "launcher: removed $(Split-Path $f -Leaf)" }
  }
  if (Test-Path -LiteralPath $Json) {
    $j = Get-Content -LiteralPath $Json -Raw | ConvertFrom-Json
    $jar = 'pzopt/aot/pzopt.jar'
    $aot = @($j.vmArgs | Where-Object { $_ -like '-XX:AOTCache*' -or $_ -like '-Xlog:aot=info:file=pzopt/aot/*' })
    if ((@($j.classpath) -contains $jar) -or $aot.Count -gt 0) {
      $j.classpath = @('.') + @($j.classpath | Where-Object { $_ -ne '.' -and $_ -ne $jar })
      $j.vmArgs = @($j.vmArgs | Where-Object { $aot -notcontains $_ })
      [IO.File]::WriteAllText($Json, ($j | ConvertTo-Json -Depth 10), (New-Object Text.UTF8Encoding $false))
      Write-Host 'launcher: AOT-cache form put back to the loose classes'
    }
  }
  $aotDir = Join-Path $Dir 'pzopt\aot'
  if (Test-Path -LiteralPath $aotDir) { Remove-Item -LiteralPath $aotDir -Recurse -Force }
}
# Undo pzopt.GcChoice's launcher switch (marker -Dpzopt.gc=g1[,pause]): G1 back to ZGC, the pause target it added removed;
# its JIT flags and its heap size too.
function Reset-Gc {
  if (-not (Test-Path -LiteralPath $Json)) { return }
  $j = Get-Content -LiteralPath $Json -Raw | ConvertFrom-Json
  $m = '-Dpzopt.gc=g1'; $mp = '-Dpzopt.gc=g1,pause'; $changed = $false
  $fix = {
    param($a)
    $a = @($a)
    if (-not (($a -contains $m) -or ($a -contains $mp))) { return ,$a }
    if ($a -contains $mp) { $a = @($a | Where-Object { $_ -notlike '-XX:MaxGCPauseMillis=*' }) }
    $a = @($a | Where-Object { $_ -ne $m -and $_ -ne $mp } | ForEach-Object { if ($_ -eq '-XX:+UseG1GC') { '-XX:+UseZGC' } else { $_ } })
    $script:gcChanged = $true
    return ,$a
  }
  # jitSteady (marker -Dpzopt.jit=steady): the JIT trap-limit flags it added
  $jm = '-Dpzopt.jit=steady'
  $fixJit = {
    param($a)
    $a = @($a)
    if (-not ($a -contains $jm)) { return ,$a }
    $a = @($a | Where-Object { $_ -ne $jm -and $_ -notlike '-XX:PerMethodTrapLimit=*' -and $_ -notlike '-XX:PerBytecodeTrapLimit=*' })
    $script:gcChanged = $true
    return ,$a
  }
  # gcHeapMb / gcHeapFixed / gcPreTouch (marker -Dpzopt.heap=<old -Xmx>,<old -Xms>,<pre-touch added 0|1>): the old flags back
  $hm = '-Dpzopt.heap='
  $fixHeap = {
    param($a)
    $a = [System.Collections.ArrayList]@($a)
    $mk = @($a | Where-Object { $_ -like "$hm*" })
    if ($mk.Count -eq 0) { return ,@($a) }
    $old = @(($mk[-1].Substring($hm.Length) -split ',') + @('none', 'none', '0'))
    foreach ($x in $mk) { $a.Remove($x) }
    foreach ($pair in @(@('-Xmx', $old[0]), @('-Xms', $old[1]))) {
      $i = -1
      for ($k = 0; $k -lt $a.Count; $k++) { if ($a[$k].StartsWith($pair[0])) { $i = $k } }
      if ($pair[1] -eq 'none') { if ($i -ge 0) { $a.RemoveAt($i) } }
      elseif ($i -ge 0) { $a[$i] = $pair[0] + $pair[1] }
      else { [void]$a.Add($pair[0] + $pair[1]) }
    }
    if ($old[2] -eq '1') { $a.Remove('-XX:+AlwaysPreTouch') }
    $script:gcChanged = $true
    return ,@($a)
  }
  $script:gcChanged = $false
  # every vmArgs array at any depth: the switch lands where ZGC was, on Windows in windows."10.0.17134".vmArgs
  $walk = {
    param($o)
    foreach ($p in @($o.PSObject.Properties)) {
      if ($p.Name -eq 'vmArgs') { $p.Value = & $fixHeap (& $fixJit (& $fix $p.Value)) }
      elseif ($p.Value -is [System.Management.Automation.PSCustomObject]) { & $walk $p.Value }
    }
  }
  & $walk $j
  if ($script:gcChanged) {
    [IO.File]::WriteAllText($Json, ($j | ConvertTo-Json -Depth 10), (New-Object Text.UTF8Encoding $false))
    Write-Host "launcher: pzopt's G1 switch / JIT flags / heap size undone (back to the launcher's own)"
  }
}
$Rev = Get-JarRevision
$ZomboidPzopt = Join-Path $env:USERPROFILE 'Zomboid\pzopt'
$ShortcutFiles = @('Uninstall-PZ-Optimization.cmd', 'uninstall-pz-optimization.bash')

# What the game itself left about an earlier install (an unfinished in-game uninstall, the boot repair's note for the
# Workshop item's install helper) no longer applies once this script has installed or removed one.
function Clear-GameNotes {
  foreach ($f in @((Join-Path $ZomboidPzopt 'uninstall-files.txt'), (Join-Path $ZomboidPzopt 'uninstall-dirs.txt'),
                   (Join-Path $env:USERPROFILE 'Zomboid\Lua\pzopt-boot-repair.txt'))) {
    Remove-Item -LiteralPath $f -Force -ErrorAction SilentlyContinue
  }
}

# Is a file in the game folder PZ Optimization's? A path with "pzopt" in it (the package, the Lua, the shaders, the
# lists), a class whose bytes name the pzopt package (every override that calls it), or an inner class of one. Other
# Java mods' class files (Better Vehicle Dynamics ships zombie/iso/IsoChunkMap.class) are not. pzopt.properties is the
# player's own settings file and stays.
function Test-NamesPzopt($p) {
  try { return [Text.Encoding]::ASCII.GetString([IO.File]::ReadAllBytes($p)).Contains('pzopt/') } catch { return $false }
}
function Test-OursFile($rel) {
  if ($rel -eq 'pzopt.properties') { return $false }
  if ($ShortcutFiles -contains $rel -or $rel -match '(^|/)[^/]*pzopt') { return $true }
  if ($rel -notlike '*.class') { return $false }
  $p = Join-Path $Dir ($rel -replace '/', '\')
  if (Test-NamesPzopt $p) { return $true }
  $leaf = Split-Path $rel -Leaf
  if ($leaf.Contains('$')) {
    $outer = Join-Path (Split-Path $p -Parent) ($leaf.Substring(0, $leaf.IndexOf('$')) + '.class')
    if (Test-Path -LiteralPath $outer) { return Test-NamesPzopt $outer }
  }
  return $false
}

# --- status / uninstall ----------------------------------------------------------------

if ($Status) {
  Write-Host "game dir:      $Dir"
  Write-Host "game revision: $(if ($Rev) { $Rev } else { 'unknown' })"
  if (Test-Path $Manifest) {
    $lines = Get-Content $Manifest | Where-Object { $_ -and -not $_.StartsWith('#') }
    $for = (Get-Content $Manifest | Select-String '^# revision=(\S+)').Matches[0].Groups[1].Value
    Write-Host "installed:     yes, for $for ($($lines.Count) files)"
    $bad = $false
    foreach ($l in $lines) {
      $rel, $sha = $l -split ' ', 2
      $p = Join-Path $Dir $rel
      if (-not (Test-Path -LiteralPath $p)) { Write-Host "  MISSING  $rel"; $bad = $true }
      elseif ((Get-Sha256 $p) -ne $sha) { Write-Host "  MODIFIED $rel"; $bad = $true }
    }
    if (-not $bad) { Write-Host '  all files present and unchanged' }
  } else { Write-Host 'installed:     no' }
  $props = Join-Path $Dir 'pzopt.properties'
  if (Test-Path $props) { Write-Host 'pzopt.properties:'; Get-Content $props | ForEach-Object { "  $_" } }
  Done 0
}

# Removes an install: the files of the manifest (or of a hand-unpacked zip's pzopt-files.txt), the folders they leave,
# the launcher edits. Used by -Uninstall and by an install that finds a previous one (a build for an older game
# revision crashes the updated game at start, so the install replaces it instead of refusing). $false: nothing installed.
function Remove-Install {
  Reset-Aot | Out-Null   # only the $true / $false below may reach the caller
  Reset-Gc | Out-Null
  $filesTxt = Join-Path $Dir 'pzopt-files.txt'
  if (Test-Path $Manifest) { $list = Get-Content $Manifest | Where-Object { $_ -and -not $_.StartsWith('#') } | ForEach-Object { ($_ -split ' ')[0] } }
  elseif (Test-Path $filesTxt) { $list = Get-Content $filesTxt | Where-Object { $_ } }
  else {
    $list = @(Find-Leftovers)
    if ($list.Count -eq 0) { return $false }
    Write-Host "no list of installed files in $Dir (an install that stopped early, or files copied by hand): removing the $($list.Count) files that are PZ Optimization's"
  }
  $list = @($list) + @($ShortcutFiles | Where-Object { Test-Path -LiteralPath (Join-Path $Dir $_) })
  # the overrides first, the pzopt package they call last: a removal cut short never leaves an override without it
  $list = @($list | Where-Object { $_ -notlike 'pzopt/*' }) + @($list | Where-Object { $_ -like 'pzopt/*' })
  $n = 0
  foreach ($rel in $list) {
    $p = Join-Path $Dir $rel
    if (Test-Path -LiteralPath $p) { Remove-Item -LiteralPath $p -Force; $n++ }
    $d = Split-Path $p -Parent
    while ($d -and ($d.TrimEnd('\','/') -ne $Dir.TrimEnd('\','/')) -and (Test-Path -LiteralPath $d) -and -not (Get-ChildItem -LiteralPath $d -Force)) {
      Remove-Item -LiteralPath $d; $d = Split-Path $d -Parent
    }
  }
  Remove-Item -LiteralPath $Manifest, $filesTxt -Force -ErrorAction SilentlyContinue
  Clear-GameNotes
  Write-Host "removed $n files; projectzomboid.jar was never modified"
  return $true
}

# No manifest and no pzopt-files.txt: the files that are PZ Optimization's by Test-OursFile, among the loose class
# folders, every path with "pzopt" in it, and the files of the Steam Workshop copy's list that are byte-identical to it
# (overrides that never name the pzopt package, e.g. zombie/FliesSound.class, only when something of ours is there too). The game's own classes are in the jar.
function Find-Leftovers {
  $found = New-Object System.Collections.Generic.List[string]
  $root = $Dir.TrimEnd('\', '/')
  foreach ($top in 'zombie', 'org', 'se', 'fmod', 'pzopt', 'media', 'natives') {
    $t = Join-Path $root $top
    if (-not (Test-Path -LiteralPath $t)) { continue }
    foreach ($f in Get-ChildItem -LiteralPath $t -Recurse -File -Force -ErrorAction SilentlyContinue) {
      $rel = $f.FullName.Substring($root.Length + 1).Replace('\', '/')
      if ($top -eq 'media' -and $rel -notmatch 'pzopt') { continue }
      if (Test-OursFile $rel) { $found.Add($rel) }
    }
  }
  if ($found.Count -gt 0) {
    $copy = Find-WorkshopCopy -AnyRevision
    if ($copy) {
      foreach ($rel in Get-Content -LiteralPath (Join-Path $copy 'pzopt-files.txt')) {
        # only a byte-identical copy: a class of the same name that differs may be another mod's
        $mine = Join-Path $root ($rel -replace '/', '\')
        if ($rel -and -not $found.Contains($rel) -and (Test-Path -LiteralPath $mine -PathType Leaf) -and
            ((Get-Sha256 $mine) -eq (Get-Sha256 (Join-Path $copy ($rel -replace '/', '\'))))) { $found.Add($rel) }
      }
    }
  }
  return $found
}

if ($Uninstall) {
  Wait-GameClosed
  if (-not (Remove-Install)) { Clear-GameNotes; Write-Host "PZ Optimization is not installed in $Dir (nothing of it found there)"; Done 0 }
  Write-Host "PZ Optimization is uninstalled: the next launch is the stock game. You can unsubscribe from the Workshop item now."
  Write-Host "caches under $env:USERPROFILE\Zomboid\pzopt\ (anims, packs, framecap.ini, options.ini) can be deleted by hand"
  Done 0
}

# --- install ---------------------------------------------------------------------------

Wait-GameClosed
if (-not $Rev) { Fail "could not read the game revision from $Jar" }
if ((Test-Path $Manifest) -or (Test-Path (Join-Path $Dir 'pzopt-files.txt'))) {
  $oldRev = 'unknown'
  if (Test-Path $Manifest) { $m = Get-Content $Manifest | Select-String '^# revision=(\S+)' | Select-Object -First 1; if ($m) { $oldRev = $m.Matches[0].Groups[1].Value } }
  Write-Host "replacing the installed build (for game revision $oldRev; this game is $Rev)"
  [void](Remove-Install)
}

Reset-Aot
# the launcher must search "." before the jar or loose classes never load
$cp = @((Get-Content $Json -Raw | ConvertFrom-Json).classpath)
if (($cp.IndexOf('.') -lt 0) -or ($cp.IndexOf('projectzomboid.jar') -lt 0) -or ($cp.IndexOf('.') -gt $cp.IndexOf('projectzomboid.jar'))) {
  Fail "$Json does not list `".`" before projectzomboid.jar on the classpath; loose classes would never load (found: $($cp -join ', '))"
}

$tmp = $null
if (-not $Zip -and -not $From -and $PSScriptRoot) {
  $sibling = Join-Path $PSScriptRoot 'pzopt-classes'
  if (Test-Path (Join-Path $sibling 'pzopt\build-info.properties')) { $From = $sibling }
}
if (-not $Zip -and -not $From -and -not $Tag) {
  $From = Find-WorkshopCopy
  if ($From) { Write-Host "found the Steam Workshop copy for revision $Rev" }
}
if ($From) {
  if (-not (Test-Path (Join-Path $From 'pzopt\build-info.properties'))) { Fail "$From is not an unpacked PZ_Optimization release (no pzopt\build-info.properties)" }
  Write-Host "installing from folder $From"
} elseif (-not $Zip) {
  $pattern = "pzopt-$Rev-classes.zip"
  $tmp = Join-Path ([System.IO.Path]::GetTempPath()) ("pzopt-" + [guid]::NewGuid().ToString('N').Substring(0, 8))
  New-Item -ItemType Directory -Path $tmp | Out-Null
  $gh = Get-Command gh -ErrorAction SilentlyContinue
  $ghOk = $false
  if ($gh) { & gh auth status 2>$null | Out-Null; $ghOk = ($LASTEXITCODE -eq 0) }
  if ($ghOk) {
    if (-not $Tag) {
      $Tag = (& gh release list -R $RepoSlug --json tagName -q '.[].tagName') | Where-Object { $_ -match "-$Rev(-|$)" } | Select-Object -First 1
      if (-not $Tag) { Fail "no release for game revision $Rev (your game is a build these classes were not built for)" }
    }
    Write-Host "downloading $pattern from release $Tag"
    & gh release download $Tag -R $RepoSlug -p $pattern -D $tmp
    if ($LASTEXITCODE -ne 0) { Fail 'gh release download failed' }
  } else {
    $h = @{ Accept = 'application/vnd.github+json' }
    if ($env:GITHUB_TOKEN) { $h.Authorization = "Bearer $env:GITHUB_TOKEN" }
    $rels = Invoke-RestMethod -UseBasicParsing -Headers $h "https://api.github.com/repos/$RepoSlug/releases?per_page=50"
    $asset = $null
    # the list's order is by the tagged commit's date, not by publish date
    foreach ($r in ($rels | Sort-Object -Property published_at -Descending)) {
      if ($Tag -and $r.tag_name -ne $Tag) { continue }
      $a = $r.assets | Where-Object { $_.name -eq $pattern } | Select-Object -First 1
      if ($a) { $asset = $a; $Tag = $r.tag_name; break }
    }
    if (-not $asset) { Fail "no release has $pattern (your game revision $Rev is a build these classes were not built for)" }
    Write-Host "downloading $pattern from release $Tag"
    $h.Accept = 'application/octet-stream'
    Invoke-WebRequest -UseBasicParsing -Headers $h -Uri $asset.url -OutFile (Join-Path $tmp $pattern)
  }
  $Zip = Join-Path $tmp $pattern
}
if ($From) {
  $Source = $From
  $fromRoot = (Resolve-Path -LiteralPath $From).Path.TrimEnd('\','/')
  # relative paths with '/' separators, the manifest format the zip path produces
  $files = @(Get-ChildItem -LiteralPath $fromRoot -Recurse -File | ForEach-Object { $_.FullName.Substring($fromRoot.Length + 1).Replace('\', '/') } | Sort-Object)
  $bi = Get-Content -Raw (Join-Path $fromRoot 'pzopt\build-info.properties')
} else {
  if (-not (Test-Path -LiteralPath $Zip)) { Fail "zip not found: $Zip" }
  $Source = $Zip
  $z = [System.IO.Compression.ZipFile]::OpenRead($Zip)
  try { $files = @($z.Entries | Where-Object { -not $_.FullName.EndsWith('/') } | ForEach-Object { $_.FullName } | Sort-Object) } finally { $z.Dispose() }
  if ($files -notcontains 'pzopt/build-info.properties') { Fail "$Zip is not a PZ_Optimization release zip" }
  $bi = [System.Text.Encoding]::UTF8.GetString((Read-ZipEntry $Zip 'pzopt/build-info.properties'))
}
$zipRev = ([regex]::Match($bi, '(?m)^revision=(\S+)')).Groups[1].Value
if ($zipRev -ne $Rev) { Fail "$Source was built for game revision $zipRev but this game is $Rev; the classes would disable themselves. Get the build for $Rev" }
# No manifest is left at this point, so a release file already in the folder is either the remains of an install that
# stopped before writing one (the game keeps its own classes in the jar), replaced (refusing left no way out: -Uninstall
# found nothing to remove, and the game crashed on an override whose pzopt classes were missing), or another Java mod's
# copy of a class we replace too (Better Vehicle Dynamics: zombie/iso/IsoChunkMap.class), refused unless -Force. What is
# not recognisably ours is moved to Zomboid\pzopt\replaced-files\<time>\ first.
$left = @($files | Where-Object { Test-Path -LiteralPath (Join-Path $Dir ($_ -replace '/', [IO.Path]::DirectorySeparatorChar)) })
if ($left.Count -gt 0) {
  $ours = @($left | Where-Object { Test-OursFile $_ })
  $foreign = @($left | Where-Object { $ours -notcontains $_ })
  $backup = Join-Path $ZomboidPzopt ('replaced-files\' + (Get-Date -Format 'yyyyMMdd-HHmmss'))
  if ($ours.Count -eq 0 -and -not $Force) {
    Fail ("$($foreign.Count) game classes this release replaces are already in $Dir and are not PZ Optimization's, e.g. " +
      (($foreign | Select-Object -First 3) -join ', ') + ".`nAnother Java mod put them there (Better Vehicle Dynamics ships zombie/iso/IsoChunkMap.class, for one); " +
      "two mods cannot both replace the same class. Remove that mod's files, or run the installer again with -Force: they are moved to $backup first.")
  }
  foreach ($rel in $foreign) {
    $dst = Join-Path $backup ($rel -replace '/', '\')
    New-Item -ItemType Directory -Force -Path (Split-Path $dst -Parent) | Out-Null
    Move-Item -LiteralPath (Join-Path $Dir ($rel -replace '/', '\')) -Destination $dst -Force
  }
  if ($foreign.Count -gt 0) { Write-Host "moved $($foreign.Count) files that were not PZ Optimization's to $backup" }
  if ($ours.Count -gt 0) { Write-Host "replacing $($ours.Count) files an unfinished install left behind (e.g. $($ours[0]))" }
  foreach ($rel in $ours) { Remove-Item -LiteralPath (Join-Path $Dir ($rel -replace '/', [IO.Path]::DirectorySeparatorChar)) -Force }
}

$jarBefore = Get-Sha256 $Jar
# The manifest goes first, so an install cut short (antivirus, full disk, closed window) is still replaced by the next
# run or removed by -Uninstall; the pzopt package goes before the overrides that call it.
$stamp = "# revision=$zipRev installed=$([DateTime]::UtcNow.ToString('yyyy-MM-ddTHH:mm:ssZ'))"
[System.IO.File]::WriteAllLines($Manifest, @('# files written by install.ps1 - do not edit', $stamp,
  '# unfinished: the install stopped before the end; run the installer again') + @($files | ForEach-Object { "$_ -" }))
$ordered = @($files | Where-Object { $_ -like 'pzopt/*' }) + @($files | Where-Object { $_ -notlike 'pzopt/*' })
$done = 0
$rel = ''
try {
  if ($From) {
    foreach ($rel in $ordered) {
      $dst = Join-Path $Dir ($rel -replace '/', [IO.Path]::DirectorySeparatorChar)
      New-Item -ItemType Directory -Force -Path (Split-Path $dst -Parent) | Out-Null
      Copy-Item -LiteralPath (Join-Path $fromRoot ($rel -replace '/', [IO.Path]::DirectorySeparatorChar)) -Destination $dst
      $done++
    }
  } else {
    $z = [System.IO.Compression.ZipFile]::OpenRead($Zip)
    try {
      foreach ($rel in $ordered) {
        $dst = Join-Path $Dir ($rel -replace '/', [IO.Path]::DirectorySeparatorChar)
        New-Item -ItemType Directory -Force -Path (Split-Path $dst -Parent) | Out-Null
        [System.IO.Compression.ZipFileExtensions]::ExtractToFile($z.GetEntry($rel), $dst)
        $done++
      }
    } finally { $z.Dispose() }
  }
} catch {
  Fail "the install stopped at $rel ($done of $($files.Count) files written): $($_.Exception.Message)`nAn antivirus may have blocked the file. Run the installer again (it replaces the unfinished install) or run it with -Uninstall."
}
$out = @('# files written by install.ps1 - do not edit', $stamp)
foreach ($rel in $files) { $out += "$rel $(Get-Sha256 (Join-Path $Dir ($rel -replace '/', [IO.Path]::DirectorySeparatorChar)))" }
[System.IO.File]::WriteAllLines($Manifest, $out)
if ((Get-Sha256 $Jar) -ne $jarBefore) { Fail 'projectzomboid.jar changed during install (this should be impossible)' }
if ($tmp) { Remove-Item -Recurse -Force $tmp -ErrorAction SilentlyContinue }

Clear-GameNotes
Write-Host "installed $($files.Count) files into $Dir for game revision $zipRev; projectzomboid.jar untouched"
Write-Host "launch from Steam; $env:USERPROFILE\Zomboid\console.txt shows one '[pzopt] loaded override ... active' line per class"
Write-Host "settings: Options > PZ Optimization in the game, or $Dir\pzopt.properties"
Write-Host "to remove it: Options > PZ Optimization > Uninstall PZ Optimization, or double-click $Dir\Uninstall-PZ-Optimization.cmd"
Done 0
