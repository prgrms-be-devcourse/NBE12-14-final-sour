local c = redis.call('INCRBY', KEYS[1], ARGV[2])
if c == tonumber(ARGV[2]) then redis.call('EXPIRE', KEYS[1], ARGV[1]) end
return c