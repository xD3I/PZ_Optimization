-- Actual OS-driven combat fixture for harness/input-thread-win.ps1.
-- It only prepares a disposable solo world; the driver supplies every mouse transition.
local enabled = false
local flags = getFileReader("pzopt-harness.txt", false)
if flags then
    while true do
        local line = flags:readLine()
        if line == nil then break end
        if string.match(line, "^%s*input_thread_combat%s*=%s*1%s*$") then
            enabled = true
        end
    end
    flags:close()
end
if not enabled then return end

local PREFIX = "[pzopt-combat] "
local STALL_MS = 3200
local AIM_HOLD_MS = 300
local PRESS_MS = 35
local FRONTEND_TIMEOUT_MS = 90000
local SETUP_TIMEOUT_MS = 15000
local STAGE_TIMEOUT_MS = 10000
local SETTLE_TICKS = 20
local MOUSE_KEY_OFFSET = 10000

local run = {
    phase = "frontend",
    frontendDeadline = getTimestampMs() + FRONTEND_TIMEOUT_MS,
    frontendNextAt = 0,
    finished = false,
    stage = nil,
}

local frontendTick
local worldTick
local onWeaponHitXp
local onWeaponSwingHitPoint

local function log(message)
    print(PREFIX .. message)
end

local function now()
    return getTimestampMs()
end

local function visible(ui)
    return ui and ui.isVisible and ui:isVisible()
end

local function near(a, b, tolerance)
    return math.abs(a - b) <= tolerance
end

local function health(character)
    return character:getHealth()
end

local function screenPoint(player, target)
    local zoom = getCore():getZoom(0)
    if zoom <= 0 then zoom = 1 end
    -- B42's native getAimVector projects the reticle onto the player's aim-origin plane, not the floor.
    local aimZ = player:getAimOriginPosZ()
    local sx = IsoUtils.XToScreen(target:getX(), target:getY(), aimZ, 0) - getCameraOffX()
    local sy = IsoUtils.YToScreen(target:getX(), target:getY(), aimZ, 0) - getCameraOffY()
    return math.floor(sx / zoom + 0.5), math.floor(sy / zoom + 0.5)
end

local function safeScreenPoint(x, y)
    local core = getCore()
    return x >= 24 and x <= core:getScreenWidth() - 24
        and y >= 24 and y <= core:getScreenHeight() - 24
end

local function stop()
    if run.finished then return end
    run.finished = true
    pcall(function() Events.OnFETick.Remove(frontendTick) end)
    pcall(function() Events.OnTickEvenPaused.Remove(worldTick) end)
    pcall(function() Events.OnWeaponHitXp.Remove(onWeaponHitXp) end)
    pcall(function() Events.OnWeaponSwingHitPoint.Remove(onWeaponSwingHitPoint) end)
    getCore():quitToDesktop()
end

local function fail(reason)
    if run.finished then return end
    local stage = run.stage and run.stage.name or "none"
    log("FAIL phase=" .. run.phase .. " stage=" .. stage .. " reason=" .. tostring(reason))
    stop()
end

local function gridSquare(x, y, z)
    return getCell():getGridSquare(x, y, z)
end

local function walkable(square)
    return square and square:getFloor() and not square:isSolid() and not square:isSolidTrans()
end

local function targetSquareOpen(square)
    return walkable(square) and square:isFree(false) and square:getMovingObjects():size() == 0
end

local function clearLine(x0, y0, x1, y1, z)
    local steps = math.max(math.abs(x1 - x0), math.abs(y1 - y0))
    local previous = gridSquare(x0, y0, z)
    if not walkable(previous) then return false end

    for step = 1, steps do
        local x = math.floor(x0 + (x1 - x0) * step / steps + 0.5)
        local y = math.floor(y0 + (y1 - y0) * step / steps + 0.5)
        local square = gridSquare(x, y, z)
        if not walkable(square) or previous:isBlockedTo(square) then return false end
        if step < steps and square:getMovingObjects():size() > 0 then return false end
        previous = square
    end
    return true
end

local function findGeometry(player)
    local current = player:getCurrentSquare()
    if not current then return nil end

    local baseX, baseY, z = current:getX(), current:getY(), current:getZ()
    local directions = {
        { x = 1, y = 0 },
        { x = 0, y = 1 },
        { x = -1, y = 0 },
        { x = 0, y = -1 },
    }

    for radius = 0, 5 do
        for dx = -radius, radius do
            for dy = -radius, radius do
                if math.max(math.abs(dx), math.abs(dy)) == radius then
                    local ax, ay = baseX + dx, baseY + dy
                    local anchor = gridSquare(ax, ay, z)
                    if targetSquareOpen(anchor) then
                        for _, direction in ipairs(directions) do
                            local bx, by = ax + direction.x * 3, ay + direction.y * 3
                            local cx, cy = ax - direction.x * 3, ay - direction.y * 3
                            local mx, my = ax + direction.x, ay + direction.y
                            local nx, ny = ax - direction.x, ay - direction.y
                            if targetSquareOpen(gridSquare(bx, by, z))
                                and targetSquareOpen(gridSquare(cx, cy, z))
                                and targetSquareOpen(gridSquare(mx, my, z))
                                and targetSquareOpen(gridSquare(nx, ny, z))
                                and clearLine(ax, ay, bx, by, z)
                                and clearLine(ax, ay, cx, cy, z)
                                and clearLine(ax, ay, mx, my, z)
                                and clearLine(ax, ay, nx, ny, z) then
                                return {
                                    anchor = { x = ax, y = ay, z = z },
                                    ranged = {
                                        b = { x = bx, y = by, z = z },
                                        c = { x = cx, y = cy, z = z },
                                    },
                                    melee = {
                                        b = { x = mx, y = my, z = z },
                                        c = { x = nx, y = ny, z = z },
                                    },
                                }
                            end
                        end
                    end
                end
            end
        end
    end
    return nil
end

local function spawnTarget(position)
    local spawned = addZombiesInOutfit(position.x, position.y, position.z, 1, nil, 0)
    if not spawned or spawned:size() < 1 then
        error("native addZombiesInOutfit returned no target")
    end

    local target = spawned:get(0)
    target:setForceX(position.x + 0.5)
    target:setForceY(position.y + 0.5)
    target:setUseless(true)
    target:setCanWalk(false)
    target:setNoTeeth(true)
    target:setHealth(100.0)
    return target
end

local function verifyMouseBindings()
    local core = getCore()
    local function mouseBinding(name)
        local key = core:getKey(name)
        return key >= MOUSE_KEY_OFFSET and key or core:getAltKey(name)
    end
    local attack = mouseBinding("Attack/Click")
    local aim = mouseBinding("Aim")
    local melee = core:getKey("Melee")
    if attack < MOUSE_KEY_OFFSET or aim < MOUSE_KEY_OFFSET or attack == aim then
        error("Attack/Click and Aim must be distinct mouse bindings; attack=" .. attack .. " aim=" .. aim)
    end
    if melee == attack then
        error("Melee binding conflicts with Attack/Click; the fixture requires the Attack/Click path")
    end
    run.attackButton = attack - MOUSE_KEY_OFFSET
    run.aimButton = aim - MOUSE_KEY_OFFSET
    run.meleeBinding = melee
end

local function prepareWorld()
    local player = run.player
    local geometry = findGeometry(player)
    if not geometry then
        error("no clear four-target lane near the native spawn square")
    end

    -- teleportTo(float, float, int) floors X/Y in B42; restore the intended center and its next/last positions.
    player:teleportTo(geometry.anchor.x, geometry.anchor.y, geometry.anchor.z)
    player:setForceX(geometry.anchor.x + 0.5)
    player:setForceY(geometry.anchor.y + 0.5)
    player:setPerkLevelDebug(Perks.Aiming, 10)

    local inventory = player:getInventory()
    local gun = inventory:AddItem("Base.Pistol")
    local melee = inventory:AddItem("Base.HuntingKnife")
    if not gun or not melee then
        error("native weapon creation failed")
    end

    gun:setContainsClip(true)
    gun:setRoundChambered(true)
    gun:setCurrentAmmoCount(2)
    gun:setHitChance(100)
    gun:setAimingTime(0)
    gun:setMinDamage(20)
    gun:setMaxDamage(20)

    melee:setMaxHitCount(1)
    melee:setMaxRange(1.8)
    melee:setMinRange(0)
    melee:setMinDamage(20)
    melee:setMaxDamage(20)

    verifyMouseBindings()
    run.geometry = geometry
    run.gun = gun
    run.melee = melee
    run.setupReadyAt = now() + 5000 -- let the native new-world fade and camera settle
    run.setupDeadline = now() + SETUP_TIMEOUT_MS
    run.phase = "ranged_prepare"
    log(string.format("PHASE world_ready anchor=%d,%d,%d attack_button=%d aim_button=%d melee_binding=%d", geometry.anchor.x,
        geometry.anchor.y, geometry.anchor.z, run.attackButton, run.aimButton, run.meleeBinding))
end

local function armStage(name)
    local player = run.player
    local positions = run.geometry[name]
    if getGameSpeed() ~= 1 then error("native simulation must be running at normal speed before arming") end
    local weapon = name == "ranged" and run.gun or run.melee
    local targetB = spawnTarget(positions.b)
    local targetC = spawnTarget(positions.c)

    player:setPrimaryHandItem(weapon)
    player:setSecondaryHandItem(nil)

    local bX, bY = screenPoint(player, targetB)
    local cX, cY = screenPoint(player, targetC)
    if not safeScreenPoint(bX, bY) or not safeScreenPoint(cX, cY) then
        error("projected target point outside the game viewport")
    end

    local ammoBefore = run.gun:getCurrentAmmoCount()
    local expectedAmmoDelta = name == "ranged" and run.gun:getAmmoPerShoot() or 0
    run.stage = {
        name = name,
        weapon = weapon,
        b = targetB,
        c = targetC,
        bBefore = health(targetB),
        cBefore = health(targetC),
        ammoBefore = ammoBefore,
        expectedAmmoDelta = expectedAmmoDelta,
        bX = bX,
        bY = bY,
        cX = cX,
        cY = cY,
        starts = 0,
        lastStarted = false,
        swings = 0,
        hitsB = 0,
        hitsC = 0,
        idleTicks = 0,
        armDeadline = now() + 5000,
        aimReadyAt = nil,
        phase = "arming",
    }
    run.phase = name .. "_arming"

    log(string.format(
        "READY stage=%s screen=%dx%d b=%d,%d c=%d,%d attack_button=%d aim_button=%d aim_hold_ms=%d press_ms=%d sequence=aim-b,settle,stall,press-b,press-c,aim-up",
        name, getCore():getScreenWidth(), getCore():getScreenHeight(), bX, bY, cX, cY, run.attackButton, run.aimButton, AIM_HOLD_MS, PRESS_MS
    ))
end

local function stageSummary(stage, result, reason)
    local player = run.player
    local bAfter = health(stage.b)
    local cAfter = health(stage.c)
    local ammoAfter = run.gun:getCurrentAmmoCount()
    local cNowX, cNowY = screenPoint(player, stage.c)
    local finalX, finalY = getMouseX(), getMouseY()
    local cursorAtC = near(finalX, stage.cX, 2) and near(finalY, stage.cY, 2)
    log(string.format(
        "OUTCOME stage=%s result=%s starts=%d swings=%d hit_b=%d hit_c=%d b_health=%.3f->%.3f c_health=%.3f->%.3f ammo=%d->%d expected_delta=%d idle_ticks=%d final=%d,%d c_now=%d,%d cursor_c=%s%s",
        stage.name, result, stage.starts, stage.swings, stage.hitsB, stage.hitsC,
        stage.bBefore, bAfter, stage.cBefore, cAfter, stage.ammoBefore, ammoAfter, stage.expectedAmmoDelta,
        stage.idleTicks, finalX, finalY, cNowX, cNowY, tostring(cursorAtC), reason and " reason=" .. reason or ""
    ))
    return bAfter, cAfter, ammoAfter, cursorAtC
end

local function assessStage(stage)
    local bAfter, cAfter, ammoAfter, cursorAtC = stageSummary(stage, "CHECK")
    local expectedAmmo = stage.ammoBefore - stage.expectedAmmoDelta
    local passed = stage.starts == 1
        and stage.swings == 1
        and stage.hitsB >= 1
        and stage.hitsC == 0
        and bAfter < stage.bBefore
        and near(cAfter, stage.cBefore, 0.001)
        and ammoAfter == expectedAmmo
        and cursorAtC

    if not passed then
        stageSummary(stage, "FAIL", "native evidence did not prove exactly one accepted B attack with final cursor C")
        fail("stage assertions failed")
        return
    end

    stageSummary(stage, "PASS")
    if stage.name == "ranged" then
        run.phase = "between_stages"
        run.nextStageAt = now() + 1000
        run.nextStageDeadline = now() + 7000
    else
        log("DONE requesting normal Core.quitToDesktop")
        stop()
    end
end

onWeaponHitXp = function(owner, weapon, target, damage)
    local stage = run.stage
    if not stage or owner ~= run.player or weapon ~= stage.weapon then return end
    if target == stage.b then
        stage.hitsB = stage.hitsB + 1
    elseif target == stage.c then
        stage.hitsC = stage.hitsC + 1
    end
end

onWeaponSwingHitPoint = function(owner, weapon)
    local stage = run.stage
    if stage and owner == run.player and weapon == stage.weapon then
        stage.swings = stage.swings + 1
    end
end

local function tickStage()
    local stage = run.stage
    local player = run.player
    local time = now()

    if stage.phase == "arming" then
        if time > stage.armDeadline then
            fail("stage did not become idle before the input window")
            return
        end
        if not player:isAiming() or player:isAttackStarted() or player:isAttacking() then return end
        if not stage.aimReadyAt then stage.aimReadyAt = time + 1000 end
        if time < stage.aimReadyAt then return end
        stage.bX, stage.bY = screenPoint(player, stage.b)
        stage.cX, stage.cY = screenPoint(player, stage.c)

        stage.phase = "awaiting_attack"
        stage.deadline = time + STAGE_TIMEOUT_MS
        log("PHASE stage=" .. stage.name .. " input_armed")
        log(string.format("STALL_BEGIN stage=%s ms=%d b=%d,%d c=%d,%d", stage.name, STALL_MS, stage.bX, stage.bY, stage.cX, stage.cY))
        local untilMs = now() + STALL_MS
        while now() < untilMs do end
        log("STALL_END stage=" .. stage.name)
        return
    end

    if stage.phase == "awaiting_attack" then
        local started = player:isAttackStarted()
        if started and not stage.lastStarted then
            stage.starts = stage.starts + 1
        end
        stage.lastStarted = started
        if stage.starts > 1 or stage.swings > 1 then
            stageSummary(stage, "FAIL", "duplicate native attack evidence")
            fail("duplicate attack observed")
            return
        end
        if stage.starts == 1 and not started and not player:isAttacking() then
            stage.idleTicks = stage.idleTicks + 1
            if stage.idleTicks >= SETTLE_TICKS then
                assessStage(stage)
                return
            end
        else
            stage.idleTicks = 0
        end
        if time > stage.deadline then
            stageSummary(stage, "FAIL", "timed out waiting for native attack/cooldown")
            fail("stage timeout")
        end
    end
end

local function worldTickImpl()
    if run.finished then return end
    local player = getPlayer()
    if not player then return end

    if run.phase == "frontend" then
        run.player = player
        run.phase = "setup"
        run.setupDeadline = now() + SETUP_TIMEOUT_MS
        log("PHASE world_entered")
    end

    if run.phase == "setup" then
        if now() > run.setupDeadline then
            fail("world player never received a current square")
            return
        end
        if not player:getCurrentSquare() then return end
        prepareWorld()
        return
    end

    if run.phase == "ranged_prepare" then
        if now() > run.setupDeadline then
            fail("world setup did not settle")
            return
        end
        if now() >= run.setupReadyAt and not player:isAttackStarted() and not player:isAttacking() then
            armStage("ranged")
        end
        return
    end

    if run.phase == "between_stages" then
        if now() > run.nextStageDeadline then
            fail("ranged cooldown did not settle before melee")
            return
        end
        if now() >= run.nextStageAt and not player:isAttackStarted() and not player:isAttacking() then
            armStage("melee")
        end
        return
    end

    if run.stage then
        tickStage()
    end
end

worldTick = function()
    if run.finished then return end
    local ok, err = pcall(worldTickImpl)
    if not ok then fail("world fixture error: " .. tostring(err)) end
end

local function frontendTickImpl()
    if run.finished or getPlayer() then return end
    local time = now()
    if time > run.frontendDeadline then
        fail("native frontend did not reach a solo world")
        return
    end
    if time < run.frontendNextAt then return end

    local menu = MainScreen and MainScreen.instance
    if not menu or (menu.delay and menu.delay > 0) then return end

    if not run.frontendStarted then
        if not menu.survivalOption then return end
        getCore():setOptionShowSurvivalGuide(false)
        MainScreen.onMenuItemMouseDownMainMenu(menu.survivalOption, 0, 0)
        run.frontendStarted = true
        run.frontendNextAt = time + 500
        log("PHASE frontend=solo")
        return
    end

    if visible(menu.soloScreen) then
        menu.soloScreen:selectNewPanel(1)
        menu.soloScreen:clickPlay()
        run.frontendNextAt = time + 500
        log("PHASE frontend=apocalypse")
        return
    end

    if visible(menu.worldSelect) then
        local worldSelect = menu.worldSelect
        if not worldSelect.listbox.items[1] then return end
        worldSelect.listbox.selected = 1
        worldSelect:onSelectWorld()
        worldSelect:clickNext()
        run.frontendNextAt = time + 500
        log("PHASE frontend=world_select")
        return
    end

    if visible(MapSpawnSelect.instance) then
        local spawn = MapSpawnSelect.instance
        if not spawn.listbox.items[1] then return end
        spawn.listbox.selected = 1
        if spawn.textEntry then
            spawn.textEntry:setText("pzopt-input-combat-" .. tostring(time))
        end
        spawn:clickNext()
        run.frontendNextAt = time + 500
        log("PHASE frontend=spawn_select")
        return
    end

    if visible(menu.charCreationProfession) then
        menu.charCreationProfession:onOptionMouseDown({ internal = "NEXT" }, 0, 0)
        run.frontendNextAt = time + 500
        log("PHASE frontend=profession")
        return
    end

    if visible(menu.charCreationMain) then
        menu.charCreationMain:onOptionMouseDown({ internal = "NEXT" }, 0, 0)
        run.frontendNextAt = time + 500
        log("PHASE frontend=character")
    end
end

frontendTick = function()
    if run.finished then return end
    local ok, err = pcall(frontendTickImpl)
    if not ok then fail("frontend fixture error: " .. tostring(err)) end
end

Events.OnWeaponHitXp.Add(onWeaponHitXp)
Events.OnWeaponSwingHitPoint.Add(onWeaponSwingHitPoint)
Events.OnTickEvenPaused.Add(worldTick)
Events.OnFETick.Add(frontendTick)
log("LOADED input_thread_combat=1")
