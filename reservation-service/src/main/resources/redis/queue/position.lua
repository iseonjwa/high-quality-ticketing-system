#!lua flags=no-writes
-- KEYS[1]=waiting  KEYS[2]=admitted  KEYS[3]=issued  KEYS[4]=admitLog
-- ARGV[1]=userId  ARGV[2]=토큰 유효 시간 T(ms)  ARGV[3]=차례 유예 시간(ms)
-- 반환: {종류, seq, admitted}  종류 = READY | WAITING | NOT_JOINED

local t = redis.call('TIME')
local now = tonumber(t[1])*1000 + math.floor(tonumber(t[2])/1000)
local userId = ARGV[1]

local issuedAt = redis.call('ZSCORE', KEYS[3], userId)
if issuedAt then
    if tonumber(issuedAt) > now - tonumber(ARGV[2]) then -- 토큰이 유효하다면
        return {'READY'}
    else
        return {'NOT_JOINED'}
    end
end

local seq = redis.call('ZSCORE', KEYS[1], userId)
if not seq then -- 번호를 발급받지 않은 상태면
    return {'NOT_JOINED'}
end

local admitted = redis.call('GET', KEYS[2]) or '0'
if tonumber(seq) <= tonumber(admitted) then -- 차례가 됨
    local admittedAt = redis.call('ZRANGEBYSCORE', KEYS[4], seq, '+inf', 'LIMIT', 0, 1)[1]
    if not admittedAt or tonumber(admittedAt) + tonumber(ARGV[3]) >= now then -- 유예 시간이 남았을 경우
        return {'READY'}
    else
        return {'NOT_JOINED'}
    end
else
    return {'WAITING', tonumber(seq), tonumber(admitted)}
end
