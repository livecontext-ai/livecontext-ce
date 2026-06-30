-- timer_claim_due.lua
-- Atomically claim due timers (score <= now) using Redis server time
--
-- KEYS[1] = timer sorted set key
-- KEYS[2] = timer payload hash key
-- ARGV[1] = maxBatch
-- Returns: array of {timerId, score, payload} triples (flat)

local now_parts = redis.call('TIME')
local now_ms = tonumber(now_parts[1]) * 1000 + math.floor(tonumber(now_parts[2]) / 1000)

local due = redis.call('ZRANGEBYSCORE', KEYS[1], '-inf', now_ms, 'WITHSCORES', 'LIMIT', 0, tonumber(ARGV[1]))
if #due == 0 then return {} end

local result = {}
for i = 1, #due, 2 do
    local timerId = due[i]
    local score = due[i + 1]
    local payload = redis.call('HGET', KEYS[2], timerId) or ''
    redis.call('ZREM', KEYS[1], timerId)
    redis.call('HDEL', KEYS[2], timerId)
    result[#result + 1] = timerId
    result[#result + 1] = score
    result[#result + 1] = payload
end

return result
