require "pzopt/pzopt_text"

-- PZ_Optimization's Steam Workshop item: the install helper, the only Lua the item itself loads (the optimizations are
-- class files the installer copies into the game folder; scripts/workshop.sh stages this file under 42/media/).
--
-- Enabled in the Mods list, it shows at the main menu the install command for this computer, with the item's real
-- folder (the mod's version dir: whatever Steam library it is in) and a Copy button. The installers wait for the game
-- to close, so the flow is copy, paste into PowerShell / a terminal, quit the game. On Linux the clipboard can empty
-- when the game quits: pasting before quitting is also what keeps it there. Once the overrides are installed (their
-- Lua defines PzoptUpdateDialog) it says so once per session and suggests disabling the mod, which only matters for
-- this helper.

require "ISUI/ISPanelJoypad"
require "ISUI/ISButton"
require "ISUI/ISModalDialog"

local MOD_ID = "PZ_Optimization"
local PAGE = "https://steamcommunity.com/sharedfiles/filedetails/?id=3805285544"

PZOptInstallHelper = ISPanelJoypad:derive("PZOptInstallHelper")

-- The installed pzopt Lua (media/lua/client/pzopt/ in the game folder, loaded before any mod) defines this global.
-- Not a pcall on a pzopt Java method: the game logs an exception caught by pcall anyway and shows its red error count.
local function installed()
    return PzoptUpdateDialog ~= nil
end

-- The item's 42/ folder, from the active mod whose id is PZ_Optimization (B42 may list it as "\PZ_Optimization").
local function itemDir()
    local mods = getActivatedMods()
    for i = 0, mods:size() - 1 do
        local id = mods:get(i)
        if id == MOD_ID or string.sub(id, -(#MOD_ID + 1)) == "\\" .. MOD_ID then
            local info = getModInfoByID(id)
            if info and info:getVersionDir() then return info:getVersionDir() end
        end
    end
    return nil
end

local function installCommand(dir)
    if isSystemWindows() then
        local win = string.gsub(dir, "/", "\\")
        return 'powershell -ExecutionPolicy Bypass -File "' .. win .. '\\install.ps1"'
    end
    local quoted = string.gsub(dir, "'", "'\\''")
    return "bash '" .. quoted .. "/install.bash'"
end

-- The install walkthrough (harness/install-walkthrough.py): one frame set per OS under media/ui/pzopt_install/<os>/,
-- listed with each frame's hold time by PZ_Optimization_InstallFrames.lua (loads first: F before H). The game has no
-- GIF decoder before the optimizations are installed, so the animation is PNG frames the window swaps itself.
local function walkthrough()
    local set = PZOptInstallFrames and PZOptInstallFrames[isSystemWindows() and "win" or (isSystemMacOS() and "mac" or "linux")]
    if not set then return nil end
    local frames = {}
    for _, f in ipairs(set) do
        local tex = getTexture(f[1])
        if not tex then return nil end
        table.insert(frames, { tex = tex, ms = f[2] })
    end
    return #frames > 0 and frames or nil
end

local function shellName()
    if isSystemWindows() then return "PowerShell (Start menu, type powershell, Enter)" end
    if isSystemMacOS() then return "Terminal (Applications > Utilities)" end
    return "a terminal"
end

function PZOptInstallHelper:new(command)
    local small = getTextManager():getFontHeight(UIFont.Small)
    local medium = getTextManager():getFontHeight(UIFont.Medium)
    local code = getTextManager():getFontHeight(PzoptText.font(UIFont.Code))
    local lines = {
        "The optimizations are class files for the game folder; Steam downloaded them with",
        "this item, and one command copies them in. The game does not load them from here.",
        "",
        "1. Copy the command below.",
        "2. Open " .. shellName() .. ", paste it, press Enter.",
        "3. Quit the game (QUIT). The installer waits for that, then copies the files.",
        "4. Start the game: Options now has an Optimizations tab.",
        "",
        "Then disable this mod in the Mods list: it only shows this window.",
        "To uninstall later: Options > PZ Optimization > Uninstall PZ Optimization...",
    }
    if not command then
        lines = {
            "The optimizations are class files for the game folder; Steam downloaded them with",
            "this item, and the installer in the item's folder copies them in.",
            "",
            "This window could not find the item's folder; the Workshop page has the command:",
            PAGE,
        }
    end
    local pad = 20
    local frames = walkthrough()
    local aw, ah = 0, 0
    if frames then
        local k = getCore():getScreenHeight() >= 1000 and 0.75 or 0.55   -- 480 x 300 from the 640 x 400 frames
        aw, ah = math.floor(640 * k), math.floor(400 * k)
    end
    local w = math.max(aw, getTextManager():MeasureStringX(UIFont.Medium, PzoptText.text("PZ Optimization is not installed yet")))
    for _, l in ipairs(lines) do w = math.max(w, getTextManager():MeasureStringX(UIFont.Small, PzoptText.text(l))) end
    if command then w = math.max(w, getTextManager():MeasureStringX(PzoptText.font(UIFont.Code), PzoptText.text(command)) + 16) end
    w = math.min(w + 2 * pad, getCore():getScreenWidth() - 40)
    local h = pad + medium + 12 + (frames and (ah + 12) or 0) + #lines * small + (command and (code + 24) or 0) + 12 + 25 + pad
    local o = ISPanelJoypad.new(self, (getCore():getScreenWidth() - w) / 2, (getCore():getScreenHeight() - h) / 2, w, h)
    o.backgroundColor = { r = 0, g = 0, b = 0, a = 0.92 }
    o.borderColor = { r = 1, g = 1, b = 1, a = 0.4 }
    o.moveWithMouse = true
    o.command = command
    o.lines = lines
    o.pad = pad
    o.frames, o.aw, o.ah = frames, aw, ah
    o.frame, o.frameAt = 1, getTimestampMs()
    return o
end

function PZOptInstallHelper:createChildren()
    ISPanelJoypad.createChildren(self)
    local y = self.height - self.pad - 25
    local x = self.pad
    local function add(title, onclick, width)
        local b = ISButton:new(x, y, width, 25, PzoptText.text(title), self, onclick)
        b:initialise()
        b:setWidthToTitle(width)
        self:addChild(b)
        x = x + b:getWidth() + 10
        return b
    end
    if self.command then
        self.copyButton = add("Copy the command", PZOptInstallHelper.onCopy, 140)
    end
    self.pageButton = add("Open the Workshop page", PZOptInstallHelper.onPage, 140)
    self.closeButton = add("Close", PZOptInstallHelper.close, 100)
    self.closeButton:setX(self.width - self.pad - self.closeButton:getWidth())
end

function PZOptInstallHelper:prerender()
    ISPanelJoypad.prerender(self)
    local small = getTextManager():getFontHeight(UIFont.Small)
    local y = self.pad
    self:drawText(PzoptText.text("PZ Optimization is not installed yet"), self.pad, y, 1, 0.85, 0.4, 1, UIFont.Medium)
    y = y + getTextManager():getFontHeight(UIFont.Medium) + 12
    if self.frames then
        local now = getTimestampMs()
        while now - self.frameAt >= self.frames[self.frame].ms do
            self.frameAt = self.frameAt + self.frames[self.frame].ms
            self.frame = self.frame % #self.frames + 1
            if now - self.frameAt > 10000 then self.frameAt = now end   -- after a stall, no catch-up burst
        end
        local x = (self.width - self.aw) / 2
        self:drawTextureScaled(self.frames[self.frame].tex, x, y, self.aw, self.ah, 1, 1, 1, 1)
        self:drawRectBorder(x, y, self.aw, self.ah, 0.5, 0.5, 0.5, 0.5)
        y = y + self.ah + 12
    end
    for i, l in ipairs(self.lines) do
        self:drawText(PzoptText.text(l), self.pad, y, 1, 1, 1, 1, UIFont.Small)
        y = y + small
        if self.command and i == 4 then
            local code = getTextManager():getFontHeight(PzoptText.font(UIFont.Code))
            y = y + 6
            self:drawRect(self.pad, y, self.width - 2 * self.pad, code + 12, 1, 0.12, 0.12, 0.12)
            self:drawRectBorder(self.pad, y, self.width - 2 * self.pad, code + 12, 0.6, 0.5, 0.5, 0.5)
            self:drawText(PzoptText.text(self.command), self.pad + 8, y + 6, 0.6, 1, 0.6, 1, UIFont.Code)
            y = y + code + 18
        end
    end
end

function PZOptInstallHelper:onCopy()
    Clipboard.setClipboard(self.command)
    self.copyButton:setTitle(PzoptText.text("Copied"))
end

function PZOptInstallHelper:onPage()
    openUrl(PAGE)
end

function PZOptInstallHelper:close()
    self:setVisible(false)
    self:removeFromUIManager()
    local joypadData = JoypadState.getMainMenuJoypad()
    if joypadData and joypadData.focus == self then
        joypadData.focus = self.prevFocus
        updateJoypadFocus(joypadData)
    end
    PZOptInstallHelper.instance = nil
end

function PZOptInstallHelper:onJoypadDown(button, joypadData)
    if button == Joypad.AButton and self.command then
        self:onCopy()
    elseif button == Joypad.BButton then
        self:close()
    end
end

local function showInstalledNote()
    local text = "PZ Optimization is installed.\n\nThis Workshop mod only shows the install command,\nso you can disable it in the Mods list."
    local modal = ISModalDialog:new(getCore():getScreenWidth() / 2 - 180, getCore():getScreenHeight() / 2 - 60, 360, 120,
        PzoptText.text(text), false, nil, nil)
    modal:initialise()
    modal:setCapture(true)
    modal:setAlwaysOnTop(true)
    modal:addToUIManager()
end

local noteShown = false

-- harness/uninstall-e2e.sh: with Zomboid/Lua/pzopt-e2e-helper.txt present the window is driven: the command logged,
-- Copy pressed, the game quit once the script has started the pasted command (Zomboid/Lua/pzopt-e2e-quit.txt), or
-- 4 s after the installed note. Players never have the file.
local function e2eFile(name)
    local ok, r = pcall(getFileReader, name, false)
    if not ok or not r then return false end
    pcall(function() r:close() end)
    return true
end

local drive = nil
local function driveTick()
    local now = getTimestampMs()
    if not drive or now < drive.at then return end
    local panel = PZOptInstallHelper.instance
    if drive.step == "note" and drive.shot then
        drive.shot = nil
        getCore():TakeFullScreenshot("pzopt-e2e-C-note.png")
        drive.at = getTimestampMs() + 3000
        return
    end
    if drive.step == "note" then
        drive = nil
        print("[pzopt-e2e] helper: quitting after the installed note")
        MainScreen.instance:quitToDesktop()
    elseif drive.step == "shown" then
        print("[pzopt-e2e] helper shown: " .. tostring(panel and panel.command))
        getCore():TakeFullScreenshot("pzopt-e2e-B-helper.png")
        drive.step, drive.at = "copy", now + 4000
    elseif drive.step == "copy" then
        if panel and panel.command then panel:onCopy() end
        print("[pzopt-e2e] copied: " .. tostring(Clipboard.getClipboard()))
        drive.step, drive.at, drive.until_ = "wait", now + 1000, now + 180000
    elseif drive.step == "wait" then
        if e2eFile("pzopt-e2e-quit.txt") or now > drive.until_ then
            drive = nil
            print("[pzopt-e2e] helper: quitting at " .. now)
            MainScreen.instance:quitToDesktop()
        else
            drive.at = now + 1000
        end
    end
end
Events.OnFETick.Add(function() pcall(driveTick) end)

-- one front-end tick after the main menu is up, so the stock main screen is built underneath
local function showOnce()
    Events.OnFETick.Remove(showOnce)
    if MainScreen.instance and MainScreen.instance.inGame then return end
    local e2e = e2eFile("pzopt-e2e-helper.txt")
    if installed() then
        print("[pzopt] install helper: the overrides are installed")
        if not noteShown then
            noteShown = true
            showInstalledNote()
            if e2e then
                print("[pzopt-e2e] installed note shown")
                drive = { step = "note", at = getTimestampMs() + 1500, shot = true }
            end
        end
        return
    end
    if PZOptInstallHelper.instance then return end
    local dir = itemDir()
    print("[pzopt] install helper: not installed; item folder " .. tostring(dir))
    local panel = PZOptInstallHelper:new(dir and installCommand(dir) or nil)
    panel:initialise()
    panel:addToUIManager()
    panel:setAlwaysOnTop(true)
    PZOptInstallHelper.instance = panel
    local joypadData = JoypadState.getMainMenuJoypad()
    if joypadData then
        panel.prevFocus = joypadData.focus
        joypadData.focus = panel
        updateJoypadFocus(joypadData)
    end
    if e2e then drive = { step = "shown", at = getTimestampMs() + 3000 } end
end

Events.OnMainMenuEnter.Add(function() Events.OnFETick.Add(showOnce) end)
