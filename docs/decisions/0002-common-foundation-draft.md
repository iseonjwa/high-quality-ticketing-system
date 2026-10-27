# 0002. 공통 모듈 개인 초안의 빌드와 의존성

- 상태: 대체됨 (0004)
- 날짜: 2026-10-26
- 결정자: Junhyun An (개인 초안 구현 지시)
- 관련 AI 판단: [공통 모듈 개인 초안 선택](../ai-judgments/junhyun/2026-10-26-common-foundation-draft.md)

## 맥락

M1에서는 각자 공통 모듈을 만든 뒤 비교해 통합한다. 저장소에는 빌드와 서비스 코드가 없다. SA 11-2의 응답 6키와 common-response·common-web 분리는 합의 전 제안이며 패키지명 iseonjwa도 팀 확인 대상이다.

## 결정

개인 비교용으로 common, common-response, common-web만 먼저 구현한다. Java 21, Gradle Wrapper 9.1.0, Groovy DSL, Spring Boot 4.1.2 BOM을 사용한다. 공통 모듈은 실행 서버가 아닌 일반 JAR다.

common-response는 Spring에 의존하지 않는다. common-web은 명시적으로 설정을 import한 MVC 서비스에만 제공한다. 공통 이벤트와 엔티티는 서비스 코드에 의존하지 않는다.

감사값은 JPA 콜백 리스너로 기록한다. 사용자 작업 뒤 시스템이 수정할 때도 updated_by를 명시적으로 NULL로 바꾼다. 사용자 공급자와 시계는 Spring 빈으로 주입하고 Hibernate의 SpringBeanContainer 연결을 요구한다. 데이터베이스 테스트로 연결 계약을 확인한다.

## 검토한 대안

- 빌드 기반만 구현: 사용자가 공통 모듈까지 포함한 개인 초안을 선택했다.
- 계약 합의 자료만 준비: 코드로 비교하고 테스트하는 계획을 선택했다.
- 전체 서비스 뼈대 생성: 인증·결제·Gateway·Outbox를 이번 범위에 포함하지 않는다.

## 결과

팀 통합 전에 형식과 모듈 경계를 비교할 수 있다. 팀 합의 뒤 변경될 수 있으며 이 문서를 팀 설계 채택으로 해석하지 않는다. Spring Cloud는 현재 모듈에서 사용하지 않아 의존성을 추가하지 않는다.

2026-10-26 사용자의 범위 수정 지시에 따라 [ADR 0004](0004-workspace-auth-bootstrap.md)로 대체했다. 당시의 개인 초안 계획은 이력으로 보존한다.
