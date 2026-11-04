-- KEYS[1]=waiting  KEYS[2]=admitted  KEYS[3]=issued
-- ARGV[1]=userId  ARGV[2]=토큰 유효 시간 T(ms)  ARGV[3]=키 만료 시각(초)
-- TOKEN_ISSUED | NOT_READY | NOT_JOINED

local userId = ARGV[1]

local t = redis.call('TIME')
local now = tonumber(t[1])*1000 + math.floor(tonumber(t[2])/1000)
local issuedAt = redis.call('ZSCORE', KEYS[3], userId)
if issuedAt then
    if tonumber(issuedAt) > now - tonumber(ARGV[2]) then -- 아직 유효한 토큰
        redis.call('ZREM', KEYS[1], userId) -- waiting 정리
        return {'TOKEN_ISSUED', tonumber(issuedAt)}
    end
    redis.call('ZREM', KEYS[3], userId) -- issued 정리
    redis.call('ZREM', KEYS[1], userId) -- waiting 정리
    return {'NOT_JOINED'} -- 만료된 토큰이 있다는 것은, 만료 이후 대기열에 입장하지 않았음
end

local seq = redis.call('ZSCORE', KEYS[1], userId)
if not seq then
    return {'NOT_JOINED'}
end
local admitted = redis.call('GET', KEYS[2]) or '0'
if tonumber(seq) <= tonumber(admitted) then
    redis.call('ZADD', KEYS[3], now, userId)
    redis.call('ZREM', KEYS[1], userId)
    redis.call('EXPIREAT', KEYS[3], ARGV[3])
    return {'TOKEN_ISSUED', now}
end
return {'NOT_READY'}