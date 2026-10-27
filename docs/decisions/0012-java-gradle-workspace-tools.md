# 0012. 개발 도구와 검증을 Java·Gradle로 통일한다

- 상태: 채택 (사용자 선택에 따른 브랜치 구현)
- 날짜: 2026-10-27
- 결정자: Junhyun An
- 관련 AI 판단: [전체 Java·Gradle 이관 선택](../ai-judgments/junhyun/2026-10-27-java-gradle-tools.md)
- 대체: [ADR 0011](0011-junit-verification-boundary.md). 앱 JUnit 원칙은 유지하고 도구 범위를 확장한다.

## 맥락

앱 검증을 JUnit으로 옮긴 뒤, 환경·프로세스 도구는 Python에 남기는 안과 전체 이관 안을 비교했다.
사용자가 "Python 도구까지 모두 Java·Gradle로 통일"을 선택하고 완료 후 푸시까지 요청했다.

## 결정

검증 전용 `system-tests`와 개발 도구 라이브러리 `workspace-tools`를 사용한다. 실행 서비스와 업무 API는 추가하지 않는다.
기존 Python 앱·환경·포트·JVM 도구와 도구 테스트를 모두 제거한다. 구현은 Java, 실행 진입점은 Gradle, 회귀 검증은 JUnit으로 관리한다.

| 기능 | 진입점 |
|---|---|
| 개인 환경 파일 생성 | `initLocalEnv` |
| Compose 포트 규칙 검사 | `checkPortExposure` |
| 격리 인프라·전체 빌드·JUnit·기록 검사 | `verifyWorkspace` |
| 선택 앱의 loopback 실행 | `runLocal -Papp=<앱>` |
| 실행 중인 Compose 스택 검증 | `:system-tests:systemTest` |
| 소유한 앱 JVM 7개 기동·소켓·연동 검증·종료 | `verifyJvm` |

dotenv는 직접 새 문법으로 파싱하지 않고 Java에서도 Compose의 canonical JSON을 사용한다. 달러 복원·상위 환경변수 우선순위·앱별 비밀값 전달 범위를 유지한다.
환경 생성은 기존 파일을 덮어쓰지 않고 POSIX 0600으로 생성한다. 비밀값은 도구 출력과 테스트 결과에 원문을 남기지 않는다.
실행 도구는 소유한 프로세스만 정리한다. 시간 초과·정상 종료·중단 때 종료 신호 이후 실제 프로세스 종료까지 기다린다.

## 검토한 대안

- Python 실행 도구 유지: 앱 검증만 JUnit으로 옮길 수 있지만 사용자가 언어 통일을 선택했다.
- dotenv 직접 파싱: Compose와 해석이 달라질 위험이 있어 기존처럼 Compose에 위임한다.
- Gradle에서 Python만 호출: Python 의존성이 그대로이므로 선택한 목표를 충족하지 않는다.

## 결과

Python 설치가 필요 없고 CI·문서도 Gradle 진입점으로 통일한다. 기존 셸 에이전트 훅·기록 검사와 `check.sh` 호환 진입점은 Python을 사용하지 않는다.
JDK 21·Docker Compose가 필요하고 실제 JVM 소켓 검사는 기존과 같이 `lsof`를 사용한다. 파일 권한은 POSIX 환경에서 검증한다.
시스템·JVM 검사는 외부 상태에 의존하므로 캐시하지 않는다. 기본 CI 테스트에는 전체 앱 스택이나 실제 `.env`를 요구하지 않는다.

## 검증

기존 도구 회귀 14개를 이관하고 환경 파일 생성·보존 2개와 프로세스 종료 2개를 더해 도구 JUnit 18개를 검증했다.
기본 JUnit 124개와 실제 스택 시스템 13개가 통과했다. Java가 소유한 JVM 모드에서도 11개가 통과했고 재기동·Redis pause 옵션 2개는 생략했다.
`runLocal -Papp=mock-pg`의 실제 기동·readiness·수동 중단 후 앱 종료도 확인했다. 검사 후 Compose 스택을 복구했다.
