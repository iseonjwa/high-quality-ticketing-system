---
title: 긴 측정 뒤 배포 실패를 막으려고 Zipkin 힙을 늘리자는 AI 추천을 거부하고, 메모리 설정은 두고 스케줄러 Redis 스팬을 근원에서 줄이며 이미지 받기 재시도를 더함
date: 2026-11-11
updated: 2026-11-11
judge: kangmin
ai: Claude Code (메인 세션 추천, 작업 세션 조사·반영)
verdict: 거부
area: 기타
presentation: 후보
shared: 예
---

# 긴 측정 뒤 배포 실패를 막으려고 Zipkin 힙을 늘리자는 AI 추천을 거부하고, 메모리 설정은 두고 스케줄러 Redis 스팬을 근원에서 줄이며 이미지 받기 재시도를 더함

## 맥락
- 해결하려던 작업: 긴 측정 뒤 main 배포가 실패하는 일을 막는다. 11/10에는 Zipkin 힙이 차서 `compose up --wait`가 실패했고(TS-54, `MEM_MAX_SPANS` 10만으로 줄임), 11/11에는 `docker compose pull`이 38초 만에 `context canceled`로 취소돼 직전 커밋으로 되돌려졌다.
- AI 활용 방식: 메인 세션에 두 실패의 재발 방지 선택지를 물었다. 11/11에 다시 재 보니 10만 건이 이미 힙 오래 묵은 영역의 90%(242/268MB)였다. 평시 샘플링 1.0에서 시간당 약 20만 스팬을 받는다.

## AI 제안
- A: `deploy.sh`의 이미지 받기를 실패 때 10초 간격으로 최대 3회 다시 시도한다.
- E′(추천): 저장 한도 10만 건은 두고 Zipkin 힙(`-Xmx384m`)과 컨테이너 메모리(`768m`)를 늘린다.
- D: 스팬 양을 줄인다.
- 메인 세션은 강민의 "A + D"를 처음에 "저장 한도를 5만 건으로 줄인다"로 잘못 이해해 작업 세션에 넘겼다. 작업 세션은 이 기록 초안만 썼고 설정은 바꾸지 않았다.

## 판단
- 결과: 거부(E′ 추천 대신 D). A는 수용했다.
- 판단자: kangmin
- 근거: "메모리 내비두고 스팬을 줄인다고 했잖아. 스팬이 그 스케줄러 안의 Redis 호출 스팬만, 이것만 처리해도 될 것 같은데."
- 축소 범위: AI가 낸 두 범위(가. 스케줄러 실행 스팬은 두고 그 안의 Redis 호출 스팬만 뺌, 약 87% / 다. 실행 스팬까지 뺌, 약 98%) 중 AI 추천인 가를 골랐다(말씀: "가. evalsha만 빼기").
- 검토한 대안: E′(힙·컨테이너 메모리 증설), 저장 한도 5만 건(메인 세션의 오해, 취소), Lettuce 명령 추적 전체 끄기(요청 경로 Redis 추적도 사라짐).

## 반영
- 스팬 근원 축소: reservation-service `global/observability`의 관측 필터와 내보내기 조건. 스케줄러 바로 아래 Redis 명령 스팬에 표시를 붙이고 Zipkin으로 보내지 않는다. 관측은 그대로라 `lettuce_*` 지표는 남는다. 설정 `observability.scheduler-redis-spans.export`(기본 false, `config-repo/reservation-service.yml`)로 다시 켤 수 있다.
- `script/deploy/deploy.sh` 이미지 받기 재시도(10초 간격, 최대 3회). `docs/troubleshooting.md` TS-55, TS-54 후속.
- Zipkin 메모리 설정(`-Xmx384m`, `768m`, `MEM_MAX_SPANS: 100000`)은 바꾸지 않는다.

## 검증
- 11/11 10:29~10:39Z 배포 서버 Zipkin 10분치(추적 3,237건, 스팬 25,887건)를 뽑았다. `queueadmissionscheduler.admit` 추적이 1,190건(0.5초마다)이고 추적마다 스팬 20개(`evalsha` 19개)로, 전체 스팬의 91.9%였다. 예약 작업 전체를 합치면 약 97.6%다.
- 끄는 범위별로 잃는 것은 [학습 노트](../../learning/kangmin/qna/2026-11-11-scheduler-span-off-what-we-lose.md)에 있다. 스케줄러 실행 스팬은 남기고 그 안의 Redis 호출 스팬만 빼면 실행 시간·로그 추적 ID·Kafka로 이어진 흐름과 `lettuce_*` 지표가 남는다.
- lettuce-core 7.5.2 소스(`CommandHandler.attachTracing`, `MicrometerTracing.nextSpan`)에서 부모 관측이 없는 Redis 명령은 새 추적(루트 스팬)으로 만들어진다. 예약 작업 관측만 끄면 `evalsha`가 추적 19개로 흩어질 뿐 스팬 수는 거의 줄지 않는다.

- `SchedulerRedisSpanConfigurationTest` 4건: 실제 Brave 추적 처리기와 지표 처리기를 붙여 예약 작업 아래 Redis 스팬만 빠지고, 요청 경로 Redis 스팬과 작업 스팬은 보고되며, `lettuce` 지표는 남는지 확인했다. 표시 달기를 뺀 변형과 부모 검사를 뺀 변형은 각각 1건이 실패했다. reservation-service 전체 테스트 392건 통과(건너뜀 11).
- `deploy.sh` 재시도는 pull을 가짜 명령으로 바꾼 시험 3건으로 확인했고, 재시도를 뺀 변형은 실패했다.
- 배포 뒤 Zipkin 스팬 유입량과 힙은 아직 확인 전이다.

## 판단 이력
- 2026-11-11 거부: E′ 대신 D, A는 수용. 메인 세션이 D를 저장 한도 축소로 오해했다가 정정했다.
- 2026-11-11 선택: 축소 범위는 가(evalsha 등 스케줄러 안의 Redis 호출 스팬만)

## 발표·면접 포인트
- 메모리에만 쌓는 관측 저장소가 차는 문제를 자원을 늘려 풀지 않고, 스팬의 92%를 만드는 근원(0.5초마다 도는 대기열 입장 스케줄러의 Redis 명령)을 숫자로 찾아 줄인 사례다.
- AI가 사용자 결정을 잘못 전달한 것을 사용자가 바로잡았다. 지시를 옮길 때 원문을 함께 넘겨야 하는 이유다.
