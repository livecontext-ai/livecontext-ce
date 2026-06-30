-- semaphore_heartbeat.lua
-- Extend owner's expiry score and key TTL using Redis server time
--
-- KEYS[1] = semaphore key (ZSET)
-- ARGV[1] = ownerId
-- ARGV[2] = ttl_seconds
-- Returns: 1 if owner exists and was updated, 0 if not found

local exists = redis.call('ZSCORE', KEYS[1], ARGV[1])
if not exists then return 0 end

local now = tonumber(redis.call('TIME')[1])
redis.call('ZADD', KEYS[1], now + tonumber(ARGV[2]), ARGV[1])
-- Preserve longer TTL from other owners with different lifetimes
local new_ttl = tonumber(ARGV[2]) * 2
local cur_ttl = redis.call('TTL', KEYS[1])
if cur_ttl < new_ttl then
    redis.call('EXPIRE', KEYS[1], new_ttl)
end
return 1
