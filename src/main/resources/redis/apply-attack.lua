-- KEYS[1] rank:univ
-- KEYS[2] dirty:univ
-- KEYS[3] score:user:attack
-- KEYS[4] dirty:user
-- KEYS[5] attacks:univ:{targetUniversityId}:{nowSec}        // 이번 초 학교 공격 수
-- KEYS[6] attacks:univ:{targetUniversityId}:{nowSec - 1}    // 직전 초 학교 공격 수
-- KEYS[7] clicks:univ:{targetUniversityId}:{nowSec - 1}     // 직전 초 학교 클릭 수
-- KEYS[8] assists:univ:{targetUniversityId}:{nowSec - 1}     // 직전 초 학교 지원 수
-- ARGV[1] targetUniversityId
-- ARGV[2] requestedDamage
-- ARGV[3] userId
-- ARGV[4] clickCount

local cur = tonumber(redis.call('ZSCORE', KEYS[1], ARGV[1]) or '0')
local damage = math.min(tonumber(ARGV[2]), cur)

local univTotal = redis.call('ZINCRBY', KEYS[1], -damage, ARGV[1])

-- 데미지가 0이면 변경X
if damage > 0 then
    redis.call('SADD', KEYS[2], ARGV[1])
    redis.call('SADD', KEYS[4], ARGV[3])
end

-- 초 단위 학교 공격 수
redis.call('INCRBY', KEYS[5], ARGV[4])
redis.call('EXPIRE', KEYS[5], 5)

local myAttack = redis.call('HINCRBY', KEYS[3], ARGV[3], damage)
local univRank = redis.call('ZREVRANK', KEYS[1], ARGV[1])   -- 0부터 시작
local attackLastSecond = redis.call('GET', KEYS[6]) or '0'
local clickLastSecond = redis.call('GET', KEYS[7]) or '0'
local assistLastSecond = redis.call('GET', KEYS[8]) or '0'

return { tostring(damage), univTotal, tostring(univRank + 1), tostring(myAttack),
 attackLastSecond, clickLastSecond, assistLastSecond }