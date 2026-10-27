# 0014. 패키지 이름은 com.iseonjwa로 한다

- 상태: 채택
- 날짜: 2026-10-27
- 결정자: 팀 (2026-10-27 통합 회의, 결정 시트 D4)
- 관련 AI 판단: [통합 회의 결정](../ai-judgments/kangmin/2026-10-27-integration-meeting-decisions.md)

## 맥락

초기 환경 브랜치마다 패키지 이름이 달랐다. 준현 브랜치(통합 기준)는 `iseonjwa.*`, 효승·강민·영은 브랜치는 `com.iseonjwa.*`, 권우 브랜치는 `com.highquality.*`였다. SA 13장은 `iseonjwa`를 `(팀 확인)`(U12)으로 남겨 두었다. 서비스 코드가 쌓인 뒤 바꾸면 전 서비스를 고쳐야 하므로 통합 전에 하나로 정했다.

## 결정

모든 모듈의 최상위 패키지를 `com.iseonjwa`로 한다. 예: `com.iseonjwa.common.response`, `com.iseonjwa.user.application`. Gradle `group`도 `com.iseonjwa`다.

## 검토한 대안

- `iseonjwa.*`: 기준 브랜치 그대로라 바꿀 것이 없다. 자바 관례(도메인 역순)와 다르다.
- `com.highquality.*`: 저장소 이름에서 왔지만 팀 이름과 다르고 한 브랜치만 썼다.

실제 도메인이 없어 어느 쪽도 기술적 문제는 없다. 팀은 관례에 가까운 `com.iseonjwa`를 골랐다.

## 결과

- 통합 브랜치에서 소스 폴더를 `src/*/java/com/iseonjwa`로 옮기고 `package`·`import`·문자열 참조를 일괄 변경했다.
- 문자열로 패키지를 쓰는 곳은 일괄 변경에서 빠지기 쉽다. `@RestControllerAdvice(basePackages = "iseonjwa")`가 남아 공통 예외 처리기가 어느 컨트롤러에도 적용되지 않았고, `CommonWebTest` 6개가 실패해 발견했다. `com.iseonjwa`로 고쳤다.
- 패키지 이름이 아닌 `iseonjwa`(DB 사용자, Eureka 클러스터 이름, compose 프로젝트 이름, 관리자 이메일 도메인)는 바꾸지 않았다.
