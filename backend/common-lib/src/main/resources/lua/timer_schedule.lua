-- timer_schedule.lua
-- Atomic timer schedule using sorted set
--
-- KEYS[1] = timer sorted set key
-- KEYS[2] = timer payload hash key
-- ARGV[1] = timerId
-- ARGV[2] = executeAt (epoch milliseconds)
-- ARGV[3] = payload (JSON string)
-- ARGV[4] = key TTL seconds
-- Returns: 1 if scheduled, 0 if timer already exists

local exists = redis.call('ZSCORE', KEYS[1], ARGV[1])
if exists then return 0 end

redis.call('ZADD', KEYS[1], tonumber(ARGV[2]), ARGV[1])
redis.call('HSET', KEYS[2], ARGV[1], ARGV[3])
redis.call('EXPIRE', KEYS[1], tonumber(ARGV[4]))
redis.call('EXPIRE', KEYS[2], tonumber(ARGV[4]))
return 1
