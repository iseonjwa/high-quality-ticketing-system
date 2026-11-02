-- KEYS[1]=seq  KEYS[2]=waiting  KEYS[3]=admitted  KEYS[4]=issued  KEYS[5]=lastTick
-- ARGV[1]=초당 입장 인원  ARGV[2]=토큰 유효 시간 T(ms)  ARGV[3]=최대 정리 인원  ARGV[4]=키 만료 시각(초)

local t = redis.call('TIME')
local lastTick = redis.call('GET', KEYS[5])
if lastTick and tonumber(t[1]) == tonumber(lastTick) then
    return
end

local admitted = redis.call('GET', KEYS[3]) or '0'
local seq = redis.call('GET', KEYS[1])
if not seq then
    return
end
redis.call('SET', KEYS[3], math.min(tonumber(admitted)+tonumber(ARGV[1]), tonumber(seq)))
redis.call('SET', KEYS[5], t[1])

local now = tonumber(t[1])*1000 + math.floor(tonumber(t[2])/1000)
local expired = redis.call('ZRANGEBYSCORE', KEYS[4], '-inf', now - tonumber(ARGV[2]), 'LIMIT', 0, tonumber(ARGV[3]))
if #expired > 0 then
    redis.call('ZREM', KEYS[4], unpack(expired))
    redis.call('ZREM', KEYS[2], unpack(expired))
end

redis.call('EXPIREAT', KEYS[3], ARGV[4])
redis.call('EXPIREAT', KEYS[5], ARGV[4])