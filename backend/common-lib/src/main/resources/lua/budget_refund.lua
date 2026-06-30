-- budget_refund.lua
-- Idempotent budget refund with sharded per-hour dedup sets
--
-- KEYS[1] = budget hash key
-- KEYS[2] = base dedup key (will be appended with hour bucket)
-- ARGV[1] = tenantId (hash field)
-- ARGV[2] = amount to refund
-- ARGV[3] = requestId (dedup key)
-- Returns: 1 if refunded, 0 if already refunded (dedup hit)

local now_parts = redis.call('TIME')
local hour_bucket = math.floor(tonumber(now_parts[1]) / 3600)
local dedup_key = KEYS[2] .. ':' .. hour_bucket
-- Check both current and previous hour bucket (boundary race prevention)
local prev_bucket = hour_bucket - 1
local prev_key = KEYS[2] .. ':' .. prev_bucket

local already = redis.call('SISMEMBER', dedup_key, ARGV[3])
if already == 1 then return 0 end
local already_prev = redis.call('SISMEMBER', prev_key, ARGV[3])
if already_prev == 1 then return 0 end

redis.call('SADD', dedup_key, ARGV[3])
redis.call('EXPIRE', dedup_key, 28800) -- 8h TTL
redis.call('HINCRBYFLOAT', KEYS[1], ARGV[1], tonumber(ARGV[2]))
return 1
