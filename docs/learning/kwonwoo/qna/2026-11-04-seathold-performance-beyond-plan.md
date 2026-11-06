---
title: 좌석 선점 부하 계획(P1~P7) 밖에서 더 해 볼 수 있는 성능 개선은 무엇인가
date: 2026-11-04
updated: 2026-11-04
asker: KWON-WOO
level: 고급
tags: [performance, k6, postgresql, hikaricp, cache, http]
---

# 좌석 선점 부하 계획(P1~P7) 밖에서 더 해 볼 수 있는 성능 개선은 무엇인가

## 질문
지금 부하테스트 시나리오(`docs/design/reservation/k6-seathold-bottleneck-plan.md`)에 적힌 것보다 더 성능을 개선할 수 있는 방법이 뭐가 있을까?

## 짧은 답
계획은 "선점 트랜잭션 줄이기"와 "조회 캐시"에 집중돼 있다. 그 밖에 효과가 큰 쪽은 다섯 갈래다.
1. 좌석 조회 응답 자체를 작게 만든다(상태 비트맵, 직렬화 결과 캐시, gzip, ETag).
2. 409 응답이 재조회를 부르지 않게 한다(409 본문에 잡힌 좌석 ID를 넣는다).
3. 조회와 선점이 커넥션을 나눠 쓰게 하고, 넘치는 요청은 빨리 거절한다(벌크헤드, 부하 차단).
4. PostgreSQL 쪽 쓰기 비용을 줄인다(fillfactor로 HOT 업데이트, 커밋 비용, 드라이버 설정).
5. 측정 자체를 믿을 수 있게 한다(워밍업, 서버 시간 분해).

## 자세히

### A. 좌석 조회(B2)를 근본적으로 가볍게
계획 P4는 "배치/상태 분리 + 1초 TTL 캐시"까지다. 한 단계 더 갈 수 있다.

| 방법 | 내용 | 왜 효과가 있나 | 비용 |
|---|---|---|---|
| A1 상태 비트맵 | 응답을 "배치(정적)"와 "상태(동적)"로 나누고, 상태는 좌석 순번별 1비트(또는 2비트) 배열로 준다. 15,000석이면 약 1.9KB(2비트면 3.75KB) | 지금은 요청마다 15,000개 객체를 JSON으로 만든다. 상태만 비트로 보내면 응답 바이트와 CPU·GC가 수십 분의 1로 준다 | 응답 계약 변경(프론트), contract 이슈 필요 |
| A2 직렬화 결과 캐시 | 1초 TTL 캐시에 객체가 아니라 직렬화된 `byte[]`(gzip까지 마친 것)를 둔다 | 초당 100~200건이 같은 1초 안에서 같은 결과를 받는다. 캐시를 객체로 두면 직렬화 비용은 그대로 남는다 | 캐시 무효화는 TTL에만 맡긴다 |
| A3 단일 비행(single-flight) | 캐시가 비는 순간 여러 요청이 동시에 DB를 치지 않게 한 요청만 갱신하고 나머지는 그 결과를 기다린다(Caffeine `AsyncLoadingCache`, `refreshAfterWrite`) | TTL 만료 순간마다 생기는 캐시 쇄도(stampede)를 막는다 | 거의 없음 |
| A4 HTTP 압축·ETag | `server.compression.enabled`로 gzip, 상태 버전(마지막 변경 시각)으로 ETag를 걸어 변하지 않았으면 304 | 배치 부분은 거의 항상 304가 된다. 현재 설정에는 압축이 없다 | Gateway가 압축·헤더를 그대로 통과시키는지 확인 |
| A5 회차 존재 확인 왕복 제거 | `ScheduleSeatQueryService#getSeats`가 `findById`로 회차를 먼저 읽고 좌석을 다시 읽는다. 좌석 결과가 비면 404로 판단하면 왕복 1번이 준다 | 캐시가 있으면 효과는 작지만, 캐시 미스 경로가 짧아진다 | 회차는 있는데 좌석이 0인 경우를 구분해야 한다 |

### B. 409 재시도 증폭(B6) 끊기
계획은 409 뒤 "좌석 재조회 → 재선점"을 그대로 두고 조회를 싸게 만드는 방향이다. 재조회 자체를 없앨 수 있다.
- B1: 409 본문에 "이미 잡힌 좌석 ID 목록"을 담는다. `holdAvailableSeats`가 갱신하지 못한 좌석을 알 수 있으면(`UPDATE ... RETURNING id`로 성공한 ID를 받으면 나머지가 실패분이다) 추가 조회 없이 만든다. 프론트는 그 좌석만 회색으로 바꾸고 바로 다른 좌석을 고른다.
- B2: 1석 실패로 전체를 거절하는 현재 규칙은 유지하되, "부분 성공 제안"(남은 좌석으로 다시 시도할지)을 프론트에 맡긴다.
- 효과: k가 1이면 사용자당 요청이 4건에서 3건으로 준다. 매진 직전 409가 몰리는 구간에서 가장 크다.

### C. 커넥션을 지키는 장치
계획 P1은 풀 크기만 키운다. 크기보다 "누가 커넥션을 쓰느냐"를 나누는 것이 더 중요할 수 있다.
- C1 벌크헤드: 좌석 조회와 선점이 같은 풀(5개)을 쓴다. 조회가 몰리면 선점이 커넥션을 못 받는다. 조회용 읽기 전용 풀을 따로 두거나(`AbstractRoutingDataSource` + `@Transactional(readOnly = true)`), 최소한 조회에 동시 실행 상한(세마포어, Resilience4j Bulkhead)을 건다. 돈이 걸린 선점을 조회가 굶기지 않게 하는 것이 목적이다.
- C2 부하 차단(load shedding): Tomcat 스레드 200개가 커넥션 5개를 기다리며 줄 서는 구조는 대기가 3초 Gateway 타임아웃까지 쌓인다. 선점 API에 동시 처리 상한(예: 풀 크기 × 2)을 두고 넘치면 바로 503(`Retry-After`)을 준다. "느린 성공"보다 "빠른 거절"이 전체 p99와 성공 처리량을 지킨다. P1의 `connection-timeout` 축소와 같은 방향이지만, 커넥션을 기다리기 전에 거절하므로 스레드도 덜 쓴다.
- C3 가상 스레드는 이 병목에 도움이 안 된다. 병목이 스레드 수가 아니라 커넥션 수이기 때문이다. 오히려 더 많은 요청이 커넥션 대기열에 들어간다. 해 본다면 비교 실험으로만 한다.

### D. PostgreSQL 쓰기 비용
- D1 fillfactor와 HOT 업데이트: `schedule_seats`는 상태 열을 계속 UPDATE한다. 바뀌는 열이 인덱스에 없고 같은 페이지에 빈 공간이 있으면 PostgreSQL은 인덱스를 건드리지 않는 HOT 업데이트를 한다. `ALTER TABLE schedule_seats SET (fillfactor = 80~90)`으로 페이지에 여유를 둔다. 상태 열에 인덱스가 걸려 있다면 HOT가 안 되므로 그 인덱스가 정말 필요한지 본다. 확인은 `pg_stat_user_tables.n_tup_hot_upd / n_tup_upd`.
- D2 커밋 비용: 선점 1건이 커밋 1번(fsync 1번)이다. 초당 수백 건이면 WAL fsync가 지연의 큰 부분일 수 있다. `commit_delay`·`commit_siblings`로 그룹 커밋을 유도할 수 있다. `synchronous_commit = off`는 장애 시 막 커밋한 선점이 사라질 수 있어 좌석 판매에는 쓰지 않는다(학습용으로 차이만 재 보는 것은 의미가 있다).
- D3 드라이버·Hibernate 설정: P3에서 시퀀스로 바꾸면 `hibernate.jdbc.batch_size`, `order_inserts`, PostgreSQL 드라이버의 `reWriteBatchedInserts=true`를 같이 켜야 실제로 한 문장으로 묶인다. 시퀀스는 `allocationSize`(pooled optimizer)를 50 정도로 둬야 ID마다 `nextval` 왕복이 생기지 않는다.
- D4 autovacuum: 같은 행을 계속 바꾸는 표는 죽은 튜플이 빨리 쌓인다. 5분 측정 중에 autovacuum이 끼어들어 지연이 튀는지 `pg_stat_progress_vacuum`으로 본다.

### E. 선점 트랜잭션에서 한 번 더 깎을 곳
계획 P2·P3에 더해:
- E1 멱등키 선조회 없애기: 지금은 `seatHoldRequests.findById`로 먼저 본다. 첫 요청이 대부분이라면 바로 진행하고, 마지막 `seat_hold_requests` INSERT의 PK 충돌에서만 재요청으로 처리하면 정상 경로 왕복이 1번 준다. 단, 그러면 재요청이 좌석 UPDATE까지 갔다가 롤백되므로 재요청 비율이 높을 때는 손해다. TS-3(같은 키 동시 요청) 처리와 같이 설계해야 한다.
- E2 회차 정보 캐시: `inventory.findSchedule`이 읽는 `maxPerUser`, `holdMinutes`, `openAt`, `startsAt`은 판매 중에 거의 안 바뀐다. 로컬 캐시(수십 초 TTL)로 트랜잭션 밖으로 뺄 수 있다. P2의 "트랜잭션 앞으로"보다 한 단계 더 나가 DB 왕복 자체를 없앤다. 판매 종료 판정이 캐시 TTL만큼 늦을 수 있다는 점을 기록해야 한다.

### F. 측정 신뢰도
개선 효과가 측정 흔들림보다 작으면 판단할 수 없다. JUnit 근사에서 처리량이 2배 넘게 흔들렸다.
- F1 워밍업: JIT 컴파일 전 첫 30초가 섞이면 p99가 부풀려진다. 본 측정 전에 낮은 도착률로 1분 워밍업 시나리오를 두고, 결과에서 뺀다(k6 `startTime`으로 시나리오 2개).
- F2 서버 시간 분해: `http_server_requests_seconds`, Hikari `acquire`·`usage` 시간, Zipkin 구간을 같이 보면 "커넥션 대기 / DB 실행 / 직렬화 / Gateway" 중 어디서 시간이 가는지 나눌 수 있다. 개선 단계를 고르는 근거가 된다.
- F3 같은 데이터 상태에서 시작: 매 실행 전 좌석·예매를 초기화하는 스크립트를 고정한다. 앞 실행의 죽은 튜플이 다음 실행을 느리게 할 수 있으므로 `VACUUM ANALYZE`까지 포함한다.

## 우선순위 제안
비용 대비 효과로 보면 다음 순서가 합리적이다(가설, 측정으로 고친다).
1. F1·F2(측정 정비): 코드 변경 없음, 이후 모든 판단의 근거.
2. A2·A3·A4(조회 바이트 캐시·압축): 서비스 내부 변경만, 매진 뒤 구간 부하 대부분이 여기다.
3. C1·C2(벌크헤드·빠른 거절): 설정과 필터 수준, p99를 지킨다.
4. D1·D3(fillfactor, 배치 설정): DB 설정 한두 줄.
5. B1·A1(409 본문, 비트맵 응답): 프론트 계약 변경이라 contract 이슈를 먼저 올린다.
6. E1·E2: 정합성 규칙(TS-3, 판매 기간)과 얽혀 있어 마지막에 신중하게.

## 이 프로젝트에서는
- 계획 문서: `docs/design/reservation/k6-seathold-bottleneck-plan.md`(P1~P7)
- 좌석 조회: `reservation-service/.../concert/application/ScheduleSeatQueryService.java`
- 선점: `reservation-service/.../seathold/application/SyncSeatHoldService.java`
- 풀 설정: `config-repo/reservation-service.yml`(`maximum-pool-size: 5`), OSIV는 `config-repo/application.yml`에서 이미 꺼져 있다(`open-in-view: false`). 압축 설정은 없다.

## 면접 포인트
- "캐시를 붙였는데 CPU가 그대로다" → 객체를 캐시하면 직렬화 비용이 남는다. 직렬화된 바이트를 캐시해야 한다.
- "풀을 키우면 빨라지나" → 어느 지점까지만. DB 코어 수 근처를 넘으면 문맥 전환·잠금 경합으로 느려진다. 그래서 크기보다 벌크헤드와 빠른 거절이 중요하다.
- "가상 스레드로 처리량이 오르나" → 병목이 스레드가 아니라 DB 커넥션이면 오르지 않는다.
- "HOT 업데이트란" → 인덱스 열이 안 바뀌고 같은 페이지에 공간이 있으면 인덱스 갱신 없이 새 튜플 버전을 같은 페이지에 쓰는 PostgreSQL 최적화.

## 참고
- PostgreSQL 문서: Heap-Only Tuples(HOT), `fillfactor`, `commit_delay`
- HikariCP wiki: About Pool Sizing
- k6 문서: constant-arrival-rate, scenarios `startTime`
