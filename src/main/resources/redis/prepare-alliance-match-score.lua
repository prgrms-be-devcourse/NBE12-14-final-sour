-- KEYS[1] score hash. ARGV: startedAt, endedAt (epoch microseconds), four roster IDs in order.
local key = KEYS[1]
local fields = {'startedAt', 'endedAt', 'aUniversity1Id', 'aUniversity2Id', 'bUniversity1Id', 'bUniversity2Id'}
local contributions = {'aUniversity1Contribution', 'aUniversity2Contribution',
                       'bUniversity1Contribution', 'bUniversity2Contribution'}
local kind = redis.call('TYPE', key).ok
if kind == 'hash' then
    local values = redis.call('HMGET', key, unpack(fields))
    for i = 1, #fields do
        if values[i] ~= ARGV[i] then return 0 end
    end
    for _, value in ipairs(redis.call('HMGET', key, unpack(contributions))) do
        if not value or not string.match(value, '^%d+$') or tonumber(value) > 9007199254740991 then
            return 0
        end
    end
    return 1
end
if kind ~= 'none' then return 0 end

local time = redis.call('TIME')
local now = tonumber(time[1]) * 1000000 + tonumber(time[2])
if now >= tonumber(ARGV[2]) then return 0 end

redis.call('HSET', key,
    fields[1], ARGV[1], fields[2], ARGV[2], fields[3], ARGV[3],
    fields[4], ARGV[4], fields[5], ARGV[5], fields[6], ARGV[6],
    contributions[1], '0', contributions[2], '0', contributions[3], '0', contributions[4], '0')
return 1
