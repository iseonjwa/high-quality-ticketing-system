---
title: 걸릴 일이 없는 Gateway Lettuce 시한 300ms를 "마지막 안전망"으로 유지하자는 AI 결론을 거부함
date: 2026-11-12
updated: 2026-11-12
judge: kangmin
ai: Claude Code
verdict: 거부
area: 기타
presentation: 후보
shared: 예
---

# 걸릴 일이 없는 Gateway Lettuce 시한 300ms를 "마지막 안전망"으로 유지하자는 AI 결론을 거부함

## 맥락
- 해결하려던 작업: Gateway 인증 경로의 Redis 시한 계단(느린 호출 판정 100ms, 필터 시한 200ms, Lettuce 명령 시한 300ms, TimeLimiter 1초)이 어떤 순서로 걸려야 정상인지 이해하는 학습.
- AI 활용 방식: 필터와 Lettuce 중 어느 쪽이 바깥인지, 정상적인 시한 순서가 무엇인지 다시 확인해 달라고 물었다.

## AI 제안
- 필터 `Mono.timeout(200ms)`이 Lettuce 호출을 감싸므로 필터가 바깥, Lettuce가 안쪽이다.
- 두 시계는 사실상 같은 구간을 잰다(Lettuce 7.5.2는 `write()` 때 타이머를 건다). 그래서 둘 중 하나는 반드시 걸리지 않는다.
- 그런데도 결론은 "지금 구조(Lettuce 300 > 필터 200)를 유지할 만하다"였다. 근거로 Lettuce 300ms가 Gateway 전체 Redis 명령의 마지막 안전망(기동 웜업 `PING`, 앞으로 추가될 사용처)이라고 들었다.
- 원문: [학습 노트](../../learning/kangmin/qna/2026-11-12-filter-vs-lettuce-timeout-order.md)

## 판단
- 결과: 거부
- 판단자: kangmin
- 근거: Lettuce 300ms는 사실상 형식적이고 걸릴 일이 없는 시한인데, 왜 그 구조가 유지할 만하다고 하는지 이해할 수 없다.
- 검토한 대안: 미기재(대안 선택은 아직 하지 않음)

## 반영
- 학습 노트 `2026-11-12-filter-vs-lettuce-timeout-order.md`를 다시 썼다. Lettuce 300ms는 인증 경로에서 기능이 없고, 끌 수 없어서(Spring Boot 4.1.2가 항상 켬, 비우면 60초) 필터를 방해하지 않는 자리에 둔 값이라고 정정했다.
- 지금 배치가 맞는 이유를 "안전망"에서 "더 정확하고 원인 이름을 남기는 시계(필터)를 먼저 울리게 함"으로 바꿨다. 근거는 Lettuce 타이머가 Netty 100ms 눈금이라 최대 약 100ms 늦게 울린다는 바이트코드 확인이다.
- 같은 날 노트 `2026-11-12-timeout-layers-and-config-repo-map.md`와 개인 문서의 "안전망·경보" 설명에 정정 문단을 달았다.
- 설정·코드·ADR 0050은 바꾸지 않았다.

## 검증
구현 전. 바이트코드 확인(Lettuce 7.5.2 `CommandExpiryWriter.write`, Spring Boot 4.1.2 `TimeoutOptions.enabled()`)만 했다.

## 판단 이력
- 2026-11-12 거부: "안전망이라 유지"라는 결론을 거부함. 대안(시계 하나로 합치기 등)은 미정
- 2026-11-12 거부: "넌 틀렸어 고쳐", "그 판단이 틀렸다면 정정해서 부연설명해 둬"라고 지시함. AI가 노트와 문서의 "안전망·경보" 설명을 정정함

## 발표·면접 포인트
- 시한을 여러 겹 두었더라도 같은 구간을 재면 하나는 죽은 값이 된다. "안전망"이라는 말로 죽은 값을 정당화하지 않고, 실제로 걸리는지 확인해 남길지 정했다.
