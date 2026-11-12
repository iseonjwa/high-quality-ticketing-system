---
title: 좌석 배치는 로컬 캐시가 Redis보다 낫다는 AI 설명을 그대로 받지 않고, Redis로도 구현해 측정으로 비교하게 함
date: 2026-11-12
updated: 2026-11-12
judge: 최권우
ai: Claude Code
verdict: 보류
area: 동시성
presentation: 후보
shared: 예
---

# 좌석 배치는 로컬 캐시가 Redis보다 낫다는 AI 설명을 그대로 받지 않고, Redis로도 구현해 측정으로 비교하게 함

## 맥락
- 해결하려던 작업: ISE-79 측정 브랜치의 P4b(좌석 배치 캐시)가 로컬 캐시인지 Redis 캐시인지 확인하던 중이었다. 본 구현(ISE-204, 반효승)은 캐시 위치를 "서비스 메모리 또는 Redis"로 열어 두었다.
- AI 활용 방식: P4b가 어느 캐시인지 묻고, 이어서 같은 내용을 Redis로도 구현해 성능을 비교하고 우세한 지표와 원인을 정리하게 했다.

## AI 제안
P4b·P4c는 JVM 안의 로컬 캐시(`SharedLoadCache`)라고 답했다. 배치는 크고(15,000석) 판매 중에 바뀌지 않으며 1초 낡은 상태를 허용하므로, Redis보다 로컬이 싸다고 설명했다. Redis에 두면 요청마다 수백 KB를 옮기고 Redis 장애에 함께 묶인다는 이유였다. 측정 근거는 없었다.

## 판단
- 결과: 보류(설명을 바로 받지 않고 측정으로 검증하게 함)
- 판단자: 최권우
- 근거: 미기재
- 검토한 대안: AI 설명을 그대로 받아 ISE-204에 넘기는 것.

## 반영
- 측정 브랜치 `6f89bbbb`: `SeatLayoutCache`(설정 `seatmap.layout-cache`로 로컬·Redis 선택), `compose.layout-redis.yml`, Redis 모드 좌석 조회 시험, STEPS.md 예상.
- 측정 브랜치 `b95450d9`: STEPS.md 실측 6회.
- feat 문서: [k6-p4b-local-vs-redis.md](../../design/reservation/k6-p4b-local-vs-redis.md), [k6-seathold-story.md](../../design/reservation/k6-seathold-story.md) 4절, 질문 노트 [2026-11-12-p4b-p4c-local-cache-not-redis.md](../../learning/kwonwoo/qna/2026-11-12-p4b-p4c-local-cache-not-redis.md).

## 검증
- 시험: 좌석 조회 시험 7개를 Redis 모드로 한 번 더 돌려 통과(응답 JSON 동일).
- 측정(local, 각 1회, 대사 위반 0): 세 조건 모두 로컬이 앞섰다.
  - Gateway 경유 초당 100명: 좌석 조회 p95 7.0 대 38.3ms, 한 건당 CPU 약 5.5 대 25ms.
  - 직접 호출 스트레스: 99% 이상 버틴 단계 640 대 160명.
  - CPU 4코어: 버틴 단계 160 대 40명.
- 설명 중 맞은 부분: 큰 값을 요청마다 옮기는 비용. 빠진 부분: 역직렬화가 키운 할당·GC(L 할당 7배, GC 약 17배), 공유 Lettuce 연결과 시한 100ms가 지연을 500으로 바꾼 점, 공유 Redis 128MB·`noeviction`에서 쓰기 거절(34건). 측정 전 예상(CPU 1.5~2배)도 실측(약 4.5배)보다 작았다.

## 판단 이력
- 2026-11-12 보류: AI 설명 대신 Redis 구현 비교 측정을 하게 함. 측정 결과는 로컬 우세다. ISE-204에 근거로 넘길지는 미정이다.

## 발표·면접 포인트
- "로컬이 Redis보다 낫다"는 AI 설명을 그대로 쓰지 않고, 같은 커밋에서 설정 하나만 바꿔 세 조건으로 쟀다. 결과는 설명과 같은 방향이었지만, 원인은 설명에 없던 GC와 연결 시한까지 드러났다.
- Redis가 앞선 것처럼 보인 지표(선점 지연, 커넥션 대기)는 앞 단계가 먼저 실패해 부하가 깎인 효과였다. 지표를 볼 때 같은 양의 일을 처리했는지부터 확인했다.
