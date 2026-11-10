# 11/6 MVP 시연 시나리오

상태: 초안(2026-11-05 작성, 2026-11-06 코드와 로컬 리허설 기준으로 수정). 서버 추첨 결과는 [서버 추첨 리허설 기록](../../test-scenarios/runs/2026-11-06-server-lottery.md)에 있다. D-02\~D-05, F-01\~F-10, L-01\~L-08은 로컬 결과가 5절에 있고, 서버 기록이 없는 장면은 서버 리허설에서 다시 확인한다. 각 장면의 기대 결과는 코드, 설계 문서, 로컬 실행 기록을 근거로 쓴 것이고 리허설에서 확정한다. 진행은 통합 진행자 1명이 맡는다([판단 기록](../../ai-judgments/kangmin/2026-11-05-demo-single-integrated-presenter.md)).

시연 장면 밖의 시나리오 전체는 [테스트 시나리오](../../test-scenarios/README.md)에 있다.

## 1. 시연 방식

- 화면: `http://52.63.10.174/?demo=on`. 요청마다 traceId가 시연 패널에 남는다.
- 관측: traceId를 눌러 Grafana `요청 로그` 대시보드를 연다. 장애 장면은 `결제` 대시보드도 함께 본다.
- DB: 장면마다 3절의 쿼리로 저장 결과를 확인한다.
- 장애: 정상 흐름만 보이지 않고 장애 상황을 현장에서 주입한다(4절).
- 시간: 길이 제한이 없다. 발표용 핵심 장면 S1~S10은 개인 Notion 「MVP 시연 사전 준비자료」에 튜터 양식으로 쓰고, 나머지는 이 문서와 그 하위 페이지 「MVP 시연 전체 테스트 시나리오」에 둔다.

| 순서 | 장면 | 이유 |
|---|---|---|
| 1 | S1~S4 (D-02~D-05) 일반 예매 | 정상 흐름을 먼저 보인다 |
| 2 | S5~S7 (F-01, F-04, F-06) 일반 예매 장애 | 정상 흐름과 비교한다 |
| 3 | S8~S10 (L-01, L-02, L-04~L-06) 추첨 | 일반 결제 장애 주입이 끝난 뒤 자동결제를 돌려 섞이지 않게 한다 |
| 4 | L-08 추첨 종결 | 추첨 약 31분 뒤에 결과를 확인한다(L-08 참고). 시간이 맞지 않으면 로컬 기록으로 대체한다 |
| 5 | 그 밖의 항목, F-08~F-10 | 시간이 남으면 진행한다. 선택 장면은 로컬 리허설을 통과한 것만 넣는다 |

## 2. 사전 준비

### 2-1. 순서

배포가 끝난 뒤 데이터를 쌓는다. 재배포하면 Mock PG 장애 설정과 빌링키가 사라지고(메모리 보관, 2-2 참고), 재기동 뒤 약 35초 동안 503이 날 수 있다(2026-11-04 첫 배포 기록). 11/5 릴리스는 머지에서 배포 완료까지 4분 35초 걸렸다.

| 순서 | ID | 할 일 | 완료 확인 |
|---|---|---|---|
| 1 | P-01 | 반영할 변경을 `dev`에 머지하고 로컬 테스트 뒤 `main`에 머지 | `images` 성공, Jenkins 배포 성공 |
| 2 | P-02 | 스모크 실행, 서버 커밋 확인 | 4개 통과, `.deploy/current`가 머지 커밋과 같음 |
| 3 | P-03 | 시연 시간까지 `main` 머지를 멈추도록 팀에 공지. 장애 주입 시간도 함께 공지 | 공지 완료 |
| 4 | P-04 | 시연 장소 공인 IP를 서버 담당에게 등록 요청 | SSH 접속과 Grafana 열림 |
| 5 | P-05 | 관리자 계정 확보. `.env`를 직접 열지 않고 서버 담당에게 받는다 | 관리자 로그인 |
| 6 | P-06 | 추첨용 회차 3개 준비(리허설, 시연, 예비) | 회차별 `openAt`이 미래 |
| 7 | P-07 | 응모자 계정 적재(2-2). P-01 배포가 끝난 뒤에 실행한다. 적재 뒤 다시 배포했으면 2-2의 카드 재등록을 한다 | `users`와 `billing_keys` 건수. 건수로는 Mock PG의 빌링키 유실을 알 수 없다 |
| 8 | P-08 | 로컬에서 F-01~F-10 리허설, 이어서 서버 리허설 | 장면별 결과를 5절에 기록 |

- 추첨은 회차당 1개이고, 일반 오픈 전 회차에만 설정할 수 있다. 지금 서버의 회차 1·2는 오픈이 지나 쓸 수 없다.
- 회차는 관리자 화면이나 `script/deploy/seed-demo.sh open <OPEN_AT>`로 만든다. 공연 시작은 추첨 시각 + 60분 뒤여야 한다. 15,000석 생성 시간은 서버에서 재지 않았다.
- 추첨 회차의 `openAt`은 시연 다음 날 이후로 둔다. 시연 도중 일반 오픈이 지나 설정이 막히는 일을 피한다.

### 2-2. 추첨 응모자 적재

응모자가 쌓인 추첨을 모의 진행한다. 숫자는 제안값이고 근거가 있는 기준이 아니다.

| 항목 | 제안값 | 이유 |
|---|---|---|
| 응모자 | 120명 | 추첨 경쟁과 승계가 보이면서 준비 부담이 작다 |
| 추첨석 / 예비 | 30석 / 15명 | 당첨, 예비, 미당첨이 모두 나온다 |
| 매수 | 1매 90명, 2매 30명 | 2매 배정도 보인다 |
| 카드 미등록 | 12명 | 현장에서 거절과 승계가 일어난다 |

| 방식 | 장점 | 단점 | 판단 |
|---|---|---|---|
| 공개 API로 적재 | 응모 규칙, 멱등, 카드 암호화, 지표가 실제와 같다. 자동결제까지 이어진다 | 가입에 시간이 든다. 건수를 크게 늘리기 어렵다 | 채택 |
| DB에 직접 INSERT | 수만 건도 빠르다 | 카드가 없어 당첨자 자동결제가 전부 거절된다. 공유 서버 DB에 쓰기를 한다 | 제외 |

- 가입, 로그인, 카드 등록은 시연 전에 해 둔다. 응모는 응모 기간에만 받으므로 L-02에서 넣는다.
- 추첨 설정은 응모 시작 전에만 고칠 수 있다. L-01을 현장에서 하면 시연 시각이 밀려도 시각을 다시 맞출 수 있다.
- 적재 스크립트는 `script/deploy/seed-lottery-entrants.sh`다. `prepare [주소]`가 가입, 로그인, 카드 등록을 하고, `enter <추첨 id> [주소]`가 응모한다. 다시 실행해도 안전하다.
- 기본 주소는 로컬 nginx(`http://127.0.0.1:8000`)다. loopback이 아닌 주소는 `LOTTERY_SEED_ALLOW_REMOTE=yes`가 있어야 요청을 보낸다.
- 계정 비밀번호는 시연 전용 임의 값이다. 저장소 밖 상태 디렉터리(`~/.iseonjwa/lottery-seed/<호스트_포트>/password`)에 두고 커밋하지 않는다.
- 만든 데이터를 지우는 API는 없다. 다음 주 측정 전에 정리할지 서버 담당과 정한다.

**시연 전 주의: Mock PG가 빌링키를 메모리에 보관해 재기동하면 DB에는 `ACTIVE`인데 자동결제가 `BILLING_KEY_MISSING`이 된다. 서버 배포 뒤에는 카드를 다시 등록해야 한다.** 코드 근거와 로컬 재현은 다음과 같다. 서버에서는 재현하지 않았다.

| 단계 | 동작 | 근거 |
|---|---|---|
| Mock PG 재기동 | 발급 목록이 비워진다 | `mock-pg`의 `BillingKeyStore`(메모리 `Set`) |
| 배포 | 커밋마다 `IMAGE_TAG`가 달라 `mock-pg` 이미지도 바뀐 채 `compose up`한다 | `compose.deploy.yml`, `script/deploy/deploy.sh` |
| 자동결제 | DB의 `ACTIVE` 키로 PG를 불러 `BILLING_KEY_INVALID`를 받고 `BILLING_KEY_MISSING`으로 거절한다 | Mock PG `PaymentService`, `MockPgAdapter` |
| 다시 적재 | 가입 409이면서 `carded`에 있는 계정은 카드 등록 없이 건너뛴다 | `seed-lottery-entrants.sh`의 `prepare_one` |

적재 뒤 다시 배포했으면 다음 순서로 카드만 재등록한다.

1. 상태 디렉터리(`~/.iseonjwa/lottery-seed/<호스트_포트>/`, `LOTTERY_SEED_STATE_DIR`를 썼으면 그 경로)에서 `carded` 파일만 지운다.
2. `password`와 `plan`은 남긴다. `password`를 지우면 새 임의 값이 만들어져 기존 계정 로그인이 401로 멈춘다.
3. 같은 주소로 `prepare`를 다시 실행한다. 기존 계정은 가입 409 뒤 로그인해 카드를 등록하고, 미등록 계정은 로그인만 한다.
4. 화면에서 카드를 등록한 계정(발표자 등)은 스크립트 대상이 아니므로 화면에서 다시 등록한다.

재등록하면 결제 서비스가 기존 키를 `REVOKED`로 바꾸고 새 키를 `ACTIVE`로 저장해 계정당 활성 키는 1개다(`BillingKeyService.register`). 로컬에서 108개 재등록에 82초 걸렸고 `REVOKED`가 108건 늘었다(2026-11-06).

### 2-3. 시연 계정

추첨 순위는 `SHA-256(seed ‖ entryId)`로 정해져 특정 계정을 당첨시킬 수 없다. 추첨 뒤 3절 쿼리로 결과를 보고 해당 계정으로 로그인한다.

| 계정 | 용도 |
|---|---|
| 발표자 | L-02 현장 응모, 일반 예매 |
| 경쟁자 | F-01 이선좌. 다른 브라우저 프로필로 연다 |
| 추첨 결과 확인 3개 | 당첨·결제 완료, 카드 미등록 실패, 미당첨. 추첨 뒤 고른다 |

## 3. DB 확인

서버에서 읽기 전용으로 실행한다. 컨테이너 안의 환경 변수를 쓰므로 `.env`를 읽지 않는다. 컨테이너 이름은 `iseonjwa-<서비스>-1` 형식으로 보이며 리허설 전에 `docker ps`로 확인한다.

```bash
# 예약 DB. payment는 컨테이너 이름만 postgres-payment로 바꾼다
docker exec -it -e PGOPTIONS='-c default_transaction_read_only=on' iseonjwa-postgres-reservation-1 \
  sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB"'
```

| 확인 | DB | 쿼리 |
|---|---|---|
| 예매와 좌석 | reservation | `SELECT r.id, r.status, r.source, r.hold_expires_at, rs.status AS seat_status, ss.status AS schedule_seat_status FROM reservations r JOIN reservation_seats rs ON rs.reservation_id = r.id JOIN schedule_seats ss ON ss.id = rs.schedule_seat_id WHERE r.id = :id;` |
| 티켓 | reservation | `SELECT t.ticket_no, t.status FROM tickets t JOIN reservation_seats rs ON rs.id = t.reservation_seat_id WHERE rs.reservation_id = :id;` |
| 예매 상태 이력 | reservation | `SELECT * FROM reservation_status_histories WHERE reservation_id = :id ORDER BY id;` |
| 응모 건수 | reservation | `SELECT status, count(*) FROM lottery_entries WHERE lottery_id = :id GROUP BY status;` |
| 응모 결과 | reservation | `SELECT user_id, quantity, status, draw_rank, fail_reason, reservation_id FROM lottery_entries WHERE lottery_id = :id ORDER BY draw_rank NULLS LAST;` |
| 추첨 상태 | reservation | `SELECT status, seat_count, waitlist_count, draw_at FROM lotteries WHERE id = :id;` |
| 회차 좌석 | reservation | `SELECT grade, status, count(*) FROM schedule_seats WHERE schedule_id = :id GROUP BY grade, status ORDER BY 1, 2;` |
| 결제 | payment | `SELECT id, reservation_id, method, status, decline_code FROM payments WHERE reservation_id = :id;` |
| 결제 이력 | payment | `SELECT * FROM payment_status_histories WHERE payment_id = :id ORDER BY id;` |
| 환불 | payment | `SELECT payment_id, reason, status FROM refunds WHERE payment_id = :id;` |
| 이벤트 | 둘 다 | `SELECT status, event_type, count(*) FROM outbox_events GROUP BY 1, 2;` |
| 실패 메시지 | 둘 다 | `SELECT count(*) FROM dead_letters;` |

공통 기대: 시연 전후 `dead_letters`의 증가가 없고, 이벤트 처리가 끝난 뒤 `outbox_events`가 `SENT`다. 장면 시작 전에 건수를 한 번 적어 둔다. 이전 데이터가 이미 있을 수 있어 0을 기대하지 않는다.

## 4. 장애 주입

`mock-pg`는 Gateway 경로가 없어 서버 안에서 호출한다. 이미지에 `curl`이 있다. 설정은 `count`만큼 쓰이면 해제되고, 재기동하면 사라진다.

```bash
# 주입: 본문만 장면별로 바꾼다
docker exec iseonjwa-mock-pg-1 curl -s -X POST http://localhost:8090/mock-pg/faults \
  -H 'Content-Type: application/json' -d '{"target":"APPROVE","mode":"DECLINE","count":1}'
```

```bash
# 해제
docker exec iseonjwa-mock-pg-1 curl -s -X POST http://localhost:8090/mock-pg/faults \
  -H 'Content-Type: application/json' -d '{"target":"APPROVE","mode":"NONE"}'
```

```bash
# 현재 설정 확인
docker exec iseonjwa-mock-pg-1 curl -s http://localhost:8090/mock-pg/faults
```

- `orderId` 없이 건 규칙은 서버의 모든 승인 요청에 적용된다. 그래서 주입 시간을 공지하고, 장면이 끝날 때마다 현재 설정을 확인한다.
- 자동결제가 도는 동안에는 일반 결제 장애를 주입하지 않는다. 자동결제도 같은 승인 API를 써서 주입 횟수를 가져간다.

## 5. 시나리오

결과 칸은 리허설에서 채운다. 아래 결과는 2026-11-06 00:20~01:43(KST) **로컬** 실행이다. 공유 서버에는 요청을 보내지 않았다. 비어 있는 칸은 이번 범위 밖이거나 서버 기록이 없는 장면이다.

리허설 조건은 다음과 같다.

| 항목 | 내용 |
|---|---|
| 기준 커밋 | `0bc8141`로 빌드한 이미지. ISE-200 추첨석 LIMIT 수정(`900f83e`) 전이다 |
| 방법 | 화면 장면은 Chrome 헤드리스(`?demo=on`), 나머지는 `http://127.0.0.1:8000` API, DB는 읽기 전용 `psql` |
| Mock PG 프로필 | 로컬 기본 `local`에서는 장애 API가 404다. 저장소 밖 compose override로 배포와 같은 `loadtest`로 띄웠다 |
| 회차 | F-08만 `holdMinutes=1`. 나머지는 기본 5분 |
| 로컬 DB 쓰기 | L-08은 승계 마감까지 기다리지 않고 DB 시각을 조정했다. 1건: `UPDATE lotteries SET entry_start_at, entry_end_at, draw_at = 각 값 - interval '31 minutes' WHERE id=7` |
| `dead_letters` | 시작과 끝 모두 예매 0건, 결제 0건. outbox 전부 `SENT` |
| 경보 | `RefundNeedsManualCheck`, `RefundManualCheckAging`은 리허설 시작 전부터 활성이다 |
| 실행 기록 | 장면별 근거는 [실행 기록](../../test-scenarios/runs/2026-11-06-rehearsal.md)에 있다 |

L-05 1회차 실패와 재실행: 장애 API를 열려고 Mock PG를 재생성한 뒤 추첨 6을 돌리자 빌링키가 사라져 결제 DB에는 키가 `ACTIVE`인데도 자동결제가 거절됐다. 결과는 `PAID` 2건, `FAILED` 36건(`BILLING_KEY_MISSING` 35건, 주입한 `CARD_DECLINED` 1건)이다. `password`와 `plan`만 복사하고 `carded`가 없는 새 `LOTTERY_SEED_STATE_DIR`로 `prepare`를 다시 실행해 카드 108개를 재등록하고(82초) 추첨 7로 다시 실행했다. 아래 L-01\~L-08 결과는 추첨 7 기준이다. 시연 때 주의할 점은 2-2에 있다.

### 인증·권한

| ID | 무엇을 증명하나 | 절차 | 기대 결과 | 결과 |
|---|---|---|---|---|
| A-01 | 가입 후 로그인해 내 정보를 본다 | 가입 → 로그인 → 내 정보 | 201, 토큰 발급, 200 | |
| A-02 | 토큰 없는 요청은 Gateway가 막는다 | `GET /api/v1/users/me` 토큰 없이 | 401 | |
| A-03 | 로그아웃한 토큰을 거절한다 | 로그아웃 뒤 이전 토큰으로 호출 | 401 `TOKEN_REVOKED` | |
| A-04 | 비밀번호 반복 실패 시 잠긴다 | 시연에 쓰지 않는 계정으로 틀린 비밀번호를 5번 보낸 뒤 맞는 비밀번호로 로그인 | 1~4번째 401 `INVALID_CREDENTIALS`, 5번째 실패 응답부터 423 `ACCOUNT_LOCKED`, 맞는 비밀번호도 423 | |
| A-05 | 일반 사용자는 관리자 API를 못 쓴다 | 일반 토큰으로 `POST /api/v1/admin/concerts` 호출. 이 경로에는 POST만 있다 | 403 `FORBIDDEN` | |

### 일반 예매 정상

| ID | 무엇을 증명하나 | 사전 조건 | 절차 | 기대 결과 | 실패 시 대체 | 결과 |
|---|---|---|---|---|---|---|
| D-01 | 운영자가 판매할 공연을 등록한다 | 관리자 로그인 | 운영 화면에서 회차, 좌석 등록. 오픈은 몇 분 뒤 | 회차가 `BEFORE_OPEN`, 회차 좌석 15,000행 | 미리 만든 오픈 전 회차 | |
| D-02 | 오픈 판단이 브라우저가 아닌 서버 시각을 따른다 | D-01 회차 | 공연 상세에서 카운트다운을 본다 | 0이 되면 새로고침 없이 [예매하기] 활성 | 회차 1로 넘어가 설명 | 통과. 브라우저 시계를 서버보다 1시간 빠르게 둔 채 카운트다운이 `00:01:12`로 서버 기준을 따랐다. 오픈 전 버튼 비활성, 오픈 0.4초 뒤 새로고침 0회로 [예매하기] 활성 |
| D-03 | 입장 토큰을 받아야 좌석에 들어간다 | 로그인 | [예매하기] → 대기열 | `WAITING` → `READY` → 토큰 → 좌석 화면 | 패널 traceId로 응답 확인 | 통과. 화면에서 `POST /queue` 201 → `GET /queue/me` 200 → `POST /queue/token` 200 → `GET /seats` 200, 좌석 화면 진입. 같은 흐름의 API 호출에서 `WAITING` → `READY` 확인. 화면에 긴 대기는 보이지 않음 |
| D-04 | 선점부터 확정까지 한 흐름이 이어지고 DB 상태가 맞다 | 입장 완료 | 좌석 선택 → [선택 완료] → 결제 → 결과 → 내 예매 | 예매 `CONFIRMED`, 결제 `APPROVED`, 좌석별 티켓 `ISSUED`, `outbox_events` `SENT` | 회차 1에서 다시 실행, 로컬 녹화 | 통과. 예매 44: 선점 201 `HELD` → 결제 200 `APPROVED` → 완료 화면 → 내 예매 표시. DB: 예매·좌석 `CONFIRMED`, 회차 좌석 `SOLD`, 결제 `CARD`·`APPROVED`, 티켓 `ISSUED`, 결제 outbox `PaymentApproved` `SENT` |
| D-05 | 요청 하나를 Gateway에서 그 요청을 받은 서비스까지 따라간다 | D-04 완료 | 1. 선점 요청(`POST /api/v1/schedules/{id}/reservations`)의 traceId 클릭 → 요청 로그 → Zipkin 구간에서 gateway와 reservation-service 확인 2. 결제 요청(`POST /api/v1/payments`)의 traceId로 같은 순서를 밟아 gateway와 payment-service 확인 | 요청 하나에 Gateway와 그 요청을 받은 서비스가 같은 traceId를 갖는다. 결제 뒤 예매 확정은 Kafka 비동기라 다른 trace다 | Loki 탐색으로 traceId 검색. 서버 Grafana는 익명 Viewer라 막힐 수 있고, 막히면 서버에서 `docker logs`를 traceId로 검색한다 | 수정 전 기대 결과로는 **실패**. 결제 trace `6aeca0bc0c4d…`는 Gateway·payment만 있다(Zipkin 4구간, Loki 각 1줄, reservation 0줄). 예매 확정은 outbox 릴레이 뒤 다른 trace로 처리되고, 이벤트 본문의 `traceId`에만 원래 값이 남는다. 기대 결과를 설계에 맞게 고쳤다(`#367`). **수정한 절차 전체는 다시 실행하지 않았다.** 선점 trace `6aeca0bcd596…`는 reservation-service 요청 로그에서 확인했고, 같은 trace의 Gateway 로그와 Zipkin 구간은 기록이 없어 미확인이다. 로컬 Grafana는 로그인이 필요해(302) 패널을 보지 못했다 |
| D-06 (E2) | 같은 멱등키 재요청은 결제 1건이다 | HELD 예매 | 같은 키로 결제 재요청 | 같은 `paymentId`, PG 승인 1회 | 통합 테스트 FT-1 결과 제시 | |

D-05 근거: [관측성 설계](../infra/observability.md)의 완료 기준을 읽는 법과 남은 항목의 Kafka 소비자 전파 행이다. 2026-11-06 로컬 확인에서 결제 trace는 Zipkin에 gateway와 payment-service만 나왔고 reservation 로그는 0줄이었다.

참고: 혼자 진행하면 대기열이 금방 끝나 긴 줄은 보이지 않는다. 입장 속도가 초당 50명이기 때문이다. 긴 대기를 보이려면 별도 부하가 필요한데 11/6은 부하 측정을 하지 않기로 했다.

### 일반 예매 장애

| ID | 무엇을 증명하나 | 주입·사전 조건 | 절차 | 기대 결과 | 실패 시 대체 | 결과 |
|---|---|---|---|---|---|---|
| F-01 (E1) | 같은 좌석을 동시에 골라도 한 명만 선점한다 | 입장 완료 계정 2개 | 같은 좌석을 고르고 거의 동시에 [선택 완료] | 1명은 `HELD`, 1명은 409와 재선택 안내. 해당 좌석의 활성 예매 1건 | 먼저 선점된 좌석을 뒤늦게 눌러 409 재현 | 통과. 동시 선점 3회 모두 1명 201 `HELD`, 1명 409 `SEAT_ALREADY_HELD`, 좌석당 활성 예매 1건. 화면: `이미 선택된 좌석입니다` 안내 후 나머지 선택 유지 |
| F-02 (E3) | 회차당 구매 한도를 서버가 지킨다 | 한도까지 보유한 계정 | 추가 선점 | 409 `PURCHASE_LIMIT_EXCEEDED` | 쿼터 쿼리로 설명 | 통과. 4매 선점 201 뒤 1매 추가 409 `PURCHASE_LIMIT_EXCEEDED` |
| F-03 (E4·E5·E14) | 토큰과 시각 검사는 화면이 아니라 서버가 한다 | 오픈 전 회차, 입장 토큰, 결제 토큰 | `curl`로 오픈 전 진입, 토큰 없는 좌석 조회, 토큰을 바꾼 호출 | 오픈 전 409 `SALE_NOT_OPEN`, 나머지 403 | 시스템 테스트 결과 제시 | 통과. 오픈 전 진입 409 `SALE_NOT_OPEN`. 토큰 없는 조회, 바꾼 입장 토큰, 다른 회차 토큰 403 `ENTRY_TOKEN_INVALID`. 바꾼 결제 토큰 403 `PAYMENT_TOKEN_INVALID`, 결제 행 없음 |
| F-04 (E6) | 결제가 거절되면 SAGA 보상으로 좌석이 돌아간다 | `{"target":"APPROVE","mode":"DECLINE","count":1}` | 결제 | 화면 거절 안내, 결제 `DECLINED`, 예매 `RELEASED`, 좌석 판매 가능 | 추첨 카드 미등록 거절(L-06) | 통과. 예매 52: 화면 `결제가 거절됐어요`, 결제 `DECLINED`·`CARD_DECLINED`, 예매·좌석 행 `RELEASED`, 회차 좌석 `AVAILABLE`, 주입 설정 소비됨 |
| F-05 (E7) | 결제하지 않으면 좌석이 자동 반환된다 | 시연 5분 전 선점해 둠. 선점 시간 기본 5분 | 만료 뒤 예매와 좌석 조회 | 예매 `EXPIRED`, 좌석 판매 가능(만료 스케줄러 5초 주기) | 만료 전후 쿼리 기록 제시 | 통과(실제 5분 대기). 예매 43: 만료 시각 1.9초 뒤 `EXPIRED`, 좌석 행 `RELEASED`. 풀린 좌석을 예매 55가 `EXPIRED` 7.8초 뒤 선점해 구매했다 |
| F-06 (E9) | PG 응답이 유실돼도 중복 결제 없이 확정한다 | `{"target":"APPROVE","mode":"DROP_RESPONSE","count":1}` | 결제 | "결제를 확인하고 있어요" 뒤 `UNKNOWN` → `APPROVED` → 확정. `payment_status_histories`에 경과, PG 승인 1건 | 통합 테스트 FT-2 결과 제시 | 통과. 예매 55: 결제 202 `UNKNOWN`, 화면 `결제를 확인하고 있어요`, DB `UNKNOWN` → `APPROVED` 10.5초, 완료 화면 20.2초. `approveCalls` 1, 티켓 `ISSUED`. 3회 실행(예매 53·55·58) 모두 완료 화면까지 갔고, DB는 53·55만 확인했다 |
| F-07 | PG가 느려도 요청이 묶이지 않는다 | `{"target":"APPROVE","mode":"DELAY","delayMs":5000,"count":1}` | 결제. Grafana `승인 지연`, `PG Unknown 원인` 확인 | PG 호출 시한(연결 0.5초, 응답 2초)이 지나면 202와 "결제를 확인하고 있어요"가 나오고, 재확인 뒤 최종 상태로 수렴한다. 2026-11-06 로컬 실측: 202 응답 2,022ms, 확정까지 약 22초 | F-06만 진행 | 통과. API 호출은 2,022ms 뒤 202 `UNKNOWN`이다. 예매 56: `UNKNOWN` → `APPROVED` 10.7초, 완료 화면 22.2초, `approveCalls` 1. Grafana 패널은 화면으로 확인하지 못했다 |

### 추첨

| ID | 무엇을 증명하나 | 사전 조건 | 절차 | 기대 결과 | 실패 시 대체 | 결과 |
|---|---|---|---|---|---|---|
| L-01 | 운영자 설정으로 추첨석이 일반 판매에서 분리된다 | P-06 회차 | 운영 화면에서 응모 1~2분 뒤 시작, 몇 분 응모, 마감 1분 뒤 추첨 | 추첨 번호 발급, 추첨석 수만큼 `LOTTERY` 등급 | 예비 회차로 재설정 | 통과. 운영 화면으로 추첨 7 설정, 201 `OPEN`. 회차 14: `LOTTERY` 정확히 30석, `S` 5,970석. 시간대 안내 표시. 이미지가 `0bc8141`이라 LIMIT 수정 전이며 2회 모두 초과 전환은 없었다 |
| L-02 | 응모가 멱등하게 쌓인다 | L-01, P-07 | 응모 시작 뒤 적재 실행 → 건수 쿼리 → 발표자 화면 응모 | `ENTERED` 건수 증가, 발표자 응모 카드 표시 | 적재 일부 실패 시 성공 건수로 진행 | 통과. 적재 120명 85초 실패 0, `ENTERED` 120건(150매). 발표자 화면 응모 201 `ENTERED`, `응모했어요` 카드 표시. API로 등록한 카드는 화면에 `이 브라우저에서 등록한 카드가 없어요`로 보인다 |
| L-03 | 응모 규칙을 서버가 지킨다 | L-02 | 같은 계정 재응모, 같은 키 재요청, 마감 뒤 응모 | 재응모 409, 같은 키는 처음 응모 반환, 마감 뒤 409 | 로컬 11/5 기록 제시 | 통과. 시작 전·마감 뒤 409 `LOTTERY_NOT_OPEN`. 같은 키 재요청 201에 같은 `entryId`, 새 키 409 `LOTTERY_ALREADY_ENTERED`, 같은 키 다른 내용 409 `IDEMPOTENCY_KEY_REUSED` |
| L-04 | 추첨 시각에 시드 기반으로 순위가 정해진다 | 추첨 시각 경과 | 추첨 + 20초 뒤 응모 결과 쿼리, 화면 확인. 추첨 스케줄러가 앞 실행이 끝난 뒤 10초 간격이고 추첨 한 번의 시한이 10초다. 화면은 10~13초마다 다시 받아 조금 더 늦을 수 있다 | 상태 `DRAWN`, `WON`·`WAITLISTED`·`LOST`로 나뉜다. `draw_rank`는 `WON`·`WAITLISTED`에만 기록되고 `LOST`는 NULL이다 | 추첨 설정 시각 확인 뒤 다음 주기 대기 | 통과. 추첨 4초 안에 `DRAWN`, `WON` 25건(30매), `WAITLISTED` 15건(20매), `LOST` 82건. 순위 1~40 중복 없음 |
| L-05 | 당첨자는 자동결제로 확정된다 | 카드 등록 당첨자 | 당첨자 로그인, 예매 보기 | 응모 `PAID`, 예매 `CONFIRMED`, 결제 `BILLING`·`APPROVED`, 티켓 발급 | DB 결과로 설명 | 통과(2회차). 순위 2: 응모 `PAID`, 예매 `CONFIRMED`, 결제 `BILLING`·`APPROVED`, 티켓 `ISSUED`, 화면 `당첨되고 결제까지 끝났어요`. 순위 14(2매): 티켓 2장. **1회차는 실패**: 36건 `FAILED`(이 절 앞의 재실행 설명 참고) |
| L-06 | 카드가 없으면 예비자에게 넘어간다 | 카드 미등록 당첨자 | 실패 화면, 응모 결과 쿼리 | `FAILED`·`BILLING_KEY_MISSING`, 예약 `RELEASED`, 예비자가 `WON`을 거쳐 `PAID` | 미등록 당첨자가 없으면 L-07 | 통과. 순위 3·5·7: `FAILED`·`BILLING_KEY_MISSING`, 예매 `RELEASED`, 화면 `자동결제가 되지 않았어요`. 빈 자리에 순위 26·27·30이 승계해 `PAID`. 2매인 순위 28·29는 남은 자리가 모자라 건너뜀. 추첨석 30석 모두 판매 |
| L-07 | PG 거절도 같은 경로로 승계한다 | 예비자가 남았을 때 `{"target":"APPROVE","mode":"DECLINE","count":1}`를 추첨 전에 건다 | 결과 쿼리 | `FAILED`·`CARD_DECLINED`, 다음 예비자 승계 | L-06으로 대체 | 통과. 추첨 전 `DECLINE` 주입. 순위 1: `FAILED`·`CARD_DECLINED`, 활성 카드 있음, 예매 `RELEASED`, 주입 소비됨, 같은 승계로 자리 채움 |
| L-08 | 승계 마감 뒤 추첨이 끝나고 남은 좌석이 돌아간다 | 추첨 + 31분 경과. 승계 마감은 추첨 + 30분이고, 예비자 마감 작업이 10초 주기, 종결 작업이 1분 주기라 30분 정각에는 끝나지 않는다. `WON`이 남으면 더 늦어지고, 추첨 + 30분 전에 `WON`·`WAITLISTED`가 모두 없어지면 더 일찍 끝난다 | 추첨 상태, 회차 좌석 쿼리 | 상태 `DONE`, 남은 예비 `LOST`, 판매 좌석은 원래 등급·`SOLD`, 빈 좌석은 `AVAILABLE` | 로컬 11/5 기록 제시 | 통과(**로컬 DB 시각 조정**). 응모 시작·마감·추첨 시각을 함께 31분 앞당겼다. 추첨 시각만 옮기면 `chk_lotteries_period`에 걸린다. 2.5초 뒤 예비 12명 `LOST`, 22.7초 뒤 `DONE`. 판매 30석 `S`·`SOLD`, 남은 `LOTTERY` 0석, 공개 조회 `DONE` |
| L-09 | 마감 전 응모를 취소한다 | 응모 기간 | `DELETE /api/v1/lotteries/{id}/entries/me` 뒤 재응모 | `CANCELED`, 재응모 409, 추첨 제외 | 로컬 11/5 기록 제시 | |

추첨 대시보드와 경보 규칙은 `main`에 있다(`#358`). 서버에서 `추첨` 대시보드가 열리는지 확인한 뒤 L-04~L-07에 함께 보여 준다.

### 선택 장면

로컬 리허설을 통과한 장면만 넣는다. 서버에서는 동작을 확인하지 못했다.

| ID | 무엇을 증명하나 | 주입 | 절차와 기대 결과 | 확인 못 한 것 | 실패 시 대체 | 결과 |
|---|---|---|---|---|---|---|
| F-08 (E8) | 선점 만료 뒤 승인이 오면 자동 환불한다 | 결제 가능 시간 막바지에 `DELAY` | 화면의 `결제 가능` 시간이 끝나기 전에 결제를 누른다. 결제 토큰은 선점보다 10초 먼저 끝난다(`EXPIRY_MARGIN`). 그 뒤의 새 결제는 403 `PAYMENT_TOKEN_INVALID`이고 PG를 부르지 않는다. 기대 결과: 예매 `EXPIRED`, 환불 행 생성 후 `REFUNDED` | 재확인이 먼저 늦은 승인 방지(취소)로 끝날 수 있어 타이밍을 맞춰야 한다 | 통합 테스트 결과 제시 | 통과(타이밍 조건 있음). `holdMinutes=1` 회차에서 토큰 만료 약 1.5초 전 `DELAY` 5초로 결제했다. 예매 60: 202 `UNKNOWN`, 결제 `APPROVED`(15:40:35.044 UTC), 예매 `EXPIRED`(35.094, 선점 만료 약 1.1초 뒤), 환불 `EXPIRED_AFTER_APPROVAL`·`REFUNDED`, 결제 `REFUNDED`. 재확인은 202 응답 10.4초 뒤에 돌았다. 재확인이 선점 만료 뒤에 돌려면 이 값 기준으로 토큰 만료 전 약 2.4초 안에 눌러야 한다(응답 시한 2초 + 재확인 10.4초 - 토큰 여유 10초, 계산값이고 반복 측정하지 않았다). 선점 만료 3.3초 전, 곧 토큰 만료 뒤의 결제는 403 `PAYMENT_TOKEN_INVALID` |
| F-09 (E10) | PG 장애가 이어지면 CB가 막고 복구된다 | `{"target":"APPROVE","mode":"SERVER_ERROR"}` | 최근 20회 중 50% 실패로 OPEN, 결제 503 `PG_UNAVAILABLE`, 해제 뒤 10초 지나 복구. Grafana `PG Circuit Breaker OPEN`, 슬랙 `PaymentPgCircuitOpen` | 수동 결제로는 20회를 채우기 어렵다. 자동결제 20건 이상을 쓰는 방법을 로컬에서 확인해야 한다 | 대시보드 패널과 규칙 설명 | 통과. 수동 결제 22건으로 재현. 4번째부터 503 `PG_UNAVAILABLE`(앞 장면의 타임아웃 7건 + 3건 = 최근 20회 중 10건). CB `open`, 경보 `PaymentPgCircuitOpen` 활성. 해제 11초 뒤 200 `APPROVED`. 실패 3건은 `DECLINED`·`NOT_SENT`로 수렴. 슬랙은 로컬 주소가 자리표시 값이라 미확인 |
| F-10 (E12) | 결제 서비스가 재시작돼도 결제가 수렴한다 | 결제 중 `docker restart iseonjwa-payment-service-1` | 재기동 뒤 상태 확정, `dead_letters` 증가 없음 | 복구 시간 동안 503. 공유 서버라 재시작 전 팀 채널 공지가 필요하다 | 통합 테스트 FT-3 결과 제시 | 통과(정상 종료 기준). 지연 주입 결제 중 재시작. 202 `UNKNOWN` 응답 뒤 앱이 내려갔다. 13.9초 뒤 `APPROVED`, 예매 86 `CONFIRMED`, `approveCalls` 1, `dead_letters` 증가 없음. `docker restart`는 SIGTERM이라 PG 호출 중 강제 중단은 재현하지 않았다 |

이번 시연에서 제외한다: Redis 장애(E11, ISE-59 미완료), 접수형 선점과 DLQ 도달(E15, M4), 예매 취소(M4), 성능 수치(기준선 ISE-25 미측정).

## 6. 시연 전 확인할 것

| 질문 | 확인 방법 |
|---|---|
| 서버 백엔드가 머지 커밋과 같은가 | `.deploy/current`, `docker ps` 이미지 태그 |
| 컨테이너 이름과 `mock-pg` 장애 API가 서버에서 동작하는가 | 현재 설정 조회(GET) |
| 회차당 15,000석 생성에 몇 초 걸리는가 | P-06 실행 시간 측정 |
| 120명 가입과 카드 등록에 몇 분 걸리는가 | 서버에서 적재 시간 측정. 로컬은 73초, 응모는 75초다(2026-11-05) |
| 등록된 IP에서 Grafana가 열리고 `요청 로그`의 `트레이스(Zipkin)` 패널에 구간이 나오는가 | 시연 장소에서 접속. 서버의 Zipkin은 `127.0.0.1:9411`만 열려 있어 등록 IP로 바로 접속할 수 없다. 화면을 직접 보려면 SSH 터널을 쓴다 |
| 재확인 후 `UNKNOWN`이 확정되기까지 몇 초인가 | F-06·F-07 서버 리허설 측정. 로컬은 DB 기준 10.3\~10.9초, 화면 완료 20\~22초다(2026-11-06) |
