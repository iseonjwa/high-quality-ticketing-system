-- KEYS[1]=issued
-- ARGV[1]=userId  ARGV[2]=삭제하고자 하는 토큰의 발급 시각(ms)

local currentIssuedAt = redis.call('ZSCORE', KEYS[1], ARGV[1])
if currentIssuedAt and tonumber(currentIssuedAt) == tonumber(ARGV[2]) then -- 최근 발급 기록 시간과 일치한다면
    redis.call('ZREM', KEYS[1], ARGV[1])
end
