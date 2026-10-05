-- Translate at display boundaries, leaving setting values, paths and search data intact.
if PzoptText then return end
PzoptText = {}
local T = PzoptText
local data, heads, upper, reverse
local caches, counts = { general = {}, quality = {} }, { general = 0, quality = 0 }

function T.chinese()
    return Translator and Translator.getLanguage and Translator.getLanguage():name() == "CN"
end

local function load()
    if data then return end
    require "pzopt/pzopt_text_cn"
    data, heads, upper = PzoptTextCN, {}, {}
    for source, key in pairs(data.text) do
        if #source < 80 and source:match("^[A-Z][A-Za-z /%-]+$") then upper[string.upper(source)] = key end
        if source ~= "" then
            local head = source:sub(1, 1)
            heads[head] = heads[head] or {}
            heads[head][#heads[head] + 1] = source
        end
    end
    for _, list in pairs(heads) do table.sort(list, function(a, b) return #a > #b end) end
end

local function identifier(c)
    return c ~= "" and (c:match("[%w_.$]") ~= nil or c == "/" or c == "\\" or c == ":" or c == "-")
end

local function replacement(source, quality)
    local key = quality and source == "ultra" and "UI_PZOpt_UltraPerformance" or data.text[source] or upper[source]
    local value = key and getText(key)
    return value and value ~= key and value or source
end

local function dynamicText(value)
    for _, row in ipairs(data.dynamic) do
        local a, b = value:match(row.pattern)
        if a then
            local format = getText(row.key)
            if format ~= row.key then
                if b then return string.format(format, a, row.rawSecond and b or T.text(b)) end
                return string.format(format, a)
            end
        end
    end
end

function T.text(value, settingKey)
    if not T.chinese() or type(value) ~= "string" or value == "" then return value end
    if value:match("^%a:[/\\]") or value:match("^/") or value:match("^powershell[%s%.]")
            or value:match("^pwsh%s") or value:match("^bash%s") or value:match("^sh%s") then return value end
    load()
    local quality = settingKey == "upscalerQuality"
    local bank = quality and "quality" or "general"
    local cache = caches[bank]
    if cache[value] ~= nil then return cache[value] end
    local translated
    if not data.text[value] and not upper[value] then translated = dynamicText(value) end
    if not translated then
        if data.text[value] or upper[value] then translated = replacement(value, quality)
        elseif not value:find("[A-Za-z]") then return value
        else
            local out, pos = {}, 1
            while pos <= #value do
                local found
                for _, source in ipairs(heads[value:sub(pos, pos)] or {}) do
                    if value:sub(pos, pos + #source - 1) == source then
                        local left, right = value:sub(pos - 1, pos - 1), value:sub(pos + #source, pos + #source)
                        if not (identifier(source:sub(1, 1)) and identifier(left))
                                and not (identifier(source:sub(-1)) and identifier(right)) then found = source; break end
                    end
                end
                if found then out[#out + 1] = replacement(found, quality); pos = pos + #found
                else out[#out + 1] = value:sub(pos, pos); pos = pos + 1 end
            end
            translated = table.concat(out)
        end
    end
    if counts[bank] >= 512 then cache = {}; caches[bank], counts[bank] = cache, 0 end
    cache[value], counts[bank] = translated, counts[bank] + 1
    return translated
end

function T.font(value)
    if not T.chinese() then return value end
    if value == UIFont.CodeSmall then return UIFont.Small end
    if value == UIFont.CodeMedium then return UIFont.Medium end
    if value == UIFont.CodeLarge then return UIFont.Large end
    return value
end

-- Reuse the English BM25 index without changing the text the player typed.
function T.search(value)
    if not T.chinese() or type(value) ~= "string" or value == "" then return value end
    load()
    if not reverse then
        reverse = {}
        for source, key in pairs(data.text) do
            local translated = getText(key)
            if translated ~= "" and translated ~= key and not reverse[translated] then reverse[translated] = source end
        end
        for en, key in pairs(data.search) do reverse[getText(key)] = en end
    end
    if reverse[value] then return reverse[value] end
    return (value:gsub("%S+", function(word) return reverse[word] or word end))
end

function T.firstSentence(value)
    local stop = #value
    for _, punctuation in ipairs({ getText("UI_PZOpt_FullStop"), getText("UI_PZOpt_Exclamation"), getText("UI_PZOpt_Question"), ". " }) do
        local pos = value:find(punctuation, 1, true)
        if pos then stop = math.min(stop, pos + (punctuation == ". " and 0 or #punctuation - 1)) end
    end
    return value:sub(1, stop)
end

function T.clip(font, value, width)
    local tm = getTextManager()
    font = T.font(font)
    if tm:MeasureStringX(font, value) <= width then return value end
    if tm:MeasureStringX(font, "...") > width then return "" end
    while #value > 0 and tm:MeasureStringX(font, value .. "...") > width do value = value:sub(1, #value - 1) end
    return value .. "..."
end

-- Kahlua strings are Java strings: Chinese characters are individual string.sub units.
-- Keep ASCII words together; long runs of Chinese can break between characters.
function T.wrapLines(font, value, width)
    value = T.text(value or "")
    local tm, out = getTextManager(), {}
    font = T.font(font)
    for paragraph in (value .. "\n"):gmatch("(.-)\n") do
        local line = ""
        for word in paragraph:gmatch("%S+") do
            local candidate = line == "" and word or line .. " " .. word
            if tm:MeasureStringX(font, candidate) <= width then line = candidate
            else
                if line ~= "" then out[#out + 1] = line; line = "" end
                if tm:MeasureStringX(font, word) <= width then line = word
                else
                    for i = 1, #word do
                        local c = word:sub(i, i)
                        if line ~= "" and tm:MeasureStringX(font, line .. c) > width then
                            out[#out + 1] = line; line = ""
                        end
                        line = line .. c
                    end
                end
            end
        end
        if line ~= "" or paragraph == "" then out[#out + 1] = line end
    end
    return out
end
