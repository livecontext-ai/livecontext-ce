-- stream_ack_delete.lua
-- Atomically XACK + XDEL a message from a Redis Stream.
--
-- KEYS[1] = stream key
-- ARGV[1] = consumer group
-- ARGV[2] = message id
-- Returns: 1 if acknowledged and deleted, 0 if message not found in PEL

local acked = redis.call('XACK', KEYS[1], ARGV[1], ARGV[2])
if acked > 0 then
    redis.call('XDEL', KEYS[1], ARGV[2])
    return 1
end
return 0
