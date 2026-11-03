# 수동 복구 절차

알림이 울렸을 때 원인을 확인하고 복구하는 절차다. 운영자는 결제·환불·Outbox·`dead_letters`의 상태를 SQL로 직접 바꾸지 않고, 감사 기록이 남는 Ops 명령으로만 바꾼다([0028](../../decisions/0028-payment-ops-commands.md)). 지키는 조건은 INV-7이다([invariants.md](invariants.md)). `inspect`와 `outbox requeue`는 구현했고(ISE-158) 로컬 테스트 DB에서 jar를 `ops` 프로필로 실행해 확인했다. EC2의 compose 환경에서는 실행해 확인하지 않았다. `payment resolve`·`refund resolve`(ISE-159), `refund retry`·`dead-letter resolve`(ISE-160)는 구현 전이다.

## 1. 원칙과 Ops 명령

- 상태를 바꾸는 SQL(UPDATE·INSERT·DELETE)을 쓰지 않는다. 읽기 SQL은 원인 조사에 쓸 수 있지만 먼저 `inspect`를 쓴다.
- 운영자는 목표 상태를 고르지 않는다. 명령이 실행 시점에 PG를 다시 조회해 근거에 맞는 전이만 한다. PG를 조회하지 못하면 아무것도 바꾸지 않는다.
- `NEEDS_MANUAL_CHECK`를 알림을 끄려고 닫지 않는다. 닫는 전이는 PG 근거가 있을 때만 있고, 전이마다 정해진 이벤트·이력·감사 기록이 같은 트랜잭션에 남는다(아래 표).
- 모든 명령은 dry-run이 기본이다. dry-run은 대상의 현재 상태, PG 근거, 바뀔 전이, 발행할 이벤트, 계획 해시를 출력한다. `--apply --confirm <계획 해시>`로 실행하며, 그사이 상태나 PG 근거가 바뀌었으면 거부한다.
- 돈이나 이벤트가 움직이는 명령은 4-eyes다. `--operator`와 다른 `--approver`가 있어야 하고 둘 다 설정의 허용 목록에 있어야 한다.
- 실행은 EC2 호스트의 SSH 세션에서 `payment-service` 이미지를 `ops` 프로필로 한 번 띄우는 방식이다. 웹 서버·Kafka 소비자·스케줄러·Outbox 릴레이·환불 워커·정리 작업·Gauge 갱신·Eureka 등록은 띄우지 않고(`application.yml`의 `on-profile: ops` 문서), 명령 하나를 실행하고 종료한다. Ops JVM은 Outbox 행을 쓰기만 하고 실제 발행은 떠 있는 결제 서비스의 릴레이가 하므로 Kafka에 연결하지 않는다.

### 실행 방법 (확정)

```bash
docker compose run --rm --no-deps -e SPRING_PROFILES_ACTIVE=local,ops payment-service ops <명령> [--옵션=값 ...]
```

- 인자 형식은 `ops <명령> [--옵션=값 ...]`이다. 명령은 한 단어(`inspect`)나 두 단어(`outbox requeue`)다. 옵션 이름은 Spring 설정 키와 겹치지 않게 `operator`, `approver`, `apply`, `confirm`, `reason`, `payment-id`, `refund-id`, `reservation-id`, `outbox-id`, `with-pg`로 둔다. 모르는 옵션은 오타를 막으려고 거부한다.
- `--no-deps`는 이미 떠 있는 config-server·postgres-payment를 쓰고 의존 서비스를 새로 띄우지 않는다는 뜻이다. 설정 서버가 떠 있어야 허용 목록을 받는다.
- 종료 코드: `0` 성공(dry-run 계획 출력 또는 적용), `2` 거부(통제 위반·근거 불일치·해시 불일치·조건부 UPDATE 0행·대상 없음), `1` 오류(사용법 오류·예외).
- 결과는 사람이 읽는 평문이고 `== ops <명령> ==` 줄 아래에 나온다. Spring 기동 로그도 같은 표준 출력에 섞여 나온다(로그 설정은 바꾸지 않았다).
- 허용 목록은 `config-repo/payment-service.yml`의 `payment.ops.operators`다(팀원 ID: hyoseung, junhyun, kangmin, kwonwoo, youngeun). `--operator`는 필수이고 목록에 있어야 한다. 이 ID는 자기 신고이고 증명이 아니라서 감사 기록의 정확성은 호스트의 SSH 접근 통제에 기댄다.
- 상태를 바꾸는 명령은 dry-run이 기본이다. 적용은 `--apply --confirm=<계획 해시> --reason=<메모>`이고 4-eyes 명령은 `--approver=<승인자>`를 더한다. 적용 때 계획을 다시 만들고(DB를 다시 읽고, PG 근거가 있는 명령은 다시 조회) 해시가 다르면 거부한다.
- 계획 해시는 명령 이름·대상·현재 상태 스냅숏·PG 근거 요약·계획한 전이를 정규화한 문자열의 SHA-256(소문자 16진수 64자)이다. 실행자·승인자는 넣지 않는다.
- `manual_action_logs`에는 `--apply` 시도의 결과만 남는다(`APPLIED`는 상태 변경과 같은 트랜잭션, `REJECTED`는 별도 트랜잭션). dry-run과 `inspect`는 남기지 않는다. `--operator`가 없으면 누구의 시도인지 알 수 없어 거부만 하고 기록하지 않는다.
- 거부 기록의 `plan_hash`는 다시 만든 계획의 해시다. 계획을 만들기 전에 거부한 통제 위반(허용 목록 밖, 승인자 없음·같음, 메모·확인 값 없음)과 계획을 만들 수 없는 거부(대상 없음)는 사용자가 준 `--confirm` 값, 형식이 틀리면 64자 0이다. 자격이 없는 요청이 PG 조회를 일으키지 않게 하려고 통제 위반은 계획을 만들기 전에 거부한다.

### 출력 예 (로컬 테스트 DB, 실제 실행 결과)

`inspect`(결제 하나. `--with-pg`를 더하면 마지막에 `[PG 대조]`가 붙고 PG를 조회하지 못해도 종료 코드는 0이다):

```text
$ ops inspect --operator=alice --payment-id=27
== ops inspect ==
예매 키: 9900001
[결제] id=27 번호=PAY-DEMO1 예매=9900001 사용자=7
  상태=APPROVED 금액=50000 KRW 방식=CARD
  PG 주문=08ee5aca-7450-4566-bf64-247c42257216 PG 거래=pg-tx-demo1 PG 승인 금액=50000
  사유(decline_code)=- 재확인 횟수=0 다음 확인=-
  승인=2026-11-03T02:14:10.915065Z 환불=- 생성=2026-11-03T02:14:10.915065Z 수정=2026-11-03T02:14:10.915065Z
[상태 이력] 2건
  #5 2026-11-03T02:14:10.916333Z PAYMENT - -> REQUESTED 사유=- actor=SYSTEM
  #6 2026-11-03T02:14:10.917185Z PAYMENT REQUESTED -> APPROVED 사유=- actor=SYSTEM
[환불] 없음
[Outbox] 2건
  id=3 상태=FAILED 시도=5 이벤트=PaymentApproved 토픽=payment.result 다음 시도=2026-11-03T02:14:10.917415Z 오류=RecordTooLargeException: 메시지가 너무 큼
  id=4 상태=PENDING 시도=0 이벤트=PaymentRefunded 토픽=payment.result 다음 시도=2026-11-03T02:14:10.917894Z 오류=-
[dead_letters] 0건
[Ops 기록] 0건
[대사 불일치] 아직 조회하지 않는다(대사 테이블 없음, ISE-43)
```

`outbox requeue` dry-run:

```text
$ ops outbox requeue --operator=alice --outbox-id=3
== ops outbox requeue ==
[dry-run] 상태를 바꾸지 않았다
대상: OUTBOX 3
현재 상태: status=FAILED attempts=5 eventId=1be2982c-6a39-4626-be73-e8739d56f857 eventType=PaymentApproved topic=payment.result key=9900001 lastError=RecordTooLargeException: 메시지가 너무 큼
바뀔 전이: FAILED -> PENDING (attempts 유지, next_attempt_at=실행 시각, claimed_until=NULL, last_error 유지)
발행할 이벤트: PaymentApproved 재발행(릴레이가 같은 eventId로 발행)
이벤트: PaymentApproved 토픽=payment.result 키=9900001
마지막 오류: RecordTooLargeException: 메시지가 너무 큼
같은 키에서 이 행 뒤에 막혀 있는 PENDING: 1건
계획 해시: 3cb74212f8849b70566956ab2115ee71b8d802ace7c15d7a61abc9fca8a55229
적용하려면 같은 인자에 --apply --confirm=3cb74212f8849b70566956ab2115ee71b8d802ace7c15d7a61abc9fca8a55229 --reason=<메모>를 붙인다
```

`outbox requeue` 적용(종료 코드 0). 같은 인자를 다시 실행하면 행이 이미 `PENDING`이라 `거부: FAILED인 행만 되살립니다. 현재 상태는 PENDING입니다`와 종료 코드 2로 끝나고 `REJECTED`가 남는다:

```text
$ ops outbox requeue --operator=alice --outbox-id=3 --apply --confirm=3cb74212...a55229 --reason="메시지 크기 제한을 늘렸다"
== ops outbox requeue ==
적용했다: OUTBOX 3
전이: FAILED -> PENDING (attempts 유지, next_attempt_at=실행 시각, claimed_until=NULL, last_error 유지)
발행한 이벤트: PaymentApproved 재발행(릴레이가 같은 eventId로 발행)
계획 해시: 3cb74212f8849b70566956ab2115ee71b8d802ace7c15d7a61abc9fca8a55229
감사 기록: APPLIED (실행자 alice)
```

해시가 다르면 `거부: 계획 해시가 확인 값과 다릅니다. 상태나 근거가 바뀌었을 수 있으니 dry-run을 다시 실행한다`(종료 코드 2, `REJECTED`), 허용 목록 밖의 실행자는 `거부: 허용 목록에 없는 실행자입니다: mallory`(종료 코드 2, dry-run이라 기록하지 않음)이다.

| 명령 | 대상 | 하는 일 | PG | 4-eyes |
|---|---|---|---|---|
| `inspect` | 결제·환불·예매 ID | 상태, 이력, 환불, Outbox, `dead_letters`, Ops 기록, 대사 불일치를 묶어 출력. `--with-pg`면 PG 조회 결과와 대조(구현, 대사 불일치는 ISE-43에서 연결) | 조회(선택) | 아니오 |
| `payment resolve` | 결제 `NEEDS_MANUAL_CHECK` | PG 근거로 `APPROVED`(승인, 금액 일치), `DECLINED`(거절, 또는 미발견 + 늦은 승인 방지 성공), 환불 시작(승인, 금액 불일치) 중 하나 | 조회, 늦은 승인 방지 | 예 |
| `refund retry` | 환불 `NEEDS_MANUAL_CHECK` | 원인이 풀린 뒤 `REQUESTED`로 되돌려 자동 환불 워커가 이어받게 함(워커는 조회부터 한다) | 조회 | 예 |
| `refund resolve` | 환불 `NEEDS_MANUAL_CHECK` | PG가 환불 완료면 `REFUNDED`. PG가 거절이고 `--close-failed --external-ref`가 있으면 `FAILED` | 조회 | 예 |
| `outbox requeue` | Outbox `FAILED` | 원인을 고친 뒤 `PENDING`으로 되돌림(구현) | 없음 | 아니오 |
| `dead-letter resolve` | `dead_letters` `OPEN` | `--replay`: 원인을 고친 뒤 DLQ 처리기와 같은 검증·영속화를 다시 실행. `--no-obligation`: 환불 의무가 없음을 확인하고 닫음 | 없음 | 예 |

전이별로 함께 쓰는 것(모두 한 트랜잭션, 조건부 UPDATE가 1행일 때만):

| 명령 결과 | 상태 변경 | 이벤트(Outbox) | 이력 | 감사 |
|---|---|---|---|---|
| `payment resolve` → 승인 | 결제 NMC → `APPROVED` | `PaymentApproved` | 결제 | APPLIED + PG 근거 |
| `payment resolve` → 거절 | 결제 NMC → `DECLINED` | `PaymentDeclined` | 결제 | 같음 |
| `payment resolve` → 환불 시작 | 환불 `REQUESTED` 생성(`reason=PG_AMOUNT_MISMATCH`, 금액 = PG 승인 금액) | 없음(환불 완료 때 `PaymentRefunded`) | 환불 | 같음 |
| `refund retry` | 환불 NMC → `REQUESTED` | 없음 | 환불 | 같음 |
| `refund resolve` → 완료 | 환불 NMC → `REFUNDED`, 결제 → `REFUNDED` | `PaymentRefunded` | 환불·결제 | 같음 |
| `refund resolve` → 종결 | 환불 NMC → `FAILED` | 없음 | 환불 | APPLIED + 외부 조치 참조 |
| `outbox requeue` | Outbox `FAILED` → `PENDING`(`attempts`·`last_error` 유지, `next_attempt_at`=지금, 임대 비움) | 기존 이벤트 재발행(릴레이가 발행) | 없음 | APPLIED |
| `dead-letter resolve --replay` | `dead_letters` → `RESOLVED(REPLAYED)`, 환불 `REQUESTED` 생성 | 없음 | 환불 | APPLIED |
| `dead-letter resolve --no-obligation` | `dead_letters` → `RESOLVED(NO_OBLIGATION)` | 없음 | 없음 | APPLIED + 근거 메모 |

거부(근거 불일치, 0행, 4-eyes 미충족, 해시 불일치)는 `manual_action_logs`에 REJECTED로만 남는다.

## 2. NEEDS_MANUAL_CHECK (결제·환불, 금액 불일치 포함)

1. `inspect --with-pg`로 대상과 PG 상태를 본다. 결제는 `pg_order_id`, 환불은 `pg_refund_id`와 `pg_tx_id`로 조회된다.
2. 결제 NMC는 `payment resolve`를 dry-run으로 실행해 PG 근거와 계획을 확인한 뒤 승인자와 함께 apply한다.
   - `APPROVED`가 되면 `PaymentApproved`가 나간다. 이 시점에 예매는 대개 만료돼 있으므로 Reservation이 `RefundRequested`를 보내고 자동 환불로 이어진다(0027).
   - 금액 불일치(`PG_AMOUNT_MISMATCH`)는 예매를 확정하지 않고 PG 승인 금액 전액을 환불한다.
   - PG가 아직 불확실(`Unknown`)이면 명령은 거부되고 상태는 그대로다. PG 장애가 풀린 뒤 다시 한다.
3. 환불 NMC는 사유로 나눈다.
   - `REFUND_UNRESOLVED`(PG 장기 장애), `STATE_CONFLICT`: 원인이 풀렸으면 `refund retry`.
   - `REFUND_REJECTED`: PG가 거절한 이유를 확인한다. 고칠 수 있으면 고치고 `refund retry`. PG로 환불할 수 없으면 고객 계좌 환불 같은 외부 조치를 하고 `refund resolve --close-failed --external-ref <조치 참조>`.
   - 이미 PG에서 환불돼 있으면 `refund resolve`로 `REFUNDED` 반영.
   - `REFUND_AMOUNT_MISMATCH`: PG의 실제 환불 금액을 확인하고 담당자와 원인을 찾은 뒤 위 중 하나로 처리한다.
4. 처리 뒤 다음 대사에서 해당 예매의 불일치가 없어졌는지 확인한다.

## 3. dead_letters OPEN·DLQ 처리기 실패

1. `inspect`로 `dead_letters`의 사유(검증 실패 항목, 역직렬화 실패)와 원문을 본다.
2. DB 장애로 처리기가 재시도 중이면 DB를 복구한다. 처리기는 오프셋을 커밋하지 않고 기다렸다가 이어서 처리한다.
3. 검증 실패 건은 위조·버그·데이터 불일치를 가린다. 결제와 예매를 함께 확인한다.
   - 정당한 환불 요청인데 데이터·코드 문제로 실패했으면 원인을 고친 뒤 `dead-letter resolve --replay`.
   - 환불 의무가 없음이 확인되면(이미 환불됨, 존재하지 않는 결제 등) `dead-letter resolve --no-obligation`과 근거 메모. 실제 의무가 남아 있었다면 대사 RC-2가 다시 찾는다.
4. `.dlq` 토픽 보관 기간(7일`[가정]`) 안에 처리한다. `dead_letters`에 원문이 있으므로 토픽이 지워져도 근거는 남는다.

## 4. Outbox FAILED·발행 지연

1. `last_error`로 영구 오류인지 일시 오류인지 구분한다. 일시 오류는 Kafka 복구를 확인한다(자동 재시도).
2. 영구 오류는 원인(직렬화, 토픽 설정)을 고친 뒤 `outbox requeue`(구현, ISE-158).
   1. `inspect --reservation-id=<예매 ID>`로 그 예매 키의 Outbox 행(상태, `attempts`, `last_error`)을 본다. `FAILED` 행의 id를 확인한다.
   2. `outbox requeue --operator=<나> --outbox-id=<id>`를 dry-run으로 실행한다. 이벤트 종류·키·`last_error`와 같은 키에서 이 행 뒤에 막혀 있는 `PENDING` 건수가 나온다. 같은 키에 앞선 `FAILED`가 더 있으면 경고가 나오고, 그 행을 먼저 되살려야 이 행이 나간다(키별 순서, ADR 0022).
   3. 원인을 고친 것을 확인하고 `--apply --confirm=<계획 해시> --reason=<메모>`로 적용한다. 1인 명령이라 `--approver`가 없다. `FAILED`가 아닌 행(이미 `PENDING`·`SENT`)은 거부된다.
   4. 이 명령은 행의 상태만 되돌린다. 떠 있는 결제 서비스의 릴레이가 같은 `eventId`로 발행하고, 같은 키의 뒤 행도 순서대로 따라 나간다. `inspect`로 상태가 `SENT`가 되었는지 확인한다. 소비자는 멱등이라 이미 처리한 이벤트가 다시 와도 한 번만 반영된다.
   5. 다시 `FAILED`가 되면 원인이 고쳐지지 않은 것이다. 같은 방식으로 반복하기 전에 `last_error`를 다시 본다.
3. 발행 지연은 `PENDING` 최고령과 `outbox_relay` 작업의 마지막 성공 시각, 브로커 상태를 본다.

## 5. 미확정 체류와 PG Circuit Breaker OPEN

1. `REQUESTED` 최고령 경고: 재확인 스케줄러(`payment_recheck`)가 도는지와 실행기 정체를 본다.
2. `UNKNOWN`·환불 대기 장기 체류: `pg-recovery` CB 상태와 PG 응답을 본다. PG 장애 중에는 시도 횟수가 줄지 않으므로 PG가 복구되면 자동으로 이어진다. 끝내 확정하지 못하면 `NEEDS_MANUAL_CHECK`가 되어 2절로 간다.
3. `pg-approve` OPEN: 신규 결제는 503으로 거절되고 기존 결제 조회·재확인·환불은 계속된다. PG 복구를 기다린다.

## 6. 결제·예매 대사 불일치

| 규칙 | 먼저 볼 것 | 복구 |
|---|---|---|
| RC-1 승인 미확정 | Reservation의 `payment.result` 소비 랙·DLQ, 만료 스케줄러 | 반효승과 Reservation 쪽 처리. 예매가 만료되면 RC-2 경로로 환불된다 |
| RC-2 환불 의무 미등록 | Reservation의 `RefundRequested` 발행 여부, Payment `dead_letters` | 원인 쪽에서 `RefundRequested`를 다시 내게 한다. Payment 쪽 원인이면 `dead-letter resolve --replay` |
| RC-3 환불 뒤 확정 유지 | 예매 취소 근거, Reservation의 이상 격리 기록(0027) | 반효승과 확인. 자동 좌석 해제 금지 |
| RC-4 미승인 확정 | 예매의 `payment_id`, 결제 존재 여부 | 반효승과 확인(위조·버그 의심) |
| RC-5 금액, RC-6 사용자 불일치 | 결제·예매·토큰 발급 기록 | 원인 확인 뒤 담당자와 처리 |
| RC-7 상태·이력 불일치, RC-8 이벤트 누락 | 직접 SQL 실행 흔적, 배포 이력 | 원인 확인. 누락 이벤트는 해당 전이의 Ops 명령 절차로 보충하는 방법을 담당자와 정한다 |

대사는 데이터를 고치지 않는다. 다음 대사에서 일치하면 불일치가 `RESOLVED`가 된다.

## 7. 작업 정체·DB 연결 대기

1. `payment_job_last_success_timestamp`로 멈춘 작업을 찾고 실행기 스레드 덤프·로그를 본다.
2. 정리 작업은 `pg_try_advisory_lock` 점유(다른 인스턴스), 배치 실패 로그, 테이블 크기를 본다. 멱등이라 원인을 고친 뒤 다음 주기에 이어진다.
3. 대사 정체는 예매 내부 API 응답을 본다.
4. Hikari 연결 대기 초과는 풀 크기(SA 9-4의 20), 오래 걸리는 트랜잭션, 배경 작업의 연결 점유를 본다.

## 8. 긴급 PG 콘솔 환불 (예외 절차)

정상 운영에서는 PG 콘솔에서 직접 환불하지 않는다. 우리 `pg_refund_id` 멱등키 없이 환불이 일어나 이중 환불과 기록 누락의 원인이 된다.

예외 조건(모두 충족): 환불이 `NEEDS_MANUAL_CHECK`라 자동 경로가 멈춰 있다, PG API 경로로는 환불할 수 없고 콘솔만 된다, 고객 피해가 커서 기다릴 수 없다, 두 사람이 함께 한다.

1. 콘솔 환불 전에 `inspect --with-pg`로 PG에 환불 기록이 없음을 확인한다.
2. 콘솔에서 전액 환불하고 PG 화면의 거래·취소 식별자를 기록한다.
3. `refund resolve`를 실행한다. 명령은 PG API로 환불 완료를 다시 확인한 뒤에만 `REFUNDED`와 `PaymentRefunded`를 반영하고, 콘솔 환불 사실을 `external_ref`로 남긴다.
4. PG API로 확인할 수 없으면 명령은 거부되고 환불은 `NEEDS_MANUAL_CHECK`로 남는다. API가 복구된 뒤 3단계를 다시 한다.
5. 콘솔 환불 뒤에는 `refund retry`를 쓰지 않는다.

## 9. 결제 토큰 키 유출
1. 새 `PAYMENT_TOKEN_SECRET`(32바이트 난수의 16진수 64자)을 만들어 Reservation과 Payment에만 주입한다([0025](../../decisions/0025-payment-token-secret-injection.md)).
2. 두 서비스를 함께 재배포하면 이전 키로 서명한 토큰은 검증에 실패한다(결제 토큰 수명은 최대 4분 50초). 진행 중인 사용자는 새 토큰을 받아 다시 결제한다.
3. 유출 시점 이후의 승인 요청과 비정상 결제를 조회해 확인하고, 환불이 필요하면 2절 절차를 따른다.
4. 키는 로그·저장소에 남기지 않고, 회전 사실과 처리자는 `manual_action_logs`가 아닌 운영 기록에 남긴다.
