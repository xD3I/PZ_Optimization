-- pzopt harness: pause-menu Escape rig (2026-10-19, Workshop reports after release 26229d22: "Esc opens the pause menu and
-- it goes back to the game right away").
--
-- esc_check=S   S seconds after the player exists, real Escape presses (xdotool, sent by harness/esc-check.sh, which
--               watches Zomboid/Lua/pzopt-esc-press.txt) in this order, each followed by a check 2.5 s later:
--                 1. Esc            -> the pause menu is up and stays up
--                 2. Esc            -> the game again
--                 3. Esc            -> the pause menu; then the PZ OPTIMIZATION screen opened from it (its item's click)
--                 4. Esc            -> the screen closes, the pause menu stays
--                 5. Esc            -> the game again
--               Every MainScreen:setVisible in a game is logged with the frame and the press it followed, so a press that
--               toggles twice (open + close in one frame) shows as two calls. `[pzopt-esc]` lines; screenshots
--               Screenshots/pzopt-esc-<n>.png; "[pzopt-esc] result PASS|FAIL ..." at the end.
--   esc_quit=true  quit the game when done
--   esc_modwrap=true  at load, wrap the menu key the way mods do (this file is a mod: it loads after the game's Lua and
--               ours): Events.OnKeyPressed.Remove(ToggleEscapeMenu), ToggleEscapeMenu = a wrapper calling the old one,
--               Events.OnKeyPressed.Add(ToggleEscapeMenu)

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

local function log(s) print("[pzopt-esc] " .. s) end

local rig = nil
local frame = 0
local pressN = 0

local function hook()
    if not MainScreen or MainScreen.pzoptEscRigHooked then return end
    MainScreen.pzoptEscRigHooked = true
    local orig = MainScreen.setVisible
    function MainScreen:setVisible(v, ...)
        if self.inGame and rig then
            log(string.format("setVisible(%s) frame=%d after press %d (was %s)", tostring(v), frame, pressN, tostring(self:isVisible())))
        end
        return orig(self, v, ...)
    end
end

local function pauseUp()
    local ms = MainScreen.instance
    return ms ~= nil and ms.inGame and ms:isVisible()
end

local function screenUp()
    local ms = MainScreen.instance
    local s = ms and ms.pzoptMenuScreen
    return s ~= nil and s:isVisible()
end

local function press()
    pressN = pressN + 1
    local w = getFileWriter("pzopt-esc-press.txt", true, true) -- appended: the wrapper presses once per line
    w:write("Escape " .. pressN .. "\n")
    w:close()
    log("requested Escape #" .. pressN .. " frame=" .. frame)
end

-- each step: an action, then 2.5 s later the expected state
local steps = {
    { act = press, want = { pause = true, screen = false } },
    { act = press, want = { pause = false, screen = false } },
    { act = press, want = { pause = true, screen = false } },
    { act = function()
        local ok, err = pcall(function() PzoptMainMenu.show() end)
        log("opened PZ OPTIMIZATION from the pause menu: " .. tostring(ok) .. " " .. tostring(err or ""))
    end, want = { pause = true, screen = true } },
    { act = press, want = { pause = true, screen = false } },
    { act = press, want = { pause = false, screen = false } },
}

local function tick()
    frame = frame + 1
    if rig == false then return end
    if not getPlayer() then return end
    if rig == nil then
        local flags = readFlags()
        if not flags or not flags.esc_check or flags.esc_check == "" then rig = false; return end
        rig = { at = getTimestampMs() + (tonumber(flags.esc_check) or 8) * 1000, step = 1, phase = "act",
            quit = flags.esc_quit == "true", fails = 0 }
        hook()
        log("armed: first press in " .. tostring(flags.esc_check) .. " s")
        return
    end
    local now = getTimestampMs()
    if now < rig.at then return end
    local st = steps[rig.step]
    if not st then
        log(string.format("result %s (%d of %d checks failed)", rig.fails == 0 and "PASS" or "FAIL", rig.fails, #steps))
        rig = false
        if MainScreen.instance and MainScreen.instance.inGame and MainScreen.instance:isVisible() then
            ToggleEscapeMenu(getCore():getKey("Main Menu") ~= 0 and getCore():getKey("Main Menu") or Keyboard.KEY_ESCAPE)
        end
        getCore():quit()
        return
    end
    if rig.phase == "act" then
        st.act()
        rig.phase, rig.at = "check", now + 2500
    else
        local p, s = pauseUp(), screenUp()
        local ok = p == st.want.pause and s == st.want.screen
        if not ok then rig.fails = rig.fails + 1 end
        log(string.format("check %d %s: pause menu %s (want %s), PZ OPTIMIZATION %s (want %s), frame=%d", rig.step,
            ok and "ok" or "FAIL", tostring(p), tostring(st.want.pause), tostring(s), tostring(st.want.screen), frame))
        getCore():TakeFullScreenshot("pzopt-esc-" .. rig.step .. ".png")
        rig.step, rig.phase, rig.at = rig.step + 1, "act", now + 1500
    end
end

Events.OnTickEvenPaused.Add(tick)

do
    local flags = readFlags()
    if flags and flags.esc_modwrap == "true" and ToggleEscapeMenu then
        local old = ToggleEscapeMenu
        Events.OnKeyPressed.Remove(ToggleEscapeMenu)
        ToggleEscapeMenu = function(key) return old(key) end
        Events.OnKeyPressed.Add(ToggleEscapeMenu)
        log("esc_modwrap: ToggleEscapeMenu wrapped the way a mod does")
    end
end
