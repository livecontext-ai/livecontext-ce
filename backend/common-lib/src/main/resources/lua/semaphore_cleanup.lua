-- semaphore_cleanup.lua
-- Remove expired owners from ZSET semaphore using Redis server time
--
-- KEYS[1] = semaphore key (ZSET)
-- Returns: number of expired owners removed

local now = tonumber(redis.call('TIME')[1])
return redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', now)
