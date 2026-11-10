---
title: auth-redis 재측정 값을 느린 호출 100ms·80%, 시한 200ms, 벌크헤드 50(거절은 차단기 집계에서 제외), 실패율 50%로 하자는 AI 추천을 받아들임
date: 2026-11-10
updated: 2026-11-10
judge: kangmin
ai: Claude Code (코드·라이브러리 소스·11/10 측정 기록 분석)
verdict: 수용
area: 기타
presentation: 후보
shared: 예
---

# auth-redis 재측정 값을 느린 호출 100ms·80%, 시한 200ms, 벌크헤드 50(거절은 차단기 집계에서 제외), 실패율 50%로 하자는 AI 추천을 받아들임

## 맥락
- 해결하려던 작업: 11/10 L1 측정에서 Gateway `auth-redis` 차단기가 Redis는 멀쩡한데(Redis 컨테이너 CPU 8%) 열리고, 설정을 완화하면 서버 CPU 100%에서 지연이 폭증했다. 이 상태에서 다음 재측정 값을 정하려 했다.
- AI 활용 방식: Gateway 스레드·벌크헤드·연결 풀이 서버 사양을 고려한 값인지부터 물었다. 이어 과수용, Tomcat 스레드, 차단기 시간 측정 주체, 재시도 유무를 차례로 묻고, 마지막에 느린 호출 100ms를 고정한 채 나머지 값을 추천받았다.

## AI 제안
- 분석: 차단기의 시한·느린 호출 판정은 모두 Gateway 안의 타이머가 잰다. 그 시간에 Lettuce I/O 스레드 대기(연결 1개 공유), GC 멈춤, CPU 차례 대기가 섞인다. 그래서 차단기가 Gateway 혼잡을 Redis 장애로 오해할 수 있다. 벌크헤드가 차단기 안쪽에 있어 벌크헤드 거절도 차단기 실패로 세어진다(학습 노트 10·12·13절).
- 강민이 먼저 낸 "시한을 낮추고 느린 호출을 넓히자"에 대해서는, 시한을 낮추면 Gateway 혼잡이 곧 실패가 되어 반대로 작동한다고 답했다(12절).
- 추천 값(14절): 느린 호출 100ms·80%(강민 고정), 필터 시한 200ms, Lettuce 명령 시한 300ms, 벌크헤드 50, `ignoreExceptions: BulkheadFullException`, 실패율 50%, 창은 E안(시간 10초, 최소 500건, 열림 5초, 반열림 20건) 유지.
- 측정은 2회로 나눈다. 1회는 E안에서 시한만 올린다. 2회는 1회에 벌크헤드 50과 거절 제외를 더한다.

## 판단
- 결과: 수용
- 판단자: kangmin
- 근거: "추천대로 해보자". 앞선 대화에서 강민은 벌크헤드를 높이는 것이 과수용으로 더 문제라고 봤다("벌크헤드를 높히는게 더 문제인것같다").
- 검토한 대안: 시한을 낮추고 느린 호출을 넓히는 안(강민 제안, 거둠), 시한 200ms·느린 호출 150ms·80%(먼저 골랐다가 느린 호출 100ms로 고정하며 바꿈), 벌크헤드 25(거절이 차단기 실패로 세어지는 구조에서는 실패율을 올린다는 AI 답을 듣고 거절 제외와 짝지은 50으로 감)

## 반영
- 미반영. 필터 시한(`GatewayAuthenticationFilter.java:29`의 상수)을 설정값으로 바꾸고, 측정 덮어쓰기(`loadtest/deploy/compose.gateway-bulkhead.yml`)에 시한·Lettuce 시한·`ignoreExceptions`를 넣는 변경이 ISE-230 범위에서 먼저 필요하다.
- 운영 설정(`config-repo/gateway.yml`)은 바꾸지 않는다. 결과를 운영에 넣으려면 SA 9-4의 Redis 시한 100ms `[가정]`을 바꾸는 결정 기록(`docs/decisions/`)이 필요하다.

## 검증
- 측정 전.
- 라이브러리 소스로 확인한 것: 무시 예외는 성공·실패 어느 쪽으로도 세지 않고 허가만 돌려준다(`CircuitBreakerStateMachine.handleThrowable`, resilience4j-circuitbreaker 2.3.0). 설정 키 `ignoreExceptions`가 있다(`CommonCircuitBreakerConfigurationProperties.java:171-172,351`, framework-common 2.3.0). 지표에는 `kind="ignored"`로 따로 나온다(`AbstractCircuitBreakerMetrics.java:33`, micrometer 2.3.0).
- 이 Gateway 경로에서 `ignoreExceptions`가 실제로 적용되는지는 테스트로 확인해야 한다.

## 판단 이력
- 2026-11-10 수용: 추천 값과 2회 나눠 재는 순서를 받아들임

## 발표·면접 포인트
- 클라이언트 쪽 시한·느린 호출 판정은 클라이언트 자신의 혼잡을 함께 잰다. 그래서 "의존성 장애 감지"와 "자기 과부하 차단"을 같은 숫자에 맡기지 않고, 벌크헤드(과부하 차단)와 차단기(의존성 장애)의 집계를 분리했다.
- 시한을 낮춰 예민하게 하자는 첫 직관이 측정 구조상 반대로 작동한다는 것을 소스로 확인하고 방향을 바꿨다.
