---
title: PR 487 대기열 담당 리뷰 반영에서 오픈 피크용 거절 경보를 따로 두자는 AI 추천(A안)을 받아들임
date: 2026-11-11
updated: 2026-11-11
judge: kangmin
ai: Claude Code (리뷰 6건을 규칙·Alertmanager 설정·필터 코드·측정 기록과 대조)
verdict: 수용
area: 기타
presentation: 아님
shared: 예
---

# PR 487 대기열 담당 리뷰 반영에서 오픈 피크용 거절 경보를 따로 두자는 AI 추천(A안)을 받아들임

## 맥락
- 해결하려던 작업: ISE-230 T3 PR(`#487`)에 대기열 담당이 리뷰 6건을 남겼다. 가장 큰 지적은 "5분 평균·5분 지속 경보는 수십 초짜리 오픈 피크를 못 잡아, ADR 0050의 바꿀 조건(경보가 오픈마다 울리면 입구 요청률 제한)이 발동하지 않는다"였다.
- AI 활용 방식: 각 지적이 맞는지 코드·설정·측정 기록으로 확인하고 반영안을 달라고 했다.

## AI 제안
- 6건 모두 타당하다고 봤다. 2번(Redis 지연도 bulkhead를 채움)은 필터 시한이 차단기·bulkhead 안쪽에 걸린 코드로 확인했다(50 ÷ 0.2초 = 초당 250건). 3번의 예외 종류·시간은 저장소 기록에 없어 "간접 근거"로만 적자고 했다.
- 1번 선택지: A. 5분 규칙을 지속 과부하용으로 두고 1분 창·`for` 없음 피크 규칙을 더한다. B. 경보는 그대로 두고 바꿀 조건만 측정 기준으로 바꾼다.
- 추천: A. 11/15 재측정에서 피크 규칙이 울리는지 보면 기준값을 측정으로 고칠 근거가 생긴다. 대가는 오탐 증가, 20초 안팎보다 짧은 몰림은 여전히 놓친다는 것이다.

## 판단
- 결과: 수용(A안)
- 판단자: kangmin
- 근거: 미기재
- 검토한 대안: B안(경보 유지, 바꿀 조건을 측정 기준으로)

## 반영
- `infra/prometheus/rules/gateway-alerts.yml`: `GatewayAuthRedisBulkheadRejectingPeak` 추가, 두 규칙 문구를 원인을 단정하지 않게 바꾸고 runbook에 `lookup_timeout`·`redis_error` 확인을 적음
- `infra/prometheus/tests/gateway-alerts.test.yml`: 분모에 `failed`·`ignored` 시리즈, 피크 시험, `for` 구간 시험(8분)
- [ADR 0050](../../decisions/0050-gateway-auth-redis-breaker-bulkhead-split.md) 결정 2·감수·바꿀 조건·후속(11/15 대기열 재측정)
- `docs/design/infra/open-peak-scenarios.md` G1 절 제목을 "배포 서버 4차"로(리뷰 5번)

## 검증
- `script/check-prometheus-rules.sh`: 규칙 2개 문법, 단위 테스트 통과.
- 변이 테스트: 피크 기준 0.05, 피크 창 5분, 지속 규칙 `for` 제거, 지속 기준 0.05 네 가지 모두 단위 테스트가 실패했다. 처음에는 `for` 제거가 통과해서 8분 시험을 더했다. 되돌린 뒤 통과.
- 실제 오픈 피크에서 울리는지는 아직이다(11/15 재측정).

## 판단 이력
- 2026-11-11 수용: A안으로 리뷰 6건을 반영함
