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
--               baseline, folder), Compare (two passes or a share code as the Grafana compare page: PzoptReportCompare),
--               Report (the scenes;
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
--  screen with the cursor on the PZ OPTIMIZATION item; L3 the search (on-screen keyboard), R3 the before / after clips on / off.
--  This file also packs the main-menu column into short windows (fitMenuColumn) and starts the once-per-boot update check.
-- Installed by scripts/pzopt.sh into <game dir>/media/lua/client/pzopt/ (loose game-dir Lua, no mod to enable).

-- pzopt menu-impeccable prototype (2026-10-19, not verified in game; docs/menu-critique-2026-10-19.md section 4): the
-- maintainer's layout. The top-level entries are tabs under the breadcrumbs (LB / RB), the sidebar lists the selected
-- tab's pages always expanded, the controller's prompts sit where they act, a settings page's detail is read-only (the
-- controls live on the rows) and plays one clip (the setting on). A tab is one page listing every setting as a tree: its
-- categories are headings (switch, counts, "Reset to defaults" at the right end), their subcategories fold, each setting
-- has its on / off switch before its name and its modifiers indented under it, a value with choices is a dropdown (the
-- maintainer, 2026-10-19 night / 10-20; the mouse no longer selects by resting on a row: a click does; a change that
-- needs a restart only shows the notice at the bottom right). The bar under the page holds BACK, a switch that hides the
-- sidebar, the preset box with SAVE... and EXPORT / IMPORT... / UNINSTALL (2026-10-20). false = the released layout.
local TAB_LAYOUT = true
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
local C_DIM = { r = 0.52, g = 0.52, b = 0.56 } -- (about 5:1 on the cards: 0.40 was 3.4:1, under what small text needs)
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
-- (tab layout) "Boot and loading" and "System" are tabs of their own (maintainer, 2026-10-20), split off Optimizations
-- in settingsTree: the one category's switch is the tab's (no separate tab switch); the whole mod's `enabled` still gates it
local BOOT_CAT = "load"
-- (tab layout) settings shown on another tab's page (settingsTree moves them; the tab's page lists them as a category)
local MOVED_TABS = {
    { tab = "compat", cat = "mods", title = "Mods & MP", catTitle = "Mod settings", colour = { 0.55, 0.85, 0.85 },
      blurb = "How PZ Optimization meets other mods: Java mods that patch the same game code, windows mods draw in, mod Lua reached from helper threads.",
      subTitle = "Mods", keys = { "modProfile", "modCompat", "uiRetainedMods", "luaWorkerGate" } },
    { tab = "compat", cat = "mp", title = "Mods & MP", catTitle = "Multiplayer settings", colour = { 0.55, 0.85, 0.85 },
      blurb = "Playing on a server.",
      subTitle = "Multiplayer", keys = { "luaChecksumExempt" } },
    { tab = "update", cat = "upd", title = "Update", catTitle = "Update settings", colour = { 0.86, 0.86, 0.90 },
      blurb = "How new releases reach you: the offer in the main menu, the download in the background, where it comes from.",
      subTitle = "Updates", keys = { "updateCheck", "updatePrefetch", "updateFromWorkshop" } },
}
-- (tab layout) Optimizations' categories that are a tab of their own (maintainer, 2026-10-20): LOADING, SYSTEM
local SPLIT_TABS = {
    { id = "boot", cat = BOOT_CAT, name = "Loading", master = "catLoadEnabled", colour = { 0.94, 0.56, 0.66 },
      note = "Faster launches and loads: from launch to the menu, and from Continue to the world." },
    { id = "sys", cat = "system", name = "System", master = "catSystemEnabled", colour = { 0.86, 0.74, 0.58 },
      note = "The PC side: Java memory (heap size), cores and power, the audio mix." },
}
for _, sp in ipairs(TAB_LAYOUT and SPLIT_TABS or {}) do
    GROUPS[sp.id] = { name = sp.name, master = sp.master, live = false, catSwitch = true }
end

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
                local sub = { title = s.title, cat = cat, keys = {}, dlss = s.dlssButton, sectioned = s.sections ~= nil }
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
    local P = TAB_LAYOUT and t.byId.perf
    local after = P
    for _, sp in ipairs(P and SPLIT_TABS or {}) do
        for k, c in ipairs(P.cats) do
            if c.id == sp.cat then
                table.remove(P.cats, k)
                local B = { id = sp.id, title = sp.name, name = sp.name, page = P.page, c = rgb(sp.colour), note = sp.note, cats = { c } }
                c.group = B
                t.byId["perf/" .. c.id] = nil
                t.byId[sp.id .. "/" .. c.id] = c
                t.byId[sp.id] = B
                for gi, G in ipairs(t.groups) do -- (after Optimizations and the tabs split off before it)
                    if G == after then table.insert(t.groups, gi + 1, B) break end
                end
                after = B
                break
            end
        end
    end
    -- (tab layout) the mod and multiplayer settings live on the MODS & MP tab (under the compatibility check), the update
    -- settings on UPDATE (maintainer, 2026-10-20): taken out of their subcategory (an emptied one goes) into a category of
    -- that tab each
    for _, mv in ipairs(TAB_LAYOUT and MOVED_TABS or {}) do
        local want, keys = {}, {}
        for _, k in ipairs(mv.keys) do want[k] = true end
        for _, G in ipairs(t.groups) do
            for _, c in ipairs(G.cats) do
                for si = #c.subs, 1, -1 do
                    local sub = c.subs[si]
                    for ki = #sub.keys, 1, -1 do
                        if want[sub.keys[ki]] then table.remove(sub.keys, ki) end
                    end
                    if #sub.keys == 0 then table.remove(c.subs, si) end
                end
            end
        end
        for _, k in ipairs(mv.keys) do if byKey[k] then table.insert(keys, k) end end
        if #keys > 0 then
            local G = t.byId[mv.tab]
            if not G then
                G = { id = mv.tab, title = mv.title, name = mv.title, page = byKey[keys[1]].page, c = rgb(mv.colour), note = mv.blurb, cats = {}, moved = true }
                table.insert(t.groups, G)
                t.byId[mv.tab] = G
            end
            local cat = { id = mv.cat, title = mv.catTitle, blurb = mv.blurb, group = G, subs = {} }
            cat.subs[1] = { title = mv.subTitle, cat = cat, keys = keys }
            table.insert(G.cats, cat)
            t.byId[mv.tab .. "/" .. mv.cat] = cat
        end
    end
    local sys = TAB_LAYOUT and t.byId["sys/system"]
    if sys then -- (its mod settings went to MODS, its update settings to UPDATE)
        sys.title = "System, memory and sound"
        sys.blurb = "Java memory (heap size), cores and power, the audio mix."
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
-- search and Fix a problem show every match. Kept for the session, Simple first, like the tab. (Tab layout: every page lists
-- every setting, its subcategories and modifiers fold instead.)
local VIEW = { level = TAB_LAYOUT and 3 or 1 }
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

-- (tab layout) a tip without its "applies on the next launch" sentence: the detail's metadata says "Needs restart"
local function stripLaunchNote(t)
    t = string.gsub(t or "", "%s*Applies on the next launch%.", "")
    t = string.gsub(t, "%s*Takes effect on the next launch%.", "")
    t = string.gsub(t, "%s*Applies at once; no restart needed%.", "")
    return t
end

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
    elseif (kind == "choice" or kind == "bezier") and e.choices then
        local at = 1
        for i, c in ipairs(e.choices) do if tostring(c) == v then at = i end end
        at = ((at - 1 + (dir or 1) + #e.choices) % #e.choices) + 1
        settingStore(key, tostring(e.choices[at]))
        return true
    end
    return false
end

-- (tab layout) A setting's switch: a tick box flips, a choice with an off value ("off", "stock", ...) goes off or back to
-- the value it had (this session), else its default, the layout's onValues, the first value that is not off.
local LAST_ON = {}

local function offValueOf(key, e)
    return PzoptSettings and PzoptSettings.offValue(key, e) or nil
end

local function settingOn(key, e)
    local v = (settingNext(key))
    if settingKind(key, e) == "bool" then return v == "true" end
    local off = offValueOf(key, e)
    return off ~= nil and v ~= off
end

local function settingFlip(key, e)
    local v, pinned = settingNext(key)
    if pinned ~= "" then return false end
    if settingKind(key, e) == "bool" then
        settingStore(key, v == "true" and "false" or "true")
        return true
    end
    local off = offValueOf(key, e)
    if not off then return false end
    if v ~= off then
        LAST_ON[key] = v
        settingStore(key, off)
        return true
    end
    local on = LAST_ON[key]
    local def = opt("getPzoptOptionDefault", key)
    if not on and def ~= off then on = def end
    if not on then on = ((PzoptSettingsLayout or {}).onValues or {})[key] end
    if not on then
        for _, c in ipairs(e.choices) do
            if tostring(c) ~= off then on = tostring(c) break end
        end
    end
    settingStore(key, on)
    return true
end

-- a switched choice's value steps over its off value (the switch is the way off)
local function settingStepOn(key, e, dir)
    local v, pinned = settingNext(key)
    local off = offValueOf(key, e)
    if pinned ~= "" or not off or v == off then return settingStep(key, e, dir) end
    local at = 1
    for i, c in ipairs(e.choices) do if tostring(c) == v then at = i end end
    for _ = 1, #e.choices do
        at = ((at - 1 + (dir or 1) + #e.choices) % #e.choices) + 1
        if tostring(e.choices[at]) ~= off then break end
    end
    settingStore(key, tostring(e.choices[at]))
    return true
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
    { id = "bench/compare", title = "Compare", blurb = "Two runs side by side (the newest and the one before it on its scene unless a report picked them): frame time, fps, utilization, power, mods, setup." },
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

-- the tab layout's tabs (TOP's entries; Home is none since 2026-10-20: its tools are in the bar under the page); `short` when
-- the full labels do not fit the row
local TABS = {
    { id = "perf", short = "OPTIMIZE" }, { id = "boot", label = "LOADING", short = "LOAD" },
    { id = "sys", label = "SYSTEM", short = "SYSTEM" }, { id = "vis", short = "ENHANCE" },
    { id = "tools", short = "PROFILER" },
    { id = "bench", short = "BENCH" }, { id = "update", short = "UPDATE" }, { id = "compat", label = "MODS & MP", short = "MODS & MP" },
    { id = "problems", label = "HELP", short = "HELP" },
}

-- the tab a node lives in (Fix a problem: Home's, the HELP tab in the tab layout); nil for the search results, which
-- belong to no tab
local function tabOf(id)
    if not id or id == "home" then return "home" end
    local top = topOf(id)
    if top == "problems" then return TAB_LAYOUT and "problems" or "home" end
    if top == "search" then return nil end
    return top
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
    if id == "compat" then return TAB_LAYOUT and "Mods & multiplayer" or "Mod compatibility check" end
    if (a == "compat" or a == "update") and b then -- (tab layout) a moved category or its subcategory
        local node = t.byId[id]
        return node and node.title or id
    end
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
    if id == "perf" or id == "boot" or id == "sys" or id == "vis" or id == "tools" then
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
    for _, e in ipairs(TAB_LAYOUT and {} or TOP) do -- (the tab layout: they are the tabs)
        local value = nil
        if GROUPS[e.id] then value = function() return (masterState(GROUPS[e.id].master)) and "on" or "off" end end
        if e.id == "update" then value = function() return (updateShort()) end end
        table.insert(p.items, { id = e.id, label = titleOf(e.id), value = value, kind = "node", desc = function() return homeDesc(e.id) end })
    end
    if not TAB_LAYOUT then -- (tab layout: the presets are in the bar under the page, Fix a problem the HELP tab)
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
    end
    table.insert(p.items, { kind = "section", label = "Tools" })
    if not TAB_LAYOUT then table.insert(p.items, viewRow()) end -- (tab layout: every page lists every setting)
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
        value = (not TAB_LAYOUT) and function() return (masterState(key)) and "On" or "Off" end or nil, -- (tab layout: its switch)
        meta = function() return { restart = not live, key = key } end,
        desc = function()
            local on, now, pinned = masterState(key)
            local d = TAB_LAYOUT and esc(stripLaunchNote(tip))
                or (esc(tip) .. " " .. (live and LIVE_NOTE or RESTART_NOTE) .. " <LINE> <LINE> <RGB:0.66,0.66,0.70> Key: " .. key)
            if pinned ~= "" then d = d .. " <LINE> <RGB:1,0.85,0.45> Pinned by " .. esc(pinned) .. ", which wins: change it there."
            elseif on ~= now and not live then d = d .. " <LINE> <RGB:1,0.85,0.45> Switched " .. (on and "on" or "off") .. ": applies when the game restarts." end
            return d
        end,
        activate = function() toggleMasterKey(key) end,
        toggle = TAB_LAYOUT and { get = function() return (masterState(key)) end, set = function() toggleMasterKey(key) end,
            pinned = function() return opt("getPzoptOptionPinnedBy", key) ~= "" end } or nil,
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
        value = (not TAB_LAYOUT) and function() return shown() and "Shown" or "Hidden" end or nil,
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
        toggle = TAB_LAYOUT and { get = shown, set = function() pcall(function() perf():togglePzoptOverlay() end) end } or nil,
        controls = { { type = "tick", label = "Shown", get = shown, set = function() pcall(function() perf():togglePzoptOverlay() end) end } } }
end

-- A setting's detail text: what it does, when it applies, its values, who pins it, the release it came with, the Java
-- classes that read it. (Tab layout: when it applies, the release, the key and the classes are the metadata at the foot
-- of the detail card, settingMeta.)
local function settingDesc(k, e, page)
    local v, pinned = settingNext(k)
    local now = opt("getPzoptOption", k)
    local def = opt("getPzoptOptionDefault", k)
    local d = esc(TAB_LAYOUT and stripLaunchNote(e and e.tip) or (e and e.tip or "")) .. " <LINE> <LINE> <RGB:0.66,0.66,0.70> "
        .. (TAB_LAYOUT and "" or ((settingLive(k, e, page) and LIVE_NOTE or RESTART_NOTE) .. " <LINE> "))
        .. "Next launch: " .. esc(showValue(v, e)) .. "  /  In use now: " .. esc(showValue(now, e)) .. "  /  Default: "
        .. esc(showValue(def, e)) .. (TAB_LAYOUT and "" or (" <LINE> Key: " .. k))
    if pinned ~= "" then d = d .. " <LINE> <RGB:1,0.85,0.45> Pinned by " .. esc(pinned) .. ", which wins: change it there." end
    local why = opt("getPzoptModCompatReason", k)
    if why ~= "" and opt("getPzoptOptionSaved", k) == "" then
        d = d .. " <LINE> <RGB:1,0.85,0.45> Mod compatibility switched it off: " .. esc(why) .. " (your choice here wins)."
    end
    if PzoptSettings and not TAB_LAYOUT then
        local date = PzoptSettings.date(k)
        local classes = PzoptSettings.classes(k)
        d = d .. " <LINE> <RGB:0.66,0.66,0.70> " .. (date and ("Released " .. date) or "New in this version") .. ".  Java: "
            .. esc(#classes > 0 and table.concat(classes, ", ") or "read by pzopt.Config only")
    end
    return d
end

-- (tab layout) a setting's metadata: whether it needs a restart, the release it came with, its key, the Java classes
-- that read it
local function settingMeta(k, e, page)
    local m = { restart = not settingLive(k, e, page), key = k }
    if PzoptSettings then
        local date = PzoptSettings.date(k)
        m.released = date or "new in this version"
        local classes = PzoptSettings.classes(k)
        m.java = #classes > 0 and table.concat(classes, ", ") or "read by pzopt.Config only"
    end
    return m
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
        desc = function() return settingDesc(k, e, page) .. (where and (" <LINE> <RGB:0.66,0.66,0.70> On the page " .. esc(where)) or "") end,
        meta = function() return settingMeta(k, e, page) end }
    if kind == "bool" or kind == "choice" or kind == "bezier" then
        item.activate = function() settingStep(k, e, 1) end
        item.controls = { { type = kind == "bool" and "tick" or "chooser", label = kind == "bool" and "Enabled" or (kind == "bezier" and "Curve" or "Value"),
            -- (the and / or form returned "Off", a truthy string, for a switch that is off: the tick box was always filled)
            get = function()
                if kind == "bool" then return (settingNext(k)) == "true" end
                return showValue((settingNext(k)), nil)
            end,
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
    -- (prototype) changed from the build's default: the row's dot and its reset (DEFAULT as a row action)
    item.isChanged = function()
        return opt("getPzoptOptionPinnedBy", k) == "" and (settingNext(k)) ~= opt("getPzoptOptionDefault", k)
    end
    item.reset = function() item.actions[1].act() end
    if TAB_LAYOUT then
        -- (tab layout) the row's switch sits before its name, a value it has after the name; a switched choice shows its
        -- value only while on
        local off = (kind == "choice") and offValueOf(k, e) or nil
        if kind == "bool" or off then
            item.toggle = { get = function() return settingOn(k, e) end, set = function() settingFlip(k, e) end,
                pinned = function() return opt("getPzoptOptionPinnedBy", k) ~= "" end }
            item.activate = item.toggle.set
        end
        if kind == "choice" or kind == "bezier" then
            -- a dropdown: the closed box shows the value (the curve: its preset's name), the list every choice but the
            -- off one (the switch is the way off) with its note under it
            local function shown(v)
                if kind == "bezier" then return (e.note and e.note[v]) or (v == "" and "-" or "custom") end
                return showValue(v, nil)
            end
            item.chooser = { type = "chooser", label = "Value", get = function() return shown((settingNext(k))) end,
                set = function(dir) settingStepOn(k, e, dir or 1) end,
                shown = function() return not off or settingOn(k, e) end,
                pinned = function() return opt("getPzoptOptionPinnedBy", k) ~= "" end,
                options = function()
                    local v = (settingNext(k))
                    local t, has = {}, false
                    for _, ch in ipairs(e.choices or {}) do
                        local cv = tostring(ch)
                        if cv ~= off then
                            local note = kind ~= "bezier" and e.note and e.note[cv] or nil
                            table.insert(t, { label = shown(cv), note = note, current = cv == v,
                                pick = function() if opt("getPzoptOptionPinnedBy", k) == "" then settingStore(k, cv) end end })
                            if cv == v then has = true end
                        end
                    end
                    if not has and v ~= off then table.insert(t, 1, { label = shown(v), current = true }) end -- (a value set by hand)
                    return t
                end }
            if not off then item.activate = function() item.chooser.set(1) end end
            item.value = function() return item.chooser.shown() and item.chooser.get() or "" end
        elseif kind == "bool" then
            item.value = nil
        end
        item.isOn = item.toggle and item.toggle.get or nil
    end
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
        -- prototype: one step at a time (Simple -> Advanced -> Everything), the count of what that step adds; the old row
        -- jumped straight to Everything and its tuning numbers
        local nextLevel = VIEW.level + 1
        local adds = 0
        for _, k in ipairs(s and s.keys or {}) do if tierOf(k) == nextLevel then adds = adds + 1 end end
        if adds == 0 then nextLevel, adds = 3, hidden end
        table.insert(p.items, { id = "more", kind = "row", label = "Show " .. adds .. " more settings (" .. VIEW_NAMES[nextLevel] .. " view)",
            desc = function()
                return hidden .. " settings of " .. esc(s.title) .. " are left out in the " .. VIEW_NAMES[VIEW.level] .. " view. A or SHOW MORE "
                    .. "switches to " .. VIEW_NAMES[nextLevel] .. " (" .. VIEW_TIPS[nextLevel] .. ") until the game closes; the view is on the home page."
            end,
            activate = function() VIEW.level = nextLevel end,
            actions = { { label = "SHOW MORE", key = "y", act = function() VIEW.level = nextLevel end },
                { label = "SHOW ALL", key = "x", act = function() VIEW.level = 3 end } } })
    end
    if #p.items == 0 then table.insert(p.items, { kind = "note", label = "No settings here." }) end
    return p
end

-- (tab layout) One page per tab (tabPage below): every category as a heading, every subcategory as a group that folds
-- (the sidebar's rows are its table of contents: a row scrolls to its heading and opens it), every setting with its
-- switch and its modifiers indented under it (PzoptSettings.nest; the layout's `parents`). COLLAPSED: the groups
-- ("<sub id>") and settings ("key:<key>") folded on this screen, kept until the game closes.
local COLLAPSED = {}

local function nestKind(k)
    local b = settingsTree().entry[k]
    local kind = settingKind(k, b and b.e)
    if kind == "bool" then return "bool" end
    if kind == "choice" or kind == "bezier" then return "choice" end
    return "other"
end

-- the settings of a subcategory that differ from the build's default (the group row's count)
local function changedCount(keys)
    local n = 0
    for _, k in ipairs(keys) do
        if opt("getPzoptOptionPinnedBy", k) == "" and (settingNext(k)) ~= opt("getPzoptOptionDefault", k) then n = n + 1 end
    end
    return n
end

-- every key of a category (its switch first): its reset
local function catKeys(cat)
    local keys = {}
    if cat.master then table.insert(keys, cat.master) end
    for _, sub in ipairs(cat.subs) do for _, k in ipairs(sub.keys) do table.insert(keys, k) end end
    return keys
end

-- "Reset to defaults" at the right end of a category's heading (and of the tab's switch): asks first; X with a controller
local RESET_LINK = "Reset to defaults"
local function resetAction(self, title, keysFn)
    return { label = "RESET", key = "x", quiet = true, confirm = "Reset every setting of " .. title .. " to the build's defaults?", act = function()
        local keys = keysFn()
        for _, k in ipairs(keys) do TOUCHED[k] = true end
        local n = PzoptSettings.resetKeys(keys)
        showMessage(self, title .. ": " .. n .. (n == 1 and " setting" or " settings") .. " back to the default.")
    end }
end

-- a category's subcategories as groups that fold, each with its settings as a tree (under the category's heading `head`)
local function addSubs(self, p, a, cat, head)
    local t = settingsTree()
    local id = a .. "/" .. cat.id
    for i, sub in ipairs(cat.subs) do
        local sid = id .. "/" .. i
        local s = sub
        local open = not COLLAPSED[sid]
        local changed = changedCount(s.keys)
        table.insert(p.groups, sid)
        table.insert(p.items, { id = "group:" .. sid, kind = "group", label = s.title, anchor = sid, open = open,
            count = #s.keys .. (changed > 0 and ("  (" .. changed .. " changed)") or ""), changed = changed > 0,
            activate = function() COLLAPSED[sid] = open or nil end,
            desc = function()
                local names = {}
                for _, k in ipairs(s.keys) do
                    local e = t.entry[k] and t.entry[k].e
                    table.insert(names, esc(e and e.label or k) .. ": " .. esc(showValue((settingNext(k)), e)))
                end
                return #s.keys .. (#s.keys == 1 and " setting" or " settings") .. (changed > 0 and (", " .. changed .. " changed from the defaults") or "")
                    .. ". A click on the heading (A with a controller) folds or opens it. <LINE> <LINE> " .. table.concat(names, " <LINE> ")
            end })
        if open then
            if a == "tools" and cat.id == "overlay" and i == 1 then
                local o = overlayRow()
                o.anchor, o.depth, o.treeParent = sid, 0, head
                table.insert(p.items, o)
            end
            local nodes = PzoptSettings and PzoptSettings.nest(s.keys, function(k) return t.entry[k] end, nestKind, s.sectioned) or {}
            -- a row knows its depth, whether it is the last of its parent's (the tree's guide lines) and the rows above it
            local function add(n, parentItem, lastFlags)
                local it = settingItem(self, n.key)
                it.anchor, it.depth, it.treeParent = sid, n.depth, parentItem
                it.lastFlags = lastFlags
                if #n.kids > 0 then
                    local ck = "key:" .. n.key
                    it.kids = #n.kids
                    it.folded = COLLAPSED[ck] == true
                    it.fold = function() COLLAPSED[ck] = (not COLLAPSED[ck]) or nil end
                    table.insert(it.actions, { label = function() return COLLAPSED[ck] and "OPEN" or "FOLD" end, key = "y", quiet = true,
                        act = it.fold })
                end
                table.insert(p.items, it)
                if not it.folded then
                    for j, c in ipairs(n.kids) do
                        local flags = {}
                        for _, f in ipairs(lastFlags) do table.insert(flags, f) end
                        table.insert(flags, j == #n.kids)
                        add(c, it, flags)
                    end
                end
            end
            for _, n in ipairs(nodes) do add(n, head, {}) end
            if s.dlss and PzoptSettings then
                table.insert(p.items, { id = "dlss", kind = "row", anchor = sid, depth = 0, label = "NVIDIA DLSS files",
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
        end
    end
end

-- a category's heading: its switch, its name, how many settings and how many changed, "Reset to defaults" at the right end
local function catHead(self, a, cat)
    local id = a .. "/" .. cat.id
    local n, changed = 0, 0
    for _, sub in ipairs(cat.subs) do n, changed = n + #sub.keys, changed + changedCount(sub.keys) end
    local it = { id = "cat:" .. id, kind = "cat", label = cat.title, anchor = id,
        count = n .. (n == 1 and " setting" or " settings") .. (changed > 0 and ("  (" .. changed .. " changed)") or ""), changed = changed > 0,
        desc = function() return esc(cat.blurb or "") end }
    it.headLink = { text = RESET_LINK, action = resetAction(self, cat.title, function() return catKeys(cat) end) }
    it.actions = { it.headLink.action }
    if cat.master then
        local live = cat.group.page ~= 1
        local entry = PzoptSettings and PzoptSettings.CATS.entry({ master = cat.master, title = cat.title }, live)
        local m = masterRow("master:" .. id, cat.master, cat.title .. " enabled", live, entry and entry.tip or "")
        it.master, it.toggle, it.activate, it.isOn, it.meta = cat.master, m.toggle, m.activate, m.toggle.get, m.meta
        it.desc = function() return esc(cat.blurb or "") .. " <LINE> <LINE> " .. m.desc() end
    end
    return it
end

-- (tab layout) A tab's one page: its master switch, then every category (a heading with its switch and reset), every
-- subcategory of it as a group that folds, every setting with its switch and its modifiers indented under it. The
-- sidebar's rows are its table of contents: a category or subcategory scrolls the page to its heading.
local function tabPage(self, a)
    local t = settingsTree()
    local G, g = t.byId[a], GROUPS[a]
    local p = { items = {}, settingsPage = true, toc = a, groups = {} }
    local top
    if g.catSwitch then -- (Loading, System: its category's switch is the tab's; the whole mod's switch still dims it)
        top = { isOn = function() return (masterState(GROUPS.perf.master)) end }
    else
        top = masterRow("master:" .. a, g.master, g.name .. " enabled", g.live, groupMasterTip(a))
        top.isOn = top.toggle and top.toggle.get
        top.headLink = { text = RESET_LINK, action = resetAction(self, g.name, function()
            -- (what this page lists: Loading, the mod and multiplayer settings live on their own tabs now)
            local keys = { g.master }
            for _, cat in ipairs(G.cats) do for _, k in ipairs(catKeys(cat)) do table.insert(keys, k) end end
            return keys
        end) }
        top.actions = { top.headLink.action }
        table.insert(p.items, top)
    end
    for _, cat in ipairs(G and G.cats or {}) do
        local head = catHead(self, a, cat)
        head.treeParent = top
        table.insert(p.items, head)
        addSubs(self, p, a, cat, head)
    end
    return p
end

-- (tab layout) the settings moved to a tab that is not a settings tab (MODS & MP): each category's heading and tree
local function movedSettings(self, p, a)
    local G = settingsTree().byId[a]
    if not (G and G.moved) then return p end
    p.toc, p.groups, p.settingsPage = a, p.groups or {}, true
    for _, cat in ipairs(G.cats) do
        local head = catHead(self, a, cat)
        table.insert(p.items, head)
        addSubs(self, p, a, cat, head)
    end
    return p
end

-- every group of the page folded, or every group and setting open
local function foldAll(page, fold)
    for _, sid in ipairs(page.groups or {}) do COLLAPSED[sid] = fold or nil end
    if not fold then
        for k in pairs(COLLAPSED) do if string.sub(k, 1, 4) == "key:" then COLLAPSED[k] = nil end end
    end
    PzoptLogInfo("[pzopt] menu: " .. (fold and "folded" or "opened") .. " every group")
end

local function anyOpen(page)
    for _, sid in ipairs(page.groups or {}) do if not COLLAPSED[sid] then return true end end
    return false
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
                        sub = { title = s.title }, node = G.id .. "/" .. c.id .. "/" .. i, where = G.name .. " / " .. c.title .. " / " .. s.title,
                        catNode = G.id .. "/" .. c.id, tabName = G.name })
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
    local total = #list
    if TAB_LAYOUT then
        -- (tab layout) under their categories, the category of the best match first; each category's heading has "Go to
        -- section" at its right end (A with a controller), which opens its tab scrolled to that category; B comes back
        local order, byCat = {}, {}
        for n, h in ipairs(list) do
            if n > 60 then break end
            local cn = h.row.catNode
            if not byCat[cn] then
                byCat[cn] = {}
                table.insert(order, cn)
            end
            table.insert(byCat[cn], h)
        end
        list = {}
        for _, cn in ipairs(order) do
            local hs = byCat[cn]
            local first = hs[1].row
            local node = cn
            local function go()
                self:selectNode(node, "master")
                self.backTo[node] = "search"
            end
            local goAct = { label = "GO TO SECTION", key = "x", quiet = true, act = go }
            table.insert(list, { head = { id = "searchcat:" .. cn, kind = "cat", label = first.cat.title,
                count = first.tabName .. ", " .. #hs .. (#hs == 1 and " match" or " matches"),
                headLink = { text = "Go to section", action = goAct }, actions = { goAct }, activate = go, verb = "Go to section",
                desc = function()
                    return "Matches in " .. esc(first.tabName) .. " / " .. esc(first.cat.title)
                        .. ". Go to section (A with a controller) opens it on its page; B comes back to these results."
                end } })
            for _, h in ipairs(hs) do table.insert(list, h) end
        end
    end
    local n = 0
    for _, h in ipairs(list) do
        if h.head then
            table.insert(p.items, h.head)
        else
            n = n + 1
            if n > 60 then break end
            local row = h.row
            local item = settingItem(self, row.key, row.where)
            if TAB_LAYOUT then item.depth = 0 else item.value = function() return showValue((settingNext(row.key)), nil) end end
            item.searchNode = row.node
            table.insert(item.actions, 1, { label = "OPEN ITS PAGE", key = "y", act = function() self:openSetting(row.node, row.key) end })
            if TAB_LAYOUT then -- (tab layout) the row opens the setting on its page; its value still changes on the row
                item.activate = function() self:openSetting(row.node, row.key) end
                item.verb = "Open its page"
            end
            table.insert(p.items, item)
        end
    end
    if total == 0 then
        table.insert(p.items, { kind = "note", label = q:match("%w") and ("Nothing matches \"" .. q .. "\".") or "Type in the search box (L3 with a controller)." })
    elseif total > 60 then
        table.insert(p.items, { kind = "note", label = (total - 60) .. " more: type more words to narrow it down." })
    end
    p.count = total
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

-- (tab layout) the planner's scenes in the detail card: their before / after clip, how hard each part of the PC works
-- (0 none .. 3 heavy: from what the route is built to load, the same on every PC, not measured here), where it plays
local SCENE_INFO = {
    spin = { clip = "bench-spin", where = "Rosewood, south from the gas station on KY-60",
             use = { cpu = 3, render = 3, cores = 1, gpu = 2, vram = 1, ram = 1, disk = 1, load = 1, chunks = 2 } },
    drive120 = { clip = "bench-drive120", where = "KY-60, east out of Rosewood",
                 use = { cpu = 2, render = 3, cores = 2, gpu = 3, vram = 2, ram = 1, disk = 3, load = 1, chunks = 3 } },
    louisville = { clip = "bench-louisville", where = "Downtown Louisville",
                   use = { cpu = 3, render = 2, cores = 3, gpu = 2, vram = 1, ram = 3, disk = 1, load = 1, chunks = 1 } },
    drive120south = { clip = "bench-drive120south", where = "Rosewood, KY-60 and North Main Street",
                      use = { cpu = 3, render = 3, cores = 2, gpu = 3, vram = 2, ram = 1, disk = 2, load = 1, chunks = 3 } },
    -- (2026-10-21) the horde moved to the school's baseball field: no clip until one of the new place is made (bench-horde shows the pier)
    horde = { where = "Riverside, the school's baseball field at noon: 150 fast zombies, fought on foot",
              use = { cpu = 3, render = 2, cores = 2, gpu = 2, vram = 1, ram = 2, disk = 0, load = 1, chunks = 0 } },
    -- (2026-10-21) the sweep moved to the church: no clip until one of the new place is made (bench-sweep shows the gas station)
    sweep = { where = "Rosewood, outside the church's east doors: a small square walked, a turn at each corner",
              use = { cpu = 1, render = 2, cores = 0, gpu = 3, vram = 2, ram = 1, disk = 0, load = 1, chunks = 0 } },
    reflections = { where = "Riverside, a house with a home gym on the river bank and the pool next door, no zombies",
                    use = { cpu = 1, render = 2, cores = 1, gpu = 3, vram = 2, ram = 1, disk = 0, load = 1, chunks = 1 } },
}

-- (tab layout, 2026-10-21, maintainer) the run planner as a settings page: each scene a switch row (its card: its clip,
-- where it plays, its resource use) with its own Passes, Weather, Fog and Time of day dropdowns indented under it while it is
-- on; the cool-down as a dropdown row, the run's name as a row with NAME..., the options as switch rows, and one Start row
-- at the foot whose START BENCHMARK button carries the estimate and stays disabled until a scene is on (Y starts it too)
local function setupPageTabs(self)
    local B = benchState(self)
    local S = B.setup
    local p = { items = {}, cardPage = true }
    local function estimate()
        local ids, _, _, total, steps = PzoptBench.estimate(S, B.scenarios)
        if #ids == 0 then return nil end
        return string.format("%d %s, about %d min", steps, steps == 1 and "pass" or "passes", math.max(1, math.floor(total / 60 + 0.5)))
    end
    -- (2026-10-21, maintainer) the disclaimer while the full performance overlay is chosen: in the head, the Start row and
    -- the confirm
    local PROFILER_NOTE = "The full performance overlay is on: drawing and sampling it costs frames, so this run scores lower "
        .. "than the same run without it. Compare its scores only with runs that had the overlay on too."
    p.head = function()
        return "Our own test routes in a fresh world made for the benchmark; your saves are not touched. The game restarts for "
            .. "every pass, so each one starts alike: keep the window in front until the report shows."
            .. (S.opt.profiler and (" <LINE> <RGB:1,0.75,0.3> " .. PROFILER_NOTE) or "")
            .. (B.error and (" <LINE> <RGB:1,0.6,0.5> " .. esc(B.error)) or "")
    end
    local start = { danger = true, key = "y",
        label = function()
            local e = PzoptBench.estimateText(S, B.scenarios)
            return e and ("START BENCHMARK (" .. string.upper(e) .. ")") or "START BENCHMARK"
        end,
        disabled = function() return estimate() == nil end,
        confirm = function()
            return ((MainScreen.instance and MainScreen.instance.inGame)
                and "Start the benchmark? This saves your game and quits the session; the game restarts for every pass and shows the report at the end."
                or "Start the benchmark? The game restarts for every pass and shows the report at the end.")
                .. (S.opt.profiler and ("\n\n" .. PROFILER_NOTE) or "")
        end,
        act = function() B.error = PzoptBench.start(S, B.scenarios) end }
    p.y = start -- (Y on any row: the page's main action)
    -- a dropdown row (the settings rows' chooser): `list` the values, `name` a value's label, `at` / `pick` the index;
    -- `extra` more fields (a scene's card for its own rows)
    local function dropRow(id, label, list, name, at, pick, desc, depth, extra)
        local c = { type = "chooser", label = label,
            get = function() return name(list[at()]) end,
            set = function(dir) pick(((at() - 1 + (dir or 1) + #list) % #list) + 1) end,
            shown = function() return true end,
            options = function()
                local t = {}
                for i, v in ipairs(list) do
                    table.insert(t, { label = name(v), current = i == at(), pick = function() pick(i) end })
                end
                return t
            end }
        local it = { id = id, kind = "row", label = label, depth = depth or 0, chooser = c, activate = function() c.set(1) end,
            value = function() return c.get() end, desc = function() return desc end }
        for k, v in pairs(extra or {}) do it[k] = v end
        return it
    end
    table.insert(p.items, { kind = "section", label = "Scenes" })
    for _, sc in ipairs(B.scenarios) do
        local id = sc.id
        local per = S.per[id]
        -- the scene's card (its own rows show it too: the clip and the facts stay while its passes, weather or hour change)
        local card = {
            meta = function() return {} end,
            use = SCENE_INFO[id] and SCENE_INFO[id].use or {},
            clip = SCENE_INFO[id] and SCENE_INFO[id].clip or nil,
            metaRows = function()
                local info = SCENE_INFO[id] or {}
                local n, _, _ = PzoptBench.sceneSetup(S, id)
                return {
                    { "Where", info.where or "-", C_GREY },
                    { "Length", string.format("About %d s a pass, plus about 16 s to restart and load", sc.seconds), C_GREY },
                    { "Runs", string.format("%s, %s, %s, %s", n == 1 and "1 pass" or (n .. " passes"),
                        PzoptBench.weathers[per.weather].label, PzoptBench.fogs[per.fog].label, PzoptBench.times[per.time].label), C_GREY },
                    { "Score", id == "sweep" and "Not in the score: it measures each Visuals feature" or "Counts in the score", C_GREY },
                    { "Tests", sc.stresses ~= "" and sc.stresses or "-", C_GREY },
                }
            end }
        local row = { id = "scen:" .. id, kind = "tick", label = sc.title, depth = 0,
            toggle = { get = function() return S.scen[id] == true end, set = function() S.scen[id] = not S.scen[id] end },
            activate = function() S.scen[id] = not S.scen[id] end,
            desc = function()
                return esc(sc.what) .. " <LINE> <LINE> <RGB:0.55,0.55,0.60> Resource use is how hard the route is built to work "
                    .. "each part, the same on every PC; the report measures what it costs on yours."
            end }
        for k, v in pairs(card) do row[k] = v end
        table.insert(p.items, row)
        if S.scen[id] then
            table.insert(p.items, dropRow("passes:" .. id, "Passes", PzoptBench.passes,
                function(n) return n == 1 and "1 pass" or (n .. " passes") end,
                function() return per.passes end, function(i) per.passes = i end,
                "How many times " .. esc(sc.title) .. " runs. The report gives its mean and how far the passes spread: more "
                    .. "passes, a tighter number.", 1, card))
            table.insert(p.items, dropRow("weather:" .. id, "Weather", PzoptBench.weathers,
                function(w) return w.label end,
                function() return per.weather end, function(i) per.weather = i end,
                "The weather " .. esc(sc.title) .. " runs in, held for the whole pass. Rain and the thunderstorm add puddles and "
                    .. "rain, the storm lightning too. A score compares with another run's only at the same weather, fog and hour.",
                1, card))
            table.insert(p.items, dropRow("fog:" .. id, "Fog", PzoptBench.fogs,
                function(f) return f.label end,
                function() return per.fog end, function(i) per.fog = i end,
                "The fog over " .. esc(sc.title) .. ", on its own beside the weather and held for the whole pass: none, light, "
                    .. "thick or heavy (the thickest the game rolls). With the thunderstorm it takes the storm's dark tint.", 1, card))
            table.insert(p.items, dropRow("time:" .. id, "Time of day", PzoptBench.times,
                function(t) return t.label end,
                function() return per.time end, function(i) per.time = i end,
                "The hour " .. esc(sc.title) .. " runs at, held for the whole pass. Noon is the benches' own light; the evening's "
                    .. "low sun casts long shadows, the night is lit by the street lights and the cars.", 1, card))
        end
    end
    table.insert(p.items, { kind = "section", label = "Run" })
    table.insert(p.items, dropRow("cool", "Between passes", PzoptBench.cooldown,
        function(c) return c == 0 and "No cool-down" or (c .. " s cool-down") end,
        function() return S.cool end, function(i) S.cool = i end,
        "A pause before each pass so the CPU and GPU start at the same temperature; worth it on laptops, whose clocks drop "
            .. "when they are hot."))
    -- (2026-10-21, maintainer) the name shows the one the run gets by itself (its start's date and minute) until the player
    -- types another; kept as that automatic name (empty label: the run is named by its own start) when left unchanged
    table.insert(p.items, { id = "name", kind = "row", label = "Name", depth = 0,
        value = function() return (S.label or "") ~= "" and S.label or PzoptBench.autoName() end,
        desc = function()
            return "The run's name in the reports list and on its report. Left as it is, the run is named by when it starts "
                .. "(the date and minute); NAME... gives it your own, what you changed, say."
        end,
        actions = { { label = "NAME...", act = function()
            self:askText("A name for this run", (S.label or "") ~= "" and S.label or PzoptBench.autoName(), function(typed)
                local name = string.gsub(typed or "", "^%s*(.-)%s*$", "%1")
                S.label = (name == PzoptBench.autoName()) and "" or name
            end)
        end } } })
    table.insert(p.items, { kind = "section", label = "Options" })
    for _, o in ipairs(PzoptBench.options) do
        local id = o.id
        table.insert(p.items, { id = "opt:" .. id, kind = "tick", label = o.title, depth = 0,
            toggle = { get = function() return S.opt[id] == true end, set = function() S.opt[id] = not S.opt[id] end },
            activate = function() S.opt[id] = not S.opt[id] end,
            desc = function() return esc(o.desc) end })
    end
    table.insert(p.items, { kind = "section", label = "Start" })
    table.insert(p.items, { id = "start", kind = "row", label = "Start the benchmark", depth = 0,
        value = function() -- (the time is on the button)
            local ids, _, _, _, steps = PzoptBench.estimate(S, B.scenarios)
            if #ids == 0 then return "Choose at least one scene" end
            return steps == 1 and "1 pass" or (steps .. " passes")
        end,
        desc = function()
            local e = estimate()
            return (e and ("This plan: " .. e .. ". ") or "<RGB:1,0.6,0.5> Turn on at least one scene above to start. <RGB:0.94,0.94,0.96> ")
                .. "The game quits and starts again for every pass (it saves your game first when one is running); keep its "
                .. "window in front. The report opens when the last pass ends. Y starts it from any row of this page."
                .. (S.opt.profiler and (" <LINE> <LINE> <RGB:1,0.75,0.3> " .. PROFILER_NOTE) or "")
        end,
        actions = { start } })
    return p
end

PAGES["bench/setup"] = function(self)
    if TAB_LAYOUT then return setupPageTabs(self) end
    local B = benchState(self)
    local S = B.setup
    local p = { items = {}, cardPage = TAB_LAYOUT }
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
          set = function(dir) S.passes = ((S.passes - 1 + (dir or 1) + #PzoptBench.passes) % #PzoptBench.passes) + 1 end,
          options = function()
              local t = {}
              for i, n in ipairs(PzoptBench.passes) do
                  table.insert(t, { label = n == 1 and "1 pass" or (n .. " passes"), current = i == S.passes, pick = function() S.passes = i end })
              end
              return t
          end },
        { type = "chooser", label = "Between passes", get = function() local c = PzoptBench.cooldown[S.cool] return c == 0 and "No cool-down" or (c .. " s cool-down") end,
          set = function(dir) S.cool = ((S.cool - 1 + (dir or 1) + #PzoptBench.cooldown) % #PzoptBench.cooldown) + 1 end,
          options = function()
              local t = {}
              for i, c in ipairs(PzoptBench.cooldown) do
                  table.insert(t, { label = c == 0 and "No cool-down" or (c .. " s cool-down"), current = i == S.cool, pick = function() S.cool = i end })
              end
              return t
          end },
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
        -- (tab layout: the reports are the sidebar)
        (not TAB_LAYOUT) and { label = "PAST RESULTS", key = "x", act = function() self:openPage("bench/history") end } or nil,
    }
    table.insert(p.items, { kind = "section", label = "Scenes" })
    for _, sc in ipairs(B.scenarios) do
        local id = sc.id
        table.insert(p.items, { id = "scen:" .. id, kind = "tick", label = sc.title,
            value = (not TAB_LAYOUT) and function() return S.scen[id] and "On" or "Off" end or nil, -- (tab layout: its switch)
            toggle = TAB_LAYOUT and { get = function() return S.scen[id] == true end, set = function() S.scen[id] = not S.scen[id] end } or nil,
            depth = TAB_LAYOUT and 0 or nil,
            activate = function() S.scen[id] = not S.scen[id] end,
            desc = function()
                if TAB_LAYOUT then -- (the card: the tests are in its facts)
                    return esc(sc.what) .. " <LINE> <LINE> <RGB:0.55,0.55,0.60> Resource use is how hard the route is built to work "
                        .. "each part, the same on every PC; the report measures what it costs on yours."
                end
                return esc(sc.what) .. " <LINE> <LINE> <RGB:0.6,0.8,1> Tests: " .. esc(sc.stresses)
            end,
            -- (tab layout) the card's parts
            meta = TAB_LAYOUT and function() return {} end or nil,
            use = SCENE_INFO[id] and SCENE_INFO[id].use or {},
            clip = SCENE_INFO[id] and SCENE_INFO[id].clip or nil,
            metaRows = function()
                local info = SCENE_INFO[id] or {}
                return {
                    { "Where", info.where or "-", C_GREY },
                    { "Length", string.format("About %d s a pass, plus about 16 s to restart and load", sc.seconds), C_GREY },
                    { "Score", id == "sweep" and "Not in the score: it measures each Visuals feature" or "Counts in the score", C_GREY },
                    { "Tests", sc.stresses ~= "" and sc.stresses or "-", C_GREY },
                }
            end,
            controls = controls, actions = actions })
    end
    table.insert(p.items, { kind = "section", label = "Options" })
    for _, o in ipairs(PzoptBench.options) do
        local id = o.id
        table.insert(p.items, { id = "opt:" .. id, kind = "tick", label = o.title,
            value = (not TAB_LAYOUT) and function() return S.opt[id] and "On" or "Off" end or nil,
            toggle = TAB_LAYOUT and { get = function() return S.opt[id] == true end, set = function() S.opt[id] = not S.opt[id] end } or nil,
            depth = TAB_LAYOUT and 0 or nil,
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

-- (2026-10-21) two passes compared as the Grafana compare page (pzopt_mainscreen_compare.lua): A (the reference) and B,
-- each a saved run's pass or a pasted share code, chosen over the panels; the newest run against the one before it on
-- its scene unless a report or Past results picked them
PAGES["bench/compare"] = function(self)
    local B = benchState(self)
    local CMP = PzoptReportCompare
    if not B.cmp and CMP then B.cmp = CMP.defaultState() end
    local p = { items = {} }
    if not (B.cmp and CMP and PzoptReportDash) then
        table.insert(p.items, { kind = "note", label = CMP and CMP.NO_TWO or "Two runs are needed: run the benchmark twice (Setup)." })
        return p
    end
    local st = B.cmp
    local actions = {
        { label = "SWAP A AND B", key = "x", act = function() st.a, st.b = st.b, st.a end },
        { label = "PASTE A SHARE CODE AS A", key = "y", act = function() self:askShareCode() end },
        { label = function() return B.onlyDiff and "SETUP: EVERY SETTING" or "SETUP: ONLY WHAT DIFFERS" end,
          act = function() self:setOnlyDiff(not B.onlyDiff) end },
    }
    for _, k in ipairs({ "a", "b" }) do
        local sd = st[k]
        if sd.run and not sd.code then
            table.insert(actions, { label = "OPEN " .. string.upper(k) .. "'S REPORT", act = function()
                self:openPage("bench/report", { sd.run, sd.scene })
                B.rep.cfg, B.rep.pass = sd.cfg, sd.pass
            end })
        end
    end
    p.dash = { mod = CMP, state = st, actions = actions, data = CMP.load(st, B.onlyDiff) }
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
        { label = "COMPARE A CODE", act = function() self:askShareCode() end }, -- (the code as A beside this pass as B)
        { label = "PAST RESULTS", act = function() self:openPage("bench/history") end },
        { label = "RUN AGAIN", act = function() self:openPage("bench/setup") end },
    }
    if TAB_LAYOUT then -- (the reports are the sidebar: Compare with the run before this one, the folder)
        actions[4] = { label = "COMPARE WITH PREVIOUS", act = function()
            -- (2026-10-21) this pass as B, the run before it with its scene as A (the compare page)
            local sel = (p.dash and p.dash.data and p.dash.data.sel) or {}
            local prev = PzoptReportCompare and PzoptReportCompare.previous(PzoptBench.history(), R.run, sel.scene)
            if not prev then
                showMessage(self, "This is the only run: run the benchmark again to compare two.")
                return
            end
            self:openPage("bench/compare", { { run = prev, scene = sel.scene, cfg = sel.cfg },
                { run = R.run, scene = sel.scene, cfg = sel.cfg, pass = sel.pass } })
        end }
        table.insert(actions, { label = "SHOW RESULTS FOLDER", confirm = "Open the results folder in your file manager?", act = function() PzoptBench.folder() end })
    end
    if TAB_LAYOUT and PzoptReportDash then -- (2026-10-21) the Grafana run page's panels for one pass (pzopt_mainscreen_report.lua, showDash)
        p.dash = { run = R.run, rep = d, state = R, actions = actions,
            data = PzoptReportDash.load(R.run, R.scene, R.cfg, R.pass) }
        return p
    end
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
            scene = row[2], cfg = row[4],
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
            end,
            reserve = { { "UPDATE NOW" }, { "RESTART GAME" } } }
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
    if TAB_LAYOUT then movedSettings(self, p, "update") end
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
        end, actionsFn = profileRow,
        -- (the room of both choices and RESTART GAME, which comes and goes: the row and the page's column never move)
        reserve = { { string.upper(PzoptCompatApi.names.performance or "MAX PERFORMANCE"),
            string.upper(PzoptCompatApi.names.compatibility or "MAX COMPATIBILITY"), "RESTART GAME" } } })
    if TAB_LAYOUT then movedSettings(self, p, "compat") end
    if #d.jars > 0 then table.insert(p.items, { kind = "section", label = "Java files of this launch" }) end
    for i, jar in ipairs(d.jars) do
        table.insert(p.items, { id = "jar" .. i, kind = "row", label = jar.name, value = PzoptCompatApi.verdict(d, i),
            desc = function() return PzoptCompatApi.jarText(d, i) end })
    end
    return p
end

-- (tab layout) A page whose rows all carry the same controls / actions (the benchmark setup's Passes / cool-down / name and
-- START, the report's buttons, the comparison's "Only what differs") gets them as rows of their own: a chooser or tick as a
-- setting row, a name as a row whose button opens a text box, an action as a row with its button; a keyed action stays
-- the page's X / Y. Setup puts them under its scenes ("Run"), the others above their rows ("Actions").
local function sentence(a)
    return function()
        local t = tostring(val(a.label))
        return string.sub(t, 1, 1) .. string.lower(string.sub(t, 2))
    end
end

local function inlinePage(self, p, id)
    if not TAB_LAYOUT or not p or not p.items then return p end
    local countC, countA = {}, {}
    for _, it in ipairs(p.items) do
        if it.controls and it.kind ~= "setting" and it.kind ~= "master" then countC[it.controls] = (countC[it.controls] or 0) + 1 end
        if it.actions and it.kind ~= "setting" then countA[it.actions] = (countA[it.actions] or 0) + 1 end
    end
    local rows, seen = {}, {}
    for _, it in ipairs(p.items) do
        local cs = it.controls
        if cs and countC[cs] and countC[cs] > 1 then
            it.controls = nil
            if not seen[cs] then
                seen[cs] = true
                for _, ctl in ipairs(cs) do
                    local c = ctl
                    if c.type == "chooser" then
                        table.insert(rows, { id = "ctl:" .. tostring(val(c.label)), kind = "setting", label = c.label,
                            value = function() return tostring(c.get() or "") end, activate = function() c.set(1) end, controls = { c },
                            desc = function() return esc(tostring(val(c.label))) .. ": " .. esc(tostring(c.get() or "")) .. (c.options and ". Click or A for the list of choices; left / right step through them." or ". Click or A for the next value, left for the previous.") end })
                    elseif c.type == "tick" then
                        table.insert(rows, { id = "ctl:" .. tostring(val(c.label)), kind = "setting", label = c.label,
                            value = function() return c.get() and "On" or "Off" end, activate = function() c.set() end, controls = { c },
                            desc = function() return esc(tostring(val(c.label))) .. "." end })
                    elseif c.type == "entry" then
                        table.insert(rows, { id = "ctl:" .. tostring(val(c.label)), kind = "row", label = c.label,
                            value = function() local v = c.get() return (v and v ~= "") and v or "-" end,
                            desc = function() return esc(tostring(val(c.label))) .. ". EDIT opens a text box." end,
                            actions = { { label = "EDIT...", act = function() self:askText(tostring(val(c.label)), c.get() or "", c.set) end } } })
                    end
                end
            end
        end
        local as = it.actions
        if as and countA[as] and countA[as] > 1 then
            it.actions = nil
            if not seen[as] then
                seen[as] = true
                for _, act in ipairs(as) do
                    local a = act
                    if not a.quiet then
                        if a.key and not p[a.key] then p[a.key] = a end
                        table.insert(rows, { id = "act:" .. tostring(val(a.label)), kind = "row", label = sentence(a), actions = { a },
                            desc = function() return esc(tostring(val(a.label))) .. (a.confirm and " (asks first)." or ".") end })
                    end
                end
            end
        end
    end
    if #rows == 0 then return p end
    local bottom = id == "bench/setup"
    local out = {}
    if not bottom then
        table.insert(out, { kind = "section", label = "Actions" })
        for _, r in ipairs(rows) do table.insert(out, r) end
    end
    for _, it in ipairs(p.items) do table.insert(out, it) end
    if bottom then
        table.insert(out, { kind = "section", label = "Run" })
        for _, r in ipairs(rows) do table.insert(out, r) end
    end
    p.items = out
    return p
end

local function pageOf(self, id)
    local a, b, c = split3(id)
    if PAGES[id] then return inlinePage(self, PAGES[id](self), id) end
    if TAB_LAYOUT and b and (a == "compat" or a == "update") then return inlinePage(self, PAGES[a](self), a) end
    if a == "problems" and b then return problemPage(self, tonumber(b)) end
    if TAB_LAYOUT and GROUPS[a] then return tabPage(self, a) end
    if GROUPS[a] and not b then return groupPage(self, a) end
    if GROUPS[a] and b and not c then return catPage(self, a, b, id) end
    if GROUPS[a] and c then return subPage(self, a, b, c, id) end
    return { items = {} }
end

local function selectable(it)
    return it and it.kind ~= "section" and it.kind ~= "note"
end

-- pzopt menu-impeccable prototype (2026-10-19, not verified in game): a row's value shows what a click does to it, a
-- tick box beside On / Off, arrows around a choice; the click lands on that value zone and changes it at once
-- a row's main action when it has nothing of its own to do (a preset's APPLY, the DLSS download): drawn as a button on
-- the row in the tab layout (its detail is read-only there)
local function rowActions(it)
    local acts = {}
    for _, a in ipairs(it.actions or {}) do if not a.quiet then table.insert(acts, a) end end
    if it.actionsFn then for _, a in ipairs(it.actionsFn()) do if not a.quiet then table.insert(acts, a) end end end
    return acts
end

local function firstAction(it)
    return rowActions(it)[1]
end

local function buttonW(a)
    return textW(UIFont.Small, tostring(val(a.label))) + 24
end

local function valueDeco(it)
    if not it then return nil end
    if TAB_LAYOUT and it.kind ~= "setting" and it.kind ~= "master" and (firstAction(it) or it.reserve) then return "button" end
    if TAB_LAYOUT and it.chooser then return it.chooser.shown() and "chooser" or nil end -- (its switch: before its name)
    if TAB_LAYOUT and it.toggle then return nil end
    if (it.kind ~= "setting" and it.kind ~= "master") or not it.activate then return nil end
    local c = it.controls and it.controls[1]
    if c and c.type == "tick" then return "tick" end
    if c and c.type == "chooser" then return "chooser" end
    return nil
end

-- (tab layout) the dropdown a row's value opens: a setting's chooser while it shows, else a page control's chooser
local function rowChooser(it)
    if not (TAB_LAYOUT and it) then return nil end
    if it.chooser then return (it.chooser.options and it.chooser.shown()) and it.chooser or nil end
    local c = it.activate and it.controls and it.controls[1]
    return (c and c.type == "chooser" and c.options) and c or nil
end

local function decoW(deco, it)
    if deco == "tick" then return fontH(UIFont.Small) + 8 end
    if deco == "chooser" then return textW(UIFont.Small, "<  >") + 4 end
    if deco == "button" then
        local w = 0
        for _, a in ipairs(rowActions(it)) do w = w + buttonW(a) + 8 end
        -- `reserve`: the button sets the row can show (labels); it keeps the room of the widest, so a button that comes
        -- and goes (RESTART GAME after a choice) never moves the value column or sends the row to a second line
        for _, set in ipairs(it.reserve or {}) do
            local r = 0
            for _, l in ipairs(set) do r = r + buttonW({ label = l }) + 8 end
            w = math.max(w, r)
        end
        return w + 2
    end
    return 0
end

-- what A does on a list row (the in-place controller prompt)
local function rowVerb(it)
    if not it then return nil end
    if it.verb then return it.verb end
    if it.kind == "node" then return "Open" end
    if it.kind == "group" then return it.open and "Fold" or "Open" end
    if it.toggle then return "Toggle" end
    if rowChooser(it) then return "Choose" end
    if it.activate then
        local c = it.controls and it.controls[1]
        if c and c.type == "chooser" then return "Next" end
        return "Toggle"
    end
    local a = firstAction(it)
    return a and tostring(val(a.label)) or nil
end

-- the game's own controller glyph (Joypad.Texture: Xbox / PlayStation / Steam Deck art, the Controller options' style)
local function glyph(el, tex, x, y, size)
    if tex then el:drawTextureScaled(tex, x, y, size, size, 1, 1, 1, 1) end
end

-- glyph + word pairs drawn left to right from `x`; stops at `maxX`; returns the right end
local function drawPromptsLR(el, list, x, y, maxX)
    local g = fontH(UIFont.Small)
    for _, p in ipairs(list) do
        local w = g + 4 + textW(UIFont.Small, p[2])
        if x + w > maxX then break end
        glyph(el, p[1], x, y, g)
        el:drawText(p[2], x + g + 4, y, 0.85, 0.85, 0.88, 1, UIFont.Small)
        x = x + w + 14
    end
    return x
end

-- glyph + word pairs ending at `right`, kept right of `minX`; returns the left end. The list runs most important first:
-- what does not fit is dropped from its end (2026-10-21: it dropped from the front, so at 1280x720 a row lost its A / Y
-- actions and kept "RS Scroll", and "LT RT Page" lost its LT). A glyph without a word goes with the next pair (LT + RT Page).
local function drawPrompts(el, list, right, y, minX)
    local g = fontH(UIFont.Small)
    local n, used, k = 0, 0, 1
    while k <= #list do
        local j, w = k, 0
        while list[j][2] == "" and j < #list do w = w + g + 4 + 14; j = j + 1 end
        w = w + g + 4 + textW(UIFont.Small, list[j][2])
        if right - used - (used > 0 and 14 or 0) - w < minX then break end
        used, n, k = used + (used > 0 and 14 or 0) + w, j, j + 1
    end
    local x = right
    for i = n, 1, -1 do
        local p = list[i]
        x = x - (g + 4 + textW(UIFont.Small, p[2]))
        glyph(el, p[1], x, y, g)
        el:drawText(p[2], x + g + 4, y, 0.85, 0.85, 0.88, 1, UIFont.Small)
        x = x - 14
    end
    return x
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
    if TAB_LAYOUT then return self:layoutTree() end
    local hS = fontH(UIFont.Small)
    local pad = 12
    local inner = self.width - 8 - 2 * pad
    for _, it in ipairs(self.items) do
        local label = tostring(val(it.label))
        local value = it.value and tostring(val(it.value)) or nil
        if value == "" then value = nil end
        local deco = valueDeco(it)
        if deco ~= "button" and not value then deco = nil end
        local L = { value = value, deco = deco }
        local vw = (value and textW(UIFont.Small, value) or 0) + decoW(deco, it)
        if not selectable(it) then
            -- a heading is drawn in capitals: wrapped as it is drawn (the lower-case width ran past the list)
            L.label = wrap(it.kind == "section" and string.upper(label) or label, UIFont.Small, inner + pad)
        elseif (value or deco) and vw <= inner * (deco == "button" and 0.65 or 0.4) then
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

-- ------------------------------------------------------------------ (tab layout) the list as a tree
-- A group (a subcategory) folds with the arrow before its heading. A setting's switch sits before its name and its value
-- in the page's value column; its modifiers are indented under it (their switches under its name), joined to it by guide
-- lines, and drawn dim while its switch is off (they still change: they apply once it is on). A setting with modifiers
-- folds with the arrow before its switch. Sizes follow the small font: the arrow column, the switch, one indent level.

local function treeGeom()
    local hS = fontH(UIFont.Small)
    local th = math.max(10, hS - 4)
    local tw = math.floor(th * 1.9)
    return { hS = hS, th = th, tw = tw, cw = math.floor(hS * 0.9), ind = tw + 10, base = 4 + 12 }
end

-- where a row's switch (or its name, without one) starts
local function treeX(it, G)
    if it.kind == "group" then return G.base + G.cw end
    if it.depth then return G.base + G.cw + it.depth * G.ind end
    return G.base + 2
end

local function treeLabelX(it, G)
    return treeX(it, G) + (it.toggle and (G.tw + 8) or 0)
end

local function treeDim(it)
    local p = it.treeParent
    while p do
        if p.isOn and not p.isOn() then return true end
        p = p.treeParent
    end
    return false
end

-- a fold arrow in a box of `size` at x, y: pointing down when open, right when folded (1 px rows, no font glyph)
local function drawArrow(el, x, y, size, open, c, a)
    local n = math.max(3, math.floor(size / 3))
    if open then
        local ox, oy = x + math.floor((size - (2 * n - 1)) / 2), y + math.floor((size - n) / 2)
        for k = 0, n - 1 do el:drawRect(ox + k, oy + k, 2 * (n - k) - 1, 1, a, c.r, c.g, c.b) end
    else
        local ox, oy = x + math.floor((size - n) / 2), y + math.floor((size - (2 * n - 1)) / 2)
        for k = 0, n - 1 do el:drawRect(ox + k, oy + k, 1, 2 * (n - k) - 1, a, c.r, c.g, c.b) end
    end
end

-- an on / off switch: green with its knob right when on, a dark track with a grey knob left when off
local function drawToggle(el, x, y, G, on, alpha, hot)
    local w, h = G.tw, G.th
    local k = h - 4
    if on then
        el:drawRect(x, y, w, h, 0.85 * alpha, C_OPT.r, C_OPT.g, C_OPT.b)
        el:drawRect(x + w - k - 2, y + 2, k, k, alpha, 0.96, 0.97, 0.96)
        if hot then el:drawRectBorder(x, y, w, h, 0.9 * alpha, 1, 1, 1) end
    else
        el:drawRect(x, y, w, h, 0.9 * alpha, 0.12, 0.12, 0.14)
        el:drawRectBorder(x, y, w, h, (hot and 0.95 or 0.6) * alpha, C_GREY.r, C_GREY.g, C_GREY.b)
        el:drawRect(x + 2, y + 2, k, k, 0.9 * alpha, C_GREY.r, C_GREY.g, C_GREY.b)
    end
end

-- each row's lines: the name wrapped up to the value column (the column starts after the page's widest name with its
-- indent: no eye travel across a 32:9 screen), the value beside it when it fits, else under the name
function PzoptMenuList:layoutTree()
    -- (a tab's page holds every setting of it: laid out again only when its rows, the width or a value under it changed;
    -- every change goes through refreshPage, which gives the list new rows, and the screen marks it once a second)
    if self.layoutFor == self.items and self.layoutW == self.width and self.layoutH == fontH(UIFont.Small) and not self.layoutDirty then return end
    self.layoutFor, self.layoutW, self.layoutH, self.layoutDirty = self.items, self.width, fontH(UIFont.Small), false
    local G = treeGeom()
    local hS = G.hS
    local right = self.width - 4 - 12
    -- the value column: right after the widest label of a row with a value, but never so far left that a label
    -- wraps while the space past the widest value stays empty (narrow screens)
    -- a dropdown's closed box is as wide as the page's widest choice (its text, the arrow): one even column
    local dropW = 0
    for _, it in ipairs(self.items) do
        local dc = rowChooser(it)
        if dc then
            if not dc.optW then
                dc.optW = 0
                for _, o in ipairs(dc.options()) do dc.optW = math.max(dc.optW, textW(UIFont.Small, o.label)) end
            end
            dropW = math.max(dropW, dc.optW, textW(UIFont.Small, tostring(dc.get() or "")))
        end
    end
    self.dropW = dropW > 0 and (dropW + 16 + hS + 4) or nil
    local col, maxVW = 0, 0
    for _, it in ipairs(self.items) do
        if selectable(it) and it.kind ~= "group" and it.kind ~= "cat" and (it.value or valueDeco(it)) then
            col = math.max(col, treeLabelX(it, G) + textW(UIFont.Small, tostring(val(it.label))))
            local v = it.value and tostring(val(it.value)) or ""
            maxVW = math.max(maxVW, rowChooser(it) and self.dropW or (textW(UIFont.Small, v) + decoW(valueDeco(it), it)))
        end
    end
    local inner = right - G.base
    col = math.max(G.base + math.floor(inner * 0.3), math.min(col + 28, right - maxVW - 12))
    self.valueCol = col
    local minW = textW(UIFont.Small, "MMMMMMMM")
    for _, it in ipairs(self.items) do
        local label = tostring(val(it.label))
        local value = it.value and tostring(val(it.value)) or nil
        if value == "" then value = nil end
        local deco = valueDeco(it)
        if deco ~= "button" and not value then deco = nil end
        local L = { value = value, deco = deco }
        local lx = treeLabelX(it, G)
        -- (a row with a link at its right end, the tab's switch: the label wraps left of the link)
        local linkR = (it.kind ~= "cat" and it.headLink) and (right - textW(UIFont.Small, it.headLink.text) - 24) or right
        local vw = (value and textW(UIFont.Small, value) or 0) + decoW(deco, it)
        if deco == "chooser" and rowChooser(it) then vw = self.dropW end
        if it.kind == "cat" then
            L.label = { label } -- (one line in the medium font, cut to fit when drawn)
            L.deco = nil
            L.h = fontH(UIFont.Medium) + 30
            -- (a narrow list: the counts and the reset on a line of their own under the name)
            local tx = G.base + (it.toggle and (G.tw + 10) or 0)
            local side = (it.headLink and (textW(UIFont.Small, it.headLink.text) + 24) or 24) + textW(UIFont.Small, it.count or "") + 16
            if textW(UIFont.Medium, label) > right - tx - side then
                L.split = true
                L.h = L.h + hS + 2
            end
        elseif it.kind == "group" then
            -- (two lines at most in a narrow list, the second cut to fit when drawn)
            local lines = wrap(string.upper(label), UIFont.Small, right - treeX(it, G) - textW(UIFont.Small, it.count or "") - 16)
            L.label = { lines[1] or "", lines[2] and table.concat(lines, " ", 2) or nil }
            L.h = #L.label * hS + 18
        elseif not selectable(it) then
            L.label = wrap(it.kind == "section" and string.upper(label) or label, UIFont.Small, right - lx)
        elseif (value or deco) and vw <= right - col then
            local room = math.min(col, linkR) - lx - 16
            L.label = wrap(label, UIFont.Small, math.max(minW, room))
            L.inline = true
            -- (a narrow list, a deep row: the name in a column narrower than a word of it, or past two lines; the
            -- control goes on its own line under the name, the name gets the row's width)
            local narrow = #L.label > 2 -- (or a line wider than the room: a word, or the shortest wrap width)
            for _, line in ipairs(L.label) do if textW(UIFont.Small, line) > room then narrow = true end end
            if narrow and deco == "button" and room > 0 then -- (its buttons stay on the line: the name in more, shorter lines)
                local alt = wrap(label, UIFont.Small, room)
                local fits = true
                for _, line in ipairs(alt) do if textW(UIFont.Small, line) > room then fits = false end end
                if fits then L.label = alt end
            elseif narrow and deco ~= "button" and lx + vw <= right then
                L.label = wrap(label, UIFont.Small, math.max(minW, linkR - lx))
                L.under = true
                L.h = 10 + (#L.label + 1) * hS + 8
            end
        else
            L.label = wrap(label, UIFont.Small, math.max(minW, linkR - lx))
            L.valueLines = value and wrap(value, UIFont.Small, math.max(minW, right - lx - 14)) or {}
        end
        if #L.label == 0 then L.label = { "" } end
        L.h = L.h or (10 + (#L.label + (L.valueLines and #L.valueLines or 0)) * hS + (it.kind == "section" and 4 or 0))
        it.L = L
    end
end

-- a heading's link right-aligned at `right` ("Reset to defaults" on a category's heading and the tab's switch, "Go to
-- section" on a search result's category); returns its left end
local function drawHeadLink(el, i, text, y, hot, mx, right)
    local w = textW(UIFont.Small, text)
    local x = right - w
    local on = hot and mx >= x - 6 and mx < right + 6
    local rc = on and C_TEXT or C_GREY
    el:drawText(text, x, y, rc.r, rc.g, rc.b, 1, UIFont.Small)
    if on then el:drawRect(x, y + fontH(UIFont.Small), w, 1, 0.8, rc.r, rc.g, rc.b) end
    el.headResetX[i] = { x - 6, right + 6 }
    return x
end

function PzoptMenuList:renderTree()
    self:layoutItems()
    local s = self.screen
    local c = s.colour or C_TEXT
    local focused = (s.joyfocus or s.forceHints) and s.pane == "master"
    local vis = self:visibleCount()
    local G = treeGeom()
    local hS = G.hS
    local right = self.width - 4 - 12
    local over = self:isMouseOver()
    local mx, my = over and self:getMouseX() or -1, over and self:getMouseY() or -1
    local guide = function(x, y0, h) self:drawRect(x, y0, 1, h, 0.4, C_GREY.r, C_GREY.g, C_GREY.b) end
    local y = 4
    self.rowsY, self.valueX, self.buttonX, self.resetX, self.valueEnd, self.afterX, self.toggleX, self.foldX = {}, {}, {}, {}, {}, {}, {}, {}
    self.headResetX, self.underY = {}, {}
    for row = 0, vis - 1 do
        local i = self.top + row
        local it = self.items[i]
        if not it then break end
        local L = it.L
        self.rowsY[i] = { y, L.h }
        local sel = i == self.selected
        local hot = selectable(it) and my >= y and my < y + L.h
        local ty = it.kind == "group" and (y + 11) or (y + 5) -- (a heading: more room above it than under it)
        if sel then
            self:drawRect(4, y, self.width - 8, L.h, 0.18, c.r, c.g, c.b)
            if focused then
                for k = 0, 2 do self:drawRectBorder(4 + k, y + k, self.width - 8 - 2 * k, L.h - 2 * k, 0.95, c.r, c.g, c.b) end
            end
        end
        if hot then self:drawRect(4, y, self.width - 8, L.h, 0.08, 1, 1, 1) end
        if it.kind == "section" then
            self:drawRect(4, y + L.h - 3, self.width - 8, 1, 0.35, c.r, c.g, c.b)
            for k, line in ipairs(L.label) do self:drawText(line, G.base, y + 7 + (k - 1) * hS, c.r, c.g, c.b, 1, UIFont.Small) end
        elseif it.kind == "note" then
            for k, line in ipairs(L.label) do self:drawText(line, G.base, y + 5 + (k - 1) * hS, C_GREY.r, C_GREY.g, C_GREY.b, 1, UIFont.Small) end
        elseif it.kind == "cat" then
            -- a category's heading: a rule over it, its switch, its name in the medium font; the counts and its reset at
            -- the right end
            local hM = fontH(UIFont.Medium)
            local cy = y + L.h - hM - 8
            if L.split then cy = y + 14 end -- (the name alone on its line, the counts and the reset under it)
            if i > 1 then self:drawRect(4, y + 6, self.width - 8, 1, 0.55, c.r, c.g, c.b) end
            local dim = treeDim(it)
            local tx, on = G.base, true
            if it.toggle then
                local ok, v = pcall(it.toggle.get)
                on = ok and v == true
                local pinned = it.toggle.pinned and it.toggle.pinned()
                drawToggle(self, tx, cy + math.floor((hM - G.th) / 2), G, on, (pinned or dim) and 0.45 or 1, hot and mx >= tx - 4 and mx < tx + G.tw + 4)
                self.toggleX[i] = { tx - 4, tx + G.tw + 4 }
                tx = tx + G.tw + 10
            end
            local ny = cy + math.floor((hM - hS) / 2) -- (the small font level with the name)
            local sy = L.split and (cy + hM + 2) or ny
            local rx = it.headLink and drawHeadLink(self, i, it.headLink.text, sy, hot, mx, right) or (right + 24)
            local cnt = it.count or ""
            local cc = it.changed and { r = 1, g = 0.75, b = 0.3 } or C_DIM
            self:drawTextRight(cnt, rx - 24, sy, cc.r, cc.g, cc.b, 1, UIFont.Small)
            local lc = (on and not dim) and C_TEXT or { r = 0.55, g = 0.55, b = 0.58 }
            local room = L.split and (right - tx) or (rx - 24 - textW(UIFont.Small, cnt) - 16 - tx)
            local name = clipText(UIFont.Medium, L.label[1], room)
            self:drawText(name, tx, cy, lc.r, lc.g, lc.b, 1, UIFont.Medium)
            self.afterX[i] = tx + textW(UIFont.Medium, name)
            self.promptAt = self.promptAt or {}
            self.promptAt[i] = { ny, tx + room }
        elseif it.kind == "group" then
            drawArrow(self, G.base, ty, hS, it.open, c, 1)
            local cnt = it.count or ""
            local lx = treeX(it, G)
            for k, line in ipairs(L.label) do
                self:drawText(clipText(UIFont.Small, line, right - lx - textW(UIFont.Small, cnt) - 16), lx, ty + (k - 1) * hS, c.r, c.g, c.b, 1, UIFont.Small)
            end
            local cc = it.changed and { r = 1, g = 0.75, b = 0.3 } or C_DIM
            self:drawTextRight(cnt, right, ty, cc.r, cc.g, cc.b, 1, UIFont.Small)
            self:drawRect(G.base, y + L.h - 3, right - G.base, 1, 0.35, c.r, c.g, c.b)
        else
            local tx = treeX(it, G)
            local d = it.depth or 0
            local mid = ty + math.floor(hS / 2)
            for l = 1, d do -- the guide lines of the levels above: the parent's to this row, the others through it
                local gx = G.base + G.cw + (l - 1) * G.ind + math.floor(G.tw / 2)
                local last = it.lastFlags and it.lastFlags[l]
                if l == d then
                    guide(gx, y, last and (mid - y + 1) or L.h)
                    self:drawRect(gx, mid, tx - gx - 4, 1, 0.4, C_GREY.r, C_GREY.g, C_GREY.b)
                elseif not last then
                    guide(gx, y, L.h)
                end
            end
            if it.kids then
                drawArrow(self, tx - G.cw, ty, hS, not it.folded, (hot and mx < tx) and C_TEXT or C_GREY, 1)
                self.foldX[i] = { tx - G.cw - 2, tx }
            end
            local dim = treeDim(it)
            if it.toggle then
                local ok, on = pcall(it.toggle.get)
                local pinned = it.toggle.pinned and it.toggle.pinned()
                local tby = ty + math.floor((hS - G.th) / 2)
                drawToggle(self, tx, tby, G, ok and on == true, (pinned or dim) and 0.45 or 1, hot and mx >= tx - 4 and mx < tx + G.tw + 4)
                self.toggleX[i] = { tx - 4, tx + G.tw + 4 }
            end
            if it.kids and not it.folded then -- the line down to the first modifier
                local gy = ty + hS
                guide(tx + math.floor(G.tw / 2), gy, y + L.h - gy)
            end
            local col = (sel or hot) and C_TEXT or { r = 0.8, g = 0.8, b = 0.82 }
            if it.kind == "master" then col = c end
            if dim then col = { r = 0.5, g = 0.5, b = 0.54 } end
            if it.bad then col = { r = 1, g = 0.6, b = 0.5 } end
            local lx = treeLabelX(it, G)
            local ly = ty
            for _, line in ipairs(L.label) do
                self:drawText(line, lx, ly, col.r, col.g, col.b, 1, UIFont.Small)
                ly = ly + hS
            end
            local vc = dim and C_DIM or C_GREY
            local v = L.value
            if not dim then
                if v == "On" or v == "on" then vc = C_OPT elseif v == "Off" or v == "off" then vc = { r = 1, g = 0.55, b = 0.35 }
                elseif v and string.sub(v, 1, 1) == "+" then vc = C_OPT elseif v and string.match(v, "^%-%d") then vc = { r = 1, g = 0.55, b = 0.35 } end
            end
            local vty, vcol = ty, self.valueCol -- (where the control sits: right of the name, or under it)
            if L.under then vty, vcol = ty + #L.label * hS + 4, lx end
            self.underY[i] = L.under and vty or nil
            if L.inline and L.deco == "chooser" and rowChooser(it) then
                -- a dropdown: a box with the value and a down arrow; a click (A) opens the list of choices
                local x0, bw, bh = vcol, self.dropW, hS + 6
                local by = vty - 3
                local open = self.drop and self.drop.i == i
                local pinned = it.toggle and it.toggle.pinned and it.toggle.pinned() or (rowChooser(it).pinned and rowChooser(it).pinned())
                local hotB = hot and mx >= x0 and mx < x0 + bw and not pinned
                self:drawRect(x0, by, bw, bh, 0.9, 0.09, 0.09, 0.11)
                local bc = open and c or ((hotB or (sel and focused)) and C_TEXT or C_GREY)
                self:drawRectBorder(x0, by, bw, bh, (open or hotB) and 1 or ((dim or pinned) and 0.35 or 0.7), bc.r, bc.g, bc.b)
                if v then self:drawText(v, x0 + 8, vty, vc.r, vc.g, vc.b, (pinned and 0.6 or 1), UIFont.Small) end
                local ac = (dim or pinned) and C_DIM or ((hotB or open) and C_TEXT or C_GREY)
                drawArrow(self, x0 + bw - hS - 2, by + 3, hS, true, ac, 1)
                self.valueX[i], self.valueEnd[i] = x0, x0 + bw
                self.boxY = self.boxY or {}
                self.boxY[i] = { by, bh }
                self.afterX[i] = x0 + bw
            elseif L.inline then
                local x = vcol
                local x0 = x
                if L.deco == "chooser" then
                    self:drawText("<", x, vty, C_GREY.r, C_GREY.g, C_GREY.b, 1, UIFont.Small)
                    x = x + textW(UIFont.Small, "< ")
                end
                if v then
                    self:drawText(v, x, vty, vc.r, vc.g, vc.b, 1, UIFont.Small)
                    x = x + textW(UIFont.Small, v)
                end
                if L.deco == "chooser" then
                    self:drawText(" >", x, vty, C_GREY.r, C_GREY.g, C_GREY.b, 1, UIFont.Small)
                    x = x + textW(UIFont.Small, " >")
                end
                if L.deco ~= "button" then
                    self.valueX[i], self.valueEnd[i] = x0, x
                else -- the row's actions as buttons after its value, the first leftmost
                    local bs = {}
                    x = x + (v and 12 or 0)
                    for _, a in ipairs(rowActions(it)) do
                        local bw = buttonW(a)
                        local off = val(a.disabled) -- (2026-10-21) a disabled action: dim, no hover, does nothing (runAction)
                        local hotB = hot and mx >= x and mx < x + bw and not off
                        local bc = off and C_DIM or (a.danger and { r = 1, g = 0.6, b = 0.45 } or c)
                        local tc = off and C_DIM or C_TEXT
                        if hotB then self:drawRect(x, y + 3, bw, L.h - 6, 0.25, bc.r, bc.g, bc.b) end
                        self:drawRectBorder(x, y + 3, bw, L.h - 6, off and 0.5 or 0.8, bc.r, bc.g, bc.b)
                        self:drawText(tostring(val(a.label)), x + 12, ty, tc.r, tc.g, tc.b, 1, UIFont.Small)
                        table.insert(bs, { x, x + bw, a })
                        x = x + bw + 8
                    end
                    self.buttonX[i] = bs
                end
                self.afterX[i] = x
            elseif L.valueLines then
                for _, line in ipairs(L.valueLines) do
                    self:drawText(line, lx + 14, ly, vc.r, vc.g, vc.b, 1, UIFont.Small)
                    ly = ly + hS
                end
            end
        end
        if it.kind ~= "cat" and it.headLink then drawHeadLink(self, i, it.headLink.text, ty, hot, mx, right) end
        if selectable(it) and it.isChanged and it.isChanged() then -- changed from the build's default: a dot, and reset
            self:drawRect(7, ty + math.floor(hS / 2) - 3, 6, 6, 1, 1, 0.75, 0.3)
            local rx = (self.afterX[i] or (treeLabelX(it, G) + textW(UIFont.Small, L.label[#L.label] or ""))) + 18
            local rw = textW(UIFont.Small, "reset")
            local ry = self.underY[i] or ty
            if rx + rw > right + 4 then -- (no room right of the value, a narrow list: at the end of the name's last line)
                rx = treeLabelX(it, G) + textW(UIFont.Small, L.label[#L.label] or "") + 18
                ry = ty + (#L.label - 1) * hS
            end
            local rc = (hot and mx >= rx - 4 and mx < rx + rw + 4) and C_TEXT or C_GREY
            self:drawText("reset", rx, ry, rc.r, rc.g, rc.b, 1, UIFont.Small)
            self.resetX[i] = { rx - 4, rx + rw + 4 }
            if ry == (self.underY[i] or ty) then self.afterX[i] = rx + rw end
        end
        if i == self.selected and s.pane == "master" and s.promptsOn and s:promptsOn() and selectable(it) then
            -- the in-place prompts of the focused row: what A does, the D-pad on a choice, X / Y when the row has them
            local T, BT = Joypad.Texture, Joypad.ButtonTextures
            local list = {}
            if L.deco == "chooser" then table.insert(list, { BT[Joypad.DPadLeft], "Change" }) end
            local verb = rowVerb(it)
            if verb then table.insert(list, { T.AButton, verb }) end
            local xa, ya = s:keyAction("x"), s:keyAction("y")
            if ya and ya ~= firstAction(it) then table.insert(list, { T.YButton, tostring(val(ya.label)) }) end
            if xa then table.insert(list, { T.YButton, "Hold: " .. tostring(val(xa.label)) }) end -- (X is the presets: the second action is a held Y)
            local px = self.afterX[i] or (treeLabelX(it, G) + textW(UIFont.Small, L.label[1] or ""))
            local pa = it.kind == "cat" and self.promptAt and self.promptAt[i]
            local maxX = pa and pa[2] or (self.headResetX[i] and (self.headResetX[i][1] - 12)) or (self.width - 12)
            drawPromptsLR(self, list, px + 22, pa and pa[1] or self.underY[i] or ty, maxX)
        end
        y = y + L.h
    end
    if #self.items > vis then -- where the window is in the list
        local h = self.height - 8
        local barH = math.max(12, math.floor(h * vis / #self.items))
        local barY = 4 + math.floor((h - barH) * (self.top - 1) / math.max(1, #self.items - vis))
        self:drawRect(self.width - 4, barY, 2, barH, 0.6, 1, 1, 1)
    end
    local d = self.drop
    if d then
        if self.items[d.i] ~= d.item or not self.rowsY[d.i] or not self.valueX[d.i] or not rowChooser(d.item) then
            self.drop = nil -- (its row scrolled away, folded, or its switch went off)
        else
            self:renderDrop(d, G, c, mx, my)
        end
    end
end

-- ------------------------------------------------------------------ (tab layout) the dropdown's open list
-- Under the value's box (over it when there is no room below), inside the list: each choice, its note wrapped under it
-- in grey, the current one marked; the mouse or up / down highlights, a click or A picks, B / Escape / a click outside
-- closes it. Longer than the list: it scrolls (wheel, or following the highlight).
function PzoptMenuList:openDrop(i)
    local it = self.items[i]
    local dc = rowChooser(it)
    if not dc or (dc.pinned and dc.pinned()) or (it.toggle and it.toggle.pinned and it.toggle.pinned()) then return false end
    local opts = dc.options()
    if #opts == 0 then return false end
    local G = treeGeom()
    local hS, pad = G.hS, 10
    local right = self.width - 4 - 12
    local w = self.dropW or 0
    local notes = false
    for _, o in ipairs(opts) do
        w = math.max(w, textW(UIFont.Small, o.label) + 2 * pad + 12)
        if o.note then notes = true end
    end
    if notes then w = math.max(w, hS * 24) end
    w = math.min(w, right - G.base)
    local at = 1
    for k, o in ipairs(opts) do
        if o.current then at = k end
        o.noteLines = o.note and wrap(o.note, UIFont.Small, w - 2 * pad - 12) or {}
        o.h = 8 + hS * (1 + #o.noteLines)
    end
    self.drop = { i = i, item = it, spec = dc, opts = opts, at = at, top = 1, w = w, pad = pad, follow = true }
    getSoundManager():playUISound("UIActivateMainMenuItem")
    return true
end

function PzoptMenuList:closeDrop()
    if self.drop then getSoundManager():playUISound("UIHighlightMainMenuItem") end
    self.drop = nil
end

function PzoptMenuList:pickDrop(k)
    local d = self.drop
    self.drop = nil
    local o = d and d.opts[k]
    getSoundManager():playUISound("UIActivateMainMenuItem")
    if o and o.pick and not o.current then
        o.pick()
        self.screen:refreshPage()
    end
end

function PzoptMenuList:moveDrop(dir)
    local d = self.drop
    if not d then return end
    d.at = math.max(1, math.min(#d.opts, d.at + dir))
    d.follow = true
    getSoundManager():playUISound("UIHighlightMainMenuItem")
end

function PzoptMenuList:renderDrop(d, G, c, mx, my)
    local hS, pad = G.hS, d.pad
    local s = self.screen
    local right = self.width - 4 - 12
    local foot = (s.pane == "master" and s.promptsOn and s:promptsOn()) and (hS + 10) or 0
    local total = foot + 2
    for _, o in ipairs(d.opts) do total = total + o.h end
    local h = math.min(total, self.height - 8)
    local B = self.boxY and self.boxY[d.i]
    local bx = self.valueX[d.i]
    local x = math.max(4, math.min(bx, right - d.w))
    local y
    if B and B[1] + B[2] + 2 + h <= self.height - 4 then
        y = B[1] + B[2] + 2
    elseif B and B[1] - 2 - h >= 4 then
        y = B[1] - 2 - h
    else
        y = math.max(4, self.height - 4 - h)
    end
    -- the rows that fit from d.top; the highlight kept in view when it moved by the pad
    local function lastFrom(top)
        local used, last = foot + 2, top - 1
        for k = top, #d.opts do
            if used + d.opts[k].h > h then break end
            used, last = used + d.opts[k].h, k
        end
        return last
    end
    if d.follow then
        if d.at < d.top then d.top = d.at end
        while lastFrom(d.top) < d.at and d.top < d.at do d.top = d.top + 1 end
        d.follow = false
    end
    local last = lastFrom(d.top)
    d.lastVis = last
    self:drawRect(x, y, d.w, h, 0.98, 0.06, 0.06, 0.075)
    self:drawRectBorder(x, y, d.w, h, 1, c.r, c.g, c.b)
    d.rect, d.hit = { x, y, d.w, h }, {}
    local moved = d.mx ~= mx or d.my ~= my
    d.mx, d.my = mx, my
    local ry = y + 1
    for k = d.top, last do
        local o = d.opts[k]
        if moved and mx >= x and mx < x + d.w and my >= ry and my < ry + o.h then d.at = k end
        if k == d.at then self:drawRect(x + 1, ry, d.w - 2, o.h, 0.22, c.r, c.g, c.b) end
        local tc = o.current and c or C_TEXT
        if o.current then self:drawRect(x + pad, ry + 4 + math.floor(hS / 2) - 2, 5, 5, 1, c.r, c.g, c.b) end
        self:drawText(o.label, x + pad + 12, ry + 4, tc.r, tc.g, tc.b, 1, UIFont.Small)
        for n, line in ipairs(o.noteLines) do
            self:drawText(line, x + pad + 12, ry + 4 + n * hS, C_GREY.r, C_GREY.g, C_GREY.b, 1, UIFont.Small)
        end
        table.insert(d.hit, { k, ry, ry + o.h })
        ry = ry + o.h
    end
    if d.top > 1 or last < #d.opts then -- where the window is in the choices
        local ih = h - foot - 4
        local barH = math.max(10, math.floor(ih * (last - d.top + 1) / #d.opts))
        local barY = y + 2 + math.floor((ih - barH) * (d.top - 1) / math.max(1, #d.opts - (last - d.top + 1)))
        self:drawRect(x + d.w - 4, barY, 2, barH, 0.6, 1, 1, 1)
    end
    if foot > 0 then
        local fy = y + h - foot
        self:drawRect(x + 1, fy, d.w - 2, 1, 0.35, c.r, c.g, c.b)
        local T = Joypad.Texture
        drawPromptsLR(self, { { T.AButton, "Choose" }, { T.BButton, "Close" } }, x + pad, fy + 5, x + d.w - pad)
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
    if i > 1 and (self.items[i - 1].kind == "section" or self.items[i - 1].kind == "group") then want = i - 1 end
    if want < self.top then self.top = want end
    while i >= self.top + self:visibleCount() and self.top < i do self.top = self.top + 1 end
end

function PzoptMenuList:prerender()
    ISPanel.prerender(self)
    if TAB_LAYOUT then return self:renderTree() end
    self:layoutItems()
    local s = self.screen
    local c = s.colour or C_TEXT
    local focused = (s.joyfocus or s.forceHints) and s.pane == "master"
    local vis = self:visibleCount()
    local pad = 12
    local hS = fontH(UIFont.Small)
    local y = 4
    local my = self:isMouseOver() and self:getMouseY() or -1
    self.rowsY, self.valueX, self.buttonX, self.resetX, self.valueEnd, self.afterX = {}, {}, {}, {}, {}, {}
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
                if focused then
                    for k = 0, 2 do self:drawRectBorder(4 + k, y + k, self.width - 8 - 2 * k, L.h - 2 * k, 0.95, c.r, c.g, c.b) end
                end
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
                local right = self.width - 4 - pad + 4
                if L.deco == "button" then -- the row's actions as buttons at its end (tab layout), the first rightmost
                    local bs = {}
                    for _, a in ipairs(rowActions(it)) do
                        local bw = buttonW(a)
                        local bx = right - bw
                        local off = val(a.disabled) -- (2026-10-21) a disabled action: dim, no hover, does nothing (runAction)
                        local hotB = hot and self:getMouseX() >= bx and self:getMouseX() < right and not off
                        local bc = off and C_DIM or (a.danger and { r = 1, g = 0.6, b = 0.45 } or c)
                        local tc = off and C_DIM or C_TEXT
                        if hotB then self:drawRect(bx, y + 3, bw, L.h - 6, 0.25, bc.r, bc.g, bc.b) end
                        self:drawRectBorder(bx, y + 3, bw, L.h - 6, off and 0.5 or 0.8, bc.r, bc.g, bc.b)
                        self:drawText(tostring(val(a.label)), bx + 12, y + 5, tc.r, tc.g, tc.b, 1, UIFont.Small)
                        table.insert(bs, { bx, right, a })
                        right = bx - 8
                    end
                    bs.left = right
                    self.buttonX[i] = bs
                    right = right - 4
                end
                if L.deco == "chooser" then
                    local aw = textW(UIFont.Small, ">")
                    self:drawText(">", right - aw, y + 5, C_GREY.r, C_GREY.g, C_GREY.b, 1, UIFont.Small)
                    right = right - aw - textW(UIFont.Small, " ")
                end
                if v then self:drawTextRight(v, right, y + 5, vc.r, vc.g, vc.b, 1, UIFont.Small) end
                local vx = right - (v and textW(UIFont.Small, v) or 0)
                if L.deco == "chooser" then
                    self:drawText("<", vx - textW(UIFont.Small, "< "), y + 5, C_GREY.r, C_GREY.g, C_GREY.b, 1, UIFont.Small)
                elseif L.deco == "tick" then -- a tick box left of On / Off, filled when on
                    local bs = hS - 4
                    local bx, by = vx - bs - 8, y + 5 + 2
                    self:drawRectBorder(bx, by, bs, bs, 0.9, vc.r, vc.g, vc.b)
                    local ok, on = pcall(it.controls[1].get)
                    if ok and on == true then self:drawRect(bx + 3, by + 3, bs - 6, bs - 6, 0.9, vc.r, vc.g, vc.b) end
                end
                if L.deco ~= "button" then self.valueX[i] = vx - decoW(L.deco, it) end
            elseif L.valueLines then
                for _, line in ipairs(L.valueLines) do
                    self:drawText(line, 4 + pad + 16, ly, vc.r, vc.g, vc.b, 1, UIFont.Small)
                    ly = ly + hS
                end
            end
        end
        if selectable(it) and it.isChanged and it.isChanged() then -- changed from the build's default: a dot, and reset
            self:drawRect(10, y + 5 + math.floor(hS / 2) - 3, 6, 6, 1, 1, 0.75, 0.3)
        end
        if i == self.selected and s.pane == "master" and s.promptsOn and s:promptsOn() and selectable(it) then
            -- the in-place prompts of the focused row: what A does, the D-pad on a choice, X / Y when the row has them
            local T, BT = Joypad.Texture, Joypad.ButtonTextures
            local list = {}
            local c1 = it.controls and it.controls[1]
            if it.activate and c1 and c1.type == "chooser" then table.insert(list, { BT[Joypad.DPadLeft], "Change" }) end
            local verb = rowVerb(it)
            if verb then table.insert(list, { T.AButton, verb }) end
            local xa, ya = s:keyAction("x"), s:keyAction("y")
            if ya and ya ~= firstAction(it) then table.insert(list, { T.YButton, tostring(val(ya.label)) }) end
            if xa then table.insert(list, { T.YButton, "Hold: " .. tostring(val(xa.label)) }) end -- (X is the presets: the second action is a held Y)
            local right = (self.resetX[i] and self.resetX[i][1]) or (self.buttonX[i] and self.buttonX[i].left) or self.valueX[i]
                or (self.width - pad)
            drawPrompts(self, list, right - 12, y + 5, 4 + pad + 2 + textW(UIFont.Small, L.label[1] or "") + 16)
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

-- a click selects the row and shows its detail; a click on the selected row opens it (a node) or acts (a switch, a
-- tick); a click on a setting's value (its tick box, its arrows) changes it at once. Tab layout: a click on a group's
-- heading folds or opens it, on a fold arrow folds the setting's modifiers, on a switch flips it; a second click on a
-- setting does nothing (its switch and value have their own places), only the mouse's position never selects.
function PzoptMenuList:onMouseUp(x, y)
    local d = self.drop
    if d then -- an open dropdown takes the click: a choice picks it, anywhere else closes the list
        self.pressed = nil
        local R = d.rect
        if R and x >= R[1] and x < R[1] + R[3] and y >= R[2] and y < R[2] + R[4] then
            for _, hit in ipairs(d.hit or {}) do
                if y >= hit[2] and y < hit[3] then self:pickDrop(hit[1]) return end
            end
            return
        end
        self:closeDrop()
        return
    end
    local i = self:rowAt(y)
    if not i or i ~= self.pressed then self.pressed = nil return end
    self.pressed = nil
    if not selectable(self.items[i]) then return end
    local s = self.screen
    if TAB_LAYOUT then
        local it = self.items[i]
        local fz, tz = self.foldX and self.foldX[i], self.toggleX and self.toggleX[i]
        local hz = self.headResetX and self.headResetX[i]
        if hz and x >= hz[1] and x < hz[2] and it.headLink then
            self:setSelected(i)
            getSoundManager():playUISound("UIActivateMainMenuItem")
            s:runAction(it.headLink.action)
            return
        end
        local act = (it.kind == "group" and it.activate) or (fz and x >= fz[1] and x < fz[2] and it.fold)
            or (tz and x >= tz[1] and x < tz[2] and it.toggle and it.toggle.set) or nil
        if act then
            self:setSelected(i)
            getSoundManager():playUISound("UIActivateMainMenuItem")
            act()
            s:refreshPage()
            return
        end
    end
    local rz, bz = self.resetX and self.resetX[i], self.buttonX and self.buttonX[i]
    if rz and x >= rz[1] and x < rz[2] and self.items[i].reset then
        self:setSelected(i)
        getSoundManager():playUISound("UIActivateMainMenuItem")
        self.items[i].reset()
        s:refreshPage()
        return
    end
    for _, b in ipairs(bz or {}) do
        if x >= b[1] and x < b[2] then
            self:setSelected(i)
            s:showDetail()
            getSoundManager():playUISound("UIActivateMainMenuItem")
            s:runAction(b[3])
            return
        end
    end
    local vx = self.valueX and self.valueX[i]
    if vx and x >= vx and x < ((self.valueEnd and self.valueEnd[i]) or self.width) and rowChooser(self.items[i]) then
        self:setSelected(i)
        s:showDetail()
        self:openDrop(i)
        return
    end
    if vx and x >= vx - 8 and x < ((self.valueEnd and self.valueEnd[i]) or self.width) + 8 and self.items[i].activate then
        self:setSelected(i)
        local it = self.items[i]
        local c = (TAB_LAYOUT and it.chooser) or (it.controls and it.controls[1])
        getSoundManager():playUISound("UIActivateMainMenuItem")
        if c and c.type == "chooser" and x < vx + textW(UIFont.Small, "< ") then -- the left arrow steps back
            c.set(-1)
        elseif c and c.set then -- (the control itself: on a search result the row opens its page instead)
            c.set(1)
        else
            it.activate()
        end
        s:refreshPage()
        return
    end
    if i == self.selected then
        local it = self.items[i]
        if not (TAB_LAYOUT and (it.toggle or it.chooser) and not it.verb) then s:activateItem(i) end
    else
        self:setSelected(i)
        s:showDetail()
        getSoundManager():playUISound("UIHighlightMainMenuItem")
    end
end

function PzoptMenuList:onMouseUpOutside(x, y)
    self.pressed = nil
    self.drop = nil
end

function PzoptMenuList:onMouseWheel(del)
    local d = self.drop
    local R = d and d.rect
    if R then -- over the open list: it scrolls; elsewhere the page scrolls and the list follows its box
        local mx, my = self:getMouseX(), self:getMouseY()
        if mx >= R[1] and mx < R[1] + R[3] and my >= R[2] and my < R[2] + R[4] then
            local vis = (d.lastVis or #d.opts) - d.top + 1
            d.top = math.max(1, math.min(d.top + (del > 0 and 1 or -1), math.max(1, #d.opts - vis + 1)))
            return true
        end
    end
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
    elseif self.spec.type == "heading" then
        -- (2026-10-21) a line of coloured parts in the medium font over the controls that follow it (the compare page's
        -- "Run A" over A's dropdowns); not a stop of the cursor
        local hM = fontH(UIFont.Medium)
        local x, my = 0, math.floor((self.height - hM) / 2)
        for _, sg in ipairs(val(self.spec.segs) or {}) do
            local t = clipText(UIFont.Medium, tostring(sg[1]), self.width - x)
            local sc = sg[2] or C_TEXT
            self:drawText(t, x, my, sc.r, sc.g, sc.b, 1, UIFont.Medium)
            x = x + textW(UIFont.Medium, t)
            if x >= self.width then break end
        end
        return
    elseif self.spec.type == "rule" then -- (2026-10-21) the divider under a group of controls
        self:drawRect(0, math.floor(self.height / 2), self.width, 1, 0.8, SIDE_BORDER.r, SIDE_BORDER.g, SIDE_BORDER.b)
        return
    elseif self.spec.type == "chooser" and self.spec.options then
        -- (2026-10-21) a dropdown as on the settings rows: the label, a box with the value and a down arrow; a click (A)
        -- opens the list of choices under it (the preset list's popup), left / right still step it
        local v = tostring(self.spec.get() or "")
        local lw = textW(UIFont.Small, label) + 16
        self:drawText(label, 0, ty, C_GREY.r, C_GREY.g, C_GREY.b, 1, UIFont.Small)
        local bx, bw = lw, self.width - lw
        local open = s:ctrlDropOpen(self)
        self:drawRect(bx, 0, bw, self.height, 0.9, 0.09, 0.09, 0.11)
        local bc = open and c or ((hot or focused) and C_TEXT or C_GREY)
        self:drawRectBorder(bx, 0, bw, self.height, (open or hot or focused) and 1 or 0.7, bc.r, bc.g, bc.b)
        self:drawText(clipText(UIFont.Small, v, bw - 16 - hS - 6), bx + 8, ty, 1, 1, 1, 1, UIFont.Small)
        drawArrow(self, bx + bw - hS - 4, ty - 2, hS, true, (hot or open) and C_TEXT or C_GREY, 1)
        if focused then self:drawRectBorder(bx + 1, 1, bw - 2, self.height - 2, 0.95, c.r, c.g, c.b) end
        return
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
    if spec.type == "heading" or spec.type == "rule" then return end
    if spec.type == "chooser" and spec.options then -- (a click on the box while its list is open closes it)
        local s = self.screen
        if s:ctrlDropOpen(self) then s:closePresets()
        elseif x >= textW(UIFont.Small, tostring(val(spec.label))) + 16 and getTimestampMs() - (s.presetClosedAt or 0) > 150 then
            s:openCtrlDrop(self)
        end
        return
    end
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
    o.backgroundColor = TAB_LAYOUT and { r = 0.04, g = 0.04, b = 0.06, a = 0.85 } or { r = SIDE_BG.r, g = SIDE_BG.g, b = SIDE_BG.b, a = 1 }
    o.borderColor = { r = SIDE_BORDER.r, g = SIDE_BORDER.g, b = SIDE_BORDER.b, a = 1 }
    o.rows = {}
    return o
end

-- (tab layout) the sidebar drawn as the page's tree (maintainer, 2026-10-20): its colours and highlight, a category per top
-- row with its fold arrow, its subcategories under it on guide lines; the before / after clips switch under a divider at
-- the foot. A click on an arrow (the D-pad's left) folds or opens a category.
local SIDE_FOLD = {}
-- (tab layout) the sidebar hidden by the bar's switch: the page takes its width (kept until the game closes, like the view)
local SIDE_HIDDEN = false

-- the sidebar row of the page shown: a report's row (its run's report, the list on no scene of it or the report folded),
-- a scene's leaf (the list on that scene), a section's leaf under it (the row at the top of the run page)
local function sideRowIs(s, r)
    if r.run then
        if not (s.selected == "bench/report" and s.bench ~= nil and s.bench.rep ~= nil and s.bench.rep.run == r.run) then return false end
        if r.section then
            local p = s.detailText
            return p ~= nil and p.dash ~= nil and p.dash.run == r.run and PzoptReportDash.currentRow(p) == r.section
        end
        local cur = s.list and s.list.items and s.list.items[s.list.selected or 0]
        local scene = cur and cur.scene
        if not cur and s.page and s.page.dash then scene = s.bench.rep.scene end -- (the run page: its Scene chooser)
        if r.scene then return scene == r.scene end
        return scene == nil or r.folded == true
    end
    if r.section then -- (a section's leaf of another page of panels: the compare page's)
        local p = s.detailText
        return r.id == s.selected and p ~= nil and p.dash ~= nil and PzoptReportDash.currentRow(p) == r.section
    end
    return r.id == s.selected
end

local function sideGeom()
    local G = treeGeom()
    local x0 = G.base - 4
    return G, x0, { [0] = x0 + G.cw, [1] = x0 + G.cw + 18, [2] = x0 + G.cw + 36 }
end

function PzoptMenuSidebar:renderTabTree()
    local s = self.screen
    local G, x0, labelX = sideGeom()
    local hS, pad = G.hS, 12
    local focusRow = (s.joyfocus or s.forceHints) and s.pane == "side" and s.sideIndex or nil
    local path = {}
    for _, n in ipairs(pathOf(s.selected)) do path[n] = true end
    local over = self:isMouseOver()
    local mx, my = over and self:getMouseX() or -1, over and self:getMouseY() or -1
    local function guide(x, y0, h) self:drawRect(x, y0, 1, h, 0.4, C_GREY.r, C_GREY.g, C_GREY.b) end
    local gx = x0 + math.floor(hS / 2)
    local rows = self.rows
    for i, r in ipairs(rows) do
      if not r.hidden and r.head then -- a heading (Reports): its name and count over a rule, never selected
        local c = colourOf(r.id)
        local hy = r.y + r.h - hS - 6
        if r.note then
            self:drawText(clipText(UIFont.Small, r.label, self.width - x0 - pad), x0, hy, C_DIM.r, C_DIM.g, C_DIM.b, 1, UIFont.Small)
        else
            self:drawText(string.upper(r.label), x0, hy, c.r, c.g, c.b, 1, UIFont.Small)
            if r.count then self:drawTextRight(r.count, self.width - pad, hy, C_DIM.r, C_DIM.g, C_DIM.b, 1, UIFont.Small) end
            self:drawRect(4, r.y + r.h - 3, self.width - 8, 1, 0.35, c.r, c.g, c.b)
        end
      elseif not r.hidden then
        local c = colourOf(r.id)
        local sel = sideRowIs(s, r) and not r.secs -- (a scene with its sections under it: the lit one is the section)
        local hot = my >= r.y and my < r.y + r.h
        if sel then self:drawRect(4, r.y, self.width - 8, r.h, 0.18, c.r, c.g, c.b) end
        if hot then self:drawRect(4, r.y, self.width - 8, r.h, 0.08, 1, 1, 1) end
        if focusRow == i then
            for k = 0, 2 do self:drawRectBorder(4 + k, r.y + k, self.width - 8 - 2 * k, r.h - 2 * k, 0.95, c.r, c.g, c.b) end
        end
        local ty = r.y + 5
        local mid = ty + math.floor(hS / 2)
        local x = labelX[math.min(r.depth, 2)]
        if r.depth > 0 then -- the guides from the category (and a scene) down to this row, a tick into it
            local gxs = { gx, labelX[1] + 4 }
            local function more(d) -- another row at depth d further down, before the tree goes back above d
                for k = i + 1, #rows do
                    local q = rows[k]
                    if q.hidden or q.head or q.depth < d then return false end
                    if q.depth == d then return true end
                end
                return false
            end
            for d = 1, r.depth - 1 do
                if more(d) then guide(gxs[d], r.y - 1, r.h + 1) end
            end
            local g = gxs[r.depth] or gx
            guide(g, r.y - 1, more(r.depth) and (r.h + 1) or (mid - r.y + 2))
            self:drawRect(g, mid, x - g - 4, 1, 0.4, C_GREY.r, C_GREY.g, C_GREY.b)
        end
        if r.secs then guide(r.depth == 0 and gx or (labelX[1] + 4), ty + hS, r.y + r.h - ty - hS) end
        if r.kids then
            drawArrow(self, x0, ty, hS, not r.folded, (hot and mx < x) and C_TEXT or C_GREY, 1)
            if not r.folded then guide(gx, ty + hS, r.y + r.h - ty - hS) end
        end
        local off = r.count == "off"
        local col = (sel or hot or (path[r.id] and not r.run) or (r.secs and sideRowIs(s, r))) and C_TEXT or { r = 0.8, g = 0.8, b = 0.82 }
        if off then col = { r = 0.55, g = 0.55, b = 0.58 } end
        local count = r.count
        if focusRow == i and s:promptsOn() then count = nil end -- (the row's A prompt takes the count's place)
        local countW = count and (textW(UIFont.Small, count) + pad) or 0
        if r.lines then
            for n, line in ipairs(r.lines) do self:drawText(line, x, ty + (n - 1) * hS, col.r, col.g, col.b, 1, UIFont.Small) end
        else
            self:drawText(clipText(UIFont.Small, r.label, self.width - x - pad - countW), x, ty, col.r, col.g, col.b, 1, UIFont.Small)
        end
        if count then
            local cc = off and C_STOCK or C_DIM
            self:drawTextRight(count, self.width - pad, ty, cc.r, cc.g, cc.b, 1, UIFont.Small)
        end
        if focusRow == i and s:promptsOn() then -- in place: A (or right) opens it in the list
            self:drawRect(self.width - pad - hS - 4 - textW(UIFont.Small, "Open") - 6, r.y + 2, hS + 10 + textW(UIFont.Small, "Open"), r.h - 4, 0.9, 0.04, 0.04, 0.06)
            drawPrompts(self, { { Joypad.Texture.AButton, "Open" } }, self.width - pad, ty, x)
        end
      end
    end
    if s.sideFirst and s.sideFirst > 1 then -- more rows above / below the window
        self:drawTextCentre("...", self.width / 2, s.sideTop - hS + 2, C_DIM.r, C_DIM.g, C_DIM.b, 1, UIFont.Small)
    end
    -- the foot: a divider, the before / after clips switch
    local y0 = self.height - s.clipsH
    self:drawRect(4, y0, self.width - 8, 1, 0.55, C_GREY.r, C_GREY.g, C_GREY.b)
    local ry, rh = y0 + 6, hS + 10
    self.clipsY = { ry, ry + rh }
    local hot = my >= ry and my < ry + rh
    local focus = focusRow ~= nil and focusRow == s:sideClipsIndex()
    if hot then self:drawRect(4, ry, self.width - 8, rh, 0.08, 1, 1, 1) end
    if focus then
        for k = 0, 2 do self:drawRectBorder(4 + k, ry + k, self.width - 8 - 2 * k, rh - 2 * k, 0.95, C_TEXT.r, C_TEXT.g, C_TEXT.b) end
    end
    local tx = self.width - pad - G.tw
    drawToggle(self, tx, ry + math.floor((rh - G.th) / 2), G, clipsOn(), 1, hot)
    local lc = hot and C_TEXT or { r = 0.8, g = 0.8, b = 0.82 }
    self:drawText(clipText(UIFont.Small, "Before / after clips", tx - x0 - 8), x0, ry + 5, lc.r, lc.g, lc.b, 1, UIFont.Small)
end

function PzoptMenuSidebar:prerender()
    ISPanel.prerender(self)
    if TAB_LAYOUT then return self:renderTabTree() end
    local s = self.screen
    local pad = 12
    self:drawText(ITEM_TEXT, pad, s.sidePad, 1, 1, 1, 1, UIFont.Medium)

    local focusRow = (s.joyfocus or s.forceHints) and s.pane == "side" and s.sideIndex or nil
    local path = {}
    for _, n in ipairs(pathOf(s.selected)) do path[n] = true end
    for i, r in ipairs(self.rows) do
      if not r.hidden then
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
        if focusRow == i then
            for k = 0, 2 do self:drawRectBorder(4 + k, r.y + k, self.width - 8 - 2 * k, r.h - 2 * k, 0.95, c.r, c.g, c.b) end
        end
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
        if focusRow == i and s:promptsOn() then -- in place: A (or right) opens it in the list
            local g = fontH(UIFont.Small)
            self:drawRect(self.width - pad - g - 4 - textW(UIFont.Small, "Open") - 6, r.y + 2, g + 10 + textW(UIFont.Small, "Open"), r.h - 4, 0.9, SIDE_BG.r, SIDE_BG.g, SIDE_BG.b)
            drawPrompts(self, { { Joypad.Texture.AButton, "Open" } }, self.width - pad, r.y + 5, x)
        end
      end
    end
    if TAB_LAYOUT and s.sideFirst and s.sideFirst > 1 then -- more rows above / below the window
        self:drawTextCentre("...", self.width / 2, s.sideTop - fontH(UIFont.Small) + 2, C_DIM.r, C_DIM.g, C_DIM.b, 1, UIFont.Small)
    end
end

function PzoptMenuSidebar:onMouseWheel(del)
    local s = self.screen
    if not TAB_LAYOUT then return false end
    s.sideFirst = (s.sideFirst or 1) + (del > 0 and 3 or -3)
    s.sideFirstFor = s.selected .. (s.selected == "bench/report" and s.bench and s.bench.rep and s.bench.rep.run or "") -- (the window stays where the wheel put it until the selection moves)
    self.rows = s:sideRows()
    return true
end

function PzoptMenuSidebar:rowAt(y)
    for i, r in ipairs(self.rows) do
        if not r.hidden and y >= r.y and y < r.y + r.h then return i end
    end
    return nil
end

function PzoptMenuSidebar:onMouseDown(x, y)
    self.pressed = self:rowAt(y)
    if TAB_LAYOUT then
        if not self.pressed and self.clipsY and y >= self.clipsY[1] and y < self.clipsY[2] then self.pressed = "clips" end
        return true
    end
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
    if self.pressed == "clips" then
        self.pressed = nil
        if self.clipsY and y >= self.clipsY[1] and y < self.clipsY[2] then s:toggleClips() end
        return
    end
    if not i or i ~= self.pressed then self.pressed = nil return end
    self.pressed = nil
    if TAB_LAYOUT and self.rows[i].kids and x < ({ sideGeom() })[3][0] then -- its arrow: fold / open
        s:foldSide(i)
        return
    end
    if self.rows[i].head then return end
    if self.rows[i].open then
        self.rows[i].open()
        getSoundManager():playUISound("UIActivateMainMenuItem")
        return
    end
    s:selectNode(self.rows[i].id, "side")
    getSoundManager():playUISound("UIActivateMainMenuItem")
end

function PzoptMenuSidebar:onMouseUpOutside(x, y)
    self.pressed = nil
end

-- ------------------------------------------------------------------ (tab layout) the preset box in the bar under the page
-- "Preset:" and a dropdown (maintainer, 2026-10-20): the built-in presets, the ones saved on this PC
-- (Zomboid/Lua/pzopt-presets.ini), "Save current settings as..." and, while a saved one is in use, its delete. The box
-- names the preset the settings match: a saved one, else the one applied last while the settings stay as it left them,
-- every setting at its default = Recommended, else Custom.
local PRESET_LAST = nil -- { title, text, saved }: the preset applied, loaded or saved last this session, the settings it left

local function presetShort(title)
    return string.match(title, "^(.-) %(") or title
end

local function firstSentence(t)
    t = tostring(t or "")
    return string.match(t, "^(.-[%.!?])%s") or t
end

local PzoptPresetCard = ISPanel:derive("PzoptPresetCard")

-- (tab layout) "Preset:" and its box in the bar under the page (maintainer, 2026-10-20: the preset, SAVE... and the
-- tools moved from a card over the sidebar to BACK's line); the box is as wide as "Recommended" with A's prompt
local function presetBoxW()
    local hS = fontH(UIFont.Small)
    return math.max(150, textW(UIFont.Small, "Recommended") + 16 + hS + 6 + hS + 10 + textW(UIFont.Small, "Choose"))
end

function PzoptPresetCard:new(x, y, h, screen)
    local o = ISPanel:new(x, y, textW(UIFont.Small, "Preset:") + 8 + presetBoxW(), h)
    setmetatable(o, self)
    self.__index = self
    o.screen = screen
    o.backgroundColor = { r = 0, g = 0, b = 0, a = 0 }
    o.borderColor = { r = 0, g = 0, b = 0, a = 0 }
    return o
end

-- the dropdown's box: right of "Preset:", the bar's height
function PzoptPresetCard:boxRect()
    local bw = presetBoxW()
    return self.width - bw, 0, bw, self.height
end

function PzoptPresetCard:prerender()
    local s, hS = self.screen, fontH(UIFont.Small)
    local bx, by, bw, bh = self:boxRect()
    local ty = by + math.floor((bh - hS) / 2)
    self:drawText("Preset:", 0, ty, C_GREY.r, C_GREY.g, C_GREY.b, 1, UIFont.Small)
    local open = s:presetOpen() and not s:ctrlDropOpen()
    local hot = self:isMouseOver() and self:getMouseX() >= bx and self:getMouseX() < bx + bw
        and self:getMouseY() >= by and self:getMouseY() < by + bh
    local focus = (s.joyfocus or s.forceHints) and s:barFocus() == "preset"
    self:drawRect(bx, by, bw, bh, 0.9, 0.09, 0.09, 0.11)
    local bc = (open or hot or focus) and C_TEXT or C_GREY
    self:drawRectBorder(bx, by, bw, bh, (open or hot or focus) and 1 or 0.7, bc.r, bc.g, bc.b)
    if focus then
        for k = 1, 2 do self:drawRectBorder(bx + k, by + k, bw - 2 * k, bh - 2 * k, 0.95, bc.r, bc.g, bc.b) end
    end
    local name = s.presetName or "Custom"
    local nc = s.presetName and C_TEXT or C_GREY
    local room = bw - 16 - hS - 6
    local promptW = hS + 10 + textW(UIFont.Small, "Choose")
    -- in place: A opens it (while the name still fits beside it)
    if focus and s:promptsOn() and not open and room - promptW >= textW(UIFont.Small, name) then
        room = room - promptW
        drawPrompts(self, { { Joypad.Texture.AButton, "Choose" } }, bx + bw - hS - 8, ty, bx + 8)
    end
    local nx = bx + 8
    if s:promptsOn() and not open then -- (2026-10-21) X opens the list from anywhere: its glyph in the box
        glyph(self, Joypad.Texture.XButton, nx, ty, hS)
        nx, room = nx + hS + 5, room - hS - 5
    end
    self:drawText(clipText(UIFont.Small, name, room), nx, ty, nc.r, nc.g, nc.b, 1, UIFont.Small)
    drawArrow(self, bx + bw - hS - 2, ty - 2, hS, true, (hot or open) and C_TEXT or C_GREY, 1)
    -- UNINSTALL only where it can run (the main menu, an install it can remove): checked once a second
    local now = getTimestampMs()
    local ub = s.cardButtons and s.cardButtons.uninstall
    if ub and PzoptSettings and now >= (self.nextCheck or 0) then
        self.nextCheck = now + 1000
        local why = PzoptSettings.uninstallUnavailable()
        ub:setEnable(why == "")
        ub:setTooltip(PzoptSettings.UNINSTALL_TIP .. (why ~= "" and (" Not available now: " .. why .. ".") or ""))
    end
end

function PzoptPresetCard:onMouseDown(x, y)
    return true
end

function PzoptPresetCard:onMouseUp(x, y)
    local s = self.screen
    local bx, by, bw, bh = self:boxRect()
    local inBox = x >= bx and x < bx + bw and y >= by and y < by + bh
    -- (a click on the box while the list is open closes it: the list's click-outside may have done it a moment ago)
    if s:presetOpen() then s:closePresets()
    elseif inBox and getTimestampMs() - (s.presetClosedAt or 0) > 150 then s:openPresets() end
    return true
end

-- the open list: section headings, the presets (the current one marked, a line of what each does), the actions
local PzoptPresetPop = ISPanel:derive("PzoptPresetPop")

function PzoptPresetPop:new(screen)
    local o = ISPanel:new(0, 0, 10, 10)
    setmetatable(o, self)
    self.__index = self
    o.screen = screen
    o.backgroundColor = { r = 0, g = 0, b = 0, a = 0 }
    o.borderColor = { r = 0, g = 0, b = 0, a = 0 }
    o.entries, o.at, o.top = {}, 1, 1
    return o
end

function PzoptPresetPop:measure()
    local hS, pad = fontH(UIFont.Small), 10
    local tw = self.width - 2 * pad - 12
    for _, e in ipairs(self.entries) do
        if e.head then e.h = hS + 12
        elseif e.rule then e.h = 9
        elseif e.dim then
            e.lines = wrap(e.dim, UIFont.Small, tw)
            e.h = 6 + hS * #e.lines
        else
            e.noteLines = e.note and wrap(e.note, UIFont.Small, tw) or {}
            e.h = 8 + hS * (1 + #e.noteLines)
        end
    end
end

function PzoptPresetPop:prerender()
    local s = self.screen
    local hS, pad = fontH(UIFont.Small), 10
    local foot = s:promptsOn() and (hS + 10) or 0
    self:drawRect(0, 0, self.width, self.height, 0.98, 0.06, 0.06, 0.075)
    self:drawRectBorder(0, 0, self.width, self.height, 1, C_GREY.r, C_GREY.g, C_GREY.b)
    local avail = self.height - foot - 2
    local function lastFrom(top)
        local used, last = 0, top - 1
        for k = top, #self.entries do
            if used + self.entries[k].h > avail then break end
            used, last = used + self.entries[k].h, k
        end
        return last
    end
    if self.follow then -- the highlight in view after the pad moved it
        if self.at < self.top then self.top = self.at end
        while lastFrom(self.top) < self.at and self.top < self.at do self.top = self.top + 1 end
        self.follow = false
    end
    local last = lastFrom(self.top)
    self.lastVis = last
    local mx, my = self:getMouseX(), self:getMouseY()
    local moved = self.mx ~= mx or self.my ~= my
    self.mx, self.my = mx, my
    local over = self:isMouseOver()
    self.hit = {}
    local y = 1
    for k = self.top, last do
        local e = self.entries[k]
        if e.head then
            self:drawText(string.upper(e.head), pad, y + 7, C_DIM.r, C_DIM.g, C_DIM.b, 1, UIFont.Small)
        elseif e.rule then
            self:drawRect(pad, y + 4, self.width - 2 * pad, 1, 0.6, SIDE_BORDER.r, SIDE_BORDER.g, SIDE_BORDER.b)
        elseif e.dim then
            for n, line in ipairs(e.lines) do
                self:drawText(line, pad + 12, y + 3 + (n - 1) * hS, C_GREY.r, C_GREY.g, C_GREY.b, 1, UIFont.Small)
            end
        else
            if over and moved and my >= y and my < y + e.h then self.at = k end
            if k == self.at then self:drawRect(1, y, self.width - 2, e.h, 0.18, 1, 1, 1) end
            if e.current then self:drawRect(pad, y + 4 + math.floor(hS / 2) - 2, 5, 5, 1, C_OPT.r, C_OPT.g, C_OPT.b) end
            local tc = e.danger and { r = 1, g = 0.6, b = 0.45 } or (e.current and C_OPT or C_TEXT)
            self:drawText(clipText(UIFont.Small, e.label, self.width - 2 * pad - 12), pad + 12, y + 4, tc.r, tc.g, tc.b, 1, UIFont.Small)
            for n, line in ipairs(e.noteLines) do
                self:drawText(line, pad + 12, y + 4 + n * hS, C_GREY.r, C_GREY.g, C_GREY.b, 1, UIFont.Small)
            end
            table.insert(self.hit, { k, y, y + e.h })
        end
        y = y + e.h
    end
    if self.top > 1 or last < #self.entries then -- where the window is in the list
        local ih = avail - 2
        local barH = math.max(10, math.floor(ih * (last - self.top + 1) / #self.entries))
        local barY = 2 + math.floor((ih - barH) * (self.top - 1) / math.max(1, #self.entries - (last - self.top + 1)))
        self:drawRect(self.width - 4, barY, 2, barH, 0.6, 1, 1, 1)
    end
    if foot > 0 then
        local fy = self.height - foot
        self:drawRect(1, fy, self.width - 2, 1, 0.35, 1, 1, 1)
        local T = Joypad.Texture
        drawPromptsLR(self, { { T.AButton, "Choose" }, { T.BButton, "Close" } }, pad, fy + 5, self.width - pad)
    end
end

function PzoptPresetPop:onMouseDown(x, y)
    return true
end

function PzoptPresetPop:onMouseUp(x, y)
    for _, hb in ipairs(self.hit or {}) do
        if y >= hb[2] and y < hb[3] then
            self.screen:pickPreset(hb[1])
            return true
        end
    end
    return true
end

function PzoptPresetPop:onMouseUpOutside(x, y)
    if self:isVisible() then self.screen:closePresets() end
end

function PzoptPresetPop:onMouseWheel(del)
    local n = #self.entries
    self.top = math.max(1, math.min(n, self.top + (del > 0 and 1 or -1)))
    if del > 0 and (self.lastVis or n) >= n then self.top = math.max(1, self.top - 1) end
    return true
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
    o.selected = TAB_LAYOUT and "perf" or "home"
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

-- After LATER (tab layout: from the first change): "Restart to apply N settings" and its RESTART NOW at the right end of the bar under the page (the
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
        if TAB_LAYOUT then
            self.restartBtn.joypadTexture, self.restartBtn.joypadTextureWH = Joypad.Texture.Start, fontH(UIFont.Small)
            self.restartBtn:setWidth(bw + fontH(UIFont.Small) + 10)
        end
        self:addChild(self.restartBtn)
    end
    local bx = self.hintX + self.hintW - 6 - self.restartBtn:getWidth()
    self.restartBtn:setX(bx)
    self.restartBtn:setY(self.hintY + math.floor((self.hintH - self.btnH) / 2))
    self.restartBtn:setVisible(true)
    -- (a narrow bar: the count alone, else only the button)
    if bx - 12 - textW(UIFont.Small, text) < self.hintX + 8 then text = n .. " waiting" end
    if bx - 12 - textW(UIFont.Small, text) < self.hintX + 8 then text = "" end
    local tw = textW(UIFont.Small, text)
    return { text = text, x = bx - 12 - tw, w = tw + 12 + self.restartBtn:getWidth() + 6 }
end

-- the sidebar's rows: the six entries, the selected one's children (a group's categories, the benchmark's pages), the
-- selected category's subcategories; the deepest level is left out when the rows would not fit the sidebar
-- the tab layout's sidebar: the selected tab's pages, every level open (nothing opens or closes as the cursor moves); when
-- they do not fit, a window over them follows the selection (the wheel moves it)
-- (tab layout) the benchmark's reports for the sidebar: PzoptBench.history (each row's report read by Java) at most every
-- 5 s, each run's preset named once
function PzoptMainMenu:benchHistory()
    local now = getTimestampMs()
    if not self.benchHist or now - self.benchHist.at > 5000 then
        local titles = {}
        for _, sc in ipairs(PzoptBench and PzoptBench.scenarios() or {}) do titles[sc.id] = sc.title end
        self.benchHist = { at = now, items = PzoptBench and PzoptBench.history() or {}, titles = titles }
    end
    return self.benchHist.items
end

function PzoptMainMenu:tabSideRows()
    local t = settingsTree()
    local rows = {}
    local function add(id, label, depth, count)
        local r = { id = id, label = label, depth = depth, count = count }
        table.insert(rows, r)
        return r
    end
    local tab = tabOf(self.selected) or self.tab or (TAB_LAYOUT and "perf" or "home")
    self.tab = tab
    if GROUPS[tab] then
        for _, cat in ipairs(t.byId[tab] and t.byId[tab].cats or {}) do
            local off = cat.master and not (masterState(cat.master))
            local cid = tab .. "/" .. cat.id
            local r = add(cid, cat.title, 0, off and "off" or tostring(catCount(cat)))
            r.kids, r.folded = #cat.subs > 0, SIDE_FOLD[cid] == true
            if not r.folded then
                for i, sub in ipairs(cat.subs) do add(cid .. "/" .. i, sub.title, 1, tostring(#(shownKeys(sub.keys)))) end
            end
        end
    elseif tab == "bench" and TAB_LAYOUT then
        -- the run planner, Compare, then every report (newest first): its name, date, preset, score and fps, 99 % frame time
        local hS = fontH(UIFont.Small)
        add("bench/setup", "Run planner", 0)
        local cmp = add("bench/compare", "Compare two runs", 0)
        -- (2026-10-21) the compare page shown carries its sections (Overview, Frame time, Utilization, Power ...) as the
        -- run page's scene does
        local cdash = self.selected == "bench/compare" and self.page and self.page.dash
        if cdash and PzoptReportCompare and cdash.mod == PzoptReportCompare then -- (not the page before it, while it is built)
            cmp.secs = true
            for _, sec in ipairs(PzoptReportCompare.sections(cdash.data)) do
                local sr = add("bench/compare", sec.label, 1)
                sr.section = sec.id
                sr.open = function()
                    if self.selected ~= "bench/compare" then self:openPage("bench/compare") end
                    local p = self.detailText
                    if p and p.dash then PzoptReportDash.scrollToRow(p, sec.id) end
                end
            end
        end
        local items = self:benchHistory()
        local titles = self.benchHist.titles
        local h = add("bench/reports", "Reports", 0, tostring(#items))
        h.head = true
        if #items == 0 then
            local n = add("bench/none", "None yet: start a run in the planner.", 0)
            n.head, n.note = true, true
        end
        for k, it in ipairs(items) do
            local run = it[1]
            local fold = "rep:" .. run
            local scenes = {}
            for e in string.gmatch(it[6] or "", "[^;]+") do
                local id = string.match(e, "^([^=]+)=")
                if id then table.insert(scenes, id) end
            end
            -- (2026-10-21) the scene shown on the run page carries its sections (Overview, Frame time, Utilization, Power ...)
            local dash = self.selected == "bench/report" and self.page and self.page.dash
            local shown = dash and dash.run == run and dash.data and dash.data.sel and dash.data.sel.scene or nil
            -- (a run without a valid pass lists no scene fps, the Mac's unfocused runs: the scene shown stands in)
            if #scenes == 0 and shown then scenes = { shown } end
            local r = add("bench/report", ((it[4] or "") ~= "" and it[4]) or (it[2] or run), 0)
            r.run, r.fold = run, fold
            if SIDE_FOLD[fold] == nil then SIDE_FOLD[fold] = k > 1 end -- (the newest report open, the older ones folded)
            r.kids, r.folded = #scenes > 0, #scenes > 0 and SIDE_FOLD[fold] == true
            r.open = function()
                SIDE_FOLD[fold] = false
                self:openPage("bench/report", { run })
            end
            if not r.folded then
                for _, id in ipairs(scenes) do
                    local leaf = add("bench/report", titles[id] or id, 1)
                    leaf.run, leaf.scene = run, id
                    leaf.open = function() self:openPage("bench/report", { run, id }) end
                    if id == shown and PzoptReportDash.sections then
                        leaf.secs = true
                        for _, sec in ipairs(PzoptReportDash.sections(dash.data)) do
                            local sr = add("bench/report", sec.label, 2)
                            sr.run, sr.scene, sr.section = run, id, sec.id
                            sr.open = function()
                                local p = self.detailText
                                if not (p and p.dash and p.dash.run == run and p.data.sel and p.data.sel.scene == id) then
                                    self:openPage("bench/report", { run, id })
                                    p = self.detailText
                                end
                                if p and p.dash then PzoptReportDash.scrollToRow(p, sec.id) end
                            end
                        end
                    end
                end
            end
        end
    elseif tab == "bench" then
        add("bench", "Overview", 0)
        for _, pg in ipairs(BENCH_PAGES) do add(pg.id, pg.title, 0) end
    elseif tab == "home" then
        add("home", "Start here", 0)
    elseif tab == "problems" then
        local probs = (PzoptSettingsLayout or {}).problems or {}
        local r = add("problems", "Fix a problem", 0, tostring(#probs))
        r.kids, r.folded = #probs > 0, SIDE_FOLD.problems == true
        if not r.folded then
            for i, pr in ipairs(probs) do add("problems/" .. i, pr.title, 1) end
        end
    else
        add(tab, titleOf(tab), 0)
    end
    -- the window: the rows that fit from the first one (rows differ in height: a heading, a report's lines)
    local rowH = fontH(UIFont.Small) + 10
    -- (a name wider than the sidebar, a small window: on two lines, the second cut when it is still longer)
    local sideW = self.side and self.side.width or 0
    local _, _, labelX = sideGeom()
    for _, r in ipairs(rows) do
        if r.head then r.h = r.h or (fontH(UIFont.Small) + 14) end
        if not r.head and sideW > 0 then
            local room = sideW - labelX[math.min(r.depth, 2)] - 12 - (r.count and (textW(UIFont.Small, r.count) + 12) or 0)
            local lines = textW(UIFont.Small, r.label) > room and wrap(r.label, UIFont.Small, room) or {}
            if #lines > 1 then
                r.lines = { lines[1], clipText(UIFont.Small, table.concat(lines, " ", 2), room) }
                r.h = rowH + fontH(UIFont.Small)
            end
        end
        r.h = r.h or rowH
    end
    local avail = self.sideRowsH
    local function lastFrom(f)
        local used, last = 0, f - 1
        for k = f, #rows do
            if used + rows[k].h + 1 > avail then break end
            used, last = used + rows[k].h + 1, k
        end
        return math.max(last, f)
    end
    local at = 1
    for i, r in ipairs(rows) do if sideRowIs(self, r) then at = i end end
    local selKey = self.selected .. (self.selected == "bench/report" and self.bench and self.bench.rep and self.bench.rep.run or "")
    local first = self.sideFirst or 1
    if self.sideFirstFor ~= selKey then
        if at < first then first = at end
        while lastFrom(first) < at and first < at do first = first + 1 end
        self.sideFirstFor = selKey
    end
    local maxFirst = #rows -- (the last window that still fills the sidebar)
    while maxFirst > 1 and lastFrom(maxFirst - 1) >= #rows do maxFirst = maxFirst - 1 end
    first = math.max(1, math.min(first, maxFirst))
    local last = lastFrom(first)
    self.sideFirst, self.sideFit = first, last - first + 1
    local y = self.sideTop
    for i, r in ipairs(rows) do
        if i < first or i > last then
            r.hidden = true
        else
            r.y = y
            y = y + r.h + 1
        end
    end
    return rows
end

function PzoptMainMenu:sideRows()
    if TAB_LAYOUT then return self:tabSideRows() end
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

-- ------------------------------------------------------------------ (tab layout) presets: the card's list and what it does
function PzoptMainMenu:presetOpen()
    return self.presetPop ~= nil and self.presetPop:isVisible()
end

-- the name in the card's box (nil = Custom) and whether it is a saved preset; read on every refreshPage
function PzoptMainMenu:updatePreset()
    self.presetName, self.presetSaved = nil, false
    if not (PzoptSettings and PzoptSettings.settingsLines) then return end
    local now = PzoptSettings.settingsLines()
    for _, pr in ipairs(PzoptSettings.userPresets()) do
        if pr.text == now then
            self.presetName, self.presetSaved = pr.name, true
            return
        end
    end
    if PRESET_LAST and PRESET_LAST.text == now then
        self.presetName, self.presetSaved = PRESET_LAST.title, PRESET_LAST.saved == true
    elseif now == "" then
        local b = PzoptSettings.presets()[1]
        self.presetName = b and presetShort(b.title) or "Recommended"
    end
end

-- every key a preset can set: the restart reminder names the ones that wait for a restart
local function touchPresetKeys()
    TOUCHED.enabled = true
    for page = 1, 3 do
        local m = PzoptSettings.MASTERS and PzoptSettings.MASTERS[page]
        if m and m.key then TOUCHED[m.key] = true end
        for _, k in ipairs(PzoptSettings.pageKeys(page)) do TOUCHED[k] = true end
        for _, k in ipairs(PzoptSettings.catMasters(page)) do TOUCHED[k] = true end
    end
end

function PzoptMainMenu:presetEntries()
    local out = {}
    local unsaved = not self.presetName
    table.insert(out, { head = "Built in" })
    for _, pr in ipairs(PzoptSettings.presets()) do
        local preset, name = pr, presetShort(pr.title)
        table.insert(out, { label = name, note = firstSentence(preset.tip),
            current = (not self.presetSaved) and self.presetName == name,
            pick = function() self:applyPreset(preset, name, unsaved) end })
    end
    table.insert(out, { head = "Saved on this PC" })
    local saved = PzoptSettings.userPresets()
    if #saved == 0 then table.insert(out, { dim = "None yet: SAVE... beside the box saves the current settings." }) end
    for _, pr in ipairs(saved) do
        local p = pr
        local n = #p.lines
        table.insert(out, { label = p.name,
            note = n == 0 and "Every setting at its default." or (n .. (n == 1 and " setting" or " settings") .. " changed from the defaults."),
            current = self.presetSaved and self.presetName == p.name,
            pick = function() self:loadSavedPreset(p, unsaved) end })
    end
    if self.presetSaved and self.presetName then
        local name = self.presetName
        table.insert(out, { rule = true })
        table.insert(out, { label = "Delete \"" .. name .. "\"", danger = true, pick = function() self:deletePreset(name) end })
    end
    return out
end

local UNSAVED_NOTE = " Your current settings are not saved as a preset: SAVE... beside the box keeps them first."

function PzoptMainMenu:applyPreset(preset, name, unsaved)
    self:confirm("Apply \"" .. preset.title .. "\"? It changes many settings at once (saved now)." .. (unsaved and UNSAVED_NOTE or ""), function()
        touchPresetKeys()
        local msg = preset.apply()
        PRESET_LAST = { title = name, text = PzoptSettings.settingsLines() }
        PzoptLogInfo("[pzopt] menu: preset applied: " .. name)
        if self.parent then self:refreshPage() end
        showMessage(self, name .. ": " .. msg)
    end)
end

function PzoptMainMenu:loadSavedPreset(pr, unsaved)
    self:confirm("Load the preset \"" .. pr.name .. "\"? Every setting it does not list goes back to the build's default (saved now)."
            .. (unsaved and UNSAVED_NOTE or ""), function()
        touchPresetKeys()
        local msg = PzoptSettings.importSettings("# Optimizations\n" .. pr.text)
        PRESET_LAST = { title = pr.name, text = PzoptSettings.settingsLines(), saved = true }
        PzoptLogInfo("[pzopt] menu: preset loaded: " .. pr.name)
        if self.parent then self:refreshPage() end
        showMessage(self, pr.name .. ": " .. msg)
    end)
end

function PzoptMainMenu:savePreset()
    self:askText("Save the current settings as a preset: its name", self.presetSaved and self.presetName or "", function(typed)
        local name = string.gsub(string.gsub(typed or "", "[%[%]\r\n]", ""), "^%s*(.-)%s*$", "%1")
        if name == "" then
            showMessage(self, "No name given: nothing was saved.")
            return
        end
        local function save()
            local text = PzoptSettings.settingsLines()
            local ok, saved = PzoptSettings.saveUserPreset(name, text)
            if not ok then
                showMessage(self, "The preset could not be written to Zomboid/Lua/pzopt-presets.ini: nothing was saved.")
                return
            end
            PRESET_LAST = { title = saved, text = text, saved = true }
            local n = 0
            for _ in string.gmatch(text, "[^\n]+") do n = n + 1 end
            PzoptLogInfo("[pzopt] menu: preset saved: " .. saved .. " (" .. n .. " settings)")
            if self.parent then self:refreshPage() end
            showMessage(self, "Saved the preset \"" .. saved .. "\": " .. (n == 0 and "every setting at its default."
                or (n .. (n == 1 and " setting" or " settings") .. " changed from the defaults.")) .. " Pick it from Preset: to load it again.")
        end
        for _, pr in ipairs(PzoptSettings.userPresets()) do
            if pr.name == name and not (self.presetSaved and self.presetName == name) then
                self:confirm("A preset named \"" .. name .. "\" exists. Replace it with the current settings?", save)
                return
            end
        end
        save()
    end)
end

function PzoptMainMenu:deletePreset(name)
    self:confirm("Delete the saved preset \"" .. name .. "\"? Your settings stay as they are.", function()
        PzoptSettings.deleteUserPreset(name)
        if PRESET_LAST and PRESET_LAST.title == name then PRESET_LAST = nil end
        PzoptLogInfo("[pzopt] menu: preset deleted: " .. name)
        if self.parent then self:refreshPage() end
    end)
end

function PzoptMainMenu:openPresets()
    local pop, card = self.presetPop, self.presetCard
    if not (pop and card and PzoptSettings and PzoptSettings.userPresets) then return end
    if self.list.drop then self.list:closeDrop() end
    if self:ctrlDropOpen() then self:closePresets() end -- (a page control's list: the preset list takes its place)
    self:updatePreset()
    -- (the box is in the bar at the foot: the list opens upwards from it, at least as wide as the sidebar)
    local bx, by, bw = card:boxRect()
    pop.entries = self:presetEntries()
    local w = math.max(bw, self.side:getWidth(), fontH(UIFont.Small) * 22)
    local x = math.min(card:getX() + bx, self.frameX + self.frameW - self.margin - w)
    pop:setX(x)
    pop:setWidth(w)
    pop:measure()
    local foot = self:promptsOn() and (fontH(UIFont.Small) + 10) or 0
    local total = foot + 2
    for _, e in ipairs(pop.entries) do total = total + e.h end
    local bottom = card:getY() + by - 2
    local h = math.min(total, bottom - self.margin)
    pop:setY(bottom - h)
    pop:setHeight(h)
    pop:bringToTop() -- (over the detail's controls, added after it)
    pop.at, pop.top, pop.follow, pop.mx, pop.my = nil, 1, true, nil, nil
    for k, e in ipairs(pop.entries) do if e.current then pop.at = k end end
    if not pop.at then
        for k, e in ipairs(pop.entries) do if e.pick then pop.at = k break end end
    end
    pop.at = pop.at or 1
    pop:setVisible(true)
    getSoundManager():playUISound("UIActivateMainMenuItem")
end

function PzoptMainMenu:closePresets()
    if not self:presetOpen() then return end
    self.presetPop:setVisible(false)
    self.presetPop.ctrl = nil
    self.presetClosedAt = getTimestampMs()
    getSoundManager():playUISound("UIHighlightMainMenuItem")
end

function PzoptMainMenu:movePreset(dir)
    local pop = self.presetPop
    local k = pop.at + dir
    while pop.entries[k] and not pop.entries[k].pick do k = k + dir end
    if pop.entries[k] then
        pop.at, pop.follow = k, true
        getSoundManager():playUISound("UIHighlightMainMenuItem")
    elseif dir < 0 then
        pop.top, pop.follow = 1, false -- (the headings above the first preset come into view)
    end
end

function PzoptMainMenu:pickPreset(k)
    local e = self.presetPop and self.presetPop.entries[k]
    local ctrl = self.presetPop and self.presetPop.ctrl
    self:closePresets()
    if e and e.pick and not e.current then
        getSoundManager():playUISound("UIActivateMainMenuItem")
        e.pick()
        if ctrl and self.parent then self:refreshPage() end
    end
end

-- (2026-10-21) a page control's dropdown (PzoptMenuControl, a chooser with `options`): the preset list's popup under the
-- control's box (over it when there is no room below), its choices with the current one marked; A / a click picks, B /
-- Escape / a click outside closes it, as the preset list
function PzoptMainMenu:ctrlDropOpen(ctrl)
    return self:presetOpen() and self.presetPop.ctrl ~= nil and (ctrl == nil or self.presetPop.ctrl == ctrl)
end

function PzoptMainMenu:openCtrlDrop(ctrl)
    local pop, spec = self.presetPop, ctrl.spec
    if not (pop and spec.options) then return false end
    local opts = spec.options()
    if #opts == 0 then return false end
    if self.list.drop then self.list:closeDrop() end
    if self:presetOpen() then self:closePresets() end
    local hS, pad = fontH(UIFont.Small), 10
    local lw = textW(UIFont.Small, tostring(val(spec.label))) + 16
    local bx, by = ctrl:getAbsoluteX() - self:getAbsoluteX() + lw, ctrl:getAbsoluteY() - self:getAbsoluteY()
    local bw = ctrl:getWidth() - lw
    local w, notes = bw, false
    for _, o in ipairs(opts) do
        w = math.max(w, textW(UIFont.Small, o.label) + 2 * pad + 12 + 4)
        if o.note then notes = true end
    end
    if notes then w = math.max(w, hS * 24) end
    local right = self.frameX + self.frameW - self.margin
    w = math.min(w, right - self.frameX - self.margin)
    pop.entries = {}
    for _, o in ipairs(opts) do table.insert(pop.entries, { label = o.label, note = o.note, current = o.current, pick = o.pick }) end
    pop.ctrl = ctrl
    pop:setX(math.max(self.frameX + self.margin, math.min(bx, right - w)))
    pop:setWidth(w)
    pop:measure()
    local foot = self:promptsOn() and (hS + 10) or 0
    local total = foot + 2
    for _, e in ipairs(pop.entries) do total = total + e.h end
    local top, bottom = self.margin, self.height - self.margin -- (the frame is the screen's full height)
    local below, above = bottom - (by + ctrl:getHeight() + 2), by - 2 - top
    local h
    if total <= below or below >= above then
        h = math.min(total, below)
        pop:setY(by + ctrl:getHeight() + 2)
    else
        h = math.min(total, above)
        pop:setY(by - 2 - h)
    end
    pop:setHeight(h)
    pop:bringToTop()
    pop.at, pop.top, pop.follow, pop.mx, pop.my = 1, 1, true, nil, nil
    for k, e in ipairs(pop.entries) do if e.current then pop.at = k end end
    pop:setVisible(true)
    getSoundManager():playUISound("UIActivateMainMenuItem")
    return true
end

-- (tab layout) the sidebar's controller places past its rows: the clips switch, then BACK
function PzoptMainMenu:sideClipsIndex()
    return TAB_LAYOUT and (#self.side.rows + 1) or nil
end

function PzoptMainMenu:sideBackIndex()
    return #self.side.rows + (TAB_LAYOUT and 2 or 1)
end

-- (tab layout) the bar under the page is the sidebar's last controller place (sideBackIndex): left / right move along it,
-- barIndex is the element; its id ("back", "sidebar", "preset", "save", a tool's) while the cursor is on it, else nil
function PzoptMainMenu:barFocus()
    if not (TAB_LAYOUT and self.barEls and self.pane == "side" and (self.sideIndex or 1) >= self:sideBackIndex()) then return nil end
    local e = self.barEls[self.barIndex or 1]
    return e and e.id
end

-- the sidebar shown or hidden: the screen is built again at the new widths, on the same page and row (on the next frame,
-- from the main screen's prerender: not while this screen's button is still handling the click)
function PzoptMainMenu:toggleSidebar()
    SIDE_HIDDEN = not SIDE_HIDDEN
    PzoptLogInfo("[pzopt] menu: sidebar " .. (SIDE_HIDDEN and "hidden" or "shown"))
    self.rebuildPending = { pane = self.pane, bar = self.barIndex, restore = { self.selected, self.pane, self.list.selected } }
end

function PzoptMainMenu:rebuildNow()
    local p = self.rebuildPending
    self.rebuildPending = nil
    local s = PzoptMainMenu.show(p.restore, true)
    if s then s.ltDown, s.ltHold = true, { t = 0, done = true } end -- (a held LT toggled it: no second toggle)
    if s and p.pane == "side" then
        s.barIndex = p.bar
        s:focusSide(s:sideBackIndex())
    end
end

-- (tab layout) the bar under the page (maintainer, 2026-10-20): BACK, the sidebar's switch, "Preset:" and its box with
-- SAVE..., EXPORT / IMPORT... / UNINSTALL from the left, every element the bar's height; the restart notice and the
-- controller's hints in what is left at the right (hintX, hintW). When that leaves no room for the notice, the preset and
-- its tools take a line of their own above (two lines). Returns the bar's height.
-- (2026-10-21) a controller button drawn inside a bar button at its right end, where it acts (like LB / RB beside the tabs):
-- the glyph and a dim word ("hold"); the title centres in the rest while the prompts show. `when`: only while it acts
function PzoptMainMenu:padMark(b, tex, word, when)
    local hS = fontH(UIFont.Small)
    local w = hS + 6 + (word and (textW(UIFont.Small, word) + 3) or 0)
    b:setWidth(b:getWidth() + w)
    local base, s = b.render, self
    b.render = function(o)
        local on = s:promptsOn() and (not when or when())
        if not on then return base(o) end
        o.width = o.width - w
        base(o)
        o.width = o.width + w
        local gx, gy = o.width - w, math.floor((o.height - hS) / 2)
        glyph(o, tex, gx, gy, hS)
        if word then o:drawText(word, gx + hS + 3, gy, C_DIM.r, C_DIM.g, C_DIM.b, 1, UIFont.Small) end
    end
end

function PzoptMainMenu:createBar(x0, x1, bottom, h)
    local s, hS, gap = self, fontH(UIFont.Small), 6
    local function button(label, w, act, tip)
        local b = ISButton:new(0, 0, w or (textW(UIFont.Small, label) + 24), h, label, s, function()
            getSoundManager():playUISound("UIActivateMainMenuItem")
            act()
        end)
        b:initialise()
        b:instantiate()
        if tip then b:setTooltip(tip) end
        self:addChild(b)
        return b
    end
    -- (2026-10-21, maintainer) the sidebar's switch: an on / off switch and "Sidebar" as the clips switch in the sidebar's
    -- foot, on while the sidebar shows; still a button underneath (the click, the tooltip, A on the bar, LT held)
    local G = treeGeom()
    local side = button("", G.tw + 8 + textW(UIFont.Small, "Sidebar") + 16, function() s:toggleSidebar() end,
        "Hides the sidebar so the page takes the whole width, or shows it again (kept until the game closes).")
    side.displayBackground = false
    side.render = function(o)
        local hot = o:isMouseOver()
        if hot then o:drawRect(0, 0, o.width, o.height, 0.08, 1, 1, 1) end
        drawToggle(o, 8, math.floor((o.height - G.th) / 2), G, not SIDE_HIDDEN, 1, hot)
        local lc = hot and C_TEXT or { r = 0.8, g = 0.8, b = 0.82 }
        o:drawText(clipText(UIFont.Small, "Sidebar", o.width - G.tw - 16), 8 + G.tw + 8, math.floor((o.height - hS) / 2), lc.r, lc.g, lc.b, 1, UIFont.Small)
    end
    self:padMark(side, Joypad.Texture.LTrigger, "hold")
    self.sideToggle = side
    self.presetCard = PzoptPresetCard:new(0, 0, h, self)
    self.presetCard:initialise()
    self:addChild(self.presetCard)
    local save = button("SAVE...", nil, function()
        if PzoptSettings and PzoptSettings.saveUserPreset then s:savePreset() end
    end, "Saves the current settings as a preset of this PC under a name: pick it from Preset: to load it again.")
    self:padMark(save, Joypad.Texture.XButton, "hold")
    self.presetSave = save
    local tools = {
        { id = "export", label = "EXPORT", tip = "Copies every setting that differs from the defaults to the clipboard as key=value "
            .. "text and saves it to Zomboid/pzopt/settings-export.ini. IMPORT reads it back, here or on another PC.", act = function()
            if PzoptSettings then showMessage(s, PzoptSettings.exportSettings()) end
        end },
        { id = "import", label = "IMPORT...", tip = "Sets every setting from exported text pasted into a box (it starts with the "
            .. "clipboard, or the last export). Settings it does not list go back to the defaults; pinned ones stay.", act = function()
            if PzoptSettings then s:askImport() end
        end },
        { id = "uninstall", label = "UNINSTALL", danger = true, tip = PzoptSettings and PzoptSettings.UNINSTALL_TIP or nil, act = function()
            if not PzoptSettings or PzoptSettings.uninstallUnavailable() ~= "" then return end
            s:confirm(PzoptSettings.UNINSTALL_CONFIRM, function() s:uninstall() end)
        end },
    }
    self.cardButtons = {}
    self.barEls = { { id = "back", el = self.back, verb = "Back" }, { id = "sidebar", el = side, verb = SIDE_HIDDEN and "Show sidebar" or "Hide sidebar" },
        { id = "preset", el = self.presetCard, verb = "Choose a preset" }, { id = "save", el = save, verb = "Save preset" } }
    for _, t in ipairs(tools) do
        local tool = t
        local b = button(tool.label, nil, tool.act, tool.tip)
        if tool.danger then b.borderColor = { r = 1, g = 0.6, b = 0.45, a = 0.8 } end
        b.pzoptTool = tool.id
        table.insert(self.cardButtons, b)
        self.cardButtons[tool.id] = b
        table.insert(self.barEls, { id = tool.id, el = b, verb = string.sub(tool.label, 1, 1) .. string.lower(string.gsub(string.sub(tool.label, 2), "%.", "")) })
    end
    -- the widths: BACK and the switch, then the preset group (a wider gap between the two)
    local function width(from, to)
        local w = 0
        for k = from, to do w = w + self.barEls[k].el:getWidth() + (k > from and gap or 0) end
        return w
    end
    local group = 3 * gap
    local nav, presets = width(1, 2), width(3, #self.barEls)
    local restartW = math.max(120, textW(UIFont.Small, "RESTART NOW") + 24) + hS + 10 + 24
    local two = x0 + nav + group + presets + group + restartW > x1
    local barH = two and (2 * h + gap) or h
    local function place(from, to, x, y)
        for k = from, to do
            local el = self.barEls[k].el
            el:setX(x)
            el:setY(y)
            x = x + el:getWidth() + gap
        end
        return x - gap
    end
    local y = bottom - h
    local xEnd = place(1, 2, x0, y)
    if two then
        place(3, #self.barEls, x0, y - h - gap)
    else
        xEnd = place(3, #self.barEls, xEnd + group, y)
    end
    self.hintX = xEnd + group
    self.hintW = x1 - self.hintX
    self.hintY = y
    return barH
end

function PzoptMainMenu:toggleClips()
    clipsAction().act()
    getSoundManager():playUISound("UIActivateMainMenuItem")
    self:refreshPage()
end

-- a sidebar category folds or opens (its subcategories under it); the cursor stays on it
function PzoptMainMenu:foldSide(i)
    local r = self.side.rows[i]
    if not (r and r.kids) then return false end
    local key = r.fold or r.id
    SIDE_FOLD[key] = not r.folded
    getSoundManager():playUISound("UIActivateMainMenuItem")
    self.side.rows = self:sideRows()
    for k, row in ipairs(self.side.rows) do
        if (row.fold or row.id) == key and not row.scene and self.pane == "side" then self.sideIndex = k end
    end
    self:traceFocus()
    return true
end

function PzoptMainMenu:createChildren()
    ISPanelJoypad.createChildren(self)
    local sw, sh = self.width, self.height
    -- (tab layout) the screen's frame is at most 16:9 wide and centred: on a 21:9 or 32:9 monitor (5120x1440,
    -- 3840x1080) everything stays in the middle instead of spreading to both edges
    local ox = 0
    if TAB_LAYOUT and sw > math.floor(sh * 16 / 9) then
        local fw = math.floor(sh * 16 / 9)
        ox = math.floor((sw - fw) / 2)
        sw = fw
    end
    self.frameX, self.frameW = ox, sw
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
    self.sideTop = TAB_LAYOUT and (self.sidePad + math.floor(hS / 2) + 2) or (self.sidePad + hM + 10) -- (tab layout: the title is on the card)
    local sideY, sideH = m, sh - 2 * m
    local hintH = math.max(22, hS + 4) + 8
    local backTitle = getText("UI_btn_back")
    self.back = ISButton:new(10, sideH - 10 - btnH, math.max(90, textW(UIFont.Small, backTitle) + 24), btnH, backTitle, self,
        function(target) target:close("the BACK button") end)
    self.back:initialise()
    self.back:instantiate()
    self.back:enableCancelColor()
    self.crumbY = m + 2
    self.pageY = m + hM + 10
    self.hintH = hintH
    local barH = hintH
    if TAB_LAYOUT then
        -- (tab layout) the title card level with the breadcrumbs and the tab row, the sidebar level with the page; the bar
        -- under both: BACK, the sidebar's switch, the preset and its tools (maintainer, 2026-10-20)
        self.tabY = self.pageY
        self.tabH = btnH + 10
        self.pageY = self.tabY + self.tabH + 6
        self.back.joypadTexture, self.back.joypadTextureWH = Joypad.Texture.BButton, hS
        self.back:setWidth(self.back:getWidth() + hS + 10)
        self.back:setHeight(hintH)
        self:padMark(self.back, Joypad.Texture.Start, nil, function() return #pendingRestart() == 0 end)
        self:addChild(self.back)
        barH = self:createBar(ox + m, ox + sw - m, sh - m, hintH)
        self.titleCard = ISPanel:new(ox + m, m, sideW, self.pageY - 6 - m)
        self.titleCard:initialise()
        self.titleCard.backgroundColor = { r = 0.04, g = 0.04, b = 0.06, a = 0.85 }
        self.titleCard.borderColor = { r = SIDE_BORDER.r, g = SIDE_BORDER.g, b = SIDE_BORDER.b, a = 1 }
        self.titleCard.prerender = function(o)
            ISPanel.prerender(o)
            o:drawText(ITEM_TEXT, 12, math.floor((o.height - hM) / 2), 1, 1, 1, 1, UIFont.Medium)
        end
        self:addChild(self.titleCard)
        sideY = self.pageY
        sideH = sh - m - barH - 6 - sideY
        self.clipsH = 1 + 6 + hS + 10 + 6
    end
    self.side = PzoptMenuSidebar:new(ox + m, sideY, sideW, sideH, self)
    self.side:initialise()
    self:addChild(self.side)
    -- the search box under the title: every setting of the three groups (PzoptSettings.search), live as you type
    self:createSearch(sideW, btnH)
    if TAB_LAYOUT then
        self.sideRowsH = self.side.height - 10 - self.sideTop - self.clipsH
        if SIDE_HIDDEN then -- (the page from the frame's left edge; the rows still drive the controller and the breadcrumbs)
            self.side:setVisible(false)
            self.titleCard:setVisible(false)
            sideW = -self.gap
        end
    else
        self.back:setY(self.side.height - 10 - btnH)
        self.side:addChild(self.back)
        self.sideRowsH = self.back:getY() - 10 - self.sideTop
    end
    -- the page area right of it, under the breadcrumbs
    self.contentX = ox + m + sideW + self.gap
    self.contentW = ox + sw - self.contentX - m
    if TAB_LAYOUT then self:placeSearch() end
    -- the controller's button-hint bar under the page area (shown while a controller drives the screen; tab layout: the
    -- part of the bar right of its buttons, createBar)
    self.pageH = sh - self.pageY - m - barH - 6
    if not TAB_LAYOUT then
        self.hintY = self.pageY + self.pageH + 6
        self.hintX, self.hintW = self.contentX, self.contentW
    end
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
    if TAB_LAYOUT then -- the list's header: the page's name, Common | All
        self.listHeadH = btnH + 6
        self.listRect[2] = self.listRect[2] + self.listHeadH
        self.listRect[4] = self.listRect[4] - self.listHeadH
    end
    local L = self.listRect
    self.list = PzoptMenuList:new(L[1], L[2], L[3], L[4], self)
    self.list:initialise()
    self:addChild(self.list)
    PzoptLogInfo(string.format("[pzopt] menu: screen %dx%d, sidebar %d px, page %d px %s", sw, sh, sideW, self.contentW,
        self.stacked and "(the detail under the list)" or "(list | detail)"))
    if TAB_LAYOUT then -- (the preset card's open list: over everything on its side of the screen)
        self.presetPop = PzoptPresetPop:new(self)
        self.presetPop:initialise()
        self.presetPop:setVisible(false)
        self:addChild(self.presetPop)
        self:updatePreset()
    end
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

local NO_CLIP = "No visual comparison available"

-- whether any row of a page shows a clip (its rows without one then keep the clip's box, so nothing moves between rows)
local function pageHasClip(items)
    for _, row in ipairs(items or {}) do
        local key = row.setting or row.master
        if row.clip or (key and PzoptSettings.clipOf(key, row.sec)) then return true end
    end
    return false
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
        if not self.clip then
            -- (a setting that does not change the picture; `compact`: no row of the page has a clip, one line instead of
            -- the empty box the page's other rows keep)
            local top = self.clipTop or 4
            if self.compact or not TAB_LAYOUT then
                self:drawTextCentre(NO_CLIP, self.width / 2, self.compact and top or math.floor((self.height - self.hS) / 2), C_DIM.r, C_DIM.g, C_DIM.b, 1, UIFont.Small)
                return
            end
            local bx, by = math.floor((self.width - self.clipW) / 2), top + self.hS + 2
            self:drawRect(bx, by, self.clipW, self.clipH, 1, 0.02, 0.02, 0.03)
            self:drawTextCentre(NO_CLIP, bx + self.clipW / 2, by + math.floor((self.clipH - self.hS) / 2), C_DIM.r, C_DIM.g, C_DIM.b, 1, UIFont.Small)
            self:drawRectBorder(bx, by, self.clipW, self.clipH, 1, 0.31, 0.31, 0.35)
            return
        end
        local info = PzoptSettings.clipInfo(self.clip)
        local gap = self.pad
        local now = getTimestampMs()
        local x = math.floor((self.width - (2 * self.clipW + gap)) / 2)
        -- a caption wider than its clip drops its part in brackets ("OPTIMIZED (every optimization on)" -> "OPTIMIZED")
        local function caption(c)
            if textW(UIFont.Small, c) <= self.clipW then return c end
            return string.match(c, "^(.-) %(") or c
        end
        if TAB_LAYOUT then -- (prototype) only the setting on; the stock GIF is never loaded
            self:drawClip(math.floor((self.width - self.clipW) / 2), self.clipTop or 4, self.clipW, self.clipH, caption(info.sides[2]), info.opt, now, C_OPT)
            return
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
-- (tab layout) The sidebar follows the page's scroll: its highlight (and the breadcrumbs, the tab's last place) is the
-- section of the list's top row, set every frame from prerender. A way back (B to the search results) moves with it.
function PzoptMainMenu:followScroll()
    if not (TAB_LAYOUT and self.page and self.page.toc and GROUPS[self.page.toc]) then return end
    local top = self.list.items[self.list.top or 1]
    local anc = (top and top.anchor) or self.page.toc
    if anc == self.selected then return end
    if self.backTo[self.selected] then
        self.backTo[anc] = self.backTo[self.selected]
        self.backTo[self.selected] = nil
    end
    self.selected = anc
    self.tabLast = self.tabLast or {}
    self.tabLast[tabOf(anc)] = anc
    self.side.rows = self:sideRows()
    if self.pane ~= "side" then -- (the controller's cursor in the sidebar stays where it is)
        for i, r in ipairs(self.side.rows) do if r.id == anc then self.sideIndex = i end end
    end
end

-- ------------------------------------------------------------------ (tab layout) a setting's detail card
-- One card (maintainer, 2026-10-20): the setting's name, its effect on the hardware compacted to a line of chips under it
-- (a resource, a small bar from the stock middle: left green = less load, right amber = more, blue = idle cores put to
-- work, and the level's word), the text, and under a divider at the foot the metadata: Needs restart / Applies at once,
-- the release it came with, its key, the Java classes that read it.
local CARD_BG = { r = 0.04, g = 0.04, b = 0.06, a = 0.85 }
local META_WARN = { r = 1, g = 0.85, b = 0.45 }

-- the effect grid at width w: every resource in a fixed cell (columns of equal width, the bars aligned), the same for
-- every setting so nothing moves between rows; a resource the setting does not move is dimmed as "stock"
local FX_BAR_W, FX_GAP = 40, 20
-- (a benchmark scene) its resource use: 0 .. 3, a bar from the left
local USE_WORDS = { [0] = "none", [1] = "light", [2] = "medium", [3] = "heavy" }
local function effectGrid(w)
    local axes = PzoptSettings and PzoptSettings.AXES or {}
    local labW, wordW = 0, textW(UIFont.Small, "stock")
    for _, axis in ipairs(axes) do labW = math.max(labW, textW(UIFont.Small, axis.label)) end
    for _, word in pairs(PzoptSettings and PzoptSettings.LEVELS or {}) do wordW = math.max(wordW, textW(UIFont.Small, word)) end
    for _, word in pairs(USE_WORDS) do wordW = math.max(wordW, textW(UIFont.Small, word)) end
    local cellW = labW + 6 + FX_BAR_W + 6 + wordW
    local fit = math.max(1, math.floor((w + FX_GAP) / (cellW + FX_GAP)))
    local rows = math.max(1, math.ceil(#axes / fit))
    local cols = math.max(1, math.ceil(#axes / rows)) -- (as few columns as give that many rows: even columns)
    return { axes = axes, labW = labW, cellW = cellW, cols = cols, rows = #axes > 0 and rows or 0 }
end

local function drawEffectGrid(el, x, y, g, key)
    local hS = fontH(UIFont.Small)
    local C = PzoptSettings.FX_COLOURS
    local fx = PzoptSettings.effects(key or "")
    for i, axis in ipairs(g.axes) do
        local col0 = (i - 1) % g.cols
        local row0 = math.floor((i - 1) / g.cols)
        local cx, cy = x + col0 * (g.cellW + FX_GAP), y + row0 * (hS + 6)
        local v = fx[axis.id] or 0
        if v > 3 then v = 3 elseif v < -3 then v = -3 end
        local lc = v ~= 0 and C_GREY or C_DIM
        el:drawTextRight(axis.label, cx + g.labW, cy, lc.r, lc.g, lc.b, 1, UIFont.Small)
        local bx = cx + g.labW + 6
        local by, bh = cy + math.floor(hS / 2) - 3, 6
        el:drawRect(bx, by, FX_BAR_W, bh, 1, 0.13, 0.13, 0.16)
        local mid = bx + math.floor(FX_BAR_W / 2)
        local word, wc = "stock", C_DIM
        if v ~= 0 then
            local len = math.floor((FX_BAR_W / 2 - 1) * math.abs(v) / 3)
            local col = v < 0 and C.less or (axis.moreIsWork and C.work or C.more)
            el:drawRect(v < 0 and (mid - len) or (mid + 1), by, len, bh, 1, col.r, col.g, col.b)
            word, wc = PzoptSettings.LEVELS[v], (col == C.more and C.moreText or col)
        end
        el:drawRect(mid, by - 2, 1, bh + 4, 1, 0.55, 0.55, 0.60)
        el:drawText(word, bx + FX_BAR_W + 6, cy, wc.r, wc.g, wc.b, 1, UIFont.Small)
    end
end

-- the same cells for a scene's resource use: the bar fills from the left with the load, an unused part dimmed as "none"
local function drawUseGrid(el, x, y, g, use)
    local hS = fontH(UIFont.Small)
    local C = PzoptSettings.FX_COLOURS
    for i, axis in ipairs(g.axes) do
        local cx, cy = x + ((i - 1) % g.cols) * (g.cellW + FX_GAP), y + math.floor((i - 1) / g.cols) * (hS + 6)
        local v = math.max(0, math.min(3, use[axis.id] or 0))
        local lc = v > 0 and C_GREY or C_DIM
        el:drawTextRight(axis.label, cx + g.labW, cy, lc.r, lc.g, lc.b, 1, UIFont.Small)
        local bx = cx + g.labW + 6
        local by, bh = cy + math.floor(hS / 2) - 3, 6
        el:drawRect(bx, by, FX_BAR_W, bh, 1, 0.13, 0.13, 0.16)
        local col = v >= 3 and C.more or C.work
        if v > 0 then el:drawRect(bx, by, math.floor(FX_BAR_W * v / 3), bh, 1, col.r, col.g, col.b) end
        local wc = v == 0 and C_DIM or (v >= 3 and C.moreText or col)
        el:drawText(USE_WORDS[v], bx + FX_BAR_W + 6, cy, wc.r, wc.g, wc.b, 1, UIFont.Small)
    end
end

-- the room a page's detail cards keep for the name: its longest name's lines at this width (cached per page and width)
function PzoptMainMenu:cardTitleLines(w)
    local page = self.page or {}
    page.cardTitle = page.cardTitle or {}
    if page.cardTitle[w] then return page.cardTitle[w] end
    local n = 1
    for _, row in ipairs(page.items or {}) do
        if row.meta then n = math.max(n, #wrap(tostring(val(row.label)), UIFont.Medium, w)) end
    end
    page.cardTitle[w] = n
    return n
end

function PzoptMainMenu:settingCard(x, y, w, h, it, title, text)
    local hS, hM, pad = fontH(UIFont.Small), fontH(UIFont.Medium), 12
    local key = it.setting or it.master
    -- the frame first: the head, the text and the metadata draw over it
    local frame = ISPanel:new(x, y, w, h)
    frame:initialise()
    frame.backgroundColor = { r = CARD_BG.r, g = CARD_BG.g, b = CARD_BG.b, a = CARD_BG.a }
    frame.borderColor = { r = SIDE_BORDER.r, g = SIDE_BORDER.g, b = SIDE_BORDER.b, a = 1 }
    self:addChild(frame)
    table.insert(self.detailEls, frame)
    -- the head: the name (the room of the page's longest), the effect grid (every resource, a fixed place each): the same
    -- height for every row of the page, so the text under it never moves
    local titleLines = wrap(title, UIFont.Medium, w - 2 * pad)
    local grid = effectGrid(w - 2 * pad)
    local useLabel = it.use and grid.rows > 0 and (hS + 4) or 0 -- (a scene: its grid under a "RESOURCE USE" label)
    local titleH = 10 + self:cardTitleLines(w - 2 * pad) * hM
    local gridH = grid.rows > 0 and (8 + useLabel + grid.rows * (hS + 6)) or 0
    -- the metadata: label | value rows under a divider
    local m = it.meta() or {}
    local rows = {}
    local labW, valX = 0, 0
    if it.metaRows then -- (a benchmark scene: its own facts, as many rows for every scene)
        rows = it.metaRows()
        for _, r in ipairs(rows) do labW = math.max(labW, textW(UIFont.Small, r[1])) end
        valX = pad + labW + 14
    else
        -- (always the same rows, Java on two lines at most: the metadata's height never changes either)
        table.insert(rows, { "Applies", m.restart and "Needs restart" or "At once, no restart", m.restart and META_WARN or C_GREY })
        table.insert(rows, { "Released", m.released or "-", C_GREY })
        table.insert(rows, { "Key", m.key or key, C_GREY })
        for _, r in ipairs({ "Applies", "Released", "Key", "Java" }) do labW = math.max(labW, textW(UIFont.Small, r)) end
        valX = pad + labW + 14
        local lines = wrap(m.java or "-", UIFont.Small, w - valX - pad)
        if #lines > 2 then lines = { lines[1], lines[2] .. " ..." } end
        for i = 1, 2 do table.insert(rows, { i == 1 and "Java" or "", lines[i] or "", C_GREY }) end
    end
    local metaH = 10 + #rows * hS + 10
    -- the before / after clip while the clips are on: over the metadata, a divider over it (the same room for every
    -- setting; one without a clip of its own says so in the clip's box)
    local cc = self.cardClips
    local clipW, clipH = cc and cc.w or 0, cc and cc.h or 0
    local noteMax = 2 -- (what the clip shows, under it: two lines kept, centred; a longer text ends in "...")
    local function clipsHOf()
        if cc and cc.compact then return 10 + hS + 10 end -- (the one line saying there is no clip)
        return cc and (10 + hS + 2 + clipH + 6 + noteMax * hS + 8) or 0
    end
    -- a short card (a small window) fits by steps, decided on the room every row of the page keeps (never this row's
    -- own text), so the parts do not move between rows: the metadata goes into the text (it scrolls), the caption keeps
    -- one line, the effect grid goes into the text, the clip shrinks down to a third of its height, then it is left out
    local minText = 3 * hS + 14
    local function over() return titleH + gridH + 4 + metaH + clipsHOf() + minText - h end
    local metaInText, gridInText = false, false
    if over() > 0 then metaInText, metaH = true, 0 end
    if over() > 0 and cc then noteMax = 1 end
    if over() > 0 and gridH > 0 then gridInText, gridH = true, 0 end
    if over() > 0 and cc and not cc.compact then
        local nh = clipH - over()
        if nh >= math.max(40, math.floor(cc.h / 3)) then
            clipW, clipH = math.floor(clipW * nh / clipH), nh
        else
            cc = nil
        end
    end
    if over() > 0 and cc and cc.compact then cc = nil end
    local headH = titleH + gridH + 4
    local headEl = ISPanel:new(x, y, w, headH)
    headEl:initialise()
    headEl.background = false
    headEl.prerender = function(o)
        local ty = 10
        for _, line in ipairs(titleLines) do
            o:drawText(line, pad, ty, C_TEXT.r, C_TEXT.g, C_TEXT.b, 1, UIFont.Medium)
            ty = ty + hM
        end
        if gridInText then return end
        local gy = headH - 4 - grid.rows * (hS + 6)
        if grid.rows > 0 and it.use then
            o:drawText("RESOURCE USE", pad, gy - useLabel, C_DIM.r, C_DIM.g, C_DIM.b, 1, UIFont.Small)
            drawUseGrid(o, pad, gy, grid, it.use)
        elseif grid.rows > 0 then
            drawEffectGrid(o, pad, gy, grid, key)
        end
    end
    self:addChild(headEl)
    table.insert(self.detailEls, headEl)
    -- what moved into the text: under it, as label | value lines (the grid's words in their colours)
    local function rgb(c) return string.format(" <RGB:%.2f,%.2f,%.2f> ", c.r, c.g, c.b) end
    local tail = ""
    if gridInText then
        local C = PzoptSettings.FX_COLOURS
        local fx = (not it.use) and PzoptSettings.effects(key or "") or {}
        tail = tail .. " <LINE> <LINE> " .. rgb(C_DIM) .. (it.use and "RESOURCE USE" or "EFFECT ON YOUR HARDWARE") .. " <LINE> "
        for _, axis in ipairs(grid.axes) do
            local word, wc
            if it.use then
                local v = math.max(0, math.min(3, it.use[axis.id] or 0))
                word, wc = USE_WORDS[v], v == 0 and C_DIM or (v >= 3 and C.moreText or C.work)
            else
                local v = math.max(-3, math.min(3, fx[axis.id] or 0))
                word, wc = "stock", C_DIM
                if v ~= 0 then
                    local col = v < 0 and C.less or (axis.moreIsWork and C.work or C.more)
                    word, wc = PzoptSettings.LEVELS[v], (col == C.more and C.moreText or col)
                end
            end
            tail = tail .. rgb(C_GREY) .. esc(axis.label) .. " <SETX:" .. (grid.labW + 14) .. "> " .. rgb(wc) .. esc(word) .. " <LINE> "
        end
    end
    if metaInText then
        tail = tail .. " <LINE> "
        for _, r in ipairs(rows) do
            if r[2] ~= "" then
                tail = tail .. rgb(C_DIM) .. esc(r[1]) .. " <SETX:" .. (valX - pad) .. "> " .. rgb(r[3]) .. esc(r[2]) .. " <LINE> "
            end
        end
    end
    if metaH > 0 then
        local metaEl = ISPanel:new(x, y + h - metaH, w, metaH)
        metaEl:initialise()
        metaEl.background = false
        metaEl.prerender = function(o)
            o:drawRect(pad, 0, o.width - 2 * pad, 1, 0.55, C_GREY.r, C_GREY.g, C_GREY.b)
            local ry = 10
            for _, r in ipairs(rows) do
                if r[1] ~= "" then o:drawText(r[1], pad, ry, C_DIM.r, C_DIM.g, C_DIM.b, 1, UIFont.Small) end
                o:drawText(clipText(UIFont.Small, r[2], o.width - valX - pad), valX, ry, r[3].r, r[3].g, r[3].b, 1, UIFont.Small)
                ry = ry + hS
            end
        end
        self:addChild(metaEl)
        table.insert(self.detailEls, metaEl)
    end
    local clipsH = 0
    if cc then
        local clipKey = it.clip or (key and PzoptSettings.clipOf(key, it.sec)) or nil
        local note = clipKey and PzoptSettings.clipInfo(clipKey).title or ""
        local noteLines = wrap(note, UIFont.Small, w - 2 * pad)
        if #noteLines > noteMax then
            -- (too long: its first sentence, the scene, when that fits, else nothing; the whole caption in the text)
            noteLines = wrap(firstSentence(note), UIFont.Small, w - 2 * pad)
            if #noteLines > noteMax then noteLines = {} end
            tail = tail .. " <LINE> <LINE> " .. rgb(C_DIM) .. "Clip: " .. rgb(C_GREY) .. esc(note)
        end
        local noteY = 10 + hS + 2 + clipH + 6
        clipsH = clipsHOf()
        local clips = cc.cls:new(x, y + h - metaH - clipsH, w, clipsH)
        clips:initialise()
        clips.clipW, clips.clipH, clips.clipTop = clipW, clipH, 10
        clips.clip, clips.compact = clipKey, cc.compact
        clips.backgroundColor = { r = 0, g = 0, b = 0, a = 0 }
        clips.borderColor = { r = 0, g = 0, b = 0, a = 0 }
        local base = clips.prerender
        clips.prerender = function(o)
            o:drawRect(pad, 0, o.width - 2 * pad, 1, 0.55, C_GREY.r, C_GREY.g, C_GREY.b)
            base(o)
            for n, line in ipairs(noteLines) do
                o:drawTextCentre(line, o.width / 2, noteY + (n - 1) * hS, C_GREY.r, C_GREY.g, C_GREY.b, 1, UIFont.Small)
            end
        end
        self:addChild(clips)
        table.insert(self.detailEls, clips)
    end
    -- the text between them (it scrolls when it is longer)
    local tp = newText(self, x + 1, y + headH, w - 2, math.max(hS * 3, h - headH - metaH - clipsH - 2))
    tp.background = false
    tp.borderColor = { r = 0, g = 0, b = 0, a = 0 }
    tp.marginTop = 2
    tp.text = text .. tail
    tp:paginate()
    table.insert(self.detailEls, tp)
    self.detailText = tp
    self.detailText2 = nil
end

function PzoptMainMenu:showDetail()
    for _, el in ipairs(self.detailEls) do
        el:setVisible(false)
        self:removeChild(el)
    end
    self.detailEls, self.focusables, self.actionButtons = {}, {}, {}
    self.chartRect, self.impactChart, self.cardClips = nil, nil, nil
    local D = self.detailRect
    local it = self.list.items[self.list.selected or 0]
    local page = self.page or {}
    self.list:setVisible(not page.dash)
    if page.dash and PzoptReportDash then return self:showDash(page) end
    local fixed = (page.settingsPage or page.cardPage) and PzoptSettings ~= nil
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
    -- (prototype) the tab layout's read-only detail: a settings page's and Home's rows carry their controls and buttons
    local readOnly = TAB_LAYOUT and not (it and it.plot)
    local btnRows = readOnly and {} or buttonRows(actions)
    local ch = math.max(self.btnH, fontH(UIFont.Small) + 8)
    local controls = (not readOnly and it and it.controls) or {}
    -- the slots of a settings page: the most button rows and controls any of its rows has
    local bandBtn, bandCtrl = #btnRows, #controls
    if fixed and not readOnly then
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
        if clipsOnNow and TAB_LAYOUT then
            local CW, CH = PzoptSettings.CLIP_W, PzoptSettings.CLIP_H
            local maxW, maxH = D[3] - 24, math.floor(avail * 0.34) - (hS + 2 + 4 + 8)
            local k = math.floor(math.min(maxW / CW, maxH / CH))
            if k < 1 then k = math.max(0.25, math.min(maxW / CW, maxH / CH)) end
            clipW, clipH = math.floor(CW * k), math.floor(CH * k)
        elseif clipsOnNow then
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
        if Impact and not TAB_LAYOUT and avail - clipBand - chartMax > 4 * hS then
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
        if clipsOnNow and TAB_LAYOUT and it and it.meta then -- (in the setting's card, over its metadata: settingCard)
            self.cardClips = { cls = Clips, w = clipW, h = clipH, band = clipBand - 6, compact = not pageHasClip(self.list.items) }
        elseif clipsOnNow and not page.cardPage then
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
    if fixed and clipsOn() and it and (it.setting or it.master) and not (TAB_LAYOUT and it.meta) then -- what the clips above show
        -- (tab layout: the card says it under the clip)
        local clip = PzoptSettings.clipOf(it.setting or it.master, it.sec)
        body = body .. " <LINE> <LINE> <RGB:0.66,0.66,0.70> Clips: " .. esc(clip and PzoptSettings.clipInfo(clip).title or NO_CLIP)
    end
    -- a blank line under the heading: the next line is spaced for the small font and touched its descenders
    local text = (it and (" <H2> " .. esc(title) .. " <TEXT> <LINE> <LINE> ") or "") .. (head and (head .. " <LINE> ") or "")
        .. " <LINE> " .. body
    if TAB_LAYOUT and fixed and it and it.meta then
        self:settingCard(D[1], textTop, D[3], math.max(fontH(UIFont.Small) * 8, textH), it, title,
            (head and (head .. " <LINE> ") or "") .. body)
    elseif it and it.chart then
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

-- (tab layout, 2026-10-21) a benchmark report as the Grafana run page: over the whole page area the run page's variables
-- (Scene, Variant, Pass choosers) with the controller's in-place prompts on their right, the report's buttons (open the
-- full report, the share code, compare, run again, the folder) under them, then PzoptReportDash's panels, which are the
-- detail text the triggers, the right stick and the D-pad past the last button scroll. The controller's order: the
-- choosers, then the button rows; X / Y are the buttons' keys as before.
function PzoptMainMenu:showDash(page)
    local dash = page.dash
    local D = dash.mod or PzoptReportDash -- (2026-10-21) the compare page brings its own panels (PzoptReportCompare)
    local x0, y0, W, H = self.contentX, self.pageY, self.contentW, self.pageH
    local hS = fontH(UIFont.Small)
    local ch = math.max(self.btnH, hS + 8)
    local ctrlEls = {}
    local x, y = x0, y0
    -- (2026-10-21) a "heading" (a side's title) and a "rule" (the divider) take a line of their own and are no stops of
    -- the cursor; a spec's `w` keeps a column the same width on every line (the compare page's A and B dropdowns)
    local hM = fontH(UIFont.Medium)
    for _, spec in ipairs(D.controls(dash.data, dash.state)) do
        local full = spec.type == "heading" or spec.type == "rule"
        local w = full and W or math.min(W, spec.w or PzoptReportDash.controlW(spec))
        local h = (spec.type == "heading" and hM + 4) or (spec.type == "rule" and 9) or ch
        if x > x0 and (full or spec.newLine or x + w > x0 + W) then x, y = x0, y + ch + 6 end
        local c = PzoptMenuControl:new(x, y, w, h, spec, self)
        c:initialise()
        self:addChild(c)
        table.insert(self.detailEls, c)
        if full then
            x, y = x0, y + h + (spec.type == "heading" and 4 or 6)
        else
            table.insert(ctrlEls, c)
            x = x + w + 16
        end
    end
    local screen = self
    -- what A / the D-pad / X / Y / B do from here: after the last control on its line, else (the controls end with a
    -- full line) after the buttons
    local hintsAfterButtons = x == x0 and #ctrlEls > 0
    local hints = ISPanel:new(x, y, math.max(0, x0 + W - x), ch)
    hints:initialise()
    hints.background = false
    hints.prerender = function(o)
        if not screen:promptsOn() then return end
        local T, BT = Joypad.Texture, Joypad.ButtonTextures
        if screen.pane ~= "detail" then -- (2026-10-21) the page's own: LT / RT a page, RS a scroll (in place, not in the bar)
            drawPrompts(o, { { T.LTrigger, "" }, { T.RTrigger, "Page" }, { T.RStick, "Scroll" } }, o.width, math.floor((o.height - hS) / 2), 0)
            return
        end
        local f = screen.focusables[screen.detailIndex or 1]
        local list = {}
        if f and f.button then table.insert(list, { T.AButton, tostring(f.el.title) }) end
        if f and f.el.spec and f.el.spec.type == "chooser" and f.el.spec.options then table.insert(list, { T.AButton, "Choose" }) end
        if f and f.el.spec and f.el.spec.type == "chooser" then table.insert(list, { BT[Joypad.DPadLeft], "Change" }) end
        local xa, ya = screen:keyAction("x"), screen:keyAction("y")
        if ya then table.insert(list, { T.YButton, tostring(val(ya.label)) }) end
        if xa then table.insert(list, { T.YButton, "Hold: " .. tostring(val(xa.label)) }) end
        table.insert(list, { T.BButton, "Sidebar" })
        table.insert(list, { T.LTrigger, "" })
        table.insert(list, { T.RTrigger, "Page" })
        table.insert(list, { T.RStick, "Scroll" })
        drawPrompts(o, list, o.width, math.floor((o.height - hS) / 2), 0)
    end
    self:addChild(hints)
    table.insert(self.detailEls, hints)
    if #ctrlEls > 0 and not hintsAfterButtons then x, y = x0, y + ch + 6 end
    local row = 1
    for _, a in ipairs(dash.actions or {}) do
        local title = tostring(val(a.label))
        local w = math.max(100, textW(UIFont.Small, title) + 24)
        if x > x0 and x + w > x0 + W then x, y, row = x0, y + self.btnH + 6, row + 1 end
        local act = a
        local b = ISButton:new(x, y, w, self.btnH, title, self, function()
            if not act.quiet then getSoundManager():playUISound("UIActivateMainMenuItem") end
            self:runAction(act)
        end)
        b:initialise()
        b:instantiate()
        b.pzoptAction = act
        b.pzoptRow = row
        if act.danger then b:enableAcceptColor() end
        self:addChild(b)
        table.insert(self.detailEls, b)
        table.insert(self.actionButtons, b)
        x = x + w + 8
    end
    if hintsAfterButtons then
        hints:setX(x + 8)
        hints:setY(y + math.floor((self.btnH - ch) / 2))
        hints:setWidth(math.max(0, x0 + W - x - 8))
    end
    y = y + self.btnH + 8
    local panel = D.newPanel(x0, y, W, math.max(hS * 4, y0 + H - y), dash)
    self:addChild(panel)
    table.insert(self.detailEls, panel)
    self.detailText, self.detailText2 = panel, nil
    self.detailActions = dash.actions or {}
    for _, c in ipairs(ctrlEls) do table.insert(self.focusables, { el = c, row = #self.focusables + 1 }) end
    for _, b in ipairs(self.actionButtons) do table.insert(self.focusables, { el = b, button = true }) end
    if self.pane == "detail" then self:focusDetail(math.min(self.detailIndex or 1, #self.focusables)) end
    self.lastDesc = nil
    if self.prompt or self.help then self:setPageShown(false) end
    if self.side then self.side.rows = self:sideRows() end -- (the scene shown carries its sections: the page is read now)
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
            local hint = (TAB_LAYOUT and screen.joyfocus and "Search settings") or (screen.joyfocus and "Search (L3)") or "Search settings (Ctrl+F)"
            o:drawText(clipText(UIFont.Small, hint, o.width - 16), 8, math.floor((o.height - fontH(UIFont.Small)) / 2),
                C_GREY.r, C_GREY.g, C_GREY.b, 1, UIFont.Small)
        end
    end
    self.search = box
    self.searchTyped, self.searchAt, self.query = "", 0, ""
    if TAB_LAYOUT then -- (placed on the breadcrumbs' line, right-aligned, once the page area is known: placeSearch)
        self:addChild(box)
        return
    end
    self.side:addChild(box)
    self.sideTop = self.sideTop + btnH + 10
end

-- (tab layout) the search box at the top right, on the breadcrumbs' line, above the tab row; the View glyph left of it
-- while a controller drives (View opens it)
function PzoptMainMenu:placeSearch()
    local box = self.search
    if not box then return end
    local w = math.max(240, math.min(math.floor(self.contentW * 0.28), textW(UIFont.Small, "Search settings (Ctrl+F)") * 2))
    box:setWidth(w)
    box:setX(self.contentX + self.contentW - w)
    box:setY(self.crumbY + math.floor((fontH(UIFont.Medium) - box:getHeight()) / 2))
    self.searchReserve = w + fontH(UIFont.Small) + textW(UIFont.Small, "Search") + 40
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
        self:selectNode(self.searchFrom or (TAB_LAYOUT and "perf" or "home"), "master")
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
    local selId = sel and self.list.items[sel] and self.list.items[sel].id
    self.page = pageOf(self, self.page and self.page.toc or self.selected)
    self.list.items = self.page.items
    if selId then -- (rows above it can fold or open)
        for i, it in ipairs(self.list.items) do if it.id == selId then sel = i break end end
    end
    if sel then self.list:setSelected(sel) end
    self.list.top = math.min(top, math.max(1, #self.list.items))
    self.side.rows = self:sideRows()
    local scroll = self.detailText and self.detailText:getYScroll() or 0
    self:showDetail()
    if self.detailText then self.detailText:setYScroll(scroll) end
    self:syncRestart()
    if self.presetCard then self:updatePreset() end
    self:traceFocus()
end

-- ------------------------------------------------------------------ restarting for the next-launch settings
-- A change that waits for a restart opens the prompt (the settings it names, RESTART NOW, LATER); after LATER the
-- breadcrumbs' line keeps "Restart to apply N settings" with its RESTART NOW. Tab layout (maintainer, 2026-10-20): no
-- prompt, only that notice at the bottom right from the first change on. The restart is pzopt.Restart (the same as
-- the update's Restart game): the settings are saved as they change (UserOptions), options.ini is the game's own; the
-- menu only shows at the main menu, so no world is open to save.
function PzoptMainMenu:syncRestart()
    local pend = pendingRestart()
    local n = #pend
    if n > (self.pendingSeen or 0) and not self.forceNoPrompt and not TAB_LAYOUT then self:showRestartPrompt() end
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
    self.list:setVisible(on and not (self.page and self.page.dash))
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
    -- (tab layout) the benchmark has no overview and no Past results page: its tab opens on the run planner, the reports
    -- are the sidebar
    if TAB_LAYOUT and (id == "bench" or id == "bench/history") then id = "bench/setup" end
    if TAB_LAYOUT and tabOf(id) then
        self.tabLast = self.tabLast or {}
        self.tabLast[tabOf(id)] = id
    end
    self.backTo[id] = nil -- reached another way than openPage's: B goes to its parent (openPage sets it again after)
    self.selected = id
    self.colour = colourOf(id)
    self.side.rows = self:sideRows()
    if TAB_LAYOUT then COLLAPSED[id] = nil end -- (a subcategory's group opens when it is reached)
    self.page = pageOf(self, id)
    self.list.items = self.page.items
    self.list.top = 1
    self.list.selected = nil
    self.list:setSelected(self.list:firstSelectable() or 1)
    if TAB_LAYOUT and self.page.toc and id ~= self.page.toc then
        for i, it in ipairs(self.page.items) do
            if (it.kind == "section" or it.kind == "group" or it.kind == "cat") and it.anchor == id then
                self.list:setSelected(it.kind == "cat" and i or (i + 1), 1)
                self.list.top = i
                break
            end
        end
    end
    if from then
        for i, it in ipairs(self.page.items) do if it.id == from then self.list:setSelected(i) end end
    end
    self.detailIndex = 1
    for i, r in ipairs(self.side.rows) do
        if sideRowIs(self, r) then self.sideIndex = i end
    end
    if focus == "side" then
        self.pane = "side"
    elseif focus == "master" then
        -- (the report's run page has no list: its choosers and buttons, unless the cursor is moving in the sidebar)
        self.pane = (self.list.selected and "master") or ((self.page.dash and self.pane ~= "side") and "detail") or "side"
    end
    self:showDetail()
    self:traceFocus()
end

-- a page from an action or another file (Past results' Open / Compare, the dev screens, the report after a run): its
-- arguments, where B goes back to
function PzoptMainMenu:openPage(id, arg, from)
    local B = benchState(self)
    if id == "bench/report" and arg then
        B.rep = { run = arg[1], scene = arg[2] }
        -- (2026-10-21) the report shown opens in the sidebar, its scene's sections with it: an older report opened from the
        -- compare page (OPEN A'S REPORT) or Past results stayed folded there, without its section leaves
        SIDE_FOLD["rep:" .. tostring(arg[1])] = false
    end
    if id == "bench/compare" and arg and PzoptReportCompare then B.cmp = PzoptReportCompare.state(arg[1], arg[2]) end
    if id == "bench/setup" then B.error = nil end
    self:selectNode(id, "master")
    if id == "bench/report" and arg and arg[2] then -- (a scene's leaf: its row, the PZ Optimization pass first)
        local pick
        for i, it in ipairs(self.list.items or {}) do
            if it.scene == arg[2] and (not pick or (it.cfg == "A" and self.list.items[pick].cfg ~= "A")) then pick = i end
        end
        if pick then
            self.list:setSelected(pick)
            self:showDetail()
            self:traceFocus()
        end
    end
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

-- the compare page's Setup table: only the settings that differ, or every one
function PzoptMainMenu:setOnlyDiff(on)
    local B = benchState(self)
    B.onlyDiff = on
    PzoptLogInfo("[pzopt] menu: only what differs " .. (on and "on" or "off"))
    if self.selected == "bench/compare" then self:refreshPage() end
end

-- (2026-10-21) a friend's share code as side A of the compare page: beside the report's pass (Compare a code), or in
-- place of A on the compare page (B stays)
function PzoptMainMenu:applyShareCode(code)
    local B = benchState(self)
    local t = PzoptBench.decode(code)
    if t.bad then
        showMessage(self, "That is not a benchmark share code (it starts with PZOB1:).")
        return
    end
    local st = nil
    if self.selected == "bench/report" and B.rep then
        local d = self.page and self.page.dash and self.page.dash.data
        local sel = d and d.sel or {}
        st = { b = { run = B.rep.run, scene = sel.scene or B.rep.scene, cfg = sel.cfg or B.rep.cfg, pass = sel.pass or B.rep.pass } }
    else
        st = B.cmp or (PzoptReportCompare and PzoptReportCompare.defaultState())
        if not st then
            local items = PzoptBench.history()
            if #items == 0 then
                showMessage(self, "Run the benchmark first: a share code is compared with one of your runs.")
                return
            end
            st = { b = { run = items[1][1] } }
        end
    end
    st.code = { text = code, t = t }
    st.a = { code = true, scene = st.b.scene, cfg = st.b.cfg }
    B.cmp = st
    self:openPage("bench/compare")
    print("[pzopt] benchmark: dev: report compare shown (" .. tostring(st.b.run) .. ")")
end

function PzoptMainMenu:askText(title, current, set)
    local w, h = math.min(520, getCore():getScreenWidth() - 40), 140
    local box = ISTextBox:new((getCore():getScreenWidth() - w) / 2, (getCore():getScreenHeight() - h) / 2, w, h, title, current or "", self,
        function(target, button)
            if button.internal == "OK" then
                set(button.parent.entry:getText() or "")
                if target.parent then target:refreshPage() end
            end
        end)
    box:initialise()
    box:addToUIManager()
    box:setAlwaysOnTop(true)
    box:bringToTop()
    local jd = mainJoypad()
    if jd then
        box.prevFocus = jd.focus
        jd.focus = box
        updateJoypadFocus(jd)
    end
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
    if self.pane == "side" and SIDE_HIDDEN then self.sideIndex = self:sideBackIndex() end -- (only the bar is on screen)
    if self.pane == "side" then
        local r = self.side.rows[self.sideIndex or 1]
        if TAB_LAYOUT and self:barFocus() then -- the bar: its element (the preset box by name), row = the bar's index
            local e = self.barEls[self.barIndex or 1]
            el, row = e.id == "preset" and { name = "Preset" } or e.el, self.sideIndex * 10 + (self.barIndex or 1)
        elseif (self.sideIndex or 1) >= self:sideBackIndex() then
            el, row = self.back, self.sideIndex
        elseif TAB_LAYOUT and self.sideIndex == self:sideClipsIndex() then
            el, row = { name = "Before / after clips" }, self.sideIndex
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
    for _, e in ipairs(self.barEls or {}) do
        if e.id ~= "preset" and e.el ~= self.back then e.el:setJoypadFocused(self.pane == "side" and el == e.el and self.joyfocus ~= nil) end
    end
    for i, f in ipairs(self.focusables or {}) do
        if f.button then f.el:setJoypadFocused(self.pane == "detail" and i == self.detailIndex and self.joyfocus ~= nil) end
    end
    if before ~= self.traceKey and self.joyfocus then getSoundManager():playUISound("UIHighlightMainMenuItem") end
end

function PzoptMainMenu:focusSide(i)
    local n = #self.side.rows
    i = math.max(1, math.min(i, self:sideBackIndex()))
    if SIDE_HIDDEN then i = self:sideBackIndex() end -- (the bar alone)
    local dir = i >= (self.sideIndex or 1) and 1 or -1 -- (a heading is passed over in the direction of the move)
    while self.side.rows[i] and self.side.rows[i].head do i = i + dir end
    if i < 1 then i = 1 end
    while self.side.rows[i] and self.side.rows[i].head do i = i + 1 end
    self.sideIndex = i
    self.pane = "side"
    local r0 = self.side.rows[i]
    if i >= 1 and i <= n and r0 and not sideRowIs(self, r0) then
        if r0.open then r0.open() else self:selectNode(r0.id, "side") end
        -- the rows below it change when it opens or closes its children: keep the cursor on the entry (the same row when it
        -- is still there: a scene's leaf opens with its sections under it, which are lit too)
        local same = nil
        for k, r in ipairs(self.side.rows) do
            if r.id == r0.id and r.run == r0.run and r.scene == r0.scene and r.section == r0.section then same = k end
        end
        if same then self.sideIndex = same else
            for k, r in ipairs(self.side.rows) do if sideRowIs(self, r) then self.sideIndex = k end end
        end
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
    elseif TAB_LAYOUT and not it.toggle and not it.verb and rowChooser(it) then -- a dropdown opens
        self:showDetail()
        self.list:openDrop(i)
    elseif it.activate then
        getSoundManager():playUISound("UIActivateMainMenuItem")
        it.activate()
        self:refreshPage()
    elseif TAB_LAYOUT and firstAction(it) then -- (the row's first button, read-only detail)
        getSoundManager():playUISound("UIActivateMainMenuItem")
        self:runAction(firstAction(it))
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
    if TAB_LAYOUT then
        if self.pane == "side" then
            self:close("B in the sidebar")
            return
        end
        for i, r in ipairs(self.side.rows) do if r.id == self.selected then self.sideIndex = i end end
        if SIDE_HIDDEN then self.sideIndex, self.barIndex = self:sideBackIndex(), 1 end -- (BACK; B again closes)
        self.pane = "side"
        getSoundManager():playUISound("UIActivateMainMenuItem")
        self:traceFocus()
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
    if dir < 0 and i == self.detailIndex and self.detailText then -- at the top: the text scrolls back
        self.detailText:setYScroll(math.min(0, self.detailText:getYScroll() + fontH(UIFont.Small) * 3))
    end
    if dir > 0 and i == self.detailIndex and self.detailText then -- at the end: the text scrolls
        local maxScroll = math.max(0, self.detailText:getScrollHeight() - self.detailText:getHeight())
        self.detailText:setYScroll(math.max(-maxScroll, self.detailText:getYScroll() - fontH(UIFont.Small) * 3))
    end
end

function PzoptMainMenu:onJoypadDirUp(joypadData)
    if self.prompt then return end
    if self:presetOpen() then self:movePreset(-1) return end
    if self.list.drop then self.list:moveDrop(-1) return end
    if self.pane == "side" and SIDE_HIDDEN then -- (the bar alone: up goes into the page)
        if self.list.selected then
            self.pane = "master"
            self:traceFocus()
        elseif self.page and self.page.dash then
            self:focusDetail(1, true)
        end
    elseif self.pane == "side" then
        self:focusSide((self.sideIndex or 1) - 1)
    elseif self.pane == "master" then
        self:moveList(-1)
    elseif self.pane == "detail" then
        self:moveDetail(-1)
    end
end

function PzoptMainMenu:onJoypadDirDown(joypadData)
    if self.prompt then return end
    if self:presetOpen() then self:movePreset(1) return end
    if self.list.drop then self.list:moveDrop(1) return end
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
    if self.list.drop or self:presetOpen() then return end
    if TAB_LAYOUT and self.pane == "side" then -- the bar's previous element; on a category, fold / open it
        if self:barFocus() then
            if (self.barIndex or 1) > 1 then
                self.barIndex = self.barIndex - 1
                self:traceFocus()
            end
        elseif (self.sideIndex or 0) >= 1 then
            self:foldSide(self.sideIndex)
        end
        return
    end
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
        if TAB_LAYOUT and self:stepRow(-1) then return end
        local at = nil
        for i, r in ipairs(self.side.rows) do if r.id == self.selected then at = i end end
        if SIDE_HIDDEN then
            self:focusSide(self:sideBackIndex())
        elseif at then
            self.sideIndex = at
            self.pane = "side"
            self:traceFocus()
        else
            self:focusSide(1) -- home has no row: the first entry (and its page)
        end
    end
end

-- (tab layout) left / right on a row with a choice steps it, as the game's Options screen does with a controller
function PzoptMainMenu:stepRow(dir)
    local it = self.list.items[self.list.selected or 0]
    if it and it.kind == "group" and (dir > 0) ~= it.open then -- right opens a group, left folds it
        getSoundManager():playUISound("UIActivateMainMenuItem")
        it.activate()
        self:refreshPage()
        return true
    end
    local c = it and ((it.chooser and it.chooser.shown() and it.chooser) or (not it.chooser and it.activate and it.controls and it.controls[1]))
    if not (c and c.type == "chooser") then return false end
    getSoundManager():playUISound("UIActivateMainMenuItem")
    c.set(dir)
    self:refreshPage()
    return true
end

function PzoptMainMenu:onJoypadDirRight(joypadData)
    if self.prompt then self.promptIndex = #(self.promptButtons or {}) self:traceFocus() return end
    if self.list.drop or self:presetOpen() then return end
    if self.pane == "side" then
        if self:barFocus() then -- the bar's next element
            if (self.barIndex or 1) < #self.barEls then
                self.barIndex = (self.barIndex or 1) + 1
                self:traceFocus()
            end
        elseif (self.sideIndex or 1) <= #self.side.rows and self.list.selected then
            self.pane = "master"
            self:traceFocus()
        elseif (self.sideIndex or 1) <= #self.side.rows and self.page and self.page.dash then
            self:focusDetail(1, true)
        end
    elseif self.pane == "master" then
        if TAB_LAYOUT and self:stepRow(1) then return end
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
    if val(a.disabled) then return end -- (2026-10-21) a disabled action (START BENCHMARK without a scene): its click, A and Y do nothing
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
    if TAB_LAYOUT then -- the tabs, wrapping; the cursor stays in the pane it was in
        local cur = tabOf(self.selected) or self.tab or "home"
        local at = 1
        for i, tb in ipairs(TABS) do if tb.id == cur then at = i end end
        at = ((at - 1 + dir + #TABS) % #TABS) + 1 -- (Kahlua's % keeps the dividend's sign: -1 % n is -1)
        self:openTab(TABS[at].id, self.pane == "side" and "side" or "master")
        return
    end
    local cur = topOf(self.selected) or "home"
    local at = 1
    for i, id in ipairs(TOP_ORDER) do if id == cur then at = i end end
    at = ((at - 1 + dir + #TOP_ORDER) % #TOP_ORDER) + 1
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
    if self:presetOpen() then -- the preset card's list: A picks, B closes, the rest waits
        if button == Joypad.AButton then self:pickPreset(self.presetPop.at)
        elseif button == Joypad.BButton then self:closePresets() end
        return
    end
    if self.list.drop then -- an open dropdown: A picks the highlighted choice, B closes it, the rest waits
        if button == Joypad.AButton then self.list:pickDrop(self.list.drop.at)
        elseif button == Joypad.BButton then self.list:closeDrop() end
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
            local bar = self:barFocus()
            if bar == "preset" then
                self:openPresets()
            elseif bar and bar ~= "back" then
                local b = self.barEls[self.barIndex].el
                if b.enable then b:forceClick() end
            elseif (self.sideIndex or 1) >= self:sideBackIndex() then
                self:close("A on BACK")
            elseif TAB_LAYOUT and self.sideIndex == self:sideClipsIndex() then
                self:toggleClips()
            elseif self.list.selected then
                self.pane = "master"
                self:traceFocus()
            elseif self.page and self.page.dash then
                self:focusDetail(1, true)
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
            elseif f.el.spec.type == "chooser" and f.el.spec.options then -- a dropdown: A opens its list
                self:openCtrlDrop(f.el)
            else
                f.el.spec.set(1)
                getSoundManager():playUISound("UIActivateMainMenuItem")
                self:refreshPage()
            end
        end
    elseif button == Joypad.XButton then -- (2026-10-21) the preset list on release, a preset saved once held (padHold)
        self.xHold = { t = getTimestampMs() }
    elseif button == Joypad.YButton then -- the main action; held, the row's second action (X's until X became the presets)
        if self:keyAction("x") then
            self.yHold = { t = getTimestampMs() }
        else
            self:padHold("y", false)
        end
    elseif button == Joypad.LBumper or button == Joypad.RBumper then
        self:cycleSection(button == Joypad.RBumper and 1 or -1)
    elseif button == Joypad.Back and TAB_LAYOUT then -- (tab layout) View / Select / Back: the search box (on-screen keyboard)
        getSoundManager():playUISound("UIActivateMainMenuItem")
        self:focusSearch()
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
        -- L3 (search) and R3 (the clips) act a moment after the press, unless the other stick is pressed too: L3 + R3
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

-- (2026-10-21) X and Y act on their release, or once held half a second: X opens the preset list / saves the settings as a
-- preset, Y runs the main action / the second one
function PzoptMainMenu:padHold(b, held)
    if b == "x" then
        if not held then self:openPresets()
        elseif PzoptSettings and PzoptSettings.saveUserPreset then self:savePreset() end
        return
    end
    local a = self:keyAction(held and "x" or "y")
    if a then
        getSoundManager():playUISound("UIActivateMainMenuItem")
        self:runAction(a)
    end
end

function PzoptMainMenu:onJoypadButtonReleased(button, joypadData)
    local k = (button == Joypad.XButton and "xHold") or (button == Joypad.YButton and "yHold") or nil
    if not (k and self[k]) then return end
    self[k] = nil -- (pollPad clears it when the hold already acted)
    self:padHold(k == "xHold" and "x" or "y", false)
end

function PzoptMainMenu:onJoypadButtonReleased_Descendant(descendant, button, joypadData)
    self:onJoypadButtonReleased(button, joypadData)
end

-- Ctrl (on macOS also Cmd) held: the shortcuts' modifier (2026-10-21: a Mac player saves with Cmd+S; Option is Alt there)
function PzoptMainMenu.ctrlDown()
    if isCtrlKeyDown() then return true end
    local ok, mac = pcall(function() return isSystemMacOS() and isMetaKeyDown() end)
    return ok and mac == true
end

-- Ctrl+S saves the settings as a preset, Alt+S hides / shows the sidebar (the bar's SAVE... and HIDE SIDEBAR)
function PzoptMainMenu:shortcut(key)
    if key ~= Keyboard.KEY_S or UIManager.isModalVisible() or self.prompt or self.help then return false end
    if PzoptMainMenu.ctrlDown() then
        if PzoptSettings and PzoptSettings.saveUserPreset then self:savePreset() end
        return true
    end
    if isAltKeyDown() and TAB_LAYOUT then
        self:toggleSidebar()
        return true
    end
    return false
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
        if p.b == Joypad.LStickButton then self:focusSearch() else self:toggleClips() end -- (2026-10-21, maintainer: R3 the clips)
    end
    for _, k in ipairs({ "xHold", "yHold" }) do -- (a held X / Y: its hold action once, nothing on its release)
        local h = self[k]
        if h and getTimestampMs() - h.t >= 500 then
            self[k] = nil
            self:padHold(k == "xHold" and "x" or "y", true)
        end
    end
    local jd = self.joyfocus
    if not jd or not jd.id or self.help then return end
    local id = jd.id
    local okT, lt, rt = pcall(function() return isJoypadLTPressed(id), isJoypadRTPressed(id) end)
    if okT then
        if lt or rt then self.padInputAt = getTimestampMs() end
        -- (2026-10-21) LT: a page up on its release, the sidebar hidden / shown once held (the rebuilt screen starts held)
        local now = getTimestampMs()
        if lt and not self.ltDown then self.ltHold = { t = now } end
        if lt and self.ltHold and not self.ltHold.done and now - self.ltHold.t >= 500 then
            self.ltHold.done = true
            self:toggleSidebar()
        end
        if not lt and self.ltDown and self.ltHold and not self.ltHold.done then self:pageMove(-1) end
        if not lt then self.ltHold = nil end
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
    if self:presetOpen() then
        add(T.AButton, "Choose")
        add(T.BButton, "Close")
        add(BT[Joypad.DPadDown], "Move")
    elseif self:barFocus() then
        add(T.AButton, self.barEls[self.barIndex or 1].verb)
        add(T.BButton, "Close")
        add(BT[Joypad.DPadLeft], "Move")
    elseif TAB_LAYOUT and self.pane == "side" and self.sideIndex == self:sideClipsIndex() then
        add(T.AButton, "Toggle")
        add(T.BButton, "Close")
        add(BT[Joypad.DPadDown], "Move")
    elseif self.pane == "side" then
        add(T.AButton, "Open")
        add(T.BButton, parentOf(self.selected) == "home" and "Close" or "Up")
        add(BT[Joypad.DPadDown], "Move")
    elseif self.pane == "master" then
        local a = "Details"
        if it and it.kind == "node" then a = "Open"
        elseif self.list.drop then a = "Choose"
        elseif it and TAB_LAYOUT and rowVerb(it) then a = rowVerb(it)
        elseif it and it.activate then a = (it.kind == "setting" and it.controls and it.controls[1].type == "chooser") and "Next value" or "Toggle" end
        add(T.AButton, a)
        add(T.BButton, parentOf(self.selected) and "Back" or "Close")
    else
        local f = self.focusables[self.detailIndex or 1]
        if f and f.button then add(T.AButton, tostring(f.el.title)) end
        if f and f.el.spec and f.el.spec.type == "chooser" and f.el.spec.options then add(T.AButton, "Choose") end
        if f and f.el.spec and (f.el.spec.type == "chooser" or f.el.spec.type == "number") then add(BT[Joypad.DPadLeft], "Change") end
        if f and f.el.spec and f.el.spec.type == "colour" then add(T.AButton, "Pick a colour") end
        if f and f.el.spec and f.el.spec.type == "tick" then add(T.AButton, "Toggle") end
        add(T.BButton, "List")
    end
    local x, y = self:keyAction("x"), self:keyAction("y")
    if y then add(T.YButton, tostring(val(y.label))) end
    if x then add(T.YButton, "Hold: " .. tostring(val(x.label))) end
    add(T.LBumper, "Section")
    add(T.RBumper, nil)
    add(T.LTrigger, "Page up (hold: sidebar)")
    add(T.RTrigger, "Page down")
    add(T.RStick, "Scroll")
    add(BT[Joypad.Back], self.pane == "side" and "Page" or "Sidebar")
    if TAB_LAYOUT then -- A, B, X, Y, the D-pad, LB / RB and View are drawn where they act
        -- (2026-10-21) and every other button too: LT / RT / RS at the list's head and the page's strip, X in the preset
        -- box, held X on SAVE..., held LT on the sidebar switch, Start on BACK / RESTART NOW, L3 / R3 beside the search
        return {}
    end
    local nPend = #pendingRestart()
    add(BT[Joypad.Start], nPend > 0 and ("Restart (" .. nPend .. ")") or "Close")
    add(BT[Joypad.LStickButton], "Search")
    add(BT[Joypad.RStickButton], "Clips")
    return out
end

local HELP_LINES = {
    { "D-pad, left stick", "move (the stick repeats while held)" },
    { "A", "open, toggle, press" },
    { "B", "back one level: detail, list, sidebar, close" },
    { "X", "the preset list; held: save the current settings as a preset" },
    { "Y", "the row's main action (Apply a preset, Start benchmark, Open, Update now...), asks first when it restarts, installs or changes many settings; held: the row's second action (Default, Reset, Compare, Copy share code...)" },
    { "LB / RB", "previous / next section of the sidebar" },
    { "LT / RT", "a page up / down in the list or the text; LT held: hide or show the sidebar" },
    { "Right stick", "scroll the detail's text" },
    { "View (Back)", "the focus between the sidebar and the page" },
    { "Menu (Start)", "close the menu (every change is saved as you make it)" },
    { "L3", "search every setting (the on-screen keyboard)" },
    { "R3", "the before / after clips on / off" },
}

function PzoptMainMenu:render()
    ISPanelJoypad.render(self)
    if TAB_LAYOUT and not SIDE_HIDDEN and self.pane == "master" and not self.prompt and not self.help and self:promptsOn()
        and not self:presetOpen() then -- (2026-10-21: drawn after the children, it sat on the open preset list at 1280x720)
        -- in place, at the sidebar's top-right corner: what takes the cursor there from the list (left only from a row
        -- without a choice: on a choice the D-pad changes the value)
        local it = self.list.items[self.list.selected or 0]
        local c = it and it.activate and it.controls and it.controls[1]
        local list = { { Joypad.Texture.BButton, "Sidebar" } }
        if not (c and c.type == "chooser") then table.insert(list, 1, { Joypad.ButtonTextures[Joypad.DPadLeft], "" }) end
        local sx, sy = self.side:getX(), self.side:getY() + self.sidePad - 8
        if self.titleCard then sy = self.titleCard:getY() + math.floor((self.titleCard:getHeight() - fontH(UIFont.Small)) / 2) - 2 end
        drawPrompts(self, list, sx + self.side:getWidth() - 10, sy + 2, sx + textW(UIFont.Medium, ITEM_TEXT) + 24)
    end
    local pad = self.joyfocus ~= nil or self.forceHints
    local rem = self:reminder()
    if pad or rem then
        -- the bar: the restart reminder at its right end, the controller's hints in their order in what is left
        local x0, y0, w = self.hintX, self.hintY, self.hintW
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
    for _, e in ipairs(self.barEls or {}) do if e.id ~= "preset" then e.el:setJoypadFocused(false) end end
    for _, f in ipairs(self.focusables or {}) do if f.button then f.el:setJoypadFocused(false) end end
end

-- the breadcrumbs: "PZ Optimization  >  Enhancements  >  Lighting and shadows", each part a link up to that level
function PzoptMainMenu:drawCrumbs()
    local path = pathOf(self.selected)
    local x, y = self.contentX, self.crumbY
    local reserve = TAB_LAYOUT and (self.searchReserve or 0) or 0
    if TAB_LAYOUT and self.search and self:promptsOn() and not self.prompt then
        -- (2026-10-21) the controller's "R3 Clips, View / L3 Search" left of the box: at 1280x720 "Compare" ran into them
        local g = fontH(UIFont.Small)
        reserve = self.search:getWidth() + 10 + 3 * (g + 4) + 28 + textW(UIFont.Small, "Clips") + textW(UIFont.Small, "Search") + 16
    end
    local sep = "  >  "
    local sepW = textW(UIFont.Medium, sep)
    local total = 0
    for i, id in ipairs(path) do total = total + textW(UIFont.Medium, titleOf(id)) + (i > 1 and sepW or 0) end
    local first = 1
    local dotsW = textW(UIFont.Medium, "...") + sepW -- (dropping part 1 took a separator it never had: the "..." brings it back)
    while total + (first > 1 and dotsW or 0) > self.contentW - reserve and first < #path do -- a long path drops its first parts ("...  >")
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
        local last = i == #path
        -- (the last part alone wider than the room left of the search box: cut; the list's heading under it is whole)
        if last then label = clipText(UIFont.Medium, label, self.contentX + self.contentW - reserve - x) end
        local w = textW(UIFont.Medium, label)
        local hot = not last and mx >= x and mx < x + w and my >= y and my < y + fontH(UIFont.Medium)
        local c = last and C_TEXT or (hot and C_TEXT or C_GREY)
        self:drawText(label, x, y, c.r, c.g, c.b, 1, UIFont.Medium)
        if not last then table.insert(self.crumbs, { id = id, x = x, w = w }) end
        x = x + w
    end
end

-- the controller's prompts show while a controller drives the screen and the mouse has not moved since (the game's rule)
function PzoptMainMenu:promptsOn()
    if self.forceHints then return true end
    if self.joyfocus == nil then return false end
    local ok, mouse = pcall(wasMouseActiveMoreRecentlyThanJoypad)
    return not (ok and mouse)
end

-- The tab row: full labels, else the short ones, else a window of whole tabs around the selected one; LB / RB glyphs at
-- its ends while a controller drives (their room is kept with the mouse too, so the tabs never move)
function PzoptMainMenu:drawTabs()
    local x0, y0, w, h = self.contentX, self.tabY, self.contentW, self.tabH
    local g = fontH(UIFont.Small) + 4
    local inner0, inner1 = x0 + g + 12, x0 + w - g - 12
    self:drawRect(x0, y0 + h - 2, w, 2, 0.6, SIDE_BORDER.r, SIDE_BORDER.g, SIDE_BORDER.b)
    local cur = tabOf(self.selected)
    local pad = 16
    local function labelOf(tb, short)
        if short then return tb.short end
        if tb.label then return tb.label end
        local e = topEntry(tb.id)
        return e and e.label or tb.short
    end
    local function widths(short)
        local ws, total = {}, 0
        for i, tb in ipairs(TABS) do
            ws[i] = textW(UIFont.Small, labelOf(tb, short)) + 2 * pad
            total = total + ws[i]
        end
        return ws, total
    end
    local short = false
    local ws, total = widths(false)
    if total > inner1 - inner0 then
        short = true
        ws, total = widths(true)
    end
    local first, last = 1, #TABS
    local arrowW = textW(UIFont.Small, "<  ")
    if total > inner1 - inner0 then
        local sel = 1
        for i, tb in ipairs(TABS) do if tb.id == cur then sel = i end end
        first, last = sel, sel
        local used, avail = ws[sel], inner1 - inner0 - 2 * arrowW
        local grew = true
        while grew do
            grew = false
            if last < #TABS and used + ws[last + 1] <= avail then last = last + 1 used = used + ws[last] grew = true end
            if first > 1 and used + ws[first - 1] <= avail then first = first - 1 used = used + ws[first] grew = true end
        end
    end
    self.tabRects = {}
    local x = inner0
    local ty = y0 + math.floor((h - fontH(UIFont.Small)) / 2)
    if first > 1 then
        self:drawText("<", x, ty, C_DIM.r, C_DIM.g, C_DIM.b, 1, UIFont.Small)
        x = x + arrowW
    end
    local mx, my = self:getMouseX(), self:getMouseY()
    for i = first, last do
        local tb = TABS[i]
        local c = tb.id == "home" and C_TEXT or colourOf(tb.id)
        local sel = tb.id == cur
        local hot = mx >= x and mx < x + ws[i] and my >= y0 and my < y0 + h
        if sel then
            self:drawRect(x, y0, ws[i], h - 2, 0.18, c.r, c.g, c.b)
            self:drawRect(x, y0 + h - 5, ws[i], 4, 1, c.r, c.g, c.b)
        elseif hot then
            self:drawRect(x, y0, ws[i], h - 2, 0.08, 1, 1, 1)
        end
        local off = GROUPS[tb.id] and not (masterState(GROUPS[tb.id].master))
        local col = sel and c or (off and C_DIM or { r = c.r * 0.8, g = c.g * 0.8, b = c.b * 0.8 })
        self:drawText(labelOf(tb, short), x + pad, ty, col.r, col.g, col.b, 1, UIFont.Small)
        table.insert(self.tabRects, { id = tb.id, x = x, w = ws[i] })
        x = x + ws[i]
    end
    if last < #TABS then
        self:drawText(">", x + 6, ty, C_DIM.r, C_DIM.g, C_DIM.b, 1, UIFont.Small)
        x = x + arrowW
    end
    if self:promptsOn() then -- LB before the first tab, RB right after the last one
        local gy = y0 + math.floor((h - g) / 2)
        glyph(self, Joypad.Texture.LBumper, x0 + 6, gy, g)
        glyph(self, Joypad.Texture.RBumper, math.min(x + 6, x0 + w - g - 6), gy, g)
    end
end

-- the list's header (tab layout): the page's name; on a category page "Fold all" / "Open all" at its right end
function PzoptMainMenu:drawListHead()
    if self.page and self.page.dash then return end -- (the report's run page: no list)
    local L = self.listRect
    local x0, y0, w, h = L[1], L[2] - self.listHeadH, L[3], self.listHeadH - 4
    local c = self.colour or C_TEXT
    local ty = y0 + math.floor((h - fontH(UIFont.Small)) / 2)
    local title = self.page and self.page.toc and titleOf(self.page.toc) or titleOf(self.selected)
    self.foldAllRect = nil
    local linkW = 0
    if self.page and #(self.page.groups or {}) > 1 then
        local label = anyOpen(self.page) and "Fold all" or "Open all"
        linkW = textW(UIFont.Small, label)
        local lx = x0 + w - 8 - linkW
        local hot = self:isMouseOver() and self:getMouseX() >= lx - 6 and self:getMouseX() < lx + linkW + 6
            and self:getMouseY() >= y0 and self:getMouseY() < y0 + h
        local lc = hot and C_TEXT or C_GREY
        self:drawText(label, lx, ty, lc.r, lc.g, lc.b, 1, UIFont.Small)
        if hot then self:drawRect(lx, ty + fontH(UIFont.Small), linkW, 1, 0.8, lc.r, lc.g, lc.b) end
        self.foldAllRect = { lx - 6, y0, linkW + 12, h }
    end
    local right = x0 + w - 8 - (linkW > 0 and linkW + 18 or 0)
    if self:promptsOn() and not self.prompt and not self.help then -- (2026-10-21) LT / RT page the list, where they act
        local T = Joypad.Texture
        local list = { { T.LTrigger, "" }, { T.RTrigger, "Page" } }
        if self.detailText then table.insert(list, { T.RStick, "Scroll" }) end
        right = drawPrompts(self, list, right, ty, x0 + 8 + textW(UIFont.Small, string.upper(title)) + 16)
    end
    self:drawText(clipText(UIFont.Small, string.upper(title), right - x0 - 16), x0 + 8, ty, c.r, c.g, c.b, 1, UIFont.Small)
end

-- a tab: the page the player was on in it last, else its first page
function PzoptMainMenu:openTab(id, focus)
    self.tabLast = self.tabLast or {}
    getSoundManager():playUISound("UIActivateTab")
    self:selectNode(self.tabLast[id] or id, focus or "master")
end

function PzoptMainMenu:onMouseUp(x, y)
    local f = self.foldAllRect
    if f and x >= f[1] and x < f[1] + f[3] and y >= f[2] and y < f[2] + f[4] then
        getSoundManager():playUISound("UIActivateMainMenuItem")
        foldAll(self.page, anyOpen(self.page))
        self:refreshPage()
        return true
    end
    if TAB_LAYOUT and self.tabY and y >= self.tabY and y < self.tabY + self.tabH then
        for _, r in ipairs(self.tabRects or {}) do
            if x >= r.x and x < r.x + r.w then
                self:openTab(r.id)
                return true
            end
        end
    end
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
    self:followScroll()
    self:drawCrumbs()
    if TAB_LAYOUT then
        self:drawTabs()
        self:drawListHead()
        if self.search and self:promptsOn() and not self.prompt then
            local bx, by = self.search:getX(), self.search:getY() + math.floor((self.search:getHeight() - fontH(UIFont.Small)) / 2)
            local BT = Joypad.ButtonTextures -- (R3: the clips, next to the other header prompt)
            drawPrompts(self, { { BT[Joypad.RStickButton], "Clips" }, { BT[Joypad.Back], "" }, { BT[Joypad.LStickButton], "Search" } },
                bx - 10, by, self.contentX)
        end
        -- B on BACK while the sidebar has the cursor (B closes from there), Start on RESTART NOW
        local on = self:promptsOn()
        self.back.isJoypad = on and self.pane == "side"
        if self.restartBtn then self.restartBtn.isJoypad = on end
    end
    local padBar = (self.joyfocus ~= nil or self.forceHints) and (not TAB_LAYOUT or #self:hints() > 0) -- (tab layout: the prompts are in place)
    if padBar or (#pendingRestart() > 0 and not self.prompt) then -- the bar under the page
        self:drawRect(self.hintX, self.hintY, self.hintW, self.hintH, 0.85, SIDE_BG.r, SIDE_BG.g, SIDE_BG.b)
        self:drawRectBorder(self.hintX, self.hintY, self.hintW, self.hintH, 1, SIDE_BORDER.r, SIDE_BORDER.g, SIDE_BORDER.b)
    end
    self:pollPad()
    self:pollSearch()
    -- a value shown in the list, the sidebar or the detail can change under it (the updater, a download): once a second
    local now = getTimestampMs()
    if now >= (self.nextRefresh or 0) then
        self.nextRefresh = now + 1000
        self.side.rows = self:sideRows()
        self.list.layoutDirty = true
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

-- restore = { node, pane, list row, detail control } to come back where the player was (after Options, a resize);
-- rebuild: built again at the same size (the sidebar shown / hidden)
function PzoptMainMenu.show(restore, rebuild)
    local ms = MainScreen.instance
    if not ms then return nil end
    local sw, sh = getCore():getScreenWidth(), getCore():getScreenHeight()
    local s = ms.pzoptMenuScreen
    local bench, backTo, tabLast = nil, nil, nil
    if s and (rebuild or s.width ~= sw or s.height ~= sh) then
        restore = restore or { s.selected, s.pane, s.list.selected }
        bench, backTo, tabLast = s.bench, s.backTo, s.tabLast
        if s.presetPop then s:closePresets() end
        s:setVisible(false)
        ms:removeChild(s)
        s = nil
    end
    if not s then
        s = PzoptMainMenu:new(0, 0, sw, sh)
        s.bench = bench
        s.backTo = backTo or {}
        s.tabLast = tabLast
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
    if s.rebuildPending then s:rebuildNow() return end
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
    if s and s:isVisible() and s.presetOpen and s:presetOpen() then -- the preset card's list closes first
        s:closePresets()
        return true
    end
    if s and s:isVisible() and s.list and s.list.drop then -- an open dropdown closes first
        s.list:closeDrop()
        return true
    end
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
        local b = s.cardButtons and s.cardButtons.uninstall -- (tab layout: in the bar under the page)
        if not b then
            s:selectNode("home", "master")
            for i, it in ipairs(s.list.items) do if it.id == "uninstall" then s.list:setSelected(i) end end
            s:showDetail()
            for _, f in ipairs(s.actionButtons or {}) do if f.title == "UNINSTALL" then b = f end end
        end
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
        if key == Keyboard.KEY_F and PzoptMainMenu.ctrlDown() and self.pzoptMenuScreen and self.pzoptMenuScreen:isVisible() then
            self.pzoptMenuScreen:focusSearch()
            return
        end
        if key == Keyboard.KEY_S and self.pzoptMenuScreen and self.pzoptMenuScreen:isVisible() and self.pzoptMenuScreen:shortcut(key) then return end
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
    -- Ctrl+F, Ctrl+S, Alt+S in a game (the pause menu's screen: MainScreen.onKeyRelease is the main menu's)
    Events.OnKeyPressed.Add(function(key)
        local ms = MainScreen.instance
        local s = ms and ms.inGame and ms.pzoptMenuScreen
        if key == Keyboard.KEY_F and PzoptMainMenu.ctrlDown() and s and s:isVisible() then pcall(function() s:focusSearch() end) end
        if key == Keyboard.KEY_S and s and s:isVisible() then pcall(function() s:shortcut(key) end) end
    end)
end

install()
Events.OnGameBoot.Add(install)
