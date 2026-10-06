local T, F = PzoptText, LocalizationFixture
assert(T.text("Visuals") == CN_VISUALS)
assert(T.text("ultra", "upscalerQuality") == CN_ULTRA)
assert(T.text("Show 17 more settings (Advanced view)") == CN_MORE)
assert(T.text('5 settings match "light on", best first') == CN_MATCH)
assert(T.text("E:/Steam/pzopt/options.ini") == "E:/Steam/pzopt/options.ini")
assert(T.text('powershell -File "E:\\mods\\install.ps1"') == 'powershell -File "E:\\mods\\install.ps1"')
assert(T.text("pzopt.shadow ShadowPass.render") == "pzopt.shadow ShadowPass.render")
assert(T.search(CN_QUERY) == "lighting shadow")
assert(T.font(UIFont.CodeSmall) == UIFont.Small)

local full = T.text("Stutter while driving")
local lines = F.wrap(UIFont.Small, "Stutter while driving", 36)
assert(#lines > 1 and table.concat(lines, "") == full, "Chinese wrap must preserve characters")
for _, line in ipairs(lines) do assert(measure(line) <= 36, "Chinese line exceeds its width") end
assert(measure(F.clip(UIFont.Small, "Stutter while driving", 36)) <= 36)
assert(F.clip(UIFont.Small, "Stutter while driving", 10) == "")
assert(F.first(CN_SENTENCES) == CN_FIRST)

local entry = {key="upscalerQuality",label="Upscaler quality",tip="Test",choices={"quality","ultra"}}
local labels, values = F.combo(entry, "quality", "custom-value")
assert(labels[1] ~= "Default (quality)" and labels[3] == CN_ULTRA)
assert(values[1] == "quality" and values[2] == "ultra" and values[3] == "custom-value")
local stored
TEST_PERF = {
    getPzoptOptionPinnedBy=function()return "" end,
    getPzoptOptionDefault=function()return "quality" end,
    getPzoptOptionSaved=function()return "ultra" end,
    setPzoptOption=function(_,key,value)stored={key,value}end,
}
GameOption={new=function(_,name,control)return {name=name,control=control}end}
local screen = {gameOptions={add=function()end},addCombo=function(_,x,y,w,h,name,options)
    return {options=options,selected=1,setToolTipMap=function()end,
        addOption=function(self,value)self.options[#self.options+1]=value end}
end}
local option = F.option(screen,entry,0,0,200)
option:toUI()
assert(option:pzoptCurrent() == "ultra")
option:apply()
assert(stored[1] == "upscalerQuality" and stored[2] == "ultra", "translated combo must save the raw value")
option:pzoptReset(); option:apply()
assert(stored[2] == "", "reset retains the build-default marker")
option:pzoptSet("custom-value"); option:apply()
assert(stored[2] == "custom-value", "unknown saved values stay intact")

MISSING_KEY = PzoptTextCN.text["Enhancements"]
assert(T.text("Enhancements") == "Enhancements", "missing translation falls back to English")
MISSING_KEY = nil
LANG = "EN"
assert(T.text("Visuals") == "Visuals" and T.search(CN_LIGHT) == CN_LIGHT)
assert(T.font(UIFont.CodeSmall) == UIFont.CodeSmall)
assert(F.clip(UIFont.Small, "Stutter while driving", 500) == "Stutter while driving")
local enLabels, enValues = F.combo(entry,"quality","")
assert(enLabels[1] == "Default (quality)" and enLabels[3] == "ultra" and enValues[2] == "ultra")
