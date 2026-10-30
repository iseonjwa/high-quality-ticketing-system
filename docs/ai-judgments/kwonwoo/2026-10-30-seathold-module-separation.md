---
title: 트래픽 집중 지점 격리와 향후 스케일업을 위해 좌석 선점(seathold)을 별도 도메인 모듈로 분리하되 단방향 의존 규칙을 둔다
date: 2026-10-30
updated: 2026-10-30
judge: kwonwoo
ai: Antigravity (Gemini)
verdict: 부분 수용
area: 서비스 경계
presentation: 후보
shared: 예
---

# 트래픽 집중 지점 격리와 향후 스케일업을 위해 좌석 선점(seathold)을 별도 도메인 모듈로 분리하되 단방향 의존 규칙을 둔다

## 맥락
- 해결하려던 작업: `ISE-10`(좌석 선점 테이블 스키마 및 도메인 엔티티 구현) 진행 중 `reservation-service` 안에서 좌석 선점(`seathold`)과 예매 관리(`reservation`)를 단일 패키지로 둘지(방법 1, SA 13장 기본안), 별도 도메인 패키지로 분리할지(방법 2) 결정해야 했다.
- AI 활용 방식: 두 방법의 트레이드오프(데이터·트랜잭션 결합도, 역방향 상태 전이와 데드락 방지 순서, M4 Kafka 접수형 확장성)를 3차례에 걸쳐 교차 검토하도록 요청했다([질문 노트](../../learning/kwonwoo/qna/2026-10-30-reservation-seathold-module-boundary.md)).

## AI 제안
- **방법 1 (단일 패키지 유지, AI 우선 권장)**: `reservation` 패키지 하나에 엔티티를 모으고 `SeatHold*` 클래스 접두어로 유스케이스만 나눈다. SA 13장과 바로 일치하고 모듈 간 참조 고민이 없다.
- **방법 2 (`seathold` 패키지 분리 + 단방향 소유권 조정)**: `seathold`를 별도 모듈 패키지로 나누되, 처음 구상(`UserScheduleQuota`를 `seathold`에 배치)에서 생기는 양방향 순환 참조(`seathold ↔ reservation`)를 막기 위해 `seathold.domain`에는 `SeatHoldRequest`만 두고, `Reservation`·`ReservationSeat`·`UserScheduleQuota`는 `reservation.domain`에 두어 **`seathold → reservation`, `concert` 단방향 의존**으로 고정한다.

## 판단
- 결과: 부분 수용 (AI가 우선 권장한 방법 1 대신 **방법 2(`seathold` 모듈 분리)**를 선택하고, AI가 짚은 **단방향 소유권·의존 규칙**을 수용)
- 판단자: kwonwoo
- 근거: 트래픽이 집중되는 지점(티켓 오픈 정각의 좌석 선점 폭주 및 M4 Kafka 접수형 큐잉)에 따라 모듈 경계를 분명하게 나눌 수 있고, 이후에 스케일업이나 서비스 분리를 진행할 때도 `seathold`가 분리되어 있는 편이 훨씬 수월하기 때문이다.
- 검토한 대안: 방법 1(`reservation` 단일 패키지 유지), 초기 구상안(`UserScheduleQuota`까지 `seathold`에 두어 양방향 참조가 생기는 구조)

## 반영
- ADR: [`docs/decisions/0033-seathold-unidirectional-module-boundary.md`](../../decisions/0033-seathold-unidirectional-module-boundary.md) (상태: 제안, 반효승 확인 후 확정)
- 학습 노트: [`docs/learning/kwonwoo/qna/2026-10-30-reservation-seathold-module-boundary.md`](../../learning/kwonwoo/qna/2026-10-30-reservation-seathold-module-boundary.md)
- 코드 반영 예정(`ISE-10`): `SeatHoldRequest`, `SeatHoldRequestStatus`는 `com.iseonjwa.reservation.seathold.domain.entity`에 두고, `Reservation`, `ReservationSeat`, `ReservationStatusHistory`, `UserScheduleQuota`는 `com.iseonjwa.reservation.reservation.domain.entity`에 배치

## 검증
- 구현 전 (이어지는 `ISE-10` 엔티티·스키마 작업에서 `./gradlew verifyWorkspace --no-daemon`으로 검증 예정)

## 판단 이력
- 2026-10-30 부분 수용: 트래픽 집중 지점 경계 분리와 향후 스케일업 용이성을 근거로 방법 2(`seathold` 모듈 분리 + `seathold → reservation` 단방향 의존) 선택

## 발표·면접 포인트
- "단순히 SA 초안대로 한 패키지에 몰아넣는 대신, 오픈 정각 스파이크 트래픽을 받아내는 입구(`seathold`)와 이후 결제 SAGA를 처리하는 원장(`reservation`)의 트래픽 비대칭성과 향후 스케일업을 고려해 모듈을 분리했습니다. 이때 발생할 수 있는 양방향 순환 참조를 막기 위해, 결제 거절·취소에서도 함께 차감하는 `UserScheduleQuota`는 원장(`reservation`)에 두고 `seathold → reservation` 단방향으로만 의존하도록 경계를 설계했습니다."
