# 추첨 테스트 시나리오

기준 커밋 `0bc8141`. 표 읽는 법은 [README](README.md)에 있다. 이 문서는 시나리오 정의만 담고, 실행 결과는 [`runs/`](runs/)에 있다.

## 추첨 (31건)

| ID | 시나리오 | 유형 | 우선순위 | 기대 결과 | 테스트 경로 | 실행 환경 |
|---|---|---|---|---|---|---|
| LOT-001 | 추첨 정상 흐름: 설정부터 종결까지 (S8, S9) | 정상 | P0 | 당첨자 예매 확정, 종결 DONE | `reservation-service/LotteryScenarioTest(S1)` | 자동 테스트, 로컬, 서버 |
| LOT-002 | 응모 기간 안 응모 201 ENTERED (S8) | 정상 | P0 | 201 ENTERED | `reservation-service/LotteryEntryApiTest` | 자동 테스트, 로컬, 서버 |
| LOT-003 | 응모 멱등: 같은 키 재전송, 매수 변경 409 | 경계값 | P0 | 처음 응모 반환, 변경 시 409 | `reservation-service/LotteryEntryApiTest` | 자동 테스트, 로컬 |
| LOT-004 | 같은 사용자 다른 키 동시 응모 | 동시성 | P0 | 1건만 성공 | `reservation-service/LotteryEntryApiTest`, `reservation-service/LotteryMigrationTest` | 자동 테스트 |
| LOT-005 | 응모 시작 전·마감 뒤 응모 거절 | 경계값 | P1 | 409 LOTTERY_NOT_OPEN | `reservation-service/LotteryEntryApiTest` | 자동 테스트, 로컬, 서버 |
| LOT-006 | 응모 매수 경계와 멱등키 형식 | 경계값 | P1 | 400 | `reservation-service/LotteryEntryApiTest` | 자동 테스트, 로컬 |
| LOT-007 | 응모 취소 뒤 재응모 불가, 추첨 대상 제외 | 경계값 | P1 | CANCELED, 재응모 불가, 대상 제외. 마감 뒤 취소 불가 | `reservation-service/LotteryEntryApiTest`, `reservation-service/LotteryScenarioTest(S8)` | 자동 테스트, 로컬 |
| LOT-008 | 미응모자의 취소·결과 조회, 없는 추첨 응모 404 | 실패 | P2 | 404 | `reservation-service/LotteryEntryApiTest` | 자동 테스트 |
| LOT-009 | 추첨 정보는 로그인 없이 조회, 내 결과는 조회 가능 (S8) | 정상 | P1 | 공개 조회, 내 결과 | `reservation-service/LotteryEntryApiTest` | 자동 테스트, 로컬, 서버 |
| LOT-010 | 마감 지난 OPEN만 CLOSED, 추첨 시각 전 미실행 | 경계값 | P1 | 대상만 전이 | `reservation-service/LotteryDrawServiceTest` | 자동 테스트 |
| LOT-011 | 추첨 결과: 당첨 매수가 좌석 수 이하, 예비·낙첨 분리, 재실행 1회 반영 (S9) | 정상 | P0 | 한 번만 반영, 좌석 수 불초과 | `reservation-service/LotteryDrawServiceTest` | 자동 테스트, 로컬, 서버 |
| LOT-012 | 공개 계산식과 시드로 순위 재현 | 보안 | P0 | 같은 순위, 시드가 다르면 달라짐. 시드는 32바이트, 빈 시드 거절 | `reservation-service/DrawRankerTest`, `reservation-service/LotteryScenarioTest(S10)` | 자동 테스트 |
| LOT-013 | 응모 3만 행 추첨 10초 이내 | 경계값 | P1 | 10초 안에 완료 | `reservation-service/LotteryDrawServiceTest` | 자동 테스트 |
| LOT-014 | 좌석 배분: 매수 초과 건너뛰기, 옆자리 우선, 열 경계 | 경계값 | P1 | 건너뛴 응모자는 예비 앞쪽, 2매는 실제 옆자리, 없으면 떨어진 좌석, 중복 좌석 거절 | `reservation-service/SeatAllocatorTest`, `reservation-service/LotteryScenarioTest(S7)` | 자동 테스트 |
| LOT-015 | 당첨자 배정: LOTTERY 예매를 30분 선점으로 생성 | 정상 | P0 | HELD 예매 연결, 트랜잭션 밖 호출 거절 | `reservation-service/LotteryAssignmentTest`, `reservation-service/SystemHoldPortTest` | 자동 테스트 |
| LOT-016 | 배정 실패: 건너뛰기, 3회 연속 ASSIGN_ERROR, NO_SEAT, 매수 초과 | 실패 | P1 | 다음 당첨자 진행, 세 번째 ASSIGN_ERROR, 승계 마감 뒤 NO_SEAT FAILED, PURCHASE_LIMIT_EXCEEDED FAILED | `reservation-service/LotteryAssignmentSchedulerTest`, `reservation-service/LotteryAssignmentTest` | 자동 테스트 |
| LOT-017 | 결제 승인으로 응모 PAID, 거절로 FAILED (S9) | 정상 | P0 | PAID 또는 FAILED와 사유 | `reservation-service/PaymentResultLotteryTest` | 자동 테스트, 로컬, 서버 |
| LOT-018 | NOT_SENT 거절은 새 예매로 1회 재시도, 두 번째 FAILED | 실패 | P1 | 재시도 1회, 승계 마감 뒤 즉시 FAILED | `reservation-service/LotteryAssignmentTest`, `reservation-service/LotteryScenarioTest(S3, S13)` | 자동 테스트 |
| LOT-019 | 결과 없이 만료: 1회 연장 뒤 FAILED, 연장 상한 | 경계값 | P1 | 1회 연장, 상한까지만, 이후 HOLD_EXPIRED | `reservation-service/LotteryHoldExpiryTest`, `reservation-service/LotteryScenarioTest(S4)` | 자동 테스트 |
| LOT-020 | 결제 결과 이상 순서: 만료 뒤 승인·금액 불일치·해제 뒤 승인 | 실패 | P0 | 환불만 요청, 응모 사유 유지. 연장된 예매에 온 승인은 PAID | `reservation-service/PaymentResultLotteryTest` | 자동 테스트 |
| LOT-021 | 중복 결과와 롤백: 한 번만 반영, 예매·응모 함께 복원 | 동시성 | P1 | 한 번 반영, 롤백 시 함께 복원 | `reservation-service/PaymentResultLotteryTest`, `reservation-service/LotteryAssignmentTest` | 자동 테스트 |
| LOT-022 | 일반 예매 결과는 추첨 처리기를 호출하지 않음 | 경계값 | P2 | 처리기 미호출 | `reservation-service/PaymentResultLotteryTest` | 자동 테스트 |
| LOT-023 | 승계: 실패 자리를 예비자가 순위대로 채움 (S10) | 정상 | P0 | 순위 순 당첨, 매수 초과 예비자 건너뜀 | `reservation-service/LotterySuccessionTest`, `reservation-service/LotteryScenarioTest(S2)`, `reservation-service/SuccessionPlannerTest` | 자동 테스트, 로컬, 서버 |
| LOT-024 | 승계 워커 동시 실행과 행 잠금 | 동시성 | P0 | 좌석 수 불초과, 잠금 시 건너뜀 | `reservation-service/LotterySuccessionTest` | 자동 테스트 |
| LOT-025 | 승계 마감 뒤 미승계와 예비자 LOST | 경계값 | P1 | 승계 안 함, 남은 예비자 LOST | `reservation-service/LotterySuccessionTest` | 자동 테스트 |
| LOT-026 | 좌석 반환이 늦어도 승계자 탈락 없음 | 경계값 | P1 | 승계자 유지 | `reservation-service/LotteryScenarioTest(S11)` | 자동 테스트 |
| LOT-027 | 종결: 미완료가 없으면 DONE과 추첨석 반환 | 정상 | P0 | DONE, 원래 등급으로 일반 판매 복귀 | `reservation-service/LotteryFinishTest`, `reservation-service/LotteryScenarioTest(S5)` | 자동 테스트, 로컬, 서버 |
| LOT-028 | 미결제 당첨·승계 가능 예비자가 있으면 종결 보류 | 경계값 | P1 | 종결 안 함, 잠금 시 건너뜀, 롤백 시 DONE·반환 함께 취소 | `reservation-service/LotteryFinishTest` | 자동 테스트 |
| LOT-029 | 응모 없는 추첨 즉시 종결 | 경계값 | P2 | 바로 종결, 좌석 모두 반환 | `reservation-service/LotteryScenarioTest(S6)` | 자동 테스트 |
| LOT-030 | 전체 단계 2회 호출에도 결과 동일 | 동시성 | P1 | 같은 결과 | `reservation-service/LotteryScenarioTest(S9)` | 자동 테스트 |
| LOT-031 | 추첨 DB 제약: 회차당 1개, LOTTERY 등급 금지, 시드 길이, 마감 순서 | 경계값 | P2 | 거부 | `reservation-service/LotteryMigrationTest` | 자동 테스트 |

### 추첨: 직접 실행할 때

| ID | 사전 조건 | 절차 | 서버 데이터 변경 |
|---|---|---|---|
| LOT-001 | 오픈 전 회차, 빌링키 | 설정 → 응모 → 마감 → 추첨 → 배정 → 자동결제 → 종결 | 있음 |
| LOT-002 | OPEN 추첨 | POST /lotteries/{id}/entries | 있음 |
| LOT-003 | 응모 완료 | 같은 키, 매수 변경 재전송 | 있음 |
| LOT-005 | 기간 밖 | 응모 | 없음 |
| LOT-006 | OPEN 추첨 | 매수 0, 3, 추첨 최대 초과, UUID 아닌 키 | 없음 |
| LOT-007 | 응모 완료 | DELETE entries/me 뒤 재응모, 추첨 | 있음 |
| LOT-009 | 추첨 | GET /lotteries/{id}, GET entries/me | 없음 |
| LOT-011 | 응모 다수 | 추첨 2회 호출 | 있음 |
| LOT-017 | 배정 완료 | 결제 결과 소비 | 있음 |
| LOT-023 | 거절 당첨자, 예비자 | 승계 작업 실행 | 있음 |
| LOT-027 | 모든 응모 종료 | 종결 작업 | 있음 |
