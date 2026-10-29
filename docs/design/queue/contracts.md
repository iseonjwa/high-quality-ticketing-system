# 대기열 서비스 간 계약 (ISE-20 입장 토큰)

SA 17-2 U1의 계약 ① 입장 토큰 claim을 대기열 담당 기준으로 정리한다. 입장 토큰은 대기열(Reservation `queue` 모듈)이 발급하고 Gateway가 검증한다. 좌석 조회·좌석 선점 서비스는 토큰을 직접 읽지 않는다.

상태: 일부 확정. 4절 1·2번(선점 경로, 경로 기준 회차 비교)은 2026-10-29 확정했고, 3번(claim 이름·타입, 헤더 이름)은 확인 중이다. 3번이 확정되면 상태를 `확정`으로 바꾼다. 발급 위치·재발급 규칙은 [ADR 0031](../../decisions/0031-queue-token-api-and-ready-status.md)을 따른다.

## 1. 계약 ① 입장 토큰 (Reservation 발급 → Gateway 검증)

대기열 토큰 API(`POST /api/v1/schedules/{scheduleId}/queue/token`)가 응답의 `entryToken`으로 내려 주고, 사용자가 좌석 조회·선점 요청의 `X-Entry-Token` 헤더로 보낸다. Gateway는 서비스 호출이나 Redis 조회 없이 토큰만으로 검증한다(SA 9-5).

### 형식

| 항목 | 값 |
|---|---|
| 형식 | JWS compact(JWT), 서명 알고리즘 HS256 고정. 헤더의 `alg`를 믿지 않는다 |
| 서명 키 | `ENTRY_TOKEN_SECRET`. 결제 토큰 키와 분리하며 Reservation·Gateway만 가진다 |
| 키 바이트 | 문자열의 UTF-8 바이트를 그대로 쓴다(16진수 디코딩 금지). 발급기·검증기 모두 `TokenDecoders.signingKey(secret)`를 쓴다([0025](../../decisions/0025-payment-token-secret-injection.md)와 같은 방식) |
| 길이 상한 | 4,096자. 넘으면 검증 전에 거절한다 |
| 전달 | 요청 헤더 `X-Entry-Token` |

### claim

| claim | 타입 | 필수 | 의미와 검증 |
|---|---|---|---|
| `typ` | 문자열 | 예 | 값 `entry`. 다르면 거절 |
| `aud` | 문자열 | 예 | 값 `gateway`. 토큰을 열어 검증하는 곳이 Gateway뿐이라 단일 값이다. 다르면 거절 |
| `sub` | 문자열 | 예 | 사용자 ID(양의 정수 문자열). Gateway가 Access JWT로 확인한 사용자 ID와 같아야 한다(ADR 0015) |
| `scheduleId` | JSON 정수 | 예 | 회차 ID. 요청 경로의 `{scheduleId}`와 같아야 한다. 문자열·소수는 거절한다. 이전 안의 `sid` 대신 결제 토큰의 `reservationId`처럼 전체 이름을 쓴다 |
| `exp` | NumericDate(초) | 예 | `floor(발급 시각 ms / 1000) + 600`. 만료 10분은 SA 9-4의 T `[가정]` |
| `iat` | NumericDate(초) | 예 | `floor(발급 시각 ms / 1000)`. 검증하지 않는다 |
| `jti` | 문자열 | 예 | `entry:{scheduleId}:{sub}:{발급 시각 ms}`의 SHA-256 앞 16바이트(소문자 hex 32자). 추적·로그용이며 검증하지 않는다 |

- 발급 시각(ms)은 토큰 발급 Lua가 Redis `TIME`으로 정해 `issued`에 기록한 값이다. 재발급 때는 같은 값을 쓰므로 `exp`·`iat`·`jti`가 같고, 같은 키로 서명하면 토큰 문자열까지 같다(ADR 0031 결정 9).
- 다른 claim이 더 있어도 무시한다. 위 표의 claim만 발급한다.

예시(payload):

```json
{ "typ": "entry", "aud": "gateway", "sub": "42", "scheduleId": 31,
  "iat": 1793176230, "exp": 1793176830, "jti": "5f1c2e9a0b7d4c3e8a6f1d2b3c4e5f60" }
```

### 입장 토큰이 필요한 요청

| 메서드 · 경로 | 담당 | 회차 비교 |
|---|---|---|
| GET `/api/v1/schedules/{scheduleId}/seats` | 반효승 | 경로 `{scheduleId}` |
| POST `/api/v1/schedules/{scheduleId}/reservations` | 최권우 | 경로 `{scheduleId}` (이전: POST `/api/v1/reservations` 본문 `scheduleId`) |

- 같은 `/schedules/{scheduleId}/` 아래라도 대기열 API(`/queue`, `/queue/me`, `/queue/token`)는 입장 토큰이 필요 없다. 토큰을 받으러 가는 경로다.
- Gateway는 대상 경로를 목록으로 가진다. 경로 판정은 `AccessPolicy`와 같은 PathPattern·파싱된 경로를 쓴다([0006](../../decisions/0006-gateway-single-access-policy.md)).

### Gateway의 동작

- 대상 경로에서 서명·`typ`·`aud`·`exp`·필수 claim 중 하나라도 틀리거나, `sub`가 로그인 사용자와 다르거나, `scheduleId`가 경로의 회차와 다르면 403 `ENTRY_TOKEN_INVALID`다(E5, E14). 이유는 응답에 담지 않는다.
- 요청 본문은 읽지 않는다. 회차 비교에 필요한 값이 모두 경로와 토큰에 있다.
- Redis를 쓰지 않으므로 Redis 장애 중에도 이미 받은 토큰은 검증을 통과한다(SA 9-5).
- 통과하면 요청을 그대로 서비스에 넘긴다. 서비스는 입장 토큰을 다시 검증하지 않는다. 좌석이 그 회차의 좌석인지 같은 업무 검증은 서비스가 한다.

Gateway 쪽 고칠 곳: 입장 토큰 필터(ISE-58). 발급 쪽 고칠 곳: 토큰 서명(ISE-56), 토큰 API(ISE-69). claim 이름은 발급과 검증이 다른 모듈이므로 `common`의 `auth` 패키지에 두는 것을 권장하고, ISE-56에서 확정한다.

## 2. 프론트 사용 규칙

SA 11-5를 따른다. 입장 토큰은 `sessionStorage`에 두고, 만료(`entryTokenExpiresAt`) 전이면 대기열을 건너뛴다. 403 `ENTRY_TOKEN_INVALID`를 받으면 저장한 토큰을 지우고 대기열 진입부터 다시 한다.

## 3. 바뀐 점

| 항목 | 이전 (SA 7-3·11-5) | 이 계약 |
|---|---|---|
| 회차 ID claim | "회차 ID" (이름 미정, 이전 안 `sid`) | `scheduleId`, JSON 정수 |
| `iat` | 없음 | 발급 시각(초) |
| `exp`·`jti` | 규칙 없음 | 발급 시각 ms에서 계산 |
| 선점 경로 | POST `/api/v1/reservations`, 본문 `scheduleId` | POST `/api/v1/schedules/{scheduleId}/reservations`, 본문 `seatIds`만 |
| 회차 비교 | 위치 미정 | Gateway가 경로로 비교 |

## 4. 확인 요청 항목

각 항목에 "그대로 / 이렇게 바꾼다"로 확인해 주면 결과 칸을 채운다. 모두 채워지면 상태를 `확정`으로 바꾼다.

| # | 항목 | 확인할 사람 | 결과 |
|---|---|---|---|
| 1 | 선점 경로를 POST `/api/v1/schedules/{scheduleId}/reservations`로 바꾸고 본문에서 `scheduleId`를 뺀다. 선점 계약(`docs/design/reservation/contracts.md`)도 같이 고쳤다 | 최권우 | 그대로 (2026-10-29 확정) |
| 2 | 좌석 조회는 경로 `{scheduleId}`로 Gateway가 회차를 비교한다. 서비스는 입장 토큰을 다시 보지 않는다 | 반효승 | 그대로 (2026-10-29 확정) |
| 3 | claim 이름·타입(`scheduleId` JSON 정수, `sub` 문자열)과 헤더 이름 `X-Entry-Token` | 최권우, 반효승 | 확인 중 (기본 안: 그대로) |
