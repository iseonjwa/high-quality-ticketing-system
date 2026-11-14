---
title: 서비스의 XxxResult와 컨트롤러의 XxxResponse는 왜 따로 있나
date: 2026-11-04
asker: hyos1
level: 기초
tags: [layer, dto, result, response, dependency-direction, reservation]
---

# 서비스의 XxxResult와 컨트롤러의 XxxResponse는 왜 따로 있나

## 질문
web은 domain을 알아도 되지만 domain은 web을 알면 안 된다는 규칙 때문에, 서비스가 웹 응답 DTO를 모르게 하려고 서비스용 응답 DTO(XxxResult)를 따로 만든 것인가? 여러 엔티티 정보를 합쳐야 할 때 서비스가 XxxResult를 만들고, 컨트롤러가 그것으로 응답 DTO를 만드는 흐름인가?

## 답
둘 다 맞다. 정확히는 "서비스(application)가 웹(presentation)을 모르게" 하는 규칙이다.

```
presentation(웹)  →  application(서비스)  →  domain(엔티티·저장소)
  Controller            Service                Reservation, Repository
  XxxResponse           XxxResult              XxxRow(저장소 조회 결과)
```

- 화살표 방향으로만 import한다. 컨트롤러는 서비스와 엔티티를 알아도 된다. 서비스가 `XxxResponse`를 돌려주면 서비스가 웹 클래스를 import해 화살표가 거꾸로 생긴다.
- 그래서 서비스는 자기 계층의 `XxxResult`를 돌려주고, 컨트롤러가 `XxxResponse.from(result)`로 바꾼다.

`XxxResult`가 필요한 때는 돌려줄 값이 엔티티 하나로 끝나지 않을 때다.

| 경우 | 서비스가 돌려주는 것 | 예 |
|---|---|---|
| 엔티티 하나(또는 목록)로 충분 | 엔티티 그대로 | 회차 좌석 조회 `List<ScheduleSeat>` |
| 여러 출처를 묶어야 함 | `XxxResult` | 예매 상세: 예매 + 좌석 표시 정보(concert 테이블) + 티켓 번호 + 결제 토큰 |
| 페이지 정보까지 줘야 함 | `XxxResult` | 내 예매 목록: 행 목록 + page·size·totalElements |

`XxxResponse`는 API 모양을 책임진다. 시각을 `+09:00`으로 바꾸고, `Money`를 숫자로 바꾸고, `CONFIRMED`가 아니면 `tickets`를 빈 배열로 내리는 일이 여기 있다. 같은 Result라도 API가 다르면 Response만 바꾸면 된다.

## 예매 상세 흐름(ISE-171)
1. `ReservationController`가 `currentUserId`와 `reservationId`를 받는다.
2. `ReservationService.getReservation`
   - 예매를 찾고 본인 것이 아니면 404를 던진다.
   - `ReservationSeatRepository.findSeatDetails`로 좌석 표시 정보와 티켓 번호를 한 번에 읽는다(`SeatDetailRow`).
   - `HELD`이고 선점 만료 10초 전이면 결제 토큰을 발급한다.
   - 셋을 `ReservationDetailResult`로 묶어 돌려준다.
3. 컨트롤러가 `ReservationDetailResponse.from(result)`로 바꿔 `SuccessResponse`에 담는다.

`SeatDetailRow`는 저장소 인터페이스(domain)에 둔 조회 결과 타입이다. 저장소가 서비스의 `XxxResult`를 돌려주면 domain이 application을 알게 되므로, 조회 결과 타입은 저장소 쪽에 둔다(`ScheduleSeatRepository.LotterySeatRow`와 같은 방식).

## 정리
- 서비스는 웹 DTO를 모른다. 그래서 서비스 결과 타입(`XxxResult`)과 웹 응답 타입(`XxxResponse`)이 나뉜다.
- `XxxResult`는 여러 엔티티·조회 결과를 묶어야 할 때만 만든다. 엔티티 하나면 그대로 돌려준다.
- 응답 형식(시각대, 금액 숫자, 조건부 필드)은 `XxxResponse.from()`에서 정한다.
