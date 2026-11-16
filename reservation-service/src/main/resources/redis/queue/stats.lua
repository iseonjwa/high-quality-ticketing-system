-- KEYS[1]=seq  KEYS[2]=waiting  KEYS[3]=admitted  KEYS[4]=issued
-- ARGV[1]=토큰 유효 시간(ms)

-- 반환: {줄 선 총인원, 입장된 번호, 차례가 오지 않은 대기 인원(이탈자 포함), 차례가 왔는데 입장 토큰을 받지 않은 인원, 유효한 입장 토큰을 가진 인원}

local t = redis.call('TIME')
local now = tonumber(t[1]*1000) + math.floor(tonumber(t[2]/1000))

local joined = tonumber(redis.call('GET', KEYS[1]) or '0')
local admitted = tonumber(redis.call('GET', KEYS[3]) or '0')
local waiting = redis.call('ZCOUNT', KEYS[2], '(' .. admitted, '+inf')
local admittedUnissued = redis.call('ZCOUNT', KEYS[2], '-inf', admitted)
local activeTokens = redis.call('ZCOUNT', KEYS[4], '(' .. (now-tonumber(ARGV[1])), '+inf')

return {joined, admitted, waiting, admittedUnissued, activeTokens} -- ZCOUNT의 결과는 Long으로 반환됨