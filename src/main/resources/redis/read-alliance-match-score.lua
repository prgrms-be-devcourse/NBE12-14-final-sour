-- KEYS: live ledger, immutable snapshot. ARGV: read/finalize, start, end, four roster IDs.
local mode = ARGV[1]
local live, snapshot = KEYS[1], KEYS[2]
local fields = {'aUniversity1', 'aUniversity2', 'bUniversity1', 'bUniversity2'}
local limit = 9007199254740991
local function integer(value)
    if not value or not (value == '0' or value:match('^[1-9]%d*$')) then return nil end
    local number = tonumber(value)
    if number and number <= limit then return number end
end
local function totals(key)
    if redis.call('TYPE', key).ok ~= 'hash' then return nil end
    if redis.call('HGET', key, 'startedAt') ~= ARGV[2]
            or redis.call('HGET', key, 'endedAt') ~= ARGV[3] then return nil end
    local scores = {}
    for i, field in ipairs(fields) do
        if redis.call('HGET', key, field .. 'Id') ~= ARGV[i + 3] then return nil end
        scores[i] = integer(redis.call('HGET', key, field .. 'Contribution'))
        if not scores[i] then return nil end
    end
    local a, b = scores[1] + scores[2], scores[3] + scores[4]
    if a > limit or b > limit then return nil end
    return string.format('%.0f', a), string.format('%.0f', b), scores
end

local time = redis.call('TIME')
local now = tonumber(time[1]) * 1000000 + tonumber(time[2])
if mode == 'finalize' and now < tonumber(ARGV[3]) then return {'EARLY'} end
local snapshotType = redis.call('TYPE', snapshot).ok
if snapshotType ~= 'none' then
    local a, b = totals(snapshot)
    if snapshotType ~= 'hash' or not a or redis.call('HGET', snapshot, 'finalized') ~= '1'
            or redis.call('HGET', snapshot, 'aScore') ~= a or redis.call('HGET', snapshot, 'bScore') ~= b then
        return {'UNAVAILABLE'}
    end
    return {'SETTLEMENT_PENDING', a, b}
end

local a, b, scores = totals(live)
if not a then return {'UNAVAILABLE'} end
if mode == 'read' then
    if now >= tonumber(ARGV[3]) then return {'FINALIZING'} end
    return {'LIVE', a, b}
end

local args = {'finalized', '1', 'aScore', a, 'bScore', b, 'startedAt', ARGV[2], 'endedAt', ARGV[3]}
for i, field in ipairs(fields) do
    args[#args + 1] = field .. 'Id'
    args[#args + 1] = ARGV[i + 3]
    args[#args + 1] = field .. 'Contribution'
    args[#args + 1] = string.format('%.0f', scores[i])
end
redis.call('HSET', snapshot, unpack(args))
return {'SETTLEMENT_PENDING', a, b}
