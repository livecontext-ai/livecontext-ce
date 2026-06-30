-- budget_decrement.lua
-- Atomic decrement-if-sufficient for budget consumption
--
-- KEYS[1] = budget hash key
-- ARGV[1] = tenantId (hash field)
-- ARGV[2] = amount to decrement
-- Returns: new balance string if decremented, current balance string if insufficient,
--          or nil (false) if key does not exist.

if redis.call('HEXISTS', KEYS[1], ARGV[1]) == 0 then
    return false
end

local current_str = redis.call('HGET', KEYS[1], ARGV[1])
local current = tonumber(current_str)
local amount = tonumber(ARGV[2])
if current >= amount then
    return redis.call('HINCRBYFLOAT', KEYS[1], ARGV[1], -amount)
end
-- Insufficient: return current value unchanged (no TOCTOU - single atomic read)
return current_str
