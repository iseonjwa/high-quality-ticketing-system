-- 입장 토큰을 좌석 선점이 성공한 순간 소모한다(ADR 0053, ISE-207). 선점에 쓴 토큰의 jti를 선점 요청 행에 남긴다.
-- 추첨 선점처럼 입장 토큰 없이 들어온 요청은 NULL이다
ALTER TABLE seat_hold_requests ADD COLUMN entry_token_jti VARCHAR(64);

-- 같은 토큰으로 두 번째 선점을 막는다. 동시에 두 번 눌러도 DB가 하나만 통과시킨다.
-- REJECTED는 뺀다. 접수형 소비자가 거절한 요청은 선점에 실패한 것이라 토큰을 소모하지 않는다(ADR 0053 결정 1, 0057)
CREATE UNIQUE INDEX uq_seat_hold_requests_entry_token_jti
    ON seat_hold_requests (entry_token_jti)
    WHERE entry_token_jti IS NOT NULL AND status <> 'REJECTED';
