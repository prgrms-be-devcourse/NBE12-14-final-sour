-- KEYS[1] rank:univ
-- KEYS[2] dirty:univ
-- KEYS[3] score:user:attack
-- ARGV[1] targetUniversityId
-- ARGV[2] requestedDamage
-- ARGV[3] userId

local cur = tonumber(redis.call('ZSCORE', KEYS[1], ARGV[1]) or '0')
local damage = math.min(tonumber(ARGV[2]), cur)

local univTotal = redis.call('ZINCRBY', KEYS[1], -damage, ARGV[1])

-- 데미지가 0이면 변경X
if damage > 0 then
    redis.call('SADD', KEYS[2], ARGV[1])
end
local myAttack = redis.call('HINCRBY', KEYS[3], ARGV[3], damage)
local univRank = redis.call('ZREVRANK', KEYS[1], ARGV[1])   -- 0부터 시작

return { tostring(damage), univTotal, tostring(univRank + 1), tostring(myAttack) }