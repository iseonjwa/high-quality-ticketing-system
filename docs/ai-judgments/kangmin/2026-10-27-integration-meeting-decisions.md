---
title: 통합 회의에서 AI 결정 시트 추천 중 공통 모듈, 리뷰, 패키지를 다르게 정함
date: 2026-10-27
updated: 2026-10-27
judge: 팀 (전달: kangmin)
ai: Claude Code (브랜치 5개 비교 분석, 통합 시안, 결정 시트 작성)
verdict: 부분 수용
area: 협업
presentation: 후보
shared: 예
---

# 통합 회의에서 AI 결정 시트 추천 중 공통 모듈, 리뷰, 패키지를 다르게 정함

## 맥락
- 해결하려던 작업: 팀원 5명의 초기 환경 브랜치를 하나로 합치기 전에 쟁점 D1~D17을 회의로 정한다.
- AI 활용 방식: AI가 브랜치별 빌드·테스트와 충돌을 계산해 통합 시안(`ggangmy/chore/integration`)을 만들고, 항목마다 선택지·추천·감수할 점을 적은 [결정 시트](../../integration/decision-sheet.md)를 냈다. 팀이 회의에서 결정 칸을 채웠다.

## AI 제안
- D1 준현 브랜치 기준, D2 Groovy, D3 Gradle 9.8.2, D7 `build`만으로 테스트 통과, D8 Windows 수정 유지, D9 팀 규칙 변경은 별도 PR, D10 ADR·TS 번호 이어 붙임, D12 PostgreSQL 17.
- D4 패키지 이름은 하나로 확정(어느 쪽인지는 추천하지 않음).
- D6 공통 모듈은 기준 브랜치의 5개 모듈 유지. Gateway(WebFlux)가 MVC·JPA를 받으면 기동하지 못하므로 격리를 빌드 구조로 보장한다.
- D13 Lombok은 팀 규칙으로 정함. D14 블랙리스트 Redis 장애 정책은 추천하지 않음.
- D11 SA 범위 밖 기능 중 system-tests는 유지, 나머지는 항목별 결정.
- D15 Config 서버(native) 유지로 확정.
- D16 브랜치 `{type}/ISE-{번호}-{주제}`, dev에서 출발, squash, 승인 1명 이상 + CI, main·dev 보호, 마일스톤마다 main 반영.
- D17 통합 담당 1명 + 리뷰 1명.

## 판단
- 결과: 부분 수용
- 판단자: 팀 (전달: kangmin)
- 수용: D1, D2, D3, D5, D7, D8, D9, D10, D12, D15, D16의 이름·출발점·병합 방식(squash 기본, 필요하면 merge)·보호 규칙·main 반영.
- 선택: D4 `com.iseonjwa.*`, D13 Lombok 사용(허용 범위를 정하는 안), D14 closed로 하고 SA도 수정, D11 전부 채택.
- 거부
  - D6: 5개 모듈 대신 `common` 하나로 묶고 안에서 폴더(패키지)로 나눈다.
  - D16 리뷰: 승인 1명 대신 AI 검증만 하고 머지 뒤 통보한다. 다른 사람 일을 도울 때만 리뷰를 요청한다.
  - D17: 리뷰 담당을 두지 않는다. 통합 담당은 이강민.
- 근거: 결정 시트에 이유를 적지 않았다(미기재). D15에서는 "Config 서버 왜 씀?", "Config 서버 죽으면 다 죽는 거 아냐?"라는 질문이 나왔다.
- 검토한 대안: 결정 시트의 각 항목 선택지.

## 반영
회의 뒤 재확인 4건을 사용자가 정했다: 메시징도 `common`에 둔다, 회의에서 정한 문서 변경(D13·D14·D16)은 통합 PR에 포함한다, Lombok은 제한 없이 허용한다, 통합 브랜치 이름은 `chore/ISE-1-integration`이다. 메시징·문서 PR·브랜치 이름은 AI 추천과 같고 Lombok은 AI 추천(허용 범위 제한)과 다르게 정했다.

통합 브랜치 `chore/ISE-1-integration`(로컬)에 반영했다.
- 코드: 공통 모듈 5개를 `common` 하나로 합침(웹·JPA·Kafka·JWT는 `compileOnly`, `GatewayClasspathTest` 추가), 패키지 `com.iseonjwa`, 모든 모듈에 Lombok.
- 문서: [ADR 0013](../../decisions/0013-single-common-module.md)·0005·0007 채택, [ADR 0014](../../decisions/0014-package-name-com-iseonjwa.md) 추가, SA 사본 9-5·13장·기술 스택, `docs/guides/` 공통 모듈·워크스페이스·브랜치 전략·Lombok, [결정 시트](../../integration/decision-sheet.md) 회의 결과.
- 미반영: Notion SA·결정 기록 #30, GitHub 브랜치 보호 설정, dev PR.

## 검증
- `./gradlew verifyWorkspace`(Windows): 성공, 테스트 130개 실패 0(회의 전 127개 + `GatewayClasspathTest` 3개).
- `GatewayClasspathTest` 변이: `common/build.gradle`의 `spring-webmvc`, `spring-boot-starter-kafka`를 `api`로 바꾸면 각각 실패했다.
- 패키지 일괄 변경 때 `@RestControllerAdvice(basePackages = "iseonjwa")`가 남아 `CommonWebTest` 6개가 실패했고, 고친 뒤 통과했다.
- compose 이미지를 새로 빌드해 `:system-tests:systemTest` 13개 중 11개 통과, 2개는 Redis 장애 전용이라 건너뜀(회의 전과 같다).
- D15 질문 측정: Config 서버를 멈추자 실행 중인 서비스는 healthy를 유지했고, 재시작한 user-service는 `ConfigClientFailFastException`으로 종료됐다. 자세한 결과는 [학습 노트](../../learning/kangmin/qna/2026-10-27-config-server-role-tradeoffs.md)의 "Config 서버가 죽으면" 절에 있다.

## 판단 이력
- 2026-10-27 부분 수용: 회의에서 17개 항목을 정했다. D6, D16 리뷰, D17 리뷰 담당은 AI 추천과 다르게 정했다.
- 2026-10-27 반영: 재확인 4건을 정하고(Lombok은 추천과 다르게 제한 없이 허용) 통합 브랜치에 반영했다.

## 발표·면접 포인트
- D6은 AI가 "격리를 빌드 구조로 보장"을 근거로 5개 모듈을 추천했지만, 팀은 모듈 수를 줄이고 패키지로 나누기로 했다. 대신 Gateway 격리는 테스트로 지켜야 한다.
- 리뷰 없이 AI 검증 후 머지하기로 한 것은 4일 일정과 5명 병렬 작업을 고려한 선택이다. 대신 CI(`verifyWorkspace`)가 유일한 자동 관문이 된다.
