-- stockrig=1 (2026-10-24, the PZO comparison, harness/pzo-compare/): the bench routes in Lua with the game's own calls,
-- for a game dir WITHOUT our classes (truly stock, or stock + PZO, whose IncompatibleModGuard disables PZO when any of
-- our files is there). pzopt.Harness is absent there, so this file does what it does for the three benches:
--   mode=bench (--preset louisville): world ready -> god mode + invisible, worn items pinned (chanceToFall 0), the
--     population multipliers forced (set in OnLoadedMapZones, i.e. after SandboxOptions.load and before the popman's
--     init reads them; Harness pushes them at world-ready through onConfigReloaded, which Lua cannot reach), teleport to
--     start=, zoom to the widest level, settle, then route=S:<n> at speed= tiles/s by whole-square teleports with the
--     facing spun turn= deg/s (Harness's bench walk);
--   mode=drive (drive-120): the player out of any car, a Base.RaceCar12 spawned on the path start facing east and the
--     player seated, zoom widest, settle, engine on + the game's cruise control at kmh= (re-asserted every tick), no
--     steering (KY-60 runs straight east; Harness's pilot steers 0.00 there after the first metres); the route ends at the
--     path's last x (or max_seconds); weather=storm pins the storm overrides every tick and strikes lightning 60 tiles
--     away every thunder_secs from the route start, as pzopt.Scene does.
-- Everything else goes through files in Zomboid/Lua (getFileWriter allows .txt only): pzostock-started.txt (the
-- clicker stops pressing click-to-start), pzostock-schedule.txt / pzostock-bench.txt (the wrapper mirrors them to
-- Zomboid/pzopt-schedule.out / pzopt-bench.out, which run.sh's MangoHud scheduler and analyze.py read), and it waits
-- for pzostock-logdone.txt (run.sh's pzopt-logdone mirrored back) before quitting. Lines: [pzostock].
-- Without stockrig in the flag file this file does nothing (it ships in the harness mod of every run).

local FLAG_FILE = "pzopt-harness.txt"

local function readFlags()
    local reader = getFileReader(FLAG_FILE, false)
    if not reader then return nil end
    local flags, n = {}, 0
    while true do
        local line = reader:readLine()
        if line == nil then break end
        local k, v = string.match(line, "^%s*([%w_]+)%s*=%s*(.-)%s*$")
        if k then flags[k] = v; n = n + 1 end
    end
    reader:close()
    if n == 0 then return nil end
    return flags
end

local function writeFile(name, text)
    local w = getFileWriter(name, true, false)
    if not w then print("[pzostock] could not write " .. name); return end
    w:write(text)
    w:close()
end

local function appendFlag(line)
    local w = getFileWriter(FLAG_FILE, true, true)
    if not w then return end
    w:write(line .. "\n")
    w:close()
end

local function fileExists(name)
    local r = getFileReader(name, false)
    if r then r:close(); return true end
    return false
end

local function log(s) print("[pzostock] " .. s) end

local function num(flags, k, d) local v = tonumber(flags[k] or ""); if v then return v end; return d end

-- population before the popman reads the sandbox options (see the header)
Events.OnLoadedMapZones.Add(function()
    local flags = readFlags()
    if not flags or not flags.stockrig or not flags.population or flags.population == "" then return end
    local v = flags.population == "max" and 4.0 or tonumber(flags.population)
    if not v then return end
    local so = getSandboxOptions()
    for _, k in ipairs({ "ZombieConfig.PopulationMultiplier", "ZombieConfig.PopulationStartMultiplier", "ZombieConfig.PopulationPeakMultiplier" }) do
        so:getOptionByName(k):setValue(v)
    end
    log("population multipliers set to " .. v .. " before the popman init (OnLoadedMapZones)")
end)

-- the storm Scene.assertWeather pins (no time_of_day: the daylight values times the storm's factors)
local function pinStorm(cm)
    local function set(id, v) cm:getClimateFloat(id):setOverride(v, 1.0) end
    set(3, 1.0)   -- FLOAT_PRECIPITATION_INTENSITY
    set(8, 1.0)   -- FLOAT_CLOUD_INTENSITY
    set(6, 0.9)   -- FLOAT_WIND_INTENSITY
    set(7, 0.7)   -- FLOAT_WIND_ANGLE_INTENSITY
    set(5, 0.0)   -- FLOAT_FOG_INTENSITY
    set(0, 0.3)   -- FLOAT_DESATURATION
    set(1, 0.4)   -- FLOAT_GLOBAL_LIGHT_INTENSITY
    set(9, 0.45)  -- FLOAT_AMBIENT
    set(11, 0.45) -- FLOAT_DAYLIGHT_STRENGTH
    cm:getClimateBool(0):setOverride(false) -- BOOL_IS_SNOW
end

local rig = nil

local function worldReady(player, now)
    local flags = rig.flags
    player:setGodMod(true, true)
    player:setInvisible(true, true)
    local pinned, worn = 0, player:getWornItems()
    if worn then
        for i = 0, worn:size() - 1 do
            local it = worn:getItemByIndex(i)
            if it and instanceof(it, "Clothing") and it:getChanceToFall() > 0 then it:setChanceToFall(0); pinned = pinned + 1 end
        end
    end
    if rig.storm then
        getClimateManager():stopWeatherAndThunder()
        pinStorm(getClimateManager())
    end
    local px, py = math.floor(player:getX()), math.floor(player:getY())
    if rig.drive then
        if player:getVehicle() then
            log("leaving the vehicle the character was saved in (" .. tostring(player:getVehicle():getScriptName()) .. ")")
            player:getVehicle():exit(player)
        end
        local sq = getCell():getGridSquare(rig.pathX0, rig.pathY0, 0)
        if not sq then log("the path start square is not loaded; teleporting there"); player:teleportTo(rig.pathX0, rig.pathY0, 0); return false end
        local v = addVehicleDebug("Base.RaceCar12", IsoDirections.E, 0, sq)
        if not v then error("could not spawn the car") end
        v:repair()
        v:setAngles(0, 90, 0) -- addVehicleDebug adds up to 11 deg of random heading; KY-60 runs due east
        player:getInventory():AddItem(v:createVehicleKey())
        if not v:enter(0, player) then error("could not seat the player") end
        rig.v = v
        log(string.format("spawned %s at %.1f,%.1f facing %.1f, player seated %s", tostring(v:getScriptName()), v:getX(), v:getY(), v:getAngleY(), tostring(player:getVehicle() == v)))
    else
        if rig.startX then
            log("teleporting from " .. px .. "," .. py .. " to start " .. rig.startX .. "," .. rig.startY)
            player:teleportTo(rig.startX, rig.startY, 0)
            rig.x, rig.y = rig.startX + 0.5, rig.startY + 0.5
        else
            rig.x, rig.y = player:getX(), player:getY()
        end
    end
    getCore():setAutoZoom(0, false)
    for _ = 1, 12 do getCore():doZoomScroll(0, 1) end
    rig.readyMs = now
    rig.startMs = now + rig.settle * 1000
    writeFile("pzostock-schedule.txt", "world_ready_epoch_ms=" .. now .. "\nroute_start_epoch_ms=" .. rig.startMs .. "\nsettle=" .. rig.settle .. "\n")
    log("world ready at " .. px .. "," .. py .. "; god mode, invisible, " .. pinned .. " worn items pinned; zombies loaded "
        .. getCell():getZombieList():size() .. "; population multiplier now "
        .. tostring(getSandboxOptions():getOptionByName("ZombieConfig.PopulationMultiplier"):getValue()) .. "; settling " .. rig.settle .. " s")
    return true
end

local function finish(status, now)
    rig.state = "end"
    rig.endMs = now
    local player = getPlayer()
    if rig.v then rig.v:setRegulator(false); rig.v:setRegulatorSpeed(0) end
    local zcount = getCell():getZombieList():size()
    local out = { "mode=" .. tostring(rig.flags.mode), "rig=stockrig-lua", "route_status=" .. status,
        "route_start_epoch_ms=" .. rig.runStartMs, "route_end_epoch_ms=" .. now,
        "route_seconds=" .. string.format("%.2f", (now - rig.runStartMs) / 1000),
        "zoom=" .. string.format("%.2f", getCore():getZoom(0)), "max_zoom=" .. string.format("%.2f", getCore():getMaxZoom()),
        "zombies_loaded=" .. zcount, "weather=" .. (rig.storm and "storm" or "save"), "thunder_count=" .. rig.thunders }
    if rig.drive then
        table.insert(out, "vehicle=" .. tostring(rig.v and rig.v:getScriptName()))
        table.insert(out, string.format("vehicle_end=%.1f,%.1f", rig.v:getX(), rig.v:getY()))
        table.insert(out, string.format("drive_max_lateral=%.2f", rig.maxLat))
        table.insert(out, "drive_reseats=" .. rig.reseats)
    else
        table.insert(out, "start=" .. tostring(rig.startX) .. "," .. tostring(rig.startY))
        table.insert(out, string.format("player_end=%d,%d", math.floor(player:getX()), math.floor(player:getY())))
    end
    writeFile("pzostock-bench.txt", table.concat(out, "\n") .. "\n")
    log("route " .. status .. " in " .. string.format("%.1f", (now - rig.runStartMs) / 1000) .. " s; zombies " .. zcount .. "; waiting for the MangoHud log to close")
end

local function tick()
    if rig == false then return end
    local player = getPlayer()
    if not player then return end
    local now = getTimestampMs()
    if rig == nil then
        local flags = readFlags()
        if not flags or not flags.stockrig then rig = false; return end
        rig = { flags = flags, state = "wait", drive = flags.mode == "drive", storm = flags.weather == "storm",
            settle = num(flags, "settle", 5), speed = num(flags, "speed", 18), turn = num(flags, "turn", 0),
            kmh = num(flags, "kmh", 60), maxSecs = num(flags, "max_seconds", 90), thunderSecs = num(flags, "thunder_secs", 6),
            thunders = 0, maxLat = 0, reseats = 0, angle = 0 }
        local sx, sy = string.match(flags.start or "", "^(%-?%d+),(%-?%d+)")
        if sx then rig.startX, rig.startY = tonumber(sx), tonumber(sy) end
        local dir, len = string.match(flags.route or "", "^([NSEW]):(%d+)$")
        rig.legDir, rig.legLen = dir or "S", tonumber(len or "150")
        if flags.path then
            local pts = {}
            for x, y in string.gmatch(flags.path, "(%-?[%d%.]+),(%-?[%d%.]+)") do table.insert(pts, { tonumber(x), tonumber(y) }) end
            rig.pathX0, rig.pathY0 = math.floor(pts[1][1]), math.floor(pts[1][2])
            rig.pathYc = pts[1][2]
            rig.pathX1 = pts[#pts][1]
        end
        writeFile("pzostock-started.txt", "player_epoch_ms=" .. now .. "\n")
        appendFlag("started=1") -- back at the main menu = the run is over (pzopt_harness.lua quits to the desktop)
        log("player exists; rig " .. (rig.drive and "drive" or "bench") .. (rig.storm and " + storm" or ""))
    end
    local s = rig
    if s.storm and s.state ~= "end" then pinStorm(getClimateManager()) end
    if s.state == "wait" then
        if not player:getCurrentSquare() then return end
        if worldReady(player, now) then s.state = "settle" end
        return
    end
    if s.state == "settle" then
        if s.drive and player:getVehicle() ~= s.v and s.reseats < 3 then
            s.reseats = s.reseats + 1
            log("player not in the rig's car during the settle (in " .. tostring(player:getVehicle()) .. "); re-seated " .. tostring(s.v:enter(0, player)))
        end
        if now < s.startMs then return end
        for _ = 1, 12 do getCore():doZoomScroll(0, 1) end
        if s.drive then
            s.v:setKeysInIgnition(true)
            s.v:engineDoRunning()
            s.v:setRegulator(true)
            s.v:setRegulatorSpeed(s.kmh)
        end
        s.state = "run"
        s.runStartMs = now
        s.lastMs = now
        s.lastLogMs = 0
        s.lastThunderMs = now
        log("route start epoch_ms=" .. now .. " zoom=" .. string.format("%.2f", getCore():getZoom(0)) .. " zombies=" .. getCell():getZombieList():size())
        return
    end
    if s.state == "run" then
        local dt = math.min((now - s.lastMs) / 1000, 0.1)
        s.lastMs = now
        if s.storm and s.thunderSecs > 0 and now - s.lastThunderMs >= s.thunderSecs * 1000 then
            s.lastThunderMs = now
            s.thunders = s.thunders + 1
            getClimateManager():getThunderStorm():triggerThunderEvent(math.floor(player:getX()) + 60, math.floor(player:getY()) - 60, true, true, true)
        end
        if s.drive then
            local v = s.v
            if player:getVehicle() ~= v and s.reseats < 3 then
                s.reseats = s.reseats + 1
                log("driver check failed (in " .. tostring(player:getVehicle()) .. "); re-seated " .. tostring(v:enter(0, player)))
            end
            if not v:isRegulator() or v:getRegulatorSpeed() ~= s.kmh then v:setRegulator(true); v:setRegulatorSpeed(s.kmh) end
            if not v:isEngineRunning() then v:setKeysInIgnition(true); v:engineDoRunning() end
            local lat = math.abs(v:getY() - s.pathYc)
            if lat > s.maxLat then s.maxLat = lat end
            if now - s.lastLogMs >= 1000 then
                s.lastLogMs = now
                log(string.format("drive t=%.0fs pos=%.1f,%.1f kmh=%.1f yaw=%.1f zoom=%.2f", (now - s.runStartMs) / 1000, v:getX(), v:getY(),
                    v:getCurrentSpeedKmHour(), v:getAngleY(), getCore():getZoom(0)))
            end
            if v:getX() >= s.pathX1 - 4 then finish("complete", now)
            elseif (now - s.runStartMs) / 1000 >= s.maxSecs then finish("timeout", now) end
            return
        end
        local step = s.speed * dt
        local dx, dy = 0, 0
        if s.legDir == "S" then dy = 1 elseif s.legDir == "N" then dy = -1 elseif s.legDir == "E" then dx = 1 else dx = -1 end
        s.done = (s.done or 0) + step
        if s.done > s.legLen then step = step - (s.done - s.legLen); s.done = s.legLen end
        s.x, s.y = s.x + dx * step, s.y + dy * step
        if math.floor(s.x) ~= math.floor(player:getX()) or math.floor(s.y) ~= math.floor(player:getY()) then
            player:teleportTo(math.floor(s.x), math.floor(s.y), 0)
        end
        if s.turn ~= 0 then
            s.angle = (s.angle + s.turn * dt) % 360
            player:setDirectionAngle(s.angle)
        end
        if now - s.lastLogMs >= 5000 then
            s.lastLogMs = now
            log(string.format("walk t=%.0fs pos=%d,%d done=%.0f/%d zombies=%d zoom=%.2f", (now - s.runStartMs) / 1000, math.floor(player:getX()),
                math.floor(player:getY()), s.done, s.legLen, getCell():getZombieList():size(), getCore():getZoom(0)))
        end
        if s.done >= s.legLen then finish("complete", now) end
        return
    end
    if s.state == "end" then
        if fileExists("pzostock-logdone.txt") or now - s.endMs >= 10000 then
            log("quitting (" .. (fileExists("pzostock-logdone.txt") and "MangoHud log closed" or "10 s after the route end, no log-done marker") .. ")")
            rig = false
            getCore():quit()
        end
    end
end

Events.OnTick.Add(function()
    local ok, err = pcall(tick)
    if not ok then print("[pzostock] rig error " .. tostring(err)); rig = false; getCore():quit() end
end)
