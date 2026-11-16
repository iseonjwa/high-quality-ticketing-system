---
title: 결제 토큰 발급 포트를 seathold에서 reservation 모듈로 옮기자는 AI 추천을 받아들이고, 직접 맡아 판정 통합까지 처리한다
date: 2026-11-13
updated: 2026-11-13
judge: kangmin
ai: Claude Code (reservation-service import 집계, ADR 0034·계약 문서 대조)
verdict: 수용
area: 계층형 아키텍처
presentation: 후보
shared: 예
---

# 결제 토큰 발급 포트를 seathold에서 reservation 모듈로 옮기자는 AI 추천을 받아들이고, 직접 맡아 판정 통합까지 처리한다

## 맥락
- 해결하려던 작업: 예매 서비스 패키지의 계층·모듈 의존을 공부하다가 ADR 0034 위반 1건(`ReservationService`가 `seathold.application.PaymentTokenIssuer`를 import)을 찾았고, 어떻게 고칠지 물었다.
- AI 활용 방식: 위반이 생긴 경위와 고칠 방안을 물었고, AI가 사용처 세 곳, 포트의 TODO 주석, 계약 문서 5-1·5-3절을 읽고 세 안을 비교했다.

## AI 제안
- 1안(추천): 포트를 `reservation.application`으로, 구현 `JwtPaymentTokenIssuer`를 `reservation.infrastructure.token`으로 옮긴다. 남는 방향은 허용된 `seathold → reservation`뿐이고, 원래 계약과 TODO의 의도와 맞는다.
- 2안: `global.token` 같은 공유 위치로 옮긴다. 업무 규칙(토큰 만료 정책)이 잡동사니 패키지로 가 소유자가 흐려진다.
- 3안: ADR 0034에 예외를 적는다. 비용은 가장 적지만 "절대 import하지 않는다"는 규칙이 처음 깨진다.
- 선택 항목: 두 곳에 있는 "결제할 수 있는가" 판정을 `Reservation`으로 모은다(커밋 분리).
- 남는 것: `reservation → lottery → seathold → reservation` 패키지 순환은 이동 뒤에도 남는다.

## 판단
- 결과: 수용(1안)
- 판단자: kangmin
- 근거: "옮기는게 의존구조에 위반하지 않아"
- 검토한 대안: 2안, 3안
- 처리 담당: 강민("내가 처리할게"). 선택 항목(결제 가능 판정 통합)도 함께 처리한다("전부 처리해").
- 선점 담당(@ckw075)과 예매 흐름 담당(@hyozzang99)에게는 이슈 멘션으로 알린다.

## 반영
- Linear ISE-273(담당 강민, In Progress). 선점·예매 흐름 담당 멘션.
- 커밋 1: 포트 `reservation.application.PaymentTokenIssuer`, 구현 `reservation.infrastructure.token.JwtPaymentTokenIssuer`로 이동. 테스트 3개(`PaymentTokenTestKeys` 포함)도 같은 패키지로.
- 커밋 2: `Reservation.isPayableAt(now)`와 `Reservation.PAYMENT_CLOSE_MARGIN`(10초)으로 판정·토큰 만료 기준을 하나로 모음. `SeatHoldRules.isPayable`과 포트의 `EXPIRY_MARGIN` 제거.
- 문서: `docs/design/reservation/contracts.md` 5-1절 위치·판정, 5-3절 4번 결정 반영. ADR 0034 결과에 위반 정리 한 줄.

## 검증
- reservation-service 테스트: 이동 전 457(실패 0, 건너뜀 11) → 이동 뒤 457 → 판정 통합 뒤 459(새 경계 테스트 2).
- `grep -rn "reservation.seathold" .../reservation/reservation` 결과 0.
- 변이 테스트 2/2: 경계를 `!isAfter`로 바꾸기, 상태 검사 지우기 모두 `ReservationTest`가 실패로 잡음. 원복 뒤 통과.

## 판단 이력
- 2026-11-13 수용: 1안으로 정하고 담당은 회의에서 정하기로 함.
- 2026-11-13 스크럼 안건으로 올림: 이동 여부와 담당을 스크럼에서 함께 논의한다. 이슈 게시는 회의 뒤로.
- 2026-11-13 담당 결정: 강민이 맡고 선택 항목까지 처리하기로 함. ISE-273 게시, 커밋 2개.

## 발표·면접 포인트
- 구현이 하나뿐인 포트라도 "누가 소유하는가"를 기준으로 위치를 정했다. 토큰 claim(예매 ID·금액·만료)이 모두 예매의 값이라 예매 모듈이 소유하고, 선점 모듈은 허용된 방향으로 그 포트를 쓴다.
