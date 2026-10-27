# 0027. 운영자 복구는 감사되는 Ops 명령으로만 하고 상태를 바꾸는 직접 SQL을 금지한다

- 상태: 제안 (SA 변경 요청 필요. 명령 형태·인증·4-eyes 범위는 AI 제안이라 사용자 확인 필요)
- 날짜: 2026-10-28
- 결정자: 안준현(직접 SQL 금지, 감사되는 Ops 명령, 최소 기능, 콘솔 환불 예외, 알림만 끄는 복구 금지는 사용자 결정)
- 관련 AI 판단: [결제 상세 설계 빈틈 결정](../ai-judgments/junhyun/2026-10-27-payment-design-gaps.md)
- 관련 결정: [0019](0019-payment-refund-dlq-manual-check.md), [0021](0021-outbox-retry-and-retention.md), [0022](0022-payment-pg-client-port.md), [0008](0008-internal-trust-and-auth-design.md)
- 불변 조건: INV-2, INV-7, INV-9([invariants.md](../design/payment/invariants.md))

## 맥락

0019(2026-10-27)는 MVP에 관리자 재처리 API를 두지 않고, 운영자가 PG를 확인한 뒤 "검증된 SQL 템플릿"으로 DB를 고치게 했다. 이 방식은 다음 위험이 있다(Opus 검토 H6·H7).

- SQL 한 줄로 `NEEDS_MANUAL_CHECK`를 `APPROVED`로 바꾸면 `PaymentApproved`가 나가지 않아 예매는 확정도 환불도 되지 않는다. 알림만 꺼지고 돈은 묶인다.
- 전이별로 함께 써야 할 이력·Outbox·감사 기록을 사람이 기억해야 한다.
- PG 콘솔에서 직접 환불하면 우리 `pg_refund_id` 멱등키 없이 환불이 일어나 자동 경로의 재시도와 겹칠 수 있다.
- `manual_action_log`가 "수정·삭제하지 않는다"는 약속뿐이라 기록을 고쳐도 막지 못한다.

사용자는 직접 SQL 수정을 금지하고, 결제 상태 확인·PG 실제 거래 대조·안전한 상태 복구·필요한 후속 이벤트 발행을 하는 감사되는 Ops 명령을 두라고 했다. PG 콘솔 직접 환불은 정상 운영에서 제한하고, 불가피하면 PG 결과를 확인한 뒤 DB에 반영하는 예외 절차를 둔다. `NEEDS_MANUAL_CHECK`를 `APPROVED` 등으로 바꿔 알림만 끄는 복구는 허용하지 않는다.

## 결정

1. **직접 SQL 금지**: 결제·환불·Outbox·`dead_letter`의 상태를 바꾸는 SQL(UPDATE·INSERT·DELETE)을 운영 절차에서 쓰지 않는다. 읽기 SQL은 조사에 쓸 수 있다.
2. **명령 목록(최소 6개)**: `inspect`(상태·이력·환불·Outbox·`dead_letter`·대사를 묶어 출력, `--with-pg`로 PG 대조), `payment resolve`, `refund retry`, `refund resolve`, `outbox requeue`, `dead-letter resolve`. 각 명령의 전이와 함께 쓰는 이벤트·이력·감사는 [manual-recovery.md](../design/payment/manual-recovery.md) 1절 표가 기준이다.
3. **PG 근거가 목표 상태를 정한다**: 운영자는 목표 상태를 인자로 고르지 않는다. `payment resolve`·`refund resolve`는 실행 시점에 `PgClient`로 다시 조회해 근거와 맞는 전이만 한다. PG를 조회하지 못하거나 `Unknown`이면 아무것도 바꾸지 않는다. 그래서 근거 없이 알림만 끄는 경로가 없다.
4. **PG 환불은 자동 경로로만**: Ops 명령은 `PgClient.refund`를 부르지 않는다. 환불 시작·재시도는 환불 행을 `REQUESTED`로 만들어 환불 워커가 처리하게 한다. 워커는 재호출 전에 조회부터 한다(0022).
5. **전이 규칙 공유**: 명령은 승인 API·워커와 같은 application 유스케이스를 부른다. 조건부 UPDATE가 1행일 때만 이력(`actor=OPS:<operator>`)·Outbox·`manual_action_log`(APPLIED)를 한 트랜잭션에 쓴다. 0행·근거 불일치·통제 위반은 `manual_action_log`에 REJECTED만 남긴다.
6. **형태**: `payment-service` 이미지를 `ops` 프로필로 한 번 띄우는 명령행 실행이다. 웹 서버·Kafka 소비자·스케줄러를 켜지 않고, 끝나면 종료한다. HTTP 엔드포인트를 만들지 않는다. EC2에서는 호스트의 SSH 세션에서 compose 네트워크로 실행한다. 정확한 실행 명령은 구현 때 정한다.
7. **인증**: 실행 경계는 SSH(팀원 IP, 키 인증, SA 9-2)와 호스트의 컨테이너 실행 권한이다. 운영자 ID는 `--operator`로 받고 설정의 허용 목록(팀원 ID)에 있어야 한다. 이 ID는 암호학적으로 증명되지 않는 자기 신고이며, 감사 기록의 정확성은 SSH 접근 통제에 기댄다.
8. **4-eyes**: 돈이나 이벤트가 움직이는 명령(`payment resolve`, `refund retry`, `refund resolve`, `dead-letter resolve`)은 `--approver`가 필요하다. 승인자는 허용 목록에 있고 실행자와 달라야 한다. `inspect`와 `outbox requeue`(이미 정해진 이벤트를 다시 보냄)는 1인이다.
9. **dry-run과 계획 해시**: 모든 명령은 dry-run이 기본이다. dry-run은 현재 상태, PG 근거, 바뀔 전이, 발행할 이벤트, 계획 해시(대상·현재 상태·PG 근거 요약의 SHA-256)를 출력한다. `--apply --confirm <해시>`로만 실행하고, 그사이 상태나 근거가 바뀌어 해시가 다르면 거부한다.
10. **감사 테이블 보호**: `manual_action_log`와 `payment_status_history`에 UPDATE·DELETE·TRUNCATE를 거부하는 트리거를 둔다([data-model.md](../design/payment/data-model.md)). 직접 SQL로 상태를 바꾸면 대사 RC-7(상태·이력 불일치)·RC-8(확정 전이 이벤트 누락)이 사후에 찾는다([0028](0028-payment-reservation-reconciliation.md)).
11. **PG 콘솔 직접 환불**: 정상 운영에서 금지한다. 예외 조건은 환불이 `NEEDS_MANUAL_CHECK`(자동 경로 정지), PG API 경로로는 불가하고 콘솔만 가능, 기다릴 수 없는 고객 피해, 두 사람이 함께 함이다. 콘솔 환불 뒤에는 `refund resolve`가 PG API로 환불 완료를 다시 확인한 뒤에만 `REFUNDED`·`PaymentRefunded`를 반영하고 콘솔 조치를 `external_ref`로 남긴다. 콘솔 환불 뒤 `refund retry`는 쓰지 않는다.
12. **0019의 "관리자 재처리 API 없음"과의 관계**: HTTP API는 여전히 만들지 않는다. 외부·내부 네트워크에 새 공격 표면을 열지 않는다는 이유가 그대로다. 이 ADR은 0019의 "SQL 템플릿으로 DB를 고친다"만 대체한다.

## 검토한 대안

- **검증된 SQL 템플릿(0019 원안)**: 구현이 가장 작다. 전이별 이벤트·이력·감사를 사람이 맞춰야 해 누락과 알림만 끄는 종결을 막지 못한다. 사용자가 금지했다.
- **관리자 HTTP API(Gateway 경유, ADMIN 역할)**: 원격에서 쓰기 쉽고 인증이 이미 있다. 외부에 노출된 경로로 돈을 움직이게 되고, ADMIN 토큰 하나가 탈취되면 복구 명령을 쓸 수 있다. 4-eyes를 HTTP로 구현하려면 승인 대기 상태와 화면이 필요하다.
- **내부 전용 HTTP(`/internal/**` 또는 관리 포트)**: 외부 노출은 없다. 내부망의 어떤 컨테이너든 호출할 수 있고(0008 T1·T5), 서비스 간 인증이 없어 별도 토큰이 필요하다.
- **Kafka-UI로 메시지 재발행**: 도구가 이미 있다. 감사 기록이 없고 이벤트 위조 경로가 된다(Kafka-UI는 READONLY로 둔다, 0019).

## 트레이드오프

| | 얻는 것 | 감수하는 것 |
|---|---|---|
| 일회성 명령행 실행 | 새 네트워크 표면이 없고, 운영 코드와 같은 전이 규칙을 쓴다 | 실행 때마다 JVM을 띄운다(EC2 메모리 예산 안에서 잠깐). 호스트 접속이 필요하다 |
| PG 근거가 목표를 정함 | 근거 없는 종결이 불가능하다 | PG가 오래 장애면 수동 종결도 멈춘다(그동안 NMC로 추적) |
| 4-eyes와 계획 해시 | 혼자 하는 실수와 오래된 계획 실행을 막는다 | 운영자 신원이 자기 신고라 공모나 SSH 계정 공유는 막지 못한다 |
| append-only 트리거 | 기록 수정이 DB에서 거부된다 | 같은 DB 계정이 트리거를 끌 수 있다(계정 분리는 후속) |

## 결과

- `manual-recovery.md` 전면 개정, `data-model.md`의 `manual_action_log` 컬럼(`command`, `result`, `plan_hash`, `pg_evidence`, `external_ref`, `approver`)과 트리거, `observability.md`의 `payment_ops_command_total`.
- 0019 개정(SQL 템플릿 문구 제거), SA 변경 요청 S-5·S-22.
- 구현은 M3. M3 일정이 부족하면 `inspect`·`payment resolve`·`refund retry`·`refund resolve`를 먼저 하고 나머지는 같은 마일스톤 안에서 잇는다. 그 사이에도 직접 SQL은 쓰지 않는다.
- 테스트: N21, N30, N31, N32.

## 면접에서 설명할 한 줄

운영자 복구를 SQL이 아니라 감사되는 명령으로만 하게 하고, 목표 상태를 사람이 아니라 PG 조회 결과가 정하게 해서 "알림만 끄는 복구"와 이벤트 누락을 구조적으로 막았으며, 감사 테이블은 DB 트리거로 수정을 거부하게 했다.
