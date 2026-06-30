-- budget_reserve_batch.lua
-- Atomic partial batch budget reservation
--
-- KEYS[1] = budget hash key
-- ARGV[1] = tenantId (hash field)
-- ARGV[2] = costPerItem
-- ARGV[3] = itemCount
-- Returns: number of affordable items (0 to itemCount)

local perItem = tonumber(ARGV[2])
local count = tonumber(ARGV[3])
-- Guard against negative cost; zero-cost items are free (allow all)
if perItem < 0 then return 0 end
if perItem == 0 then return count end

local current = tonumber(redis.call('HGET', KEYS[1], ARGV[1]) or '0')
local affordable = math.min(count, math.floor(current / perItem))
if affordable <= 0 then return 0 end

local total = perItem * affordable
redis.call('HINCRBYFLOAT', KEYS[1], ARGV[1], -total)
return affordable
