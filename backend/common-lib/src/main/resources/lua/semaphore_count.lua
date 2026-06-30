-- semaphore_count.lua
-- Atomically cleanup expired owners and return the current count.
--
-- KEYS[1] = semaphore key (ZSET)
-- Returns: number of active (non-expired) owners

local now = tonumber(redis.call('TIME')[1])
redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', now)
return redis.call('ZCARD', KEYS[1])
