---
title: 결제 상세 설계 빈틈 11개를 결정하고 Opus 검토 지적을 반영
date: 2026-10-27
updated: 2026-10-28
judge: Junhyun An
ai: Claude Code (빈틈 탐색·선택지 제시, Opus 검토, Opus 문서 반영)
verdict: 수용
area: 결제
presentation: 후보
shared: 예
---

# 결제 상세 설계 빈틈 11개를 결정하고 Opus 검토 지적을 반영

## 맥락

- 해결하려던 작업: 결제 모듈 구현과 Linear 이슈 등록 전에 SA MASTER v3.2의 결제 설계를 점검하고 상세 설계를 확정한다.
- AI 활용 방식: Claude Code가 SA 6-6, 7-3, 8, 9-4, 10, 11-5, 12-4·12-5를 읽고 구현 전에 정해야 할 빈틈을 찾았다. 사용자는 이슈 등록 전에 설계부터 하기로 했고, 산출물은 `docs/design/payment/`에 두고 SA와 달라지는 부분은 SA 변경 요청 PR로 올리며 결정마다 ADR을 남기기로 정했다.
- 2026-10-28: 사용자가 완성된 설계 문서와 ADR 0015~0024를 Claude Code(Opus)에게 검토받았고, 검토 지적에 결정을 내린 뒤 문서 반영을 Opus에게 맡겼다.

## AI 제안

- 2026-10-27: 빈틈 11개(토큰 재사용, D3 인덱스 정의, 멱등 미완료 상태, CB와 D3 순서, 재요청 응답, 환불 DLQ, 조회 권한, outbox 보관, `PgClient` 포트, 관측 지표, 토큰 키 설정)를 찾고 한 건씩 선택지와 권장안을 제시했다. 목록과 영향은 이 기록과 [ADR 목록](../../decisions/README.md)에서 이어진다.
- 2026-10-28 검토: Opus가 C1(0015의 "HELD가 아닌 모든 상태에 환불"이 CONFIRMED를 포함해 재전달 때 확정 티켓이 환불될 수 있음), C2(환불 FAILED의 뜻이 문서마다 다름), H1~H8(조회 후 쓰기 경합, 금액·사용자 최종 대조 없음, 0행 UPDATE 뒤 후속 쓰기, REQUESTED의 재조회 시각 누락, 환불 조회 미발견 처리, 이중 환불 방어가 PG 멱등키에만 의존, 수동 복구의 전이별 이벤트와 감사 보호, 결제·예매 대사 없음), M1~M9(TX1 유니크 위반 500, 토큰 만료 재검사와 DB 연결 대기, CB·Bulkhead 분리, 스케줄러와 릴레이 간섭, PG 결과의 금액·통화, APPROVED CHECK와 환불 `INSERT … SELECT`, Kafka-UI 권한과 `RefundRequested` 교차 검증, DLQ 처리, 토스 `orderId`), L3~L9(새 키 연결 미기록, 토큰 키 해석 불일치, 임대와 전송 시한, `event_id`/`event_key`·NMC 에이징 알림·0016 순서 문구, SA 12-1과 `paymentId`·풀 크기, GET의 확정 여부, Gateway 3초)를 지적했다.
- 2026-10-28 반영: 사용자가 정하지 않고 맡긴 지점은 Opus가 근거를 찾아 정했고, 각 ADR에 대안과 트레이드오프를 적었다. 사용자 확인이 필요한 AI 판단은 아래 판단 이력의 마지막 항목에 모았다.

## 판단

- 결과: 수용 (결정마다 아래 판단 이력에 추가)
- 판단자: Junhyun An
- 근거: 사용자는 이유와 트레이드오프를 남겨 취업 준비 때 꺼내 쓰고 싶다고 했고, SA 대전제를 유지하되 변경은 SA 변경 요청 PR로 올리고 결정마다 기록하라고 했다. 2026-10-28에는 "보안과 결제 정합성에 목숨을 걸고, 성능을 포기하더라도 방어적으로"를 요구했다.
- 검토한 대안: 전체 설계 초안부터 쓰고 고치는 방식(사용자가 빈틈별 결정 방식을 선택했다)

## 반영

- [ADR 0015](../../decisions/0015-payment-declined-token-reuse.md): 거절된 결제의 토큰 재사용 방어(10/28 개정)
- [ADR 0016](../../decisions/0016-payment-idempotency-in-progress.md): 멱등키 처리 중 응답(10/28 개정)
- [ADR 0017](../../decisions/0017-payment-pg-permit-before-tx1.md): CB·Bulkhead 허가와 TX1 순서, 호출 자원 두 그룹(10/28 개정)
- [ADR 0018](../../decisions/0018-payment-new-key-existing-payment.md): 새 멱등키 재요청과 별칭 기록(10/28 개정)
- [ADR 0019](../../decisions/0019-payment-refund-dlq-manual-check.md): 환불 요청 영속화와 자동 복구(10/28 개정)
- [ADR 0020](../../decisions/0020-payment-object-level-authorization.md): 결제 조회 객체 단위 인가(10/28 보완)
- [ADR 0021](../../decisions/0021-outbox-retry-and-retention.md): Outbox 재시도·정리, 영향 행 1일 때만 후속 쓰기(10/28 개정)
- [ADR 0022](../../decisions/0022-payment-pg-client-port.md): PgClient 포트, 결과 금액·통화 검증(10/28 개정)
- [ADR 0023](../../decisions/0023-payment-observability-metrics.md): 결제 관측 지표·알림(10/28 개정)
- [ADR 0024](../../decisions/0024-payment-token-secret-injection.md): 결제 토큰 키 주입과 해석(10/28 개정)
- [ADR 0025](../../decisions/0025-payment-one-per-reservation.md): 예매당 결제 하나, 전체 UNIQUE
- [ADR 0026](../../decisions/0026-reservation-payment-event-contract.md): Reservation 결제 이벤트 처리 계약
- [ADR 0027](../../decisions/0027-payment-ops-commands.md): Ops 명령과 수동 복구
- [ADR 0028](../../decisions/0028-payment-reservation-reconciliation.md): 결제·예매 대사
- 설계 문서: [`docs/design/payment/`](../../design/payment/README.md), 불변 조건은 [invariants.md](../../design/payment/invariants.md)
- 빈틈 #2(D3 인덱스에 NEEDS_MANUAL_CHECK 누락)는 10/27에 DDL을 4개 상태로 쓰기로 했고, 10/28 ADR 0025의 전체 UNIQUE로 대체됐다.

## 검증

구현 전이다. 2026-10-28 문서 반영 뒤 `script/records-check.sh`, `git diff --check`, docs 상대 링크 검사, ADR·시나리오 번호 상호 참조 확인을 했다(결과는 반영 작업 보고에 있다). 시나리오와 테스트는 설계 기대값이며 실행하지 않았다.

## 판단 이력

- 2026-10-27 수용: 빈틈 #1(거절 뒤 토큰 재사용)은 Payment 차단과 Reservation 환불을 함께 두는 안을 골랐다. jti 폐기 목록과 Reservation 환불만 두는 안은 채택하지 않았다. ADR 0015.
- 2026-10-27 수용: 빈틈 #3(멱등키 처리 중 응답)은 202 + 기존 paymentId를 골랐다. 409 IN_PROGRESS와 완료 대기는 채택하지 않았다. ADR 0016.
- 2026-10-27 부분 수용: 빈틈 #4(CB·D3 순서)는 허가를 TX1 앞에서 얻는 안을 골랐다. 사용자는 기존 결제·멱등 조회는 허가 없이 처리하고, TX1 실패 시 허가를 반환하며, PG 호출은 TX1 커밋 뒤에 하고, 불확실한 승인에는 별도 조회·복구 정책을 적용하라고 조건을 추가했다. ADR 0017.
- 2026-10-27 부분 수용: 빈틈 #5(새 키 재요청)는 기존 결제로 응답하는 안을 골랐다. 사용자는 사용자·예매·금액·통화 일치 검증, REQUESTED·UNKNOWN 202와 APPROVED 200과 NEEDS_MANUAL_CHECK 409 + Location, 신규 PG 호출 금지, 새 키 조회는 멱등 기록에 저장하지 않고 감사 로그로 구분하는 조건을 추가했다. 통화 필드는 SA에 없어 현재는 검증 대상이 아니고, 감사는 구조화 로그로 정했다(AI 정리). ADR 0018. 이 과정에서 ADR 0015에 REFUNDED 차단을 추가했다.
- 2026-10-27 부분 수용: 빈틈 #6(환불 DLQ)은 DLQ 처리기 + 수동 확인 전환을 골랐다. 사용자는 NEEDS_MANUAL_CHECK를 실패 확정이 아닌 운영자 확인 필요 상태로 정의하고, 처리기의 멱등·유실 방지, refundId 기준 중복 환불 방지, 처리기 실패와 장기 미해결 알림, 수동 환불 감사 기록, 유실 경로 점검을 요구했고 재처리 API는 MVP에서 제외하며 수동 절차를 문서화하기로 했다. 환불 행이 REQUESTED·UNKNOWN일 때는 스케줄러가 이어받게 두는 부분과 `dead_letter`·`manual_action_log` 테이블은 AI가 구체화한 것이라 사용자 확인이 필요하다. ADR 0019.
- 2026-10-27 부분 수용: 빈틈 #7(조회 권한)은 소유자 또는 ADMIN만 허용하고 타인은 404로 하는 안을 골랐다. 사용자는 Gateway가 검증한 X-User-Id만 사용, 직접 접근·헤더 위조 방지, paymentId와 userId를 함께 조건으로 조회, ADMIN은 인증된 역할로 확인, 미존재와 타인 결제의 외부 응답 동일, 다른 paymentId 기반 API에도 같은 객체 단위 인가를 요구했고 BIGINT paymentId는 유지하며 외부 식별자 분리는 후속 개선으로 정했다. 타인 접근 시도의 내부 WARN 로그는 AI가 더한 항목이다. ADR 0020.
- 2026-10-27 부분 수용: 빈틈 #8(Outbox 정리)은 지속 재시도와 배치 삭제 안을 골랐다. 사용자는 지수 백오프·최대 간격·Jitter, 영구 오류만 FAILED, 미처리 건 자동 삭제 금지와 장기 미처리 모니터링, SENT 7일·processed_event 14일(Kafka·DLQ·수동 재처리보다 길게), 진행 중 결제의 멱등 유지, 작은 배치와 중복 실행 방지·재시도, SENT는 발행 성공만 의미, 업무 상태·DB 제약 방어, Kafka 전송 확인 기준 판단과 중단 시 재처리를 요구했다. 임대(`claimed_until`) 방식, 키 단위 순서 보존, 시작 시 보존 기간 검증, Reservation의 `payment.result` DLQ 처리기 통보는 AI가 구체화한 것이라 사용자 확인이 필요하다. ADR 0021.
- 2026-10-27 부분 수용: 빈틈 #9(PgClient 포트)는 포트 + 결과 타입 + 계약 테스트를 골랐다. 사용자는 승인·조회·환불 결과 타입 구분과 불확실하면 UNKNOWN, PG 응답·에러 코드는 어댑터에서 변환, 토스의 paymentKey·orderId·Idempotency-Key 역할 반영, 토스가 실제 지원하는 망취소 방식을 확인한 뒤 추상화, 계약 테스트 항목, Mock 장애 주입과 M4의 HTTP 스텁·토스 환경 통합 테스트 분리를 요구했고 M2는 포트·결과 타입·Mock 어댑터·계약 테스트, M4는 Toss 어댑터로 정했다. AI가 토스 공식 문서를 조회해 별도 망취소 API가 문서에 없음, confirm 흐름과 paymentKey 발급 주체, 멱등키 15일을 확인했고 확정하지 못한 5가지는 M4에서 확인하기로 했다. `preventLateApproval` 포트 메서드와 `clientPaymentRef`는 AI가 구체화한 것이라 사용자 확인이 필요하다. ADR 0022.
- 2026-10-27 부분 수용: 빈틈 #10(관측 지표)은 핵심 지표 + 대시보드 1장 + 알림을 골랐다. 사용자는 Micrometer·Prometheus·Grafana, 승인 지연·결과·PG 지연·UNKNOWN 원인, Resilience4j 지표 활용, UNKNOWN·NEEDS_MANUAL_CHECK·Outbox·DLQ·정리 작업 관측, 즉시 확인과 임계값 경고 구분, 낮은 카디널리티 라벨, DB 집계 Gauge 주기 갱신, 알림별 복구 절차 문서화를 요구했고 분산 추적과 추가 대시보드는 후속으로 정했다. 구체적 지표 이름과 임계값 초기값은 AI가 정리한 것이다. ADR 0023.
- 2026-10-27 부분 수용: 빈틈 #11(토큰 키)은 환경변수 + init 스크립트 생성을 골랐다. 사용자는 32바이트 16진수 키, initLocalEnv 최초 1회 생성·유지, reservation·payment에만 주입, 시작 시 존재·형식·길이 Fail-Fast, `.env`·Git 제외와 config-repo 자리표시자, 환경별 다른 키, 노출 방지, 자동 회전 제외와 유출 시 절차 문서화, 서명 외 만료·reservationId·userId·amount·용도 검증을 요구했다. ADR 0024. 이로써 빈틈 11개의 결정이 모두 끝났다.
- 2026-10-28 부분 수용(결정 1, 검토 H1): 사용자는 예매당 하나의 논리적 Payment 유지에 동의했다. 전체 UNIQUE(reservation_id) 적용 전에 현재 payment 행이 논리적 결제 단위인지 승인 시도 단위인지 확인하고, SA의 M4 자동결제와 "명확한 승인 거절 뒤 재시도" 요구를 SA·코드·설계 문서에서 직접 확인해 요구가 없으면 전체 UNIQUE를, 필요하면 Payment와 PaymentAttempt를 나누는 구조를 제안하라고 했다. 조회 후 쓰기만으로 중복을 막는 로직은 DB 제약으로 보완하라고 했다. ADR 0025.
- 2026-10-28 부분 수용(결정 2, 검토 M8): 사용자는 RefundRequested가 DLQ에 오면 전부 즉시 NEEDS_MANUAL_CHECK로 보내던 10/27 결정을 바꿔, 환불 요청을 DB에 영속화하고 Refund 행을 멱등 생성해 자동 환불 워커가 상태 조회·재시도하게 했다. 일시적 DB·네트워크 장애는 자동 복구, 명확한 영구 오류나 장기 미확정만 NEEDS_MANUAL_CHECK + 알림, 영속화 실패 시 DLQ 메시지를 처리 완료로 인정하지 않음(오프셋 미커밋)을 요구했다. ADR 0019 개정.
- 2026-10-28 부분 수용(결정 3, 검토 M3): 사용자는 신규 승인용과 UNKNOWN 재확인·환불 복구용 PG 호출 자원을 분리해 복구가 신규 승인 과부하로 막히지 않게 하되, 기능별 무조건 세분화는 하지 말고 호출 특성·장애 영향 기준의 최소 그룹(예: 2그룹)으로 하라고 했다. ADR 0017 개정.
- 2026-10-28 부분 수용(결정 4, 검토 C1·H2): 사용자는 Reservation 이벤트 처리 규칙을 상태별로 정했다. CONFIRMED + 같은 paymentId는 상태 변경·환불 없이 멱등, CONFIRMED + 다른 paymentId는 중복 승인으로 보고 추가 결제 환불, HELD + 유효한 승인은 금액·사용자·통화·만료 시간 검증 후 확정, RELEASED·EXPIRED + 승인은 확정 금지와 환불·정합성 확인, CONFIRMED + PaymentRefunded는 정상 취소 근거 확인 전까지 자동 좌석 해제 금지와 이상 격리·알림이다. Reservation은 이벤트의 reservationId·paymentId·userId·amount·currency를 원장·기존 결제 정보와 대조하고, 상태 변경과 후속 Outbox는 같은 트랜잭션이다. 반효승 계약이라 협의 대상으로 표시하라고 했다. ADR 0026.
- 2026-10-28 부분 수용(결정 5, 검토 H6·H7): 사용자는 운영자의 직접 SQL 수정을 금지하고 감사 로그가 남는 Ops 명령(결제 상태 확인, PG 실제 거래 대조, 안전한 상태 복구, 필요한 후속 이벤트 발행)을 두라고 했다. PG 콘솔 직접 환불은 정상 운영에서 제한하고 불가피하면 PG 결과 확인 후 DB에 반영하는 예외 절차로, NEEDS_MANUAL_CHECK를 APPROVED 등으로 바꿔 알림만 끄는 복구는 허용하지 않는다. manual-recovery.md 전면 개정과, MVP 관리자 재처리 API 미구현 결정과의 관계, Ops 명령의 형태·인증·4-eyes 여부를 근거와 함께 제안하라고 했다. ADR 0027.
- 2026-10-28 부분 수용(결정 6, 검토 H8): 사용자는 M3에 최소 대사를 두어 Payment와 Reservation의 상태·결제 금액·환불 여부를 대조하고 허용 지연을 넘긴 불일치를 탐지하며, MVP는 자동 수정 없이 기록과 Grafana 알림만 하라고 했다. 대사가 Reservation DB를 직접 읽지 못하는 서비스 경계 문제의 해법(API 조회, 이벤트 로그 기반 등)을 근거와 함께 제안하라고 했다. ADR 0028.
- 2026-10-28 부분 수용(결정 7, 검토 L3): 사용자는 기존 결제로 연결된 새 키를 기존 paymentId를 참조하는 별칭 기록으로 저장하고, 같은 키 재요청은 새 PG 호출 없이 기존 결제 상태를 조회하며, 연결 시 사용자·예매·금액 등 핵심 정보를 검증하고, 토큰 만료 뒤에는 신규 승인 작업은 불허하되 적법한 기존 결제 조회는 가능하게 나누라고 했다. ADR 0016·0018 개정.
- 2026-10-28 수용(추가 필수 수정): 사용자는 C2와 L6 즉시 수정, TX1 유니크 충돌을 정상 비즈니스 응답으로 처리(500 금지, 재조회 후 응답), PG 호출 직전 토큰 만료 재검증과 DB 커넥션 대기 제한, 스케줄러 실행기 구성과 Outbox 작업 간섭 방지, Kafka-UI 접근 권한 제한과 RefundRequested 교차 검증, HMAC 키 해석 규칙 통일(실제 TokenDecoders 동작과의 차이 명시, 어느 쪽에 맞출지 ADR 0024에서 결정), Gateway 타임아웃과 승인 복구 정책의 정합성(504 뒤에도 서버 처리는 계속되고 같은 키 재시도로 복구), PG 결과 타입에 실제 승인 금액·통화 반영과 불일치 처리 규칙, APPROVED의 PG 거래 식별자 무결성 CHECK, 조건부 UPDATE 영향 행 1일 때만 후속 Outbox 생성, 장애 시나리오(메시지 중복, PG 응답 유실, 서버 재시작, DB 일시 장애, 환불 중단)별 통합 테스트에서 승인 중복 방지·환불 중복 방지·결제↔예매 최종 정합성 검증을 지시했다. ADR 0016·0017·0019·0021·0022·0024, failure-scenarios.md 4절.
- 2026-10-28 원칙 제시: 사용자는 "기술이 있다고 안전한 것이 아니고 각 장치가 서로 다른 실패를 책임지게 해야 한다"며 불변 조건 7개를 먼저 정의하고 각각을 보장하는 기술과 연결하라고 했고, 가장 중요한 원칙으로 "금전적 의무가 추적 없이 사라지지 않는다"를 제시했다. AI 제안에 대한 판단이 아니라 사용자가 준 설계 원칙이라 근거로 남긴다. invariants.md.
- 2026-10-28 위임: 사용자는 위 결정의 문서 반영을 Opus에게 맡기고, 판단이 갈리는 지점은 근거를 찾아 스스로 정하되 대안과 트레이드오프를 ADR에 적으라고 했다. Opus가 정한 것 중 사용자 확인이 필요한 것: (1) 결정 1의 결론. SA 8-2·8-3·10-1·12-3·15장 #58·부록 A M6·U9를 대조해 "명확한 거절 뒤 같은 예매 재결제" 요구가 없다고 보고 전체 UNIQUE를 적용했다. U9(추첨 결제 실패 유형별 재시도)가 그 요구를 만들면 다시 연다. (2) Ops 명령 형태(payment-service 이미지의 `ops` 프로필 일회성 실행, SSH 경계, 운영자 허용 목록, 돈·이벤트가 움직이는 명령의 4-eyes, dry-run 계획 해시, PG 근거가 목표 상태를 정함). (3) 대사 경계 해법(Payment가 주체, Reservation의 읽기 전용 내부 조회 API, 허용 지연 10분, RC-1~RC-8). (4) 토큰 키 해석을 기존 TokenDecoders의 UTF-8 바이트에 맞추고 `exp` 허용 오차 0초. (5) 환불 자동 복구 한계(SA 9-4 간격으로 실제 PG 호출 9회·약 21분, CB OPEN 주기는 세지 않음, 환불 대기 경고 30분·심각 2시간). (6) 검증을 통과하지 못한 DLQ 메시지는 환불 행을 만들 근거가 없어 NEEDS_MANUAL_CHECK가 아니라 `dead_letter` OPEN + 알림으로 둔 것. (7) currency 필드를 PaymentApproved·PaymentRefunded·RefundRequested에 추가하고 payment에 `currency` 컬럼을 둔 것. (8) PG 금액 불일치는 자동 환불하지 않고 NEEDS_MANUAL_CHECK + Ops 명령 환불로 둔 것. (9) PG 호출 직전 만료는 `DECLINED(TOKEN_EXPIRED)`로 닫는 것.
- 2026-10-28 보류 뒤 결정: Linear 이슈 등록 뒤 AI가 M3 이슈 8건이 3일(11/4–11/6)에 몰린다며 일부를 M4로 미루는 안을 제시했다. 사용자는 M3 항목을 M2로 당겨오는 방안을 고려한 끝에, 마일스톤은 기존 배치(M2 6건, M3 8건, M4 2건)대로 구현하고 M2가 일찍 끝나면 그때 M3 항목을 당기기로 했다. AI가 제안한 당김 후보는 타임아웃(ISE-37 일부), 재확인 스케줄러(ISE-38), Outbox 재시도(ISE-40 일부), 승인 중복 방지 테스트(ISE-44 일부)다. Linear와 `docs/core/milestones.md`는 바꾸지 않았다.

## 발표·면접 포인트

- AI가 찾은 설계 빈틈을 그대로 받지 않고, 선택지와 트레이드오프를 보고 결정해 근거를 ADR로 남겼다.
- AI 검토가 찾은 결함(재전달 때 확정 티켓 환불, 조회 후 쓰기 경합)을 받은 뒤 "금전적 의무가 추적 없이 사라지지 않는다"는 원칙과 불변 조건을 먼저 세우고, 장치마다 다른 실패를 맡게 해 단일 방어 지점을 찾아 보강했다.
