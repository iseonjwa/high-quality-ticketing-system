---
title: "Gateway에는 벌크헤드가 없다"는 AI 조사 결과를 팀원 지적으로 다시 확인해 틀렸다고 판단함(라이브러리 기본 벌크헤드 동시 25가 걸려 있음)
date: 2026-11-09
updated: 2026-11-09
judge: kangmin
ai: Claude Code (저장소 코드·설정 검색, 이후 라이브러리 바이트코드 확인)
verdict: 거부
area: 기타
presentation: 후보
shared: 예
---

# "Gateway에는 벌크헤드가 없다"는 AI 조사 결과를 팀원 지적으로 다시 확인해 틀렸다고 판단함

## 맥락
- 해결하려던 작업: 11/9 밤 부하 측정 중 영은님이 "gateway 벌크헤드를 바꾸거나 끄고 재도 되나"를 물었고, 강민이 "상관없어요"라고 답했다. 이 답이 맞는지 검토하려고 AI에게 gateway 벌크헤드 구현을 조사하게 했다.
- AI 활용 방식: 코드 위치, 설정값, 한도에 걸릴 때의 응답, 측정 조건이 섞이는지 정리를 요청했다.

## AI 제안
- 저장소 코드와 `config-repo`를 검색해 "gateway에는 벌크헤드가 없다. 벌크헤드는 payment-service의 `pg-approve`(10)·`pg-recovery`(2)에만 있다"고 보고했다.
- 그래서 영은님이 말한 것은 결제 벌크헤드이거나 gateway의 `auth-redis` 서킷 브레이커일 것이라고 추정했다.

## 판단
- 결과: 거부(AI 조사 결과가 틀림)
- 판단자: kangmin(영은님 지적을 전달)
- 근거: 영은님이 "없으면 기본값 아니냐"고 되물었다. 다시 확인하니 gateway가 쓰는 `spring-cloud-circuitbreaker-resilience4j` 5.0.3은 `spring.cloud.circuitbreaker.bulkhead.resilience4j.enabled`(기본 true)일 때 모든 리액티브 서킷 브레이커 실행에 Resilience4j 벌크헤드를 씌운다. `auth-redis`에는 기본값 동시 25, 대기 0이 걸린다. 넘치면 503 "일시적으로 인증을 확인할 수 없습니다"가 된다.
- 다만 "Grafana에 bulkhead 지표가 있다는 것이 증거"라는 근거는 맞지 않았다. 팀 대시보드 패널은 `application="payment-service"`만 보여 준다.
- 검토한 대안: AI 첫 결론 유지(설정에 없으니 없음). 자동 설정의 `matchIfMissing` 기본값을 보지 않은 결론이라 버렸다.

## 반영
- 학습 노트: [Gateway에 벌크헤드 설정이 없으면 기본값이 걸리나](../../learning/kangmin/qna/2026-11-09-gateway-default-bulkhead.md)
- 영은님 측정 조건(끄기·값 바꾸기)은 아직 미정이다.

## 검증
- 라이브러리 바이트코드(`javap`)로 자동 설정 조건, `run()`의 벌크헤드 적용 순서, `BulkheadConfig` 기본값(25, 0초)을 확인했다.
- 배포 서버에서 gateway가 `resilience4j_bulkhead_*{application="gateway"}` 지표를 내는지, 실제 503이 이 벌크헤드에서 나는지는 확인하지 않았다.

## 판단 이력
- 2026-11-09 거부: 팀원 지적으로 AI의 "gateway 벌크헤드 없음" 결론을 다시 확인해 틀렸다고 판단함
- 2026-11-09 수용: 바로잡은 결과(서킷 브레이커 스타터가 벌크헤드를 함께 가져온다)를 강민이 받아들여 팀 채팅에 공유함
