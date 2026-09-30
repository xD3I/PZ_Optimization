-- Adapter checks using the actual GameOption and ISColorPickerHSB callback/lifecycle code.
-- Run from the repository root with Lua 5.1 and PZ_DIR pointing to the game directory.
local checks = 0
local function check(value, message)
    checks = checks + 1
    assert(value, message)
end

require = function() end
UIFont = { Small = 1 }
getText = function(value)
    return value
end
getTexture = function() end
getTextManager = function()
    return {
        getFontHeight = function()
            return 14
        end,
        MeasureStringX = function()
            return 70
        end,
    }
end

local Widget = {}
function Widget:derive()
    local class = setmetatable({}, { __index = self })
    class.__index = class
    return class
end
function Widget:new(x, y, w, h)
    return setmetatable({ x = x, y = y, width = w, height = h, backgroundColor = {}, visible = true }, self)
end
function Widget:initialise() end
function Widget:prerender() end
function Widget:setEnable(value)
    self.enable = value
end
function Widget:getIsVisible()
    return self.visible
end
function Widget:getAbsoluteX()
    return self.x
end
function Widget:getAbsoluteY()
    return self.y
end
function Widget:getWidth()
    return self.width
end
function Widget:getHeight()
    return self.height
end
function Widget:setX(value)
    self.x = value
end
function Widget:setY(value)
    self.y = value
end
function Widget:setCapture(value)
    self.capture = value
end
function Widget:setVisible(value)
    self.visible = value
end
function Widget:bringToTop() end
function Widget:addChild(child)
    child.parent = self
end
function Widget:removeChild(child)
    child.parent = nil
end
function Widget:removeFromUIManager() end
Widget.__index = Widget
ISBaseObject, ISPanelJoypad = Widget, Widget

ColorInfo = {}
function ColorInfo.new(r, g, b)
    return {
        getR = function()
            return r
        end,
        getG = function()
            return g
        end,
        getB = function()
            return b
        end,
        getRedFloat = function()
            return r
        end,
        getGreenFloat = function()
            return g
        end,
        getBlueFloat = function()
            return b
        end,
        toColor = function(self)
            return self
        end,
    }
end
local joypad
JoypadState = {
    getMainMenuJoypad = function()
        return joypad
    end,
}
Joypad = { AButton = 1, BButton = 2 }
local game = assert(os.getenv("PZ_DIR"))
dofile(game .. "/media/lua/client/ISUI/ISColorPickerHSB.lua")
dofile(game .. "/media/lua/client/OptionScreens/GameOption.lua")

local file = assert(io.open("src/lua/client/pzopt/pzopt_optimizations_options.lua"))
local source = file:read("*a")
file:close()
local first = assert(source:find("-- Colour controls store RGB hex;", 1, true))
local last = assert(source:find("local function addIntOption", first, true))
local factory = assert(
    loadstring(
        "local perf, tooltipFor, afterStore = ...\n" .. source:sub(first, last - 1) .. "\nreturn addColourOption"
    )
)
local p = { saved = "", current = "FFC740", pin = "", writes = 0 }
function p:getPzoptOptionPinnedBy()
    return self.pin
end
function p:getPzoptOptionDefault()
    return "FFC740"
end
function p:getPzoptOptionSaved()
    return self.saved
end
function p:getPzoptOption()
    return self.current
end
function p:setPzoptOption(_, value)
    self.saved, self.writes = value, self.writes + 1
end
local afterFirst = assert(source:find("local function afterStore(", 1, true))
local afterLast = assert(source:find("-- A tab master switch", afterFirst, true))
local afterStore = assert(
    loadstring("local perf = ...\n" .. source:sub(afterFirst, afterLast - 1) .. "\nreturn afterStore")
)(function()
    return p
end)
local add = factory(function()
    return p
end, function()
    return "tooltip"
end, afterStore)

local screen = Widget:new(0, 0, 800, 600)
local panel = Widget:new(0, 0, 800, 600)
screen.gameOptions = { changes = 0 }
function screen.gameOptions:add(option)
    option.gameOptions = self
end
function screen.gameOptions:onChange()
    self.changes = self.changes + 1
end
function screen:addColorButton(_, _, _, colour, callback)
    local button = Widget:new(750, 550, 40, 20)
    button.Type, button.parent = "ISButton", panel
    button.backgroundColor, button.click = colour, callback
    return button
end
local entry = { key = "occludedOutlineColour", label = "Colour", live = true }
local option = add(screen, entry, 0, 0)
option:toUI()
check(option:pzoptCurrent() == "#FFC740 (default)", "default preview")
check(option.control.backgroundColor.g == 199 / 255, "default swatch")
option:apply()
check(p.saved == "", "default remains unset")

option.control.click(screen, option.control)
local picker = assert(screen.pzoptColourPicker)
check(picker.x >= 0 and picker.x + picker.width <= screen.width, "horizontal popup bounds")
check(picker.y >= 0 and picker.y + picker.height <= screen.height, "vertical popup bounds")
check(picker.initialColor:getG() == 199 / 255, "picker receives swatch colour")
picker.pickedRGB = { r = 0.1, g = 0.5, b = 0.9 }
picker:onSave()
check(screen.pzoptColourPicker == nil and picker.parent == nil, "accept closes popup")
check(option:pzoptCurrent() == "#1A80E6", "picker converts RGB to hex")
check(p.saved == "", "selection waits for Apply")
check(screen.gameOptions.changes == 1, "selection marks options changed")
option:apply()
check(p.saved == "1A80E6", "Apply stores RGB without calling restartRequired")
option:toUI()
check(option.control.backgroundColor.b == 230 / 255, "saved RGB round trip")

joypad = {}
option.control.click(screen, option.control)
picker = screen.pzoptColourPicker
picker.joyfocus = joypad
check(joypad.focus == picker, "controller focus enters picker")
picker.pickedRGB = { r = 1, g = 0, b = 1 }
picker:onJoypadDown(Joypad.BButton)
check(option:pzoptCurrent() == "#1A80E6", "cancel discards unaccepted picker colour")
check(joypad.focus == panel and screen.pzoptColourPicker == nil, "cancel returns focus")
option.control.click(screen, option.control)
picker = screen.pzoptColourPicker
picker.pickedRGB = { r = 0, g = 1, b = 0 }
picker:onJoypadDown(Joypad.AButton)
check(option:pzoptCurrent() == "#00FF00", "controller accept")

option.control.click(screen, option.control)
panel.visible = false
screen.pzoptColourPicker:prerender()
check(screen.pzoptColourPicker == nil, "changing tabs closes picker")
panel.visible = true
option:pzoptReset()
check(option:pzoptCurrent() == "#FFC740 (default)", "reset restores default")
option:pzoptSet("#ab12ef")
check(option:pzoptCurrent() == "#AB12EF", "explicit RGB setting")
option:pzoptSet("invalid")
check(option:pzoptCurrent() == "#FFC740", "invalid RGB uses default")

p.pin, p.current = "pzopt.properties", "123456"
local pinned = add(screen, entry, 0, 0)
pinned:toUI()
check(not pinned.control.enable and pinned:pzoptCurrent() == "#123456", "pinned colour is disabled")
local writes = p.writes
pinned:pzoptReset()
pinned:pzoptSet("FFFFFF")
pinned:apply()
pinned.control.click(screen, pinned.control)
check(p.writes == writes and pinned:pzoptCurrent() == "#123456", "pinned setting cannot change")
check(screen.pzoptColourPicker == nil, "pinned picker cannot open")
print("Outline colour adapter: " .. checks .. " checks passed")
