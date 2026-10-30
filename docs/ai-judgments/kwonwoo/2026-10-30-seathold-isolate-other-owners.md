---
title: ISE-11 구현이 다른 담당 모듈에 걸친 부분을 contract 이슈로 알리고 유지하자는 AI 권고를 거부하고 선점 모듈 안으로 격리
date: 2026-10-30
updated: 2026-10-30
judge: 최권우
ai: Claude Code
verdict: 거부
area: 서비스 경계
presentation: 아님
shared: 예
---

# ISE-11 구현이 다른 담당 모듈에 걸친 부분을 contract 이슈로 알리고 유지하자는 AI 권고를 거부하고 선점 모듈 안으로 격리

## 맥락
- 해결하려던 작업: ISE-11(동기형 좌석 일괄 선점 API) 구현 뒤 다른 담당자 영향 점검.
- AI 활용 방식: 구현 후 "ISE-11 구현만 진행된 것인지, 다른 담당자 영향은 없는지" 다시 확인하게 했다.

## AI 제안
첫 구현은 concert 모듈(반효승 담당)에 회차·좌석 저장소를, reservation 모듈에 결제 토큰 발급기와 예매 번호 생성기를 새로 만들었다. AI는 영향 범위를 정리한 뒤 "발급기를 빼면 완료 기준(201에 결제 토큰)을 못 채우므로 범위는 그대로 두고, 반효승·인프라 담당에게 `contract` 이슈 2건으로 알린다"를 권했다.

## 판단
- 결과: 거부
- 판단자: 최권우
- 근거: "다른 사람에게 영향이 안 가도록 코드 수정"을 요청했다. 그 밖의 이유는 미기재.
- 검토한 대안: AI 권고안(유지 + contract 이슈). 발급기를 빼고 토큰 없이 응답하는 안은 완료 기준을 못 채워 제외했다.

## 반영
- concert 모듈에 추가했던 저장소를 지우고 `seathold.domain.repository.SeatHoldInventoryRepository`와 `seathold.infrastructure.persistence.JpaSeatHoldInventoryRepository`(EntityManager로 concert 엔티티 조회·좌석 조건부 UPDATE)로 옮겼다.
- 결제 토큰 발급기(`PaymentTokenIssuer`, `JwtPaymentTokenIssuer`)와 `ReservationNo`를 seathold 모듈로 옮겼다. 예매 조회도 발급하게 되면 위치를 예매 흐름 담당과 정한다는 `TODO`를 남겼다.
- 남은 공유 지점: `PAYMENT_TOKEN_SECRET`을 예매 서비스에 주입하는 설정(`compose.yml`, `config-repo/reservation-service.yml`, `LocalApps`)과 `reservation-service/build.gradle`의 `spring-security-oauth2-jose` 의존성. 키가 프로세스에 전달돼야 해서 코드 이동만으로는 없앨 수 없다.

## 검증
`./gradlew :reservation-service:test` 통과(40개, 지운 회차 404 테스트 추가).

## 판단 이력
- 2026-10-30 거부: contract 이슈로 알리고 유지하는 대신 선점 모듈 안으로 격리했다.
