-- Actual OS-driven UI callback fixture for harness/input-thread-win.ps1; no synthetic Lua input calls.
local enabled = false
local flags = getFileReader("pzopt-harness.txt", false)
if flags then
    while true do
        local line = flags:readLine()
        if line == nil then break end
        if string.match(line, "^%s*input_thread%s*=%s*1%s*$") then enabled = true end
    end
    flags:close()
end
if not enabled then return end

local ARM = "pzopt-input-start.txt"
local DONE = "pzopt-input-done.txt"
local BURST = "pzopt-input-burst.txt"
local fixture = nil

local function fileExists(name)
    local r = getFileReader(name, false)
    if not r then return false end
    r:close()
    return true
end

local function log(s)
    print("[pzopt-input] " .. s)
end

local function near(a, b, tolerance)
    return math.abs(a - b) <= tolerance
end

local function createFixture()
    local w, h = getCore():getScreenWidth(), getCore():getScreenHeight()
    local p = ISPanel:new(0, 0, w, h)
    p:initialise()
    p:setWantMouseEvents(true)
    p:setWantKeyEvents(true)
    p:setForceCursorVisible(true)
    p:instantiate()
    p.background = false
    p.shortDown = 0
    p.shortDownAtA = false
    p.shortUpAtB = false
    p.shortUpSeen = false
    p.shortUp = 0
    p.shortOutsideUp = 0
    p.moveCallbacks = 0
    p.syntheticMoveCallbacks = 0
    p.wheelCallbacks = 0
    p.keyCallbacks = 0
    p.clicks = 0
    p.buttonOutsideUps = 0
    p.textChanges = 0
    p.text = ""

    local bw, bh = 160, 70
    local bx, by = math.floor(w / 2 - bw / 2), math.floor(h / 2 - bh / 2)
    local button = ISButton:new(bx, by, bw, bh, "OS input click target", p, function(target)
        target.clicks = target.clicks + 1
        log(string.format("CALLBACK click count=%d", target.clicks))
    end)
    button:initialise()
    button:instantiate()
    button:setWantMouseEvents(true)
    local originalButtonOutside = button.onMouseUpOutside
    button.onMouseUpOutside = function(self, x, y)
        p.buttonOutsideUps = p.buttonOutsideUps + 1
        log(string.format("CALLBACK button_up_outside n=%d x=%.1f y=%.1f", p.buttonOutsideUps, x, y))
        originalButtonOutside(self, x, y)
    end
    p:addChild(button)
    p.button = button

    local ew, eh = 240, 44
    local ex, ey = math.floor(w / 2 - ew / 2), math.floor(h * 0.32)
    local entry = ISTextEntryBox:new("", ex, ey, ew, eh)
    entry:initialise()
    entry:instantiate()
    entry.target = p
    entry:setWantMouseEvents(true)
    entry.onFocus = function(self, x, y)
        log(string.format("CALLBACK entry_focus x=%.1f y=%.1f", x, y))
        self:focus()
    end
    entry.onTextChangeFunction = function(target, box)
        target.textChanges = target.textChanges + 1
        target.text = box:getText()
        log(string.format("CALLBACK text_change n=%d text=%s", target.textChanges, target.text:gsub("[^%w]", "?")))
    end
    p:addChild(entry)
    p.entry = entry

    function p:onMouseDown(x, y)
        self.shortDown = self.shortDown + 1
        self.lastDownX, self.lastDownY = x, y
        if near(x, fixture.width * 0.18, 14) and near(y, fixture.height * 0.58, 14) then self.shortDownAtA = true end
        log(string.format("CALLBACK panel_down n=%d x=%.1f y=%.1f", self.shortDown, x, y))
        return true
    end
    function p:onMouseUp(x, y)
        self.shortUp = self.shortUp + 1
        self.lastUpX, self.lastUpY = x, y
        if self.shortDownAtA and not self.shortUpSeen then
            self.shortUpSeen = true
            self.shortUpAtB = near(x, fixture.buttonX, 12) and near(y, fixture.buttonY, 12)
        end
        log(string.format("CALLBACK panel_up n=%d x=%.1f y=%.1f", self.shortUp, x, y))
        return true
    end
    function p:onMouseUpOutside(x, y)
        self.shortOutsideUp = self.shortOutsideUp + 1
        self.lastUpX, self.lastUpY = x, y
        if self.shortDownAtA and not self.shortUpSeen then
            self.shortUpSeen = true
            self.shortUpAtB = near(x, fixture.buttonX, 12) and near(y, fixture.buttonY, 12)
        end
        log(string.format("CALLBACK panel_up_outside n=%d x=%.1f y=%.1f", self.shortOutsideUp, x, y))
        return true
    end
    function p:onMouseMove(dx, dy)
        self.moveCallbacks = self.moveCallbacks + 1
        if fileExists(BURST) then self.syntheticMoveCallbacks = self.syntheticMoveCallbacks + 1 end
        if self.moveCallbacks <= 12 or self.moveCallbacks % 1000 == 0 then
            log(string.format("CALLBACK panel_move n=%d dx=%.1f dy=%.1f x=%.1f y=%.1f", self.moveCallbacks, dx, dy, getMouseX(), getMouseY()))
        end
        return true
    end
    function p:onMouseWheel(delta)
        self.wheelCallbacks = self.wheelCallbacks + 1
        self.lastWheelDelta = delta
        self.lastWheelX, self.lastWheelY = getMouseX(), getMouseY()
        log(string.format("CALLBACK panel_wheel n=%d delta=%.2f x=%.1f y=%.1f", self.wheelCallbacks, delta, self.lastWheelX, self.lastWheelY))
        return true
    end
    function p:onKeyPress(key)
        self.keyCallbacks = self.keyCallbacks + 1
        self.lastKey = key
        log(string.format("CALLBACK panel_key_press n=%d key=%d", self.keyCallbacks, key))
    end
    function p:onKeyRelease(key)
        log(string.format("CALLBACK panel_key_release key=%d", key))
    end
    function p:isKeyConsumed(key)
        return true
    end


    p:addToUIManager()
    p:setVisible(true)
    fixture = { panel = p, button = button, entry = entry, width = w, height = h,
        buttonX = bx + bw / 2, buttonY = by + bh / 2, entryX = ex + ew / 2, entryY = ey + eh / 2,
        armed = false, done = false }
    log(string.format("READY screen=%dx%d button=%.0f,%.0f entry=%.0f,%.0f", w, h,
        fixture.buttonX, fixture.buttonY, fixture.entryX, fixture.entryY))
end

local function assertCallbacks()
    local f, p = fixture, fixture.panel
    local bx, by = f.buttonX, f.buttonY
    local cx, cy = f.width * 0.78, f.height * 0.78
    local shortUp = p.shortUpAtB
    local finalX, finalY = getMouseX(), getMouseY()
    local finalText = p.entry:getText()
    local checks = {
        { "short_press_release", p.shortDownAtA and shortUp
            and near(finalX, cx, 18) and near(finalY, cy, 18),
            string.format("down_at_A=%s up_at_B=%s final=%.1f,%.1f", tostring(p.shortDownAtA), tostring(shortUp), finalX, finalY) },
        { "click_once", p.clicks == 1, "observed_click_callbacks=" .. p.clicks },
        { "drag_release_outside", p.buttonOutsideUps >= 1 and p.clicks == 1,
            "button_outside_release_callbacks=" .. p.buttonOutsideUps .. " clicks=" .. p.clicks },
        { "wheel_at_B", p.wheelCallbacks >= 1 and p.lastWheelDelta ~= nil
            and near(p.lastWheelX, bx, 18) and near(p.lastWheelY, by, 18),
            string.format("wheel_callbacks=%d delta=%.2f at=%.1f,%.1f expected=%.1f,%.1f", p.wheelCallbacks, p.lastWheelDelta or 0, p.lastWheelX or -1, p.lastWheelY or -1, bx, by) },
        { "keyboard_and_text", p.keyCallbacks >= 1 and p.textChanges >= 1 and finalText == "z",
            "key_callbacks=" .. p.keyCallbacks .. " text_changes=" .. p.textChanges .. " final_text=" .. tostring(finalText) },
        { "focus_cancel_no_ghost", p.clicks == 1,
            "click_callbacks_after_drag_and_focus_cancel=" .. p.clicks },
        { "synthetic_burst_observed", p.syntheticMoveCallbacks > 0,
            "actual_UI_callbacks_during_8000_synthetic_records=" .. p.syntheticMoveCallbacks .. " (Windows may coalesce; not a hardware-rate measurement)" },
    }
    local all = true
    for _, check in ipairs(checks) do
        local result = check[2] and "PASS" or "FAIL"
        if not check[2] then all = false end
        log("ASSERT " .. check[1] .. " " .. result .. " " .. check[3])
    end
    if all then
        log("PASS all callback assertions")
    else
        log("FAIL one or more actual callback assertions failed")
    end
end

local function applyMouseOption(key, value, id)
    local menu = MainScreen.instance
    fixture.panel:setVisible(false)
    local options = menu.mainOptions
    if not options or not options:getIsVisible() then
        MainScreen.onMenuItemMouseDownMainMenu(menu.optionsOption, 0, 0)
        options = menu.mainOptions
    end
    options.tabs:activateView("Optimizations")
    local option = options.gameOptions:get("pzopt." .. key)
    assert(option, key .. " control missing")
    assert(not option.control.disabled, key .. " unexpectedly pinned")
    for _, child in pairs(options.pzoptPanel:getChildren()) do
        if child.Type == "ISTextEntryBox" then child:setText(key) end
    end
    option:pzoptSet(value)
    option:invokeOnChangeEvent()
    options:apply(false)
    assert(getPerformance():getPzoptOption(key) == value,
        key .. " did not apply live through the options screen")
    log("OPTION id=" .. id .. " key=" .. key .. " value=" .. value)
end

Events.OnFETick.Add(function()
    if fixture then
        local command = nil
        if not fixture.armed then
            local reader = getFileReader(ARM, false)
            if reader then command = reader:readLine(); reader:close() end
        end
        if command and string.match(command, "^calibrate:%d+$") then
            if fixture.lastCalibration ~= command then
                fixture.lastCalibration = command
                log(string.format("CALIBRATION id=%s cursor=%d,%d", string.match(command, "%d+"), getMouseX(), getMouseY()))
            end
        elseif command and string.match(command, "^option:%d+:[%w]+:[%w%.]+$") then
            if fixture.lastOption ~= command then
                fixture.lastOption = command
                local id, key, value = string.match(command, "^option:(%d+):([%w]+):([%w%.]+)$")
                local ok, err = pcall(applyMouseOption, key, value, id)
                if not ok then log("FAIL mouse options: " .. tostring(err)); getCore():quit() end
            end
        elseif command and string.match(command, "^sensitivity:%d+:[%d%.]+$") then
            if fixture.lastOption ~= command then
                fixture.lastOption = command
                local id, value = string.match(command, "^sensitivity:(%d+):([%d%.]+)$")
                local ok, err = pcall(applyMouseOption, "mouseSensitivity", value, id)
                if not ok then log("FAIL sensitivity options: " .. tostring(err)); getCore():quit()
                else log("SENSITIVITY id=" .. id .. " value=" .. value) end
            end
        elseif command == "input-fixture" then
            if fixture.lastOption then
                MainScreen.instance.mainOptions:close()
                fixture.panel:setVisible(true)
                fixture.lastOption = nil
                log("SENSITIVITY options closed")
            end
        elseif not fixture.armed and command == "start" then
            fixture.armed = true
            log("STALL_BEGIN game-thread deliberately blocked 3200ms; input owner must collect OS events")
            local untilMs = getTimestampMs() + 3200
            while getTimestampMs() < untilMs do end
            log("STALL_END game-thread resumed")
        end
        if fixture.armed and fileExists(DONE) and not fixture.done then
            fixture.done = true
            assertCallbacks()
            log("DONE requesting normal Core.quit")
            getCore():quit()
        end
        return
    end
    local menu = MainScreen and MainScreen.instance
    if not menu or (menu.delay and menu.delay > 0) then return end
    local ok, err = pcall(createFixture)
    if not ok then
        log("FAIL fixture construction error: " .. tostring(err))
        getCore():quit()
    end
end)
