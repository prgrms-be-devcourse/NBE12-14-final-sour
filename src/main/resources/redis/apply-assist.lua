-- 연합 학교 지원 (SCRUM-187). 대상 학교 점수와 내 기여도만 올리고, 내 학교·학과 점수는 그대로 둔다
-- KEYS[1] rank:univ                     학교 점수 ZSET
-- KEYS[2] rank:univ:{내 학교}:user       개인 기여도 ZSET
-- KEYS[3] dirty:univ   KEYS[4] dirty:user
-- KEYS[5] alliance:{allianceId}:score    연합 점수
-- KEYS[6] match:{matchId}:score       대결 점수
-- KEYS[7] dirty:alliance   KEYS[8] dirty:match
-- ARGV[1] targetUniversityId  ARGV[2] assistScore (정수)  ARGV[3] userId  ARGV[4] contribScore (정수)
-- ARGV[5] allianceId
-- ARGV[6] team(A|B)
-- ARGV[7] matchId

local assist = tonumber(ARGV[2])
local contrib = tonumber(ARGV[4])

local univTotal = redis.call('ZINCRBY', KEYS[1], assist, ARGV[1])
local myContrib = redis.call('ZINCRBY', KEYS[2], contrib, ARGV[3])

-- 1분 배치가 바뀐 대상만 DB에 쓰도록 표시
redis.call('SADD', KEYS[3], ARGV[1])
redis.call('SADD', KEYS[4], ARGV[3])

if ARGV[5] ~= '0' then
    redis.call('HINCRBY', KEYS[5], ARGV[1], assist)
    redis.call('SADD', KEYS[7], ARGV[5])
end

if ARGV[6] ~= '' then
    redis.call('HINCRBY', KEYS[6], ARGV[6], assist)
    redis.call('SADD', KEYS[8], ARGV[7])
end

local univRank = redis.call('ZREVRANK', KEYS[1], ARGV[1])   -- 0부터 시작

return { univTotal, tostring(univRank + 1), myContrib }
