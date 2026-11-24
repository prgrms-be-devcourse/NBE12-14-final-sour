-- KEYS[1] rank:univ:{universityId}:user   KEYS[2] dirty:user
-- KEYS[3] user:{userId}:effect             KEYS[4] purchase:{userId}:{idempotencyKey}
-- KEYS[5] user:{userId}:effect:voided (취소된 purchaseId → 그 구매가 꺼낸 이전 효과)
-- ARGV[1] userId  ARGV[2] price  ARGV[3] purchaseId  ARGV[4] 이전 효과 JSON 또는 ""  ARGV[5] 취소 기록 TTL(ms)
-- 반환: 1 (정상), -3 (취소 기록 연쇄가 10번을 넘어 효과를 지움)
local result = 1
redis.call('ZINCRBY', KEYS[1], tonumber(ARGV[2]), ARGV[1])
redis.call('SADD', KEYS[2], ARGV[1])
-- 효과 주인(purchaseId)이 이 구매일 때만 효과를 되돌린다 (HGET이 nil이면 false라 "주인 아님")
if redis.call('HGET', KEYS[3], 'purchaseId') == ARGV[3] then
    -- 복원 대상: 기본은 넘겨받은 이전 효과. 그 효과의 구매가 이미 취소됐으면(voided에 있으면)
    -- 취소된 구매가 꺼냈던 효과로 건너뛴다. 살아 있는 효과(또는 빈 값)를 만날 때까지 반복한다.
    -- 10번을 넘으면 어떤 효과가 맞는지 알 수 없으므로 효과를 지우고(target = '') -3을 돌려준다.
    -- 상한이 없으면 순환 기록에서 Lua가 끝나지 않아 Redis가 멈춘다.
    local target = ARGV[4]
    local hops = 0
    while target ~= '' do
        local previousId = cjson.decode(target)['purchaseId']
        if previousId and redis.call('HEXISTS', KEYS[5], previousId) == 1 then
            hops = hops + 1
            if hops > 10 then
                target = ''
                result = -3
                break
            end
            target = redis.call('HGET', KEYS[5], previousId)
        else
            break
        end
    end
    if target == '' then
        redis.call('DEL', KEYS[3])
    else
        local previous = cjson.decode(target)
        local args = {}
        for field, value in pairs(previous) do
            args[#args + 1] = field
            args[#args + 1] = value
        end
        -- 복원 순서: DEL → HSET → PEXPIREAT. 구매 Lua는 DEL 없이 덮어쓰므로, 이전 효과가 배포 전 3필드였다면
        -- DEL 없이 HSET만 할 때 취소된 구매의 purchaseId가 남는다.
        -- cjson.decode는 DEL보다 앞에 둔다: 깨진 JSON이면 효과를 지우기 전에 멈춘다 (Redis는 실행된 명령을 되돌리지 않는다)
        redis.call('DEL', KEYS[3])
        -- 만료 시각이 없는 효과는 복원하지 않는다 (TTL 없는 키가 남으면 ClickService가 배율을 영구 적용한다)
        -- 현재 효과를 쓰는 코드는 항상 expiredAt과 PEXPIREAT을 함께 써서 이 분기에 들어올 경로는 없다(방어용)
        if previous['expiredAt'] then
            redis.call('HSET', KEYS[3], unpack(args))
            redis.call('PEXPIREAT', KEYS[3], previous['expiredAt'])
        end
    end
else
    -- 주인이 아니면 효과는 건드리지 않고, 취소된 구매가 꺼낸 이전 효과를 기록한다 ("" 포함).
    -- 지금 주인이 나중에 보상할 때 이 기록을 보고 취소된 효과를 건너뛴다.
    redis.call('HSET', KEYS[5], ARGV[3], ARGV[4])
    redis.call('PEXPIRE', KEYS[5], ARGV[5])
end
redis.call('DEL', KEYS[4])
return result
