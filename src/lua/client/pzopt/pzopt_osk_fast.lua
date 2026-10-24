-- pzopt: oskFast (2026-10-24, worktree osk-lag: the controller on-screen keyboard latency profile). Two fixes to the
-- game's on-screen keyboard (ISUI/ISOnScreenKeyboard.lua), both installed only while the game's own function is still in
-- place (a mod that replaced it keeps its version) and only when the key oskFast is on (default on):
--
-- 1. Instant key focus. OnScreenKeyboardPanel.renderButton lights a key through its UITransition fade: in over 100 ms,
--    out over 200 ms. After a D-pad move the new key is drawn at 16 % and the old one at 92 %, the cursor reads as moved
--    only on the third frame (the new key's rising highlight passes the old one's falling one) and the old key is dark
--    after seven: at 60 fps the cursor arrives ~50 ms after the frame that moved it. Here a key whose pad focus changed
--    takes its new highlight at once (fully lit / unlit in the next frame); the mouse hover keeps the stock fade.
-- 2. The cursor on the default key when the keyboard opens. OnScreenKeyboardPanel:onGainJoypadFocus focuses "g", but in
--    the frame the keyboard is shown its keys are not "really visible" yet (setJoypadFocus skips invisible elements), so
--    no key is lit and the panel stays at row 1, column 1: the first A types "`" under an invisible cursor. Here the
--    panel's prerender puts the focus on the default key the first frame its keys are visible, unless the pad moved since.
-- 3. Keys typed in the frame they were pressed. A key calls queueCharEvent / queueKeyEvent, which hand the event to the
--    render thread (RenderThread.queueInvokeOnRenderContext); it lands in the keyboard state the game thread swaps in at
--    a later frame and Core.updateKeyboard gives it to the text box after that frame's render: the character showed 3
--    frames after the frame whose pad handler typed it (50 ms at 60 fps), against 1 frame for a D-pad move. Here, while
--    the keyboard is up and its entry takes the text, a character, Backspace and the arrow keys go straight to the
--    entry the way Core.updateKeyboardAux would hand them over (putCharacter / onKeyBack / onKeyLeft ...), so the next
--    frame draws them; everything else (Enter, Shift, an entry that ignores its first key or is full, a non-ASCII
--    character) still takes the stock queue.
--
-- The keyboard assigns renderButton to each key when it is first shown, after every Lua file loaded, so replacing the
-- class functions here is enough.

local VANILLA_RENDER_BUTTON = OnScreenKeyboardPanel and OnScreenKeyboardPanel.renderButton
local VANILLA_GAIN = OnScreenKeyboardPanel and OnScreenKeyboardPanel.onGainJoypadFocus
local VANILLA_PRERENDER = OnScreenKeyboardPanel and OnScreenKeyboardPanel.prerender -- nil: ISPanelJoypad's

local function enabled()
    local ok, v = pcall(function() return getPerformance():getPzoptOption("oskFast") end)
    local ok2, on = pcall(function() return getPerformance():getPzoptOption("enabled") end)
    return ok and v == "true" and not (ok2 and on == "false")
end

local function gameOwn(fn)
    local ok, origin = pcall(function() return getPerformance():getPzoptLuaOrigin(fn) end)
    return fn ~= nil and (not ok or origin == "game")
end

local function renderButton(self)
    local jf = self.joypadFocused and true or false
    if jf ~= (self.pzoptJoyFocused or false) then
        self.pzoptJoyFocused = jf
        local f = self.fade
        if f and not (self.mouseOver and self:isMouseOver()) then
            f:setFadeIn(jf)                 -- stock's own transition (in: 100 ms, out: 200 ms) ...
            f:setElapsed(jf and 100 or 200) -- ... already at its end: this frame's update() draws the final highlight
        end
    end
    return VANILLA_RENDER_BUTTON(self)
end

local function onGainJoypadFocus(self, joypadData)
    local wanted = self.defaultButton and self.parent and self.parent.bSelectDefaultKey
    VANILLA_GAIN(self, joypadData)
    if wanted and not self.defaultButton.joypadFocused then
        self.pzoptDefaultPending = true -- the keys are not visible yet: focus the default key in the first frame they are
    end
end

local function prerender(self)
    if self.pzoptDefaultPending then
        local jd = self.joyfocus
        if not jd then
            self.pzoptDefaultPending = nil
        elseif self.defaultButton:isReallyVisible() then
            self.pzoptDefaultPending = nil
            local cur = self:getJoypadFocus()
            if not (cur and cur.joypadFocused) then -- no key lit yet (a D-pad move since would have lit one)
                self:setJoypadFocus(self.defaultButton, jd)
            end
        end
    end
    if VANILLA_PRERENDER then return VANILLA_PRERENDER(self) end
    return ISPanelJoypad.prerender(self)
end

local VANILLA_QUEUE_CHAR = queueCharEvent
local VANILLA_QUEUE_KEY = queueKeyEvent
local DIRECT_KEYS = { [14] = "onKeyBack", [203] = "onKeyLeft", [205] = "onKeyRight", [200] = "onKeyUp", [208] = "onKeyDown" }

-- the keyboard's own entry, when it is the text box Core.updateKeyboard would hand this event to now
local function oskEntry()
    local osk = OnScreenKeyboard and OnScreenKeyboard.instance
    if not osk or not osk.entry or not osk:isReallyVisible() then return nil end
    local e = osk.entry.javaObject
    if e and e:isDoingTextEntry() and not e:isIgnoreFirst() then return e end
    return nil
end

local logged = {}
local function logOnce(what)
    if not logged[what] then
        logged[what] = true
        print("[pzopt] on-screen keyboard: " .. what)
    end
end

local function queueChar(ch)
    local e = type(ch) == "string" and #ch == 1 and string.byte(ch) < 128 and oskEntry()
    if e and not e:isTextLimit() and not e:isOnlyNumbers() and not e:isOnlyText() then
        local ok, err = pcall(function() e:putCharacter(string.byte(ch)) end)
        if ok then logOnce("characters typed at once") return end -- (a failed call changed nothing: the stock queue)
        logOnce("direct character failed: " .. tostring(err))
    else
        logOnce("character through the stock queue (entry " .. tostring(e) .. ")")
    end
    return VANILLA_QUEUE_CHAR(ch)
end

local function queueKey(key)
    local m = DIRECT_KEYS[key]
    local e = m and oskEntry()
    if e and pcall(function() e[m](e) end) then
        return
    end
    return VANILLA_QUEUE_KEY(key)
end

if OnScreenKeyboardPanel and enabled() then
    if VANILLA_QUEUE_CHAR and VANILLA_QUEUE_KEY then
        queueCharEvent = queueChar
        queueKeyEvent = queueKey
    end
    if VANILLA_RENDER_BUTTON and gameOwn(VANILLA_RENDER_BUTTON) then
        OnScreenKeyboardPanel.renderButton = renderButton
    end
    if VANILLA_GAIN and gameOwn(VANILLA_GAIN) and (VANILLA_PRERENDER == nil or gameOwn(VANILLA_PRERENDER)) then
        OnScreenKeyboardPanel.onGainJoypadFocus = onGainJoypadFocus
        OnScreenKeyboardPanel.prerender = prerender
    end
    print("[pzopt] on-screen keyboard: instant key focus, default key on open, keys typed at once (oskFast)")
end
