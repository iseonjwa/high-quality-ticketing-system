---
title: Gateway CPU 포화를 맨 앞 유입 제한으로 막자는 AI 추천을 거부하고, auth-redis 차단기를 단계적으로 예민하게 바꿔 포화 전에 열리는지 먼저 실험함
date: 2026-11-11
updated: 2026-11-11
judge: kangmin
ai: Claude Code (배포 서버 4차 G1 측정, ADR 0050 제안, 11/10·11/11 학습 노트의 차단기 시간 구간 분석)
verdict: 거부
area: 기타
presentation: 후보
shared: 예
---

# Gateway CPU 포화를 맨 앞 유입 제한으로 막자는 AI 추천을 거부하고, auth-redis 차단기를 단계적으로 예민하게 바꿔 포화 전에 열리는지 먼저 실험함

## 맥락
- 해결하려던 작업: 배포 서버 4차 G1(`auth-redis` 재측정 1·2회)에서 2,000/s p95 157\~208ms, 2,500/s부터 서버 CPU 99%, 성공 처리량 약 2,300\~2,470/s 정체를 봤다. 이 포화를 Gateway 쪽에서 막을 방법을 정하려 했다.
- AI 활용 방식: 장비 CPU가 한도에 닿기 전에 차단기가 열려야 하지 않느냐고 물었다([학습 노트](../../learning/kangmin/qna/2026-11-11-breaker-vs-cpu-saturation.md)). 이어서 CPU 포화를 줄일 개선안을 물었다([학습 노트](../../learning/kangmin/qna/2026-11-11-gateway-cpu-saturation-improvements.md)).

## AI 제안
- 차단기는 Redis 장애 전용으로 둔다([ADR 0050](../../decisions/0050-gateway-auth-redis-breaker-bulkhead-split.md) 제안). 차단기가 CPU 포화로 열리면 Redis가 멀쩡해도 열림 시간 동안 인증 요청 전체가 503이 되어, 넘친 만큼 거절하는 부분 저하가 전면 장애로 바뀐다는 이유다. 1차 Q1(운영 설정)에서 2,000/s에 열려 인증 전체가 503이 된 측정과, 3차 F의 열림·닫힘 9회 진동을 근거로 들었다.
- CPU 과부하는 Gateway 맨 앞의 전역 유입 제한(동시 처리 수 기반 WebFilter)으로 막는다. 한도를 넘친 요청만 일찍 싸게 거절한다.

## 판단
- 결과: 거부(먼저 차단기로 해 본다)
- 판단자: kangmin
- 근거: "auth-redis 차단기부터 좀 더 예민하게 세팅해서 CPU 99%·100%에 닿지 않게 해 보자. 단계적으로 수정하면서 포화점을 찾고, 게이트웨이로 인한 CPU 과부하를 막아 보자."
- 검토한 대안: 맨 앞 전역 유입 제한(AI 추천), 벌크헤드 50 유지와 `Retry-After`(ADR 0050), 적응형 동시성 제한

## 실험으로 가릴 것
- 원리: 차단기가 재는 시간은 Redis 처리 시간이 아니라 구독부터 응답 신호까지라, Gateway CPU 대기·Lettuce I/O 스레드 밀림·GC 멈춤이 섞인다([11/10 노트](../../learning/kangmin/qna/2026-11-10-gateway-threads-bulkhead-vs-server-spec.md) 10절). 그러면 느린 호출 기준을 낮출수록 차단기가 포화 직전에 열릴 수 있다.
- 확인할 질문
  - 느린 호출 기준을 100 → 50 → 30ms로 낮추면 차단기가 서버 CPU 몇 %에서 처음 열리는가. 90% 미만에서 여는 값이 있는가(`[가정]` 목표 CPU 90% 미만은 강민이 정함).
  - 열린 동안 성공률이 0%로 떨어진 시간과 열림·닫힘 진동 횟수는 얼마인가.
  - 같은 부하 단계에서 성공 처리량이 ADR 0050 값보다 얼마나 줄거나 느는가. 받아 준 요청의 p95가 200ms 이하로 묶이는가.
  - 웜업 직후 식은 Gateway에서 오탐으로 열리는가(1차 B0m에서 50ms 기준이 식은 Gateway에서 열렸다).
- 실패해도 그 이유를 기록한다. 결과가 어느 쪽이든 측정 → 가설 → 실패 또는 확인 → 다음 가설의 발표 서사가 된다.

## 반영
- 실험 설계를 먼저 강민에게 보이고 확인받는다. 값 변경은 `loadtest/deploy/compose.gateway-bulkhead.yml` 덮어쓰기 환경 변수로만 한다. `config-repo/gateway.yml`과 ADR 0050은 결과 전까지 바꾸지 않는다.
- 결과는 [open-peak-scenarios.md](../../design/infra/open-peak-scenarios.md) "배포 서버 5차"와 [gateway-auth-redis-resilience.md](../../design/infra/gateway-auth-redis-resilience.md)에 쓸 예정이다.

## 검증
- 배포 서버 5차 0a·0b(느린 호출 100ms)와 B20(20ms) 모두 2,500/s(서버 CPU 99%)까지 차단기가 열리지 않았다. 20ms 기준 느린 호출 비율은 최대 24%였다. 사용자 대기 대부분이 차단기가 재는 구간 밖에 있었다. 차단기 가설은 닫았고, 다음 가설은 Gateway 맨 앞 동시 수 상한이다([open-peak-scenarios.md](../../design/infra/open-peak-scenarios.md) "배포 서버 5차").

## 판단 이력
- 2026-11-11 거부: 유입 제한보다 차단기 기준 조정을 먼저 실험하기로 함
- 2026-11-11 실험 결과: 차단기 예민화로는 CPU 포화 전에 열리지 않음(B20까지). 실험 방향 B로 차단기 회차를 한 번에 닫음
- 2026-11-11 실험 설계 보강: 지표 5개와 CPU 분해 수집 추가(수용), 한 회차 한 값 순차 적용 원칙(강민 결정). [일지](daily/2026-11-11.md)

## 발표·면접 포인트
- AI는 "차단기는 의존성 장애용, 과부하는 유입 제어용"이라는 역할 구분을 추천했지만, 차단기 시간에 Gateway 대기가 섞인다는 측정 근거를 역으로 써서 차단기로 과부하를 막을 수 있는지 직접 실험했다. 결과로 어느 쪽 주장이 맞는지 숫자로 가린다.
