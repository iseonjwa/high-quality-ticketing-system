---
title: 이미 쓴 입장 토큰으로 선점하면 403과 409 중 무엇을 돌려줘야 하나(ADR 0053 결정 4)
date: 2026-11-14
updated: 2026-11-14
asker: GGangMY
level: 중급
tags: [http-status, api-design, entry-token, adr]
---

# 이미 쓴 입장 토큰으로 선점하면 403과 409 중 무엇을 돌려줘야 하나(ADR 0053 결정 4)

## 질문
결정 4가 403 `ENTRY_TOKEN_USED`인데 409로 해야 하는 것 아닌가. 결정 4를 더 설명해 달라.

## 짧은 답
- 둘 다 근거가 있다. 409는 "토큰의 현재 상태(소모됨)와 요청이 충돌"로 보고, 403은 "이 토큰은 더 이상 선점할 권한을 주지 않는다"로 본다.
- 이 API에서 409는 "다른 좌석을 고르면 될 수 있다"(`SEAT_ALREADY_HELD`)이고, 403 입장 토큰 오류는 "줄을 다시 서야 한다"(`ENTRY_TOKEN_INVALID`)다. `ENTRY_TOKEN_USED`를 받은 사람이 할 일은 다시 줄 서기라 403 쪽과 같다. 그래서 403을 유지하는 쪽을 추천한다.
- ADR 결정 4의 이유 중 "프런트가 403 흐름을 탄다"는 정확하지 않다. 프런트는 상태 코드가 아니라 오류 코드 이름으로 나눈다(`frontend/src/api/client.ts` 44행 `isApiError(error, 'ENTRY_TOKEN_INVALID')`). 어느 쪽이든 프런트는 `ENTRY_TOKEN_USED`를 새로 처리해야 한다.

## 자세히

### 두 상태 코드의 뜻
| 코드 | HTTP 뜻 | 클라이언트가 할 일 |
|---|---|---|
| 403 Forbidden | 누구인지는 알지만 이 요청을 할 권한이 없다 | 같은 자격으로 다시 보내도 소용없다. 자격을 새로 얻어야 한다 |
| 409 Conflict | 요청이 리소스의 현재 상태와 충돌한다 | 충돌을 풀고 다시 보내면 될 수 있다 |

### 이 프로젝트에서 각 코드가 이미 쓰이는 뜻
| 코드 | 오류 | 대응 |
|---|---|---|
| 403 | `ENTRY_TOKEN_INVALID`(Gateway, 만료·위조·회차 불일치) | 입장 토큰을 지우고 대기열로 |
| 409 | `SEAT_ALREADY_HELD` | 좌석을 다시 조회해 고르고 새 멱등키로 다시 보냄(`reservations.ts` 94행) |

`ENTRY_TOKEN_USED`의 대응은 "토큰을 지우고 줄을 다시 선다"다. 좌석을 다시 골라도 같은 토큰이면 또 거절된다. 대응이 같은 쪽(403)에 두면 "403 = 입장 자격 문제, 409 = 좌석 상태 문제"로 코드만 봐도 갈래가 나뉜다.

### 409를 고르는 근거
- 토큰은 서명·만료로는 아직 유효하다. 막히는 이유는 서버에 남은 상태(`seat_hold_requests.entry_token_jti`) 때문이라 "상태 충돌"에 가깝다.
- 위조·만료(진짜 권한 문제)와 이미 씀(상태 문제)이 다른 상태 코드로 나뉘어 지표·로그에서 구분하기 쉽다. 다만 오류 코드 이름으로도 이미 나뉜다.

### 바꾸는 비용(409로 갈 때)
- 코드: `SeatHoldErrorCode.ENTRY_TOKEN_USED`의 상태 값과 관련 테스트
- 문서: ADR 0053, `docs/design/reservation/contracts.md`(1-1절과 오류 표), `docs/design/queue/contracts.md`, 선점 지표 문서의 `entry_token_used` 줄
- 알림: 11/14 ISE-207 댓글과 PR #612에서 403으로 확정한다고 썼다
- 프런트: 오류 코드 이름으로 나누므로 상태만 바뀌면 영향이 없다

## 이 프로젝트에서는
- Gateway 거절: `gateway/.../security/EntryTokenFilter.java` 54행(403 `ENTRY_TOKEN_INVALID`)
- 선점 거절: `reservation-service/.../seathold/application/SeatHoldErrorCode.java`(`ENTRY_TOKEN_USED(403, ...)`), 검사 `SeatHoldRules`
- 프런트 분기: `frontend/src/api/client.ts` 44행(코드 이름으로 입장 토큰 삭제)

## 면접 포인트
- "이미 쓴 토큰에 왜 403을 줬나": 상태 코드를 클라이언트가 할 일로 나눴다. 403은 자격을 새로 얻어야 하는 경우(줄 다시 서기), 409는 다른 선택으로 풀 수 있는 충돌(다른 좌석)이다. 세부 원인은 오류 코드 이름으로 구분한다.

## 참고
- RFC 9110 15.5.4(403), 15.5.10(409)
- ADR 0053 결정 4와 검토한 대안
