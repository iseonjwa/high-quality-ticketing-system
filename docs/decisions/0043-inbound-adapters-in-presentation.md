# 0043. Kafka 소비자·스케줄러 같은 입구는 presentation 계층에 둔다

- 상태: 채택
- 날짜: 2026-11-03
- 결정자: 팀 회의 (대기열·추첨·좌석 선점·결제·예매 흐름 담당)
- 관련 AI 판단: [입구 어댑터를 presentation으로 모두 옮기기로 확정](../ai-judgments/kangmin/2026-11-03-inbound-adapters-to-presentation.md)

## 맥락
- SA 13장 계층 표는 infrastructure의 의존을 "→ domain 인터페이스 구현"으로 적었다. 표 아래 문장은 "구독: 인프라 Consumer → application 서비스 호출"이라 서로 다르게 읽혔다. 대기열 담당이 컨슈머를 만들다가 제기했다.
- 코드는 표 아래 문장을 따르고 있었다. 좌석 선점·추첨·대기열의 스케줄러와 결제의 `payment.command` 리스너가 infrastructure에 있으면서 application을 호출한다.
- 이미 합의된 것: 입구는 메시지를 파싱하고 application 서비스를 한 번 호출한다. 로직·트랜잭션·멱등 처리는 application에 두고, application은 Kafka 타입을 모른다.

## 결정
- 밖에서 들어오는 입구(HTTP 컨트롤러, Kafka 소비자, 스케줄러)는 presentation에 둔다. 소비자는 `presentation/messaging`, 스케줄러는 `presentation/scheduling`이다.
- 입구 전용 설정(`@EnableScheduling`, 리스너 컨테이너 설정)과 메시지 파서는 그 입구와 같은 패키지에 둔다.
- infrastructure에는 밖으로 나가는 구현(JPA, Redis, Kafka 생산자·Outbox 릴레이, PG 클라이언트, 캐시)만 둔다. 모두 domain·application이 정한 인터페이스를 구현하거나 그 인터페이스 뒤에 숨는다.
- 대기열만 따로 옮기지 않고 모든 모듈에 같이 적용한다. 이동은 ISE-166이다.

## 검토한 대안
- A. infrastructure에 두고 SA 표에 "들어오는 쪽은 application 호출"을 보탠다: 작업량은 가장 적다. 하지만 infrastructure 안에 의존 방향이 다른 두 종류가 섞여 예외로 설명해야 하고, 다음 담당자도 같은 혼란을 겪는다.
- 대기열만 presentation으로 옮긴다: 모듈마다 위치가 달라져 규칙이 없는 것과 같다.
- 헥사고날 패키지(`adapter/in`, `adapter/out`)로 바꾼다: 이번 문제는 4계층 표가 입구를 빠뜨린 문서 빈칸이다. 기능·테스트에서 생긴 문제가 없어 구조 전체를 바꿀 이유가 없다.

## 결과
- 얻는 것: 의존 방향이 계층 표 하나로 설명된다(presentation → application → domain ← infrastructure). 같은 유스케이스를 HTTP·Kafka·스케줄러가 함께 쓰고, Kafka 없이 서비스만 불러 테스트하는 성질은 그대로다.
- 감수하는 것: 기존 입구 클래스와 테스트를 옮기면서 패키지 참조·테스트 경로를 빠뜨리지 않아야 한다. "presentation = 화면"이라는 이름 때문에 소비자가 들어 있는 것이 처음엔 어색하다.
- 적용 범위 밖: 결제의 `BackgroundJobScheduler`와 `RecoveryConfiguration`·`HousekeepingConfiguration`은 스레드 풀을 직접 소유하는 실행기라 결제 담당이 판단한다. common의 `OutboxRelayScheduler`는 나가는 쪽이고, `LocalMessagingProbe`는 local 진단용이다.
- 다시 볼 조건: 의존 규칙이 다시 어긋나면 ArchUnit 같은 아키텍처 테스트로 강제한다.
