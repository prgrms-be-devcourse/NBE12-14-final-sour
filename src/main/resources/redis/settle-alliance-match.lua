-- KEYS[1]: rank:univ
-- KEYS[2]: dirty:univ
-- KEYS[3]: match:{matchId}:settled
-- KEYS[4]: rank:hidden:univ (숨김 학교 점수, SCRUM-238)
-- ARGV[1~4]: 패배 A/B, 승리 A/B 학교 ID
-- ARGV[5~6]: 패배 A/B 학교별 대결 기여도
-- ARGV[7~10]: ARGV[1~4] 학교가 숨김 학교면 '1'. 숨김 학교 점수는 KEYS[4]에서 읽고 쓴다 (없으면 KEYS[1])

-- 이미 정산된 대결이면 중복 지급하지 않는다.
if redis.call('EXISTS', KEYS[3]) == 1 then
    return 0
end

local loserA = ARGV[1]
local loserB = ARGV[2]
local winnerA = ARGV[3]
local winnerB = ARGV[4]

local contributionA = tonumber(ARGV[5])
local contributionB = tonumber(ARGV[6])

local maxSafe = 9007199254740991

if not contributionA or not contributionB
        or contributionA < 0 or contributionB < 0
        or contributionA ~= math.floor(contributionA)
        or contributionB ~= math.floor(contributionB)
        or contributionA > maxSafe or contributionB > maxSafe then
    return redis.error_reply('INVALID_CONTRIBUTION')
end

local ids = {loserA, loserB, winnerA, winnerB}
local scores = {}

-- 학교 점수 키. 숨김 학교 점수는 공개 학교 순위(KEYS[1])에 넣지 않는다
local function rankKey(i)
    if ARGV[6 + i] == '1' then
        return KEYS[4]
    end
    return KEYS[1]
end

-- 모든 현재 점수를 먼저 읽고 검증한다.
for i = 1, 4 do
    local score = tonumber(redis.call('ZSCORE', rankKey(i), ids[i]) or '0')

    if not score or score < 0 or score > maxSafe
            or score ~= math.floor(score) then
        return redis.error_reply('INVALID_UNIVERSITY_SCORE')
    end

    scores[i] = score
end

-- 패배 학교별 실제 차감액
local deductionA = math.min(contributionA, scores[1])
local deductionB = math.min(contributionB, scores[2])

local totalDeduction = deductionA + deductionB

if totalDeduction > maxSafe then
    return redis.error_reply('SETTLEMENT_OVERFLOW')
end

-- 승리 학교 두 곳에 50:50 지급
local rewardA = math.floor(totalDeduction / 2)
local rewardB = totalDeduction - rewardA

local newScores = {
    scores[1] - deductionA,
    scores[2] - deductionB,
    scores[3] + rewardA,
    scores[4] + rewardB
}

for i = 1, 4 do
    if newScores[i] > maxSafe then
        return redis.error_reply('SETTLEMENT_OVERFLOW')
    end
end

-- 학교 점수 변경
for i = 1, 4 do
    redis.call('ZADD', rankKey(i), newScores[i], ids[i])
    redis.call('SADD', KEYS[2], ids[i])
end

-- 같은 대결은 다시 지급하지 않도록 표시
redis.call('SET', KEYS[3], '1')

return 1