-- pzopt harness: UI interaction rig (2026-09-30, "make the UI instant and snappy").
--
-- ui_script=interactions   ui_script_at (4) s after the player exists, play the interactions below ui_reps (3) times,
--                          one every ui_step_ms (1200) ms, each through the same Lua path the key or click takes:
--                          key presses as the game raises them (OnKeyStartPressed + OnKeyPressed with the bound key),
--                          clicks through the function the click handler calls. Every step's Lua time is measured with
--                          a microsecond clock and handed to pzopt.UiProfile (getPerformance():pzoptUiMark), which logs
--                          the frames and UI renders of the second after it into Zomboid/pzopt-ui.out; harness/uiprof.py
--                          turns that into handler time, delay until the UI showed it, and the worst frame after it.
--   ui_bag_items=N (60)    a Base.Bag_BigHikingBag with N distinct items is put into the main inventory (a second
--                          container in the inventory window, for the container switch)
--   ui_floor_items=N (40)  N distinct items dropped on the player's square (the loot window's floor)
-- The run ends by itself (bench route); the rig quits the game when the script is done if ui_quit=true.

local FLAG_FILE = "pzopt-harness.txt"

local function readFlags()
    local reader = getFileReader(FLAG_FILE, false)
    if not reader then return nil end
    local flags = {}
    while true do
        local line = reader:readLine()
        if line == nil then break end
        local k, v = string.match(line, "^%s*([%w_]+)%s*=%s*(.-)%s*$")
        if k then flags[k] = v end
    end
    reader:close()
    return flags
end

local rig = nil -- nil = not read yet, false = off

local function micros() return getPerformance():pzoptMicros() end

local ITEM_TYPES = { normal = true, food = true, drainable = true, weapon = true, literature = true, clothing = true }

local function itemTypes(n, skip)
    local all = getScriptManager():getAllItems()
    local out = {}
    for i = 0, all:size() - 1 do
        if #out >= n then break end
        local script = all:get(i)
        local itemType = script:getItemType() and tostring(script:getItemType()):match("([^:]+)$"):lower()
        if script:getModuleName() == "Base" and not script:getObsolete() and ITEM_TYPES[itemType] then
            if skip > 0 then skip = skip - 1 else table.insert(out, script:getFullName()) end
        end
    end
    return out
end

local stackTypeName = nil -- ui_stack_items: the full type of the big stack

local function setup(player, flags)
    local inv = player:getInventory()
    local bag = inv:AddItem("Base.Bag_BigHikingBag")
    local nBag = tonumber(flags.ui_bag_items) or 60
    if bag and bag.getItemContainer and bag:getItemContainer() then
        for _, t in ipairs(itemTypes(nBag, 200)) do pcall(function() bag:getItemContainer():AddItem(t) end) end
    end
    local sq = player:getCurrentSquare()
    local nFloor = tonumber(flags.ui_floor_items) or 40
    if sq then
        for _, t in ipairs(itemTypes(nFloor, 400)) do pcall(function() sq:AddWorldInventoryItem(t, 0.5, 0.5, 0) end) end
    end
    -- ui_stack_items=N (200): one stack of N identical items in the main inventory (a big pile of one thing; the
    -- inventory pane groups it into one collapsible row of N)
    local nStack = tonumber(flags.ui_stack_items) or 200
    local stackType = flags.ui_stack_type or "Base.Nails"
    local added = 0
    for _ = 1, nStack do
        local ok, it = pcall(function() return inv:AddItem(stackType) end)
        if ok and it then added = added + 1 end
    end
    stackTypeName = stackType
    ISInventoryPage.dirtyUI()
    print("[pzopt-ui] setup: bag=" .. tostring(bag) .. " bag_items=" .. nBag .. " floor_items=" .. nFloor .. " stack=" .. stackType .. " x" .. added)
end

local function pressKey(id)
    local key = getCore():getKey(id)
    triggerEvent("OnKeyStartPressed", key)
    triggerEvent("OnKeyPressed", key)
end

local function escapeKey()
    local k = getCore():getKey("Main Menu")
    return k ~= 0 and k or Keyboard.KEY_ESCAPE
end

local function firstItem(container)
    local items = container:getItems()
    for i = 0, items:size() - 1 do
        local it = items:get(i)
        if not it:isEquipped() and not instanceof(it, "InventoryContainer") then return it end
    end
    return nil
end

local function worldObjects(player)
    local out = {}
    local sq = player:getCurrentSquare()
    if not sq then return out end
    for dx = -1, 1 do
        for dy = -1, 1 do
            local s = getCell():getGridSquare(sq:getX() + dx, sq:getY() + dy, sq:getZ())
            if s then
                local objs = s:getObjects()
                for i = 0, objs:size() - 1 do table.insert(out, objs:get(i)) end
            end
        end
    end
    return out
end

-- state shared by steps
local S = {}

local STEPS = {
    { "inv_close", function() pressKey(KeybindId.TOGGLE_INVENTORY) end },
    { "inv_open", function() pressKey(KeybindId.TOGGLE_INVENTORY) end },
    { "container_bag", function()
        local page = getPlayerInventory(0)
        for _, b in ipairs(page.backpacks) do
            if b.inventory ~= getPlayer():getInventory() then page:selectContainer(b); return end
        end
    end },
    { "container_main", function()
        local page = getPlayerInventory(0)
        for _, b in ipairs(page.backpacks) do
            if b.inventory == getPlayer():getInventory() then page:selectContainer(b); return end
        end
    end },
    { "container_loot", function()
        local page = getPlayerLoot(0)
        S.lootIndex = ((S.lootIndex or 0) % math.max(1, #page.backpacks)) + 1
        if page.backpacks[S.lootIndex] then page:selectContainer(page.backpacks[S.lootIndex]) end
    end },
    { "transfer_one", function()
        local player = getPlayer()
        local it = firstItem(getPlayerLoot(0).inventory)
        if it then ISInventoryPaneContextMenu.onMoveItemsTo({ it }, player:getInventory(), 0) end
    end },
    { "tooltip_show", function()
        local pane = getPlayerInventory(0).inventoryPane
        local it = firstItem(getPlayer():getInventory())
        if not it then return end
        S.tip = ISToolTipInv:new(it)
        S.tip:initialise()
        S.tip:addToUIManager()
        S.tip:setVisible(true)
        S.tip:setOwner(pane)
        S.tip:setCharacter(getPlayer())
        S.tip.followMouse = false
        S.tip.anchorBottomLeft = { x = pane:getAbsoluteX() + pane.column2, y = pane:getParent():getAbsoluteY() }
    end },
    { "tooltip_hide", function()
        if S.tip then S.tip:removeFromUIManager(); S.tip:setVisible(false); S.tip = nil end
    end },
    { "ctx_item_open", function()
        local pane = getPlayerInventory(0).inventoryPane
        local it = firstItem(getPlayer():getInventory())
        if it then S.menu = ISInventoryPaneContextMenu.createMenu(0, true, { it }, pane:getAbsoluteX() + 64, pane:getAbsoluteY() + 40) end
    end },
    { "ctx_item_close", function() if S.menu then S.menu:closeAll(); S.menu = nil end end },
    { "ctx_world_open", function()
        S.menu = ISWorldObjectContextMenu.createMenu(0, worldObjects(getPlayer()), getCore():getScreenWidth() / 2, getCore():getScreenHeight() / 2)
    end },
    { "ctx_world_close", function() if S.menu then S.menu:closeAll(); S.menu = nil end end },
    { "craft_open", function() pressKey(KeybindId.CRAFTING_UI) end },
    { "craft_close", function() pressKey(KeybindId.CRAFTING_UI) end },
    -- the building key is unbound by default: the calls its handler (xpUpdate.displayCharacterInfo) makes
    { "build_open", function() ISEntityUI.OpenBuildWindow(getPlayer()) end },
    { "build_close", function()
        local w = ISEntityUI.GetWindowInstance(0, "BuildWindow")
        if w then w:close(); w:removeFromUIManager() end
    end },
    { "health_open", function() pressKey(KeybindId.TOGGLE_HEALTH_PANEL) end },
    { "health_close", function() pressKey(KeybindId.TOGGLE_HEALTH_PANEL) end },
    { "skills_open", function() pressKey(KeybindId.TOGGLE_SKILL_PANEL) end },
    { "skills_close", function() pressKey(KeybindId.TOGGLE_SKILL_PANEL) end },
    { "map_open", function() pressKey(KeybindId.MAP) end,
        waitFor = function() return ISWorldMap_instance and ISWorldMap_instance:isVisible() end, waitName = "map_shown" },
    { "map_close", function() if ISWorldMap_instance and ISWorldMap_instance:isVisible() then ISWorldMap_instance:close() end end },
    { "pause_open", function() ToggleEscapeMenu(escapeKey()) end },
    { "pause_close", function() ToggleEscapeMenu(escapeKey()) end },
}

local function hideInventory(hide)
    for _, page in ipairs({ getPlayerInventory(0), getPlayerLoot(0) }) do
        if page then
            page:setVisible(not hide)
            if not hide then page:setPinned(); page.isCollapsed = false; page:clearMaxDrawHeight() end
        end
    end
end

local function closeCraft()
    local w = ISEntityUI.GetWindowInstance(0, "HandcraftWindow")
    if w then w:close(); w:removeFromUIManager() end
end

-- ui_script=scenes: steady states for the cost per frame (ui_scene_ms each, 8000), segmented by the marks
local SCENES = {
    { "scene_hud", function() hideInventory(true) end },
    { "scene_inv", function() hideInventory(false) end },
    { "scene_inv_hover", function() S.hoverT0 = getTimestampMs() end,
        tick = function()
            local pane = getPlayerInventory(0).inventoryPane
            local t = (getTimestampMs() - S.hoverT0) / 1000
            -- down and up the rows at 300 px/s, a little sideways so every frame moves the mouse
            local h = math.max(40, pane:getHeight() - 20)
            local y = pane:getAbsoluteY() + 10 + (math.floor(t * 300) % (2 * h))
            if y > pane:getAbsoluteY() + 10 + h then y = pane:getAbsoluteY() + 10 + 2 * h - (y - pane:getAbsoluteY() - 10) end
            getPerformance():pzoptUiMouse(pane:getAbsoluteX() + 60 + (math.floor(t * 60) % 20), y)
        end },
    { "scene_hud2", function() getPerformance():pzoptUiMouseOff(); hideInventory(true) end },
    { "scene_craft", function() ISEntityUI.OpenHandcraftWindow(getPlayer(), nil) end },
    { "scene_map", function() closeCraft(); pressKey(KeybindId.MAP) end },
    { "scene_end", function() if ISWorldMap_instance and ISWorldMap_instance:isVisible() then ISWorldMap_instance:close() end end },
}

-- ui_script=latency (2026-10-14, UI latency pass): every kind of UI interaction as a steady scene of ui_scene_ms (5000)
-- each, driven like a player (the game's mouse through the showcase aim override, the left button and the wheel through
-- pzoptUiButton / pzoptUiWheel, so UIManager dispatches presses, drags and wheel steps as for real input). Run with
-- --prop devUiTruth=true for the staleness ground truth (uiprof.py --truth), without it for the cost.
local function perf() return getPerformance() end
local function mouse(x, y) perf():pzoptUiMouse(x, y) end
local function park() mouse(getCore():getScreenWidth() / 2, getCore():getScreenHeight() / 2) end
local function tri(t, period) local p = (t % period) / period; return p < 0.5 and 2 * p or 2 - 2 * p end
local function sweepRect(t, x, y, w, h, speed)
    -- down and up the rect at speed px/s, a little sideways so every frame moves the mouse
    local period = 2 * math.max(20, h) / speed
    mouse(x + 0.3 * w + 0.2 * w * tri(t, 1.7), y + h * tri(t, period))
end
local function invPane() return getPlayerInventory(0).inventoryPane end
local function lootPane() return getPlayerLoot(0).inventoryPane end
local function rectOf(e) return e:getAbsoluteX(), e:getAbsoluteY(), e:getWidth(), e:getHeight() end
local function statNudge()
    local stats = getPlayer():getStats()
    for _, name in ipairs({ "HUNGER", "THIRST", "FATIGUE", "PANIC", "STRESS", "BOREDOM" }) do
        pcall(function()
            local st = CharacterStat[name]
            local v = stats:get(st)
            stats:set(st, (v + 0.07) % 0.9)
        end)
    end
end
local function itemNudge(n)
    local items = getPlayer():getInventory():getItems()
    for i = 0, math.min(items:size(), 12) - 1 do
        local it = items:get(i)
        if not instanceof(it, "InventoryContainer") then
            pcall(function() it:setName(it:getScriptItem():getDisplayName() .. (n % 2 == 0 and "" or " *")) end)
            pcall(function() it:setFavorite(n % 2 == 1) end)
            pcall(function() if it:getConditionMax() > 0 then it:setConditionNoSound(math.max(1, it:getConditionMax() - (n % 5))) end end)
            pcall(function() if instanceof(it, "DrainableComboItem") then it:setUsedDelta(1.0 - (n % 10) / 10) end end)
        end
    end
end

local LATENCY = {
    { "lat_hud", function() hideInventory(true); park() end },
    { "lat_hud_stats", function() S.n = 0 end,
        tick = function(t) local k = math.floor(t / 0.4); if k ~= S.n then S.n = k; statNudge() end end },
    { "lat_inv", function() hideInventory(false); park() end },
    { "lat_inv_passive", function() S.n = 0 end,
        tick = function(t) local k = math.floor(t / 0.35); if k ~= S.n then S.n = k; itemNudge(k) end end },
    { "lat_inv_hover", function() end,
        tick = function(t) local x, y, w, h = rectOf(invPane()); sweepRect(t, x, y + 10, w * 0.6, h - 20, 300) end },
    { "lat_loot_hover", function() end,
        tick = function(t) local x, y, w, h = rectOf(lootPane()); sweepRect(t, x, y + 10, w * 0.6, h - 20, 300) end },
    { "lat_btn_hover", function() end,
        tick = function(t)
            local p = getPlayerLoot(0).containerButtonPanel or getPlayerLoot(0)
            local x, y, w, h = rectOf(p)
            mouse(x + w / 2, y + 4 + (h - 8) * tri(t, 1.6))
        end },
    { "lat_tooltip", function() S.tipT = nil end,
        tick = function(t)
            -- rest on one row (the item tooltip opens), then slide sideways along it: the tooltip follows the mouse
            local x, y, w, h = rectOf(invPane())
            local px = t < 1.0 and 0.2 or 0.2 + 0.25 * tri(t - 1.0, 1.4)
            mouse(x + w * px, y + 40)
        end },
    { "lat_scroll", function() S.n = 0 end,
        tick = function(t)
            local x, y, w, h = rectOf(invPane())
            mouse(x + w * 0.3, y + h * 0.5)
            local k = math.floor(t / 0.15)
            if k ~= S.n then S.n = k; perf():pzoptUiWheel((math.floor(k / 8) % 2 == 0) and 1 or -1) end
        end },
    { "lat_drag_item", function() S.phase = nil end,
        tick = function(t)
            -- press on a row of the inventory, drag it over the loot window and back, release on the inventory again
            local ix, iy, iw, ih = rectOf(invPane())
            local lx, ly, lw, lh = rectOf(lootPane())
            local c = t % 2.5
            local sx, sy = ix + iw * 0.3, iy + 40
            if c < 0.15 then
                mouse(sx, sy); if S.phase ~= "down" then S.phase = "down"; perf():pzoptUiButton(false) end
            elseif c < 2.2 then
                if S.phase ~= "drag" then S.phase = "drag"; perf():pzoptUiButton(true) end
                local p = tri(c - 0.15, 2.05)
                mouse(sx + (lx + lw * 0.3 - sx) * p, sy + (ly + lh * 0.4 - sy) * p + 3 * math.sin(c * 20))
            else
                mouse(sx + 2, sy + 2); if S.phase ~= "up" then S.phase = "up"; perf():pzoptUiButton(false) end
            end
        end,
        stop = function() perf():pzoptUiButton(false) end },
    { "lat_window_drag", function() S.phase = nil; local p = getPlayerInventory(0); S.wx, S.wy = p:getX(), p:getY() end,
        tick = function(t)
            local p = getPlayerInventory(0)
            local c = t % 2.5
            local bx, by = S.wx + p:getWidth() * 0.45, S.wy + 6
            if c < 0.15 then
                mouse(bx, by); if S.phase ~= "rest" then S.phase = "rest"; perf():pzoptUiButton(false) end
            elseif c < 2.2 then
                if S.phase ~= "drag" then S.phase = "drag"; perf():pzoptUiButton(true) end
                local a = (c - 0.15) / 2.05 * 2 * math.pi
                mouse(bx + 120 * math.sin(a), by + 60 * (1 - math.cos(a)))
            else
                if S.phase ~= "up" then S.phase = "up"; perf():pzoptUiButton(false) end
            end
        end,
        stop = function() perf():pzoptUiButton(false); local p = getPlayerInventory(0); p:setX(S.wx); p:setY(S.wy) end },
    { "lat_ctx_hover", function()
            hideInventory(true)
            S.menu = ISWorldObjectContextMenu.createMenu(0, worldObjects(getPlayer()), getCore():getScreenWidth() / 2, getCore():getScreenHeight() / 2)
        end,
        tick = function(t)
            if not S.menu then return end
            local x, y, w, h = rectOf(S.menu)
            mouse(x + math.min(60, w / 2), y + 4 + (h - 8) * tri(t, 2.0))
        end,
        stop = function() if S.menu then S.menu:closeAll(); S.menu = nil end end },
    { "lat_craft_hover", function() park(); ISEntityUI.OpenHandcraftWindow(getPlayer(), nil) end,
        tick = function(t)
            local w = ISEntityUI.GetWindowInstance(0, "HandcraftWindow")
            if not w then return end
            local x, y, ww, h = rectOf(w)
            sweepRect(t, x + 10, y + 60, ww * 0.35, h - 80, 300)
        end,
        stop = function() closeCraft() end },
    { "lat_skills_xp", function() park(); pressKey(KeybindId.TOGGLE_SKILL_PANEL); S.n = 0 end,
        tick = function(t)
            local k = math.floor(t / 0.4)
            if k ~= S.n then S.n = k; pcall(function() getPlayer():getXp():AddXP(Perks.Fitness, 3) end) end
        end,
        stop = function() pressKey(KeybindId.TOGGLE_SKILL_PANEL) end },
    { "lat_map_pan", function() park(); pressKey(KeybindId.MAP); S.phase = nil end,
        tick = function(t)
            if not (ISWorldMap_instance and ISWorldMap_instance:isVisible()) or t < 1.6 then return end
            local cx, cy = getCore():getScreenWidth() / 2, getCore():getScreenHeight() / 2
            local c = (t - 1.6) % 1.6
            if c < 0.1 then
                mouse(cx, cy); if S.phase ~= "rest" then S.phase = "rest"; perf():pzoptUiButton(false) end
            elseif c < 1.4 then
                if S.phase ~= "drag" then S.phase = "drag"; perf():pzoptUiButton(true) end
                local a = (c - 0.1) / 1.3 * 2 * math.pi
                mouse(cx + 250 * math.sin(a), cy + 150 * (1 - math.cos(a)))
            else
                if S.phase ~= "up" then S.phase = "up"; perf():pzoptUiButton(false) end
            end
        end,
        stop = function()
            perf():pzoptUiButton(false)
            if ISWorldMap_instance and ISWorldMap_instance:isVisible() then ISWorldMap_instance:close() end
        end },
    -- the big stack (ui_stack_items): collapsed, expanded and idle, hovered, scrolled through, items changing
    { "lat_stack_collapsed", function() hideInventory(false); invPane():collapseAll(); park() end },
    { "lat_stack", function() invPane():expandAll(); park() end },
    { "lat_stack_hover", function() end,
        tick = function(t) local x, y, w, h = rectOf(invPane()); sweepRect(t, x, y + 10, w * 0.6, h - 20, 300) end },
    { "lat_stack_scroll", function() S.n = 0 end,
        tick = function(t)
            local x, y, w, h = rectOf(invPane())
            mouse(x + w * 0.3, y + h * 0.5)
            local k = math.floor(t / 0.12)
            if k ~= S.n then S.n = k; perf():pzoptUiWheel((math.floor(k / 14) % 2 == 0) and 1 or -1) end
        end },
    { "lat_stack_passive", function() S.n = 0; park() end,
        tick = function(t)
            local k = math.floor(t / 0.35)
            if k == S.n then return end
            S.n = k
            local items = getPlayer():getInventory():getItems()
            local seen = 0
            for i = 0, items:size() - 1 do
                local it = items:get(i)
                if it:getFullType() == stackTypeName then
                    seen = seen + 1
                    if seen % 20 == k % 20 then
                        pcall(function() it:setName(it:getScriptItem():getDisplayName() .. (k % 2 == 0 and "" or " *")) end)
                    end
                end
            end
        end,
        stop = function() invPane():collapseAll() end },
    { "lat_end", function() perf():pzoptUiMouseOff(); hideInventory(false) end },
}

local function tick()
    if rig == false then return end
    local player = getPlayer()
    if not player then return end
    local now = getTimestampMs()
    if rig == nil then
        local flags = readFlags()
        if not flags or (flags.ui_script or "") == "" then rig = false; return end
        local latency = flags.ui_script == "latency"
        local scenes = flags.ui_script == "scenes" or latency
        rig = { flags = flags, startMs = now + (tonumber(flags.ui_script_at) or 4) * 1000,
            steps = latency and LATENCY or (scenes and SCENES or STEPS),
            stepMs = scenes and (tonumber(flags.ui_scene_ms) or (latency and 5000 or 8000)) or (tonumber(flags.ui_step_ms) or 1200),
            reps = scenes and 1 or (tonumber(flags.ui_reps) or 3), rep = 1, i = 0 }
        print("[pzopt-ui] rig on: script=" .. flags.ui_script .. " reps=" .. rig.reps .. " step_ms=" .. rig.stepMs)
    end
    if not rig.setupDone then
        if now < rig.startMs - 2000 then return end
        rig.setupDone = true
        setup(player, rig.flags)
        rig.nextMs = rig.startMs
        return
    end
    if rig.waiting then
        local w = rig.waiting
        local ok, done = pcall(w.step.waitFor)
        if ok and done then
            local us = micros() - w.t0
            getPerformance():pzoptUiMark(w.step.waitName, us)
            print(string.format("[pzopt-ui] step %s rep=%d us=%d (since the key)", w.step.waitName, rig.rep, math.floor(us)))
            rig.waiting = nil
            rig.nextMs = now + rig.stepMs
        elseif now > w.timeoutMs then
            print("[pzopt-ui] step " .. w.step.waitName .. " timed out")
            rig.waiting = nil
            rig.nextMs = now + rig.stepMs
        end
        return
    end
    if now < rig.nextMs then
        local cur = rig.steps[rig.i]
        if cur and cur.tick then
            local ok, err = pcall(cur.tick, (now - (rig.stepStartMs or now)) / 1000)
            if not ok and not rig.tickErr then rig.tickErr = true; print("[pzopt-ui] tick error " .. tostring(err)) end
        end
        return
    end
    local prev = rig.steps[rig.i]
    if prev and prev.stop then
        local ok, err = pcall(prev.stop)
        if not ok then print("[pzopt-ui] stop error " .. tostring(err)) end
    end
    rig.tickErr = nil
    rig.stepStartMs = now
    rig.i = rig.i + 1
    if rig.i > #rig.steps then
        rig.i = 1
        rig.rep = rig.rep + 1
        if rig.rep > rig.reps then
            print("[pzopt-ui] script done")
            rig = false
            if (readFlags() or {}).ui_quit == "true" then getCore():quit() end
            return
        end
    end
    local step = rig.steps[rig.i]
    getPerformance():pzoptUiInput() -- the click / key this step stands for
    local t0 = micros()
    local ok, err = pcall(step[2])
    local us = micros() - t0
    getPerformance():pzoptUiMark(step[1], us)
    print(string.format("[pzopt-ui] step %s rep=%d us=%d%s", step[1], rig.rep, math.floor(us), ok and "" or (" error " .. tostring(err))))
    rig.nextMs = now + rig.stepMs
    if step.waitFor then rig.waiting = { step = step, t0 = t0, timeoutMs = now + 15000 } end
end

-- ui_lua_prof=Table,Table: every function of those global tables timed with the microsecond clock (inclusive);
-- every 2 s one line per function with calls: "[pzopt-uiprof] t=<ms> <Table.fn> calls=<n> us=<total> per=<us per call>".
local luaProf = nil
local function installLuaProf()
    local flags = readFlags()
    if not flags or (flags.ui_lua_prof or "") == "" then return end
    luaProf = { acc = {}, next = 0 }
    local n = 0
    for name in string.gmatch(flags.ui_lua_prof, "[^,]+") do
        local t = _G[name]
        if name == "ISUIElement" or name == "ISPanel" or name == "ISPanelJoypad" or name == "ISBaseObject" or name == "ISButton" then
            -- every screen inherits these: wrapping them left the game on the main menu (2026-09-30, luaprof1)
            print("[pzopt-uiprof] refusing base UI class " .. name)
        elseif type(t) == "table" then
            local fns = {}
            for fname, fn in pairs(t) do
                if type(fn) == "function" and type(fname) == "string" then fns[fname] = fn end
            end
            for fname, fn in pairs(fns) do
                local a = { 0, 0 }
                luaProf.acc[name .. "." .. fname] = a
                t[fname] = function(...)
                    local t0 = getPerformance():pzoptMicros()
                    local r1, r2, r3, r4 = fn(...)
                    a[1] = a[1] + (getPerformance():pzoptMicros() - t0)
                    a[2] = a[2] + 1
                    return r1, r2, r3, r4
                end
                n = n + 1
            end
        end
    end
    print("[pzopt-uiprof] " .. n .. " functions wrapped")
end
Events.OnGameBoot.Add(installLuaProf)

-- ui_inv_update_prof=true: ISInventoryPage.update replaced by a copy of the vanilla function (42.21) with a microsecond
-- clock around each block; every 2 s "[pzopt-invupd] t=<ms> calls=<n> <block>=<us per call> ...". Dev rig only.
local invUpd = nil
local function installInvUpdateProf()
    local flags = readFlags()
    if not flags or flags.ui_inv_update_prof ~= "true" then return end
    invUpd = { acc = {}, calls = 0, next = 0 }
    local function t() return getPerformance():pzoptMicros() end
    local function add(k, v) invUpd.acc[k] = (invUpd.acc[k] or 0) + v end
    ISInventoryPage.update = function(self)
        invUpd.calls = invUpd.calls + 1
        local t0 = t()
        local playerObj = getSpecificPlayer(self.player)
        if self.inventory:getEffectiveCapacity(playerObj) ~= self.capacity then
            self.capacity = self.inventory:getEffectiveCapacity(playerObj)
        end
        local t1 = t(); add("capacity", t1 - t0)
        self:updateContainerHighlight()
        local t2 = t(); add("highlight", t2 - t1)
        if (ISMouseDrag.dragging ~= nil and #ISMouseDrag.dragging > 0) or self.pin then
            self.collapseCounter = 0;
            if isClient() and self.isCollapsed then
                self.inventoryPane.inventory:requestSync();
            end
            self.isCollapsed = false;
            self:clearMaxDrawHeight();
            self.collapseCounter = 0;
        end
        local t3 = t(); add("pin", t3 - t2)
        if not self.onCharacter then
            if self.lastDir ~= playerObj:getDir() then
                self.lastDir = playerObj:getDir()
                self:refreshBackpacks()
            elseif self.lastSquare ~= playerObj:getCurrentSquare() then
                self.lastSquare = playerObj:getCurrentSquare()
                self:refreshBackpacks()
            end
            local object = self.inventory and self.inventory:getParent() or nil
            if #self.backpacks > 1 and instanceof(object, "IsoThumpable") and object:isLockedToCharacter(playerObj) then
                local currentIndex = self:getCurrentBackpackIndex()
                local unlockedIndex = self:prevUnlockedContainer(currentIndex, false)
                if unlockedIndex == -1 then
                    unlockedIndex = self:nextUnlockedContainer(currentIndex, false)
                end
                if unlockedIndex ~= -1 then
                    if playerObj:getJoypadBind() ~= -1 then
                        self.backpackChoice = unlockedIndex
                    end
                    self:selectContainer(self.backpacks[unlockedIndex])
                end
            end
        end
        local t4 = t(); add("loot", t4 - t3)
        if self.controlsUI then
            self.controlsUI:arrange()
            self.inventoryPane:setHeight(self.height - self.inventoryPane.y - self.resizeWidget.height - self.controlsUI.height)
            self.inventoryPane:setY(self:titleBarHeight())
        end
        local t5 = t(); add("controls", t5 - t4)
        self.containerButtonPanel:setHeight(self.inventoryPane.height)
        local t6 = t(); add("btnpanel_h", t6 - t5)
        self.containerButtonPanel:setY(self.inventoryPane.y)
        local t7 = t(); add("btnpanel_y", t7 - t6)
        self.containerButtonPanel:setScrollHeight(self.backpacks[#self.backpacks]:getBottom())
        local t8 = t(); add("btnpanel_scroll", t8 - t7)
        self:updateContainerOpenCloseSounds()
        local t9 = t(); add("sounds", t9 - t8)
        add("total", t9 - t0)
    end
    print("[pzopt-invupd] ISInventoryPage.update instrumented")
end
Events.OnGameStart.Add(installInvUpdateProf)

local function invUpdTick()
    if not invUpd or invUpd.calls == 0 then return end
    local now = getTimestampMs()
    if now < invUpd.next then return end
    invUpd.next = now + 2000
    local parts = {}
    for k, v in pairs(invUpd.acc) do table.insert(parts, string.format("%s=%.1f", k, v / invUpd.calls)) end
    table.sort(parts)
    print(string.format("[pzopt-invupd] t=%d calls=%d %s", now, invUpd.calls, table.concat(parts, " ")))
    invUpd.acc = {}; invUpd.calls = 0
end

local function luaProfTick()
    if not luaProf then return end
    local now = getTimestampMs()
    if now < luaProf.next then return end
    luaProf.next = now + 2000
    local rows = {}
    for k, a in pairs(luaProf.acc) do
        if a[2] > 0 then table.insert(rows, { k, a[1], a[2] }) end
        a[1] = 0; a[2] = 0
    end
    table.sort(rows, function(x, y) return x[2] > y[2] end)
    for i = 1, math.min(#rows, 30) do
        local r = rows[i]
        print(string.format("[pzopt-uiprof] t=%d %s calls=%d us=%d per=%.1f", now, r[1], r[3], math.floor(r[2]), r[2] / r[3]))
    end
end

Events.OnTickEvenPaused.Add(function()
    pcall(luaProfTick)
    pcall(invUpdTick)
    local ok, err = pcall(tick)
    if not ok then print("[pzopt-ui] rig error " .. tostring(err)); rig = false end
end)
