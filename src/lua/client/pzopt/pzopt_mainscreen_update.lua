-- pzopt: the "PZ Optimization update" dialog, opened by the UPDATE card of the PZ Optimization screen (main menu
--  PZ OPTIMIZATION, pzopt_mainscreen_menu.lua; that file also starts the check and shows the updater's news under the item).
--  The Java side (pzopt.Updater, reached through the overridden PerformanceSettings) asks the GitHub releases once per
--  boot whether a newer build for this game revision exists. The dialog: what is installed, what is available, the
--  release notes, and "Update now": the download and the file swap run on a daemon thread while the dialog shows the
--  progress; once done it offers Restart game (pzopt.Restart relaunches the game once it has quit), because the classes
--  the JVM already loaded stay the old ones until a restart. Usually the changed files were fetched in the background as
--  soon as the update was offered (updatePrefetch), so Update now only writes them. With nothing to install (checking,
--  up to date, no check at this launch, the check failed) it says so and has only Close.
--  A copy without pzopt-installed.txt (a hand-unpacked zip) cannot swap its own files: the dialog
--  then only opens the release page.
--  The offer can be the Steam Workshop copy already on disk (source "workshop", issue #16): the dialog says so,
--  Update now copies it without a download, and the page button opens the Workshop change notes. The GitHub
--  answer may replace that offer with a newer release while the dialog is open (same state, another tag).
--  Controller: the dialog takes the joypad focus like the stock modals (A = first button, B = second one or close,
--  D-pad scrolls the notes) and hands it back to the screen that opened it.
-- Installed by scripts/pzopt.sh into <game dir>/media/lua/client/pzopt/ (loose game-dir Lua is
-- loaded like any other, no mod to enable).

local function perf()
    return getPerformance()
end

local function state()
    local ok, s = pcall(function() return perf():getPzoptUpdateState() end)
    if not ok then return "idle" end
    return s
end

-- "workshop" (the Steam Workshop copy on disk), "github" or ""
local function source()
    local ok, s = pcall(function() return perf():getPzoptUpdateSource() end)
    if not ok then return "github" end
    return s
end

-- --- the dialog ----------------------------------------------------------------------------------

PzoptUpdateDialog = ISPanelJoypad:derive("PzoptUpdateDialog")
PzoptUpdateDialog.instance = nil

local FONT_HGT_SMALL = getTextManager():getFontHeight(UIFont.Small)
local FONT_HGT_MEDIUM = getTextManager():getFontHeight(UIFont.Medium)
local PAD = 12
local BTN_HGT = math.max(25, FONT_HGT_SMALL + 3 * 2)
local BAR_HGT = 14

-- Release notes come as GitHub markdown; the rich text panel only knows its own tags, so the angle
-- brackets go and every line break becomes a <LINE>.
local function richNotes(notes)
    if not notes or notes == "" then return "" end
    notes = notes:gsub("\r", ""):gsub("[<>]", ""):gsub("%s+$", "")
    if #notes > 1200 then notes = notes:sub(1, 1200) .. "..." end
    return (notes:gsub("\n", " <LINE> "))
end

function PzoptUpdateDialog:new(x, y, width, height)
    local o = ISPanelJoypad:new(x, y, width, height)
    setmetatable(o, self)
    self.__index = self
    o.backgroundColor = { r = 0, g = 0, b = 0, a = 0.85 }
    o.borderColor = { r = 1, g = 1, b = 1, a = 0.5 }
    o.moveWithMouse = true
    o.shownState = nil
    o.shownTag = nil
    return o
end

function PzoptUpdateDialog:createChildren()
    ISPanelJoypad.createChildren(self)
    local textTop = PAD + FONT_HGT_MEDIUM + PAD
    local textHgt = self.height - textTop - PAD - BAR_HGT - PAD - BTN_HGT - PAD
    self.text = ISRichTextPanel:new(PAD, textTop, self.width - PAD * 2, textHgt)
    self.text:initialise()
    self.text.background = false
    self.text.clip = true
    self.text.autosetheight = false
    self.text.marginLeft = 0
    self.text.marginTop = 0
    self.text.marginRight = 0
    self.text:addScrollBars()
    self:addChild(self.text)

    local btnY = self.height - PAD - BTN_HGT
    self.primary = ISButton:new(0, btnY, 150, BTN_HGT, "", self, PzoptUpdateDialog.onPrimary)
    self.primary:initialise()
    self.primary:instantiate()
    self.primary:enableAcceptColor()
    self:addChild(self.primary)

    self.secondary = ISButton:new(0, btnY, 110, BTN_HGT, "", self, PzoptUpdateDialog.onSecondary)
    self.secondary:initialise()
    self.secondary:instantiate()
    self:addChild(self.secondary)

    self:refresh(true)
end

-- Button labels, text and layout for the current updater state; only rebuilt when the state changes.
function PzoptUpdateDialog:refresh(force)
    local s = state()
    local p = perf()
    local tag = p:getPzoptUpdateTag()
    if s == self.shownState and tag == self.shownTag and not force then return end
    self.shownState = s
    self.shownTag = tag
    local installed = p:getPzoptUpdateInstalledCommit()
    local published = p:getPzoptUpdatePublished()
    local local_ = source() == "workshop"
    local head = "Installed build: " .. installed .. " <LINE> Available: " .. tag
    if local_ then
        head = head .. " (Steam Workshop copy on this computer" .. (published ~= "" and (", built " .. published) or "") .. ")"
    elseif published ~= "" then
        head = head .. " (published " .. published .. ")"
    end
    local pageTitle = local_ and "Open Workshop page" or "Open release page"
    local body
    if s == "available" then
        if p:canPzoptUpdateInstall() then
            body = head .. " <LINE> <LINE> "
                .. (local_ and "Update now copies the files Steam already downloaded with the Workshop item (nothing is downloaded), "
                    or "Update now downloads the release zip, ")
                .. "replaces the installed files that changed (the ones listed in "
                .. "pzopt-installed.txt; the game's own files are never touched) and offers to restart the game: the new classes load "
                .. "on the next start. Saves and options stay as they are."
            self.primary:setTitle("Update now")
            self.secondary:setTitle("Later")
        else
            body = head .. " <LINE> <LINE> "
                .. "This copy was not installed by install.sh / install.ps1 (no pzopt-installed.txt in the game folder), "
                .. "so it cannot replace its own files. "
                .. (local_ and "Run install.ps1 / install.bash from the Workshop item's folder once; later updates then install from here."
                    or "Get the new zip from the release page and unpack it by hand, or install it once with the installer.")
            self.primary:setTitle(pageTitle)
            self.secondary:setTitle("Close")
        end
        local notes = richNotes(p:getPzoptUpdateNotes())
        if notes ~= "" then body = body .. " <LINE> <LINE> <RGB:0.8,0.8,0.8> " .. notes end
    elseif s == "downloading" then
        body = head .. " <LINE> <LINE> Downloading the files that changed..."
        self.primary:setTitle("Hide")
        self.secondary:setTitle("")
    elseif s == "installing" then
        body = head .. " <LINE> <LINE> " .. (local_ and "Copying the Steam Workshop files over the installed ones..." or "Replacing the installed files...")
        self.primary:setTitle("Hide")
        self.secondary:setTitle("")
    elseif s == "installed" then
        body = head .. " <LINE> <LINE> <RGB:0.6,1,0.6> " .. p:getPzoptUpdateMessage() .. " <RGB:1,1,1> <LINE> <LINE> "
            .. "The game keeps running the previous build until it restarts. Restart game closes it and starts it again with the update."
        self.primary:setTitle("Restart game")
        self.secondary:setTitle("Later")
    elseif s == "error" then
        body = head .. " <LINE> <LINE> <RGB:1,0.6,0.6> " .. (p:getPzoptUpdateMessage():gsub("[<>]", "")) .. " <RGB:1,1,1> <LINE> <LINE> "
            .. "Nothing was changed if the " .. (local_ and "copy" or "download") .. " failed; if the file swap failed, run the installer again "
            .. "(install.sh / install.ps1, --uninstall first). "
            .. (local_ and "The Workshop item's folder has the installers." or "The release page has the zip.")
        self.primary:setTitle(pageTitle)
        self.secondary:setTitle("Close")
    else
        local why
        local installedLine = "Installed build: " .. installed .. " <LINE> <LINE> "
        if s == "checking" then
            why = "Checking whether a newer build exists for this game revision."
        elseif s == "up-to-date" then
            why = "This is the newest PZ Optimization build for this game revision. Nothing to install."
        elseif s == "error" then
            why = "<RGB:1,0.6,0.6> The update check failed: " .. (p:getPzoptUpdateMessage():gsub("[<>]", "")) .. " <RGB:1,1,1> <LINE> <LINE> "
                .. "The game asks again at its next start."
        else
            local off = false
            pcall(function() off = p:getPzoptOption("updateCheck") == "false" end)
            why = off and "No update check at this launch: \"Offer new releases in the main menu\" is off in the settings (PZ Optimization tab)."
                or "No update check ran at this launch."
        end
        body = installedLine .. why
        self.primary:setTitle("Close")
        self.secondary:setTitle("")
    end
    self.text.text = body
    self.text:paginate()

    -- buttons centred, the second one only when it has a title
    local w1 = math.max(150, getTextManager():MeasureStringX(UIFont.Small, self.primary:getTitle()) + 24)
    self.primary:setWidth(w1)
    if self.secondary:getTitle() ~= "" then
        local w2 = math.max(110, getTextManager():MeasureStringX(UIFont.Small, self.secondary:getTitle()) + 24)
        self.secondary:setWidth(w2)
        self.secondary:setVisible(true)
        local total = w1 + PAD + w2
        self.primary:setX((self.width - total) / 2)
        self.secondary:setX(self.primary:getX() + w1 + PAD)
    else
        self.secondary:setVisible(false)
        self.primary:setX((self.width - w1) / 2)
    end
    self:syncJoypadButtons()
end

-- Controller: A is the first button and B the second one, like the stock yes / no modals; with a single
-- button ("Hide" / "Close") B closes too. The glyphs follow the buttons whenever the state changes.
function PzoptUpdateDialog:syncJoypadButtons()
    if not self.joyfocus then return end
    self:setISButtonForA(self.primary)
    if self.secondary:isVisible() then
        self:setISButtonForB(self.secondary)
    else
        self.ISButtonB = nil
        self.secondary:clearJoypadButton()
    end
end

function PzoptUpdateDialog:onGainJoypadFocus(joypadData)
    ISPanelJoypad.onGainJoypadFocus(self, joypadData)
    self.joypadButtons = {}
    self:syncJoypadButtons()
end

function PzoptUpdateDialog:onLoseJoypadFocus(joypadData)
    ISPanelJoypad.onLoseJoypadFocus(self, joypadData)
    self.ISButtonA = nil
    self.ISButtonB = nil
    self.primary:clearJoypadButton()
    self.secondary:clearJoypadButton()
end

function PzoptUpdateDialog:onJoypadDown(button, joypadData)
    if button == Joypad.BButton and not self.ISButtonB then
        self:close()
        return
    end
    ISPanelJoypad.onJoypadDown(self, button, joypadData)
end

-- the D-pad scrolls the release notes (three mouse-wheel notches)
function PzoptUpdateDialog:onJoypadDirUp(joypadData)
    self.text:setYScroll(self.text:getYScroll() + 18 * 3)
end

function PzoptUpdateDialog:onJoypadDirDown(joypadData)
    self.text:setYScroll(self.text:getYScroll() - 18 * 3)
end

function PzoptUpdateDialog:prerender()
    ISPanelJoypad.prerender(self)
    self:refresh(false)
    self:drawText("PZ Optimization update", PAD, PAD, 1, 1, 1, 1, UIFont.Medium)
    -- progress bar above the buttons: the download share, full while the files swap, green when done
    local s = self.shownState
    -- the bar only where there is something to show (an offer, its progress, its outcome)
    if s ~= "available" and s ~= "downloading" and s ~= "installing" and s ~= "installed" and s ~= "error" then return end
    local barY = self.height - PAD - BTN_HGT - PAD - BAR_HGT
    local barW = self.width - PAD * 2
    self:drawRectBorder(PAD, barY, barW, BAR_HGT, 0.6, 1, 1, 1)
    local frac, r, g, b = 0, 0.35, 0.55, 0.9
    if s == "downloading" then
        frac = perf():getPzoptUpdateProgress() / 100
    elseif s == "installing" then
        frac, r, g, b = 1, 0.9, 0.75, 0.3
    elseif s == "installed" then
        frac, r, g, b = 1, 0.3, 0.8, 0.3
    elseif s == "error" then
        frac, r, g, b = 1, 0.8, 0.3, 0.3
    end
    if frac > 0 then
        self:drawRect(PAD + 1, barY + 1, (barW - 2) * frac, BAR_HGT - 2, 0.9, r, g, b)
    end
    local label = nil
    if s == "downloading" then
        label = "downloading " .. perf():getPzoptUpdateProgress() .. " %"
    elseif s == "installing" then
        label = "installing"
    end
    if label then
        local lw = getTextManager():MeasureStringX(UIFont.Small, label)
        self:drawText(label, self.width / 2 - lw / 2, barY + (BAR_HGT - FONT_HGT_SMALL) / 2 - 1, 1, 1, 1, 1, UIFont.Small)
    end
end

function PzoptUpdateDialog:onPrimary()
    local s = self.shownState
    if s == "available" then
        if perf():canPzoptUpdateInstall() then
            if not perf():pzoptUpdateInstall() then
                print("[pzopt] update: install refused (state " .. state() .. ")")
            end
            self:refresh(true)
        else
            openUrl(perf():getPzoptUpdatePageUrl())
        end
    elseif s == "installed" then
        local ok, started = pcall(function() return perf():pzoptRestartGame() end)
        if not ok or not started then
            print("[pzopt] update: could not start the restart helper, quitting only")
        end
        self:close()
        MainScreen.instance:quitToDesktop()
    elseif s == "error" then
        openUrl(perf():getPzoptUpdatePageUrl())
    else
        self:close()
    end
end

function PzoptUpdateDialog:onSecondary()
    self:close()
end

function PzoptUpdateDialog:onKeyRelease(key)
    if key == Keyboard.KEY_ESCAPE then
        self:close()
        return true
    end
end

function PzoptUpdateDialog:close()
    self:setVisible(false)
    self:removeFromUIManager()
    PzoptUpdateDialog.instance = nil
    local ms = MainScreen.instance
    if ms and ms.bottomPanel then
        ms.bottomPanel:setVisible(true)
    end
    -- the controller's focus goes back where it came from (the PZ Optimization screen, its cursor still on UPDATE)
    local joypadData = self.joyfocus
    if joypadData and joypadData.focus == self then
        joypadData.focus = self.prevFocus
        updateJoypadFocus(joypadData)
    end
end

function PzoptUpdateDialog.show()
    if PzoptUpdateDialog.instance then
        PzoptUpdateDialog.instance:close()
    end
    local width = math.min(560, getCore():getScreenWidth() - 40)
    local height = math.min(480, getCore():getScreenHeight() - 40)
    local x = (getCore():getScreenWidth() - width) / 2
    local y = (getCore():getScreenHeight() - height) / 2
    local dlg = PzoptUpdateDialog:new(x, y, width, height)
    dlg:initialise()
    dlg:addToUIManager()
    dlg:setAlwaysOnTop(true)
    dlg:bringToTop()
    PzoptUpdateDialog.instance = dlg
    -- like the stock quit dialog: the menu items go while a dialog is up, and a controller's focus moves
    -- to the dialog (the menu would otherwise keep A for itself)
    MainScreen.instance.bottomPanel:setVisible(false)
    local joypadData = JoypadState.getMainMenuJoypad()
    if joypadData then
        dlg.prevFocus = joypadData.focus
        joypadData.focus = dlg
        updateJoypadFocus(joypadData)
    end
end

-- devUpdateDrive (dev rig, Config key): the real buttons pressed in order once the menu is up: open the dialog,
-- Update now, Restart game. In the process the restart started, log the time since the press and quit.
local driveStep = nil
local function driveTick(self)
    local ok, mode = pcall(function() return perf():getPzoptUpdateDrive() end)
    if not ok or not mode or mode == "" or driveStep == "done" then return end
    if not self.exitOption or not self.exitOption:isVisible() then return end
    if mode:sub(1, 9) == "restarted" then
        driveStep = "done"
        print("[pzopt] update drive: restarted process reached the main menu " .. mode:sub(11) .. " ms after Restart game was pressed")
        self:quitToDesktop()
        return
    end
    local s = state()
    local dlg = PzoptUpdateDialog.instance
    if s == "available" and not dlg then
        print("[pzopt] update drive: offer " .. perf():getPzoptUpdateTag() .. " from " .. source() .. " at " .. getTimestampMs())
        PzoptUpdateDialog.show()
    elseif dlg and dlg.shownState == "available" and driveStep == nil then
        driveStep = "installing"
        print("[pzopt] update drive: Update now pressed at " .. getTimestampMs())
        dlg:onPrimary()
    elseif dlg and dlg.shownState == "installed" and driveStep == "installing" then
        driveStep = "done"
        print("[pzopt] update drive: installed (" .. perf():getPzoptUpdateMessage() .. "), Restart game pressed at " .. getTimestampMs())
        dlg:onPrimary()
    elseif s == "error" and driveStep ~= "done" then
        driveStep = "done"
        print("[pzopt] update drive: error " .. perf():getPzoptUpdateMessage())
        self:quitToDesktop()
    end
end

local function install()
    if not MainScreen or MainScreen.pzoptUpdateItem then return end
    local ok, has = pcall(function() return getPerformance():hasPzoptOptions() end)
    if not ok or not has then return end
    MainScreen.pzoptUpdateItem = true
    local stockPrerender = MainScreen.prerender
    function MainScreen:prerender(...)
        stockPrerender(self, ...)
        if not self.inGame then pcall(driveTick, self) end
    end
end

install()
Events.OnGameBoot.Add(install)

-- The UPDATE page of the main menu's PZ Optimization screen (pzopt_mainscreen_menu.lua): the updater's state as list rows,
-- the text and the actions of that state (the same as the dialog's; nothing installs before Update now is pressed).
PzoptUpdateApi = {}

function PzoptUpdateApi.view()
    local s = state()
    local p = perf()
    local v = { state = s, installed = p:getPzoptUpdateInstalledCommit(), tag = p:getPzoptUpdateTag(),
        published = p:getPzoptUpdatePublished(), workshop = source() == "workshop", actions = {} }
    local okC, can = pcall(function() return p:canPzoptUpdateInstall() end)
    v.canInstall = okC and can
    local pageTitle = v.workshop and "OPEN WORKSHOP PAGE" or "OPEN RELEASE PAGE"
    local function page() openUrl(p:getPzoptUpdatePageUrl()) end
    if s == "available" then
        if v.canInstall then
            v.text = (v.workshop and "Update now copies the files Steam already downloaded with the Workshop item (nothing is downloaded), "
                or "Update now downloads the release zip, ") .. "replaces the installed files that changed (the ones listed in "
                .. "pzopt-installed.txt; the game's own files are never touched) and offers to restart the game: the new classes load on "
                .. "the next start. Saves and options stay as they are."
            table.insert(v.actions, { label = "UPDATE NOW", danger = true, key = "y",
                confirm = "Install the update now? The changed files are replaced; the game asks to restart after.", act = function()
                if not p:pzoptUpdateInstall() then print("[pzopt] update: install refused (state " .. state() .. ")") end
            end })
        else
            v.text = "This copy was not installed by install.sh / install.ps1 (no pzopt-installed.txt in the game folder), so it cannot "
                .. "replace its own files. " .. (v.workshop and "Run install.ps1 / install.bash from the Workshop item's folder once; later updates then install from here."
                    or "Get the new zip from the release page and unpack it by hand, or install it once with the installer.")
        end
        table.insert(v.actions, { label = pageTitle, act = page, key = "x", confirm = "Open the page in your web browser?" })
    elseif s == "downloading" then
        v.text = "Downloading the files that changed... " .. p:getPzoptUpdateProgress() .. " %"
        v.progress = p:getPzoptUpdateProgress() / 100
    elseif s == "installing" then
        v.text = v.workshop and "Copying the Steam Workshop files over the installed ones..." or "Replacing the installed files..."
        v.progress = 1
    elseif s == "installed" then
        v.text = "<RGB:0.6,1,0.6> " .. (p:getPzoptUpdateMessage():gsub("[<>]", "")) .. " <RGB:1,1,1> <LINE> <LINE> The game keeps running the "
            .. "previous build until it restarts. Restart game closes it and starts it again with the update."
        table.insert(v.actions, { label = "RESTART GAME", danger = true, key = "y", confirm = "Restart the game now to load the update?", act = function()
            local ok, started = pcall(function() return p:pzoptRestartGame() end)
            if not ok or not started then print("[pzopt] update: could not start the restart helper, quitting only") end
            MainScreen.instance:quitToDesktop()
        end })
    elseif s == "error" then
        v.text = "<RGB:1,0.6,0.6> " .. (p:getPzoptUpdateMessage():gsub("[<>]", "")) .. " <RGB:1,1,1> <LINE> <LINE> Nothing was changed if the "
            .. (v.workshop and "copy" or "download") .. " failed; if the file swap failed, run the installer again (install.sh / install.ps1, "
            .. "--uninstall first)."
        if v.tag ~= "" then table.insert(v.actions, { label = pageTitle, act = page, key = "x", confirm = "Open the page in your web browser?" }) end
    elseif s == "checking" then
        v.text = "Checking whether a newer build exists for this game revision."
    elseif s == "up-to-date" then
        v.text = "This is the newest PZ Optimization build for this game revision. Nothing to install."
    else
        local off = false
        pcall(function() off = p:getPzoptOption("updateCheck") == "false" end)
        v.text = off and "No update check at this launch: \"Offer new releases in the main menu\" is off in the settings (PZ Optimization tab)."
            or "No update check ran at this launch."
    end
    v.notes = richNotes(p:getPzoptUpdateNotes())
    return v
end
