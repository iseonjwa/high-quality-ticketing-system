---
title: ISE-79 동시성 시나리오 설계와 이슈 전제 검토를 수용하고 별도 워크트리에서 단계별 실측을 지시
date: 2026-11-03
updated: 2026-11-03
judge: 최권우
ai: Claude Code
verdict: 수용
area: 동시성
presentation: 후보
shared: 예
---

# ISE-79 동시성 시나리오 설계와 이슈 전제 검토를 수용하고 별도 워크트리에서 단계별 실측을 지시

## 맥락
- 해결하려던 작업: ISE-79(좌석 선점·만료 동시성 제어와 데드락 방어)의 개선 결과를 문서화할 단계적 시나리오, 동시성 테스트 세팅, 부하 시나리오 구성과 검토.
- AI 활용 방식: Linear ISE-79 본문과 `docs/` 설계 문서를 근거로 시나리오 구성과 검토를 요청했다.

## AI 제안
- 단계 S0(기준선) → S1(TS-2 매수) → S2(TS-3 멱등키) → S3(TS-1 lock_timeout) → S4(TS-4 만료)로 한 단계에 변경 하나씩 넣고, 동시성 시나리오 C1~C7과 부하 시나리오 L1~L6으로 전후를 잰다([concurrency-scenarios.md](../../design/reservation/concurrency-scenarios.md), [load-test-scenarios.md](../../design/reservation/load-test-scenarios.md)).
- 이슈 전제 검토([concurrency-review.md](../../design/reservation/concurrency-review.md)): TS-1 역순 데드락은 현재 코드(정렬 + 단일 UPDATE)에서 재현되기 어렵다. TS-3의 증상은 500이 아니라 자기 요청 409다. TS-4의 ID만 `SKIP LOCKED`로 뽑는 방식은 효과가 없다. 만료만 SA 8-4 잠금 순서로 바꾸면 결제 결과 소비자와 순서가 엇갈릴 수 있다.

## 판단
- 결과: 수용
- 판단자: 최권우
- 근거: 미기재. 사용자는 "별도의 워크트리에서 시나리오대로 세팅해서 결과 지표 만들어줘"라고 지시했다.
- 검토한 대안: 없음

## 반영
- 워크트리 `.claude/worktrees/ise-79-measure`(브랜치 `experiment/ISE-79-concurrency-measure`, 미커밋)에 S1~S4 변경, 동시성 테스트 `SeatHoldConcurrencyTest`, 부하 근사 테스트 `SeatHoldLoadTest`를 두었다.
- 결과: [concurrency-results.md](../../design/reservation/concurrency-results.md), 원자료 [concurrency-results-raw.md](../../design/reservation/concurrency-results-raw.md).

## 검증
- 동시성: 기준선에서 C4(한도 초과 30/30라운드), C5(자기 요청 409 60/90), C6(헛수고 100/100), C3(잠금 대기 p50 2,034ms)가 재현됐고, 해당 단계 적용 뒤 각각 0, 0, 0, 564ms가 됐다. C2·C7 데드락은 기준선부터 0이었다.
- 부하: 병목은 커넥션 풀이었다(풀 5 → 20에서 처리량 약 2배). S2·S3은 요청당 DB 왕복을 늘려 처리량을 낮췄다(VU 400 중앙값 1,125 → 669 req/s, 편차 큼).
- 회귀: S4 상태에서 `./gradlew :reservation-service:test --rerun` 263건 통과, 실패 0.

## 판단 이력
- 2026-11-03 수용: 시나리오와 검토를 받아들이고 워크트리에서 실측을 지시했다.
