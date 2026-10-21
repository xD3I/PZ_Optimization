-- pzopt: the main menu's "PZ OPTIMIZATION" item and the PZ Optimization screen (2026-10-18), the only home of the pzopt
-- settings (the game's Options screen had a PZ Optimization tab until 2026-10-18; its data and actions are
-- pzopt_optimizations_options.lua's PzoptSettings).
--  One item in the main-menu column and in the pause menu (stock style: ISLabel, UIFont.Large, the stock hover fade and
--  sounds), right below Options, with a small-font line under it: "Version <commit>", or the updater's news ("Update
--  available: <commit>", "Updating... 42 %", "Restart to finish the update", "Update failed"). It opens PzoptMainMenu, a
--  screen of its own over the whole window (like the stock Load / Options screens): the title, the search box, a sidebar on
--  the left, the selected entry's page right of it, BACK.
--    home  the six entries below, the presets (Recommended, Stock game, the low-end sets), Fix a problem, the view
--          (Simple / Advanced / Everything), the before / after clips, Export / Import settings, Uninstall
--    OPTIMIZATIONS / ENHANCEMENTS / PROFILER  the three groups (pzopt_optimizations_layout.lua): the group's master switch,
--        its categories (each with its own switch at the top of its page), their subcategories, their settings, each
--        editable in place (a tick box, a chooser, the curve's numbers, the colour picker; saved through UserOptions: live
--        keys at once, the others at the next launch, then the restart prompt; DEFAULT), with the impact chart and the
--        before / after clips in the detail; Reset ... to defaults per group and category
--    BENCHMARK  Setup (scenes and options as rows, the choosers, the name, Start), Past results (the runs; Open, Compare,
--               baseline, folder), Compare (the rows of two runs with sections; Only what differs), Report (the scenes;
--               charts, notes, findings; full report, share code, a friend's code, Run again); data: PzoptBench
--    UPDATE                   the updater's state, builds and notes; Update now / Restart game / the release page
--    MOD COMPATIBILITY CHECK  the overview, the Max performance / Max compatibility choice, one row per Java file
--  Search: every setting by name, description, key, resource or Java class; the results page opens a setting on its page.
--  Every page is a list (the rows, headings never selected) and the selected row's detail beside it (under it when the
--  window is narrow): its text, a chart (a report row, a setting's impact), its controls and its buttons; every text
--  wrapped, nothing cut; on a settings page the clips and the text keep their place from row to row.
--  Mouse: hover + click (a sidebar entry selects its page, a row acts), BACK. Keyboard: Ctrl+F the search, Escape leaves
--  the search box, closes the open pzopt dialog, else the screen. Controller, in explicit order (never the stock geometric
--  moves): up / down in the sidebar (BACK under the last entry), A or right into the page, up / down / left / right over
--  its rows and columns, A acts, B or left (from the first column) back to the sidebar, B in the sidebar closes the
--  screen with the cursor on the PZ OPTIMIZATION item; L3 the search (on-screen keyboard), R3 the help.
--  This file also packs the main-menu column into short windows (fitMenuColumn) and starts the once-per-boot update check.
-- Installed by scripts/pzopt.sh into <game dir>/media/lua/client/pzopt/ (loose game-dir Lua, no mod to enable).

local ITEM_TEXT = "PZ OPTIMIZATION"   -- the stock items are capitals (UI_mainscreen_* translations)
local FONT_SMALL = getTextManager():getFontHeight(UIFont.Small)
local FONT_MEDIUM = getTextManager():getFontHeight(UIFont.Medium)
local FONT_LARGE = getTextManager():getFontHeight(UIFont.Large)

local function perf()
    return getPerformance()
end

local function textW(font, s)
    return getTextManager():MeasureStringX(font, tostring(s or ""))
end

local function fontH(font)
    return getTextManager():getFontHeight(font)
end

-- s in lines no wider than maxW; a word wider than maxW is broken between characters
local function wrap(s, font, maxW)
    local lines, cur = {}, ""
    for word in string.gmatch(tostring(s or ""), "%S+") do
        local try = cur == "" and word or (cur .. " " .. word)
        if textW(font, try) <= maxW then
            cur = try
        else
            if cur ~= "" then table.insert(lines, cur) end
            while textW(font, word) > maxW and #word > 1 do
                local cut = 1
                for i = 1, #word - 1 do
                    if textW(font, string.sub(word, 1, i)) > maxW then break end
                    cut = i
                end
                table.insert(lines, string.sub(word, 1, cut))
                word = string.sub(word, cut + 1)
            end
            cur = word
        end
    end
    if cur ~= "" then table.insert(lines, cur) end
    return lines
end

-- ------------------------------------------------------------------ the updater's state, as text

local function updState()
    local ok, s = pcall(function() return perf():getPzoptUpdateState() end)
    if not ok or not s then return "idle" end
    return s
end

local function updTag()
    local ok, t = pcall(function() return perf():getPzoptUpdateTag() end)
    return (ok and t) or ""
end

local function installedCommit()
    local ok, c = pcall(function() return perf():getPzoptUpdateInstalledCommit() end)
    return (ok and c and c ~= "") and c or "?"
end

local function updProgress()
    local ok, p = pcall(function() return perf():getPzoptUpdateProgress() end)
    return (ok and p) or 0
end

local function fromWorkshop()
    local ok, s = pcall(function() return perf():getPzoptUpdateSource() end)
    return ok and s == "workshop"
end

-- the offered release's commit (tag b<version>-<yyyymmdd>-<hhmm>-<commit>)
local function offeredCommit()
    local tag = updTag()
    return tag:match("([^-]+)$") or tag
end

-- something to do: an update offered, being installed, installed, or failed (the dialog explains)
local function updateOffered(s)
    return s == "available" or s == "downloading" or s == "installing" or s == "installed"
        or (s == "error" and updTag() ~= "")
end

-- the main-menu item: "PZ OPTIMIZATION - UPDATE AVAILABLE" while a newer build is offered (pzopt.Updater's state, the
-- same the UPDATE page reads), else "PZ OPTIMIZATION"
local ITEM_UPDATE_TEXT = ITEM_TEXT .. " - UPDATE AVAILABLE"

local function itemText()
    return updState() == "available" and ITEM_UPDATE_TEXT or ITEM_TEXT
end

-- the main-menu item's small line: the installed build, or the updater's news (bright)
local function itemNote()
    local s = updState()
    if s == "available" then return "Version " .. installedCommit() .. " -> " .. offeredCommit(), true end
    if s == "downloading" then return "Updating... " .. updProgress() .. " %", true end
    if s == "installing" then return "Updating... installing", true end
    if s == "installed" then return "Restart to finish the update", true end
    if s == "error" and updTag() ~= "" then return "Update failed", true end
    return "Version " .. installedCommit(), false
end

local UPDATE_TITLES = { "UPDATE", "UPDATE AVAILABLE", "UPDATING... 100 %", "UPDATING... INSTALLING", "RESTART TO FINISH THE UPDATE", "UPDATE FAILED" }

local function updateTitle()
    local s = updState()
    if s == "available" then return "UPDATE AVAILABLE" end
    if s == "downloading" then return "UPDATING... " .. updProgress() .. " %" end
    if s == "installing" then return "UPDATING... INSTALLING" end
    if s == "installed" then return "RESTART TO FINISH THE UPDATE" end
    if s == "error" and updTag() ~= "" then return "UPDATE FAILED" end
    return "UPDATE"
end

local function updateStateLine()
    local s = updState()
    local v = "Version " .. installedCommit()
    if s == "available" then
        return v .. " -> " .. offeredCommit() .. (fromWorkshop() and " (the Steam Workshop copy on this computer)" or "")
    elseif s == "downloading" or s == "installing" then
        return v .. " -> " .. offeredCommit()
    elseif s == "installed" then
        return "Version " .. offeredCommit() .. " installed: it loads when the game restarts"
    elseif s == "error" then
        return v .. (updTag() ~= "" and (" -> " .. offeredCommit() .. ": the update failed") or ": the update check failed")
    elseif s == "up-to-date" then
        return v .. ": up to date"
    elseif s == "checking" then
        return v .. ": checking for a newer build..."
    end
    return v .. ": no update check at this launch"
end

-- the longest the state line gets, for a layout that does not move when the state changes
local function updateStateVariants()
    local v = "Version " .. installedCommit()
    local o = "0123abc"
    return { v .. " -> " .. o .. " (the Steam Workshop copy on this computer)", "Version " .. o .. " installed: it loads when the game restarts",
        v .. " -> " .. o .. ": the update failed", v .. ": checking for a newer build...", v .. ": no update check at this launch" }
end

local function updateDesc()
    if updateOffered(updState()) then
        return "Opens the release notes and Update now. Nothing is installed until you press it; saves and settings stay as they are."
    end
    return "Once per boot the game asks whether a newer build exists for this game revision (GitHub releases, the Steam "
        .. "Workshop copy). This card and the main-menu item light up when one does."
end

-- the controller that drives the menu: the main menu's, or in a game the player's whose focus is the pause menu / the screen
local function mainJoypad()
    local ok, jd = pcall(function() return JoypadState.getMainMenuJoypad() end)
    if ok and jd then return jd end
    local ms = MainScreen.instance
    if ms and ms.inGame and JoypadState.players then
        for i = 1, 4 do
            local p = JoypadState.players[i]
            if p and p.focus and (p.focus == ms or p.focus == ms.pzoptMenuScreen) then return p end
        end
    end
    return nil
end

-- ------------------------------------------------------------------ the look of the options tab
-- (pzopt_optimizations_options.lua: PzoptSidebar, the header line, its colours), so the screen and the tab read as one

local C_TEXT = { r = 0.94, g = 0.94, b = 0.96 }
local C_GREY = { r = 0.66, g = 0.66, b = 0.70 }
local C_DIM = { r = 0.40, g = 0.40, b = 0.45 }
local C_STOCK = { r = 0.79, g = 0.35, b = 0.17 }
local C_OPT = { r = 0.27, g = 0.87, b = 0.49 }
local SIDE_BG = { r = 0.04, g = 0.04, b = 0.06 }
local SIDE_BORDER = { r = 0.31, g = 0.31, b = 0.35 }

local function rgb(t) return { r = t[1], g = t[2], b = t[3] } end

local function clipText(font, s, w)
    s = tostring(s or "")
    if textW(font, s) <= w then return s end
    while #s > 1 and textW(font, s .. "...") > w do s = string.sub(s, 1, #s - 1) end
    return s .. "..."
end

-- the lines of a text at a width; a height is the most any variant of its texts needs
local function linesOf(texts, font, w)
    local most = 0
    for _, t in ipairs(texts) do most = math.max(most, #wrap(t, font, w)) end
    return most
end

-- ------------------------------------------------------------------ the settings: groups -> categories -> subcategories -> keys

-- the options tab's groups (pzopt_optimizations_layout.lua) and their master switches
local GROUPS = {
    perf = { name = "Optimizations", master = "enabled", live = false },
    vis = { name = "Enhancements", master = "enhancementsEnabled", live = true },
    tools = { name = "Profiler", master = "profilerEnabled", live = true },
}

local function opt(fn, key)
    local ok, v = pcall(function() return perf()[fn](perf(), key) end)
    if ok and v == false then return false end
    return (ok and v) or ""
end

-- Every subcategory's keys, resolved like the tab's buildTree does (keys, or every key of the sections whose title starts
-- with a prefix; a key no subcategory claims goes to the "More" of the category its section maps to in `home`)
local tree = nil
local function settingsTree()
    if tree then return tree end
    local L = PzoptSettingsLayout or { groups = {} }
    local pages = PzoptOptionSections and PzoptOptionSections() or {}
    local byKey, claimed, order = {}, {}, {}
    for page, list in ipairs(pages) do
        for _, sec in ipairs(list) do
            for _, e in ipairs(sec.entries or {}) do
                if e.key and not byKey[e.key] then
                    byKey[e.key] = { e = e, page = page, section = sec.title or "", sec = sec }
                    table.insert(order, e.key)
                end
            end
        end
    end
    local t = { groups = {}, byId = {}, entry = byKey }
    for _, g in ipairs(L.groups) do
        local G = { id = g.id, title = g.title, page = g.page, c = rgb(g.colour or { 1, 1, 1 }), note = g.note or "", cats = {} }
        G.name = GROUPS[g.id] and GROUPS[g.id].name or g.title
        table.insert(t.groups, G)
        t.byId[g.id] = G
        for _, c in ipairs(g.cats or {}) do
            -- the category's own switch (the layout's `master`, when this build knows the key; Tools: its group's)
            local master = c.master and opt("isPzoptOptionKnown", c.master) == true and c.master or nil
            local cat = { id = c.id, title = c.title, blurb = c.blurb or "", group = G, subs = {}, master = master }
            table.insert(G.cats, cat)
            t.byId[g.id .. "/" .. c.id] = cat
            for _, s in ipairs(c.subs or {}) do
                local sub = { title = s.title, cat = cat, keys = {}, dlss = s.dlssButton }
                table.insert(cat.subs, sub)
                if s.sections then
                    for _, key in ipairs(order) do
                        local b = byKey[key]
                        if not claimed[key] and b.page == g.page then
                            for _, prefix in ipairs(s.sections) do
                                if string.sub(b.section, 1, #prefix) == prefix then
                                    claimed[key] = true
                                    table.insert(sub.keys, key)
                                    break
                                end
                            end
                        end
                    end
                else
                    for _, key in ipairs(s.keys or {}) do
                        if byKey[key] and not claimed[key] then
                            claimed[key] = true
                            table.insert(sub.keys, key)
                        end
                    end
                end
            end
        end
    end
    for _, key in ipairs(order) do
        if not claimed[key] then
            local b = byKey[key]
            local cat
            for _, h in ipairs(L.home or {}) do
                if string.sub(b.section, 1, #h[1]) == h[1] then
                    for _, G in ipairs(t.groups) do
                        for _, c in ipairs(G.cats) do if c.id == h[2] then cat = c end end
                    end
                    break
                end
            end
            if not cat then
                for _, G in ipairs(t.groups) do
                    if G.page == b.page and #G.cats > 0 then cat = G.cats[#G.cats] end
                end
            end
            if cat then
                if not cat.more then
                    cat.more = { title = "More", cat = cat, keys = {} }
                    table.insert(cat.subs, cat.more)
                end
                table.insert(cat.more.keys, key)
                claimed[key] = true
            end
        end
    end
    for _, G in ipairs(t.groups) do
        for _, c in ipairs(G.cats) do
            c.count = 0
            for i, s in ipairs(c.subs) do
                c.count = c.count + #s.keys
                t.byId[G.id .. "/" .. c.id .. "/" .. i] = s
            end
        end
    end
    tree = t
    return t
end

-- Simple / Advanced / Everything (the tab's views): which settings the pages list, by the layout's tiers (PzoptSettings.tier:
-- 1 the features and main switches, 2 how something is done, 3 the tuning numbers); the master switches always show,
-- search and Fix a problem show every match. Kept for the session, Simple first, like the tab.
local VIEW = { level = 1 }
local VIEW_NAMES = { "Simple", "Advanced", "Everything" }
local VIEW_TIPS = {
    "The settings worth knowing about: the features and the main switches.",
    "Also the settings that change how something is done; the defaults are what was measured best.",
    "Also the tuning numbers (budgets, thread counts, intervals). Change them to experiment.",
}

local function tierOf(key)
    return PzoptSettings and PzoptSettings.tier(key) or 2
end

-- the keys of a list the current view shows, and how many it leaves out
local function shownKeys(keys)
    local t, hidden = {}, 0
    for _, k in ipairs(keys) do
        if tierOf(k) <= VIEW.level then table.insert(t, k) else hidden = hidden + 1 end
    end
    return t, hidden
end

local function catCount(cat)
    local n = 0
    for _, s in ipairs(cat.subs) do n = n + #(shownKeys(s.keys)) end
    return n
end

-- a master switch: the value of the next launch (pinned > saved > default), the one in force, who pins it
local function masterState(key)
    local pinned = opt("getPzoptOptionPinnedBy", key)
    local now = opt("getPzoptOption", key)
    local nextV = pinned ~= "" and now or opt("getPzoptOptionSaved", key)
    if nextV == "" then nextV = opt("getPzoptOptionDefault", key) end
    return nextV == "true", now == "true", pinned
end

-- the keys changed on this screen since the game started (the restart prompt names those still waiting for a restart)
local TOUCHED = {}

-- a master switch flips (a group's or a category's): saved at once, a live one in force at once
local function toggleMasterKey(key)
    TOUCHED[key] = true
    local on, _, pinned = masterState(key)
    if pinned ~= "" then return end
    local value = on and "false" or "true"
    if value == opt("getPzoptOptionDefault", key) then value = "" end
    pcall(function() perf():setPzoptOption(key, value) end) -- UserOptions saves it; a live key is in force at once
    PzoptLogInfo("[pzopt] menu: " .. key .. " -> " .. (on and "false" or "true"))
end

local function toggleMaster(gid)
    toggleMasterKey(GROUPS[gid].master)
end

-- every master switch: key -> { live, label } (the groups', the categories' from the layout)
local function masterInfo(key)
    for _, g in pairs(GROUPS) do
        if g.master == key then return { live = g.live, label = g.name .. " enabled" } end
    end
    for _, G in ipairs(settingsTree().groups) do
        for _, c in ipairs(G.cats) do
            if c.master == key then return { live = G.page ~= 1, label = c.title .. " enabled", cat = c } end
        end
    end
    return nil
end

local function valueText(key, e)
    local v = opt("getPzoptOption", key)
    local t = v == "true" and "On" or (v == "false" and "Off" or (v == "" and "-" or v))
    if e and e.note and e.note[v] then t = t .. " (" .. e.note[v] .. ")" end
    return t
end

-- the update entry's short state
local function updateShort()
    local s = updState()
    if s == "available" then return "Update available: " .. offeredCommit(), true end
    if s == "downloading" then return "Updating... " .. updProgress() .. " %", true end
    if s == "installing" then return "Updating... installing", true end
    if s == "installed" then return "Restart to finish the update", true end
    if s == "error" then return updTag() ~= "" and "The update failed" or "The check failed", updTag() ~= "" end
    if s == "up-to-date" then return "Up to date", false end
    if s == "checking" then return "Checking for updates", false end
    return "Not checked at this launch", false
end

-- ------------------------------------------------------------------ settings: the value a row shows and how it changes
-- (as the options tab: the value of the next launch is pinned > saved > the one mod compatibility decided > default; a
-- change is saved through UserOptions, the default as no line; a live key applies at once, the others at the next launch)

local RESTART_NOTE = "Takes effect on the next launch."
local LIVE_NOTE = "Applies at once; no restart needed."

local function settingKind(key, e)
    if e and e.bezier then return "bezier" end
    if e and e.colour then return "colour" end
    if e and e.choices then return "choice" end
    local def = opt("getPzoptOptionDefault", key)
    if def == "true" or def == "false" then return "bool" end
    return "other"
end

local function settingNext(key)
    local pinned = opt("getPzoptOptionPinnedBy", key)
    if pinned ~= "" then return opt("getPzoptOption", key), pinned end
    local saved = opt("getPzoptOptionSaved", key)
    if saved ~= "" then return saved, "" end
    if opt("getPzoptModCompatReason", key) ~= "" then return opt("getPzoptOption", key), "" end
    return opt("getPzoptOptionDefault", key), ""
end

local function showValue(v, e)
    local t = v == "true" and "On" or (v == "false" and "Off" or (v == "" and "-" or v))
    if e and e.note and e.note[v] then t = t .. " (" .. e.note[v] .. ")" end
    return t
end

local function settingStore(key, value)
    TOUCHED[key] = true
    if PzoptSettings then
        PzoptSettings.set(key, value) -- (a choice equal to the default stays a choice while mod compatibility decides the key)
    else
        if value == opt("getPzoptOptionDefault", key) then value = "" end
        pcall(function() perf():setPzoptOption(key, value) end)
    end
    PzoptLogInfo("[pzopt] menu: " .. key .. " -> " .. ((value == "" or value == opt("getPzoptOptionDefault", key)) and "(default)" or value))
end

-- the next value a press gives: a tick box flips, a combo moves to its next / previous choice
local function settingStep(key, e, dir)
    local v, pinned = settingNext(key)
    if pinned ~= "" then return false end
    local kind = settingKind(key, e)
    if kind == "bool" then
        settingStore(key, v == "true" and "false" or "true")
        return true
    elseif kind == "choice" then
        local at = 1
        for i, c in ipairs(e.choices) do if tostring(c) == v then at = i end end
        at = ((at - 1 + (dir or 1)) % #e.choices) + 1
        settingStore(key, tostring(e.choices[at]))
        return true
    end
    return false
end

local function settingLive(key, e, page)
    return (e and e.live) or page == 3
end

-- The changed keys that wait for a restart: the next launch's value differs from the one in force and the key does not
-- apply live (every Performance key, the next-launch Visuals such as hdr, mirrors, car glass; the Optimizations master)
local COMPAT_KEY = "modProfile" -- the MOD COMPATIBILITY CHECK page's choice (pzopt_mainscreen_compat.lua), next launch

-- The Enhancements read at start-up (they pick the window or patch shaders when the game loads them) that a live master
-- switch also gates: the Enhancements switch all of them, a category's switch its own (PzoptSettings.CATS.restart).
local function startupKeys()
    return (PzoptSettings and PzoptSettings.MASTERS[2].restartKeys) or {}
end

local function isStartupGate(key)
    return key == "enhancementsEnabled" or (PzoptSettings and PzoptSettings.CATS.restart[key] ~= nil)
end

-- the value a start-up Enhancement has on the next launch: its own, or off while its group's or its category's switch is off
local function effectiveNext(key)
    local v, pinned = settingNext(key)
    if pinned ~= "" then return v end
    if not (masterState("enhancementsEnabled")) then return "false" end
    local cm = PzoptSettings and PzoptSettings.CATS.masterOfKey(key)
    if cm and opt("isPzoptOptionKnown", cm) == true and not (masterState(cm)) then return "false" end
    return v
end

-- The changed keys that wait for a restart: the next launch's value differs from the one in force and the key does not
-- apply live (every Optimizations key and category switch, the next-launch Enhancements such as hdr, mirrors, car glass,
-- the Optimizations master). A live switch of the Enhancements waits only through the start-up features it moves.
local function pendingRestart()
    local t = settingsTree()
    local out, seen = {}, {}
    local function consider(k)
        if seen[k] then return end
        seen[k] = true
        local live, label = false, k
        local m = masterInfo(k)
        if m then live, label = m.live, m.label end
        local b = t.entry[k]
        if b then
            live = settingLive(k, b.e, b.page)
            label = (b.e and b.e.label) or k
        end
        if k == COMPAT_KEY then label = "Mod compatibility: " .. tostring(PzoptCompatApi.names[(settingNext(k))] or (settingNext(k))) end
        if live then return end
        local nextV = (b and b.page == 2) and effectiveNext(k) or (settingNext(k))
        if nextV ~= opt("getPzoptOption", k) then
            table.insert(out, { key = k, label = label, want = nextV })
        end
    end
    local startup = false
    for k in pairs(TOUCHED) do
        consider(k)
        if isStartupGate(k) then startup = true end
    end
    if startup then
        for _, k in ipairs(startupKeys()) do consider(k) end
    end
    table.sort(out, function(a, b) return a.label < b.label end)
    return out
end

-- ------------------------------------------------------------------ the nodes of the menu
-- home; perf / vis / tools (a settings group), "<group>/<cat>", "<group>/<cat>/<sub index>"; bench and its pages
-- bench/setup, bench/history, bench/compare, bench/report; update; compat; problems ("Fix a problem") and problems/<i>;
-- search (the sidebar's search box's results).

local C_HELP = { r = 0.85, g = 0.55, b = 0.95 }
local SEARCH = { query = "" } -- what the search page shows (the crumbs name it)

local TOP = {
    { id = "perf", label = "OPTIMIZATIONS" },
    { id = "vis", label = "ENHANCEMENTS" },
    { id = "tools", label = "PROFILER" },
    { id = "bench", label = "BENCHMARK", c = { r = 0.85, g = 0.55, b = 0.95 } },
    { id = "update", label = "UPDATE", c = C_TEXT },
    { id = "compat", label = "MOD COMPATIBILITY CHECK", c = { r = 0.55, g = 0.85, b = 0.85 } },
}

local BENCH_PAGES = {
    { id = "bench/setup", title = "Setup", blurb = "Scenes, passes, the stock comparison and the options of a run; Start is on this page." },
    { id = "bench/history", title = "Past results", blurb = "Every run on this PC with its score, the baseline, the results folder." },
    { id = "bench/compare", title = "Compare", blurb = "Two runs side by side (the two newest unless Past results picked them): scores, PZ Optimization settings, game settings, mods, PC." },
    { id = "bench/report", title = "Report", blurb = "A run's report (the newest unless Past results opened another): scores per scene, charts, findings, the share code." },
}

local function split3(id)
    local a, b, c = string.match(id, "^([^/]+)/?([^/]*)/?([^/]*)$")
    return a, b ~= "" and b or nil, c ~= "" and tonumber(c) or nil
end

local function parentOf(id)
    if id == "home" then return nil end
    local a, b, c = split3(id)
    if c then return a .. "/" .. b end
    if b then return a end
    return "home"
end

local function topOf(id)
    if id == "home" then return nil end
    return (split3(id))
end

local function topEntry(id)
    for _, e in ipairs(TOP) do if e.id == id then return e end end
    return nil
end

local function colourOf(id)
    local top = topOf(id)
    if top == "problems" then return C_HELP end
    local t = settingsTree()
    if t.byId[top] and GROUPS[top] then return t.byId[top].c end
    local e = topEntry(top)
    return (e and e.c) or C_TEXT
end

local function titleOf(id)
    if id == "home" then return "PZ Optimization" end
    local t = settingsTree()
    local a, b = split3(id)
    if GROUPS[a] then
        if not b then return GROUPS[a].name end
        local node = t.byId[id]
        return node and node.title or id
    end
    if a == "bench" then
        if not b then return "Benchmark" end
        for _, p in ipairs(BENCH_PAGES) do if p.id == id then return p.title end end
    end
    if id == "update" then return "Update" end
    if id == "compat" then return "Mod compatibility check" end
    if a == "problems" then
        if not b then return "Fix a problem" end
        local pr = ((PzoptSettingsLayout or {}).problems or {})[tonumber(b) or 0]
        return pr and pr.title or id
    end
    if id == "search" then return "Search: " .. SEARCH.query end
    return id
end

local function pathOf(id)
    local out = {}
    local n = id
    while n do
        table.insert(out, 1, n)
        n = parentOf(n)
    end
    return out
end

local function val(v)
    if type(v) == "function" then return v() end
    return v
end

local function esc(s)
    return (tostring(s or ""):gsub("[<>]", ""))
end

-- ------------------------------------------------------------------ the pages
-- A page: { items = list rows, head = fn -> rich text over every row's detail }. A row: { id, label, value, kind =
-- "node" | "master" | "setting" | "tick" | "row" | "section" (a heading, never selected) | "note" (dim, never selected),
-- desc = fn -> rich text, actions = { { label, act, danger, key = "x" | "y", confirm = text } }, controls = { { type = "chooser" | "tick" | "entry", label,
-- get, set, values } }, activate = fn (A / a second click on the row), chart = fn(el, x, y, w, h), desc2 = fn -> rich
-- text under the chart }. A danger action (Start benchmark, Update now, Open full report, Restart game) is never where the
-- cursor lands first; an action with `confirm` (it restarts or installs, or opens a window outside the game) asks first.
-- `key`: the controller's X (the row's second action) or Y (its main one) presses it from the list or the detail.

local PAGES = {}

local function homeDesc(id)
    if id == "perf" or id == "vis" or id == "tools" then
        local G = settingsTree().byId[id]
        return (G and G.note or "") .. " Its master switch, its categories (each with its own switch) and every setting in them, "
            .. "changed here and saved at once."
    elseif id == "bench" then
        return "Measures this PC with built-in scenes that play by themselves: walking, driving at 120 km/h, a horde in Louisville. "
            .. "Frame times, stutters, a stock comparison, past results and a report to share."
    elseif id == "update" then
        return updateDesc()
    elseif id == "compat" then
        return "PZ Optimization ships whole game classes; a Java mod (ZombieBuddy mods, a -javaagent) patches methods of the same "
            .. "classes. The check lists each mod file, what it patches and which settings that switched off."
    end
    return ""
end

-- the result of an action that changed settings (a preset, an import, a reset), in a dialog with OK
local function showMessage(self, text)
    local w = math.min(560, getCore():getScreenWidth() - 40)
    local shown = getTextManager():WrapText(UIFont.Small, text, w - 40)
    local lines = 1
    for _ in string.gmatch(shown, "\n") do lines = lines + 1 end
    local h = math.min(getCore():getScreenHeight() - 40, 90 + lines * fontH(UIFont.Small))
    local modal = ISModalDialog:new((getCore():getScreenWidth() - w) / 2, (getCore():getScreenHeight() - h) / 2, w, h, shown, false, nil, nil)
    modal:initialise()
    modal:addToUIManager()
    modal:setAlwaysOnTop(true)
    modal:bringToTop()
    self.messageModal = modal
    local jd = mainJoypad()
    if jd then
        modal.prevFocus = jd.focus
        jd.focus = modal
        updateJoypadFocus(jd)
    end
    PzoptLogInfo("[pzopt] menu: message: " .. string.gsub(text, "\n", " "))
end

-- the before / after clips of the settings' detail (previewClips, off by default, saved at once)
local function clipsOn()
    return PzoptSettings and PzoptSettings.clipsOn() or false
end

local function clipsAction()
    return { label = function() return clipsOn() and "HIDE CLIPS" or "SHOW CLIPS" end, quiet = true, act = function()
        if PzoptSettings then PzoptSettings.setClipsOn(not clipsOn()) end
        PzoptLogInfo("[pzopt] menu: before / after clips " .. (clipsOn() and "on" or "off"))
    end }
end

local function viewRow()
    return { id = "view", kind = "setting", label = "Settings shown",
        value = function() return VIEW_NAMES[VIEW.level] end,
        desc = function()
            return "Which settings the pages list. " .. VIEW_NAMES[1] .. ": " .. VIEW_TIPS[1] .. " " .. VIEW_NAMES[2] .. ": " .. VIEW_TIPS[2] .. " "
                .. VIEW_NAMES[3] .. ": " .. VIEW_TIPS[3] .. " <LINE> <LINE> Search and Fix a problem always show every match; the master "
                .. "switches show in every view. Kept until the game closes."
        end,
        activate = function() VIEW.level = VIEW.level % 3 + 1 end,
        controls = { { type = "chooser", label = "View", get = function() return VIEW_NAMES[VIEW.level] end,
            set = function(dir) VIEW.level = ((VIEW.level - 1 + (dir or 1)) % 3) + 1 end } } }
end

local function clipsRow()
    return { id = "clips", kind = "setting", label = "Before / after clips",
        value = function() return clipsOn() and "On" or "Off" end,
        desc = function()
            return "Plays a short clip of the stock game and one with the setting on, side by side, at the top of a setting's "
                .. "details. Off saves the memory the clips take (up to ~100 MB of video memory while this screen is open; freed "
                .. "when it closes). Applies at once and is remembered."
        end,
        activate = function() clipsAction().act() end,
        controls = { { type = "tick", label = "Play the clips", get = clipsOn, set = function() clipsAction().act() end } } }
end

PAGES.home = function(self)
    local p = { items = {} }
    for _, e in ipairs(TOP) do
        local value = nil
        if GROUPS[e.id] then value = function() return (masterState(GROUPS[e.id].master)) and "on" or "off" end end
        if e.id == "update" then value = function() return (updateShort()) end end
        table.insert(p.items, { id = e.id, label = titleOf(e.id), value = value, kind = "node", desc = function() return homeDesc(e.id) end })
    end
    -- the presets (the tab's home page): each sets many settings at once, so it asks first
    table.insert(p.items, { kind = "section", label = "Presets" })
    for i, pr in ipairs(PzoptSettings and PzoptSettings.presets() or {}) do
        local preset = pr
        table.insert(p.items, { id = "preset:" .. i, kind = "row", label = preset.title,
            desc = function() return esc(preset.tip) end,
            actions = { { label = "APPLY", key = "y", confirm = "Apply \"" .. preset.title .. "\"? It changes many settings at once (saved now).",
                act = function()
                    TOUCHED.enabled = true
                    for _, k in ipairs(PzoptSettings.pageKeys(1)) do TOUCHED[k] = true end
                    for _, k in ipairs(PzoptSettings.catMasters(1)) do TOUCHED[k] = true end
                    showMessage(self, preset.title .. ": " .. preset.apply())
                end } } })
    end
    table.insert(p.items, { kind = "section", label = "Help" })
    table.insert(p.items, { id = "problems", kind = "node", label = "Fix a problem",
        value = function() return tostring(#((PzoptSettingsLayout or {}).problems or {})) end,
        desc = function()
            return "Stutter while driving, low fps in a horde, slow loading, laggy input... Pick what you notice: why it happens, and "
                .. "the settings that help, wherever they live."
        end })
    table.insert(p.items, { kind = "section", label = "Tools" })
    table.insert(p.items, viewRow())
    table.insert(p.items, clipsRow())
    table.insert(p.items, { id = "export", kind = "row", label = "Export settings",
        desc = function()
            return "Copies every setting that differs from the build's defaults (Optimizations, Enhancements, Profiler, their master "
                .. "and category switches) to the clipboard as key=value text and saves it to Zomboid/pzopt/settings-export.ini. Import "
                .. "settings reads it back, here or on another PC."
        end,
        actions = { { label = "EXPORT", key = "y", act = function() showMessage(self, PzoptSettings.exportSettings()) end } } })
    table.insert(p.items, { id = "import", kind = "row", label = "Import settings",
        desc = function()
            return "Sets every setting from exported text: paste it into the box (it starts with the clipboard, or with "
                .. "Zomboid/pzopt/settings-export.ini when the clipboard holds no settings). Settings the text does not list go back "
                .. "to the build's defaults; settings pinned by pzopt.properties or -D stay. Saved at once."
        end,
        actions = { { label = "IMPORT...", key = "y", act = function() self:askImport() end } } })
    table.insert(p.items, { id = "uninstall", kind = "row", label = "Uninstall PZ Optimization",
        desc = function()
            local why = PzoptSettings and PzoptSettings.uninstallUnavailable() or ""
            return esc(PzoptSettings and PzoptSettings.UNINSTALL_TIP or "") .. (why ~= "" and (" <LINE> <LINE> <RGB:1,0.85,0.45> Not available now: " .. esc(why) .. ".") or "")
        end,
        actionsFn = function()
            if not PzoptSettings or PzoptSettings.uninstallUnavailable() ~= "" then return {} end
            return { { label = "UNINSTALL", danger = true, key = "y", confirm = PzoptSettings.UNINSTALL_CONFIRM, act = function() self:uninstall() end } }
        end })
    return p
end

-- a master switch's row (a group's or a category's): its value, its description, A flips it
local function masterRow(id, key, label, live, tip)
    return { id = id, kind = "master", master = key,
        label = label,
        value = function() return (masterState(key)) and "On" or "Off" end,
        desc = function()
            local on, now, pinned = masterState(key)
            local d = esc(tip) .. " " .. (live and LIVE_NOTE or RESTART_NOTE) .. " <LINE> <LINE> <RGB:0.66,0.66,0.70> Key: " .. key
            if pinned ~= "" then d = d .. " <LINE> <RGB:1,0.85,0.45> Pinned by " .. esc(pinned) .. ", which wins: change it there."
            elseif on ~= now and not live then d = d .. " <LINE> <RGB:1,0.85,0.45> Switched " .. (on and "on" or "off") .. ": applies when the game restarts." end
            return d
        end,
        activate = function() toggleMasterKey(key) end,
        controls = { { type = "tick", label = label, get = function() return (masterState(key)) end, set = function() toggleMasterKey(key) end } } }
end

local function groupMasterTip(a)
    local m = PzoptSettings and PzoptSettings.MASTERS[({ perf = 1, vis = 2, tools = 3 })[a]]
    return m and m.tip or ("The master switch: off, every " .. GROUPS[a].name .. " setting is the stock game's, whatever its own choice says "
        .. "(the choices stay saved for when it is on again).")
end

-- "Reset ... to defaults": the keys back to the build's defaults, the switches on (asks first)
local function resetRow(self, id, title, keysFn)
    return { id = id, kind = "row", label = "Reset " .. title .. " to defaults",
        desc = function()
            return "Puts every setting of " .. esc(title) .. " back to the build's default on this machine and its switches on. "
                .. "Saved at once."
        end,
        actions = { { label = "RESET", key = "y", confirm = "Reset every setting of " .. title .. " to the build's defaults?", act = function()
            local keys = keysFn()
            for _, k in ipairs(keys) do TOUCHED[k] = true end
            local n = PzoptSettings.resetKeys(keys)
            showMessage(self, title .. ": " .. n .. (n == 1 and " setting" or " settings") .. " back to the default.")
        end } } }
end

local function groupPage(self, a)
    local t = settingsTree()
    local G, g = t.byId[a], GROUPS[a]
    local p = { items = {} }
    table.insert(p.items, masterRow("master:" .. a, g.master, g.name .. " enabled", g.live, groupMasterTip(a)))
    for _, cat in ipairs(G and G.cats or {}) do
        table.insert(p.items, { id = a .. "/" .. cat.id, label = cat.title, kind = "node",
            value = function()
                if cat.master and not (masterState(cat.master)) then return "off" end
                return tostring(catCount(cat))
            end,
            desc = function()
                local subs = {}
                for _, s in ipairs(cat.subs) do table.insert(subs, s.title .. " (" .. #(shownKeys(s.keys)) .. ")") end
                return esc(cat.blurb) .. " <LINE> <LINE> " .. esc(table.concat(subs, ", ")) .. "."
            end })
    end
    table.insert(p.items, resetRow(self, "reset:" .. a, g.name, function()
        local keys = { g.master }
        for _, k in ipairs(PzoptSettings.catMasters(G.page)) do table.insert(keys, k) end
        for _, k in ipairs(PzoptSettings.pageKeys(G.page)) do table.insert(keys, k) end
        return keys
    end))
    return p
end

local function catPage(self, a, b, id)
    local t = settingsTree()
    local cat = t.byId[a .. "/" .. b]
    local p = { items = {} }
    if cat and cat.master then
        local live = cat.group.page ~= 1
        local entry = PzoptSettings and PzoptSettings.CATS.entry({ master = cat.master, title = cat.title }, live)
        table.insert(p.items, masterRow("master:" .. id, cat.master, cat.title .. " enabled", live, entry and entry.tip or ""))
    elseif cat and #cat.group.cats == 1 then -- (the Profiler's one category: its group's switch)
        local g = GROUPS[a]
        table.insert(p.items, masterRow("master:" .. id, g.master, g.name .. " enabled", g.live, groupMasterTip(a)))
    end
    for i, s in ipairs(cat and cat.subs or {}) do
        local sub = s
        table.insert(p.items, { id = id .. "/" .. i, label = sub.title, kind = "node",
            value = function() return tostring(#(shownKeys(sub.keys))) end,
            desc = function()
                local names = {}
                local keys, hidden = shownKeys(sub.keys)
                for _, k in ipairs(keys) do
                    local e = t.entry[k] and t.entry[k].e
                    table.insert(names, esc(e and e.label or k) .. ": " .. esc(showValue((settingNext(k)), e)))
                end
                if hidden > 0 then table.insert(names, "<RGB:0.66,0.66,0.70> " .. hidden .. " more in the " .. VIEW_NAMES[3] .. " view") end
                return table.concat(names, " <LINE> ")
            end })
    end
    if cat then
        table.insert(p.items, resetRow(self, "reset:" .. id, cat.title, function()
            local keys = {}
            if cat.master then table.insert(keys, cat.master) end
            for _, s in ipairs(cat.subs) do for _, k in ipairs(s.keys) do table.insert(keys, k) end end
            return keys
        end))
    end
    return p
end

-- the performance overlay's own toggle (the F9 key's, L3 + R3's), first on PROFILER > Performance overlay > Overlay
local OVERLAY_BIND = "Toggle performance overlay" -- pzopt_keybinding.lua

local function overlayKeys()
    local ok, name = pcall(function()
        local key = getCore():getKey(OVERLAY_BIND)
        if not key or key <= 0 then return nil end
        return Keyboard.getKeyName(key)
    end)
    return (ok and name and name ~= "") and (string.upper(name) .. " / L3 + R3") or "L3 + R3"
end

local function overlayRow()
    local function shown()
        local ok, v = pcall(function() return perf():isPzoptOverlayVisible() end)
        return ok and v
    end
    local function sampling()
        local ok, v = pcall(function() return perf():isPzoptOverlaySampling() end)
        return ok and v
    end
    return { id = "overlay:toggle", kind = "master", label = function() return "Performance overlay (" .. overlayKeys() .. ")" end,
        value = function() return shown() and "Shown" or "Hidden" end,
        activate = function() pcall(function() perf():togglePzoptOverlay() end) end,
        desc = function()
            local d = "Shows or hides the performance overlay now, like " .. overlayKeys() .. ": frame times, utilization and "
                .. "what the game thread does."
            if not sampling() then
                d = d .. " <LINE> <LINE> <RGB:1,0.85,0.45> The overlay's measuring is off (Profiler enabled / Measure frames for the "
                    .. "overlay): showing it switches the measuring on and saves that, as these settings do."
            end
            return d
        end,
        controls = { { type = "tick", label = "Shown", get = shown, set = function() pcall(function() perf():togglePzoptOverlay() end) end } } }
end

-- A setting's detail text: what it does, when it applies, its values, who pins it, the release it came with, the Java
-- classes that read it.
local function settingDesc(k, e, page)
    local v, pinned = settingNext(k)
    local now = opt("getPzoptOption", k)
    local def = opt("getPzoptOptionDefault", k)
    local d = esc(e and e.tip or "") .. " <LINE> <LINE> <RGB:0.66,0.66,0.70> "
        .. (settingLive(k, e, page) and LIVE_NOTE or RESTART_NOTE) .. " <LINE> "
        .. "Next launch: " .. esc(showValue(v, e)) .. "    In use now: " .. esc(showValue(now, e)) .. "    Default: "
        .. esc(showValue(def, e)) .. " <LINE> Key: " .. k
    if pinned ~= "" then d = d .. " <LINE> <RGB:1,0.85,0.45> Pinned by " .. esc(pinned) .. ", which wins: change it there." end
    local why = opt("getPzoptModCompatReason", k)
    if why ~= "" and opt("getPzoptOptionSaved", k) == "" then
        d = d .. " <LINE> <RGB:1,0.85,0.45> Mod compatibility switched it off: " .. esc(why) .. " (your choice here wins)."
    end
    if PzoptSettings then
        local date = PzoptSettings.date(k)
        local classes = PzoptSettings.classes(k)
        d = d .. " <LINE> <RGB:0.66,0.66,0.70> " .. (date and ("Released " .. date) or "New in this version") .. ".  Java: "
            .. esc(#classes > 0 and table.concat(classes, ", ") or "read by pzopt.Config only")
    end
    return d
end

-- the zoom curve's four numbers (x1, y1, x2, y2 of a CSS cubic-bezier, 0..1), each stepped by 0.05
local function bezierControls(k)
    local B = PzoptSettings and PzoptSettings.bezier
    if not B then return {} end
    local t = {}
    for i, name in ipairs(B.axes) do
        local axis = i
        table.insert(t, { type = "number", label = name,
            get = function()
                local v = B.parse((settingNext(k))) or B.parse(opt("getPzoptOptionDefault", k)) or { 0, 0, 1, 1 }
                return string.format("%.2f", v[axis])
            end,
            set = function(dir)
                if opt("getPzoptOptionPinnedBy", k) ~= "" then return end
                local v = B.parse((settingNext(k))) or B.parse(opt("getPzoptOptionDefault", k)) or { 0, 0, 1, 1 }
                v[axis] = math.max(0, math.min(1, math.floor((v[axis] + 0.05 * (dir or 1)) * 20 + 0.5) / 20))
                settingStore(k, B.format(v))
            end })
    end
    return t
end

-- the colour picker (the stock one, as the tab had): the pad goes to it and comes back to the screen
local function pickColour(self, k)
    if opt("getPzoptOptionPinnedBy", k) ~= "" or not PzoptSettings then return end
    require("ISUI/ISSliderPanel")
    require("ISUI/ISColorPickerHSB")
    if self.colourPicker then self.colourPicker:removeSelf() end
    local rgb = PzoptSettings.colourRGB((settingNext(k)))
    local picker = ISColorPickerHSB:new(0, 0, ColorInfo.new(rgb.r, rgb.g, rgb.b, 1))
    picker:initialise()
    picker.resetFocusTo = self
    picker:setPickedFunc(function(_, colour)
        local function channel(v) return math.floor(math.max(0, math.min(1, v)) * 255 + 0.5) end
        settingStore(k, string.format("%02X%02X%02X", channel(colour.r), channel(colour.g), channel(colour.b)))
        self.colourPicker = nil
        if self.parent then self:refreshPage() end
    end)
    local D = self.detailRect
    picker:setX(math.max(0, math.min(D[1] + 12, self.width - picker:getWidth())))
    picker:setY(math.max(0, math.min(D[2] + D[4] - picker:getHeight() - 2 * self.btnH, self.height - picker:getHeight())))
    self:addChild(picker)
    picker:setCapture(true)
    picker:setVisible(true)
    picker:bringToTop()
    self.colourPicker = picker
    local jd = mainJoypad()
    if jd then
        jd.focus = picker
        updateJoypadFocus(jd)
    end
end

-- One setting as a list row: its value, its detail (text, impact chart, clips), its control(s), DEFAULT and the clips
-- toggle. `where` (search, Fix a problem) names the page it lives on.
local function settingItem(self, k, where)
    local t = settingsTree()
    local b0 = t.entry[k]
    local e, page = b0 and b0.e, b0 and b0.page
    local kind = settingKind(k, e)
    local item = { id = k, label = e and e.label or k, kind = "setting", setting = k, sec = b0 and b0.sec,
        value = function() return showValue((settingNext(k)), nil) end,
        desc = function() return settingDesc(k, e, page) .. (where and (" <LINE> <RGB:0.66,0.66,0.70> On the page " .. esc(where)) or "") end }
    if kind == "bool" or kind == "choice" or kind == "bezier" then
        item.activate = function() settingStep(k, e, 1) end
        item.controls = { { type = kind == "bool" and "tick" or "chooser", label = kind == "bool" and "On" or (kind == "bezier" and "Curve" or "Value"),
            get = function() return kind == "bool" and (settingNext(k)) == "true" or showValue((settingNext(k)), nil) end,
            set = function(dir) settingStep(k, e, dir or 1) end } }
        if kind == "bezier" then
            for _, c in ipairs(bezierControls(k)) do table.insert(item.controls, c) end
            item.plot = true
        end
    elseif kind == "colour" then
        item.activate = function() pickColour(self, k) end
        item.controls = { { type = "colour", label = "Colour", get = function() return (settingNext(k)) end, set = function() pickColour(self, k) end } }
    end
    item.actions = { { label = "DEFAULT", key = "x", act = function()
        if opt("getPzoptOptionPinnedBy", k) == "" then settingStore(k, opt("getPzoptOptionDefault", k)) end
    end }, clipsAction() }
    return item
end

-- where a key lives: "Optimizations > Driving > Car smoothness", and that subcategory's node id
local function homeOf(k)
    local t = settingsTree()
    for _, G in ipairs(t.groups) do
        for _, c in ipairs(G.cats) do
            for i, s in ipairs(c.subs) do
                for _, key in ipairs(s.keys) do
                    if key == k then return G.name .. " / " .. c.title .. " / " .. s.title, G.id .. "/" .. c.id .. "/" .. i end
                end
            end
        end
    end
    return nil, nil
end

local function subPage(self, a, b, c, id)
    local t = settingsTree()
    local s = t.byId[id]
    local p = { items = {}, settingsPage = true }
    if a == "tools" and b == "overlay" and c == 1 then table.insert(p.items, overlayRow()) end
    local keys, hidden = shownKeys(s and s.keys or {})
    for _, k in ipairs(keys) do table.insert(p.items, settingItem(self, k)) end
    if s and s.dlss and PzoptSettings then
        table.insert(p.items, { id = "dlss", kind = "row", label = "NVIDIA DLSS files",
            value = function() return PzoptSettings.deps().state or "" end,
            desc = function() local d = PzoptSettings.deps() return esc(d.title) .. " <LINE> <LINE> " .. esc(d.tip) end,
            actionsFn = function()
                local d = PzoptSettings.deps()
                if not d.enabled then return {} end
                return { { label = string.upper(d.title), key = "y", confirm = "Download the DLSS files (NVIDIA's library and the pzopt shim)?",
                    act = function() PzoptSettings.depsInstall() end } }
            end })
        PzoptSettings.depsCheck()
    end
    if hidden > 0 then
        table.insert(p.items, { id = "more", kind = "row", label = "Show " .. hidden .. " more (" .. VIEW_NAMES[3] .. " view)",
            desc = function()
                return hidden .. " settings of " .. esc(s.title) .. " are left out in the " .. VIEW_NAMES[VIEW.level] .. " view. A or SHOW ALL "
                    .. "switches to " .. VIEW_NAMES[3] .. " (" .. VIEW_TIPS[3] .. ") until the game closes; the view is on the home page."
            end,
            activate = function() VIEW.level = 3 end,
            actions = { { label = "SHOW ALL", key = "y", act = function() VIEW.level = 3 end } } })
    end
    if #p.items == 0 then table.insert(p.items, { kind = "note", label = "No settings here." }) end
    return p
end

-- Fix a problem: what players report (the layout's `problems`), why it happens, and the settings that help
PAGES.problems = function(self)
    local p = { items = {} }
    for i, pr in ipairs((PzoptSettingsLayout or {}).problems or {}) do
        local problem = pr
        table.insert(p.items, { id = "problems/" .. i, kind = "node", label = problem.title, value = tostring(#(problem.keys or {})),
            desc = function() return esc(problem.cause) .. " <LINE> <LINE> " .. esc(problem.why) end })
    end
    return p
end

local function problemPage(self, i)
    local pr = ((PzoptSettingsLayout or {}).problems or {})[i]
    local p = { items = {}, settingsPage = true }
    if not pr then return p end
    p.head = function() return " <RGB:0.85,0.55,0.95> " .. esc(pr.cause) .. " <LINE> " end
    for _, k in ipairs(pr.keys or {}) do
        if settingsTree().entry[k] then table.insert(p.items, settingItem(self, k, (homeOf(k)))) end
    end
    return p
end

-- Search: the best matches of every setting, best first (PzoptSettings.search); A opens the setting on its own page
local function searchRows()
    local t = settingsTree()
    if t.searchRows then return t.searchRows end
    local rows = {}
    for _, G in ipairs(t.groups) do
        for _, c in ipairs(G.cats) do
            for i, s in ipairs(c.subs) do
                for _, k in ipairs(s.keys) do
                    local b = t.entry[k]
                    table.insert(rows, { entry = b.e, key = k, section = { title = b.section }, cat = { title = c.title },
                        sub = { title = s.title }, node = G.id .. "/" .. c.id .. "/" .. i, where = G.name .. " / " .. c.title .. " / " .. s.title })
                end
            end
        end
    end
    t.searchRows = rows
    return rows
end

PAGES.search = function(self)
    local p = { items = {}, settingsPage = true }
    local q = self.query or ""
    local idx = self:searchIndex()
    local hits = (idx and q:match("%w")) and PzoptSettings.search(idx, q) or nil
    local list = {}
    for row, score in pairs(hits or {}) do table.insert(list, { row = row, score = score }) end
    table.sort(list, function(a, b) if a.score ~= b.score then return a.score > b.score end return a.row.key < b.row.key end)
    for n, h in ipairs(list) do
        if n > 60 then break end
        local row = h.row
        local item = settingItem(self, row.key, row.where)
        item.value = function() return showValue((settingNext(row.key)), nil) end
        item.searchNode = row.node
        table.insert(item.actions, 1, { label = "OPEN ITS PAGE", key = "y", act = function() self:openSetting(row.node, row.key) end })
        table.insert(p.items, item)
    end
    if #list == 0 then
        table.insert(p.items, { kind = "note", label = q:match("%w") and ("Nothing matches \"" .. q .. "\".") or "Type in the search box (L3 with a controller)." })
    elseif #list > 60 then
        table.insert(p.items, { kind = "note", label = (#list - 60) .. " more: type more words to narrow it down." })
    end
    p.count = #list
    return p
end

PAGES.bench = function(self)
    local p = { items = {} }
    for _, pg in ipairs(BENCH_PAGES) do
        table.insert(p.items, { id = pg.id, label = pg.title, kind = "node", desc = function() return pg.blurb end })
    end
    return p
end

-- the benchmark's state while the screen is up: the setup being made, the baseline, the run shown, the two compared
local function benchState(self)
    if not self.bench then
        local sc = PzoptBench.scenarios()
        self.bench = { scenarios = sc, setup = PzoptBench.newSetup(sc), onlyDiff = true }
    end
    return self.bench
end

PAGES["bench/setup"] = function(self)
    local B = benchState(self)
    local S = B.setup
    local p = { items = {} }
    local function estimateLine()
        local ids, passes, ab, total, steps = PzoptBench.estimate(S, B.scenarios)
        if #ids == 0 then return "<RGB:1,0.6,0.5> Choose at least one scene." end
        return string.format("<RGB:0.9,0.9,0.6> %d passes, about %d min", steps, math.max(1, math.floor(total / 60 + 0.5)))
    end
    p.head = function()
        return "Our own test routes in a fresh world made for the benchmark; your saves are not touched. The game restarts for "
            .. "every pass, so each one starts alike: keep the window in front until the report shows. <LINE> " .. estimateLine()
            .. (B.error and (" <LINE> <RGB:1,0.6,0.5> " .. esc(B.error)) or "")
    end
    local controls = {
        { type = "chooser", label = "Passes", get = function() local n = PzoptBench.passes[S.passes] return n == 1 and "1 pass" or (n .. " passes") end,
          set = function(dir) S.passes = ((S.passes - 1 + (dir or 1)) % #PzoptBench.passes) + 1 end },
        { type = "chooser", label = "Between passes", get = function() local c = PzoptBench.cooldown[S.cool] return c == 0 and "No cool-down" or (c .. " s cool-down") end,
          set = function(dir) S.cool = ((S.cool - 1 + (dir or 1)) % #PzoptBench.cooldown) + 1 end },
        { type = "entry", label = "Name (optional)", get = function() return S.label end, set = function(v) S.label = v end },
    }
    local actions = {
        { label = "START BENCHMARK", danger = true, key = "y", confirm = function()
              return (MainScreen.instance and MainScreen.instance.inGame)
                  and "Start the benchmark? This saves your game and quits the session; the game restarts for every pass and shows the report at the end."
                  or "Start the benchmark? The game restarts for every pass and shows the report at the end."
          end, act = function()
            B.error = PzoptBench.start(S, B.scenarios)
        end },
        { label = "PAST RESULTS", key = "x", act = function() self:openPage("bench/history") end },
    }
    table.insert(p.items, { kind = "section", label = "Scenes" })
    for _, sc in ipairs(B.scenarios) do
        local id = sc.id
        table.insert(p.items, { id = "scen:" .. id, kind = "tick", label = sc.title,
            value = function() return S.scen[id] and "On" or "Off" end,
            activate = function() S.scen[id] = not S.scen[id] end,
            desc = function() return esc(sc.what) .. " <LINE> <LINE> <RGB:0.6,0.8,1> Tests: " .. esc(sc.stresses) end,
            controls = controls, actions = actions })
    end
    table.insert(p.items, { kind = "section", label = "Options" })
    for _, o in ipairs(PzoptBench.options) do
        local id = o.id
        table.insert(p.items, { id = "opt:" .. id, kind = "tick", label = o.title,
            value = function() return S.opt[id] and "On" or "Off" end,
            activate = function() S.opt[id] = not S.opt[id] end,
            desc = function() return esc(o.desc) end, controls = controls, actions = actions })
    end
    return p
end

PAGES["bench/history"] = function(self)
    local B = benchState(self)
    B.items = PzoptBench.history()
    local p = { items = {} }
    if #B.items == 0 then
        table.insert(p.items, { kind = "note", label = "No results yet: run the benchmark first (Setup)." })
    end
    for i, it in ipairs(B.items) do
        local run = it[1]
        table.insert(p.items, { id = run, kind = "row",
            label = function() return (it[2] or run) .. ((it[4] or "") ~= "" and ("  " .. it[4]) or "") .. (B.baseline == run and "  [baseline]" or "") end,
            value = "score " .. ((it[3] or "") ~= "" and it[3] or "-"),
            desc = function()
                local setup = (it[7] ~= nil and it[7] ~= "") and it[7] or "settings and mods not recorded (an older run)"
                return "Score " .. esc((it[3] or "") ~= "" and it[3] or "-") .. " <LINE> <LINE> "
                    .. (#it.parts > 0 and esc(table.concat(it.parts, ", ")) or "no scene result") .. " <LINE> <LINE> <RGB:0.6,0.72,0.9> " .. esc(setup)
                    .. (B.baseline == run and " <LINE> <LINE> <RGB:0.9,0.9,0.6> The baseline: Compare puts the other runs beside it." or "")
            end,
            activate = function() self:openPage("bench/report", { run }, "bench/history") end,
            actions = {
                { label = "OPEN", key = "y", act = function() self:openPage("bench/report", { run }, "bench/history") end },
                { label = "COMPARE", key = "x", act = function()
                    local base = B.baseline
                    if not base or base == run then
                        local older = B.items[i + 1]
                        base = older and older[1] or nil
                        if not base then
                            local newer = B.items[i - 1]
                            if newer then self:openPage("bench/compare", { run, newer[1] }, "bench/history") end
                            return
                        end
                    end
                    self:openPage("bench/compare", { base, run }, "bench/history")
                end },
                { label = function() return B.baseline == run and "CLEAR BASELINE" or "SET AS BASELINE" end,
                  act = function() B.baseline = (B.baseline ~= run) and run or nil end },
                { label = "SHOW RESULTS FOLDER", confirm = "Open the results folder in your file manager?", act = function() PzoptBench.folder() end },
            } })
    end
    return p
end

PAGES["bench/compare"] = function(self)
    local B = benchState(self)
    if not B.cmp then
        local items = PzoptBench.history()
        if #items >= 2 then B.cmp = PzoptBench.compare(items[2][1], items[1][1]) end
    end
    local p = { items = {} }
    if not B.cmp then
        table.insert(p.items, { kind = "note", label = "Two runs are needed: run the benchmark twice (Setup), then compare them here or in Past results." })
        return p
    end
    local c = B.cmp
    local ha, hb = c.heads[1] or {}, c.heads[2] or {}
    p.head = function()
        return "<RGB:0.66,0.66,0.70> Baseline: <RGB:1,1,1> <SPACE> " .. esc(ha[3]) .. ((ha[4] or "") ~= "" and ("  " .. esc(ha[4])) or "")
            .. " <LINE> <RGB:0.66,0.66,0.70> This run: <RGB:1,1,1> <SPACE> " .. esc(hb[3]) .. ((hb[4] or "") ~= "" and ("  " .. esc(hb[4])) or "")
    end
    local controls = { { type = "tick", label = "Only what differs", get = function() return B.onlyDiff end,
        set = function() self:setOnlyDiff(not B.onlyDiff) end } }
    p.x = { label = "Only what differs", act = function() self:setOnlyDiff(not B.onlyDiff) end }
    p.y = { label = "Past results", act = function() self:openPage("bench/history") end }
    for _, r in ipairs(PzoptBench.compareRows(c, B.onlyDiff)) do
        if r.kind == "section" then
            table.insert(p.items, { kind = "section", label = r.label })
        elseif r.kind == "note" then
            table.insert(p.items, { kind = "note", label = r.label })
        else
            local change = r.extra ~= "" and r.extra or nil
            table.insert(p.items, { id = r.label, kind = "row", label = r.label, value = change or (r.kind == "diff" and "differs" or nil),
                changed = r.kind == "diff",
                desc = function()
                    return "<RGB:0.66,0.66,0.70> " .. esc(r.section or "") .. " <LINE> <LINE> <RGB:0.66,0.66,0.70> Baseline: <RGB:1,1,1> "
                        .. esc(r.a ~= "" and r.a or "-") .. " <LINE> <RGB:0.66,0.66,0.70> This run: <RGB:1,1,1> <SPACE> " .. esc(r.b ~= "" and r.b or "-")
                        .. (change and (" <LINE> <RGB:0.66,0.66,0.70> Change: <RGB:1,1,1> <SPACE> " .. esc(change)) or "")
                        .. (r.kind == "diff" and " <LINE> <LINE> <RGB:1,0.85,0.35> Not the same in both runs." or "")
                end, controls = controls })
        end
    end
    return p
end

PAGES["bench/report"] = function(self)
    local B = benchState(self)
    if not B.rep then
        local items = PzoptBench.history()
        if #items >= 1 then B.rep = { run = items[1][1] } end
    end
    local p = { items = {} }
    if not B.rep then
        table.insert(p.items, { kind = "note", label = "No run yet: run the benchmark first (Setup)." })
        return p
    end
    local R = B.rep
    if not R.d or R.d.run ~= R.run then
        R.d = PzoptBench.report(R.run)
        print("[pzopt] benchmark: report of run " .. tostring(R.run) .. " shown")
    end
    local d = R.d
    local actions = {
        { label = "OPEN FULL REPORT", danger = true, key = "y", confirm = "Open the full report in your web browser?", act = function() PzoptBench.open(R.run) end },
        { label = function() return R.copied and "COPIED" or "COPY SHARE CODE" end, key = "x", act = function()
            R.copied = PzoptBench.share(R.run)
            PzoptLogInfo("[pzopt] menu: share code copied=" .. tostring(R.copied))
        end },
        { label = "COMPARE A CODE", act = function() self:askShareCode() end },
        { label = "PAST RESULTS", act = function() self:openPage("bench/history") end },
        { label = "RUN AGAIN", act = function() self:openPage("bench/setup") end },
    }
    p.head = function()
        local t = ""
        local head = d.head and (esc(d.head[3]) .. (d.head[4] ~= "" and ("  -  " .. esc(d.head[4])) or "")) or esc(R.run)
        t = t .. "<RGB:0.66,0.66,0.70> " .. head .. " <LINE> "
        for _, cfg in ipairs({ "A", "B" }) do
            local s = d.scores[cfg]
            if s then
                t = t .. "<RGB:0.66,0.66,0.70> " .. (cfg == "A" and "Score " or "Stock score ") .. "<RGB:0.5,1,0.7> <SPACE> " .. esc(s.score)
                    .. (s.ci ~= "-" and (" <RGB:0.6,0.6,0.6> <SPACE> +- " .. esc(s.ci)) or "") .. "    "
            end
        end
        t = t .. " <LINE> "
        for _, l in ipairs(PzoptBench.pcLines(d, R.theirs, 100000)) do
            t = t .. string.format("<RGB:%.2f,%.2f,%.2f> ", l[2], l[3], l[4]) .. esc(l[1]) .. " <LINE> "
        end
        return t
    end
    for i, s in ipairs(d.scen) do
        local row = s
        table.insert(p.items, { id = "scen" .. i, kind = "row", label = PzoptBench.scenLabel(row), value = (row[6] or "-") .. " fps",
            bad = row[22] and row[22] ~= "",
            desc = function()
                local t = "<RGB:1,1,1> <SPACE> " .. esc(table.concat(PzoptBench.scenFacts(d, row, R.theirs), " <LINE> "))
                if row[22] and row[22] ~= "" then t = t .. " <LINE> <RGB:1,0.6,0.5> " .. esc(row[22]) end
                if row[23] and row[23] ~= "nan" and row[23] ~= "" then
                    local st = tonumber(row[23]) or 0
                    t = t .. " <LINE> " .. (st >= 97 and "<RGB:0.6,0.9,0.6> " or "<RGB:1,0.75,0.4> ") .. "Frame rate stability " .. esc(row[23])
                        .. " %  (slowest pass / fastest pass; 97 % or more is steady)"
                end
                local ph = d.phases[row[2] .. "/" .. row[4]]
                if ph then
                    local parts = {}
                    for k, v in string.gmatch(ph, "([^;=]+)=([^;]*)") do
                        table.insert(parts, k .. " " .. v .. "%")
                        if #parts >= 6 then break end
                    end
                    t = t .. " <LINE> <RGB:0.7,0.85,1> Game thread: " .. esc(table.concat(parts, ",  "))
                end
                return t
            end,
            chart = function(el, x, y, w, h) PzoptBench.drawCharts(el, d, row, x, y, w, h) end,
            desc2 = function() return PzoptBench.recText(d) end,
            actions = actions })
    end
    if #d.scen == 0 then table.insert(p.items, { kind = "note", label = "This run has no scene result." }) end
    return p
end

-- a word longer than a line (a folder path in the update's notes) gets break points after its / \\ - _ . (the rich text
-- panel breaks lines at spaces only, and ran such a word past the screen)
local function breakLong(t)
    return (string.gsub(t, "%S+", function(w)
        if #w <= 32 or string.sub(w, 1, 1) == "<" then return w end
        local out = string.gsub(w, "([/\\%-_%.])", "%1 ")
        return out
    end))
end

PAGES.update = function(self)
    local p = { items = {} }
    local function row(id, label, value, extra)
        local it = { id = id, kind = "row", label = label, value = value,
            desc = function()
                local v = PzoptUpdateApi.view()
                local t = "<RGB:1,1,1> " .. v.text
                if id == "notes" or extra then
                    t = t .. " <LINE> <LINE> <RGB:0.8,0.8,0.8> " .. (v.notes ~= "" and v.notes or "No release notes.")
                end
                return breakLong(t)
            end,
            actionsFn = function()
                local v = PzoptUpdateApi.view()
                return v.actions
            end }
        table.insert(p.items, it)
    end
    row("state", "State", function() return (updateShort()) end, true)
    row("installed", "Installed build", function() return installedCommit() end)
    row("available", "Available", function() local t = updTag() return t ~= "" and offeredCommit() or "-" end)
    row("source", "From", function()
        local v = PzoptUpdateApi.view()
        if v.tag == "" then return "-" end
        return v.workshop and "Steam Workshop copy" or "GitHub release"
    end)
    row("notes", "Release notes", function() return updTag() ~= "" and "" or "-" end)
    p.progress = function()
        local v = PzoptUpdateApi.view()
        return v.progress
    end
    return p
end

PAGES.compat = function(self)
    local d = PzoptCompatApi.data()
    local p = { items = {} }
    table.insert(p.items, { id = "overview", kind = "row", label = "Overview",
        value = function()
            local n = 0
            for _ in pairs(d.mods) do n = n + 1 end
            return #d.jars .. " jar" .. (#d.jars == 1 and "" or "s")
        end,
        desc = function() return PzoptCompatApi.overview(d) end })
    local function profileRow()
        local now, nextV, pinned = PzoptCompatApi.profile()
        local names = PzoptCompatApi.names
        local acts = {}
        for _, v in ipairs({ "performance", "compatibility" }) do
            -- a next-launch choice like the settings' (TOUCHED: the restart prompt and the reminder name it)
            table.insert(acts, { label = string.upper(names[v]), key = v ~= nextV and "x" or nil, act = function()
                TOUCHED[COMPAT_KEY] = true
                PzoptCompatApi.choose(v)
            end })
        end
        -- the screen's own restart (restartNow: the harness rig, the helper, the stock quit that saves a game first); none
        -- where restartBlocked says why (a multiplayer client, a harness run's bench save)
        if now ~= nextV and pinned == "" and not self:restartBlocked() then
            table.insert(acts, { label = "RESTART GAME", danger = true, key = "y", confirm = "Restart the game now so the choice applies?", act = function() self:restartNow() end })
        end
        return acts
    end
    table.insert(p.items, { id = "profile", kind = "row", label = "Choice",
        value = function() local _, nextV = PzoptCompatApi.profile() return PzoptCompatApi.names[nextV] or nextV end,
        desc = function()
            local now, nextV, pinned = PzoptCompatApi.profile()
            local names = PzoptCompatApi.names
            local t = "Max performance (the default): Java mods are checked and listed but switch nothing off, and windows mods draw in "
                .. "are reused like the game's own. Max compatibility: the settings a Java mod patches are switched off so the mod meets "
                .. "the code its author tested, and windows mods draw in are redrawn at the game's own rate. A value you pick for either "
                .. "setting on the options tab still wins. <LINE> <LINE> <RGB:0.66,0.66,0.70> Next launch: <RGB:1,1,1> <SPACE> " .. esc(names[nextV] or nextV)
                .. " <RGB:0.66,0.66,0.70>    Running now: <RGB:1,1,1> <SPACE> " .. esc(names[now] or now)
            if pinned ~= "" then t = t .. " <LINE> <RGB:1,0.85,0.45> Pinned by " .. esc(pinned) .. " on this install."
            elseif now ~= nextV then t = t .. " <LINE> <RGB:1,0.85,0.45> Applies after a restart." end
            return t
        end, actionsFn = profileRow })
    if #d.jars > 0 then table.insert(p.items, { kind = "section", label = "Java files of this launch" }) end
    for i, jar in ipairs(d.jars) do
        table.insert(p.items, { id = "jar" .. i, kind = "row", label = jar.name, value = PzoptCompatApi.verdict(d, i),
            desc = function() return PzoptCompatApi.jarText(d, i) end })
    end
    return p
end

local function pageOf(self, id)
    local a, b, c = split3(id)
    if PAGES[id] then return PAGES[id](self) end
    if a == "problems" and b then return problemPage(self, tonumber(b)) end
    if GROUPS[a] and not b then return groupPage(self, a) end
    if GROUPS[a] and b and not c then return catPage(self, a, b, id) end
    if GROUPS[a] and c then return subPage(self, a, b, c, id) end
    return { items = {} }
end

local function selectable(it)
    return it and it.kind ~= "section" and it.kind ~= "note"
end

-- ------------------------------------------------------------------ the list (master): rows drawn by the panel itself
-- (never a list box: the screen keeps the controller; the selection scrolls into view; headings are never selected)

PzoptMenuList = ISPanel:derive("PzoptMenuList")

function PzoptMenuList:new(x, y, w, h, screen)
    local o = ISPanel:new(x, y, w, h)
    setmetatable(o, self)
    self.__index = self
    o.screen = screen
    o.items, o.selected, o.top = {}, 1, 1
    o.backgroundColor = { r = 0.04, g = 0.04, b = 0.06, a = 0.85 }
    o.borderColor = { r = SIDE_BORDER.r, g = SIDE_BORDER.g, b = SIDE_BORDER.b, a = 1 }
    o.rowH = fontH(UIFont.Small) + 10
    return o
end

-- Each row's lines at the list's width: the label wrapped, the value beside it when it fits there (a short value), else
-- under it (a long choice), so nothing is cut. Recomputed every frame: a value can change (a switch, the updater).
function PzoptMenuList:layoutItems()
    local hS = fontH(UIFont.Small)
    local pad = 12
    local inner = self.width - 8 - 2 * pad
    for _, it in ipairs(self.items) do
        local label = tostring(val(it.label))
        local value = it.value and tostring(val(it.value)) or nil
        if value == "" then value = nil end
        local L = { value = value }
        local vw = value and textW(UIFont.Small, value) or 0
        if not selectable(it) then
            -- a heading is drawn in capitals: wrapped as it is drawn (the lower-case width ran past the list)
            L.label = wrap(it.kind == "section" and string.upper(label) or label, UIFont.Small, inner + pad)
        elseif value and vw <= inner * 0.4 then
            L.label = wrap(label, UIFont.Small, inner - vw - pad)
            L.inline = true
        else
            L.label = wrap(label, UIFont.Small, inner)
            L.valueLines = value and wrap(value, UIFont.Small, inner - 14) or {}
        end
        if #L.label == 0 then L.label = { "" } end
        L.h = 10 + (#L.label + (L.valueLines and #L.valueLines or 0)) * hS
        if it.kind == "section" then L.h = L.h + 4 end
        it.L = L
    end
end

function PzoptMenuList:visibleCount()
    local h, n = 0, 0
    for i = self.top, #self.items do
        local it = self.items[i]
        local ih = it.L and it.L.h or self.rowH
        if h + ih > self.height - 8 then break end
        h, n = h + ih, n + 1
    end
    return math.max(1, n)
end

function PzoptMenuList:firstSelectable()
    for i, it in ipairs(self.items) do if selectable(it) then return i end end
    return nil
end

-- the selection moves to row i (or the next selectable row in the direction dir) and into view
function PzoptMenuList:setSelected(i, dir)
    local n = #self.items
    if n == 0 then return end
    self:layoutItems()
    i = math.max(1, math.min(i, n))
    if not selectable(self.items[i]) then
        local j = i
        local step = dir or 1
        while self.items[j] and not selectable(self.items[j]) do j = j + step end
        if not self.items[j] then
            j = i
            while self.items[j] and not selectable(self.items[j]) do j = j - step end
        end
        if not self.items[j] then -- nothing selectable: the first rows in view
            self.selected = nil
            self.top = 1
            return
        end
        i = j
    end
    self.selected = i
    -- a heading right above the row comes into view with it
    local want = i
    if i > 1 and self.items[i - 1].kind == "section" then want = i - 1 end
    if want < self.top then self.top = want end
    while i >= self.top + self:visibleCount() and self.top < i do self.top = self.top + 1 end
end

function PzoptMenuList:prerender()
    ISPanel.prerender(self)
    self:layoutItems()
    local s = self.screen
    local c = s.colour or C_TEXT
    local focused = s.joyfocus and s.pane == "master"
    local vis = self:visibleCount()
    local pad = 12
    local hS = fontH(UIFont.Small)
    local y = 4
    local my = self:isMouseOver() and self:getMouseY() or -1
    self.rowsY = {}
    for row = 0, vis - 1 do
        local i = self.top + row
        local it = self.items[i]
        if not it then break end
        local L = it.L
        self.rowsY[i] = { y, L.h }
        if it.kind == "section" then
            self:drawRect(4, y + L.h - 3, self.width - 8, 1, 0.35, c.r, c.g, c.b)
            for k, line in ipairs(L.label) do
                self:drawText(line, 4 + pad, y + 7 + (k - 1) * hS, c.r, c.g, c.b, 1, UIFont.Small)
            end
        elseif it.kind == "note" then
            for k, line in ipairs(L.label) do
                self:drawText(line, 4 + pad, y + 5 + (k - 1) * hS, C_GREY.r, C_GREY.g, C_GREY.b, 1, UIFont.Small)
            end
        else
            local sel = i == self.selected
            local hot = my >= y and my < y + L.h
            if sel then
                self:drawRect(4, y, self.width - 8, L.h, 0.18, c.r, c.g, c.b)
                self:drawRect(4, y, 4, L.h, 1, c.r, c.g, c.b)
                if focused then self:drawRectBorder(4, y, self.width - 8, L.h, 0.9, c.r, c.g, c.b) end
            end
            if hot then self:drawRect(4, y, self.width - 8, L.h, 0.08, 1, 1, 1) end
            local col = (sel or hot) and C_TEXT or { r = 0.8, g = 0.8, b = 0.82 }
            if it.kind == "master" then col = c end
            if it.changed then col = { r = 1, g = 0.85, b = 0.35 } end
            if it.bad then col = { r = 1, g = 0.6, b = 0.5 } end
            local ly = y + 5
            for _, line in ipairs(L.label) do
                self:drawText(line, 4 + pad + 2, ly, col.r, col.g, col.b, 1, UIFont.Small)
                ly = ly + hS
            end
            local vc = C_GREY
            local v = L.value
            if v == "On" or v == "on" then vc = C_OPT elseif v == "Off" or v == "off" then vc = { r = 1, g = 0.55, b = 0.35 }
            elseif v and string.sub(v, 1, 1) == "+" then vc = C_OPT elseif v and string.match(v, "^%-%d") then vc = { r = 1, g = 0.55, b = 0.35 } end
            if L.inline then
                self:drawTextRight(v, self.width - 4 - pad + 4, y + 5, vc.r, vc.g, vc.b, 1, UIFont.Small)
            elseif L.valueLines then
                for _, line in ipairs(L.valueLines) do
                    self:drawText(line, 4 + pad + 16, ly, vc.r, vc.g, vc.b, 1, UIFont.Small)
                    ly = ly + hS
                end
            end
        end
        y = y + L.h
    end
    if #self.items > vis then -- where the window is in the list
        local h = self.height - 8
        local barH = math.max(12, math.floor(h * vis / #self.items))
        local barY = 4 + math.floor((h - barH) * (self.top - 1) / math.max(1, #self.items - vis))
        self:drawRect(self.width - 4, barY, 2, barH, 0.6, 1, 1, 1)
    end
end

function PzoptMenuList:rowAt(y)
    for i, r in pairs(self.rowsY or {}) do
        if y >= r[1] and y < r[1] + r[2] then return i end
    end
    return nil
end

function PzoptMenuList:onMouseDown(x, y)
    self.pressed = self:rowAt(y)
    return true
end

-- a click selects the row; a click on the selected row opens it (a node) or acts (a switch, a tick)
function PzoptMenuList:onMouseUp(x, y)
    local i = self:rowAt(y)
    if not i or i ~= self.pressed then self.pressed = nil return end
    self.pressed = nil
    if not selectable(self.items[i]) then return end
    local s = self.screen
    if i == self.selected then
        s:activateItem(i)
    else
        self:setSelected(i)
        s:showDetail()
        getSoundManager():playUISound("UIHighlightMainMenuItem")
    end
end

function PzoptMenuList:onMouseUpOutside(x, y)
    self.pressed = nil
end

function PzoptMenuList:onMouseWheel(del)
    local vis = self:visibleCount()
    self.top = math.max(1, math.min(self.top + (del > 0 and 3 or -3), math.max(1, #self.items - vis + 1)))
    return true
end

-- ------------------------------------------------------------------ the detail's controls: a chooser, a tick box, a name

PzoptMenuControl = ISPanel:derive("PzoptMenuControl")

function PzoptMenuControl:new(x, y, w, h, spec, screen)
    local o = ISPanel:new(x, y, w, h)
    setmetatable(o, self)
    self.__index = self
    o.spec, o.screen = spec, screen
    o.background = false
    o.name = tostring(val(spec.label))
    return o
end

function PzoptMenuControl:createChildren()
    if self.spec.type == "entry" then
        local lw = textW(UIFont.Small, tostring(val(self.spec.label))) + 16
        self.entry = ISTextEntryBox:new(tostring(self.spec.get() or ""), lw, 0, math.max(80, self.width - lw), self.height)
        self.entry:initialise()
        self.entry:instantiate()
        local spec = self.spec
        self.entry.target = self
        self.entry.onTextChangeFunction = function(target, box) spec.set(box:getInternalText()) end
        self:addChild(self.entry)
    end
end

function PzoptMenuControl:prerender()
    local s = self.screen
    local focused = s.joyfocus and s.pane == "detail" and s.focusEl == self
    local hot = self:isMouseOver()
    local c = s.colour or C_TEXT
    local label = tostring(val(self.spec.label))
    local hS = fontH(UIFont.Small)
    local ty = math.floor((self.height - hS) / 2)
    if self.spec.type == "tick" then
        local box = hS + 2
        local on = self.spec.get()
        self:drawRectBorder(0, math.floor((self.height - box) / 2), box, box, 0.9, 1, 1, 1)
        if on then self:drawRect(3, math.floor((self.height - box) / 2) + 3, box - 6, box - 6, 1, c.r, c.g, c.b) end
        self:drawText(clipText(UIFont.Small, label, self.width - box - 10), box + 8, ty, 1, 1, 1, 1, UIFont.Small)
    elseif self.spec.type == "chooser" or self.spec.type == "number" then
        local v = tostring(self.spec.get() or "")
        local lw = textW(UIFont.Small, label) + 16
        self:drawText(label, 0, ty, 0.8, 0.8, 0.82, 1, UIFont.Small)
        local bx = lw
        local bw = self.width - lw
        self:drawRect(bx, 0, bw, self.height, 0.5, 0.08, 0.08, 0.1)
        self:drawRectBorder(bx, 0, bw, self.height, 0.5, 1, 1, 1)
        self:drawText("<", bx + 6, ty, C_GREY.r, C_GREY.g, C_GREY.b, 1, UIFont.Small)
        self:drawTextRight(">", bx + bw - 6, ty, C_GREY.r, C_GREY.g, C_GREY.b, 1, UIFont.Small)
        local inner = bw - 2 * (textW(UIFont.Small, "<") + 14)
        self:drawTextCentre(clipText(UIFont.Small, v, inner), bx + bw / 2, ty, 1, 1, 1, 1, UIFont.Small)
    elseif self.spec.type == "entry" then
        self:drawText(label, 0, ty, 0.8, 0.8, 0.82, 1, UIFont.Small)
    elseif self.spec.type == "colour" then
        -- the swatch of the colour (the default's while none is saved), its hex, "Change..." (the picker)
        local v = tostring(self.spec.get() or "")
        local lw = textW(UIFont.Small, label) + 16
        self:drawText(label, 0, ty, 0.8, 0.8, 0.82, 1, UIFont.Small)
        local rgb = PzoptSettings and PzoptSettings.colourRGB(v) or { r = 1, g = 1, b = 1 }
        local sw = self.height * 2
        self:drawRect(lw, 2, sw, self.height - 4, 1, rgb.r, rgb.g, rgb.b)
        self:drawRectBorder(lw, 2, sw, self.height - 4, 0.8, 1, 1, 1)
        local hex = "#" .. (PzoptSettings and PzoptSettings.colourHex(v) or v)
        self:drawText(hex .. "   (A or click: change)", lw + sw + 10, ty, 1, 1, 1, 1, UIFont.Small)
    end
    if focused then self:drawRectBorder(-3, -2, self.width + 6, self.height + 4, 0.9, c.r, c.g, c.b) end
    if hot and self.spec.type ~= "entry" then self:drawRect(-3, -2, self.width + 6, self.height + 4, 0.06, 1, 1, 1) end
end

function PzoptMenuControl:onMouseDown(x, y)
    self.pressed = true
    return true
end

function PzoptMenuControl:onMouseUp(x, y)
    if not self.pressed then return end
    self.pressed = false
    local spec = self.spec
    if spec.type == "tick" or spec.type == "colour" then
        spec.set()
    elseif spec.type == "chooser" or spec.type == "number" then
        local lw = textW(UIFont.Small, tostring(val(spec.label))) + 16
        spec.set(x < lw + (self.width - lw) / 2 and -1 or 1)
    end
    getSoundManager():playUISound("UIActivateMainMenuItem")
    self.screen:refreshPage()
end

function PzoptMenuControl:onMouseUpOutside(x, y)
    self.pressed = false
end

-- ------------------------------------------------------------------ the sidebar

PzoptMenuSidebar = ISPanel:derive("PzoptMenuSidebar")

function PzoptMenuSidebar:new(x, y, w, h, screen)
    local o = ISPanel:new(x, y, w, h)
    setmetatable(o, self)
    self.__index = self
    o.screen = screen
    o.backgroundColor = { r = SIDE_BG.r, g = SIDE_BG.g, b = SIDE_BG.b, a = 1 }
    o.borderColor = { r = SIDE_BORDER.r, g = SIDE_BORDER.g, b = SIDE_BORDER.b, a = 1 }
    o.rows = {}
    return o
end

function PzoptMenuSidebar:prerender()
    ISPanel.prerender(self)
    local s = self.screen
    local pad = 12
    self:drawText(ITEM_TEXT, pad, s.sidePad, 1, 1, 1, 1, UIFont.Medium)

    local focusRow = s.joyfocus and s.pane == "side" and s.sideIndex or nil
    local path = {}
    for _, n in ipairs(pathOf(s.selected)) do path[n] = true end
    for i, r in ipairs(self.rows) do
        local sel = r.id == s.selected
        local c = colourOf(r.id)
        local hot = self:isMouseOver() and self:getMouseY() >= r.y and self:getMouseY() < r.y + r.h
        if sel then
            self:drawRect(4, r.y, self.width - 8, r.h, 0.18, c.r, c.g, c.b)
            self:drawRect(4, r.y, 4, r.h, 1, c.r, c.g, c.b)
        elseif path[r.id] then
            self:drawRect(4, r.y, 4, r.h, 0.6, c.r, c.g, c.b)
        end
        if hot then self:drawRect(4, r.y, self.width - 8, r.h, 0.08, 1, 1, 1) end
        if focusRow == i then self:drawRectBorder(4, r.y, self.width - 8, r.h, 0.9, c.r, c.g, c.b) end
        local x = pad + 2 + r.depth * 14
        local col = r.top and c or (((sel or hot) and C_TEXT) or { r = 0.8, g = 0.8, b = 0.82 })
        local count = r.count
        local countW = count and (textW(UIFont.Small, count) + pad) or 0
        self:drawText(clipText(UIFont.Small, r.label, self.width - x - pad - countW), x, r.y + 5, col.r, col.g, col.b, 1, UIFont.Small)
        if count then
            local cc = count == "off" and C_STOCK or C_DIM
            self:drawTextRight(count, self.width - pad, r.y + 5, cc.r, cc.g, cc.b, 1, UIFont.Small)
        end
        if r.note then
            local note, news = r.note()
            local nc = news and { r = 0.6, g = 1, b = 0.6 } or C_GREY
            self:drawText(clipText(UIFont.Small, note, self.width - x - pad), x, r.y + 5 + fontH(UIFont.Small), nc.r, nc.g, nc.b, 1, UIFont.Small)
        end
    end
end

function PzoptMenuSidebar:rowAt(y)
    for i, r in ipairs(self.rows) do
        if y >= r.y and y < r.y + r.h then return i end
    end
    return nil
end

function PzoptMenuSidebar:onMouseDown(x, y)
    self.pressed = self:rowAt(y)
    if not self.pressed and y < self.screen.sidePad + fontH(UIFont.Medium) + 4 then self.pressed = "title" end
    return true
end

function PzoptMenuSidebar:onMouseUp(x, y)
    local i = self:rowAt(y)
    local s = self.screen
    if self.pressed == "title" and not i then
        self.pressed = nil
        s:selectNode("home", "master")
        return
    end
    if not i or i ~= self.pressed then self.pressed = nil return end
    self.pressed = nil
    s:selectNode(self.rows[i].id, "side")
    getSoundManager():playUISound("UIActivateMainMenuItem")
end

function PzoptMenuSidebar:onMouseUpOutside(x, y)
    self.pressed = nil
end

-- ------------------------------------------------------------------ the screen

PzoptMainMenu = ISPanelJoypad:derive("PzoptMainMenu")

function PzoptMainMenu:new(x, y, w, h)
    local o = ISPanelJoypad:new(x, y, w, h)
    setmetatable(o, self)
    self.__index = self
    o.backgroundColor = { r = 0, g = 0, b = 0, a = 0.9 }
    o.borderColor = { r = 0, g = 0, b = 0, a = 0 }
    o.pzoptReadRoot = true -- the benchmark's readability rig logs the texts drawn inside it (devBenchmarkUi=read)
    o.selected = "home"
    o.pane = "master"
    o.crumbs = {}
    o.backTo = {}
    o.detailEls = {}
    return o
end

function PzoptMainMenu:searchIndex()
    if not self.searchIdx and PzoptSettings then self.searchIdx = PzoptSettings.searchIndex(searchRows()) end
    return self.searchIdx
end

-- a setting by its key, on its page (the harness's tours, Fix a problem)
function PzoptMainMenu:openKey(key)
    local _, node = homeOf(key)
    if node then self:openSetting(node, key) end
    return node
end

-- the view (1 Simple, 2 Advanced, 3 Everything), the page shown again
function PzoptMainMenu:setView(level)
    VIEW.level = math.max(1, math.min(3, level))
    self:refreshPage()
end

-- a search result or a problem's setting on its own page, the cursor on it (its tier shown even in a narrower view)
function PzoptMainMenu:openSetting(node, key)
    if tierOf(key) > VIEW.level then VIEW.level = tierOf(key) end
    local from = self.selected
    self:selectNode(node, "master", key)
    if from == "search" or string.sub(from, 1, 9) == "problems/" then self.backTo[node] = from end -- B: back to the results
    PzoptLogInfo("[pzopt] menu: opened " .. key .. " on " .. node)
end

-- After LATER: "Restart to apply N settings" and its RESTART NOW at the right end of the bar under the page (the
-- controller's hints take what is left of it). Nil when nothing waits, the prompt is up or a restart cannot be offered.
function PzoptMainMenu:reminder()
    local n = #pendingRestart()
    if n == 0 or self.prompt or self:restartBlocked() then
        if self.restartBtn then self.restartBtn:setVisible(false) end
        return nil
    end
    local text = "Restart to apply " .. n .. " setting" .. (n == 1 and "" or "s")
    if not self.restartBtn then
        local bw = math.max(120, textW(UIFont.Small, "RESTART NOW") + 24)
        self.restartBtn = ISButton:new(0, 0, bw, self.btnH, "RESTART NOW", self, function() self:askRestart() end)
        self.restartBtn:initialise()
        self.restartBtn:instantiate()
        self.restartBtn:enableAcceptColor()
        self:addChild(self.restartBtn)
    end
    local bx = self.contentX + self.contentW - 6 - self.restartBtn:getWidth()
    self.restartBtn:setX(bx)
    self.restartBtn:setY(self.hintY + math.floor((self.hintH - self.btnH) / 2))
    self.restartBtn:setVisible(true)
    local tw = textW(UIFont.Small, text)
    return { text = text, x = bx - 12 - tw, w = tw + 12 + self.restartBtn:getWidth() + 6 }
end

-- the sidebar's rows: the six entries, the selected one's children (a group's categories, the benchmark's pages), the
-- selected category's subcategories; the deepest level is left out when the rows would not fit the sidebar
function PzoptMainMenu:sideRows()
    local t = settingsTree()
    local rows = {}
    local top, b = split3(self.selected == "home" and "x" or self.selected)
    local function add(id, label, depth, isTop, count, note)
        table.insert(rows, { id = id, label = label, depth = depth, top = isTop, count = count, note = note })
    end
    local function build(withSubs)
        rows = {}
        for _, e in ipairs(TOP) do
            local count = nil
            if GROUPS[e.id] and not (masterState(GROUPS[e.id].master)) then count = "off" end
            add(e.id, e.label, 0, true, count, e.id == "update" and updateShort or nil)
            if e.id == top then
                if GROUPS[top] then
                    for _, cat in ipairs(t.byId[top] and t.byId[top].cats or {}) do
                        local off = cat.master and not (masterState(cat.master))
                        add(top .. "/" .. cat.id, cat.title, 1, false, off and "off" or tostring(catCount(cat)))
                        if withSubs and b == cat.id then
                            for i, s in ipairs(cat.subs) do add(top .. "/" .. cat.id .. "/" .. i, s.title, 2, false, tostring(#(shownKeys(s.keys)))) end
                        end
                    end
                elseif top == "bench" then
                    for _, pg in ipairs(BENCH_PAGES) do add(pg.id, pg.title, 1, false) end
                end
            end
        end
    end
    build(true)
    local rowH = fontH(UIFont.Small) + 10
    local need = 0
    for _, r in ipairs(rows) do need = need + rowH + 2 + (r.note and fontH(UIFont.Small) or 0) end
    if need > self.sideRowsH then build(false) end
    local y = self.sideTop
    for _, r in ipairs(rows) do
        r.h = rowH + (r.note and fontH(UIFont.Small) or 0)
        r.y = y
        y = y + r.h + (r.top and 2 or 0)
    end
    return rows
end

function PzoptMainMenu:createChildren()
    ISPanelJoypad.createChildren(self)
    local sw, sh = self.width, self.height
    local m = math.max(10, math.floor(math.min(sw, sh) * 0.02))
    self.margin, self.gap = m, math.max(10, math.floor(m * 0.8))
    local hS, hM = fontH(UIFont.Small), fontH(UIFont.Medium)
    local btnH = math.max(25, hS + 6)
    self.btnH = btnH
    -- the sidebar: as wide as its widest row (every category and subcategory), within a fifth and 30 % of the window
    local t = settingsTree()
    local widest = textW(UIFont.Medium, ITEM_TEXT)
    for _, e in ipairs(TOP) do widest = math.max(widest, textW(UIFont.Small, e.label) + textW(UIFont.Small, "off") + 12) end
    for _, G in ipairs(t.groups) do
        for _, c in ipairs(G.cats) do
            widest = math.max(widest, textW(UIFont.Small, c.title) + 14 + textW(UIFont.Small, "000") + 12)
            for _, s in ipairs(c.subs) do widest = math.max(widest, textW(UIFont.Small, s.title) + 28 + textW(UIFont.Small, "00") + 12) end
        end
    end
    local sideW = math.max(math.floor(sw * 0.16), math.min(math.floor(sw * 0.28), widest + 2 * 12 + 8))
    self.sidePad = 10
    self.sideTop = self.sidePad + hM + 10
    self.side = PzoptMenuSidebar:new(m, m, sideW, sh - 2 * m, self)
    self.side:initialise()
    self:addChild(self.side)
    -- the search box under the title: every setting of the three groups (PzoptSettings.search), live as you type
    self:createSearch(sideW, btnH)
    local backTitle = getText("UI_btn_back")
    self.back = ISButton:new(10, self.side.height - 10 - btnH, math.max(90, textW(UIFont.Small, backTitle) + 24), btnH, backTitle, self,
        function(target) target:close("the BACK button") end)
    self.back:initialise()
    self.back:instantiate()
    self.back:enableCancelColor()
    self.side:addChild(self.back)
    self.sideRowsH = self.back:getY() - 10 - self.sideTop
    -- the page area right of it, under the breadcrumbs
    self.contentX = m + sideW + self.gap
    self.contentW = sw - self.contentX - m
    self.crumbY = m + 2
    self.pageY = m + hM + 10
    -- the controller's button-hint bar under the page area (shown while a controller drives the screen)
    self.hintH = math.max(22, hS + 4) + 8
    self.pageH = sh - self.pageY - m - self.hintH - 6
    self.hintY = self.pageY + self.pageH + 6
    -- list | detail side by side when both get a usable width, else the detail under the list
    local minPane = math.max(240, hS * 15)
    self.stacked = self.contentW < 2 * minPane + self.gap
    if self.stacked then
        self.listRect = { self.contentX, self.pageY, self.contentW, math.floor(self.pageH * 0.42) }
        local dy = self.pageY + self.listRect[4] + self.gap
        self.detailRect = { self.contentX, dy, self.contentW, self.pageY + self.pageH - dy }
    else
        local lw = math.floor((self.contentW - self.gap) * 0.42)
        self.listRect = { self.contentX, self.pageY, lw, self.pageH }
        self.detailRect = { self.contentX + lw + self.gap, self.pageY, self.contentW - lw - self.gap, self.pageH }
    end
    local L = self.listRect
    self.list = PzoptMenuList:new(L[1], L[2], L[3], L[4], self)
    self.list:initialise()
    self:addChild(self.list)
    PzoptLogInfo(string.format("[pzopt] menu: screen %dx%d, sidebar %d px, page %d px %s", sw, sh, sideW, self.contentW,
        self.stacked and "(the detail under the list)" or "(list | detail)"))
    self:selectNode(self.selected, self.pane)
end

local function newText(self, x, y, w, h)
    local t = ISRichTextPanel:new(x, y, w, h)
    t:initialise()
    t.background = true
    t.backgroundColor = { r = 0.04, g = 0.04, b = 0.06, a = 0.85 }
    t.borderColor = { r = SIDE_BORDER.r, g = SIDE_BORDER.g, b = SIDE_BORDER.b, a = 1 }
    t.clip = true
    t.autosetheight = false
    t.marginLeft, t.marginRight, t.marginTop = 12, 12, 10
    self:addChild(t)
    t:addScrollBars()
    return t
end

-- The impact chart and the before / after clips of a setting's detail: drawn by the options file's PzoptPreview
-- (drawBars, drawClip: the same bars and clips the options tab had). That file loads after this one (name order), so the
-- two classes derive from it the first time a detail needs them.
local function previewClasses()
    if PzoptMenuImpact or not PzoptPreview then return PzoptMenuImpact, PzoptMenuClips end
    PzoptMenuImpact = PzoptPreview:derive("PzoptMenuImpact")
    function PzoptMenuImpact:prerender()
        ISPanel.prerender(self)
        local x, y, w = self.pad, 6, self.width - 2 * self.pad
        self:text("Effect on your hardware", x, y, C_TEXT, self.fontM)
        y = y + self.hM + 4
        if self.key then self:drawBars(x, y, w, PzoptSettings.effects(self.key)) end
    end
    PzoptMenuClips = PzoptPreview:derive("PzoptMenuClips")
    function PzoptMenuClips:prerender()
        ISPanel.prerender(self)
        if not self.clip then return end
        local info = PzoptSettings.clipInfo(self.clip)
        local gap = self.pad
        local now = getTimestampMs()
        local x = math.floor((self.width - (2 * self.clipW + gap)) / 2)
        -- a caption wider than its clip drops its part in brackets ("OPTIMIZED (every optimization on)" -> "OPTIMIZED")
        local function caption(c)
            if textW(UIFont.Small, c) <= self.clipW then return c end
            return string.match(c, "^(.-) %(") or c
        end
        self:drawClip(x, 4, self.clipW, self.clipH, caption(info.sides[1]), info.stock, now, { r = 1, g = 0.62, b = 0.40 }) -- (amber as text)
        self:drawClip(x + self.clipW + gap, 4, self.clipW, self.clipH, caption(info.sides[2]), info.opt, now, C_OPT)
    end
    return PzoptMenuImpact, PzoptMenuClips
end

-- the chart's height for `rows` bar rows (all of them when nil): the title, the rows, the x axis and its title
local function chartHeight(rowH, rows)
    local hS, hM = fontH(UIFont.Small), fontH(UIFont.Medium)
    local axes = rows or (PzoptSettings and #PzoptSettings.AXES or 9)
    return 6 + hM + 4 + axes * rowH + 6 + hS + 2 + hS + 6
end

-- the zoom curve beside its four numbers (PzoptBezierPlot from the options file reads `sliders`)
local function newPlot(self, x, y, size, key)
    if not PzoptBezierPlot or not PzoptSettings then return nil end
    local plot = PzoptBezierPlot:new(x, y, size, size)
    plot:initialise()
    local function v(i)
        local B = PzoptSettings.bezier
        local t = B.parse((settingNext(key))) or B.parse(opt("getPzoptOptionDefault", key)) or { 0, 0, 1, 1 }
        return t[i]
    end
    plot.sliders = {}
    for i = 1, 4 do plot.sliders[i] = { getCurrentValue = function() return v(i) end } end
    self:addChild(plot)
    return plot
end

-- The detail of the list's selected row, in the detail rectangle: its text (the page's head first), the chart and the text
-- under it (a report row), its controls (choosers, tick boxes, the name), its action buttons at the bottom. On a settings
-- page the clips (on top while they are on) and the text keep their place from row to row (the options tab's rule); under
-- the text the impact chart draws only the resources the setting moves, and the controls and buttons follow right under it.
-- Built again whenever the selection or a value changes; the controller's focus in it is kept by position.
function PzoptMainMenu:showDetail()
    for _, el in ipairs(self.detailEls) do
        el:setVisible(false)
        self:removeChild(el)
    end
    self.detailEls, self.focusables, self.actionButtons = {}, {}, {}
    self.chartRect, self.impactChart = nil, nil
    local D = self.detailRect
    local it = self.list.items[self.list.selected or 0]
    local page = self.page or {}
    local fixed = page.settingsPage and PzoptSettings ~= nil
    local function actionsOf(row)
        local acts = {}
        if not row then return acts end
        if row.kind == "node" then
            table.insert(acts, { label = "OPEN", act = function() self:activateItem(self.list.selected) end })
        end
        for _, a in ipairs(row.actions or {}) do table.insert(acts, a) end
        if row.actionsFn then for _, a in ipairs(row.actionsFn()) do table.insert(acts, a) end end
        return acts
    end
    local function buttonRows(acts)
        local rows, rowW = { {} }, 0
        for _, a in ipairs(acts) do
            local title = tostring(val(a.label))
            local w = math.max(100, textW(UIFont.Small, title) + 24)
            if rowW > 0 and rowW + w > D[3] then
                table.insert(rows, {})
                rowW = 0
            end
            table.insert(rows[#rows], { a = a, title = title, w = w })
            rowW = rowW + w + 8
        end
        if #rows[1] == 0 then rows = {} end
        return rows
    end
    local actions = actionsOf(it)
    self.detailActions = actions
    local btnRows = buttonRows(actions)
    local ch = math.max(self.btnH, fontH(UIFont.Small) + 8)
    local controls = it and it.controls or {}
    -- the slots of a settings page: the most button rows and controls any of its rows has
    local bandBtn, bandCtrl = #btnRows, #controls
    if fixed then
        for _, row in ipairs(page.items or {}) do
            if selectable(row) then
                bandBtn = math.max(bandBtn, #buttonRows(actionsOf(row)))
                bandCtrl = math.max(bandCtrl, #(row.controls or {}))
            end
        end
    end
    -- the buttons: in rows from the bottom up, as many per row as fit
    local y = D[2] + D[4]
    local bottom = y - bandBtn * (self.btnH + 6)
    y = bottom + #btnRows * (self.btnH + 6)
    for r = #btnRows, 1, -1 do
        y = y - self.btnH
        local x = D[1]
        for _, bt in ipairs(btnRows[r]) do
            local a = bt.a
            local b = ISButton:new(x, y, bt.w, self.btnH, bt.title, self, function()
                if not a.quiet then getSoundManager():playUISound("UIActivateMainMenuItem") end
                self:runAction(a)
            end)
            b:initialise()
            b:instantiate()
            b.pzoptAction = a
            b.pzoptRow = r
            if a.danger then b:enableAcceptColor() end
            self:addChild(b)
            table.insert(self.detailEls, b)
            table.insert(self.actionButtons, b)
            x = x + bt.w + 8
        end
        y = y - 6
    end
    y = bottom
    -- the controls above the buttons (a curve's numbers leave room for its plot on their right)
    local ctrlEls = {}
    local plotSize = it and it.plot and math.min(4 * (ch + 6) - 6, math.floor(D[3] * 0.3)) or 0
    local top = y - bandCtrl * (ch + 6)
    y = top + #controls * (ch + 6)
    for i = #controls, 1, -1 do
        y = y - ch - 6
        local w = math.min(D[3] - 8, math.max(300, math.floor(D[3] * 0.8)))
        if plotSize > 0 and i > 1 then w = math.min(w, D[3] - plotSize - 20) end
        local c = PzoptMenuControl:new(D[1] + 4, y, w, ch, controls[i], self)
        c:initialise()
        self:addChild(c)
        table.insert(self.detailEls, c)
        table.insert(ctrlEls, 1, c)
    end
    if plotSize > 0 and #controls > 1 then
        local plot = newPlot(self, D[1] + D[3] - plotSize - 4, top + (ch + 6), plotSize, it.setting)
        if plot then table.insert(self.detailEls, plot) end
    end
    y = top
    if bandCtrl > 0 or bandBtn > 0 then y = y - 6 end
    -- a settings page: the impact chart above the controls, the clips at the top while they are on
    local textTop = D[2]
    if fixed then
        local Impact, Clips = previewClasses()
        local hS = fontH(UIFont.Small)
        local avail = y - D[2]
        local clipsOnNow = clipsOn() and Clips ~= nil
        local clipH, clipW = 0, 0
        if clipsOnNow then
            clipW = math.floor((D[3] - 12 - 24) / 2)
            clipH = math.floor(clipW * PzoptSettings.CLIP_H / PzoptSettings.CLIP_W)
            local maxH = math.floor(avail * 0.34) - (hS + 2 + 4 + 8)
            if clipH > maxH then
                clipH = math.max(40, maxH)
                clipW = math.floor(clipH * PzoptSettings.CLIP_W / PzoptSettings.CLIP_H)
            end
        end
        local clipBand = clipsOnNow and (4 + hS + 2 + clipH + 8) or 0
        -- the bars: taller rows while the text keeps at least six lines
        local rowH = hS + 2
        while rowH < 2 * hS + 8 and avail - clipBand - chartHeight(rowH + 2) > 8 * hS + 60 do rowH = rowH + 2 end
        -- the text keeps the room of a full chart (it does not move between rows); the chart draws only the resources the
        -- setting moves, right under the text, and the controls and buttons move up by what it leaves free; a setting that
        -- moves none has no chart
        local chartMax = chartHeight(rowH)
        if Impact and avail - clipBand - chartMax > 4 * hS then
            local key = it and (it.setting or it.master) or nil
            local rows = key and PzoptSettings.effectRows(key) or 0
            local top = y - chartMax
            local used = 0
            if rows > 0 then
                local chartH = chartHeight(rowH, rows)
                local chart = Impact:new(D[1], top, D[3], chartH)
                chart:initialise()
                chart.barRowH = rowH
                chart.key = key
                chart.backgroundColor = { r = 0.04, g = 0.04, b = 0.06, a = 0.85 }
                chart.borderColor = { r = SIDE_BORDER.r, g = SIDE_BORDER.g, b = SIDE_BORDER.b, a = 1 }
                self:addChild(chart)
                table.insert(self.detailEls, chart)
                self.impactChart = chart
                used = chartH + 6
            end
            local free = chartMax + 6 - used
            for _, el in ipairs(self.detailEls) do
                if el ~= self.impactChart and el:getY() >= y then el:setY(el:getY() - free) end
            end
            y = top - 6
        end
        if clipsOnNow then
            local clips = Clips:new(D[1], D[2], D[3], clipBand - 6)
            clips:initialise()
            clips.clipW, clips.clipH = clipW, clipH
            local key = it and (it.setting or it.master)
            clips.clip = key and PzoptSettings.clipOf(key, it.sec) or nil
            clips.backgroundColor = { r = 0.04, g = 0.04, b = 0.06, a = 0.85 }
            clips.borderColor = { r = SIDE_BORDER.r, g = SIDE_BORDER.g, b = SIDE_BORDER.b, a = 1 }
            self:addChild(clips)
            table.insert(self.detailEls, clips)
            textTop = D[2] + clipBand
        end
    end
    -- the text, and the chart with the text under it (a report row)
    local textH = y - textTop
    local head = page.head and page.head() or nil
    local title = it and tostring(val(it.label)) or ""
    local body = breakLong(it and it.desc and it.desc() or (self.emptyText or "")) -- (a path, a long key list)
    if fixed and clipsOn() and it and (it.setting or it.master) then -- what the clips above show
        body = body .. " <LINE> <LINE> <RGB:0.66,0.66,0.70> Clips: " .. esc(PzoptSettings.clipInfo(PzoptSettings.clipOf(it.setting or it.master, it.sec)).title)
    end
    -- a blank line under the heading: the next line is spaced for the small font and touched its descenders
    local text = (it and (" <H2> " .. esc(title) .. " <TEXT> <LINE> <LINE> ") or "") .. (head and (head .. " <LINE> ") or "")
        .. " <LINE> " .. body
    if it and it.chart then
        local t1 = math.floor(textH * 0.36)
        local t2 = math.floor(textH * 0.22)
        local cy = textTop + t1 + 6
        self.chartRect = { D[1], cy, D[3], textH - t1 - t2 - 12 }
        local tp = newText(self, D[1], textTop, D[3], t1)
        tp.text = text
        tp:paginate()
        table.insert(self.detailEls, tp)
        self.detailText = tp
        local tp2 = newText(self, D[1], cy + self.chartRect[4] + 6, D[3], t2)
        tp2.text = it.desc2 and it.desc2() or ""
        tp2:paginate()
        table.insert(self.detailEls, tp2)
        self.detailText2 = tp2
        local chartEl = ISPanel:new(self.chartRect[1], self.chartRect[2], self.chartRect[3], self.chartRect[4])
        chartEl:initialise()
        chartEl.background = false
        local item = it
        chartEl.prerender = function(o) item.chart(o, 0, 0, o.width, o.height) end
        self:addChild(chartEl)
        table.insert(self.detailEls, chartEl)
    else
        local tp = newText(self, D[1], textTop, D[3], math.max(fontH(UIFont.Small) * 3, textH))
        tp.text = text
        tp:paginate()
        table.insert(self.detailEls, tp)
        self.detailText = tp
        self.detailText2 = nil
    end
    if page.progress then
        local pr = page.progress()
        if pr then
            local bar = ISPanel:new(D[1] + 12, textTop + textH - 26, D[3] - 24, 14)
            bar:initialise()
            bar.background = false
            bar.prerender = function(o)
                o:drawRectBorder(0, 0, o.width, o.height, 0.6, 1, 1, 1)
                o:drawRect(1, 1, (o.width - 2) * math.min(1, page.progress() or 0), o.height - 2, 0.9, 0.35, 0.55, 0.9)
            end
            self:addChild(bar)
            table.insert(self.detailEls, bar)
        end
    end
    -- the controller's order in the detail: the controls, then the button rows
    for _, c in ipairs(ctrlEls) do table.insert(self.focusables, { el = c, row = #self.focusables + 1 }) end
    for _, b in ipairs(self.actionButtons) do table.insert(self.focusables, { el = b, button = true }) end
    if self.pane == "detail" then self:focusDetail(math.min(self.detailIndex or 1, #self.focusables)) end
    self.lastDesc = body
    if self.prompt or self.help then self:setPageShown(false) end
end

-- The sidebar's search box: names, descriptions, keys, resources and the Java classes of every setting (the tab's search).
-- The results are the "search" page; clearing the box goes back to the page the search started from.
function PzoptMainMenu:createSearch(sideW, btnH)
    local pad = 12
    local box = ISTextEntryBox:new("", pad, self.sideTop, sideW - 2 * pad, btnH)
    box:initialise()
    box:instantiate()
    box:setClearButton(true)
    local screen = self
    box.onCommandEntered = function(o) -- Enter: the results' list takes the cursor
        o:unfocus()
        if screen.selected == "search" then
            screen.pane = screen.list.selected and "master" or "side"
            screen:traceFocus()
        end
    end
    box.render = function(o)
        ISTextEntryBox.render(o)
        if o:getText() == "" and not o:isFocused() then
            local hint = screen.joyfocus and "Search (L3)" or "Search settings (Ctrl+F)"
            o:drawText(clipText(UIFont.Small, hint, o.width - 16), 8, math.floor((o.height - fontH(UIFont.Small)) / 2),
                C_GREY.r, C_GREY.g, C_GREY.b, 1, UIFont.Small)
        end
    end
    self.side:addChild(box)
    self.search = box
    self.searchTyped, self.searchAt, self.query = "", 0, ""
    self.sideTop = self.sideTop + btnH + 10
end

-- the search box takes the keyboard (Ctrl+F, a click) or, with a controller, the game's on-screen keyboard (L3)
function PzoptMainMenu:focusSearch()
    local box = self.search
    if not box then return end
    local jd = self.joyfocus or mainJoypad()
    if jd and self.joyfocus and OnScreenKeyboard and not OnScreenKeyboard.IsVisible() then
        local osk = OnScreenKeyboard.Show(jd.player, box, jd)
        osk.prevFocus = self -- the pad comes back here when it closes
        jd.focus = osk
        PzoptLogInfo("[pzopt] menu: search: on-screen keyboard")
        return
    end
    box:focus()
    PzoptLogInfo("[pzopt] menu: search: box focused")
end

-- Each frame: the text, once it has been still for 120 ms (one search per word, not per letter), shows its results;
-- an empty box returns to where the search started. The index fills in idle frames meanwhile.
function PzoptMainMenu:pollSearch()
    local box = self.search
    if not box then return end
    local idx = self:searchIndex()
    if idx and not self.searchReady then
        for _ = 1, 12 do
            if PzoptSettings.searchStep(idx) then self.searchReady = true break end
        end
    end
    local text, now = box:getText() or "", getTimestampMs()
    if text ~= self.searchTyped then
        self.searchTyped, self.searchAt = text, now
        return
    end
    if text == self.query or now - self.searchAt < 120 then return end
    self:runSearch(text)
end

function PzoptMainMenu:runSearch(text)
    self.query = text
    SEARCH.query = text
    if string.match(text, "%w") then
        if self.selected ~= "search" then self.searchFrom = self.selected end
        if self.selected == "search" then self:refreshPage() else self:selectNode("search", self.pane == "side" and "master" or nil) end
        PzoptLogInfo("[pzopt] menu: search \"" .. text .. "\": " .. tostring(self.page and self.page.count or 0) .. " results")
    elseif self.selected == "search" then
        self:selectNode(self.searchFrom or "home", "master")
    end
end

-- Import settings...: the pasted text (it starts with the clipboard or the last export) in a box; OK saves it
function PzoptMainMenu:askImport()
    local w = math.min(720, getCore():getScreenWidth() - 40)
    local h = 2 * (math.max(25, fontH(UIFont.Small) + 6) + 20)
    local box = ISTextBox:new((getCore():getScreenWidth() - w) / 2, getCore():getScreenHeight() / 2 - 200, w, h,
        "Paste exported settings (settings not listed go back to the defaults; saved at once):",
        PzoptSettings and PzoptSettings.importSource() or "", self, function(target, button)
            if button.internal ~= "OK" then return end
            local msg = PzoptSettings.importSettings(button.parent.entry:getText() or "")
            for page = 1, 3 do
                TOUCHED[PzoptSettings.MASTERS[page].key] = true
                for _, k in ipairs(PzoptSettings.catMasters(page)) do TOUCHED[k] = true end
                for _, k in ipairs(PzoptSettings.pageKeys(page)) do TOUCHED[k] = true end
            end
            if target.parent then target:refreshPage() end
            showMessage(target, msg)
        end)
    box:setMultipleLine(true)
    box:setNumberOfLines(12)
    box:setMaxLines(100000)
    box:initialise()
    box:addToUIManager()
    box:setAlwaysOnTop(true)
    box:bringToTop()
    self.importBox = box
    local jd = mainJoypad()
    if jd then
        box.prevFocus = jd.focus
        jd.focus = box
        updateJoypadFocus(jd)
    end
end

-- Uninstall PZ Optimization...: pzopt.Uninstall starts its helper, then the game quits the stock way (main menu only)
function PzoptMainMenu:uninstall()
    local ok, msg = PzoptSettings.uninstall()
    if ok then
        PzoptLogInfo("[pzopt] menu: uninstall started, quitting")
        MainScreen.instance:quitToDesktop()
    else
        showMessage(self, msg)
    end
end

-- values changed (a switch, a tick, the updater): the page's rows, the sidebar, the detail
function PzoptMainMenu:refreshPage()
    local sel = self.list.selected
    local top = self.list.top
    self.page = pageOf(self, self.selected)
    self.list.items = self.page.items
    if sel then self.list:setSelected(sel) end
    self.list.top = math.min(top, math.max(1, #self.list.items))
    self.side.rows = self:sideRows()
    local scroll = self.detailText and self.detailText:getYScroll() or 0
    self:showDetail()
    if self.detailText then self.detailText:setYScroll(scroll) end
    self:syncRestart()
    self:traceFocus()
end

-- ------------------------------------------------------------------ restarting for the next-launch settings
-- A change that waits for a restart opens the prompt (the settings it names, RESTART NOW, LATER); after LATER the
-- breadcrumbs' line keeps "Restart to apply N settings" with its RESTART NOW. The restart is pzopt.Restart (the same as
-- the update's Restart game): the settings are saved as they change (UserOptions), options.ini is the game's own; the
-- menu only shows at the main menu, so no world is open to save.
function PzoptMainMenu:syncRestart()
    local pend = pendingRestart()
    local n = #pend
    if n > (self.pendingSeen or 0) and not self.forceNoPrompt then self:showRestartPrompt() end
    self.pendingSeen = n
    if n == 0 then self:hideRestartPrompt() end
    self:reminder()
end

function PzoptMainMenu:showRestartPrompt()
    self:hideRestartPrompt()
    self.prompt = true
    self.promptIndex = 2 -- LATER under the cursor: a restart needs a deliberate press
    local pend = pendingRestart()
    local hS = fontH(UIFont.Small)
    local w = math.min(self.contentW - 40, math.max(420, hS * 36))
    local inner = w - 32
    local blocked = self:restartBlocked()
    local intro = blocked and (blocked .. " The settings below take effect when the game starts again; they are saved already:")
        or "These settings take effect when the game starts again; they are saved already:"
    local lines = wrap(intro, UIFont.Small, inner)
    local names = {}
    for i, p in ipairs(pend) do
        if i > 8 then table.insert(names, { "... and " .. (#pend - 8) .. " more" }) break end
        table.insert(names, wrap("- " .. p.label, UIFont.Small, inner - 12))
    end
    local h = 16 + fontH(UIFont.Medium) + 10 + #lines * hS + 8
    for _, n in ipairs(names) do h = h + #n * hS end
    h = math.min(h + 16 + self.btnH + 16, self.pageH)
    local panel = ISPanel:new(self.contentX + math.floor((self.contentW - w) / 2), self.pageY + math.floor((self.pageH - h) / 2), w, h)
    panel:initialise()
    panel.backgroundColor = { r = SIDE_BG.r, g = SIDE_BG.g, b = SIDE_BG.b, a = 0.98 }
    panel.borderColor = { r = 1, g = 1, b = 1, a = 1 }
    local screen = self
    panel.render = function(o)
        o:drawText("Restart to apply", 16, 12, 1, 1, 1, 1, UIFont.Medium)
        local y = 16 + fontH(UIFont.Medium) + 6
        for _, l in ipairs(lines) do o:drawText(l, 16, y, 0.85, 0.85, 0.88, 1, UIFont.Small) y = y + hS end
        y = y + 8
        for _, n in ipairs(names) do
            for _, l in ipairs(n) do
                if y + hS > o.height - screen.btnH - 24 then break end
                o:drawText(l, 28, y, 1, 0.85, 0.45, 1, UIFont.Small)
                y = y + hS
            end
        end
        for i, b in ipairs(screen.promptButtons or {}) do b:setJoypadFocused(screen.joyfocus ~= nil and screen.promptIndex == i) end
    end
    panel.pzoptReadRoot = true
    self:addChild(panel)
    self.promptPanel = panel
    local bw = math.max(150, textW(UIFont.Small, "RESTART NOW") + 30)
    local bx, by = 16, h - 16 - self.btnH
    self.promptButtons = {}
    if not blocked then
        local restart = ISButton:new(bx, by, bw, self.btnH, "RESTART NOW", self, function() self:askRestart() end)
        restart:initialise()
        restart:instantiate()
        restart:enableAcceptColor()
        panel:addChild(restart)
        table.insert(self.promptButtons, restart)
        bx = bx + bw + 10
    end
    local later = ISButton:new(bx, by, bw, self.btnH, "LATER", self, function() self:hideRestartPrompt() end)
    later:initialise()
    later:instantiate()
    panel:addChild(later)
    table.insert(self.promptButtons, later)
    self.promptIndex = #self.promptButtons
    self:setPageShown(false)
    PzoptLogInfo("[pzopt] menu: restart prompt shown (" .. #pend .. " settings)")
    self:traceFocus()
end

function PzoptMainMenu:hideRestartPrompt()
    if self.promptPanel then
        self.promptPanel:setVisible(false)
        self:removeChild(self.promptPanel)
        self.promptPanel = nil
    end
    if not self.prompt then return end
    self.prompt = false
    self.promptButtons = nil
    self:setPageShown(true)
    self.side.rows = self:sideRows()
    self:traceFocus()
end

-- the page's list and detail hidden under the restart prompt and the help (only the backdrop shows under them)
function PzoptMainMenu:setPageShown(on)
    self.list:setVisible(on)
    for _, el in ipairs(self.detailEls or {}) do el:setVisible(on) end
end

-- why a restart cannot be offered here (nil when it can): a multiplayer client cannot save the world first; a harness
-- run's bench save restarts only when the run asks for it
function PzoptMainMenu:restartBlocked()
    local ms = MainScreen.instance
    if ms and ms.inGame then
        if isClient() then return "A multiplayer game cannot be saved from here: quit to the main menu first." end
        local ok, world = pcall(function() return getWorld():getWorld() end)
        if ok and world and string.find(tostring(world), "pzopt%-") and not PzoptMenuRestartRig then
            return "This is a test run's world: it restarts when the run asks for it."
        end
    end
    return nil
end

-- the rigs: a setting stepped as A on its row would (the readability tour's prompt stop, the restart rig)
function PzoptMainMenu:devStep(key, dir)
    local b = settingsTree().entry[key]
    settingStep(key, b and b.e, dir or 1)
    self:refreshPage()
end

function PzoptMainMenu:askRestart()
    if self:restartBlocked() then return end
    local n = #pendingRestart()
    self:confirm("Restart the game now? " .. n .. " setting" .. (n == 1 and " takes" or "s take") .. " effect when it starts again.",
        function() self:restartNow() end)
end

function PzoptMainMenu:restartNow()
    local pend = pendingRestart()
    if PzoptMenuRestartRig then pcall(PzoptMenuRestartRig, pend) end -- the harness's restart check (ui:restart)
    local ok, started = pcall(function() return perf():pzoptRestartGame() end)
    PzoptLogInfo("[pzopt] menu: restart for " .. #pend .. " settings (helper " .. tostring(ok and started) .. ")")
    if not ok or not started then print("[pzopt] menu: could not start the restart helper, quitting only") end
    -- the stock quit to desktop: in a game it saves the world first (as the pause menu's Quit to desktop does)
    MainScreen.instance:quitToDesktop()
end

-- Shows a node's page. focus: "side" (the cursor on its sidebar row), "master" (on the list), nil (leave it). `from`: the
-- row the player came up from, selected in the list.
function PzoptMainMenu:selectNode(id, focus, from)
    self.backTo[id] = nil -- reached another way than openPage's: B goes to its parent (openPage sets it again after)
    self.selected = id
    self.colour = colourOf(id)
    self.side.rows = self:sideRows()
    self.page = pageOf(self, id)
    self.list.items = self.page.items
    self.list.top = 1
    self.list.selected = nil
    self.list:setSelected(self.list:firstSelectable() or 1)
    if from then
        for i, it in ipairs(self.page.items) do if it.id == from then self.list:setSelected(i) end end
    end
    self.detailIndex = 1
    for i, r in ipairs(self.side.rows) do
        if r.id == id then self.sideIndex = i end
    end
    if focus == "side" then
        self.pane = "side"
    elseif focus == "master" then
        self.pane = self.list.selected and "master" or "side"
    end
    self:showDetail()
    self:traceFocus()
end

-- a page from an action or another file (Past results' Open / Compare, the dev screens, the report after a run): its
-- arguments, where B goes back to
function PzoptMainMenu:openPage(id, arg, from)
    local B = benchState(self)
    if id == "bench/report" and arg then B.rep = { run = arg[1] } end
    if id == "bench/compare" and arg then B.cmp = PzoptBench.compare(arg[1], arg[2]) end
    if id == "bench/setup" then B.error = nil end
    self:selectNode(id, "master")
    self.backTo[id] = from
    if id == "bench/setup" then print("[pzopt] benchmark: dev: setup panel shown") end
    if id == "bench/history" then print("[pzopt] benchmark: dev: past results shown") end
    return self
end

-- the static form (another file, the screen first when it is not up); a separate name: PzoptMainMenu.openPage is the
-- method above, and one name for both made the method call itself
function PzoptMainMenu.openAt(id, arg, from)
    local s = PzoptMainMenu.instance() or PzoptMainMenu.show()
    if not s then return nil end
    return s:openPage(id, arg, from)
end

function PzoptMainMenu:setOnlyDiff(on)
    local B = benchState(self)
    B.onlyDiff = on
    PzoptLogInfo("[pzopt] menu: only what differs " .. (on and "on" or "off"))
    if self.selected == "bench/compare" then self:refreshPage() end
end

-- a friend's share code beside this run (Compare a code)
function PzoptMainMenu:applyShareCode(code)
    local B = benchState(self)
    if not B.rep then return end
    B.rep.theirs = PzoptBench.decode(code)
    if self.selected == "bench/report" then self:refreshPage() end
    print("[pzopt] benchmark: dev: report compare shown (" .. tostring(B.rep.run) .. ")")
end

function PzoptMainMenu:askShareCode()
    local w, h = math.min(520, getCore():getScreenWidth() - 40), 140
    local box = ISTextBox:new((getCore():getScreenWidth() - w) / 2, (getCore():getScreenHeight() - h) / 2, w, h,
        "Paste a benchmark share code (PZOB1:...)", "", self, function(target, button)
            if button.internal == "OK" then target:applyShareCode(button.parent.entry:getText() or "") end
        end)
    box:initialise()
    box:addToUIManager()
    box:setAlwaysOnTop(true)
    box:bringToTop()
    local jd = mainJoypad()
    if jd then -- B leaves its text field, B again is Cancel, which hands the pad back here
        box.prevFocus = jd.focus
        jd.focus = box
        updateJoypadFocus(jd)
    end
end

-- the trace the controller rig reads (padTrace): row = the sidebar row, 100 + the list row, 200 + the detail control
function PzoptMainMenu:traceFocus()
    local el, row = nil, 0
    self.focusEl = nil
    if self.pane == "side" then
        local r = self.side.rows[self.sideIndex or 1]
        if (self.sideIndex or 1) > #self.side.rows then
            el, row = self.back, self.sideIndex
        else
            el = { name = r and r.label or "-" }
            row = self.sideIndex or 1
        end
    elseif self.pane == "master" then
        local it = self.list.items[self.list.selected or 0]
        el = { name = it and tostring(val(it.label)) or "-" }
        row = 100 + (self.list.selected or 0)
    elseif self.pane == "detail" then
        local f = self.focusables[self.detailIndex or 1]
        el = f and f.el
        self.focusEl = el
        row = 200 + (self.detailIndex or 1)
    end
    if self.prompt and self.promptButtons then
        el = self.promptButtons[self.promptIndex or 2]
        row = 300 + (self.promptIndex or 2)
    end
    local before = self.traceKey
    self.traceKey = tostring(self.pane) .. row
    self.joypadButtons, self.joypadIndex, self.joypadIndexY = { el }, 1, row
    self.back:setJoypadFocused(self.pane == "side" and el == self.back and self.joyfocus ~= nil)
    for i, f in ipairs(self.focusables or {}) do
        if f.button then f.el:setJoypadFocused(self.pane == "detail" and i == self.detailIndex and self.joyfocus ~= nil) end
    end
    if before ~= self.traceKey and self.joyfocus then getSoundManager():playUISound("UIHighlightMainMenuItem") end
end

function PzoptMainMenu:focusSide(i)
    local n = #self.side.rows
    i = math.max(1, math.min(i, n + 1))
    self.sideIndex = i
    self.pane = "side"
    if i <= n and self.side.rows[i].id ~= self.selected then
        self:selectNode(self.side.rows[i].id, "side")
        -- the rows below it change when it opens or closes its children: keep the cursor on the entry
        for k, r in ipairs(self.side.rows) do if r.id == self.selected then self.sideIndex = k end end
    end
    self.pane = "side"
    self:traceFocus()
end

-- the detail's focusable i; the first one that is not a danger button when the cursor arrives
function PzoptMainMenu:focusDetail(i, arriving)
    if #self.focusables == 0 then return false end
    i = math.max(1, math.min(i, #self.focusables))
    if arriving then
        while self.focusables[i] and self.focusables[i].button and self.focusables[i].el.pzoptAction.danger do i = i + 1 end
        if not self.focusables[i] then i = 1 end
    end
    self.detailIndex = i
    self.pane = "detail"
    self:traceFocus()
    return true
end

-- A on a list row: a node opens (its page, the cursor on its list), a switch / tick / setting changes, a row with nothing
-- of its own to do moves the cursor into its detail
function PzoptMainMenu:activateItem(i)
    local it = self.list.items[i]
    if not selectable(it) then return end
    self.list:setSelected(i)
    if it.kind == "node" then
        getSoundManager():playUISound("UIActivateMainMenuItem")
        self:selectNode(it.id, "master")
    elseif it.activate then
        getSoundManager():playUISound("UIActivateMainMenuItem")
        it.activate()
        self:refreshPage()
    else
        self:showDetail()
        self:focusDetail(1, true)
    end
end

-- B / up a level: the page the player came from (Past results for a report it opened), else the parent's page with the
-- cursor on the entry the player came from; at the top the menu closes
function PzoptMainMenu:goUp()
    local back = self.backTo[self.selected]
    if back then
        local from = self.selected
        self.backTo[from] = nil
        self:selectNode(back, "master")
        getSoundManager():playUISound("UIActivateMainMenuItem")
        return
    end
    local parent = parentOf(self.selected)
    if not parent then
        self:close("B at the top")
        return
    end
    if self.selected == "search" and self.search then -- leaving the results empties the box
        self.search:setText("")
        self.searchTyped, self.query, SEARCH.query = "", "", ""
    end
    local from = self.selected
    if self.pane == "side" and parent ~= "home" then
        self:selectNode(parent, "side")
    elseif self.pane == "side" then
        self:close("B in the sidebar")
        return
    else
        self:selectNode(parent, "master", from)
    end
    getSoundManager():playUISound("UIActivateMainMenuItem")
end

function PzoptMainMenu:moveList(dir)
    local i = (self.list.selected or 0) + dir
    while self.list.items[i] and not selectable(self.list.items[i]) do i = i + dir end
    if self.list.items[i] then
        self.list:setSelected(i, dir)
        self:showDetail()
    elseif dir < 0 then
        self.list.top = 1 -- the headings above the first row into view
    end
    self:traceFocus()
end

-- the detail's focus up / down: the next control, or the button row above / below
function PzoptMainMenu:moveDetail(dir)
    local f = self.focusables[self.detailIndex or 1]
    if not f then return end
    local i = self.detailIndex
    if f.button then
        local row = f.el.pzoptRow
        local j = i + dir
        while self.focusables[j] and self.focusables[j].button and self.focusables[j].el.pzoptRow == row do j = j + dir end
        if self.focusables[j] then self:focusDetail(j) end
    else
        if self.focusables[i + dir] then self:focusDetail(i + dir) end
    end
    if dir > 0 and i == self.detailIndex and self.detailText then -- at the end: the text scrolls
        local maxScroll = math.max(0, self.detailText:getScrollHeight() - self.detailText:getHeight())
        self.detailText:setYScroll(math.max(-maxScroll, self.detailText:getYScroll() - fontH(UIFont.Small) * 3))
    end
end

function PzoptMainMenu:onJoypadDirUp(joypadData)
    if self.prompt then return end
    if self.pane == "side" then
        self:focusSide((self.sideIndex or 1) - 1)
    elseif self.pane == "master" then
        self:moveList(-1)
    elseif self.pane == "detail" then
        self:moveDetail(-1)
    end
end

function PzoptMainMenu:onJoypadDirDown(joypadData)
    if self.prompt then return end
    if self.pane == "side" then
        self:focusSide((self.sideIndex or 1) + 1)
    elseif self.pane == "master" then
        self:moveList(1)
    elseif self.pane == "detail" then
        self:moveDetail(1)
    end
end

function PzoptMainMenu:onJoypadDirLeft(joypadData)
    if self.prompt then self.promptIndex = 1 self:traceFocus() return end
    if self.pane == "detail" then
        local f = self.focusables[self.detailIndex or 1]
        if f and f.el.spec and (f.el.spec.type == "chooser" or f.el.spec.type == "number") then
            f.el.spec.set(-1)
            self:refreshPage()
            return
        end
        if f and f.button and self.focusables[self.detailIndex - 1] and self.focusables[self.detailIndex - 1].button
                and self.focusables[self.detailIndex - 1].el.pzoptRow == f.el.pzoptRow then
            self:focusDetail(self.detailIndex - 1)
        else
            self.pane = self.list.selected and "master" or "side"
            self:traceFocus()
        end
    elseif self.pane == "master" then
        local at = nil
        for i, r in ipairs(self.side.rows) do if r.id == self.selected then at = i end end
        if at then
            self.sideIndex = at
            self.pane = "side"
            self:traceFocus()
        else
            self:focusSide(1) -- home has no row: the first entry (and its page)
        end
    end
end

function PzoptMainMenu:onJoypadDirRight(joypadData)
    if self.prompt then self.promptIndex = #(self.promptButtons or {}) self:traceFocus() return end
    if self.pane == "side" then
        if (self.sideIndex or 1) <= #self.side.rows and self.list.selected then
            self.pane = "master"
            self:traceFocus()
        end
    elseif self.pane == "master" then
        self:focusDetail(1, true)
    elseif self.pane == "detail" then
        local f = self.focusables[self.detailIndex or 1]
        if f and f.el.spec and (f.el.spec.type == "chooser" or f.el.spec.type == "number") then
            f.el.spec.set(1)
            self:refreshPage()
        elseif f and f.button and self.focusables[self.detailIndex + 1] and self.focusables[self.detailIndex + 1].button
                and self.focusables[self.detailIndex + 1].el.pzoptRow == f.el.pzoptRow then
            self:focusDetail(self.detailIndex + 1)
        end
    end
end

-- an action with a confirm asks first (Yes / No, A / B on a controller); then the page follows the change
function PzoptMainMenu:runAction(a)
    if a.confirm then
        self:confirm(val(a.confirm), function()
            a.act()
            if self.parent then self:refreshPage() end
        end)
        return
    end
    a.act()
    self:refreshPage()
end

function PzoptMainMenu:confirm(text, fn)
    local w = math.min(560, getCore():getScreenWidth() - 40)
    local lines = 1
    for _ in string.gmatch(getTextManager():WrapText(UIFont.Small, text, w - 40), "\n") do lines = lines + 1 end
    local h = math.min(getCore():getScreenHeight() - 40, math.max(160, 100 + lines * fontH(UIFont.Small)))
    local modal = ISModalDialog:new((getCore():getScreenWidth() - w) / 2, (getCore():getScreenHeight() - h) / 2, w, h, text, true, self,
        function(target, button) if button.internal == "YES" then fn() end end)
    modal:initialise()
    modal:addToUIManager()
    modal:setAlwaysOnTop(true)
    modal:bringToTop()
    self.confirmModal = modal -- (the uninstall rig presses its Yes)
    local jd = mainJoypad()
    if jd then -- A = Yes, B = No; the pad comes back here after either
        modal.prevFocus = jd.focus
        jd.focus = modal
        updateJoypadFocus(jd)
    end
    PzoptLogInfo("[pzopt] menu: confirm asked: " .. text)
end

-- X / Y: the selected row's second / main action (the list or the detail), else the page's
function PzoptMainMenu:keyAction(key)
    for _, a in ipairs(self.detailActions or {}) do
        if a.key == key then return a end
    end
    local p = self.page or {}
    return p[key]
end

local TOP_ORDER = { "home", "perf", "vis", "tools", "bench", "update", "compat" }

-- LB / RB: the previous / next section of the sidebar (home first), from anywhere in it
function PzoptMainMenu:cycleSection(dir)
    local cur = topOf(self.selected) or "home"
    local at = 1
    for i, id in ipairs(TOP_ORDER) do if id == cur then at = i end end
    at = ((at - 1 + dir) % #TOP_ORDER) + 1
    getSoundManager():playUISound("UIActivateTab")
    self:selectNode(TOP_ORDER[at], self.pane == "side" and TOP_ORDER[at] ~= "home" and "side" or "master")
end

-- LT / RT: a page of the focused list or text up / down (the sidebar: five rows)
function PzoptMainMenu:pageMove(dir)
    if self.pane == "master" then
        local n = self.list:visibleCount()
        local i = math.max(1, math.min(#self.list.items, (self.list.selected or 1) + dir * n))
        self.list:setSelected(i, dir)
        self:showDetail()
    elseif self.pane == "detail" then
        self:scrollDetail(dir * (self.detailText and self.detailText:getHeight() * 0.8 or 100))
    else
        self:focusSide((self.sideIndex or 1) + dir * 5)
    end
    self:traceFocus()
end

function PzoptMainMenu:scrollDetail(px)
    local t = self.detailText
    if not t then return end
    local maxScroll = math.max(0, t:getScrollHeight() - t:getHeight())
    t:setYScroll(math.max(-maxScroll, math.min(0, t:getYScroll() - px)))
end

function PzoptMainMenu:onJoypadDown(button, joypadData)
    self.padInputAt = getTimestampMs()
    if self.prompt then -- the restart prompt: left / right between its buttons, A presses, B = Later, Y / Start = Restart now
        if button == Joypad.AButton then
            local b = self.promptButtons[self.promptIndex or 2]
            if b then b:forceClick() end
        elseif button == Joypad.BButton then
            self:hideRestartPrompt()
        elseif button == Joypad.YButton or button == Joypad.Start then
            self:askRestart()
        end
        return
    end
    if self.help then -- the help over the page: B or R3 closes it, the rest waits
        if button == Joypad.BButton or button == Joypad.RStickButton then self:setHelp(false) end
        return
    end
    if button == Joypad.BButton then
        if self.pane == "detail" then
            self.pane = self.list.selected and "master" or "side"
            self:traceFocus()
        else
            self:goUp()
        end
    elseif button == Joypad.AButton then
        if self.pane == "side" then
            if (self.sideIndex or 1) > #self.side.rows then
                self:close("A on BACK")
            elseif self.list.selected then
                self.pane = "master"
                self:traceFocus()
            end
        elseif self.pane == "master" then
            if self.list.selected then self:activateItem(self.list.selected) end
        elseif self.pane == "detail" then
            local f = self.focusables[self.detailIndex or 1]
            if not f then return end
            if f.button then
                f.el:forceClick()
            elseif f.el.spec.type == "entry" then
                if f.el.entry then f.el.entry:focus() end
            else
                f.el.spec.set(1)
                getSoundManager():playUISound("UIActivateMainMenuItem")
                self:refreshPage()
            end
        end
    elseif button == Joypad.XButton or button == Joypad.YButton then
        local a = self:keyAction(button == Joypad.XButton and "x" or "y")
        if a then
            getSoundManager():playUISound("UIActivateMainMenuItem")
            self:runAction(a)
        end
    elseif button == Joypad.LBumper or button == Joypad.RBumper then
        self:cycleSection(button == Joypad.RBumper and 1 or -1)
    elseif button == Joypad.Back then -- the focus between the sidebar and the page
        if self.pane == "side" then
            if self.list.selected then self.pane = "master" end
        else
            for i, r in ipairs(self.side.rows) do if r.id == self.selected then self.sideIndex = i end end
            self.pane = "side"
        end
        self:traceFocus()
    elseif button == Joypad.Start then -- everything is saved as it changes: Start closes the menu, or restarts for the waiting ones
        if #pendingRestart() > 0 then self:askRestart() else self:close("Start") end
    elseif button == Joypad.LStickButton or button == Joypad.RStickButton then
        -- L3 (search) and R3 (the help) act a moment after the press, unless the other stick is pressed too: L3 + R3
        -- together is the performance overlay's chord (pzopt.Overlay), which keeps working here
        local now = getTimestampMs()
        local p = self.stickPress
        if p and p.b ~= button and now - p.t < 350 then
            self.stickPress = nil
        else
            self.stickPress = { b = button, t = now }
        end
    end
end

-- Once a frame while the screen holds the controller: the triggers (a page), the left stick (the D-pad's moves, repeating
-- while held), the right stick (the detail's text scrolls)
function PzoptMainMenu:setHelp(on)
    self.help = on
    self:setPageShown(not on and not self.prompt)
    self:traceFocus()
end

function PzoptMainMenu:pollPad()
    local p = self.stickPress
    if p and getTimestampMs() - p.t >= 350 then
        self.stickPress = nil
        if p.b == Joypad.LStickButton then self:focusSearch() else self:setHelp(true) end
    end
    local jd = self.joyfocus
    if not jd or not jd.id or self.help then return end
    local id = jd.id
    local okT, lt, rt = pcall(function() return isJoypadLTPressed(id), isJoypadRTPressed(id) end)
    if okT then
        if lt or rt then self.padInputAt = getTimestampMs() end
        if lt and not self.ltDown then self:pageMove(-1) end
        if rt and not self.rtDown then self:pageMove(1) end
        self.ltDown, self.rtDown = lt, rt
    end
    local okM, mx, my = pcall(function() return getJoypadMovementAxisX(id), getJoypadMovementAxisY(id) end)
    if okM then
        local dir = (my < -0.6 and "up") or (my > 0.6 and "down") or (mx < -0.6 and "left") or (mx > 0.6 and "right") or nil
        local now = getTimestampMs()
        if dir and (dir ~= self.stickDir or now >= (self.stickNext or 0)) then
            self.stickNext = now + (dir ~= self.stickDir and 400 or 130)
            if dir == "up" then self:onJoypadDirUp(jd) elseif dir == "down" then self:onJoypadDirDown(jd)
            elseif dir == "left" then self:onJoypadDirLeft(jd) else self:onJoypadDirRight(jd) end
        end
        self.stickDir = dir
    end
    local okA, ry = pcall(function() return getJoypadAimingAxisY(id) end)
    if okA and math.abs(ry) > 0.3 then
        self:scrollDetail(ry * 14)
        self:traceFocus()
    end
end

-- The button hints of the focused control: glyph + word, the most important first, as many as fit the bar
function PzoptMainMenu:hints()
    local T = Joypad.Texture
    local BT = Joypad.ButtonTextures
    local out = {}
    local function add(tex, label) if tex and label then table.insert(out, { tex, label }) end end
    if self.help then
        add(T.BButton, "Close help")
        return out
    end
    if self.prompt then
        -- A presses the button under the cursor; Y and the D-pad only while RESTART NOW is offered (restartBlocked: LATER only)
        local bs = self.promptButtons or {}
        local b = bs[self.promptIndex or #bs]
        add(T.AButton, b and (b.title == "LATER" and "Later" or "Restart now") or nil)
        add(T.BButton, "Later")
        if #bs > 1 then
            add(T.YButton, "Restart now")
            add(BT[Joypad.DPadLeft], "Choose")
        end
        return out
    end
    local it = self.list.items[self.list.selected or 0]
    if self.pane == "side" then
        add(T.AButton, "Open")
        add(T.BButton, parentOf(self.selected) == "home" and "Close" or "Up")
        add(BT[Joypad.DPadDown], "Move")
    elseif self.pane == "master" then
        local a = "Details"
        if it and it.kind == "node" then a = "Open"
        elseif it and it.activate then a = (it.kind == "setting" and it.controls and it.controls[1].type == "chooser") and "Next value" or "Toggle" end
        add(T.AButton, a)
        add(T.BButton, parentOf(self.selected) and "Back" or "Close")
    else
        local f = self.focusables[self.detailIndex or 1]
        if f and f.button then add(T.AButton, tostring(f.el.title)) end
        if f and f.el.spec and (f.el.spec.type == "chooser" or f.el.spec.type == "number") then add(BT[Joypad.DPadLeft], "Change") end
        if f and f.el.spec and f.el.spec.type == "colour" then add(T.AButton, "Pick a colour") end
        if f and f.el.spec and f.el.spec.type == "tick" then add(T.AButton, "Toggle") end
        add(T.BButton, "List")
    end
    local x, y = self:keyAction("x"), self:keyAction("y")
    if x then add(T.XButton, tostring(val(x.label))) end
    if y then add(T.YButton, tostring(val(y.label))) end
    add(T.LBumper, "Section")
    add(T.RBumper, nil)
    add(T.LTrigger, "Page")
    add(T.RStick, "Scroll")
    add(BT[Joypad.Back], self.pane == "side" and "Page" or "Sidebar")
    local nPend = #pendingRestart()
    add(BT[Joypad.Start], nPend > 0 and ("Restart (" .. nPend .. ")") or "Close")
    add(BT[Joypad.LStickButton], "Search")
    add(BT[Joypad.RStickButton], "Help")
    return out
end

local HELP_LINES = {
    { "D-pad, left stick", "move (the stick repeats while held)" },
    { "A", "open, toggle, press" },
    { "B", "back one level: detail, list, sidebar, close" },
    { "X", "the row's second action (Default, Compare, Only what differs, Copy share code...)" },
    { "Y", "the row's main action (Apply a preset, Start benchmark, Open, Update now...), asks first when it restarts, installs or changes many settings" },
    { "LB / RB", "previous / next section of the sidebar" },
    { "LT / RT", "a page up / down in the list or the text" },
    { "Right stick", "scroll the detail's text" },
    { "View (Back)", "the focus between the sidebar and the page" },
    { "Menu (Start)", "close the menu (every change is saved as you make it)" },
    { "L3", "search every setting (the on-screen keyboard)" },
    { "R3", "this help" },
}

function PzoptMainMenu:render()
    ISPanelJoypad.render(self)
    local pad = self.joyfocus ~= nil or self.forceHints
    local rem = self:reminder()
    if pad or rem then
        -- the bar: the restart reminder at its right end, the controller's hints in their order in what is left
        local x0, y0, w = self.contentX, self.hintY, self.contentW
        local g = self.hintH - 8
        -- (its background is drawn in prerender, under the RESTART NOW button)
        local ty = y0 + math.floor((self.hintH - fontH(UIFont.Small)) / 2)
        local limit = x0 + w - 8
        if rem then
            self:drawText(rem.text, rem.x, ty, 1, 0.85, 0.45, 1, UIFont.Small)
            limit = rem.x - 16
        end
        local x = x0 + 8
        for _, h in ipairs(pad and self:hints() or {}) do
            local need = g + 6 + textW(UIFont.Small, h[2]) + 18
            if x + need > limit then break end
            self:drawTextureScaled(h[1], x, y0 + 4, g, g, 1, 1, 1, 1)
            self:drawText(h[2], x + g + 6, ty, 0.85, 0.85, 0.88, 1, UIFont.Small)
            x = x + need
        end
    end
    if self.help then
        local hS = fontH(UIFont.Small)
        local w = math.min(self.contentW - 40, math.max(420, hS * 40))
        local keyW = 0
        for _, l in ipairs(HELP_LINES) do keyW = math.max(keyW, textW(UIFont.Small, l[1])) end
        local lines = {}
        for _, l in ipairs(HELP_LINES) do
            local ws = wrap(l[2], UIFont.Small, w - keyW - 48)
            table.insert(lines, { l[1], ws })
        end
        local h = 20 + fontH(UIFont.Medium) + 10
        for _, l in ipairs(lines) do h = h + #l[2] * hS + 4 end
        h = math.min(h, self.pageH)
        local x0 = self.contentX + math.floor((self.contentW - w) / 2)
        local y0 = self.pageY + math.floor((self.pageH - h) / 2)
        self:drawRect(x0, y0, w, h, 0.97, SIDE_BG.r, SIDE_BG.g, SIDE_BG.b)
        self:drawRectBorder(x0, y0, w, h, 1, 1, 1, 1)
        self:drawText("Controller", x0 + 16, y0 + 10, 1, 1, 1, 1, UIFont.Medium)
        local y = y0 + 20 + fontH(UIFont.Medium)
        for _, l in ipairs(lines) do
            if y + #l[2] * hS > y0 + h then break end
            self:drawText(l[1], x0 + 16, y, C_GREY.r, C_GREY.g, C_GREY.b, 1, UIFont.Small)
            for k, line in ipairs(l[2]) do self:drawText(line, x0 + 32 + keyW, y + (k - 1) * hS, 0.9, 0.9, 0.92, 1, UIFont.Small) end
            y = y + #l[2] * hS + 4
        end
    end
end

function PzoptMainMenu:onGainJoypadFocus(joypadData)
    ISPanelJoypad.onGainJoypadFocus(self, joypadData)
    self:traceFocus()
end

function PzoptMainMenu:onLoseJoypadFocus(joypadData)
    ISPanelJoypad.onLoseJoypadFocus(self, joypadData)
    self.back:setJoypadFocused(false)
    for _, f in ipairs(self.focusables or {}) do if f.button then f.el:setJoypadFocused(false) end end
end

-- the breadcrumbs: "PZ Optimization  >  Enhancements  >  Lighting and shadows", each part a link up to that level
function PzoptMainMenu:drawCrumbs()
    local path = pathOf(self.selected)
    local x, y = self.contentX, self.crumbY
    local reserve = 0
    local sep = "  >  "
    local sepW = textW(UIFont.Medium, sep)
    local total = 0
    for i, id in ipairs(path) do total = total + textW(UIFont.Medium, titleOf(id)) + (i > 1 and sepW or 0) end
    local first = 1
    while total > self.contentW - reserve and first < #path do -- a long path drops its first parts ("...  >")
        total = total - textW(UIFont.Medium, titleOf(path[first])) - sepW
        first = first + 1
    end
    self.crumbs = {}
    if first > 1 then
        self:drawText("...", x, y, C_DIM.r, C_DIM.g, C_DIM.b, 1, UIFont.Medium)
        x = x + textW(UIFont.Medium, "...")
    end
    local mx, my = self:getMouseX(), self:getMouseY()
    for i = first, #path do
        local id = path[i]
        if i > first or first > 1 then
            self:drawText(sep, x, y, C_DIM.r, C_DIM.g, C_DIM.b, 1, UIFont.Medium)
            x = x + sepW
        end
        local label = titleOf(id)
        local w = textW(UIFont.Medium, label)
        local last = i == #path
        local hot = not last and mx >= x and mx < x + w and my >= y and my < y + fontH(UIFont.Medium)
        local c = last and C_TEXT or (hot and C_TEXT or C_GREY)
        self:drawText(label, x, y, c.r, c.g, c.b, 1, UIFont.Medium)
        if not last then table.insert(self.crumbs, { id = id, x = x, w = w }) end
        x = x + w
    end
end

function PzoptMainMenu:onMouseUp(x, y)
    if y >= self.crumbY and y < self.crumbY + fontH(UIFont.Medium) then
        for _, c in ipairs(self.crumbs) do
            if x >= c.x and x < c.x + c.w then
                getSoundManager():playUISound("UIActivateMainMenuItem")
                self:selectNode(c.id, "master")
                return true
            end
        end
    end
    return ISPanelJoypad.onMouseUp(self, x, y)
end

function PzoptMainMenu:prerender()
    ISPanelJoypad.prerender(self)
    self:drawCrumbs()
    if self.joyfocus ~= nil or self.forceHints or (#pendingRestart() > 0 and not self.prompt) then -- the bar under the page
        self:drawRect(self.contentX, self.hintY, self.contentW, self.hintH, 0.85, SIDE_BG.r, SIDE_BG.g, SIDE_BG.b)
        self:drawRectBorder(self.contentX, self.hintY, self.contentW, self.hintH, 1, SIDE_BORDER.r, SIDE_BORDER.g, SIDE_BORDER.b)
    end
    self:pollPad()
    self:pollSearch()
    -- a value shown in the list, the sidebar or the detail can change under it (the updater, a download): once a second
    local now = getTimestampMs()
    if now >= (self.nextRefresh or 0) then
        self.nextRefresh = now + 1000
        self.side.rows = self:sideRows()
        local it = self.list.items[self.list.selected or 0]
        local d = it and it.desc and it.desc() or nil
        if d and d ~= self.lastDesc and self.pane ~= "detail" then
            local scroll = self.detailText and self.detailText:getYScroll() or 0
            self:showDetail()
            if self.detailText then self.detailText:setYScroll(scroll) end
        end
    end
end

-- the readability rig and the harness: show a page
function PzoptMainMenu:activate(id)
    if id == "benchmark" then id = "bench/setup" end
    self:selectNode(id, "master")
end

function PzoptMainMenu:select(id)
    self:selectNode(id, "master")
end

local function joypadRowOf(rows, element)
    if not element or not rows then return nil end
    for i, row in ipairs(rows) do
        if row[1] == element then return i end
    end
    return nil
end

local syncJoypadRow

-- B at the top / BACK / Escape: the main-menu column again, the controller's cursor on the PZ OPTIMIZATION item
function PzoptMainMenu:close(why)
    local ms = MainScreen.instance
    if self.colourPicker then self.colourPicker:removeSelf() self.colourPicker = nil end
    pcall(function() getPerformance():releasePzoptGifs() end) -- the clips' textures (decoded again on the next visit)
    self:setVisible(false)
    if self.parent then self.parent:removeChild(self) end
    if ms then
        if ms.pzoptMenuScreen == self then ms.pzoptMenuScreen = nil end
        if ms.bottomPanel then ms.bottomPanel:setVisible(true) end
    end
    local jd = self.joyfocus or mainJoypad()
    if jd and ms and (jd.focus == self or jd.focus == nil) then
        jd.focus = ms
        updateJoypadFocus(jd)
        pcall(syncJoypadRow, ms)
        if ms.pzoptMenuOption and ms:setJoypadFocus(ms.pzoptMenuOption, jd) then
            ms:updateBottomPanelButtons()
        end
    end
    PzoptLogInfo("[pzopt] menu: screen closed (" .. tostring(why or "-") .. ")")
end

-- restore = { node, pane, list row, detail control } to come back where the player was (after Options, a resize)
function PzoptMainMenu.show(restore)
    local ms = MainScreen.instance
    if not ms then return nil end
    local sw, sh = getCore():getScreenWidth(), getCore():getScreenHeight()
    local s = ms.pzoptMenuScreen
    local bench, backTo = nil, nil
    if s and (s.width ~= sw or s.height ~= sh) then
        restore = restore or { s.selected, s.pane, s.list.selected }
        bench, backTo = s.bench, s.backTo
        s:setVisible(false)
        ms:removeChild(s)
        s = nil
    end
    if not s then
        s = PzoptMainMenu:new(0, 0, sw, sh)
        s.bench = bench
        s.backTo = backTo or {}
        s:initialise()
        ms:addChild(s)
        ms.pzoptMenuScreen = s
    end
    s:setVisible(true)
    s:bringToTop()
    if ms.bottomPanel then ms.bottomPanel:setVisible(false) end
    if restore then
        s:selectNode(restore[1], restore[2] == "side" and "side" or "master")
        if restore[3] and restore[2] ~= "side" then s.list:setSelected(restore[3]) s:showDetail() end
        if restore[2] == "detail" then s:focusDetail(restore[4] or 1) end
        s:traceFocus()
    end
    local jd = mainJoypad()
    if jd then
        jd.focus = s
        updateJoypadFocus(jd)
    end
    return s
end

function PzoptMainMenu.instance()
    local ms = MainScreen.instance
    return ms and ms.pzoptMenuScreen
end

-- Once per frame while the screen exists: the column stays hidden under it, it comes back when Options closes, and the
-- controller goes back to it when a box it opened (the share code) gave the focus away.
local function screenTick(ms)
    local s = ms.pzoptMenuScreen
    if not s then return end
    if not s:isVisible() then return end
    if ms.bottomPanel and ms.bottomPanel:isVisible() then ms.bottomPanel:setVisible(false) end
    if UIManager.isModalVisible() then return end
    for _, cls in ipairs({ PzoptUpdateDialog, PzoptCompatDialog }) do
        if cls and cls.instance and cls.instance:isVisible() then return end
    end
    local jd = mainJoypad()
    if jd and jd.focus ~= s then
        local f = jd.focus
        local lost = f == nil or f == ms
        if not lost then
            local ok, vis = pcall(function() return f:isReallyVisible() end)
            lost = not ok or not vis
        end
        if lost then
            jd.focus = s
            updateJoypadFocus(jd)
        end
    end
end

-- ------------------------------------------------------------------ the main-menu item

local function labelHeight()
    return FONT_LARGE + 8 * 2
end

local function onItemClick(item, x, y)
    local ms = MainScreen.instance
    if not ms or (ms.delay or 0) > 0 or ms.tutorialButton or ms.checkSavefileModal then return end
    getSoundManager():playUISound("UIActivateMainMenuItem")
    local s = PzoptMainMenu.show()
    -- an offered update (the item says so): the screen opens on the UPDATE page, not on home
    if s and updateOffered(updState()) then s:selectNode("update", "master") end
    PzoptLogInfo("[pzopt] menu: screen opened on " .. tostring(s and s.selected))
end

-- The stock hover fade over the stock row height; the note line under it in the small font (the label is taller than the
-- stock ones by that line; fitMenuColumn keeps room for it).
local function itemPrerender(self)
    local fullHgt = self.height
    self.height = self.pzoptRowHgt
    MainScreen.prerenderBottomPanelLabel(self)
    self.height = fullHgt
    local note, news = itemNote()
    if news then
        self:drawText(note, 0, self.pzoptRowHgt - 6, 0.6, 1, 0.6, 1, UIFont.Small)
    else
        self:drawText(note, 0, self.pzoptRowHgt - 6, 0.7, 0.7, 0.7, 1, UIFont.Small)
    end
end

-- Right below Options, in the main menu and in the pause menu (the performance overlay's item that sat there moved into
-- the screen's Profiler page, 2026-10-18).
local function addItem(self)
    if not self.optionsOption or not self.bottomPanel or self.pzoptMenuOption then return end -- the main and the pause menu
    local above = self.optionsOption
    local rowHgt = labelHeight()
    local labelHgt = rowHgt + FONT_SMALL -- the note line starts 6 px above the stock row's bottom
    local y = above:getBottom()
    for _, child in pairs(self.bottomPanel:getChildren()) do
        if child.Type == "ISLabel" and child ~= above and child ~= self.optionsOption and child:getY() >= y then
            child:setY(child:getY() + labelHgt)
        end
    end
    local label = ISLabel:new(self.optionsOption:getX(), y, labelHgt, ITEM_TEXT, 1, 1, 1, 1, UIFont.Large, true)
    label.internal = "PZOPT_MENU"
    label:initialise()
    label.onMouseDown = onItemClick
    label.fade = UITransition.new()
    label.fade:setFadeIn(false)
    label.prerender = itemPrerender
    label.pzoptRowHgt = rowHgt
    label:setVisible(self.optionsOption:isVisible())
    self.bottomPanel:setHeight(self.bottomPanel:getHeight() + labelHgt)
    self.maxMenuItemWidth = math.max(self.maxMenuItemWidth or 0, textW(UIFont.Large, ITEM_TEXT), textW(UIFont.Large, ITEM_UPDATE_TEXT),
        textW(UIFont.Small, "Restart to finish the update"))
    label:setWidth(math.max(self.bottomPanel:getWidth(), self.maxMenuItemWidth))
    self.bottomPanel:addChild(label)
    self.pzoptMenuOption = label
    pcall(function() perf():pzoptUpdateCheck() end) -- the once-per-boot release check (pzopt.Updater)
    PzoptLogInfo("[pzopt] menu: main menu item added")
end

-- Controller. The D-pad walks self.joypadButtonsY, which the stock MainScreen:onGainJoypadFocus rebuilds from its own
-- list of labels, and A goes through onMenuItemMouseDownMainMenu, which only knows the stock `internal` names. The item
-- gets its own row after the overlay item's (or Options') while it is visible; runs after every stock rebuild and once
-- per frame while the menu holds the focus.
syncJoypadRow = function(self)
    local label = self.pzoptMenuOption
    local rows = self.joypadButtonsY
    if not label or not rows then return end
    local at = joypadRowOf(rows, label)
    local want = label:isVisible()
    if want and not at then
        local pos = joypadRowOf(rows, self.optionsOption)
        if not pos then return end -- Options not in the list yet (hidden): wait for it
        pos = pos + 1
        table.insert(rows, pos, { label })
        if (self.joypadIndexY or 0) >= pos then self.joypadIndexY = self.joypadIndexY + 1 end
    elseif at and not want then
        table.remove(rows, at)
        label:setJoypadFocused(false)
        if self.joypadIndexY == at then
            local row = rows[math.min(at, #rows)]
            if row then
                self:setJoypadFocus(row[1], self.joyfocus)
                self:updateBottomPanelButtons()
            end
        elseif (self.joypadIndexY or 0) > at then
            self.joypadIndexY = self.joypadIndexY - 1
        end
    end
end

local function syncItem(self)
    local label = self.pzoptMenuOption
    label:setVisible(self.optionsOption:isVisible())
    local text = itemText() -- the updater's check finishing while the menu shows: the label follows at once
    if label.name ~= text then
        label:setNameWithoutMoving(text)
        label:setWidth(self.maxMenuItemWidth or label:getWidth())
        PzoptLogInfo("[pzopt] menu: main menu item now " .. text)
    end
    if self.joyfocus then syncJoypadRow(self) end
end

-- The main menu column fits the window. The stock column plus our rows was taller than a 1280 x 720 window with the
-- large UI font, and the stock layout, which gives the logo what the column leaves, then put Continue and Load above the
-- screen. Rows (and the gaps between groups) are packed closer, never below the font's own height, until the column fits
-- between the stock margins; from the rows' original places, so a later resize undoes it.
local function fitMenuColumn(self)
    local bp = self.bottomPanel
    if not bp or self.inGame then return end
    local sw, sh = getCore():getScreenWidth(), getCore():getScreenHeight()
    local labels, total0 = {}, 0
    for _, c in pairs(bp:getChildren()) do
        if c.Type == "ISLabel" then
            if c.pzoptFitY == nil or c:getY() ~= c.pzoptFitY then -- first sight, or someone else moved it: its own place
                c.pzoptY0, c.pzoptH0, c.pzoptRow0 = c:getY(), c:getHeight(), c.pzoptRowHgt
            end
            table.insert(labels, c)
            total0 = math.max(total0, c.pzoptY0 + c.pzoptH0)
        end
    end
    if total0 <= 0 then return end
    local padding = 50 * (sw / 1920)
    local f = math.min(1, (sh - 2 * padding) / total0)
    if f == bp.pzoptFitF and sh == bp.pzoptFitH then return end
    bp.pzoptFitF, bp.pzoptFitH = f, sh
    local minH = FONT_LARGE + 2
    local bottom = 0
    table.sort(labels, function(a, b) return a.pzoptY0 < b.pzoptY0 end)
    for _, c in ipairs(labels) do
        local y, h = math.floor(c.pzoptY0 * f), math.max(math.min(c.pzoptH0, minH), math.floor(c.pzoptH0 * f))
        if c.pzoptRow0 then
            -- the item with a note line draws it 6 px above its row's bottom: the row keeps room for the title above it
            c.pzoptRowHgt = math.min(c.pzoptRow0, math.max(FONT_LARGE + 12, math.floor(c.pzoptRow0 * f)))
            h = math.max(h, c.pzoptRowHgt - 6 + FONT_SMALL + 2)
        end
        y = math.max(y, bottom) -- never on top of the row above (the item with a note keeps more than its share)
        c:setY(y)
        c:setHeight(h)
        c.pzoptFitY = y
        bottom = math.max(bottom, y + h)
    end
    bp:setHeight(bottom)
end

-- Escape at the main menu: the open pzopt dialog first (they never get key events of their own: UIManager sends those
-- only to elements that ask for them), then the screen; the stock screens keep theirs.
local function onEscape(ms)
    for _, cls in ipairs({ PzoptUpdateDialog, PzoptCompatDialog }) do -- the dialogs of the rigs (devUpdateDrive, compat_check)
        local d = cls and cls.instance
        if d and d:isVisible() then
            if d.onKeyRelease then d:onKeyRelease(Keyboard.KEY_ESCAPE) else d:close() end
            return true
        end
    end
    local s = ms.pzoptMenuScreen
    if s and s:isVisible() and s.search and s.search:isFocused() then
        s.search:unfocus()
        return true
    end
    if s and s:isVisible() then
        -- an Escape right after a controller press (a trigger: Steam's desktop controller layout can send it as a key) is
        -- that press, not the keyboard's: the controller's own button decides
        if s.joyfocus and getTimestampMs() - (s.padInputAt or 0) < 400 then
            PzoptLogInfo("[pzopt] menu: Escape right after a controller press ignored")
            return true
        end
        s:close("Escape")
        return true
    end
    return false
end

-- devUninstallDrive (dev rig, Config key; harness/uninstall-e2e.sh): once the main menu is up, the real controls in order:
-- the PZ OPTIMIZATION screen, its home page's Uninstall PZ Optimization... row, its UNINSTALL button, Yes. Each step is
-- logged ("[pzopt-e2e] ...") and held a few seconds for the script's screenshot; Yes starts pzopt.Uninstall and quits.
local uninstallDrive = { step = 0, at = 0 }
local function uninstallDriveTick()
    local d = uninstallDrive
    if d.step < 0 then return end
    if d.step == 0 then
        local ok, v = pcall(function() return getPerformance():getPzoptOption("devUninstallDrive") end)
        if not ok or v ~= "true" then d.step = -1 return end
        d.step, d.at = 1, getTimestampMs() + 4000
        return
    end
    if getTimestampMs() < d.at then return end
    local ms = MainScreen.instance
    if not ms or ms.inGame or not ms.pzoptMenuOption then return end
    if d.step == 1 then
        local s = PzoptMainMenu.show()
        if not s then return end
        s:selectNode("home", "master")
        for i, it in ipairs(s.list.items) do if it.id == "uninstall" then s.list:setSelected(i) end end
        s:showDetail()
        local b = nil
        for _, f in ipairs(s.actionButtons or {}) do if f.title == "UNINSTALL" then b = f end end
        if not b then
            print("[pzopt-e2e] uninstall drive: no UNINSTALL button on the screen's home page (" .. tostring(s.lastDesc) .. ")")
            d.step = -1
            return
        end
        d.button = b
        print("[pzopt-e2e] screen shown: button \"" .. tostring(b.title) .. "\" enabled=" .. tostring(b.enable) .. " visible="
            .. tostring(b:isReallyVisible()) .. " detail=" .. tostring(s.lastDesc))
        getCore():TakeFullScreenshot("pzopt-e2e-A-screen.png")
        d.step, d.at = 2, getTimestampMs() + 3000
    elseif d.step == 2 then
        d.button:forceClick()
        d.step, d.at = 3, getTimestampMs() + 1500
    elseif d.step == 3 then
        local s = PzoptMainMenu.instance()
        local m = s and s.confirmModal
        if not m then
            print("[pzopt-e2e] uninstall drive: the button opened no dialog")
            d.step = -1
            return
        end
        print("[pzopt-e2e] dialog shown: " .. string.gsub(tostring(m.text), "\n", " | "))
        getCore():TakeFullScreenshot("pzopt-e2e-A-dialog.png")
        d.step, d.at = 4, getTimestampMs() + 3000
    elseif d.step == 4 then
        local m = PzoptMainMenu.instance().confirmModal
        d.step = -1
        print("[pzopt-e2e] pressing Yes at " .. getTimestampMs())
        m:onClick(m.yes)
    end
end
Events.OnFETick.Add(function() pcall(uninstallDriveTick) end)

local function install()
    if not MainScreen or MainScreen.pzoptMenuItem then return end
    local ok, has = pcall(function() return getPerformance():hasPzoptOptions() end)
    if not ok or not has then
        print("[pzopt] menu: PerformanceSettings override not loaded or build mismatch, item not added")
        return
    end
    MainScreen.pzoptMenuItem = true
    local stockInstantiate = MainScreen.instantiate
    function MainScreen:instantiate(...)
        stockInstantiate(self, ...)
        local okAdd, err = pcall(addItem, self)
        if not okAdd then print("[pzopt] menu: item failed: " .. tostring(err)) end
    end
    local stockPrerender = MainScreen.prerender
    function MainScreen:prerender(...)
        pcall(fitMenuColumn, self) -- before the stock layout places the column under the logo
        stockPrerender(self, ...)
        if self.pzoptMenuOption then
            local okSync, err = pcall(syncItem, self)
            if not okSync then
                print("[pzopt] menu: sync failed, item removed: " .. tostring(err))
                self.pzoptMenuOption:setVisible(false)
                self.pzoptMenuOption = nil
            end
        end
        if self.pzoptMenuScreen then
            local okTick, err = pcall(screenTick, self)
            if not okTick then print("[pzopt] menu: screen tick: " .. tostring(err)) end
        end
    end
    local stockGainJoypadFocus = MainScreen.onGainJoypadFocus
    function MainScreen:onGainJoypadFocus(...)
        stockGainJoypadFocus(self, ...)
        if self.pzoptMenuOption then pcall(syncJoypadRow, self) end
    end
    local stockJoypadDown = MainScreen.onJoypadDown
    function MainScreen:onJoypadDown(button, ...)
        local label = self.pzoptMenuOption
        if label and button == Joypad.AButton and self.joypadButtons and self.joypadButtons[self.joypadIndex] == label then
            onItemClick(label, 0, 0)
            return
        end
        return stockJoypadDown(self, button, ...)
    end
    local stockKeyRelease = MainScreen.onKeyRelease
    function MainScreen:onKeyRelease(key, ...)
        if key == Keyboard.KEY_F and isCtrlKeyDown() and self.pzoptMenuScreen and self.pzoptMenuScreen:isVisible() then
            self.pzoptMenuScreen:focusSearch()
            return
        end
        if key == Keyboard.KEY_ESCAPE and not self.inGame and not UIManager.isModalVisible() then
            local okEsc, done = pcall(onEscape, self)
            if okEsc and done then return end
        end
        return stockKeyRelease(self, key, ...)
    end
    -- The multiplayer pause menu lays out Options, Exit and Quit to desktop again every frame in the stock render (the
    -- invite item comes and goes): the item goes back below Options, the two under it one row down.
    local stockRender = MainScreen.render
    function MainScreen:render(...)
        stockRender(self, ...)
        local label = self.pzoptMenuOption
        if label and self.inGame and isClient() and self.optionsOption then
            pcall(function()
                local h = label:getHeight()
                label:setY(self.optionsOption:getBottom())
                if self.exitOption then self.exitOption:setY(self.exitOption:getY() + h) end
                if self.quitToDesktopOption then
                    self.quitToDesktopOption:setY(self.quitToDesktopOption:getY() + h)
                    self.bottomPanel:setHeight(self.quitToDesktopOption:getBottom())
                end
            end)
        end
    end
    -- In a game the menu key (Escape) toggles the pause menu (the stock ToggleEscapeMenu): with the PZ Optimization screen
    -- up it closes the screen first, back onto the pause menu, instead of resuming the game under it
    if ToggleEscapeMenu and not MainScreen.pzoptEscapeWrapped then
        MainScreen.pzoptEscapeWrapped = true
        local stockToggle = ToggleEscapeMenu
        local function toggle(key)
            local ms = MainScreen.instance
            local isMenuKey = false
            pcall(function()
                isMenuKey = getCore():isKey(KeybindId.MAIN_MENU, key) or (getCore():getKey(KeybindId.MAIN_MENU) == 0 and key == Keyboard.KEY_ESCAPE)
            end)
            if isMenuKey and ms and ms.inGame and ms:isVisible() and not UIManager.isModalVisible() then
                local okEsc, done = pcall(onEscape, ms)
                if okEsc and done then return end
            end
            return stockToggle(key)
        end
        -- The global is the wrapper too (hotfix 2026-10-19): mods wrap the menu key with Events.OnKeyPressed.Remove(
        -- ToggleEscapeMenu) + their own function calling the old global + Add. With the global left at the stock function
        -- their Remove missed ours, so stock ran twice per press and the pause menu opened and closed in one frame.
        ToggleEscapeMenu = toggle
        Events.OnKeyPressed.Remove(stockToggle)
        Events.OnKeyPressed.Add(ToggleEscapeMenu)
    end
    -- Ctrl+F in a game (the pause menu's screen: MainScreen.onKeyRelease is the main menu's)
    Events.OnKeyPressed.Add(function(key)
        local ms = MainScreen.instance
        local s = ms and ms.inGame and ms.pzoptMenuScreen
        if key == Keyboard.KEY_F and isCtrlKeyDown() and s and s:isVisible() then pcall(function() s:focusSearch() end) end
    end)
end

install()
Events.OnGameBoot.Add(install)
