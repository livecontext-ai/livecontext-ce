-- timer_cancel.lua
-- Atomic timer cancellation
--
-- KEYS[1] = timer sorted set key
-- KEYS[2] = timer payload hash key
-- ARGV[1] = timerId
-- Returns: 1 if cancelled, 0 if not found

local removed = redis.call('ZREM', KEYS[1], ARGV[1])
if removed == 1 then
    redis.call('HDEL', KEYS[2], ARGV[1])
    return 1
end
return 0
