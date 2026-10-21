#!/usr/bin/env bash
# repro-ui (2026-10-16, Workshop Hazard 10-15: "I can't access the mod list because the 'Show Performance Overlay' is
# blocking it"; the screenshot shows our item drawn over MODS in a heavily modded Russian main menu: a menu mod laid the
# stock items out again after MainScreen:instantiate). Writes Zomboid/mods/pzopt-menu-fixture, enabled per run with
# `run.sh --mod pzopt-menu-fixture`: a stand-in for such a mod. Its client Lua (loaded after the game-dir Lua, so its
# wrapper is the outer one) calls the instantiate it found, then puts the stock items it knows back on its own grid
# (one row each, a gap between the groups), and adds one item of its own at the bottom, as menu mods do.
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/../../scripts/pz-env.sh"
MOD="$ZOMBOID/mods/pzopt-menu-fixture"
rm -rf "$MOD"; mkdir -p "$MOD/42/media/lua/client"
cat > "$MOD/42/mod.info" <<'INFO'
name=PZ Optimization menu re-layout fixture
id=pzopt-menu-fixture
description=Test fixture of harness/compat/make-menu-fixture.sh; not for players.
versionMin=42.0
INFO
cp "$MOD/42/mod.info" "$MOD/mod.info"
cat > "$MOD/42/media/lua/client/pzopt_menu_fixture.lua" <<'LUA'
require "OptionScreens/MainScreen"
local GROUPS = {
    { "latestSaveOption", "loadOption" },
    { "tutorialOption", "survivalOption", "onlineOption", "onlineCoopOption" },
    { "optionsOption", "modsOption", "workshopOption", "creditOption" },
    { "exitOption" },
}
local stockInstantiate = MainScreen.instantiate
function MainScreen:instantiate(...)
    stockInstantiate(self, ...)
    if self.inGame or not self.bottomPanel or not self.optionsOption then return end
    local hgt = self.optionsOption:getHeight()
    local y = 0
    for _, g in ipairs(GROUPS) do
        local any = false
        for _, f in ipairs(g) do
            local item = self[f]
            if item then item:setY(y); y = y + hgt; any = true end
        end
        if any then y = y + math.floor(hgt / 3) end
    end
    local extra = ISLabel:new(self.optionsOption:getX(), y, hgt, "FIXTURE MENU ITEM", 1, 1, 1, 1, UIFont.Large, true)
    extra.internal = "FIXTURE"
    extra:initialise()
    self.bottomPanel:addChild(extra)
    self.bottomPanel:setHeight(math.max(self.bottomPanel:getHeight(), y + hgt))
    print("[pzopt-menu-fixture] main menu laid out again (" .. y .. " px)")
end
LUA
echo "wrote $MOD"
