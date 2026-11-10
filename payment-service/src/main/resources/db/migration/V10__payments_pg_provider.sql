-- 결제마다 처리한 PG를 남긴다(ADR 0023 2026-11-10 개정). 기존 행은 모두 Mock으로 냈다(배포 기본 mock, 토스 결제 이력 없음)
ALTER TABLE payments ADD COLUMN pg_provider VARCHAR(10) NOT NULL DEFAULT 'MOCK';
-- 새 행은 값을 반드시 적게 기본값을 뗀다. 빠뜨린 코드가 조용히 MOCK으로 저장되지 않게 한다
ALTER TABLE payments ALTER COLUMN pg_provider DROP DEFAULT;
ALTER TABLE payments ADD CONSTRAINT ck_payments_pg_provider CHECK (pg_provider IN ('MOCK', 'TOSS'));
