# 결제·환불 테스트 시나리오

기준 커밋 `0bc8141`. 표 읽는 법은 [README](README.md)에 있다. 이 문서는 시나리오 정의만 담고, 실행 결과는 [`runs/`](runs/)에 있다.

## 결제·환불 (36건)

| ID | 시나리오 | 유형 | 우선순위 | 기대 결과 | 테스트 경로 | 실행 환경 |
|---|---|---|---|---|---|---|
| PAY-001 | 결제 승인 200 APPROVED와 결과 이벤트 (S4) | 정상 | P0 | 200 APPROVED, 결과 이벤트와 저장된 응답 | `payment-service/PaymentApprovalApiTest`, `system-tests/BookingFlowSystemTest` | 자동 테스트, 로컬, 서버 |
| PAY-002 | PG 거절 시 200 DECLINED와 사유 (S6) | 실패 | P0 | 200 DECLINED, 사유 포함 | `payment-service/PaymentApprovalApiTest` | 자동 테스트, 로컬, 서버 |
| PAY-003 | 같은 키 재요청 시 저장된 응답 재생, PG 1회 | 경계값 | P0 | 같은 응답, PG 호출 1회 | `payment-service/PaymentApprovalApiTest`, `payment-service/Ft1DuplicateScenarioTest` | 자동 테스트, 로컬 |
| PAY-004 | 새 키로 같은 예매 요청 시 기존 결제 응답, ALIAS 기록 | 경계값 | P0 | 기존 결제 응답, 새 키는 ALIAS | `payment-service/PaymentApprovalApiTest` | 자동 테스트 |
| PAY-005 | 거절된 예매를 같은 토큰으로 재승인 | 실패 | P1 | 409 PAYMENT_ALREADY_CLOSED, PG 미호출 | `payment-service/PaymentApprovalApiTest` | 자동 테스트 |
| PAY-006 | PG 결과 불명 시 202와 Location, 재요청도 202 (S7) | 실패 | P0 | 202와 Location, 같은 결제 | `payment-service/PaymentApprovalApiTest` | 자동 테스트, 로컬, 서버 |
| PAY-007 | 다른 사용자의 같은 예매 결제 요청 | 보안 | P0 | 409, Location과 기존 결제 정보 미제공 | `payment-service/PaymentApprovalApiTest` | 자동 테스트 |
| PAY-008 | 같은 키에 다른 본문, 사용자가 다르면 독립 | 실패 | P1 | 409 IDEMPOTENCY_KEY_REUSED, 사용자가 다르면 서로 독립 | `payment-service/PaymentApprovalApiTest` | 자동 테스트 |
| PAY-009 | 요청 금액과 토큰 금액 불일치, 소수 금액, 과도한 토큰 길이 | 경계값 | P0 | 400 PAYMENT_AMOUNT_MISMATCH, 결제와 PG 호출 없음. 소수·긴 토큰도 거부 | `payment-service/PaymentApprovalApiTest` | 자동 테스트, 로컬 |
| PAY-010 | 결제 토큰 위조·변조·alg none·용도 위반 거절 | 보안 | P0 | 검증기에서 모두 거절, API는 403 PAYMENT_TOKEN_INVALID | `payment-service/JwtPaymentTokenVerifierTest`, `payment-service/PaymentApprovalApiTest` | 자동 테스트 |
| PAY-011 | 만료 토큰: 신규 승인 403, 기존 결제 재요청·조회는 허용 | 경계값 | P0 | 결제 없음 403, 같은 키는 저장 결과, 새 키도 기존 결제 조회. 24시간 뒤에는 조회도 불가 | `payment-service/PaymentApprovalApiTest`, `payment-service/JwtPaymentTokenVerifierTest` | 자동 테스트 |
| PAY-012 | TX1과 PG 호출 사이 토큰 만료 | 경계값 | P1 | PG 미호출, 거절 | `payment-service/PaymentApprovalApiTest` | 자동 테스트 |
| PAY-013 | PG가 다른 금액으로 승인하면 수동 확인, 예매 미확정 | 실패 | P0 | NEEDS_MANUAL_CHECK, 확정 이벤트 없음, resolve로 PG 승인 금액 전액 환불 후 REFUNDED | `payment-service/PaymentApprovalApiTest`, `payment-service/Ft6AmountMismatchScenarioTest` | 자동 테스트 |
| PAY-014 | 수단이 CARD가 아니거나 형식 오류, 인증 헤더 없음 | 실패 | P2 | 400, 헤더 없으면 401 | `payment-service/PaymentApprovalApiTest` | 자동 테스트 |
| PAY-015 | 결제 조회: 소유자, 환불 시각, 미확정·수동 확인 상태 표시 | 정상 | P1 | 상태와 시각. 거절은 null 시각, 금액 불일치는 승인 시각 미노출 | `payment-service/PaymentQueryApiTest` | 자동 테스트, 로컬, 서버 |
| PAY-016 | 타인 결제 조회는 없는 결제와 같은 404, WARN 기록 | 보안 | P0 | 본문이 같은 404, 타인 접근만 WARN. ADMIN은 조회 가능 | `payment-service/PaymentQueryApiTest` | 자동 테스트 |
| PAY-017 | 빌링키 등록: 201 ACTIVE, 암호화 저장, 재등록 시 이전 키 REVOKED (S8) | 정상 | P0 | 201, ACTIVE는 하나 | `payment-service/BillingKeyAndPaymentTest`, `payment-service/AesGcmBillingKeyEncryptorTest` | 자동 테스트, 로컬, 서버 |
| PAY-018 | 빌링키 등록 PG 거절 400, 결과 불명 503, 저장 안 함 | 실패 | P1 | BILLING_KEY_REJECTED 400 또는 503, 행 없음 | `payment-service/BillingKeyAndPaymentTest` | 자동 테스트 |
| PAY-019 | 빌링키 해지와 재해지 | 경계값 | P2 | REVOKED, 재해지도 성공 | `payment-service/BillingKeyAndPaymentTest` | 자동 테스트, 로컬 |
| PAY-020 | 자동결제: BILLING 승인과 결과 이벤트 (S9) | 정상 | P0 | BILLING 결제 승인, 이벤트 발행 | `payment-service/BillingKeyAndPaymentTest` | 자동 테스트, 로컬, 서버 |
| PAY-021 | 빌링키 없으면 PG 미호출, BILLING_KEY_MISSING 거절 (S10) | 실패 | P0 | BILLING_KEY_MISSING 거절 | `payment-service/BillingKeyAndPaymentTest` | 자동 테스트, 로컬, 서버 |
| PAY-022 | 자동결제 중복 요청과 PG 거절·불명 | 실패 | P1 | 결제 1건·PG 1회, 거절 DECLINED, 불명 UNKNOWN 뒤 재확인 | `payment-service/BillingKeyAndPaymentTest` | 자동 테스트 |
| PAY-023 | 재확인: 승인 응답 유실 후 APPROVED 확정 (S7) | 실패 | P0 | APPROVED, PG 승인 1회 | `payment-service/Ft2PgResponseLossScenarioTest`, `payment-service/PaymentRecheckServiceTest` | 자동 테스트, 로컬, 서버 |
| PAY-024 | 재확인: 승인이 PG에 닿지 않음, 늦은 승인 차단 | 실패 | P0 | NotFound 뒤 망취소, DECLINED NOT_SENT | `payment-service/Ft2PgResponseLossScenarioTest` | 자동 테스트 |
| PAY-025 | 환불 요청 소비: 승인 결제면 생성, 중복·미승인·불일치 분기 | 경계값 | P0 | Created, Duplicate, NotApproved, Invalid로 구분 | `payment-service/RefundRequestResultTest`, `payment-service/RefundRequestServiceTest` | 자동 테스트 |
| PAY-026 | 환불 워커: 선점 만료 뒤 승인 건 REFUNDED | 정상 | P0 | 실제 PG에서 완료, REFUNDED | `payment-service/RefundWorkerWithMockPgTest` | 자동 테스트, 로컬 |
| PAY-027 | 환불 응답 유실: findRefund 확정, 미도달 시 같은 pg_refund_id 재호출 | 실패 | P0 | REFUNDED, PG 환불 1회 | `payment-service/RefundWorkerWithMockPgTest`, `payment-service/Ft2PgResponseLossScenarioTest` | 자동 테스트 |
| PAY-028 | PG 환불 거절·장기 장애는 NMC, refund_retry와 close_failed | 실패 | P1 | NMC, retry 뒤 완료, close_failed는 외부 조치 참조 필수 | `payment-service/Ft5RefundInterruptionScenarioTest`, `payment-service/RefundRetryTest` | 자동 테스트 |
| PAY-029 | 콘솔 환불은 pg_refund_id가 없어 resolve 거부 | 경계값 | P2 | NotFound 거부, NMC 유지 | `payment-service/Ft5RefundInterruptionScenarioTest`, `payment-service/RefundResolveTest` | 자동 테스트 |
| PAY-030 | 대사: 응답 유실 복구 뒤 불일치 0, 직접 SQL 변경 탐지 | 실패 | P1 | 복구 뒤 0건, RC7 탐지, 환불 요청 누락은 RC2 OPEN | `payment-service/ReconciliationScenarioTest`, `payment-service/ReconciliationReservationScenarioTest`, `payment-service/ReconciliationServiceTest` | 자동 테스트 |
| PAY-031 | Ops payment_resolve: dry-run, 승인자, 해시, 두 번 적용 거부 | 보안 | P1 | 승인자 없으면 PG 조회 전 거부, 결과 변동 시 해시 불일치 거부, 이벤트 1건 | `payment-service/PaymentResolveTest`, `payment-service/OpsRunnerTest` | 자동 테스트 |
| PAY-032 | 결제 상태 전이: 허용 전이와 행위자 기록 | 경계값 | P2 | 허용 전이만 수행, 이력 기록 | `payment-service/PaymentStatusTest`, `payment-service/PaymentTransitionServiceTest`, `payment-service/StatusActorTest` | 자동 테스트 |
| PAY-033 | 결제 스키마 제약 56건 | 경계값 | P2 | 제약 위반 거부(개별 항목은 테스트 참조) | `payment-service/PaymentMigrationTest` | 자동 테스트 |
| PAY-034 | PG 어댑터와 Mock PG 서버 계약 일치 | 정상 | P1 | 클라이언트와 서버 동작 일치 | `payment-service/MockPgServerContractTest`, `payment-service/MockPgAdapterContractTest`, `payment-service/PgClientContractTest` | 자동 테스트 |
| PAY-035 | 예매 조회 실패 분기와 부하 분산 | 실패 | P2 | 클라이언트 계약대로 처리 | `payment-service/ReservationLookupHttpClientTest`, `payment-service/ReservationLookupContractTest`, `payment-service/ReservationLoadBalancingConfigurationTest` | 자동 테스트 |
| PAY-036 | 보존 기간 지난 Outbox·처리 이력 정리와 잠금 | 경계값 | P2 | SENT만 삭제, 배치 단위, 다른 연결이 잠금을 쥐면 0건 | `payment-service/RetentionCleanupTest`, `payment-service/IdempotencyRecordCleanupTest`, `payment-service/RedeliveryAfterRetentionCleanupTest` | 자동 테스트 |

### 결제·환불: 직접 실행할 때

| ID | 사전 조건 | 절차 | 서버 데이터 변경 |
|---|---|---|---|
| PAY-001 | HELD 예매, 결제 토큰 | POST /api/v1/payments | 있음 |
| PAY-002 | 거절 장애 주입 | 결제 요청 | 있음 |
| PAY-003 | 승인 완료 | 같은 Idempotency-Key로 재요청 | 없음 |
| PAY-006 | 응답 유실 주입 | 결제 요청 후 같은 키·새 키로 재요청 | 있음 |
| PAY-009 | 결제 토큰 | 금액을 조작해 요청 | 없음 |
| PAY-015 | 상태별 결제 | GET /api/v1/payments/{id} | 없음 |
| PAY-017 | 로그인 | POST /api/v1/payments/billing-keys 2회 | 있음 |
| PAY-019 | ACTIVE 키 | DELETE /billing-keys/me 2회 | 있음 |
| PAY-020 | 빌링키, 자동결제 명령 | payment.command 소비 | 있음 |
| PAY-021 | 카드 미등록 당첨자 | 자동결제 명령 | 있음 |
| PAY-023 | UNKNOWN 결제 | 재확인 작업 실행 | 있음 |
| PAY-026 | E8 상황 | 환불 워커 실행 | 있음 |
