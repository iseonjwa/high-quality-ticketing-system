#!lua flags=no-writes
-- KEYS[1]=waiting  KEYS[2]=admitted  KEYS[3]=issued
-- ARGV[1]=userId  ARGV[2]=토큰 유효 시간 T(ms)
-- 반환: {종류, seq, admitted}  종류 = TOKEN_ISSUED | IN_QUEUE | NOT_JOINED

local t = redis.call('TIME')
local now = tonumber(t[1])*1000 + math.floor(tonumber(t[2])/1000)
local userId = ARGV[1]

local issuedAt = redis.call('ZSCORE', KEYS[3], userId)
if issuedAt then
    if tonumber(issuedAt) > now - tonumber(ARGV[2]) then -- 토큰이 유효하다면
        return {'TOKEN_ISSUED'}
    else
        return {'NOT_JOINED'}
    end
end

local seq = redis.call('ZSCORE', KEYS[1], userId)
if seq then
    local admitted = redis.call('GET', KEYS[2]) or '0'
    return {'IN_QUEUE', tonumber(seq), tonumber(admitted)}
end

return {'NOT_JOINED'}