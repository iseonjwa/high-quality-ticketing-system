# 결정 기록 (ADR)

되돌리기 어려운 결정과 그 이유를 남긴다. 작성 방법은 [`adr` 스킬](../../.agents/skills/adr/SKILL.md)을 따른다. AI 제안을 두고 내린 결정이면 [`docs/ai-judgments/`](../ai-judgments/README.md)의 기록과 서로 링크한다.

| 번호 | 제목 | 상태 | 날짜 |
|---|---|---|---|
| 0001 | [Java 21 사용](0001-java-21.md) | 채택 | 2026-10-26 |
| 0002 | [공통 모듈 개인 초안의 빌드와 의존성](0002-common-foundation-draft.md) | 대체됨 (0004) | 2026-10-26 |
| 0003 | [같은 저장소의 native Config Server 사용](0003-config-server-native.md) | 채택 | 2026-10-26 |
| 0004 | [전체 워크스페이스와 M1 인증 구축](0004-workspace-auth-bootstrap.md) | 채택 (공통 모듈 구조는 0013, 패키지 이름은 0014로 대체) | 2026-10-26 |
| 0005 | [인증 Redis 장애 시 우선 요청 차단](0005-auth-redis-fail-closed.md) | 채택 (10/27 통합 회의) | 2026-10-26 |
| 0006 | [경로별 인증 판정은 Gateway 한 곳에서만 한다](0006-gateway-single-access-policy.md) | 채택 | 2026-10-27 |
| 0007 | [로그인 5회 실패 잠금과 관리자 비밀번호 초기화](0007-login-lockout-admin-reset.md) | 채택 (10/27 통합 회의) | 2026-10-27 |
| 0008 | [내부 신뢰 모델과 인증 설계](0008-internal-trust-and-auth-design.md) | 설계 중 (SA 유지, 보강 후보 선택 대기) | 2026-10-27 |
| 0009 | [서비스가 Access JWT를 직접 검증하는 방향 선택](0009-service-access-jwt-verification.md) | 철회 (SA 유지) | 2026-10-27 |
| 0010 | [인증 상태 확인과 세션 변경을 사용자 행 잠금으로 직렬화](0010-auth-session-serialization.md) | 채택 (브랜치 구현) | 2026-10-27 |
| 0011 | [앱 검증은 Java·JUnit으로 관리한다](0011-junit-verification-boundary.md) | 대체됨 (0012) | 2026-10-27 |
| 0012 | [개발 도구와 검증을 Java·Gradle로 통일한다](0012-java-gradle-workspace-tools.md) | 채택 (브랜치 구현) | 2026-10-27 |
| 0013 | [공통 모듈은 common 하나로 두고, 메시징만 만들 때 common-messaging으로 분리한다](0013-single-common-module.md) | 채택 (10/27 통합 회의. 메시징도 common에 두는 것으로 조정) | 2026-10-27 |
| 0014 | [패키지 이름은 com.iseonjwa로 한다](0014-package-name-com-iseonjwa.md) | 채택 | 2026-10-27 |
| 0015 | [대기열 입장 처리와 입장 토큰 발급의 안전장치](0015-queue-admission-guards.md) | 제안 | 2026-10-27 |
