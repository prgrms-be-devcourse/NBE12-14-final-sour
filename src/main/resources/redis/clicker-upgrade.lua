-- 클리커 업그레이드: 기여도 차감, 레벨 +1, 변경 표시를 한 번에 처리한다.
-- KEYS[1]: rank:univ:{universityId}:user (교내 개인 순위 ZSet, score = 기여도)
-- KEYS[2]: dirty:user (1분 배치가 기여도를 DB에 반영할 유저 ID 모음)
-- KEYS[3]: user:{userId}:clicker (레벨 원본)
-- KEYS[4]: dirty:clicker (1분 배치가 레벨을 DB에 반영할 유저 ID 모음)
-- ARGV[1]: userId
-- ARGV[2]: 비용
-- ARGV[3]: 기대 레벨 (서버가 비용을 계산할 때 쓴 레벨)
-- 반환: 차감 후 잔액, 잔액이 부족하거나 ZSet에 기록이 없으면 -1, 레벨이 기대값과 다르면 -2

local level = tonumber(redis.call('GET', KEYS[3]))
if level ~= tonumber(ARGV[3]) then
    return -2
end

local score = redis.call('ZSCORE', KEYS[1], ARGV[1])
if not score or tonumber(score) < tonumber(ARGV[2]) then
    return -1
end

local remaining = redis.call('ZINCRBY', KEYS[1], -tonumber(ARGV[2]), ARGV[1])
redis.call('SADD', KEYS[2], ARGV[1])
redis.call('INCR', KEYS[3])
redis.call('SADD', KEYS[4], ARGV[1])
return tonumber(remaining)
