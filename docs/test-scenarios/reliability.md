# 메시징·장애·동시성 테스트 시나리오

기준 커밋 `0bc8141`. 표 읽는 법은 [README](README.md)에 있다. 이 문서는 시나리오 정의만 담고, 실행 결과는 [`runs/`](runs/)에 있다.

## Kafka·Outbox·DLQ (10건)

| ID | 시나리오 | 유형 | 우선순위 | 기대 결과 | 테스트 경로 | 실행 환경 |
|---|---|---|---|---|---|---|
| MQ-001 | Reservation 이벤트를 Kafka로 Payment가 소비 | 정상 | P0 | Payment가 소비 | `system-tests/WorkspaceSystemTest` | 자동 테스트, 로컬 |
| MQ-002 | Outbox 트랜잭션: 커밋 시 함께 저장, 롤백 시 함께 삭제 | 정상 | P0 | 업무와 이벤트 일치, 트랜잭션 밖 거절 | `reservation-service/ReservationOutboxTest` | 자동 테스트 |
| MQ-003 | Kafka 장애 중 승인 200, 복구 뒤 SENT | 실패 | P0 | 200, Outbox 적재 후 SENT | `payment-service/OutboxBrokerOutageApiTest` | 자동 테스트, 로컬 |
| MQ-004 | 릴레이 실패 분류: 영구·일시 오류와 백오프 | 실패 | P1 | 직렬화·크기·토픽 오류는 영구, 시한·브로커는 일시, 500ms\~60초 백오프 | `common/OutboxFailureClassifierTest`, `common/OutboxBackoffTest`, `payment-service/OutboxRelayIntegrationTest` | 자동 테스트 |
| MQ-005 | 릴레이 스케줄러 종료·예외 지속과 설정 검증 | 실패 | P2 | 다음 주기 지속, 종료 대기, 임대 < 시한 2배면 기동 차단 | `common/OutboxRelaySchedulerTest`, `common/OutboxPropertiesTest`, `common/ProducerTimeoutsTest` | 자동 테스트 |
| MQ-006 | 영구 오류는 즉시 DLQ, 일시 오류는 1·2·4초 재시도 뒤 DLQ | 실패 | P0 | 원본 위치 헤더 포함, 값 원문은 로그 제외, DLQ 전송 실패 시 재처리 | `common/ConsumerErrorHandlersTest` | 자동 테스트 |
| MQ-007 | 등록된 무제한 재시도 처리기: 건너뛰지 않고 60초 다시 읽기 | 경계값 | P2 | 같은 레코드 다시 읽음, 영구 오류 표시도 재시도 | `common/ConsumerErrorHandlersTest` | 자동 테스트 |
| MQ-008 | payment.command 봉투 검증: 형식 위반은 영구 오류 | 보안 | P1 | 모두 영구 오류, 모르는 필드는 무시 | `payment-service/PaymentCommandListenerTest`, `reservation-service/PaymentResultMessageParserTest`, `common/EventEnvelopeTest` | 자동 테스트 |
| MQ-009 | DLQ 소비자: 재처리 성공 RESOLVED, 실패 OPEN, DB 오류는 예외 | 실패 | P1 | 중복 1건, NUL·긴 키 치환, 나중 성공 시 RESOLVED | `reservation-service/PaymentResultDlqListenerTest`, `payment-service/PaymentCommandDlqListenerTest` | 자동 테스트 |
| MQ-010 | Ops dead_letter_resolve와 outbox_requeue | 실패 | P1 | 원문 재검증, REPLAYED·NO_OBLIGATION, 해시 불일치 거부, 같은 키 뒤 행 순서 발행 | `payment-service/DeadLetterResolveTest`, `payment-service/OutboxRequeueTest` | 자동 테스트 |

### Kafka·Outbox·DLQ: 직접 실행할 때

| ID | 사전 조건 | 절차 | 서버 데이터 변경 |
|---|---|---|---|
| MQ-001 | compose 스택 | 진단 이벤트 발행 | 서버 실행 안 함 |
| MQ-003 | Kafka 중지 | 결제 후 Kafka 재기동 | 있음 |

## 장애 주입 (14건)

| ID | 시나리오 | 유형 | 우선순위 | 기대 결과 | 테스트 경로 | 실행 환경 |
|---|---|---|---|---|---|---|
| FI-001 | 장애 주입·원장 API 프로필 제한 (S6, S7) | 보안 | P0 | test·loadtest에만 있음, prod+loadtest는 기동 실패. 배포 compose의 mock-pg는 loadtest | `mock-pg/FaultProfileTest`, `mock-pg/LedgerProfileTest` | 자동 테스트 |
| FI-002 | 승인 지연 주입 | 실패 | P1 | 지연 중 처리 중, 끝나면 승인 | `mock-pg/FaultModesApiTest`, `mock-pg/LongDelayConcurrencyTest` | 자동 테스트, 로컬, 서버 |
| FI-003 | 거절 주입과 보상 (S6) | 실패 | P0 | 거절 저장, 장애를 풀어도 거절. 이미 승인된 주문은 불변 | `mock-pg/FaultModesApiTest` | 자동 테스트, 로컬, 서버 |
| FI-004 | 응답 유실 주입 (S7) | 실패 | P0 | 응답 없음, 원장 저장, 결제 202 뒤 APPROVED | `mock-pg/ApprovalDropResponseTest`, `payment-service/Ft2PgResponseLossScenarioTest` | 자동 테스트, 로컬, 서버 |
| FI-005 | 처리 중(202)·서버 오류(500) 주입 | 실패 | P1 | 202 또는 500, 원장 불변, 망취소 미수행이면 이후 승인 200 | `mock-pg/FaultModesApiTest` | 자동 테스트, 로컬 |
| FI-006 | 환불 장애 주입 | 실패 | P1 | 테스트 편성대로 재시도·조회·호출 횟수 보존 (근거: 추정) | `mock-pg/RefundFaultApiTest`, `mock-pg/RefundCallCountingApiTest` | 자동 테스트, 로컬 |
| FI-007 | 장애 규칙 입력: 주문 한정, 모르는 필드 400 | 경계값 | P2 | 다른 주문은 미적용·횟수 유지, 모르는 필드는 400이고 규칙 불변 | `mock-pg/FaultModesApiTest`, `mock-pg/FaultRuleTest`, `mock-pg/FaultInjectorTest` | 자동 테스트 |
| FI-008 | CB OPEN 중 신규 승인 503, 재요청은 그대로 | 실패 | P0 | 503 PG_UNAVAILABLE, 행·멱등 기록 없음, 같은 키는 응답 재생 | `payment-service/PgProtectionApiTest`, `payment-service/Resilience4jPgGroupTest` | 자동 테스트, 로컬 |
| FI-009 | 승인 호출 포화 시 503, 복구 호출은 통과 | 동시성 | P1 | 바로 503, 복구 호출 미차단 | `payment-service/PgProtectionApiTest` | 자동 테스트 |
| FI-010 | 재시작 시나리오: TX1 뒤, PG 승인 뒤, 워커·릴레이·DLQ 중단 | 실패 | P0 | 임대 뒤 확정, PG 호출 1회, SENT 1행, 롤백 후 재처리 1건 | `payment-service/Ft3RestartScenarioTest` | 자동 테스트 |
| FI-011 | DB 장애: TX1·TX2 실패, 소비 중 장애, 환불 기록 실패 | 실패 | P0 | TX1은 503과 허가 반환, TX2는 REQUESTED 후 재확인, 소비는 재시도 | `payment-service/Ft4DbOutageScenarioTest` | 자동 테스트 |
| FI-012 | DB 멈춤: Gateway 시한 안 503, 생존 검사, 문장 시한 57014 | 실패 | P1 | 시한 안 503, 푼 뒤 재시도 성공, 승인 직후 정지는 202 | `payment-service/Ft4DbFreezeScenarioTest` | 자동 테스트 |
| FI-013 | 환불 CB OPEN 중 워커 대기 | 실패 | P1 | PG 미호출, 시도 횟수 유지, 닫힌 뒤 완료 | `payment-service/Ft5RefundInterruptionScenarioTest` | 자동 테스트 |
| FI-014 | 필수 Config 서버 없으면 앱 기동 실패 | 실패 | P1 | 기동 실패 | `system-tests/ConfigServerUnavailableTest` | 자동 테스트, 로컬 |

### 장애 주입: 직접 실행할 때

| ID | 사전 조건 | 절차 | 서버 데이터 변경 |
|---|---|---|---|
| FI-002 | POST /mock-pg/faults | 지연 규칙으로 승인과 조회 | 있음 |
| FI-003 | 거절 규칙 | 결제 | 있음 |
| FI-004 | 유실 규칙 | 결제 후 재확인 | 있음 |
| FI-005 | 각 규칙 | 승인·조회·망취소 | 있음 |
| FI-006 | 환불 장애 규칙 | 환불 요청과 워커 실행 | 있음 |
| FI-008 | PG 장애 지속 | CB 열린 뒤 요청 | 있음 |
| FI-014 | Config 중지 | 앱 기동 | 서버 실행 안 함 |

## 동시성·멱등 (8건)

| ID | 시나리오 | 유형 | 우선순위 | 기대 결과 | 테스트 경로 | 실행 환경 |
|---|---|---|---|---|---|---|
| CONC-001 | 동일 멱등키 동시 승인 | 동시성 | P0 | 결제 1건, PG 1회 | `payment-service/PaymentApprovalApiTest`, `payment-service/PaymentTxIdempotencyTest` | 자동 테스트 |
| CONC-002 | 다른 키 같은 예매 동시 승인 | 동시성 | P0 | 결제 1건, 진 쪽은 ALIAS | `payment-service/PaymentApprovalApiTest`, `payment-service/Ft1DuplicateScenarioTest`, `payment-service/PaymentTxApprovalRaceTest` | 자동 테스트 |
| CONC-003 | 같은 키로 다른 예매 동시 요청 | 동시성 | P1 | 한쪽 409, 결제 1건 | `payment-service/PaymentApprovalApiTest` | 자동 테스트 |
| CONC-004 | 결제 상태 전이 동시성 | 동시성 | P1 | 세부는 테스트 본문 확인 필요 (근거: 추정) | `payment-service/PaymentTransitionConcurrencyTest` | 자동 테스트 |
| CONC-005 | RefundRequested 중복·DLQ 선도착에도 환불 1건 | 동시성 | P0 | 환불 1건, PG 환불 1회 | `payment-service/Ft1DuplicateScenarioTest`, `payment-service/RefundWorkerWithMockPgTest` | 자동 테스트 |
| CONC-006 | Outbox 중단 후 같은 eventId 재전송, SENT 1행 | 동시성 | P1 | 임대 뒤 같은 eventId, SENT 1행 | `payment-service/Ft1DuplicateScenarioTest` | 자동 테스트 |
| CONC-007 | 클라이언트가 먼저 포기해도 서버 처리 계속, 같은 키로 결과 수신 | 동시성 | P1 | 저장 결과 수신 | `payment-service/PgProtectionApiTest` | 자동 테스트 |
| CONC-008 | 처리 이벤트 보존 정리 뒤 재전달 | 경계값 | P1 | 세부는 테스트 본문 확인 필요 (근거: 추정) | `payment-service/RedeliveryAfterRetentionCleanupTest` | 자동 테스트 |
