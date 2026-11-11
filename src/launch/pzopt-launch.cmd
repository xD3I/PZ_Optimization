@echo off
rem PZ Optimization: Steam launch option wrapper for Windows on Build 42.22+ (signed projectzomboid.jar).
rem Found and written by tieeeeen1994 (github.com/tieeeeen1994/PZ_Optimization, 2026-11-10), taken over unchanged.
rem
rem Steam > Project Zomboid > Properties > Launch Options (the game folder is where projectzomboid.jar is):
rem   "C:\Windows\System32\conhost.exe" --headless "<game folder>\pzopt-launch.cmd" %command%
rem Keep any other options you use after %command% (for example: ... %command% -debug).
rem
rem Why: on a signed jar the overrides load through a Java agent (-javaagent:pzopt/agent/pzopt-agent.jar in
rem ProjectZomboid64.json). The JVM loads agent libraries before java.dll, and the agent's instrument.dll imports
rem java.dll and jli.dll. java.exe finds them because it sits in jre64\bin; Steam starts ProjectZomboid64.exe from the
rem game folder, where Windows does not look, so the JVM stops with "Could not find agent library instrument on the
rem library path, with error: Can't find dependent libraries" before console.txt is written. Copying the DLLs into the
rem game folder would load java.dll twice; the game's runtime has no jdk.attach, so the agent cannot attach later.
rem So the game's own jre64\bin goes in front of PATH for this launch only, and the game folder is the working
rem directory (the agent path in the launcher JSON is relative to it).
rem
rem %1 is the game exe Steam passes for %command%, the rest are its arguments. conhost --headless keeps the console
rem hidden; cmd waits for the game, so Steam tracks it as running. Without PZ Optimization the game starts as usual.
rem After uninstalling, set the launch options back: this file goes with the uninstall and Steam reports it missing.
set "PATH=%~dp1jre64\bin;%PATH%"
cd /d "%~dp1"
%*
