# ❓ FAQ

**What is PZ_Optimization?**
Performance patches for Project Zomboid Build 42. It is not a Lua mod: it is a set of drop-in Java class files that stand in for some of the game's own classes. They remove the worst stutters in chunk loading, rendering, weather and the loading screens. The game's `projectzomboid.jar` is never modified.

**How much faster is it?**
That depends on your machine and the scene. Some measured examples, stock → optimized:
- Desktop (RTX 4090), 120 km/h drive, uncapped: 167 → 481 fps
- AYANEO handheld (Radeon 890M), walking through Rosewood: 56 → 126 fps
- 2015 laptop (4 cores, GTX 960M), 120 km/h drive: 44 → 68 fps

Every number comes from automated benchmark runs, and they are all public: <https://pzo.diegov.dev>

**Which game version does it need?**
**Build 42.21**, the default Steam branch. On any other version it switches itself off and the game runs as vanilla. When the game updates, a matching release is posted here.

**Windows, Linux, Mac, Steam Deck?**
It works on all three Steam versions (they ship the same jar). The Steam Deck runs the Linux version, so use the Linux command from Desktop Mode.

---

# 📥 Install

**How do I install it?** Close the game first.
**Steam Workshop (easiest):** subscribe to <https://steamcommunity.com/sharedfiles/filedetails/?id=3805285544>, then:
1. Enable PZ_Optimization in the game's Mods list.
2. The main menu shows an install command with a **Copy** button.
3. Paste it into PowerShell (Windows) or a terminal (Linux / Mac), then quit the game. The installer finishes once the game has closed.
4. Start the game again and disable the mod in the Mods list. It was only there to show that window.

**Without the Workshop:**
Windows (PowerShell):
```
irm https://github.com/xD3I/PZ_Optimization/releases/latest/download/install.ps1 | iex
```
Linux / macOS (Terminal):
```
curl -fsSL https://github.com/xD3I/PZ_Optimization/releases/latest/download/install.sh | bash
```

**Why do I have to run a command? Other mods just work.**
The Workshop can only put files in the mods folder, but Java classes only load from the game's own folder, next to the jar. The command copies them there and records every file, so the uninstall removes exactly what it added.

**How do I know it's working?**
Options gets an **Optimizations** tab and Display gets an **Uncapped** frame-rate entry. F9 (L3 + R3 on a controller) opens the in-game performance overlay.

---

# 🔄 Updates and uninstall

**How do I update?**
The main menu has a **PZ OPTIMIZATION** item; when a newer build is out it says "Update available" under it. Open it, click **UPDATE**, then **Update now**, then **Restart game**. Your saves and settings stay. Every new release is announced in this server.

**How do I uninstall?**
In the main menu: **PZ OPTIMIZATION > Uninstall PZ Optimization** (its home page)
⚠️ Do this **before** unsubscribing from the Workshop item. Unsubscribing removes the Workshop copy, not the files installed in the game folder.
From a terminal instead:
Windows: `& ([scriptblock]::Create((irm https://github.com/xD3I/PZ_Optimization/releases/latest/download/install.ps1))) -Uninstall`
Linux / macOS: `curl -fsSL https://github.com/xD3I/PZ_Optimization/releases/latest/download/install.sh | bash -s -- --uninstall`
The jar was never touched, so you don't need to verify the game files in Steam.

**The game doesn't start after a game update!**
A build made for the old version can stop the new game at start-up. Once the matching release is out, run the install command again (it replaces the old build). To get back to vanilla right away, run the uninstall command.

---

# ⚙️ Settings and gameplay

**Do I need to configure anything?**
No. The defaults are what all the numbers were measured with. Every optimization has its own switch in **PZ OPTIMIZATION > Optimizations** (main menu):
- The **Stock game** preset (PZ OPTIMIZATION home page) gives you the vanilla game from the next launch, without uninstalling, so you can compare on your own PC.
- The **Low-end hardware** presets are for 4-core machines.

**PZ OPTIMIZATION > Enhancements:** optional visual features (FSR / DLSS upscaling, HDR, ambient occlusion, sun shadows, reflections, colour grading, and more). Most of them are off by default.

**Does it change gameplay? Is it safe for my saves?**
The save format is untouched, and the mod is built to play exactly like vanilla. Every part of the game it replaces but does not deliberately change is checked automatically against the original game code. If something plays differently from vanilla (spawns, zombies, cars), that's a bug: please report it.

**Multiplayer?**
It is client side only, so there is nothing to install on a server. Joining a vanilla server works (tested against a stock dedicated server). Do **not** install it on a dedicated server. Multiplayer has had less testing than single player.

**Does it work with other mods?**
Lua mods are fine. It only conflicts with mods that replace the same Java classes. Other performance mods (Lua tweaks, launcher flags, texture packs) measured the same as the vanilla game in our tests, so you don't need them alongside this one.

**My fps stops at about 160.**
Turn off Steam's **In-game performance monitor** (Steam Settings > In Game; this is the newer overlay, not the classic Shift+Tab one). It caps the game at around 160 fps.

---

# 🐛 Bugs and help

**I found a bug. What should I send?**
Post it here or open an issue: <https://github.com/xD3I/PZ_Optimization/issues>
Please include:
- your OS and GPU
- what you were doing
- a screenshot or video
- **`console.txt`**, from `%USERPROFILE%\Zomboid` on Windows or `~/Zomboid` on Linux / Mac

⚠️ Copy `console.txt` right after the problem happens: the next launch overwrites it.
Tip: if it still happens with **PZ OPTIMIZATION > "Stock game" preset**, it's a vanilla bug.

**Where's the source code?**
<https://github.com/xD3I/PZ_Optimization>. Every change to the game's code is marked and described.
