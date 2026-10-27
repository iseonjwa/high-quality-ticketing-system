# 0004. 실행 가능한 전체 워크스페이스와 M1 인증까지 구축

- 상태: 채택
- 날짜: 2026-10-26
- 결정자: Junhyun An
- 관련 AI 판단: [공통 라이브러리만 구현한 범위 거부](../ai-judgments/junhyun/2026-10-26-workspace-scope-correction.md)

## 맥락

[ADR 0002](0002-common-foundation-draft.md)는 공통 라이브러리 개인 초안만 다뤘다. 사용자는 실행 가능한 Spring Boot 워크스페이스, Gateway, Config Server, Eureka와 전체 기반 설정이 필요하다고 지적했고 완료 기준에 인증 기능을 포함했다.

## 결정

기존 브랜치와 변경을 보존해 Boot 앱 7개, 공통 라이브러리 4개, PostgreSQL 3개·Redis·Kafka와 관측 도구를 구성한다. Java 21·Gradle 9.1.0·Boot 4.1.2·Cloud 2025.1.4를 사용한다. 업무 서비스는 SA의 User·Reservation·Payment 3개를 유지한다.

User의 인증 API 5개와 Gateway의 JWT 검증·헤더 정리·ADMIN 권한 검사를 연결한다. Access와 Refresh 서명 키를 분리한다. Refresh는 사용자당 하나의 해시로 저장하고 원자적 회전으로 동시 재사용을 막는다. 로그아웃은 Refresh 제거와 Access 블랙리스트 기록을 Redis 스크립트로 처리한다.

Gateway에는 MVC·JPA 의존성을 넣지 않는다. common-web은 MVC 예외·내부 사용자 컨텍스트를 제공하며 서비스가 명시적으로 import한다. User 응용 계층은 Redis·JWT 구현 대신 도메인 포트에 의존한다.

## 검토한 대안

- 공통 라이브러리만 검증: 사용자가 범위가 부족하다고 거부했다.
- 전체 기동·연결만 검증: 사용자가 인증 기능까지 포함하는 선택지를 골랐다.

## 결과

빌드·기동·인증·관측성을 각각 검증한다. local 전용 진단 API로 사용자 전달과 Reservation → Kafka → Payment 연결을 확인한다. 업무 예매·결제·Mock PG 동작과 Outbox는 후속 기능이다. 실행·검증 명령과 확인 범위는 [워크스페이스 가이드](../guides/workspace.md)에 둔다.
