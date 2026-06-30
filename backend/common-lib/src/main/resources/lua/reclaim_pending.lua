-- reclaim_pending.lua
-- Reclaims one stale pending Redis Stream message across priority streams.
--
-- KEYS = [cursor_hash, stream_p70, stream_p60, ..., stream_p0]
-- ARGV = [consumer_group, consumer_id, min_idle_ms]
-- Returns: {stream_key, message_id, field1, val1, ...} or nil

local cursor_key = KEYS[1]
local group = ARGV[1]
local consumer = ARGV[2]
local min_idle_ms = ARGV[3]

for i = 2, #KEYS do
    local stream = KEYS[i]
    local cursor = redis.call('HGET', cursor_key, stream) or '0-0'
    local result = redis.call('XAUTOCLAIM', stream, group, consumer, min_idle_ms, cursor, 'COUNT', 1)
    local next_cursor = result and result[1] or '0-0'
    redis.call('HSET', cursor_key, stream, next_cursor)
    if result and result[2] and #result[2] > 0 then
        local msg = result[2][1] -- {id, {field, val, ...}}
        local flat = { stream, msg[1] }
        for _, fv in ipairs(msg[2]) do
            flat[#flat + 1] = fv
        end
        return flat
    end
end

return nil
