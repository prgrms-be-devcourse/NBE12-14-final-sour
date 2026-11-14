local c = redis.call('INCRBY', KEYS[1], ARGV[2])
if c == tonumber(ARGV[2]) then redis.call('EXPIRE', KEYS[1], ARGV[1]) end
local max = tonumber(ARGV[3])
if c > max and c - tonumber(ARGV[2]) <= max then
    redis.call('ZINCRBY', KEYS[2], 1, ARGV[5])
    if redis.call('TTL', KEYS[2]) == -1 then redis.call('EXPIRE', KEYS[2], ARGV[4]) end
end
return c