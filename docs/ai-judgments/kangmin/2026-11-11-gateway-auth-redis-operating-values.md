---
title: Gateway auth-redis 운영값을 G1 2회 값(필터 시한 200ms·Lettuce 300ms)으로 정하고 bulkhead 거절 비율 경보를 같이 넣자는 AI 추천을 받아들임
date: 2026-11-11
updated: 2026-11-11
judge: kangmin
ai: Claude Code (11/10 배포 측정 1~3차·G1 재측정과 설정·라이브러리 소스 분석)
verdict: 수용
area: 기타
presentation: 후보
shared: 예
---

# Gateway auth-redis 운영값을 G1 2회 값(필터 시한 200ms·Lettuce 300ms)으로 정하고 bulkhead 거절 비율 경보를 같이 넣자는 AI 추천을 받아들임

## 맥락
- 해결하려던 작업: ISE-230 마무리(T3). 측정 덮어쓰기로만 확인한 auth-redis 값을 운영 기본값(`config-repo/gateway.yml`)으로 옮기는 결정 기록과 설정 변경을 준비했다.
- AI 활용 방식: 대기열 담당 3차 측정(G 설정)과 강민 G1 재측정 2회를 비교해 다른 값만 골라 달라고 했다.

## AI 제안
- 두 측정은 창·실패율·느린 호출·열림·반열림·bulkhead 50·거절 무시가 같고, 필터 시한만 다르다(G 100ms, G1 2회 200ms·Lettuce 300ms).
- 추천: 200ms·300ms. "Lettuce > 필터 > 느린 호출 > 정상 응답" 순서가 성립해 시한 초과가 실패와 느린 호출로 함께 세어지지 않는다. 대가는 진짜 Redis 멈춤 때 503까지 최대 200ms, SA 9-5의 100ms에서 Gateway 블랙리스트만 예외다.
- 거절을 차단기에서 빼면 과부하가 차단기 상태로 안 보이므로, 거절 비율 경보(C2)를 같은 변경에 넣자고 추천했다.

## 판단
- 결과: 수용(세 질문 모두 추천안)
- 판단자: kangmin
- 근거: 미기재
- 검토한 대안: 필터 시한 100ms 유지(대기열 담당 G 값), 거절 경보는 바꿀 조건으로만 남기기

## 반영
- [ADR 0050](../../decisions/0050-gateway-auth-redis-breaker-bulkhead-split.md)
- `config-repo/gateway.yml`(auth-redis 차단기·bulkhead·필터 시한·Gateway Lettuce 시한), `loadtest/deploy/compose.gateway-bulkhead.yml`(기본값을 운영값과 같게, bulkhead 끄기 주석 "확인함")
- `infra/prometheus/rules/gateway-alerts.yml`, `infra/prometheus/tests/gateway-alerts.test.yml`, `script/check-prometheus-rules.sh`
- `gateway/src/test/java/com/iseonjwa/gateway/security/AuthRedisResilienceContextTest.java` 테스트 2개 추가

## 검증
- `./gradlew :gateway:test`: 변경 전 105개 실패 0 → 변경 뒤 107개 실패 0.
- 변이 테스트: `ignore-exceptions`를 지우면 운영 설정 테스트가, 필터 시한을 100ms로 바꾸면 시한 순서 테스트가 실패했다. 되돌린 뒤 통과.
- `script/check-prometheus-rules.sh`: 규칙 문법과 단위 테스트 3개 통과. 경보 기준을 0.05로 바꾸면 단위 테스트가 실패했다. 스크립트 뒷부분(건별 알림 템플릿 그리기)은 Windows에서 임시 폴더가 컨테이너에 보이지 않아 실패했다. 이번 변경과 무관하고 CI(Linux)에서 확인한다.
- 배포 서버 반영·측정은 아직 안 했다.

## 판단 이력
- 2026-11-11 수용: 운영값과 거절 경보를 한 변경으로 준비함
