-- semaphore_release.lua
-- Remove owner from ZSET semaphore
--
-- KEYS[1] = semaphore key (ZSET)
-- ARGV[1] = ownerId
-- Returns: 1 if removed, 0 if not found

return redis.call('ZREM', KEYS[1], ARGV[1])
