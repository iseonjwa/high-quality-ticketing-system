---
title: 좌석 선점 부하 테스트에서 타임아웃이 나는 원인과 캐시 도입 지점
date: 2026-11-05
updated: 2026-11-05
asker: KWON-WOO
level: 중급
tags: [performance, k6, cache, hikaricp, gc, seathold]
---

# 좌석 선점 부하 테스트에서 타임아웃이 나는 원인과 캐시 도입 지점

## 질문
지금 좌석선점 부하테스트에서 어떤 부분에서 문제가 돼서 타임아웃이 발생하는거야? 캐시를 도입한다면 어디다가 도입하는거고?

## 짧은 답
타임아웃의 직접 원인은 선점 API 자체가 아니라 앞단의 **좌석 조회 API(`GET /api/v1/schedules/{scheduleId}/seats`)**다. 요청 한 건이 회차 15,000석 JPA 엔티티와 공연장 좌석 엔티티를 조인해 읽고 약 830KB JSON으로 직렬화하면서 DB 커넥션(풀 5개)과 힙 메모리를 동시에 잡아먹는다. 그 결과 뒤따르는 요청들이 HikariCP 커넥션 대기열(최대 197건)과 GC 정지에 묶여 Gateway 응답 제한(3초)을 넘기면서 504·503·500 타임아웃이 발생한다.

캐시는 **좌석 조회(P4)**와 **인기 좌석 선점 빠른 거절(P5)** 두 곳에 도입한다. 좌석 조회에서는 변하지 않는 배치 정보(구역·열·번호·등급·가격)를 회차별로 캐시하고 자주 바뀌는 좌석 상태만 1초 TTL로 캐시하며, 선점에서는 이미 `HELD`된 좌석 ID를 Redis 집합에 넣어 DB에 가기 전에 409로 거절한다.

## 자세히

### 1. 왜 타임아웃이 발생하는가 (K1 초당 100명 측정 기준)

사용자 시나리오(K1)는 대기열을 통과한 뒤 **좌석 조회 → 1~3초 고민 → 좌석 선점** 순서로 흐른다. 측정 결과 선점 단계까지 도달하기 전에 좌석 조회에서 서버가 먼저 무너졌다.

1. **요청당 과도한 객체 생성과 메모리 점유 (B2)**: `ScheduleSeatQueryService#getSeats`는 호출될 때마다 `ScheduleSeat` 15,000개와 `VenueSeat` 15,000개를 JPA 엔티티로 영속성 컨텍스트에 올리고, 다시 응답 DTO 15,000개와 약 830KB JSON으로 변환한다.
2. **GC 정지와 연결 시한 초과**: P0(힙 256MB, Serial GC)에서는 6분 중 101.5초, P0'(힙 768MB, Serial GC)에서는 7분 중 약 241초 동안 GC로 스레드가 멈췄다. 멈춘 동안 Eureka 하트비트가 끊겨 Gateway가 `503 Service Unavailable`을 내거나, 연결 수립 제한(`connect-timeout` 1초)에 걸려 `500`을 반환했다.
3. **DB 커넥션 풀 고갈과 대기열 적체 (B1)**: G1 GC로 바꾼 P0''에서는 긴 정지가 사라졌지만, 조회 한 건이 `@Transactional(readOnly = true)` 안에서 15,000석을 읽는 동안 커넥션을 쥔다. `maximum-pool-size`가 5개라 동시에 5건만 처리되고 나머지 Tomcat 스레드(최대 197개)가 커넥션을 기다리다 Gateway 타임아웃(3초)을 넘겨 `504 Gateway Timeout`(서버 내부 평균 지연 14.4초)이 발생했다.
4. **선점 구간에 숨어 있는 잠재 타임아웃 요인 (B3·B5)**: 조회 병목을 풀고 나면 선점(`SyncSeatHoldService#hold`)이 같은 커넥션 풀(5개)을 두고 경쟁한다. 선점 한 건이 트랜잭션 안에서 DB 왕복을 약 12~14번 수행하고, 인기 좌석 경합 시 `lock_timeout`이 없어 앞 트랜잭션이 끝날 때까지 커넥션을 쥔 채 기다린다.

### 2. 캐시를 도입한다면 어디에 어떻게 넣는가

캐시는 읽기가 몰리는 **조회 경로**와 충돌이 몰리는 **선점 앞단**에 목적을 나누어 도입한다.

| 구분 | 도입 위치 | 캐시 대상 | TTL·무효화 방식 | 해결하는 문제 |
|---|---|---|---|---|
| **P4b 정적 배치 캐시** | `ScheduleSeatQueryService#getSeats` | 회차별 15,000석의 정적 배치(좌석 ID, 구역, 열, 번호, 등급, 가격) | 공연 중 불변(긴 TTL 또는 로컬 캐시). 추첨석 전환·해제 시에만 무효화 | 매 요청마다 `venue_seats`를 조인하고 고정 필드를 다시 읽는 비용 제거 |
| **P4c 동적 상태 캐시** | `ScheduleSeatQueryService#getSeats` | 회차별 좌석 상태(`AVAILABLE` 여부) 또는 조회 응답 전체 | **1초 TTL** (짧은 만료에 의존) | 초당 100~200건의 조회를 1초당 DB 조회 1번으로 압축해 커넥션 점유와 객체 할당 제거 |
| **P5 선점 좌석 캐시** | `SyncSeatHoldService#hold` 진입부 | 회차별 현재 선점·판매된 좌석 ID 집합 (Redis Set) | 선점 성공 시 추가, 만료·취소 시 제거 (회차 종료 시 만료) | 이미 잡힌 인기 좌석 요청을 DB 커넥션과 행 잠금을 잡기 전에 409로 즉시 거절 |
| **추가 후보: 회차 메타 캐시** | `SyncSeatHoldService#hold` | `Schedule`의 `maxPerUser`, `holdMinutes`, `openAt`, `startsAt` | 수십 초 TTL 로컬 캐시 | 선점 트랜잭션마다 회차 행을 읽는 DB 왕복 1회 제거 |

#### 주의할 점과 트레이드오프
- **상태 캐시(1초 TTL)와 정합성**: 조회 캐시가 최대 1초 이전 상태를 보여 주면 이미 팔린 좌석을 빈 좌석으로 보고 선점을 시도해 409 비율이 조금 늘어날 수 있다. 그러나 실제 선점 판정은 DB의 조건부 UPDATE(`WHERE status = 'AVAILABLE'`)와 부분 유니크 인덱스가 담당하므로 중복 판매는 일어나지 않는다.
- **객체 캐시 vs 직렬화 바이트 캐시**: 15,000석 DTO 리스트를 캐시하면 DB 조회는 줄지만 요청마다 830KB JSON을 만드는 직렬화 비용은 남는다. 직렬화 결과(또는 상태 비트맵)까지 캐시하면 CPU와 GC 할당을 함께 줄일 수 있다.
- **Redis 선점 캐시 장애 정책**: P5의 Redis 집합은 빠른 거절용 보조 수단이다. Redis에 있다고 해서 무조건 허용하지 않고, Redis가 느리거나 죽으면 서킷을 열어 DB로 바로 보낸다(SA 9-5).

## 이 프로젝트에서는
- 부하 측정 결과와 단계별 계획: `docs/design/reservation/k6-seathold-bottleneck-plan.md` (2절 병목 목록, 4절 트레이드오프, 5-1~5-3절 실측 결과)
- 좌석 조회 서비스: `reservation-service/src/main/java/com/iseonjwa/reservation/concert/application/ScheduleSeatQueryService.java`
- 좌석 조회 컨트롤러: `reservation-service/src/main/java/com/iseonjwa/reservation/concert/presentation/ScheduleSeatController.java`
- 동기형 좌석 선점 서비스: `reservation-service/src/main/java/com/iseonjwa/reservation/seathold/application/SyncSeatHoldService.java`
- 커넥션 풀 설정: `config-repo/reservation-service.yml` (`maximum-pool-size: 5`)

## 면접 포인트
- **"좌석 선점 부하 테스트에서 왜 선점 API가 아니라 좌석 조회 API 때문에 타임아웃이 났나요?"** → 사용자가 선점 전에 반드시 좌석 배치를 먼저 조회하는데, 15,000석 엔티티 로딩과 830KB JSON 직렬화가 힙과 5개짜리 DB 커넥션 풀을 모두 점유해 뒤 요청들이 커넥션 대기열에서 3초 Gateway 타임아웃에 걸렸다.
- **"실시간성이 중요한 좌석 현황에 캐시를 써도 되나요?"** → 좌석 배치는 정적 정보(구역·열·가격)와 동적 정보(상태)로 나뉜다. 정적 정보는 길게 캐시하고, 상태는 1초 TTL로 캐시해도 초당 100건 조회를 1건으로 줄일 수 있다. 1초 지연으로 인한 충돌은 선점 시점의 DB 원자적 조건부 UPDATE가 막아 주므로 정합성은 깨지지 않는다.

## 참고
- `docs/design/reservation/k6-seathold-bottleneck-plan.md` 5-1~5-3절 K1 측정 결과
- [좌석 조회 경량화(P4)를 먼저 하면 단계별 개선 수치화가 불가능한가, G1 GC는 무엇인가](2026-11-05-p4-first-stepwise-measurement-and-g1.md)
- [좌석 선점 부하 계획(P1~P7) 밖에서 더 해 볼 수 있는 성능 개선은 무엇인가](2026-11-04-seathold-performance-beyond-plan.md)
