# 0030. 테이블 이름은 snake_case 복수형으로 통일한다

- 상태: 제안 (팀 확인 필요, 결제 코드 적용은 ISE-67)
- 날짜: 2026-10-28
- 결정자: 이강민 (SA 정리 담당)
- 관련 AI 판단: [테이블 이름 복수형 적용 범위](../ai-judgments/kangmin/2026-10-28-table-naming-scope.md)

## 맥락
- SA MASTER v3.2 12장 테이블 명세 22개 중 `users`만 복수형이고 나머지 21개는 단수형이었다. 단수형 `user`는 PostgreSQL 예약어라 따옴표 없이 테이블 이름으로 쓸 수 없다. 이름 규칙을 따로 정한 기록은 없었다.
- 서비스별 구현이 시작됐다. user-service는 `users`, payment-service는 V1 마이그레이션에서 `payment`·`payment_status_history`·`idempotency_record`·`outbox`·`processed_event`를 만들었다(2026-10-28 dev 기준). 규칙이 없으면 담당마다 다른 형태를 쓰고, 공통 테이블(outbox 등)은 서비스마다 이름이 달라질 수 있다.
- 예약어 문제(`user`, `order`, `group`)는 단수형에서만 생긴다. 복수형으로 통일하면 이 예외가 규칙 안으로 들어온다.

## 결정
- 테이블 이름은 snake_case 복수형으로 쓴다.
- 여러 단어면 마지막 단어만 복수형으로 쓴다: `reservation_seats`, `user_schedule_quotas`, `seat_hold_requests`.
- 불규칙형: `history` → `histories`, `lottery` → `lotteries`, `entry` → `entries`, `mismatch` → `mismatches`.
- 예외를 두지 않는다. 패턴 이름인 `outbox`도 `outbox_events`로 바꾼다.
- 컬럼 이름과 FK 컬럼은 단수형 그대로 둔다: `reservation_id`, `payment_id`. 행 하나가 가리키는 대상이 하나이기 때문이다.
- 인덱스·제약 이름은 테이블 이름에 맞춘다(`idx_payments_...`, `uq_payments_...`, `chk_outbox_events_...`). 결제는 ISE-67에서 이 규칙으로 바꿨다. 다른 서비스는 마이그레이션을 만들 때 같은 규칙을 따른다.

| v3.2 | v3.3 |
|---|---|
| users | users (그대로) |
| venue · venue_seat | venues · venue_seats |
| concert · schedule · schedule_seat | concerts · schedules · schedule_seats |
| user_schedule_quota | user_schedule_quotas |
| reservation · reservation_seat · reservation_status_history | reservations · reservation_seats · reservation_status_histories |
| seat_hold_request · ticket | seat_hold_requests · tickets |
| lottery · lottery_entry · notification | lotteries · lottery_entries · notifications |
| payment · payment_status_history · refund · billing_key | payments · payment_status_histories · refunds · billing_keys |
| outbox · processed_event · idempotency_record | outbox_events · processed_events · idempotency_records |

## 검토한 대안
- 단수형 통일(`user` → `app_user` 등): JPA 엔티티 이름과 같아 보기 쉽지만, 예약어를 피하려고 이름을 바꾸는 예외가 생긴다. 이미 `users`가 구현돼 있어 바꿀 곳이 오히려 늘어난다.
- 규칙 없이 지금 이름 유지: 바꾸는 비용은 없지만, 구현이 늘수록 섞인 이름을 나중에 고치는 비용이 커진다.
- `outbox`만 예외로 유지: Outbox 패턴 문서와 이름이 같아 알아보기 쉽지만, "예외 없음" 규칙이 깨지고 다른 공통 테이블(`processed_events`)과 형태가 달라진다.

## 결과
- 얻는 것: 테이블 이름을 보고 규칙을 따로 확인할 필요가 없다. 예약어 충돌이 규칙 안에서 해결된다.
- 감수하는 것: 엔티티 클래스(`Payment`)와 테이블(`payments`) 이름이 달라 `@Table(name = ...)`을 꼭 적어야 한다. 이미 머지된 payment-service 마이그레이션·엔티티·쿼리를 바꿔야 한다.
- 적용(ISE-67): 배포 전이라 payment-service V1·V2 마이그레이션을 직접 고쳤다(이력이 깔끔하다). 이미 V1을 적용한 로컬·테스트 DB는 Flyway 체크섬이 어긋나므로 볼륨을 지우고 다시 만든다(`docker compose -f compose.test.yml down -v` 등).
- 후속 범위: payment-service 코드, 공통 모듈 messaging의 Outbox·처리 이력 SQL(`outbox`·`processed_event`가 문자열로 들어 있음), 결제 상세 설계·ADR 0016~0029의 테이블 이름은 결제 담당이 ISE-67에서 한 PR로 바꾼다. 그때까지 SA(v3.3)와 결제 코드·문서의 이름이 다르다. 결제 상세 설계에만 있는 M3 테이블(`dead_letter`, `manual_action_log`, `reconciliation_mismatch`)도 ISE-67에서 같은 규칙을 적용한다.
- 바꿀 조건: 팀이 단수형을 원하면 이 ADR을 대체하는 결정으로 되돌린다. ISE-67 적용 전이라면 SA만 되돌리면 된다.
