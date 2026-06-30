-- semaphore_acquire.lua
-- ZSET-based semaphore with per-owner expiry timestamps
--
-- KEYS[1] = semaphore key (ZSET)
-- ARGV[1] = maxPermits
-- ARGV[2] = ownerId
-- ARGV[3] = ttl_seconds
-- Returns: 1 if acquired, 0 if full

-- Remove expired owners using Redis server time (no clock skew)
local now = redis.call('TIME')
local now_s = tonumber(now[1])
redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', now_s)

local count = redis.call('ZCARD', KEYS[1])
if count < tonumber(ARGV[1]) then
    local expiry = now_s + tonumber(ARGV[3])
    redis.call('ZADD', KEYS[1], expiry, ARGV[2])
    -- Set key TTL to max(current TTL, 2x owner TTL) to avoid shrinking TTL when owners have different lifetimes
    local new_ttl = tonumber(ARGV[3]) * 2
    local cur_ttl = redis.call('TTL', KEYS[1])
    if cur_ttl < new_ttl then
        redis.call('EXPIRE', KEYS[1], new_ttl)
    end
    return 1
end
return 0
