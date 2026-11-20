-- Runs after the script's applyOrdinary() definition. Redis serializes this preflight and the writes below.
local nKeys = isClick and 8 or 4
local nArgs = isClick and 5 or 4
local claimKey = KEYS[nKeys + 1]
local claim = ARGV[nArgs + 1]
local matchKey = KEYS[nKeys + 2]
local finalKey = KEYS[nKeys + 3]
local maxExact = 9007199254740991

local function kind(key)
    return redis.call('TYPE', key).ok
end
local function compatible(key, expected)
    local current = kind(key)
    return current == 'none' or current == expected
end
local function whole(value)
    local number = tonumber(value)
    if not number or number < 0 or number > maxExact or number ~= math.floor(number) then return nil end
    return number
end
local function decimal(text)
    return text and (text == '0' or text:match('^[1-9]%d*$')) and whole(text)
end
local function canIncrement(key, member, amount)
    if not amount or not compatible(key, 'zset') then return false end
    local old = whole(redis.call('ZSCORE', key, member) or '0')
    return old and amount <= maxExact - old
end
local function wonClaim()
    return kind(claimKey) == 'string' and redis.call('GET', claimKey) == claim
end
local function rejected(code)
    redis.call('DEL', claimKey) -- still our claim; no score mutation has been executed
    return {code}
end

if not wonClaim() then return {'DUPLICATE'} end
local points = whole(ARGV[isClick and 4 or 2])
if not points then return rejected('INVALID_SCORES') end

local field
if matchKey then
    local time = redis.call('TIME')
    local now = tonumber(time[1]) * 1000000 + tonumber(time[2])
    local start = tonumber(ARGV[nArgs + 2])
    local finish = tonumber(ARGV[nArgs + 3])
    if now < start then return rejected('NOT_READY') end
    if now < finish then
        -- If the clock steps backwards after finalization, never reopen the frozen result.
        if ARGV[nArgs + 4] ~= '1' or kind(matchKey) ~= 'hash' or kind(finalKey) ~= 'none' then
            return rejected('NOT_READY')
        end
        local window = redis.call('HMGET', matchKey, 'startedAt', 'endedAt')
        if window[1] ~= ARGV[nArgs + 2] or window[2] ~= ARGV[nArgs + 3] then return rejected('NOT_READY') end
        local schools = {'aUniversity1', 'aUniversity2', 'bUniversity1', 'bUniversity2'}
        local seen = {}
        local a, b = 0, 0
        for i, school in ipairs(schools) do
            local id = redis.call('HGET', matchKey, school .. 'Id')
            local column = school .. 'Contribution'
            local text = redis.call('HGET', matchKey, column)
            local earned = decimal(text)
            if id ~= ARGV[nArgs + 4 + i] or seen[id] or not earned then
                return rejected('NOT_READY')
            end
            seen[id] = true
            if id == ARGV[1] then
                field = column
                if points > maxExact - earned then return rejected('INVALID_SCORES') end
                earned = earned + points
            end
            if i <= 2 then a = a + earned else b = b + earned end
        end
        if not field then return rejected('NOT_READY') end
        if a > maxExact or b > maxExact then return rejected('INVALID_SCORES') end
    end
end

if not canIncrement(KEYS[1], ARGV[1], points) then return rejected('INVALID_SCORES') end
if isClick then
    if not canIncrement(KEYS[2], ARGV[2], points) or not canIncrement(KEYS[3], ARGV[3], points)
            or not compatible(KEYS[4], 'set') or not compatible(KEYS[5], 'set') or not compatible(KEYS[6], 'set')
            or not compatible(KEYS[7], 'string') or not compatible(KEYS[8], 'string') then
        return rejected('INVALID_SCORES')
    end
    local current = redis.call('GET', KEYS[7]) or '0'
    local previous = redis.call('GET', KEYS[8]) or '0'
    if not decimal(current) or not decimal(previous)
            or not decimal(ARGV[5]) or tonumber(ARGV[5]) > maxExact - tonumber(current) then
        return rejected('INVALID_SCORES')
    end
else
    if not canIncrement(KEYS[2], ARGV[3], whole(ARGV[4]))
            or not compatible(KEYS[3], 'set') or not compatible(KEYS[4], 'set') then
        return rejected('INVALID_SCORES')
    end
end

local result = applyOrdinary()
if field then
    redis.call('HINCRBY', matchKey, field, ARGV[isClick and 4 or 2])
    -- Keep the same shared key after close, including if retried through another action endpoint.
    redis.call('SET', claimKey, 'applied', 'EX', 172800)
else
    redis.call('SET', claimKey, 'applied', 'KEEPTTL')
end
return result
