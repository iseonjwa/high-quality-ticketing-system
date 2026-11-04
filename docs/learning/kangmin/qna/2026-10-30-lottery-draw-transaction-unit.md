---
title: 추첨을 한 트랜잭션으로 실행하면 무엇이 보장되고 무엇이 위험하며, 청크로 나누면 무엇이 달라지는가
date: 2026-10-30
updated: 2026-10-30
asker: GGangMY
level: 중급
tags: [lottery, transaction, postgresql, row-lock, long-transaction, scheduler, chunk, timeout]
---

# 추첨을 한 트랜잭션으로 실행하면 무엇이 보장되고 무엇이 위험하며, 청크로 나누면 무엇이 달라지는가

## 질문
추첨 실행 단위(D8)에서 "한 트랜잭션(A)"과 "청크 트랜잭션 + 중간 상태(B)"의 차이를 더 자세히 설명해 달라. 이어서, 청크와 중간 상태는 무슨 뜻이고 긴 트랜잭션의 비용은 어떻게 미리 막는가.

## 짧은 답
A는 추첨 행의 조건부 UPDATE와 응모 3만 행 갱신을 한 트랜잭션에 넣는다. 두 서버가 동시에 실행해도 추첨 행 잠금 때문에 하나만 반영되고, 도중에 죽으면 통째로 롤백되어 다음 주기에 다시 하면 된다.
위험은 트랜잭션이 길다는 것이다. 그동안 DB 연결 하나를 붙잡고, 3만 행에 잠금을 걸고, PostgreSQL이 다른 테이블의 죽은 행을 정리하지 못하게 막는다. 다른 회차 선점과 락으로 부딪히지는 않지만 DB 자원을 나눠 쓴다.
B는 트랜잭션을 짧게 나누는 대신 "추첨 중" 상태, 담당 서버가 죽었는지 판단할 임대 시각, 중간 결과를 숨기는 조회 조건이 필요하다.

## 자세히

### A안의 실행 흐름
```java
@Transactional
void draw(long lotteryId) {
    // 1. 추첨 행을 먼저 잠그고 상태를 바꾼다. 0행이면 다른 서버가 이미 했거나 아직 때가 아니다
    int claimed = jdbc.update("""
        UPDATE lotteries SET status = 'DRAWN'
         WHERE id = ? AND status = 'CLOSED' AND draw_at <= now()
        """, lotteryId);
    if (claimed == 0) return;

    // 2. 추첨 대상을 읽는다(취소 제외)
    var entries = entryRepository.findEntered(lotteryId);      // 약 3만 행

    // 3. 순위와 당첨을 메모리에서 계산한다(D7)
    //    SHA-256(seed ‖ entryId) 오름차순 → 남은 좌석 ≥ 매수면 WON, 아니면 건너뜀
    //    WON이 다 차면 다음 1,000명 WAITLISTED, 나머지 LOST
    var results = drawPolicy.rank(lottery.seed(), lottery.seatCount(), entries);

    // 4. 배치 UPDATE로 반영한다
    entryRepository.batchUpdateRankAndStatus(results);         // 3만 행
}   // 커밋. 실패하면 1~4가 모두 롤백되어 추첨은 CLOSED로 남는다
```

3단계를 SQL 한 문장(`ROW_NUMBER() OVER (ORDER BY ...)`)으로 하지 않는 이유는 2매 건너뛰기 때문이다. "앞사람까지 몇 석을 썼는지"를 순서대로 세야 하므로 절차적 계산이 필요하다.

### 동시 실행이 막히는 원리
| 시점 | 서버 1 | 서버 2 |
|---|---|---|
| t1 | 1단계 UPDATE → 추첨 행 잠금 획득 | |
| t2 | 2~4단계 진행 | 1단계 UPDATE → 같은 행 잠금을 기다림 |
| t3 | 커밋 → 잠금 해제 | 깨어남. PostgreSQL은 READ COMMITTED에서 잠금을 기다린 뒤 **갱신된 최신 행으로 WHERE를 다시 평가**한다. `status`가 이미 `DRAWN`이라 0행 |
| t4 | | `claimed == 0` → 종료 |

별도 분산 락 없이 조건부 UPDATE 하나로 "한 번만 실행"이 된다. 서버 1이 t2에서 죽으면 트랜잭션이 롤백되어 잠금이 풀리고, 서버 2가 CLOSED 행을 잡아 처음부터 실행한다. 순위는 시드로 결정되므로 결과가 같다.

### 긴 트랜잭션의 비용
| 비용 | 내용 | 우리 경우 |
|---|---|---|
| 연결 점유 | 트랜잭션 동안 커넥션 풀의 연결 하나를 쥔다 | 추첨은 회차당 1번이라 1개. 풀 크기가 작으면 다른 요청의 대기가 늘 수 있다 |
| 행 잠금 | 갱신한 3만 행은 커밋까지 다른 쓰기를 막는다 | 응모 행은 마감 뒤라 다른 쓰기가 없다. 읽기(결과 조회)는 MVCC 덕에 막히지 않고 이전 값을 본다. 좌석·예매 행은 건드리지 않아 다른 회차 선점과 락 충돌이 없다 |
| 정리 지연 | 열려 있는 트랜잭션이 있으면 PostgreSQL은 그보다 뒤에 생긴 죽은 행을 VACUUM으로 치우지 못한다 | 몇 초면 영향이 작다. 몇 분짜리가 되면 선점·예매 테이블이 부풀 수 있다 |
| 쓰기 부하 | 3만 행 갱신이 한 번에 WAL과 디스크 쓰기를 만든다 | 같은 시각 다른 회차 선점의 응답 시간에 섞인다. 이것을 측정 시나리오 3이 본다 |

그래서 상한(10초 `[가정]`)을 두고, 넘으면 B로 바꾼다. 3만 행이 10초 안에 끝나는지는 측정 전이다.

### B안: 청크 + 중간 상태
1. `UPDATE lotteries SET status = 'DRAWING', claimed_until = now() + 1분 WHERE id = ? AND status = 'CLOSED'` (짧은 트랜잭션)
2. 순위를 메모리에서 전부 계산한다(A와 같다).
3. 1,000행씩 짧은 트랜잭션으로 반영한다. 반영할 때마다 `claimed_until`을 늘린다.
4. 마지막에 `DRAWING → DRAWN`.

추가로 필요한 것:
- **죽음 판단**: 서버가 3단계 중간에 죽으면 `DRAWING`이 남는다. 다른 서버는 `claimed_until`이 지났을 때만 이어받는다(outbox 릴레이의 임대와 같은 방식).
- **이어받기**: 순위가 결정적이라 처음부터 다시 계산해 덮어쓰면 된다(멱등).
- **중간 결과 숨기기**: `DRAWING` 동안 일부 응모만 WON으로 보이면 안 되므로, 결과 조회와 배정 워커는 추첨이 `DRAWN`일 때만 응모 상태를 믿어야 한다.

| | A | B |
|---|---|---|
| 상태 | CLOSED → DRAWN | CLOSED → DRAWING → DRAWN |
| 동시 실행 방지 | 조건부 UPDATE + 행 잠금 | 조건부 UPDATE + 임대 시각 |
| 장애 복구 | 롤백 후 다음 주기 | 임대 만료 후 다른 서버가 다시 계산 |
| 트랜잭션 길이 | 3만 행 전체 | 1,000행씩 |
| 코드 | 작다 | 상태·임대·조회 조건이 는다 |

### 청크와 중간 상태란
- **청크(chunk)**: 큰 작업을 나눈 한 묶음이다. 3만 행을 한 번에 커밋하지 않고 1,000행씩 30번 커밋하면 1,000행이 청크 하나다. Spring Batch의 chunk 처리와 같은 말이다.
- **중간 상태**: 시작(`CLOSED`)과 끝(`DRAWN`) 사이에 "진행 중"을 뜻하는 상태(`DRAWING`)를 하나 더 두는 것이다.

청크로 나누면 중간 상태가 따라오는 이유는 커밋이 여러 번 일어나기 때문이다. A는 커밋이 한 번이라 DB에는 "전혀 안 함"과 "다 함" 두 모습만 보인다. B는 청크마다 커밋하므로 12번째 청크까지만 반영된 모습도 다른 트랜잭션에 보인다. 이 모습이 정상 진행 중인지, 담당 서버가 죽어 멈춘 것인지 구분할 표지가 필요하다.

| 시점 | 추첨 상태 | 응모 행 | 다른 쪽이 보는 것 |
|---|---|---|---|
| 시작 | `DRAWING`, `claimed_until` = 1분 뒤 | 모두 `ENTERED` | 다른 서버는 임대가 살아 있어 손대지 않는다 |
| 청크 12 커밋 뒤 서버 종료 | `DRAWING` 그대로 | 12,000행만 WON·LOST 반영 | 결과 조회는 `DRAWN`이 아니므로 "발표 전"으로 응답한다 |
| 임대 만료 | `DRAWING`, `claimed_until` 지남 | 일부 반영 | 다른 서버가 임대를 잡고 처음부터 다시 계산해 덮어쓴다 |
| 마지막 청크 뒤 | `DRAWN` | 모두 반영 | 결과 공개, 배정 워커 시작 |

### 긴 트랜잭션을 미리 막는 방법
| 층 | 방법 | 우리 경우 |
|---|---|---|
| 1. 트랜잭션 안의 일을 줄인다 | 갱신할 행 수를 줄인다. WON·WAITLISTED(좌석 수 + 1,000행)만 개별 갱신하고, 나머지는 `UPDATE ... SET status = 'LOST' WHERE lottery_id = ? AND status = 'ENTERED'` 한 문장으로 바꾼다 | 3만 번 개별 갱신이 약 1,000~2,000건 + 한 문장으로 준다. LOST에도 순위(`draw_rank`)를 저장해야 하는지는 확인할 질문이다 |
| | DB 왕복을 줄인다. JDBC 배치, 또는 `UPDATE ... FROM unnest(?::bigint[], ?::int[])` 한 문장으로 보낸다 | 계산은 메모리에서 끝내고 쓰기만 트랜잭션에 넣는다 |
| | 트랜잭션 안에서 외부 호출(PG, Kafka, 알림)을 하지 않는다 | 이미 outbox로 분리했다 |
| 2. 상한을 건다 | `@Transactional(timeout = 10)`: Spring이 남은 시간을 각 SQL의 조회 시간 제한으로 넘기고, 넘으면 예외 후 롤백한다 | 상한 10초 `[가정]`을 코드로 강제한다 |
| | DB 쪽 `SET LOCAL statement_timeout`, `lock_timeout`, `transaction_timeout`(PostgreSQL 17부터) | 우리 compose는 `postgres:17`이라 쓸 수 있다 |
| | 주의: 시간 초과로 롤백되면 다음 주기에 같은 일을 다시 해서 또 초과한다 | 연속 실패를 경보로 올리고 B로 바꾸는 조건으로 삼는다 |
| 3. 자원을 격리한다 | 배치용 커넥션 풀을 따로 두어 요청 처리 풀을 뺏지 않게 한다 | reservation-service 풀은 5개(`config-repo/reservation-service.yml`)라 추첨이 1개를 쥐면 20%다. 측정에서 선점 응답이 밀릴 때 도입한다 |
| 4. 미리 재고 계속 본다 | 3만 행 테스트(Testcontainers)로 트랜잭션 시간을 잰다 | 측정 전. 이 값이 A·B 선택의 근거다 |
| | 운영 감시: `pg_stat_activity`의 `now() - xact_start`, Hikari `hikaricp_connections_pending` | Prometheus·Grafana에 올린다 |

권장 순서는 1(쓰기 줄이기) + 2(시간 상한) + 4(측정)를 기본으로 하고, 측정이 상한을 넘을 때 3이나 B로 간다. 1은 A·B 어느 쪽이든 이득이고 2·4는 비용이 작다.

## 이 프로젝트에서는
- 설계 문서: `docs/design/lottery/options.md` D8, `details.md` 4절(추첨 스케줄러 10초 주기), 6절 실패 표(추첨 중 서버 종료, 동시 실행).
- 임대 방식의 선례: `common/src/main/java/com/iseonjwa/common/messaging/outbox/OutboxRepository.java`의 `claimed_until`.

## 면접 포인트
- **스케줄러가 여러 서버에서 돌 때 배치 작업을 한 번만 실행하려면?** 대상 행을 조건부 UPDATE로 상태 전이하면서 잡는다. PostgreSQL은 잠금을 기다린 쪽이 최신 행으로 조건을 다시 평가하므로 하나만 성공한다. 작업이 길면 트랜잭션을 나누고 임대 시각으로 담당 서버의 생존을 판단한다.
- **긴 트랜잭션이 왜 위험한가요?** 연결과 잠금을 오래 쥐고, VACUUM이 죽은 행을 치우지 못해 다른 테이블까지 부풀게 할 수 있다. 막으려면 트랜잭션 안의 쓰기를 줄이고, 시간 상한을 걸고, 실제 소요 시간을 측정·감시한다.

## 참고
- PostgreSQL 문서 "Transaction Isolation" 13.2.1 Read Committed(잠금 대기 뒤 갱신된 행으로 조건 재평가), "Routine Vacuuming", "Client Connection Defaults"(statement_timeout, lock_timeout, transaction_timeout)
