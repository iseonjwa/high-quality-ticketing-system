---
title: 입장 토큰의 회차 ID와 요청 회차 ID 비교를 Gateway에서 할지 서비스에서 할지
date: 2026-10-29
updated: 2026-10-29
asker: hyos1
level: 기초
tags: [entry-token, gateway, jwt, queue, validation]
---

# 입장 토큰의 회차 ID와 요청 회차 ID 비교를 Gateway에서 할지 서비스에서 할지

## 질문
팀원이 "입장 토큰이랑 요청 회차 id 비교, gateway로 넘기실래요 아님 서비스에서 하시겠어요"라고 물었다. 무슨 상황인가.

## 답
### 배경
대기열을 통과한 사용자는 입장 토큰(JWT)을 받는다. 토큰 안에는 "어느 회차에 들어갈 수 있는지"(회차 ID)가 들어 있다(SA 7-3). 검증 규칙에 "토큰의 회차 ID가 요청한 회차와 같아야 한다"가 있다. 이게 없으면 인기 없는 회차 대기열을 통과한 토큰으로 인기 회차 좌석을 잡을 수 있다(대기열 우회).

### 왜 어디서 할지 묻는가
입장 토큰이 필요한 API는 둘이다. 요청 회차 ID가 들어 있는 위치가 다르다.

| API | 담당 | 요청 회차 ID 위치 |
|---|---|---|
| `GET /api/v1/schedules/{scheduleId}/seats` | 반효승 | URL 경로 |
| `POST /api/v1/reservations` | 최권우 | 요청 본문(JSON) |

- 서명·`typ`·`aud`·`exp`·`sub` 검증은 토큰만 보면 되므로 Gateway가 한다(SA 7-3).
- 경로에 있는 회차 ID는 Gateway도 쉽게 꺼내 비교할 수 있다.
- 본문에 있는 회차 ID를 Gateway가 보려면 본문을 읽어 JSON을 파싱해야 한다. Gateway(WebFlux)는 본문을 흘려보내는 구조라 본문을 읽으면 캐싱 처리가 필요하고, 몰리는 순간 Gateway 부담이 커진다.
- 그래서 "Gateway에서 다 비교할지, Gateway는 토큰의 회차 ID만 헤더로 넘기고 서비스가 비교할지"를 묻는 것이다.

### 선택지
| 안 | 방식 | 장점 | 단점 |
|---|---|---|---|
| A. Gateway 전부 | 경로·본문 모두 Gateway가 비교 | 서비스는 신경 쓰지 않음, 규칙이 한 곳 | Gateway가 본문을 파싱해야 함 |
| B. 서비스 전부 | Gateway는 토큰의 회차 ID를 헤더(예: `X-Entry-Schedule-Id`)로 넘기고 각 서비스가 비교 | Gateway가 가벼움 | 두 API 담당이 각자 구현, 빠뜨릴 위험 |
| C. 섞기 | 경로는 Gateway, 본문은 서비스(헤더 비교) | 각자 쉬운 쪽에서 처리 | 규칙이 두 곳에 나뉨 |

### 추가 질문: 요청한 회차 ID는 어떻게 아는가
토큰의 회차 ID는 JWT를 파싱해 안다. 요청한 회차 ID는 클라이언트가 HTTP 요청에 실어 보낸 값이다.
- 좌석 조회: `GET /api/v1/schedules/7/seats`면 경로의 `7`이다. Gateway는 라우트 경로 패턴(`/api/v1/schedules/{scheduleId}/**`)으로 꺼낸다. 서비스는 `@PathVariable`로 꺼낸다.
- 선점: `POST /api/v1/reservations` 본문 `{"scheduleId": 7, "seatIds": [...]}`의 `7`이다. 서비스는 `@RequestBody`로 바로 받지만 Gateway는 본문을 열어야 한다.

### 팀원 제안: 선점 경로를 `/schedules/{id}/reservations`로 바꾼다
회차 ID를 본문에서 경로로 옮기면 두 API 모두 경로에 회차 ID가 있어 Gateway가 한 규칙으로 비교한다.
- 장점: 검증이 Gateway 한 곳에 모인다. 잘못된 토큰 요청이 서비스까지 오지 않는다(SA 9-2 요청 차단 계층). 서비스 담당이 빠뜨릴 위험이 없다.
- 비용: SA 6-4·11-4의 API 경로(계약)가 바뀐다. 선점 API 담당(최권우)과 팀 합의, SA 수정이 필요하다. 이후 접수형 `POST /reservations`(202)도 같이 바뀐다.
- 바뀌지 않는 것: `GET /reservations/{id}`, `GET /reservations/me`는 입장 토큰을 쓰지 않으므로 그대로 둔다.
- 경로를 바꾼 뒤에도 서비스는 좌석이 그 회차 것인지 확인해야 한다. 경로는 회차 7, 좌석 ID는 회차 8 것을 보내는 요청이 있을 수 있다. SA 12-7 선점 쿼리의 `WHERE schedule_id = :scheduleId AND id = ANY(:seatIds)`가 이를 막는다.

### 추가 질문: 서비스에서 검증한다면 어떻게 하는가
Gateway는 어느 방식이든 서비스를 호출하지 않는다. 경로는 요청 첫 줄에 있어 바로 보이고, 본문은 열어야 보인다는 차이뿐이다.

서비스 검증 방식은 Gateway와 서비스가 일을 나눈다.
1. Gateway: 입장 토큰의 서명·`typ`·`aud`·`exp`·`sub`를 검증하고, 토큰의 회차 ID를 헤더(예: `X-Entry-Schedule-Id`)에 담아 넘긴다. 클라이언트가 같은 이름의 헤더를 보냈으면 지우고 덮어쓴다. 이미 `X-User-Id`를 이렇게 다룬다(`GatewayAuthenticationFilterTest`에서 클라이언트가 보낸 999를 무시하고 토큰의 1을 넘기는지 확인한다).
2. 서비스 컨트롤러: 헤더 값과 본문의 `scheduleId`를 비교해 다르면 `ENTRY_TOKEN_INVALID`(403)로 거절한다.

```java
@PostMapping("/api/v1/reservations")
public ResponseEntity<...> hold(
        @RequestHeader("X-Entry-Schedule-Id") Long entryScheduleId,
        @Valid @RequestBody HoldRequest request) {
    if (!entryScheduleId.equals(request.scheduleId())) {
        throw new BusinessException(ErrorCode.ENTRY_TOKEN_INVALID);
    }
    ...
}
```

- 단점: 이 비교를 입장 토큰이 필요한 API마다 넣어야 한다. 한 곳에서 빠뜨려도 테스트가 따로 없으면 모른다.
- 헤더 덮어쓰기를 Gateway가 빠뜨리면 클라이언트가 헤더를 위조해 우회할 수 있다.

## 핵심 정리
- 입장 토큰의 회차 ID 비교는 대기열 우회를 막는 검증이다.
- 쟁점은 본문에 회차 ID가 있는 `POST /reservations`를 Gateway가 파싱할지다.

## 면접 포인트
- Gateway에서 요청 본문을 읽지 않는 이유: 리액티브 Gateway는 본문을 스트림으로 흘려보내므로 읽으려면 메모리에 캐싱해야 하고, 피크 트래픽에서 Gateway가 병목이 된다. 토큰에서 뽑은 값을 헤더로 넘기고 비교는 서비스가 한다.

## 참고
- `docs/core/SA MASTER.md` 7-3(토큰 규칙), 8장 시퀀스, 11장 `ENTRY_TOKEN_INVALID`
