# 결정 기록 (ADR)

되돌리기 어려운 결정과 그 이유를 남긴다. 작성 방법은 [`adr` 스킬](../../.agents/skills/adr/SKILL.md)을 따른다. AI 제안을 두고 내린 결정이면 [`docs/ai-judgments/`](../ai-judgments/README.md)의 기록과 서로 링크한다.

| 번호 | 제목 | 상태 | 날짜 |
|---|---|---|---|
| 0001 | [Java 21 사용](0001-java-21.md) | 채택 | 2026-10-26 |
| 0002 | [공통 모듈 개인 초안의 빌드와 의존성](0002-common-foundation-draft.md) | 대체됨 (0004) | 2026-10-26 |
| 0003 | [같은 저장소의 native Config Server 사용](0003-config-server-native.md) | 채택 | 2026-10-26 |
| 0004 | [전체 워크스페이스와 M1 인증 구축](0004-workspace-auth-bootstrap.md) | 채택 (공통 모듈 구조는 0013, 패키지 이름은 0014로 대체) | 2026-10-26 |
| 0005 | [인증 Redis 장애 시 우선 요청 차단](0005-auth-redis-fail-closed.md) | 채택 (10/27 통합 회의) | 2026-10-26 |
| 0006 | [경로별 인증 판정은 Gateway 한 곳에서만 한다](0006-gateway-single-access-policy.md) | 채택 | 2026-10-27 |
| 0007 | [로그인 5회 실패 잠금과 관리자 비밀번호 초기화](0007-login-lockout-admin-reset.md) | 채택 (10/27 통합 회의) | 2026-10-27 |
| 0008 | [내부 신뢰 모델과 인증 설계](0008-internal-trust-and-auth-design.md) | 설계 중 (SA 유지, 보강 후보 선택 대기) | 2026-10-27 |
| 0009 | [서비스가 Access JWT를 직접 검증하는 방향 선택](0009-service-access-jwt-verification.md) | 철회 (SA 유지) | 2026-10-27 |
| 0010 | [인증 상태 확인과 세션 변경을 사용자 행 잠금으로 직렬화](0010-auth-session-serialization.md) | 채택 (브랜치 구현) | 2026-10-27 |
| 0011 | [앱 검증은 Java·JUnit으로 관리한다](0011-junit-verification-boundary.md) | 대체됨 (0012) | 2026-10-27 |
| 0012 | [개발 도구와 검증을 Java·Gradle로 통일한다](0012-java-gradle-workspace-tools.md) | 채택 (브랜치 구현) | 2026-10-27 |
| 0013 | [공통 모듈은 common 하나로 두고, 메시징만 만들 때 common-messaging으로 분리한다](0013-single-common-module.md) | 채택 (10/27 통합 회의. 메시징도 common에 두는 것으로 조정) | 2026-10-27 |
| 0014 | [패키지 이름은 com.iseonjwa로 한다](0014-package-name-com-iseonjwa.md) | 채택 | 2026-10-27 |
| 0015 | [대기열 입장 처리와 입장 토큰 발급의 안전장치](0015-queue-admission-guards.md) | 제안 (결정 4번의 토큰 응답 위치·쓰기 순서는 0031로 변경) | 2026-10-27 |
| 0016 | [거절된 결제의 토큰 재사용은 Payment와 Reservation이 함께 막는다](0016-payment-declined-token-reuse.md) | 제안 (SA 변경 요청·반효승 협의 필요, 10/28 개정) | 2026-10-27 |
| 0017 | [같은 멱등키 요청이 처리 중이면 202와 기존 paymentId를 돌려준다](0017-payment-idempotency-in-progress.md) | 제안 (SA 변경 요청 필요, 10/28 개정) | 2026-10-27 |
| 0018 | [신규 승인 시도만 PG 호출 허가를 TX1 앞에서 얻고, PG 호출 자원은 승인과 복구 두 그룹으로 나눈다](0018-payment-pg-permit-before-tx1.md) | 제안 (SA 변경 요청 필요, 10/28 개정) | 2026-10-27 |
| 0019 | [같은 예매에 다른 멱등키로 재요청하면 기존 결제로 응답하고 새 키는 별칭으로 기록한다](0019-payment-new-key-existing-payment.md) | 제안 (SA 변경 요청 필요, 10/28 개정) | 2026-10-27 |
| 0020 | [환불 요청은 DB에 영속화해 자동 환불 워커가 처리하고, DLQ에 온 요청도 같은 경로로 복구한다](0020-payment-refund-dlq-manual-check.md) | 제안 (SA 변경 요청 필요, 10/28 개정) | 2026-10-27 |
| 0021 | [결제 조회는 소유자 또는 ADMIN만 허용하고 타인 결제는 404로 숨긴다](0021-payment-object-level-authorization.md) | 제안 (SA 변경 요청 필요, 10/28 개정) | 2026-10-27 |
| 0022 | [Outbox는 지속 재시도하고, 실제로 바뀐 전이만 이벤트를 만들며, 정리는 미처리 건을 지우지 않는 배치로 한다](0022-outbox-retry-and-retention.md) | 제안 (SA 변경 요청 필요, 10/28 개정) | 2026-10-27 |
| 0023 | [PG 호출은 PgClient 포트와 PG 독립 결과 타입으로 나누고, 어댑터는 같은 계약 테스트를 통과한다](0023-payment-pg-client-port.md) | 제안 (SA 변경 요청 필요, 10/28 개정) | 2026-10-27 |
| 0024 | [결제는 핵심 지표와 대시보드 1장, 금전 정합성 알림을 MVP에 둔다](0024-payment-observability-metrics.md) | 제안 (SA 변경 요청 필요, 10/28 개정) | 2026-10-27 |
| 0025 | [결제 토큰 키는 환경변수로 주입하고 시작할 때 검증하며, 키 바이트는 기존 JWT 키와 같은 방식으로 해석한다](0025-payment-token-secret-injection.md) | 제안 (SA 변경 요청 필요, 10/28 개정) | 2026-10-27 |
| 0026 | [결제 행은 예매당 하나인 논리적 결제로 두고 reservation_id 전체 UNIQUE로 강제한다](0026-payment-one-per-reservation.md) | 제안 (SA 변경 요청 필요, U9 결과에 따라 재검토) | 2026-10-28 |
| 0027 | [예매 서비스는 결제 이벤트를 예매 원장과 대조해 상태별 규칙으로 처리한다](0027-reservation-payment-event-contract.md) | 제안 (반효승 협의·SA 변경 요청 필요) | 2026-10-28 |
| 0028 | [운영자 복구는 감사되는 Ops 명령으로만 하고 상태를 바꾸는 직접 SQL을 금지한다](0028-payment-ops-commands.md) | 제안 (SA 변경 요청 필요) | 2026-10-28 |
| 0029 | [결제·예매 대사는 결제 서비스가 예매 서비스의 내부 조회 API로 하고, MVP는 기록과 알림만 한다](0029-payment-reservation-reconciliation.md) | 제안 (반효승 협의·SA 변경 요청 필요) | 2026-10-28 |
| 0030 | [테이블 이름은 snake_case 복수형으로 통일한다](0030-table-naming-plural.md) | 제안 (팀 확인 필요, 결제 코드 적용은 ISE-67) | 2026-10-28 |
| 0031 | [대기열 입장 토큰은 토큰 API에서만 발급하고 READY 상태를 둔다](0031-queue-token-api-and-ready-status.md) | 제안 (Gateway·프론트·좌석 선점 확인 필요) | 2026-10-29 |
| 0032 | [좌석 선점 결과 응답에 seatIds를 포함하고 동기형 멱등 재요청 규칙을 고정한다](0032-seat-hold-result-and-idempotency-contract.md) | 제안 (반효승 확인 필요, ISE-21) | 2026-10-29 |
| 0033 | [공연장 좌석의 열 번호(row_no)는 정수로 둔다](0033-venue-seat-row-no-int.md) | 제안 (좌석 응답 `row` 형식 변경, 좌석 선점 담당 확인 필요) | 2026-10-30 |
| 0034 | [트래픽 집중 지점 격리와 스케일업을 위해 좌석 선점(seathold)을 별도 모듈로 분리하고 단방향 의존 규칙을 둔다](0034-seathold-unidirectional-module-boundary.md) | 제안 (반효승 확인 필요, ISE-10) | 2026-10-30 |
| 0035 | [포트폴리오 트러블슈팅 검증을 위해 좌석 선점 동시성·데드락 방어와 대사 쿼리를 M4 고도화 단계로 분리한다](0035-defer-seathold-concurrency-and-reconciliation-to-m4.md) | 채택 | 2026-10-30 |
| 0036 | [추첨 배분을 유지하고 측정 시나리오 3종을 설계에 먼저 넣는다](0036-lottery-direction-measurement.md) | 제안 (추첨 담당 결정, 팀 공유 전) | 2026-10-29 |
| 0037 | [추첨석은 한 등급에서 떼어 좌석 등급 LOTTERY로 표시하고 일반 선점에서 제외한다](0037-lottery-seat-grade-split.md) | 제안 (좌석 담당·팀 확인 대기, ISE-82) | 2026-10-31 |
| 0038 | [추첨 자동결제 실패는 사유별로 나눠 NOT_SENT만 새 예매로 재시도한다](0038-lottery-payment-failure-by-reason.md) | 제안 (결제 담당 확인 대기, ISE-89) | 2026-10-31 |
| 0039 | [추첨 자동결제는 전용 토픽 payment.billing과 PG 그룹 pg-billing으로 나눈다](0039-lottery-billing-topic-and-bulkhead.md) | 제안 (결제 담당 확인 대기, ISE-89) | 2026-10-31 |
