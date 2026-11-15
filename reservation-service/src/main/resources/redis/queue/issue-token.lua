-- KEYS[1]=waiting  KEYS[2]=admitted  KEYS[3]=issued  KEYS[4]=admitLog
-- ARGV[1]=userId  ARGV[2]=토큰 유효 시간 T(ms)  ARGV[3]=키 만료 시각(초)  ARGV[4]=차례 유예 시간(ms)
-- ADMITTED | WAITING | NOT_JOINED

local userId = ARGV[1]

local t = redis.call('TIME')
local now = tonumber(t[1])*1000 + math.floor(tonumber(t[2])/1000)
local issuedAt = redis.call('ZSCORE', KEYS[3], userId)
if issuedAt then
    if tonumber(issuedAt) > now - tonumber(ARGV[2]) then -- 아직 유효한 토큰이 있다면
        redis.call('ZREM', KEYS[1], userId) -- waiting 정리
        return {'ADMITTED', tonumber(issuedAt)}
    end
    redis.call('ZREM', KEYS[3], userId) -- issued 정리
    redis.call('ZREM', KEYS[1], userId) -- waiting 정리
    return {'NOT_JOINED'} -- 만료된 토큰이 있다는 것은, 만료 이후 대기열에 입장하지 않았음을 의미
end

local seq = redis.call('ZSCORE', KEYS[1], userId)
if not seq then
    return {'NOT_JOINED'}
end

local admitted = redis.call('GET', KEYS[2]) or '0'
if tonumber(seq) <= tonumber(admitted) then -- 차례가 됨
    local admittedAt = redis.call('ZRANGEBYSCORE', KEYS[4], seq, '+inf', 'LIMIT', 0, 1)[1]
    if not admittedAt or tonumber(admittedAt) + tonumber(ARGV[4]) >= now then -- 유예 시간이 남았을 경우
        redis.call('ZADD', KEYS[3], now, userId) -- issued에 기록
        redis.call('ZREM', KEYS[1], userId)
        redis.call('EXPIREAT', KEYS[3], ARGV[3])
        return {'ADMITTED', now}
    else -- 유예 시간이 끝남
        redis.call('ZREM', KEYS[1], userId)
        return {'NOT_JOINED'}
    end
end

return {'WAITING'} -- 아직 차례가 오지 않음. 409