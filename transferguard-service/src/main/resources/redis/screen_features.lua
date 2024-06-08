-- screen_features.lua: records this attempt and returns every per-account feature in one call.
-- All keys share the {account} hash tag, so the script also works on a Redis cluster.
--
-- KEYS[1] tg:{acct}:out      sorted set, member = txnId, score = epoch ms (every attempt)
-- KEYS[2] tg:{acct}:outdone  sorted set, member = txnId|amount (completed transfers)
-- KEYS[3] tg:{acct}:in       sorted set, member = creditId|sender|amount (inbound credits)
-- KEYS[4] tg:{acct}:payees   set of known payee accounts
-- KEYS[5] tg:{acct}:sec      hash: deviceId, deviceChangedAt, pwdResetAt
-- KEYS[6] tg:{acct}:avg      string "avg|count" (cache of account_daily_stats)
--
-- ARGV[1] now (epoch ms)
-- ARGV[2] transaction ID
-- ARGV[3] out trim bound: remove attempts with score < this (now - largest window)
-- ARGV[4] out key TTL in ms
-- ARGV[5] velocity lower bound (exclusive), or "" to skip
-- ARGV[6] pass-through lower bound (exclusive), or "" to skip
-- ARGV[7] pass-through trim bound for in/outdone, or ""
-- ARGV[8] payee account, or "" to skip the known-payee lookup
-- ARGV[9] "1" to read the security hash
-- ARGV[10] "1" to read the average cache
--
-- Returns a flat array of strings:
--   velocityCount, nIn, in..., nOut, outdone..., payeeKnown, deviceId, deviceChangedAt, pwdResetAt, avg
-- Missing values are "".

local function str(v)
  if v == false or v == nil then return '' end
  return tostring(v)
end

local result = {}

-- 1-3. Trim old attempts, record this one (idempotent on txnId), count the velocity window.
redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', '(' .. ARGV[3])
redis.call('ZADD', KEYS[1], 'NX', ARGV[1], ARGV[2])
redis.call('PEXPIRE', KEYS[1], ARGV[4])
if ARGV[5] ~= '' then
  table.insert(result, str(redis.call('ZCOUNT', KEYS[1], '(' .. ARGV[5], '+inf')))
else
  table.insert(result, '')
end

-- 4. Raw inbound credits and completed debits for the pass-through window.
if ARGV[6] ~= '' then
  redis.call('ZREMRANGEBYSCORE', KEYS[3], '-inf', '(' .. ARGV[7])
  redis.call('ZREMRANGEBYSCORE', KEYS[2], '-inf', '(' .. ARGV[7])
  local credits = redis.call('ZRANGEBYSCORE', KEYS[3], '(' .. ARGV[6], '+inf')
  table.insert(result, tostring(#credits))
  for _, m in ipairs(credits) do table.insert(result, m) end
  local debits = redis.call('ZRANGEBYSCORE', KEYS[2], '(' .. ARGV[6], '+inf')
  table.insert(result, tostring(#debits))
  for _, m in ipairs(debits) do table.insert(result, m) end
else
  table.insert(result, '0')
  table.insert(result, '0')
end

-- 5. Known payee, security state and the cached 30-day average.
if ARGV[8] ~= '' then
  table.insert(result, str(redis.call('SISMEMBER', KEYS[4], ARGV[8])))
else
  table.insert(result, '')
end
if ARGV[9] == '1' then
  local sec = redis.call('HMGET', KEYS[5], 'deviceId', 'deviceChangedAt', 'pwdResetAt')
  table.insert(result, str(sec[1]))
  table.insert(result, str(sec[2]))
  table.insert(result, str(sec[3]))
else
  table.insert(result, '')
  table.insert(result, '')
  table.insert(result, '')
end
if ARGV[10] == '1' then
  table.insert(result, str(redis.call('GET', KEYS[6])))
else
  table.insert(result, '')
end

return result
