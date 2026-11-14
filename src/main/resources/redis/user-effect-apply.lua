-- 회원에게 적용 중인 물약 효과를 새 효과로 덮어쓴다 (회원당 효과 1개).
-- DEL·HSET·PEXPIREAT을 한 스크립트로 묶어, 중간에 실패해도 TTL 없는 영구 효과가 남지 않게 한다.
-- KEYS[1]: user:{userId}:effect
-- ARGV[1]: itemId
-- ARGV[2]: multiplier
-- ARGV[3]: 만료 시각 (epoch ms). DB user_item.expired_at과 같은 값
-- 반환: 1

redis.call('DEL', KEYS[1])
redis.call('HSET', KEYS[1], 'itemId', ARGV[1], 'multiplier', ARGV[2], 'expiredAt', ARGV[3])
redis.call('PEXPIREAT', KEYS[1], ARGV[3])
return 1