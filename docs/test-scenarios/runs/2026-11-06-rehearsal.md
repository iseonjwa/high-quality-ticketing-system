# 2026-11-06 리허설 실행 기록

로컬 리허설에서 실행한 시연 장면 22개의 기록이다. 절차와 기대 결과는 [시연 시나리오](../../design/demo/2026-11-06-mvp-scenarios.md) 5절이 정본이고, 이 회차는 장면 결과와 근거를 이 표에도 모았다. 공유 서버에는 요청을 보내지 않았다. 시연 장면 밖의 시나리오 ID는 실행하지 않았다.

| 항목 | 값 |
|---|---|
| 커밋 | `0bc8141`로 빌드한 이미지. ISE-200 수정(`900f83e`) 전이다 |
| 환경 | 로컬 compose 스택(`http://127.0.0.1:8000`). 저장소 밖 override로 Mock PG만 `loadtest` 프로필. 2026-11-06 00:24\~01:42(KST) |
| 실행자 | Claude Code 세션. 저장소 밖 스크립트로 헤드리스 Chrome(`?demo=on`), API, 읽기 전용 `psql`을 썼다. 사람이 화면을 직접 본 기록은 없다 |

행은 장면 22개에 D-05와 L-05의 다시 쓴 행을 더해 24개다. 시각은 KST, 괄호 안의 UTC 표기는 DB 값이다.

| ID | 결과 | 실행 명령 또는 화면 | 근거(응답, traceId, 로그) | 비고 |
|---|---|---|---|---|
| D-02 | 통과 | 화면, 00:24\~00:25. 브라우저 시계를 1시간 빠르게 둠 | 오픈 전 `00:01:12`, 버튼 비활성. 오픈 0.4초 뒤 새로고침 0회로 활성 | D-01은 화면이 아니라 API로 준비했다(회차 9) |
| D-03 | 통과 | 화면, 00:25 | `POST /queue` 201, `GET /queue/me` 200, `POST /queue/token` 200, `GET /seats` 200 | `WAITING`·`READY`는 F-01의 API 호출에서 확인. 긴 대기는 보이지 않음 |
| D-04 | 통과 | 화면, 00:29. 예매 44 | 선점 201 `6aeca0bcd596…`, 결제 200 `6aeca0bc0c4d…`. DB `CONFIRMED`·`SOLD`, 결제 `APPROVED`, 티켓 `ISSUED`, 결제 outbox `SENT` | |
| D-05 | 실패 | Zipkin API, Loki, `docker logs`, 00:29. 수정 전 기대 결과 기준 | 결제 trace `6aeca0bc0c4d…`: Zipkin 4구간 gateway·payment-service, 로그 gateway 1줄, payment 1줄, reservation 0줄 | 원인: 예매 확정은 Kafka 비동기라 다른 trace. 기대 결과가 설계와 달랐다 |
| D-05 | 보류 | 수정한 기대 결과(`#367`) 기준. 수정한 절차 전체는 다시 실행하지 않음 | 결제 쪽은 위 행과 맞다. 선점 trace `6aeca0bcd596…`는 reservation-service 요청 로그에서 확인 | 선점 trace의 Gateway 로그와 Zipkin 구간은 미확인. Grafana 화면 미확인(로컬 로그인 302) |
| F-01 | 통과 | API 동시 선점 3회, 화면 1회, 00:30 | 3회 모두 201 `HELD` 1명, 409 `SEAT_ALREADY_HELD` 1명, 활성 예매 1건. 화면 409 `6aeca0ffdf1f…`, `이미 선택된 좌석입니다` | |
| F-02 | 통과 | API, 00:30 | 4매 201 `6aeca103365d…`, 1매 추가 409 `PURCHASE_LIMIT_EXCEEDED` `6aeca103b10a…` | |
| F-03 | 통과 | API, 00:30 | 오픈 전 409 `SALE_NOT_OPEN`. 토큰 없음, 바꾼 입장 토큰, 다른 회차 토큰 403 `ENTRY_TOKEN_INVALID`. 바꾼 결제 토큰 403 `PAYMENT_TOKEN_INVALID`, 결제 행 없음 | |
| F-04 | 통과 | 화면, 00:31. `DECLINE` 주입, 예매 52 | 결제 200 `DECLINED` `6aeca1444b2a…`, 예매 `RELEASED`, 회차 좌석 `AVAILABLE`, 주입 설정 `NONE`으로 돌아감 | |
| F-05 | 통과 | 화면 선점 뒤 5분 대기, 예매 43 | 만료 15:33:03 UTC, `EXPIRED` 15:33:04.884 UTC, 예매 55가 15:33:12.654 UTC에 같은 좌석 선점 | 만료 스케줄러는 5초 주기다 |
| F-06 | 통과 | 화면, 00:33. `DROP_RESPONSE` 주입, 예매 55 | 202 `UNKNOWN` `6aeca1b8ba09…`, `UNKNOWN` → `APPROVED` 10.5초, 완료 화면 20.2초, 원장 승인 1회, 티켓 `ISSUED` | 3회 실행(예매 53·55·58) 모두 완료 화면. DB 확인은 53·55 |
| F-07 | 통과 | 화면 00:34, API 00:34. `DELAY` 5초 주입, 예매 56 | API 202 `UNKNOWN` 2,022ms `6aeca1f0a2ce…`. `UNKNOWN` → `APPROVED` 10.7초, 완료 화면 22.2초, 원장 승인 1회 | Grafana `승인 지연`, `PG Unknown 원인` 패널 미확인 |
| F-08 | 통과 | API, 00:40. `holdMinutes=1`, `DELAY` 5초 주입, 예매 60 | 202 `UNKNOWN` 2,044ms `6aeca3665b96…`. 결제 `APPROVED` 15:40:35.044 UTC, 예매 `EXPIRED` 35.094, 환불 `EXPIRED_AFTER_APPROVAL` → `REFUNDED`, 결제 `REFUNDED`, 원장 환불 112,000원 | 첫 시도(예매 59)는 토큰 만료 뒤 눌러 403 `PAYMENT_TOKEN_INVALID`, 예매 `EXPIRED`. 타이밍을 맞춘 재실행이다 |
| F-09 | 통과 | API 결제 22건, 00:41. `SERVER_ERROR` 지속 주입 | 1\~3번 202 `UNKNOWN`, 4\~22번 503 `PG_UNAVAILABLE`, 경보 `PaymentPgCircuitOpen` 활성. 해제 11초 뒤 200 `APPROVED` `6aeca3cee1f3…`. 60초 뒤 `DECLINED`·`NOT_SENT` 3건 | 슬랙은 로컬 주소가 자리표시 값이라 미확인. Grafana 패널 미확인. 앞 장면의 타임아웃이 실패 비율에 함께 들어갔다 |
| F-10 | 통과 | API, 00:46. `DELAY` 주입 중 `docker restart`, 예매 86 | 202 `UNKNOWN` `6aeca4b98742…`, 13.9초 뒤 `APPROVED`, 예매 `CONFIRMED`, 원장 승인 1회, `dead_letters` 0건 유지 | 첫 시도(예매 85)는 스크립트의 멱등키 오류로 400 `VALIDATION_FAILED`. SIGTERM 기준이고 PG 호출 중 강제 중단은 재현하지 않음 |
| L-01 | 통과 | 화면, 01:30. 추첨 7, 회차 14 | 201 `OPEN` `6aecaf201238…`, `LOTTERY` 30석, `S` 5,970석, 공개 조회 200 | LIMIT 수정 전 이미지이고 2회 모두 초과 전환 없음 |
| L-02 | 통과 | `seed-lottery-entrants.sh enter 7`, 화면 응모, 01:33 | 120명 85초 실패 0, `ENTERED` 120건(150매). 화면 201 `6aecafda174d…`, `응모했어요` | 건수 122건(152매)은 L-03 계정과 발표자 응모 포함. API로 등록한 카드는 화면에 `이 브라우저에서 등록한 카드가 없어요`로 보임 |
| L-03 | 통과 | API, 01:30\~01:36 | 시작 전 409 `LOTTERY_NOT_OPEN` `6aecaf2545b8…`. 같은 키 201 같은 `entryId` 385, 새 키 409 `LOTTERY_ALREADY_ENTERED`, 다른 내용 409 `IDEMPOTENCY_KEY_REUSED`, 마감 뒤 409 `LOTTERY_NOT_OPEN` | |
| L-04 | 통과 | DB 조회, 01:37 | 01:37:04 `WON` 25건(30매), `WAITLISTED` 15건(20매), `LOST` 82건. 추첨 + 10초에 상태 `DRAWN`, 순위 1\~40 중복 없음 | |
| L-05 | 실패 | 추첨 6, 회차 13, 01:27 | `PAID` 2건, `FAILED` 36건(`BILLING_KEY_MISSING` 35건, 주입 `CARD_DECLINED` 1건). 결제 DB 키는 `ACTIVE` 140건 | 원인: Mock PG 재생성으로 메모리의 빌링키가 사라짐. 코드로도 확인했다(`BillingKeyStore`) |
| L-05 | 통과 | `carded` 없는 상태 디렉터리로 `prepare` 뒤 재실행. 추첨 7, 화면, 01:38 | 카드 108개 재등록 82초, `REVOKED` 1건 → 109건. 순위 2: `PAID`, 예매 126 `CONFIRMED`, 결제 `BILLING`·`APPROVED`, 티켓 1장. 순위 14: 티켓 2장 | 화면 `당첨되고 결제까지 끝났어요` |
| L-06 | 통과 | 화면, DB 조회, 01:38. 추첨 7 | 순위 3·5·7 `BILLING_KEY_MISSING`, 예매 `RELEASED`, 키 없음. 순위 26·27·30 승계 `PAID`, 2매인 28·29는 `WAITLISTED` 유지, 추첨석 30석 `SOLD` | 화면 `자동결제가 되지 않았어요` |
| L-07 | 통과 | 추첨 전 `DECLINE` 주입, DB 조회, 01:38 | 순위 1 `CARD_DECLINED`, 활성 키 있음, 예매 `RELEASED`, 주입 설정 `NONE`으로 돌아감 | 경보 규칙 동작과 슬랙은 미확인. 지표 `CARD_DECLINED` 누적 2건(1회차 포함) |
| L-08 | 통과 | 로컬 DB 시각 조정 1건(응모 시작·마감·추첨 시각 31분), 01:42 | 2.5초 뒤 `LOST` 94건, 22.7초 뒤 `DONE`. `S` `SOLD` 30석, 남은 `LOTTERY` 0석, 공개 조회 `DONE` | 실제 31분은 기다리지 않음. 추첨 시각만 옮기면 `chk_lotteries_period`에 걸린다. 서버에서 시각을 조정하지 않는다 |

전 구간 `dead_letters`는 예매 0건, 결제 0건이고 outbox는 모두 `SENT`였다. 응모자 적재의 첫 `prepare`는 가입 응답 000으로 120건이 실패했고(1,871초, 원인: 미확인), 다시 실행해 끝났다. 추첨 대시보드 화면과 슬랙 알림은 어느 장면에서도 확인하지 못했다.

결과는 통과, 실패, 보류 중 하나로 쓴다. 통과에는 실행 명령과 결과를 함께 적는다. 실패하면 원인을 적거나 "원인: 미확인"으로 쓰고, 고친 뒤에는 행을 덮어쓰지 않고 새 행을 더한다. 시연 장면의 절차와 기대 결과는 시연 시나리오에 쓰고, 그 밖의 시나리오 ID도 이 표 형식으로 쓴다.

새 회차는 이 파일을 복사해 `YYYY-MM-DD-<내용>.md`로 만든다.
