-- KEYS[1]=seq  KEYS[2]=waiting  KEYS[3]=admitted  KEYS[4]=issued
-- ARGV[1]=userId  ARGV[2]=오픈 시각(ms)  ARGV[3]=토큰 유효 시간 T(ms)  ARGV[4]=키 만료 시각(초)
-- 반환: {종류, seq, admitted}  종류 = NOT_OPEN | ISSUED | EXISTING | NEW

local t = redis.call('TIME')
local now = tonumber(t[1])*1000 + math.floor(tonumber(t[2])/1000)

if now < tonumber(ARGV[2]) then
    return {'NOT_OPEN'}
end

local userId = ARGV[1]
local issuedAt = redis.call('ZSCORE', KEYS[4], userId)
if issuedAt then
    if tonumber(issuedAt) > now - tonumber(ARGV[3]) then -- 아직 유효한 토큰
        redis.call('ZREM', KEYS[2], userId)
        return {'ISSUED'}
    end
    redis.call('ZREM', KEYS[4], userId)
    redis.call('ZREM', KEYS[2], userId)
end

local seq = redis.call('ZSCORE', KEYS[2], userId)
local admitted = redis.call('GET', KEYS[3]) or '0' -- 아직 안 만들어졌을 수 있음
if seq then
    return {'EXISTING', seq, admitted}
end

seq = redis.call('INCR', KEYS[1])
redis.call('ZADD', KEYS[2], seq, userId)
redis.call('EXPIREAT', KEYS[1], ARGV[4]) -- seq 삭제 예약
redis.call('EXPIREAT', KEYS[2], ARGV[4]) -- waiting 삭제 예약
return {'NEW', tostring(seq), admitted}