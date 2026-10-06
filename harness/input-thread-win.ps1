# Isolated Windows smoke for the input-thread fork; direct bundled-Java launch, no Steam/launcher edits.
# Usage (automated OS-input + Lua/UI assertions):
#   pwsh -File harness/input-thread-win.ps1 -Classes build/classes -PZ 'C:\Program Files (x86)\Steam\steamapps\common\ProjectZomboid' -CacheDir "$env:TEMP\pzopt-input-thread" [-ReadyTimeoutSeconds 180]
# Manual physical-input run (same isolated classes/profile/flags; no synthetic burst or automatic quit):
#   pwsh -File harness/input-thread-win.ps1 -Classes build/classes -PZ 'C:\Program Files (x86)\Steam\steamapps\common\ProjectZomboid' -CacheDir "$env:TEMP\pzopt-input-thread-manual" -Manual
# -Classes is the fork's built classes directory; it precedes a private copy of projectzomboid.jar. Installed AOT overlay is never on classpath.
# -PZ is the installed game directory (used read-only for runtime/media/native files); -CacheDir is wholly disposable isolated profile/cache.
# -Manual keeps the game interactive for physical hardware input and exits only when the user closes this launched window normally.
# -Sensitivity and -Acceleration exercise the real options controls and native relative movement before the UI smoke.
[CmdletBinding()]
param(
    [string]$Classes = (Join-Path $PSScriptRoot '..\build\classes'),
    [Parameter(Mandatory)][string]$PZ,
    [string]$CacheDir = (Join-Path $env:TEMP 'pzopt-input-thread-cache'),
    [int]$ReadyTimeoutSeconds = 180,
    [string]$Jdk = $env:JAVA_HOME,
    [switch]$Manual,
    [switch]$Combat,
    [switch]$ImGui,
    [switch]$Sensitivity,
    [switch]$Acceleration
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$Classes = [IO.Path]::GetFullPath($Classes)
$PZ = [IO.Path]::GetFullPath($PZ)
$CacheDir = [IO.Path]::GetFullPath($CacheDir)
$Java = Join-Path $PZ 'jre64\bin\java.exe'
$GameJar = Join-Path $PZ 'projectzomboid.jar'
$FixtureSource = Join-Path $PSScriptRoot 'mod\pzopt-harness'
$FixtureTarget = Join-Path $CacheDir 'mods\pzopt-harness'
$LogDir = Join-Path $CacheDir 'input-thread-evidence'
 $Console = Join-Path $LogDir 'stdout.log'
$RunLog = Join-Path $LogDir 'driver.log'
$EventsLog = Join-Path $LogDir 'callbacks.log'
$FlagPath = Join-Path $CacheDir 'Lua\pzopt-harness.txt'
$ArmPath = Join-Path $CacheDir 'Lua\pzopt-input-start.txt'
$DonePath = Join-Path $CacheDir 'Lua\pzopt-input-done.txt'
$BurstPath = Join-Path $CacheDir 'Lua\pzopt-input-burst.txt'
$ProfileMarker = Join-Path $LogDir 'owned-profile.txt'
$ViewportOffsetX=0.0; $ViewportOffsetY=0.0; $ViewportMapped=$false

function Log([string]$Message) {
    if (-not (Test-Path -LiteralPath $LogDir)) { New-Item -ItemType Directory -Force -Path $LogDir | Out-Null }
    $line = "$(Get-Date -Format o) $Message"
    Add-Content -LiteralPath $RunLog -Value $line -Encoding utf8
    Write-Host $line
}
function Fail([string]$Message) {
    if (Test-Path -LiteralPath $ProfileMarker) { Log "FAIL $Message" }
    else { Write-Host "FAIL $Message" }
    throw $Message
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
function Get-GameProcesses {
    @(Get-CimInstance Win32_Process -Filter "Name='ProjectZomboid64.exe' OR Name='ProjectZomboid64' OR Name='java.exe'" -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -like 'ProjectZomboid64*' -or $_.CommandLine -match 'zombie\.gameStates\.MainScreenState|ProjectZomboid64\.exe' })
}

if ($env:OS -ne 'Windows_NT') { Fail 'This SendInput harness runs only on Windows PowerShell.' }
if (-not (Test-Path -LiteralPath $Classes -PathType Container)) { Fail "classes directory not found: $Classes (build the fork first)" }
foreach ($path in @($PZ, $Java, $GameJar, $FixtureSource)) { if (-not (Test-Path -LiteralPath $path)) { Fail "required path not found: $path" } }
if ($ReadyTimeoutSeconds -lt 30) { Fail '-ReadyTimeoutSeconds must be at least 30.' }
$existing = @(Get-GameProcesses)
if ($existing.Count) { Fail "game already running (PID(s) $($existing.ProcessId -join ', ')); no process was changed" }
if ($Sensitivity -and $Acceleration) { Fail 'Run sensitivity and acceleration as separate isolated scenarios.' }
if ([IO.Path]::GetFullPath($CacheDir).StartsWith([IO.Path]::GetFullPath($PZ), [StringComparison]::OrdinalIgnoreCase)) { Fail '-CacheDir must not be inside the installed game directory.' }
if ((Test-Path -LiteralPath $CacheDir) -and -not (Test-Path -LiteralPath $ProfileMarker)) {
    if ([IO.Directory]::EnumerateFileSystemEntries($CacheDir).GetEnumerator().MoveNext()) {
        Fail '-CacheDir must be new/empty or a profile previously created by this input-thread harness.'
    }
}

New-Item -ItemType Directory -Force -Path $LogDir, (Join-Path $CacheDir 'Lua'), (Join-Path $CacheDir 'mods'), (Join-Path $CacheDir 'Saves'), (Join-Path $CacheDir 'pzopt') | Out-Null
[IO.File]::WriteAllText($ProfileMarker,'Private disposable profile owned by input-thread-win.ps1')
Remove-Item -LiteralPath $RunLog,$EventsLog,$Console,($Console + '.stderr'),$ArmPath,$DonePath,$BurstPath -Force -ErrorAction SilentlyContinue
Log "MODE=$([string]$(if ($Manual) { 'MANUAL physical-input; no synthetic event rate measurement' } elseif ($Combat) { 'AUTOMATED native ranged/melee combat via OS SendInput' } else { 'AUTOMATED actual OS SendInput; 8000 events are synthetic only' }))"
Log "ISOLATED cache=$CacheDir classes=$Classes game=$PZ"
# Only the private profile is prepared. It contains no copied user saves, defaults, options, mod approvals, or installed files.
if (Test-Path -LiteralPath $FixtureTarget) { Remove-Item -LiteralPath $FixtureTarget -Recurse -Force }
Copy-Item -LiteralPath $FixtureSource -Destination $FixtureTarget -Recurse
# Resolve the candidate's loose Lua through the private mod, not an older installed options screen.
$BuiltLua = Join-Path $Classes 'media\lua'
if (-not (Test-Path -LiteralPath $BuiltLua -PathType Container)) { Fail "built loose Lua not found: $BuiltLua" }
Copy-Item -Path (Join-Path $BuiltLua '*') -Destination (Join-Path $FixtureTarget '42\media\lua') -Recurse -Force
# Core's first B42 launch otherwise empties default.txt before mod discovery.
[IO.File]::WriteAllText((Join-Path $CacheDir 'mods\reset-mods-42_00.txt'), 'Private B42 harness profile; no previous-version mods to reset.')
# Core.initOptionsINI copies the shipped mods directory over default.txt on a cold profile.
$OptionsPath = Join-Path $CacheDir 'options.ini'
if (-not (Test-Path -LiteralPath $OptionsPath)) {
    @('version=8','width=1280','height=720','fullScreen=false') | Set-Content -LiteralPath $OptionsPath -Encoding ascii
}
if (-not $Manual) {
    $options=[IO.File]::ReadAllText($OptionsPath)
    if ($options -notmatch '(?m)^termsOfServiceVersion=1\r?$') {
        Fail 'Run once with -Manual to complete the native first-run terms screen, then close the game. The harness does not accept legal terms.'
    }
    # Composed window capture is reliable; exclusive-fullscreen capture may be black.
    [IO.File]::WriteAllText($OptionsPath,[regex]::Replace($options,'(?m)^fullScreen=.*$','fullScreen=false'))
}
@('VERSION = 1,','','mods','{','    mod = pzopt-harness,','}','','maps','{','}') | Set-Content -LiteralPath (Join-Path $CacheDir 'mods\default.txt') -Encoding ascii
@("input_thread=$([int](-not $Manual -and -not $Combat))","input_thread_combat=$([int]($Combat -and -not $Manual))",'dashboard=disabled','launcher_read_only=1') | Set-Content -LiteralPath $FlagPath -Encoding ascii
# Isolate both Config's pre-ZomboidFileSystem read and every game cache/save/mod lookup.
$PrivateJar = Join-Path $CacheDir 'runtime\projectzomboid.jar'
New-Item -ItemType Directory -Force -Path (Split-Path -Parent $PrivateJar) | Out-Null
Copy-Item -LiteralPath $GameJar -Destination $PrivateJar -Force
$Cp = "$Classes;$PrivateJar"
 $vmArgs = @('-Djava.awt.headless=true','--enable-native-access=ALL-UNNAMED','--add-exports=java.base/jdk.internal.misc=ALL-UNNAMED','-Xmx3072m','-Dzomboid.steam=0','-Dzomboid.znetlog=1',"-Djava.library.path=$PZ\win64;$PZ",'-XX:-CreateCoredumpOnCrash','-XX:-OmitStackTraceInFastThrow',"-Dpzopt.userOptionsFile=$CacheDir\pzopt\options.ini",'-cp',$Cp,'zombie.gameStates.MainScreenState',"-cachedir=$CacheDir",'-nosteam')
 if ($Combat) { $vmArgs = @('-Dpzopt.noClickToStart=true','-Dpzopt.noIntroWait=true') + $vmArgs }
 if ($ImGui) {
     if ($Combat -and -not $Manual) { Fail 'Use -Combat and -ImGui as separate automated scenarios.' }
     $ImGuiIni = Join-Path $CacheDir 'imgui.ini'
     if (-not $Manual) {
         @('[Window][Viewport]','Pos=20,40','Size=1000,650','Collapsed=0','') | Set-Content -LiteralPath $ImGuiIni -Encoding ascii
     }
     $vmArgs = @("-Dpzopt.imguiIniFile=$ImGuiIni") + $vmArgs + '-imgui'
 }
 if (-not $Manual) { $vmArgs = @('-Dpzopt.frameCapFps=60') + $vmArgs }
 $psi = [Diagnostics.ProcessStartInfo]::new()
 $psi.FileName = $Java; $psi.WorkingDirectory = $PZ; $psi.UseShellExecute = $false; $psi.CreateNoWindow = $false
 $psi.RedirectStandardOutput = $true; $psi.RedirectStandardError = $true
 $psi.Arguments = (($vmArgs | ForEach-Object { Quote-Argument ([string]$_) }) -join ' ')
 $psi.EnvironmentVariables.Remove('_JAVA_OPTIONS') | Out-Null
 $psi.EnvironmentVariables.Remove('JAVA_TOOL_OPTIONS') | Out-Null
 $psi.EnvironmentVariables['SteamAppId'] = '108600'
 Log "JAVA command=$Java $($psi.Arguments)"
 $Game = [Diagnostics.Process]::Start($psi)
 if (-not $Game) { Fail 'could not start bundled Java process' }
 $stdoutAction = Register-ObjectEvent -InputObject $Game -EventName OutputDataReceived -MessageData $Console -Action {
     if ($EventArgs.Data) { [IO.File]::AppendAllText($Event.MessageData, $EventArgs.Data + [Environment]::NewLine) }
 }
 $stderrAction = Register-ObjectEvent -InputObject $Game -EventName ErrorDataReceived -MessageData ($Console + '.stderr') -Action {
     if ($EventArgs.Data) { [IO.File]::AppendAllText($Event.MessageData, $EventArgs.Data + [Environment]::NewLine) }
 }
 $Game.BeginOutputReadLine(); $Game.BeginErrorReadLine()
 Log "STARTED pid=$($Game.Id); installed profile/launcher untouched"
try {

if ($Manual) {
    Log 'MANUAL READY: game is interactive. Perform physical input on the real mouse/keyboard; this script makes no hardware-rate claim.'
    $Game.WaitForExit()
    Log "MANUAL EXIT pid=$($Game.Id) exit=$($Game.ExitCode) normal window close"
    exit $Game.ExitCode
}

$TargetHwnd = [IntPtr]::Zero; $WindowOrigin = $null; $ClientWidth = 0; $ClientHeight = 0
$CoreWidth = 0.0; $CoreHeight = 0.0; $ScaleX = 1.0; $ScaleY = 1.0; $readyLine = $null
if (-not ('PzOptInputNative' -as [type])) {
Add-Type -TypeDefinition @'
using System;
using System.Runtime.InteropServices;
public static class PzOptInputNative {
 [StructLayout(LayoutKind.Sequential)] public struct RECT { public int Left,Top,Right,Bottom; }
 [StructLayout(LayoutKind.Sequential)] public struct POINT { public int X,Y; }
 [StructLayout(LayoutKind.Sequential)] public struct MOUSEINPUT { public int dx,dy; public uint mouseData,dwFlags,time; public UIntPtr dwExtraInfo; }
 [StructLayout(LayoutKind.Sequential)] public struct KEYBDINPUT { public ushort wVk,wScan; public uint dwFlags,time; public UIntPtr dwExtraInfo; }
 [StructLayout(LayoutKind.Sequential)] public struct HARDWAREINPUT { public uint uMsg; public ushort wParamL,wParamH; }
 [StructLayout(LayoutKind.Explicit,Size=40)] public struct INPUT { [FieldOffset(0)] public uint type; [FieldOffset(8)] public MOUSEINPUT mi; [FieldOffset(8)] public KEYBDINPUT ki; [FieldOffset(8)] public HARDWAREINPUT hi; }
 [DllImport("user32.dll")] public static extern bool GetClientRect(IntPtr h, out RECT r);
 [DllImport("user32.dll")] public static extern bool ClientToScreen(IntPtr h, ref POINT p);
 [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr h);
 [DllImport("user32.dll")] public static extern bool ShowWindow(IntPtr h,int cmd);
 [DllImport("user32.dll")] public static extern bool IsIconic(IntPtr h);
 [DllImport("user32.dll")] public static extern IntPtr GetForegroundWindow();
 [DllImport("user32.dll")] public static extern uint SendInput(uint n, INPUT[] inputs, int size);
 [DllImport("user32.dll")] public static extern IntPtr SetThreadDpiAwarenessContext(IntPtr value);
 [DllImport("user32.dll")] public static extern bool GetCursorPos(out POINT p);
 [DllImport("kernel32.dll")] private static extern uint GetCurrentThreadId();
 [DllImport("user32.dll")] private static extern uint GetWindowThreadProcessId(IntPtr h, out uint pid);
 [DllImport("user32.dll")] private static extern bool AttachThreadInput(uint source,uint target,bool attach);
 public static bool FocusWindow(IntPtr h) {
   uint current=GetCurrentThreadId();
   uint foreground=GetWindowThreadProcessId(GetForegroundWindow(),out _);
   bool attached=foreground!=0 && foreground!=current && AttachThreadInput(current,foreground,true);
   try { return SetForegroundWindow(h); }
   finally { if(attached) AttachThreadInput(current,foreground,false); }
 }
 public const uint INPUT_MOUSE=0, INPUT_KEYBOARD=1, MOVE=0x0001, LEFTDOWN=0x0002, LEFTUP=0x0004, WHEEL=0x0800, ABSOLUTE=0x8000, VIRTUALDESK=0x4000, KEYUP=0x0002, UNICODE=0x0004;
 public static double StreamMoves(int ax,int ay,int cx,int cy,int count,int hz) {
   const int batch = 8;
   var input = new INPUT[batch];
   for (int j=0;j<batch;j++) {
     input[j].mi.dwFlags = MOVE | ABSOLUTE | VIRTUALDESK | 0x2000; // MOVE_NOCOALESCE
     input[j].mi.dx = (j & 1) == 0 ? ax : cx;
     input[j].mi.dy = (j & 1) == 0 ? ay : cy;
   }
   int size = Marshal.SizeOf<INPUT>();
   long start = System.Diagnostics.Stopwatch.GetTimestamp();
   long frequency = System.Diagnostics.Stopwatch.Frequency;
   for (int i=0;i<count;i+=batch) {
     long due = start + (long)i * frequency / hz;
     while (System.Diagnostics.Stopwatch.GetTimestamp() < due) System.Threading.Thread.SpinWait(8);
     uint n = (uint)Math.Min(batch,count-i);
     if (SendInput(n,input,size) != n) throw new InvalidOperationException("SendInput stream failed");
   }
   return (System.Diagnostics.Stopwatch.GetTimestamp() - start) / (double)frequency;
 }
 public static double StreamRelative(int dx,int dy,int count,int hz) {
   var input = new INPUT[1];
   input[0].mi.dwFlags = MOVE | 0x2000; // no native absolute-position event, MOVE_NOCOALESCE
   input[0].mi.dx = dx; input[0].mi.dy = dy;
   int size = Marshal.SizeOf<INPUT>();
   long start = System.Diagnostics.Stopwatch.GetTimestamp();
   long frequency = System.Diagnostics.Stopwatch.Frequency;
   for (int i=0;i<count;i++) {
     long due = start + (long)i * frequency / hz;
     while (System.Diagnostics.Stopwatch.GetTimestamp() < due) System.Threading.Thread.SpinWait(8);
     if (SendInput(1,input,size) != 1) throw new InvalidOperationException("relative SendInput stream failed");
   }
   return (System.Diagnostics.Stopwatch.GetTimestamp() - start) / (double)frequency;
 }
}
'@
}
function Send-Mouse([int]$X,[int]$Y,[uint32]$Flags,[uint32]$Data=0) {
    $screenX = [int]($WindowOrigin.X + $ViewportOffsetX + $X * $ScaleX); $screenY = [int]($WindowOrigin.Y + $ViewportOffsetY + $Y * $ScaleY)
    $bounds = [System.Windows.Forms.SystemInformation]::VirtualScreen
    $ax = [int](($screenX - $bounds.Left) * 65535 / [Math]::Max(1,$bounds.Width - 1)); $ay = [int](($screenY - $bounds.Top) * 65535 / [Math]::Max(1,$bounds.Height - 1))
    $mouse = [PzOptInputNative+MOUSEINPUT]::new()
    $mouse.dx=$ax; $mouse.dy=$ay; $mouse.mouseData=$Data; $mouse.dwFlags=$Flags -bor [PzOptInputNative]::ABSOLUTE -bor [PzOptInputNative]::VIRTUALDESK
    $input = [PzOptInputNative+INPUT]::new(); $input.type=0; $input.mi=$mouse
    $sent=[PzOptInputNative]::SendInput(1,@($input),[Runtime.InteropServices.Marshal]::SizeOf([type][PzOptInputNative+INPUT]))
    if ($sent -ne 1) { Fail "SendInput mouse flags=$Flags returned $sent" }
}
function Send-Key([uint16]$Vk,[bool]$Up=$false) {
    $key = [PzOptInputNative+KEYBDINPUT]::new()
    $key.wVk=$Vk; $key.dwFlags=if($Up){[PzOptInputNative]::KEYUP}else{0}
    $input = [PzOptInputNative+INPUT]::new(); $input.type=1; $input.ki=$key
    $sent=[PzOptInputNative]::SendInput(1,@($input),[Runtime.InteropServices.Marshal]::SizeOf([type][PzOptInputNative+INPUT]))
    if ($sent -ne 1) { Fail "SendInput key $Vk returned $sent" }
}
function Send-Unicode([string]$Text) {
    foreach ($ch in $Text.ToCharArray()) {
        foreach ($up in @($false,$true)) {
            $key=[PzOptInputNative+KEYBDINPUT]::new()
            $key.wScan=[uint16][char]$ch
            $key.dwFlags=[PzOptInputNative]::UNICODE -bor $(if($up){[PzOptInputNative]::KEYUP}else{0})
            $input=[PzOptInputNative+INPUT]::new(); $input.type=1; $input.ki=$key
            if([PzOptInputNative]::SendInput(1,@($input),[Runtime.InteropServices.Marshal]::SizeOf([type][PzOptInputNative+INPUT])) -ne 1){ Fail 'SendInput Unicode text failed' }
        }
    }
}
function Refresh-Window {
    $Game.Refresh(); $h=$Game.MainWindowHandle
    if ($h -eq [IntPtr]::Zero) { return $false }
    $r=New-Object PzOptInputNative+RECT
    if (-not [PzOptInputNative]::GetClientRect($h,[ref]$r)) { return $false }
    $pt=New-Object PzOptInputNative+POINT; $pt.X=0; $pt.Y=0; [void][PzOptInputNative]::ClientToScreen($h,[ref]$pt)
    $script:TargetHwnd=$h; $script:WindowOrigin=$pt; $script:ClientWidth=$r.Right-$r.Left; $script:ClientHeight=$r.Bottom-$r.Top
    if (-not $ViewportMapped -and $CoreWidth -gt 0 -and $CoreHeight -gt 0) {
        $script:ScaleX = $ClientWidth/[double]$CoreWidth; $script:ScaleY = $ClientHeight/[double]$CoreHeight
    }
    return ($ClientWidth -gt 100 -and $ClientHeight -gt 100)
}
function Capture-Window([string]$Name) {
    if (-not (Refresh-Window)) { Fail 'Cannot capture the launched game window' }
    $bitmap = [Drawing.Bitmap]::new($ClientWidth,$ClientHeight)
    $graphics = [Drawing.Graphics]::FromImage($bitmap)
    try {
        $graphics.CopyFromScreen($WindowOrigin.X,$WindowOrigin.Y,0,0,$bitmap.Size)
        $bitmap.Save((Join-Path $LogDir $Name),[Drawing.Imaging.ImageFormat]::Png)
    } finally { $graphics.Dispose(); $bitmap.Dispose() }
}
function Assert-RawInput {
    $text=[IO.File]::ReadAllText($Console)
    if ($text -notmatch '\[pzopt-raw\] registered mouse: GetRawInputBuffer' -or
        $text -notmatch '\[pzopt-raw\] GetRawInputBuffer returned [1-9]\d* native records') {
        Fail 'native buffered Raw Input was not exercised; GLFW callbacks alone are not an acceptable pass'
    }
    Log 'PASS native GetRawInputBuffer returned records; synthetic injection is not a physical polling-rate measurement'
}
# Screen metrics use the actual client area and matching game UI coordinates (fixture uses screen fractions).
Add-Type -AssemblyName System.Windows.Forms
[void][PzOptInputNative]::SetThreadDpiAwarenessContext([IntPtr](-4))
$deadline=[DateTime]::UtcNow.AddSeconds($ReadyTimeoutSeconds)
while ([DateTime]::UtcNow -lt $deadline) {
    if ($Game.HasExited) { Fail "game exited before its window appeared (exit=$($Game.ExitCode)); see $Console" }
    if (Refresh-Window) { break }
    Start-Sleep -Milliseconds 250
}
if ($TargetHwnd -eq [IntPtr]::Zero) { Fail "window did not appear within ${ReadyTimeoutSeconds}s" }
if ($Combat) {
    function Wait-CombatMarker([string]$Pattern,[int]$Seconds) {
        $until=[DateTime]::UtcNow.AddSeconds($Seconds)
        while ([DateTime]::UtcNow -lt $until) {
            if (Test-Path $Console) {
                $text=[IO.File]::ReadAllText($Console)
                if ($text -match '\[pzopt-combat\] FAIL') { Fail "combat fixture failed; see $Console" }
                $match=[regex]::Match($text,$Pattern)
                if ($match.Success) { return $match }
            }
            if ($Game.HasExited) { Fail "game exited before combat marker $Pattern; see $Console" }
            Start-Sleep -Milliseconds 30
        }
        Fail "combat marker timeout: $Pattern; see $Console"
    }
    function Send-Button([int]$Button,[bool]$Down,[int]$X,[int]$Y) {
        switch ($Button) {
            0 { $flags=if($Down){2}else{4}; $data=0 }
            1 { $flags=if($Down){8}else{16}; $data=0 }
            2 { $flags=if($Down){32}else{64}; $data=0 }
            3 { $flags=if($Down){128}else{256}; $data=1 }
            4 { $flags=if($Down){128}else{256}; $data=2 }
            default { Fail "unsupported physical mouse button $Button" }
        }
        Send-Mouse $X $Y $flags $data
    }
    foreach ($stage in @('ranged','melee')) {
        $pattern="\[pzopt-combat\] READY stage=$stage screen=(\d+)x(\d+) b=(\d+),(\d+) c=(\d+),(\d+) attack_button=(\d+) aim_button=(\d+) aim_hold_ms=(\d+) press_ms=(\d+)"
        $m=Wait-CombatMarker $pattern $ReadyTimeoutSeconds
        $CoreWidth=[double]$m.Groups[1].Value; $CoreHeight=[double]$m.Groups[2].Value
        $bx=[int]$m.Groups[3].Value; $by=[int]$m.Groups[4].Value
        $cx=[int]$m.Groups[5].Value; $cy=[int]$m.Groups[6].Value
        $attack=[int]$m.Groups[7].Value; $aim=[int]$m.Groups[8].Value
        $holdMs=[int]$m.Groups[9].Value; $pressMs=[int]$m.Groups[10].Value
        [void][PzOptInputNative]::ShowWindow($TargetHwnd,9)
        [void][PzOptInputNative]::FocusWindow($TargetHwnd)
        Start-Sleep -Milliseconds 150
        if ([PzOptInputNative]::GetForegroundWindow() -ne $TargetHwnd -or -not (Refresh-Window)) { Fail 'combat window is not foreground; no input sent' }
        Send-Mouse $bx $by ([PzOptInputNative]::MOVE)
        Send-Button $aim $true $bx $by
        $stall=Wait-CombatMarker "\[pzopt-combat\] STALL_BEGIN stage=$stage ms=\d+ b=(\d+),(\d+) c=(\d+),(\d+)" 15
        $bx=[int]$stall.Groups[1].Value; $by=[int]$stall.Groups[2].Value
        $cx=[int]$stall.Groups[3].Value; $cy=[int]$stall.Groups[4].Value
        Capture-Window "combat-$stage-ready.png"
        Send-Mouse $bx $by ([PzOptInputNative]::MOVE)
        Start-Sleep -Milliseconds $holdMs
        Send-Button $attack $true $bx $by
        Start-Sleep -Milliseconds $pressMs
        Send-Button $attack $false $bx $by
        Send-Mouse $cx $cy ([PzOptInputNative]::MOVE)
        # A second complete press in the same frozen batch must neither bypass cooldown nor replace accepted B.
        Send-Button $attack $true $cx $cy
        Start-Sleep -Milliseconds $pressMs
        Send-Button $attack $false $cx $cy
        Send-Button $aim $false $cx $cy
        Log "INJECT combat=$stage B=$bx,$by C=$cx,$cy attack=$attack aim=$aim during game-thread stall"
        [void](Wait-CombatMarker "\[pzopt-combat\] OUTCOME stage=$stage result=PASS " 20)
        Log "PASS native combat stage=$stage"
    }
    if (-not $Game.WaitForExit(30000)) { Fail 'combat game did not close normally' }
    if ($Game.ExitCode -ne 0) { Fail "combat game exited $($Game.ExitCode)" }
    Select-String -LiteralPath $Console -Pattern '\[pzopt-combat\]' | ForEach-Object { $_.Line } | Set-Content -LiteralPath $EventsLog
    Assert-RawInput
    Log "PASS ranged and melee native combat; evidence=$EventsLog"
    exit 0
}
# Lua publishes its exact UI coordinate system in the READY marker; await marker before calculating targets.
$deadline=[DateTime]::UtcNow.AddSeconds($ReadyTimeoutSeconds)
while ([DateTime]::UtcNow -lt $deadline) {
    if ($Game.HasExited) { Fail "game exited before Lua fixture ready (exit=$($Game.ExitCode)); see $Console" }
    if (Test-Path -LiteralPath $Console) {
        $readyLine=Select-String -LiteralPath $Console -Pattern '\[pzopt-input\] READY screen=(\d+)x(\d+) button=([\d,]+) entry=([\d,]+)' | Select-Object -Last 1
        if ($readyLine) { break }
    }
    Start-Sleep -Milliseconds 250
}
if (-not $readyLine) { Fail "Lua fixture READY marker timed out; see $Console and $RunLog" }
$CoreWidth=[double]$readyLine.Matches[0].Groups[1].Value; $CoreHeight=[double]$readyLine.Matches[0].Groups[2].Value
$button=($readyLine.Matches[0].Groups[3].Value -split ',') | ForEach-Object {[double]$_}
$entry=($readyLine.Matches[0].Groups[4].Value -split ',') | ForEach-Object {[double]$_}
if (-not (Refresh-Window)) { Fail 'game window disappeared after Lua ready' }
Log "WINDOW hwnd=$TargetHwnd client=${ClientWidth}x${ClientHeight} UI=${CoreWidth}x${CoreHeight} scaling=$ScaleX,$ScaleY"
[void][PzOptInputNative]::ShowWindow($TargetHwnd,9)
[void][PzOptInputNative]::FocusWindow($TargetHwnd); Start-Sleep -Milliseconds 250
if ([PzOptInputNative]::GetForegroundWindow() -ne $TargetHwnd) { Fail 'game is not foreground; refusing to send input to another window' }
function Read-CursorProbe([int]$Id) {
    [IO.File]::WriteAllText($ArmPath,"calibrate:$Id`n")
    $until=[DateTime]::UtcNow.AddSeconds(5)
    while ([DateTime]::UtcNow -lt $until) {
        $m=[regex]::Match([IO.File]::ReadAllText($Console),"\[pzopt-input\] CALIBRATION id=$Id cursor=(-?\d+),(-?\d+)")
        if ($m.Success) { return @([double]$m.Groups[1].Value,[double]$m.Groups[2].Value) }
        if ($Game.HasExited) { Fail 'game exited during cursor calibration' }
        Start-Sleep -Milliseconds 30
    }
    Fail 'native game cursor did not answer calibration'
}
if ($ImGui) {
    function Sample-ViewportCursor([int]$Id,[int]$X,[int]$Y) {
        Send-Mouse $X $Y ([PzOptInputNative]::MOVE)
        Start-Sleep -Milliseconds 120
        return Read-CursorProbe $Id
    }
    $probe1=@([int]($CoreWidth*0.25),[int]($CoreHeight*0.25))
    $probe2=@([int]($CoreWidth*0.5),[int]($CoreHeight*0.5))
    $one=Sample-ViewportCursor 1 $probe1[0] $probe1[1]
    $two=Sample-ViewportCursor 2 $probe2[0] $probe2[1]
    if ($two[0] -le $one[0] -or $two[1] -le $one[1]) { Fail 'invalid native ImGui viewport coordinate mapping' }
    $newScaleX=($probe2[0]-$probe1[0])*$ScaleX/($two[0]-$one[0])
    $newScaleY=($probe2[1]-$probe1[1])*$ScaleY/($two[1]-$one[1])
    $ViewportOffsetX=$probe1[0]*$ScaleX-$one[0]*$newScaleX
    $ViewportOffsetY=$probe1[1]*$ScaleY-$one[1]*$newScaleY
    $ScaleX=$newScaleX; $ScaleY=$newScaleY; $ViewportMapped=$true
    Log "IMGUI viewport offset=$ViewportOffsetX,$ViewportOffsetY scale=$ScaleX,$ScaleY from native cursor probes"
    # Bring the game viewport ahead of the native debug windows, without touching a fixture control.
    $focusX=[int]($CoreWidth*0.78); $focusY=[int]($CoreHeight*0.78)
    Send-Mouse $focusX $focusY ([PzOptInputNative]::MOVE)
    Send-Mouse $focusX $focusY ([PzOptInputNative]::LEFTDOWN)
    Start-Sleep -Milliseconds 60
    Send-Mouse $focusX $focusY ([PzOptInputNative]::LEFTUP)
    Start-Sleep -Milliseconds 200
}
if ($Sensitivity -or $Acceleration) {
    if ($ImGui) { Fail 'Run motion-control checks without -ImGui for exact desktop-pixel motion assertions.' }
    function Send-Relative([int]$X,[int]$Y) {
        if ([PzOptInputNative]::GetForegroundWindow() -ne $TargetHwnd) { Fail 'lost foreground during sensitivity stimulus' }
        $mouse=[PzOptInputNative+MOUSEINPUT]::new()
        $mouse.dx=$X; $mouse.dy=$Y; $mouse.dwFlags=[PzOptInputNative]::MOVE -bor 0x2000
        $input=[PzOptInputNative+INPUT]::new(); $input.mi=$mouse
        if ([PzOptInputNative]::SendInput(1,@($input),[Runtime.InteropServices.Marshal]::SizeOf([type][PzOptInputNative+INPUT])) -ne 1) { Fail 'sensitivity SendInput failed' }
    }
    function Apply-MouseOption([string]$Key,[string]$Value,[int]$Id) {
        [IO.File]::WriteAllText($ArmPath,"option:${Id}:${Key}:$Value`n")
        $until=[DateTime]::UtcNow.AddSeconds(15)
        $marker="[pzopt-input] OPTION id=$Id key=$Key value=$Value"
        while (-not [IO.File]::ReadAllText($Console).Contains($marker)) {
            if ($Game.HasExited -or [DateTime]::UtcNow -gt $until) { Fail "mouse option UI apply failed: $marker" }
            Start-Sleep -Milliseconds 50
        }
    }
}
if ($Sensitivity) {
    $sample=10
    foreach ($factor in @('0.5','2.0','1.0')) {
        $gain=[double]::Parse($factor,[Globalization.CultureInfo]::InvariantCulture)
        [IO.File]::WriteAllText($ArmPath,"sensitivity:${sample}:$factor`n")
        $until=[DateTime]::UtcNow.AddSeconds(15)
        $marker="[pzopt-input] SENSITIVITY id=$sample value=$factor"
        while (-not [IO.File]::ReadAllText($Console).Contains($marker)) {
            if ($Game.HasExited -or [DateTime]::UtcNow -gt $until) { Fail "sensitivity UI apply failed: $marker" }
            Start-Sleep -Milliseconds 50
        }
        Start-Sleep -Milliseconds 500
        Capture-Window "sensitivity-$factor.png"
        $sample++
        Send-Mouse ([int]($CoreWidth*0.18)) ([int]($CoreHeight*0.58)) ([PzOptInputNative]::MOVE)
        Start-Sleep -Milliseconds 120
        $before=Read-CursorProbe $sample; $sample++
        if ([Math]::Abs($before[0]-$CoreWidth*0.18) -gt 2 -or [Math]::Abs($before[1]-$CoreHeight*0.58) -gt 2) { Fail "absolute motion was scaled by sensitivity $factor" }
        Send-Relative 20 -12
        Start-Sleep -Milliseconds 120
        $after=Read-CursorProbe $sample; $sample++
        $dx=$after[0]-$before[0]; $dy=$after[1]-$before[1]
        if ([Math]::Abs($dx-20*$gain/$ScaleX) -gt 1 -or [Math]::Abs($dy+12*$gain/$ScaleY) -gt 1) { Fail "sensitivity $factor bulk motion mismatch: $dx,$dy" }
        Log "PASS sensitivity=$factor relative=20,-12 game_delta=$dx,$dy"
        $before=$after
        for ($packet=0; $packet -lt 20; $packet++) { Send-Relative 1 -1; Start-Sleep -Milliseconds 15 }
        Start-Sleep -Milliseconds 120
        $after=Read-CursorProbe $sample; $sample++
        $dx=$after[0]-$before[0]; $dy=$after[1]-$before[1]
        if ([Math]::Abs($dx-20*$gain/$ScaleX) -gt 1 -or [Math]::Abs($dy+20*$gain/$ScaleY) -gt 1) { Fail "sensitivity $factor lost fractional motion: $dx,$dy" }
        Log "PASS sensitivity=$factor twenty_single_counts game_delta=$dx,$dy"
        for ($packet=0; $packet -lt 20; $packet++) { Send-Relative -1 1; Start-Sleep -Milliseconds 15 }
        Start-Sleep -Milliseconds 120
        $returned=Read-CursorProbe $sample; $sample++
        if ([Math]::Abs($returned[0]-$before[0]) -gt 1 -or [Math]::Abs($returned[1]-$before[1]) -gt 1) { Fail "sensitivity $factor signed reversal drift" }
        Log "PASS sensitivity=$factor signed reversal returns to origin"
    }
    [IO.File]::WriteAllText($ArmPath,"input-fixture`n")
    $until=[DateTime]::UtcNow.AddSeconds(5)
    while (-not [IO.File]::ReadAllText($Console).Contains('[pzopt-input] SENSITIVITY options closed')) {
        if ($Game.HasExited -or [DateTime]::UtcNow -gt $until) { Fail 'sensitivity options did not close' }
        Start-Sleep -Milliseconds 30
    }
}
if ($Acceleration) {
    $sample=100
    foreach ($setting in @(
        @('mouseSensitivity','1.0'),
        @('mouseAccelerationOnsetCps','1000'),
        @('mouseAccelerationSlopePctPerKcps','50'),
        @('mouseAccelerationCapPct','200'),
        @('mouseAcceleration','false'))) {
        Apply-MouseOption $setting[0] $setting[1] $sample
        $sample++
    }
    function Measure-RelativeStream([int]$Count,[int]$Dx,[int]$Hz) {
        if ([PzOptInputNative]::GetForegroundWindow() -ne $TargetHwnd) { Fail 'lost foreground during acceleration stimulus' }
        Send-Mouse ([int]($CoreWidth*0.18)) ([int]($CoreHeight*0.58)) ([PzOptInputNative]::MOVE)
        Start-Sleep -Milliseconds 130
        $before=Read-CursorProbe $script:sample; $script:sample++
        $seconds=[PzOptInputNative]::StreamRelative($Dx,0,$Count,$Hz)
        Start-Sleep -Milliseconds 130
        $after=Read-CursorProbe $script:sample; $script:sample++
        return @(([double]$after[0] - [double]$before[0]), ([double]$after[1] - [double]$before[1]), $seconds)
    }
    $linear=Measure-RelativeStream 120 6 2000
    if ([Math]::Abs($linear[0]-720/$ScaleX) -gt 3 -or [Math]::Abs($linear[1]) -gt 1) { Fail "disabled acceleration changed linear motion: $($linear[0]),$($linear[1])" }
    Log "PASS acceleration disabled synthetic_counts=720 game_dx=$($linear[0])"
    Apply-MouseOption mouseAcceleration true $sample; $sample++
    Capture-Window 'acceleration-enabled.png'
    $curve=Measure-RelativeStream 120 6 2000
    if ($curve[0] -le 1.3*$linear[0] -or $curve[0] -gt 2.1*$linear[0] -or [Math]::Abs($curve[1]) -gt 1) {
        Fail "enabled curve did not accelerate bounded fast raw motion: linear=$($linear[0]) curved=$($curve[0]) duration=$($curve[2])"
    }
    Log "PASS acceleration enabled synthetic_counts=720 game_dx=$($curve[0]) vs linear=$($linear[0]) submitted_s=$($curve[2]); not physical hardware rate"
    Apply-MouseOption mouseAccelerationCapPct 150 $sample; $sample++
    $limited=Measure-RelativeStream 120 6 2000
    if ($limited[0] -le 1.15*$linear[0] -or $limited[0] -gt 1.6*$linear[0] -or $limited[0] -ge $curve[0]) {
        Fail "live acceleration curve cap not respected: linear=$($linear[0]) limited=$($limited[0]) previous=$($curve[0])"
    }
    Log "PASS acceleration cap=150 percent synthetic_counts=720 game_dx=$($limited[0])"
    Apply-MouseOption mouseAcceleration false $sample; $sample++
    $restored=Measure-RelativeStream 120 6 2000
    if ([Math]::Abs($restored[0]-$linear[0]) -gt 3) { Fail "acceleration off did not restore linear motion: before=$($linear[0]) after=$($restored[0])" }
    Log "PASS acceleration toggled off live game_dx=$($restored[0])"
    [IO.File]::WriteAllText($ArmPath,"input-fixture`n")
    $until=[DateTime]::UtcNow.AddSeconds(5)
    while (-not [IO.File]::ReadAllText($Console).Contains('[pzopt-input] SENSITIVITY options closed')) {
        if ($Game.HasExited -or [DateTime]::UtcNow -gt $until) { Fail 'acceleration options did not close' }
        Start-Sleep -Milliseconds 30
    }
}
# Exercise relative raw counts as well as the absolute records used by deterministic click scenarios.
Send-Mouse ([int]($CoreWidth*0.18)) ([int]($CoreHeight*0.58)) ([PzOptInputNative]::MOVE)
Start-Sleep -Milliseconds 120
$beforeRaw=Read-CursorProbe 3
$relative=[PzOptInputNative+INPUT]::new()
$relativeMouse=[PzOptInputNative+MOUSEINPUT]::new()
$relativeMouse.dx=17; $relativeMouse.dy=-11; $relativeMouse.dwFlags=[PzOptInputNative]::MOVE
$relative.type=0; $relative.mi=$relativeMouse
if ([PzOptInputNative]::SendInput(1,@($relative),[Runtime.InteropServices.Marshal]::SizeOf([type][PzOptInputNative+INPUT])) -ne 1) { Fail 'relative raw stimulus SendInput failed' }
Start-Sleep -Milliseconds 120
$afterRaw=Read-CursorProbe 4
$rawDX=$afterRaw[0]-$beforeRaw[0]; $rawDY=$afterRaw[1]-$beforeRaw[1]
if ([Math]::Abs($rawDX-17/$ScaleX) -gt 2 -or [Math]::Abs($rawDY+11/$ScaleY) -gt 2) {
    Fail "raw relative cursor mismatch: game delta=$rawDX,$rawDY expected=$((17/$ScaleX)),$((-11/$ScaleY))"
}
Log "PASS raw relative counts=17,-11 game_cursor_delta=$rawDX,$rawDY scale=$ScaleX,$ScaleY"
Capture-Window 'ready.png'
if ($Jdk -and (Test-Path -LiteralPath (Join-Path $Jdk 'bin\jcmd.exe'))) {
    & (Join-Path $Jdk 'bin\jcmd.exe') $Game.Id Thread.print | Set-Content -LiteralPath (Join-Path $LogDir 'threads.txt')
    if ($LASTEXITCODE -ne 0) { Fail 'Could not capture native game thread ownership' }
}
[IO.File]::WriteAllText($ArmPath,"start`n")
Log 'ARMED fixture; waiting for in-game deliberate game-thread stall marker'
$deadline=[DateTime]::UtcNow.AddSeconds(15)
while ([DateTime]::UtcNow -lt $deadline) {
    $lines=if(Test-Path $Console){[IO.File]::ReadAllText($Console)}else{''}
    if ($lines -match '\[pzopt-input\] STALL_BEGIN') { break }
    Start-Sleep -Milliseconds 30
}
if ($lines -notmatch '\[pzopt-input\] STALL_BEGIN') { Fail 'fixture did not begin the requested stall within 15s' }
# A-to-B press/release is injected while the game's main-menu update thread is stalled; the owner continues to collect.
$A=@([int]($CoreWidth*0.18),[int]($CoreHeight*0.58)); $B=@([int]$button[0],[int]$button[1]); $C=@([int]($CoreWidth*0.78),[int]($CoreHeight*0.78))
Send-Mouse $A[0] $A[1] ([PzOptInputNative]::MOVE); Send-Mouse $A[0] $A[1] ([PzOptInputNative]::LEFTDOWN); Start-Sleep -Milliseconds 18
Send-Mouse $B[0] $B[1] ([PzOptInputNative]::MOVE); Send-Mouse $B[0] $B[1] ([PzOptInputNative]::LEFTUP); Send-Mouse $C[0] $C[1] ([PzOptInputNative]::MOVE)
Log "INJECT short A=$($A -join ',') down to B=$($B -join ',') up to C=$($C -join ',') during main-menu update stall"
# Stall ends at 3200ms; the click, drag, wheel, keyboard and text remain real SendInput events afterward.
Start-Sleep -Milliseconds 3150
[void][PzOptInputNative]::FocusWindow($TargetHwnd)
Send-Mouse $B[0] $B[1] ([PzOptInputNative]::MOVE); Send-Mouse $B[0] $B[1] ([PzOptInputNative]::LEFTDOWN); Start-Sleep -Milliseconds 45; Send-Mouse $B[0] $B[1] ([PzOptInputNative]::LEFTUP)
Log "INJECT click at B=$($B -join ',')"
Send-Mouse $B[0] $B[1] ([PzOptInputNative]::MOVE); Send-Mouse $B[0] $B[1] ([PzOptInputNative]::WHEEL) ([uint32]120)
Log "INJECT wheel +120 at B=$($B -join ',')"
Send-Key 0x41; Start-Sleep -Milliseconds 80; Send-Key 0x41 $true
Start-Sleep -Milliseconds 150
Send-Mouse ([int]$entry[0]) ([int]$entry[1]) ([PzOptInputNative]::MOVE); Send-Mouse ([int]$entry[0]) ([int]$entry[1]) ([PzOptInputNative]::LEFTDOWN); Send-Mouse ([int]$entry[0]) ([int]$entry[1]) ([PzOptInputNative]::LEFTUP)
Start-Sleep -Milliseconds 200
Send-Unicode 'z'
Log 'INJECT keyboard A and actual Unicode text z through SendInput into game text entry'
# Text processing is asynchronous; do not let the next independent mouse test steal focus first.
$textDeadline=[DateTime]::UtcNow.AddSeconds(5)
$textObserved=$false
while ([DateTime]::UtcNow -lt $textDeadline -and -not $Game.HasExited) {
    if ([IO.File]::ReadAllText($Console) -match '\[pzopt-input\] CALLBACK text_change ') { $textObserved=$true; break }
    Start-Sleep -Milliseconds 20
}
if (-not $textObserved) { Fail 'native text entry did not consume the injected character before the next scenario' }
# Drag the button, but release outside the button; the stock ISButton callback must cancel its pressed state.
Send-Mouse $B[0] $B[1] ([PzOptInputNative]::MOVE); Send-Mouse $B[0] $B[1] ([PzOptInputNative]::LEFTDOWN); Send-Mouse $C[0] $C[1] ([PzOptInputNative]::MOVE); Start-Sleep -Milliseconds 35; Send-Mouse $C[0] $C[1] ([PzOptInputNative]::LEFTUP)
Log "INJECT drag B=$($B -join ',') to outside-button C=$($C -join ',') then release"
# Focus cancellation: leave button-down while minimizing the real target HWND; restore and release without a second click.
Send-Mouse $B[0] $B[1] ([PzOptInputNative]::MOVE); Send-Mouse $B[0] $B[1] ([PzOptInputNative]::LEFTDOWN); Start-Sleep -Milliseconds 60; [void][PzOptInputNative]::ShowWindow($TargetHwnd,6); Start-Sleep -Milliseconds 400
if (-not [PzOptInputNative]::IsIconic($TargetHwnd)) { Fail 'focus-loss stimulus did not minimize the launched game window' }
[void][PzOptInputNative]::ShowWindow($TargetHwnd,9)
if ([PzOptInputNative]::IsIconic($TargetHwnd)) { Fail 'could not restore the launched game window after focus-loss stimulus' }
[void][PzOptInputNative]::FocusWindow($TargetHwnd); Start-Sleep -Milliseconds 300
if ([PzOptInputNative]::GetForegroundWindow() -ne $TargetHwnd) { Fail 'could not return foreground focus to the launched game window' }
Send-Mouse $B[0] $B[1] ([PzOptInputNative]::LEFTUP)
Log 'INJECT minimize/restore focus loss while left button held; release after refocus'
# Synthetic 8kHz requested rate; NOCOALESCE requests delivery of individual mouse-move messages.
[IO.File]::WriteAllText($BurstPath,"synthetic-events=8000`n")
$bounds=[System.Windows.Forms.SystemInformation]::VirtualScreen
$ax=[int](($WindowOrigin.X+$ViewportOffsetX+$A[0]*$ScaleX-$bounds.Left)*65535/[Math]::Max(1,$bounds.Width-1))
$ay=[int](($WindowOrigin.Y+$ViewportOffsetY+$A[1]*$ScaleY-$bounds.Top)*65535/[Math]::Max(1,$bounds.Height-1))
$cx=[int](($WindowOrigin.X+$ViewportOffsetX+$C[0]*$ScaleX-$bounds.Left)*65535/[Math]::Max(1,$bounds.Width-1))
$cy=[int](($WindowOrigin.Y+$ViewportOffsetY+$C[1]*$ScaleY-$bounds.Top)*65535/[Math]::Max(1,$bounds.Height-1))
$seconds=[PzOptInputNative]::StreamMoves($ax,$ay,$cx,$cy,8000,8000)
Log "SYNTHETIC_STREAM submitted=8000 requested_hz=8000 batch=8 duration_s=$seconds submission_hz=$(8000/$seconds); not physical hardware, uniform 125us spacing, or measured callback rate"
Start-Sleep -Seconds 2
Remove-Item -LiteralPath $BurstPath -Force
Capture-Window 'after-input.png'
[IO.File]::WriteAllText($DonePath,"done`n")
Log 'Requested fixture normal Core.quit; waiting for Lua callback assertions and game exit'
$deadline=[DateTime]::UtcNow.AddSeconds(30)
while([DateTime]::UtcNow -lt $deadline -and -not $Game.HasExited){ Start-Sleep -Milliseconds 250; $Game.Refresh() }
if(-not $Game.HasExited){ Fail "game did not close normally after completion marker; pid=$($Game.Id); see $Console" }
if(Test-Path $Console){
    $consoleText=[IO.File]::ReadAllText($Console)
    $matches=[regex]::Matches($consoleText,'\[pzopt-input\] (CALLBACK|ASSERT|PASS|FAIL|DONE).*')
    foreach($m in $matches){Add-Content -LiteralPath $EventsLog -Value $m.Value -Encoding utf8}
    $required=@('ASSERT short_press_release','ASSERT click_once','ASSERT drag_release_outside','ASSERT wheel_at_B','ASSERT keyboard_and_text','ASSERT focus_cancel_no_ghost','ASSERT synthetic_burst_observed')
    foreach($mark in $required){if($consoleText -notmatch [regex]::Escape("[pzopt-input] $mark PASS")){Fail "Lua/UI callback assertion missing or failed: $mark; see $Console and $EventsLog"}}
    if($consoleText -notmatch '\[pzopt-input\] PASS all callback assertions') { Fail "Lua fixture did not emit PASS; see $Console and $EventsLog" }
    if($consoleText -match '\[pzopt-input\] FAIL') { Fail "Lua fixture reported failure; see $Console and $EventsLog" }
} else { Fail "game exited without isolated console log at $Console" }
if($Game.ExitCode -ne 0){Fail "game exited nonzero ($($Game.ExitCode)); see $Console"}
Assert-RawInput
Log "PASS actual Lua/UI callback evidence in $EventsLog; game exited normally (pid=$($Game.Id))"
Log 'DONE'
exit 0
} finally {
    if (-not $Game.HasExited) {
        Log "Requesting normal window close for owned pid=$($Game.Id)"
        [void]$Game.CloseMainWindow()
        if (-not $Game.WaitForExit(15000)) { Log "Owned game did not close; pid=$($Game.Id) retained for diagnosis, no forced termination" }
    }
    foreach ($subscription in @($stdoutAction,$stderrAction)) {
        Unregister-Event -SubscriptionId $subscription.Id -ErrorAction SilentlyContinue
        Remove-Job -Job $subscription -Force -ErrorAction SilentlyContinue
    }
}
