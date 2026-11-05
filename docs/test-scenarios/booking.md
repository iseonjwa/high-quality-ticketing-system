# 일반 예매 테스트 시나리오

기준 커밋 `0bc8141`. 표 읽는 법은 [README](README.md)에 있다. 이 문서는 시나리오 정의만 담고, 실행 결과는 [`runs/`](runs/)에 있다.

## 공연·좌석 (11건)

| ID | 시나리오 | 유형 | 우선순위 | 기대 결과 | 테스트 경로 | 실행 환경 |
|---|---|---|---|---|---|---|
| CON-001 | 공연 목록: 최근 등록 순, 지운 공연 제외, 다음 회차 표시 (S1) | 정상 | P0 | 200, 공연마다 아직 시작하지 않은 가장 빠른 회차. 남은 회차가 없으면 null | `reservation-service/ConcertQueryApiTest` | 자동 테스트, 로컬, 서버 |
| CON-002 | 공연 상세: 회차를 시작 순으로 판매 상태와 함께 표시 (S1) | 정상 | P0 | 200, 시작 순 정렬, 지운 회차 제외 | `reservation-service/ConcertQueryApiTest` | 자동 테스트, 로컬, 서버 |
| CON-003 | 없거나 지운 공연 상세 404 | 실패 | P1 | 404 | `reservation-service/ConcertQueryApiTest`, `reservation-service/SoftDeleteFilterTest` | 자동 테스트, 로컬, 서버 |
| CON-004 | 페이지 번호 음수·크기 범위 초과 | 경계값 | P2 | 400 | `reservation-service/ConcertQueryApiTest` | 자동 테스트, 로컬, 서버 |
| CON-005 | 좌석 배치도: 구역별 열·번호 순 묶음 (S3) | 정상 | P0 | 구역별 정렬 응답 | `reservation-service/ScheduleSeatApiTest` | 자동 테스트, 로컬, 서버 |
| CON-006 | 선점 좌석·추첨석은 선택 불가, 구역 등급은 원래 등급 (S8) | 경계값 | P1 | 해당 좌석 선택 불가 표시 | `reservation-service/ScheduleSeatApiTest` | 자동 테스트, 로컬, 서버 |
| CON-007 | 좌석 미생성 회차는 빈 구역, 없거나 지운 회차는 404 | 경계값 | P2 | 빈 구역 또는 404 | `reservation-service/ScheduleSeatApiTest` | 자동 테스트 |
| CON-008 | 판매 상태 경계: 오픈 정각부터 판매, 공연 시작 정각부터 마감 | 경계값 | P1 | 오픈 정각 판매 중, 시작 직전까지 판매, 시작 정각 마감 | `reservation-service/ScheduleTimesTest`, `reservation-service/ScheduleTest` | 자동 테스트 |
| CON-009 | 공연장 시드: KSPO DOME 25구역 15000석, VIP 3000·R 6000·S 6000 | 정상 | P2 | 구역·등급 수가 일치 | `reservation-service/VenueSeedTest`, `reservation-service/VenueSeatTest` | 자동 테스트 |
| CON-010 | 다른 공연장 좌석으로 회차 좌석 생성 거절 | 실패 | P2 | 거절. 정상 좌석은 구역·등급 복사 후 AVAILABLE | `reservation-service/ScheduleTest` | 자동 테스트 |
| CON-011 | 회차 조회 캐시: 메모 조회, DB 대체, refresh 실패 시 이전 값 유지 | 실패 | P2 | 오픈 시각이 되면 DB 없이 판매 중 진입, 실패 시 이전 메모 사용 | `reservation-service/CachedScheduleReaderTest` | 자동 테스트 |

### 공연·좌석: 직접 실행할 때

| ID | 사전 조건 | 절차 | 서버 데이터 변경 |
|---|---|---|---|
| CON-001 | 공연·회차 존재 | GET /api/v1/concerts | 없음 |
| CON-002 | 회차 여러 개 | GET /api/v1/concerts/{id} | 없음 |
| CON-003 | 없음 | 없는 ID로 상세 조회 | 없음 |
| CON-004 | 없음 | page=-1, 범위 밖 size로 목록 조회 | 없음 |
| CON-005 | 좌석 생성, 입장 토큰 | GET /api/v1/schedules/{id}/seats | 없음 |
| CON-006 | 선점·추첨 좌석 존재 | 좌석 조회 | 없음 |

## 대기열·입장 토큰 (20건)

| ID | 시나리오 | 유형 | 우선순위 | 기대 결과 | 테스트 경로 | 실행 환경 |
|---|---|---|---|---|---|---|
| QUE-001 | 대기열 첫 진입 201과 순번 조회 주소 (S2) | 정상 | P0 | 201, 순번 조회 주소 | `reservation-service/QueueApiTest` | 자동 테스트, 로컬, 서버 |
| QUE-002 | 대기열 재진입은 200과 같은 순번 (S2) | 경계값 | P0 | 200, 같은 순번 | `reservation-service/QueueApiTest`, `reservation-service/RedisQueueRepositoryTest` | 자동 테스트, 로컬, 서버 |
| QUE-003 | 판매 전 대기열 진입 거절 | 실패 | P1 | 409 SALE_NOT_OPEN | `reservation-service/QueueApiTest` | 자동 테스트, 로컬, 서버 |
| QUE-004 | 공연 시작 뒤 진입 거절, 키 미생성 | 경계값 | P1 | 409 SALE_NOT_OPEN, 유효한 토큰이 있어도 SALE_CLOSED로 판정, 키 없음 | `reservation-service/QueueApiTest`, `reservation-service/RedisQueueRepositoryTest` | 자동 테스트 |
| QUE-005 | 없는 회차 대기열 404, 사용자 헤더 없음 401 | 실패 | P2 | 404 NOT_FOUND, 401 | `reservation-service/QueueApiTest` | 자동 테스트 |
| QUE-006 | 순번 조회 WAITING이 진입 순번과 일치 (S2) | 정상 | P0 | 200 WAITING, 같은 순번 | `reservation-service/QueueApiTest` | 자동 테스트, 로컬, 서버 |
| QUE-007 | 미진입·토큰 기록 만료 시 순번 조회 404 | 실패 | P1 | 404 QUEUE_NOT_JOINED, 옛 자리를 보지 않음, 조회가 키를 만들지 않음 | `reservation-service/QueueApiTest`, `reservation-service/RedisQueueRepositoryTest` | 자동 테스트 |
| QUE-008 | 차례가 된 사용자 입장 토큰 발급과 재요청 시 같은 토큰 (S2) | 정상 | P0 | 200 ADMITTED, 재요청 시 같은 토큰, waiting에서 제거 | `reservation-service/QueueApiTest`, `reservation-service/RedisQueueRepositoryTest` | 자동 테스트, 로컬, 서버 |
| QUE-009 | 차례 전 토큰 요청 거절 | 실패 | P0 | 409 QUEUE_NOT_READY, issued에 쓰지 않음 | `reservation-service/QueueApiTest`, `reservation-service/RedisQueueRepositoryTest` | 자동 테스트, 로컬, 서버 |
| QUE-010 | 같은 사용자 동시 10회 진입 시 seq 하나 | 동시성 | P0 | seq 하나 | `reservation-service/RedisQueueRepositoryTest` | 자동 테스트 |
| QUE-011 | 여러 사용자 동시 진입 시 seq 비중복, 회차별 분리 | 동시성 | P0 | seq 중복 없음, 다른 회차 간 독립 | `reservation-service/RedisQueueRepositoryTest` | 자동 테스트 |
| QUE-012 | 입장 틱: 초당 인원만큼 증가, 발급 seq 초과 금지, 초당 1회 | 경계값 | P1 | 이어서 증가, seq를 넘지 않음, 같은 초 재호출 무시, 진입자 없으면 키 미생성 | `reservation-service/RedisQueueRepositoryTest` | 자동 테스트 |
| QUE-013 | 만료 정리: 만료 issued와 waiting 잔여 제거, 정리 상한 | 경계값 | P2 | 유효 issued 유지, 한 번에 상한까지만 삭제 | `reservation-service/RedisQueueRepositoryTest` | 자동 테스트 |
| QUE-014 | 만료된 토큰 뒤 재진입 시 새 번호 | 경계값 | P1 | 새 번호, 토큰 유효 중이면 TOKEN_ISSUED | `reservation-service/RedisQueueRepositoryTest` | 자동 테스트 |
| QUE-015 | 입장 판정과 남은 순번 계산 | 경계값 | P2 | 같으면 READY, 크면 WAITING, 지났으면 남은 순번 0 | `reservation-service/AdmissionRuleTest` | 자동 테스트 |
| QUE-016 | 폴링 간격: 3\~10초와 jitter | 경계값 | P2 | 가까우면 최소 3초, 멀면 최대 10초에 jitter | `reservation-service/PollPolicyTest` | 자동 테스트 |
| QUE-017 | 입장 스케줄러: 한 회차 실패해도 다음 회차 처리 | 실패 | P1 | 나머지 처리, 판매 중 회차 없으면 무동작 | `reservation-service/QueueAdmissionServiceTest` | 자동 테스트 |
| QUE-018 | 입장 토큰 위조·만료·용도 위반 거절 | 보안 | P0 | 모두 거절 | `common/EntryTokenDecoderTest` | 자동 테스트 |
| QUE-019 | 입장 토큰 발급 계약: 10분 만료, 같은 시각 같은 문자열 | 경계값 | P2 | 초 단위 절사, 만료 10분 뒤, 시각 다르면 jti 다름 | `reservation-service/JwtEntryTokenIssuerTest` | 자동 테스트 |
| QUE-020 | 입장 토큰 만료 시각을 한국 시간(+09:00)으로 응답 | 경계값 | P2 | entryTokenExpiresAt이 +09:00으로 끝남 | `reservation-service/QueueApiTest` | 자동 테스트, 로컬, 서버 |

### 대기열·입장 토큰: 직접 실행할 때

| ID | 사전 조건 | 절차 | 서버 데이터 변경 |
|---|---|---|---|
| QUE-001 | 판매 중 회차, 로그인 | POST /api/v1/schedules/{id}/queue | 있음 |
| QUE-002 | 이미 진입 | 같은 사용자가 다시 진입 | 있음 |
| QUE-003 | 오픈 전 회차 | 대기열 진입 | 없음 |
| QUE-006 | 진입 | GET .../queue/me | 없음 |
| QUE-008 | admitted 이내 순번 | POST .../queue/token 2회 | 있음 |
| QUE-009 | 순번이 admitted보다 큼 | 토큰 요청 | 없음 |
| QUE-020 | 차례가 된 사용자 | POST .../queue/token | 있음 |

## 좌석 선점 (16건)

| ID | 시나리오 | 유형 | 우선순위 | 기대 결과 | 테스트 경로 | 실행 환경 |
|---|---|---|---|---|---|---|
| HOLD-001 | 선점 성공 201과 계약 10개 필드 (S3) | 정상 | P0 | 201, 10개 필드, 좌석·매수·예매·원장을 한 번에 기록 | `reservation-service/SeatHoldApiTest` | 자동 테스트, 로컬, 서버 |
| HOLD-002 | 이미 선점된 좌석이 섞이면 전체 되돌림 (S5) | 실패 | P0 | 409 SEAT_ALREADY_HELD, 전체 롤백 | `reservation-service/SeatHoldApiTest` | 자동 테스트, 로컬, 서버 |
| HOLD-003 | 추첨석이 섞이면 선점 거절 | 실패 | P1 | 409 SEAT_ALREADY_HELD, 롤백 | `reservation-service/SeatHoldApiTest` | 자동 테스트, 로컬 |
| HOLD-004 | 상태가 어긋나도 부분 유니크 인덱스가 중복 선점 차단 | 동시성 | P0 | DB 제약으로 거절, RELEASED 뒤 재삽입 가능 | `reservation-service/SeatHoldApiTest`, `reservation-service/SeatHoldMigrationTest` | 자동 테스트 |
| HOLD-005 | 같은 키·본문 재전송 시 같은 예매로 201 | 경계값 | P0 | 좌석 재선점 없음, 같은 예매 201, 판매 종료 뒤에도 201 | `reservation-service/SeatHoldApiTest` | 자동 테스트, 로컬 |
| HOLD-006 | 같은 키에 다른 좌석·회차·사용자 | 실패 | P1 | 409 IDEMPOTENCY_KEY_REUSED, 변경 없음 | `reservation-service/SeatHoldApiTest` | 자동 테스트, 로컬 |
| HOLD-007 | 확정 예매로 재전송 시 201 CONFIRMED, 결제 토큰 없음 | 경계값 | P1 | 201 CONFIRMED, 토큰 없음 | `reservation-service/SeatHoldApiTest` | 자동 테스트 |
| HOLD-008 | 만료·해제·취소 예매와 결제 토큰 시각 경과 HELD 재전송 | 경계값 | P1 | 409 RESERVATION_NOT_PAYABLE, 좌석이 풀린 뒤 같은 키로 다시 선점 가능 | `reservation-service/SeatHoldApiTest` | 자동 테스트 |
| HOLD-009 | 오픈 전·공연 시작 뒤 선점 거절 | 경계값 | P1 | 409 SALE_NOT_OPEN | `reservation-service/SeatHoldApiTest` | 자동 테스트 |
| HOLD-010 | 보유+요청 매수가 회차 한도 초과 | 경계값 | P0 | 한도까지 성공, 초과 시 409 PURCHASE_LIMIT_EXCEEDED | `reservation-service/SeatHoldApiTest` | 자동 테스트, 로컬 |
| HOLD-011 | 좌석 수·소속 불일치, 없거나 지운 회차 | 실패 | P2 | 400 SEAT_COUNT_MISMATCH, 404 NOT_FOUND | `reservation-service/SeatHoldApiTest` | 자동 테스트 |
| HOLD-012 | 멱등키·경로 형식 오류, 사용자 헤더 없음 | 실패 | P2 | 400 VALIDATION_FAILED, 401 | `reservation-service/SeatHoldApiTest` | 자동 테스트 |
| HOLD-013 | 기한 지난 HELD 만료와 좌석·매수 반환 | 정상 | P0 | EXPIRED, 좌석·매수 반환, 재선점 가능 | `reservation-service/SeatHoldExpiryTest` | 자동 테스트, 로컬 |
| HOLD-014 | 기한 남은 HELD·이미 확정 예매는 만료하지 않음, 이중 만료 방지 | 경계값 | P1 | 대상만 처리, 반환은 한 번 | `reservation-service/SeatHoldExpiryTest` | 자동 테스트 |
| HOLD-015 | 보유 매수 음수 금지, 같은 request_id 저장 시 PK 충돌 | 경계값 | P2 | DB 제약 위반 | `reservation-service/SeatHoldMigrationTest` | 자동 테스트 |
| HOLD-016 | 한 좌석에 다수 사용자 동시 선점 (S5, ISE-25) | 동시성 | P0 | 확인할 질문: 1건 201, 나머지 409인가(API 단위 동시 요청 테스트는 메서드 이름에서 찾지 못함) (근거: 추정) | `reservation-service/SeatHoldMigrationTest (제약만)` | 로컬 |

### 좌석 선점: 직접 실행할 때

| ID | 사전 조건 | 절차 | 서버 데이터 변경 |
|---|---|---|---|
| HOLD-001 | 입장 토큰, 멱등키 | POST /api/v1/schedules/{id}/reservations | 있음 |
| HOLD-002 | 일부 좌석 선점됨 | 겹치는 좌석으로 선점 | 있음 |
| HOLD-003 | LOTTERY 좌석 | 추첨석 포함 선점 | 없음 |
| HOLD-005 | 선점 완료 | 같은 Idempotency-Key로 재요청 | 있음 |
| HOLD-006 | 사용한 키 | 본문을 바꿔 재요청 | 없음 |
| HOLD-010 | 한도 근접 보유 | 한도까지 나눠 선점 후 추가 요청 | 있음 |
| HOLD-013 | 기한 지난 HELD | 만료 작업 실행 후 다른 사용자가 선점 | 있음 |
| HOLD-016 | 입장 토큰 다수 | 같은 좌석에 동시 요청 | 있음 |

## 예매·티켓 (15건)

| ID | 시나리오 | 유형 | 우선순위 | 기대 결과 | 테스트 경로 | 실행 환경 |
|---|---|---|---|---|---|---|
| RES-001 | 선점 도메인: 가격 합 계산, 빈 좌석 거절, 예매 번호 형식 | 정상 | P2 | 총액·수량 계산, HELD 시작, 빈 목록 거절, 번호는 한국 날짜+영숫자 8자 | `reservation-service/ReservationTest`, `reservation-service/ReservationNoTest` | 자동 테스트 |
| RES-002 | 선점 중 본인 예매 조회: 결제 토큰 있음, 티켓 없음 (S3) | 정상 | P0 | 좌석과 결제 토큰, 티켓은 비어 있음 | `reservation-service/ReservationApiTest` | 자동 테스트, 로컬, 서버 |
| RES-003 | 선점 만료 10초 전부터 결제 토큰 미제공 | 경계값 | P1 | 결제 토큰 없음 | `reservation-service/ReservationApiTest` | 자동 테스트 |
| RES-004 | 확정 예매 조회: 좌석마다 티켓, 결제 토큰 없음 (S4) | 정상 | P0 | 좌석 수만큼 티켓 | `reservation-service/ReservationApiTest` | 자동 테스트, 로컬, 서버 |
| RES-005 | 타인·없는 예매 404, ID 0 이하 400, 미로그인 401 | 보안 | P0 | 404, 400, 401. 존재 여부를 노출하지 않음 | `reservation-service/ReservationApiTest` | 자동 테스트, 로컬 |
| RES-006 | 내 예매 목록: 최근 순, 공연 정보 포함, 쪽 나눔 | 정상 | P1 | 최근 순, 제목·시작 시각, 페이징 | `reservation-service/ReservationApiTest` | 자동 테스트, 로컬, 서버 |
| RES-007 | 승인 이벤트로 예매 확정과 좌석별 티켓 발급 (S4) | 정상 | P0 | CONFIRMED, 티켓 발급 | `reservation-service/PaymentResultServiceTest` | 자동 테스트, 로컬, 서버 |
| RES-008 | 거절 이벤트로 예매 해제와 좌석·매수 반환 (S6) | 실패 | P0 | RELEASED, 좌석·매수 반환 | `reservation-service/PaymentResultServiceTest` | 자동 테스트, 로컬, 서버 |
| RES-009 | 선점 기한 뒤 승인: 만료, 반환, 환불 요청 | 실패 | P0 | 만료 후 환불 요청, 이미 만료면 환불만 | `reservation-service/PaymentResultServiceTest`, `payment-service/RefundWorkerWithMockPgTest` | 자동 테스트 |
| RES-010 | 금액이 다른 승인은 미확정, 환불 요청 | 실패 | P0 | 확정 안 함, 환불 요청 | `reservation-service/PaymentResultServiceTest` | 자동 테스트 |
| RES-011 | 중복 이벤트, 확정 뒤 같은 결제·다른 결제 승인, 없는 예매 | 경계값 | P0 | 한 번만 반영, 다른 결제는 환불, 없는 예매는 처리 기록만 | `reservation-service/PaymentResultServiceTest` | 자동 테스트 |
| RES-012 | 확정 예매의 결제가 환불돼도 좌석 유지 | 경계값 | P1 | 좌석을 풀지 않음 | `reservation-service/PaymentResultServiceTest` | 자동 테스트 |
| RES-013 | 결제 토큰 발급 계약: 선점 만료 10초 전 만료, 정수 claim | 보안 | P1 | 계약 claim, 발급마다 jti 다름, 키 오류는 값 노출 없이 기동 차단 | `reservation-service/JwtPaymentTokenIssuerTest` | 자동 테스트 |
| RES-014 | 대사 내부 API: ID 목록과 기간 변경 조회 | 경계값 | P2 | 없는 ID 제외, 100건 초과·ID 없음·기간 역전은 400, 100건마다 nextCursor | `reservation-service/ReconciliationApiTest` | 자동 테스트 |
| RES-015 | [미구현] 사용자 예매 취소: 좌석 반환, 티켓 무효화, 환불 요청 | 정상 | P1 | SA 기준 202. 엔드포인트가 45a0900에 없음 (근거: 설계 문서) | 없음 | 자동 테스트, 로컬 |

### 예매·티켓: 직접 실행할 때

| ID | 사전 조건 | 절차 | 서버 데이터 변경 |
|---|---|---|---|
| RES-002 | HELD 예매 | GET /api/v1/reservations/{id} | 없음 |
| RES-004 | CONFIRMED 예매 | 예매 조회 | 없음 |
| RES-005 | 타인 예매 | 각 조건으로 조회 | 없음 |
| RES-006 | 예매 여러 건 | GET /api/v1/reservations/me | 없음 |
| RES-007 | HELD 예매 | 선점 시간 안의 승인 이벤트 소비 | 있음 |
| RES-008 | HELD 예매 | 거절 이벤트 소비 | 있음 |
| RES-015 | CONFIRMED 예매 | POST /api/v1/reservations/{id}/cancel | 서버 실행 안 함 |
