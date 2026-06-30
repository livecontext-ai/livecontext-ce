-- weighted_dequeue.lua
-- Capped Deficit Round Robin + atomic XREADGROUP (non-blocking, valid in Lua)
--
-- KEYS = [deficit_hash, stream_p70, stream_p60, ..., stream_p0]
-- ARGV = [weight_p70, weight_p60, ..., weight_p0, consumer_group, consumer_id, batch_size]
-- Returns: {stream_key, message_id, field1, val1, ...} or nil

local deficit_key = KEYS[1]
local num_tiers = #KEYS - 1
local MAX_DEFICIT_MULTIPLIER = 3

local group = ARGV[num_tiers + 1]
local consumer = ARGV[num_tiers + 2]
local batch = tonumber(ARGV[num_tiers + 3])

-- Step 1: add credits (capped)
for i = 1, num_tiers do
    local stream = KEYS[i + 1]
    local weight = tonumber(ARGV[i])
    local current = tonumber(redis.call('HGET', deficit_key, stream) or '0')
    local cap = weight * MAX_DEFICIT_MULTIPLIER
    local new_val = math.min(current + weight, cap)
    redis.call('HSET', deficit_key, stream, new_val)
end

-- Step 2: collect deficits for ALL tiers, sort descending
local tiers = {}
for i = 1, num_tiers do
    local stream = KEYS[i + 1]
    local d = tonumber(redis.call('HGET', deficit_key, stream) or '0')
    tiers[#tiers + 1] = { stream = stream, deficit = d, idx = i }
end

table.sort(tiers, function(a, b) return a.deficit > b.deficit end)

-- Step 3: try XREADGROUP on streams in deficit order (atomic, no race)
for _, tier in ipairs(tiers) do
    local result = redis.call('XREADGROUP', 'GROUP', group, consumer,
        'COUNT', batch, 'STREAMS', tier.stream, '>')
    if result and #result > 0 and #result[1][2] > 0 then
        -- Deduct deficit only on successful read
        redis.call('HINCRBY', deficit_key, tier.stream, -1)
        -- Return: stream key + first message
        local msg = result[1][2][1] -- {id, {field, val, ...}}
        local flat = { tier.stream, msg[1] }
        for _, fv in ipairs(msg[2]) do
            flat[#flat + 1] = fv
        end
        return flat
    end
end

return nil -- all streams truly empty of undelivered messages
