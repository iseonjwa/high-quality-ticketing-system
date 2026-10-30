# 공통 모듈·인증의 dev 통합 검토

**준현 브랜치를 구현 기준으로 삼고, 효승의 공통 응답·Gateway 보완과 강민의 빌드·DB 테스트 방식을 선별 이식하는 안을 추천한다.** 회원·세션 업무가 구현된 유일한 후보이고 실행 도구·CI까지 연결되어 있어, 다른 골격에서 인증을 다시 조립하는 비용을 줄일 수 있다. 준현의 모든 정책을 승인하거나 현재 코드를 완성품으로 판단한다는 뜻은 아니다.

2026-10-27 조사 결과다. 실제 병합·코드 수정·브랜치 생성·커밋·push·PR·Notion 변경은 하지 않았다. 이 문서는 팀 논의를 위한 추천이며 채택 결정이 아니다.

## 먼저 정할 사항

1. 준현을 코드 기준으로 선택하되 Redis 장애 정책은 별도로 정한다. 현재 Notion #48은 **fail-open 잠정**, 준현은 **503으로 차단**하므로 자동 승계할 수 없다.
2. #60의 단일 common 제안과 현재 모듈 분리를 비교한다. 최초 통합은 현재 경계를 유지하고 단순화는 별도 변경으로 검증하는 안을 추천한다.
3. JWT 키 해석·용도 구분, 세션 폐기 범위, 패키지·응답 API·라우트 소유권을 먼저 하나로 맞춘다. 컴파일 성공만으로 이 계약들이 같아지지 않는다.

회의에서는 [결정 안건과 짧은 답변 양식](team-decisions.md)을 사용한다. 결정 후 실행할 순서와 완료 조건은 [조건부 통합 계획](integration-plan.md)에 있다.

## 브랜치별 장점과 기준 선택

공통 기준 dev는 `4e02b5e`다. 아래 다섯 커밋은 모두 그 후손이며 전체 SHA·원격 이름은 [Git 스냅샷](git-conflicts.md#스냅샷)에 있다.

| 브랜치·고정 SHA | 살릴 장점 | 통합 기준으로 쓸 때의 한계 |
| --- | --- | --- |
| 준현 `45874b7` | 회원가입·로그인·Refresh 회전·로그아웃·잠금·비밀번호 변경/초기화, 공통 인가·감사, 7개 앱·격리 도구·CI | 장애 정책이 팀 잠정과 다르다. 응답 편의·메서드 검증·JWT 오류 구분 보완과 DB/Redis 재검증이 필요하다 |
| 권우 `6099469` | 작은 응답·예외 API, `ok/created`, MVC/JPA `compileOnly`로 전이 의존 제어 | 인증 업무·감사·이벤트 구현이 없고 Config/Mock PG도 없다. HTTP 예외 처리와 Compose를 그대로 대체할 근거가 부족하다 |
| 효승 `acb8627` | Servlet 자동 구성, 성공 trace 주입·메서드 검증 errors, Redis 설정 분리·라우트 테스트·Eureka 수집 | 인증은 Gateway 범위다. JWT 계약·장애 정책·감사 헤더·advice 제외 범위를 수정해야 한다 |
| 강민 `965f000` | version catalog·convention 플러그인, Config 파일 테스트 입력, PostgreSQL 18 Testcontainers·Flyway, 서비스별 DB 계정 | 공통 모듈과 인증은 골격이다. Kotlin DSL 전환·DB 버전 변경까지 동시에 합치면 회귀 원인 구분이 어려워진다 |
| 영은 `86e2e66` | 간결한 모듈 구성, 책임을 나눈 package-info, Gateway 헤더 제거 선언 | 인증·공통 기능 구현과 테스트가 없다. Outbox 설명을 실행 코드로 계산할 수 없다 |

기준 선택은 코드량·테스트 건수 순위가 아니라 이미 연결된 업무 흐름과 다시 구현할 비용에 근거한다. 공통 타입은 [공통 계약 비교](common-contracts.md), 인증 흐름은 [인증 검토](auth-review.md), 실행 기반은 [빌드·인프라 검토](infra-build.md)에 근거가 있다.

## 가져올 것·수정할 것·제외할 것

아래의 ‘유지’는 기준 코드의 출발점을 뜻한다. 팀 결정에 따른 패키지 정렬·수정·회귀 검증은 별도로 수행한다.

| 분류 | 대상 | 처리 방식과 이유 |
| --- | --- | --- |
| 기준 코드 유지 후보 | 준현 AuthService·RedisSessionStore·DB V1/V2·컨트롤러·테스트 | 회원·세션 흐름을 묶어 가져온다. 단일 Refresh·잠금·Access 폐기 의미는 D06에서 정한다 |
| 기준 코드 유지 후보 | 준현 사용자 헤더 제거·RequireRole·내부 경로 차단·관리자 User 라우트 우선순위 | 신원 전달·역할·라우팅을 함께 유지한다. 서비스 직접 접근 통제는 실제 기동 검증이 필요하다 |
| 기준 코드 유지 후보 | 준현 CurrentActorProvider·시스템 actor NULL·EventEnvelope | 감사와 이벤트 공개 타입을 각각 하나로 둔다. Outbox·SAGA는 후속 구현 범위다 |
| 수정 후 채택 후보 | 준현 응답/JWT + 효승 trace advice·메서드 검증 errors + 권우 성공 팩토리 | 기존 계약을 보존하며 반복을 줄인다. advice 제외 범위·HTTP/body status·빈 jti·만료 오류를 검증한다 |
| 패턴 이식 후보 | 효승 Redis 설정 분리·라우트/인증 테스트 시나리오 | 클래스 전체 복사 대신 누락 시나리오를 기준 계약에 맞춘다. 필터·서킷 설정의 정본을 중복 생성하지 않는다 |
| 패턴 이식 후보 | 강민 PostgreSQL Testcontainers·Config 입력 추적, 효승/강민 서비스별 DB 계정 | DB 버전·변수·도구를 맞춘 후 이식한다. 통합 테스트 방식의 채택과 PostgreSQL 18 전환은 별개다 |
| 후속 변경 후보 | 강민 catalog/convention, 효승 Eureka 동적 수집 | 첫 인증 통합 후 별도 변경으로 검증한다. 관리 포트 9080과 실제 수집 주소를 다시 맞춘다 |
| 무수정 채택 제외 | 효승 JWT verifier/인증 필터/블랙리스트의 동시 등록, 원시 헤더 감사 | 토큰 해석·중복 실행·장애 정책·감사 신뢰 경계가 충돌한다 |
| 무수정 채택 제외 | 권우 MVC handler 전체 대체·Compose, 강민/영은 골격으로 실제 기능 대체 | HTTP 상태/헤더와 계정 생성/healthcheck 문제가 남는다. 골격 자체는 기능 대체물이 아니다 |

## Git 충돌과 의미 충돌

`git merge-tree --write-tree --name-only --no-messages <SHA A> <SHA B>`로 조사한 **10개 쌍 모두 텍스트 충돌**이 있다. 다음 수치는 각 브랜치를 준현과 독립 비교한 충돌 파일 수이며 누적 병합 결과가 아니다.

| 준현과 비교 | 권우 | 효승 | 강민 | 영은 |
| --- | ---: | ---: | ---: | ---: |
| 충돌 파일 수 | 13 | 26 | 26 | 18 |

[전체 충돌표·파일 목록](git-conflicts.md)과 [원시 Git 결과](git-evidence.jsonl)를 참조한다. 빌드·설정 파일의 텍스트 충돌을 해소한 뒤에도 세 패키지, 응답/ErrorCode API, JWT 키 encoding, Redis 장애·영속성, 관리자/알림 라우트가 충돌한다. 서로 다른 패키지의 클래스가 모두 남으면 Git은 성공해도 중복 빈·상충 동작이 생길 수 있다.

## 이번에 실행한 검증

고정 SHA를 별도 임시 디렉터리에 추출해 실행했다. 아래 결과는 범위가 달라 합산해 통합 품질 점수로 사용하지 않는다.

| 대상 | 실제 명령 | 확인 결과 | 한계 |
| --- | --- | --- | --- |
| 준현 | `./gradlew :common-auth:test :common-web:test :gateway:test :user-service:test --tests 'iseonjwa.user.AuthConfigurationTest' --no-daemon` | 성공, JUnit 77건·실패/오류/skip 0 | User는 설정 8건만 실행. DB/Redis AuthIntegrationTest 20건 미실행. Gateway는 mock 기반 |
| 권우 | `bash gradlew :common:compileJava :common:test --no-daemon` | 성공, JUnit 6건·실패/오류/skip 0 | common MockMvc 범위. 전체 build·Gateway 기동 미실행 |
| 효승 | `bash gradlew build --no-daemon --console=plain` | 성공, JUnit 31건·실패/오류/skip 0 | 공통·Config·Gateway·User context 범위. 전체 스택 연동 아님 |
| 강민 | `bash gradlew build --no-daemon --console=plain` | 성공, JUnit 11건·실패/오류/skip 0 | PostgreSQL 18 Testcontainers·Flyway 포함. 회원 인증·SAGA 검증 아님 |
| 영은 | `bash gradlew build --no-daemon --console=plain` | 성공, 테스트 0건·NO-SOURCE | 컴파일·JAR 생성만 확인 |

Java 경로·추출 방식·로그 위치는 [인증 실행 근거](auth-review.md#이번-검증), [권우 실행 근거](common-contracts.md#실행한-검증과-한계), [인프라 실행 근거](infra-build.md#실행-검증)에 있다. 권우·효승·영은 `gradlew`가 0644여서 `bash`로 실행했으며 권우 최초 직접 실행은 permission denied로 실패했다.

전체 Compose, 실제 Gateway→업무 서비스, Redis 장애·복구, 관측 수집, 부하·운영 용량과 **아직 만들지 않은 통합본**은 검증하지 않았다. 기존 Docker 컨테이너의 중단·일시정지는 하지 않았다. 이번 문서 종합에서 추가 테스트를 실행하지 않았다.

문서 검증은 고정 커밋 코드 링크 80개·내부 링크 23개·파일별 줄수·후행 공백에 이상이 없었다. `git diff --check`와 `bash script/records-check.sh`도 통과했다. 기록 검사는 기존 AI 판단 기록 7건의 근거 미기재 경고를 출력했으며 해당 기록은 수정하지 않았다.

## SA와 다르게 검토할 제안

확정 계약 #19는 유지한다. #21·#30·#48·#56·#57은 잠정이고 #60은 제안 상태다. [정본 상태와 공식 출처](source-notes.md)를 기준으로 판단한다.

- D05는 #48 fail-open을 보호 API의 503 차단으로 바꾸는 안을 추천한다. 로그아웃 토큰의 조회·예약·관리자 요청도 위험에 포함되기 때문이다. Redis 장애 중 보호 API 가용성을 잃는 비용은 팀이 선택해야 한다.
- D02는 #60 단일 common과 최소 response 분리도 유효한 대안으로 다룬다. 현재 권우를 포함해 **5개 Gateway 모두 WebFlux**이므로 #60의 권우 MVC 설명을 현재 구현 근거로 사용하지 않는다.
- D07은 현행 헤더 신뢰를 유지하되 네트워크 격리가 보장되지 않을 때 서비스 JWT 검증을 다시 논의하도록 제안한다. 철회된 ADR 0009를 되살린 결정이 아니다.

위 추천의 선택지·이유·감수 비용·담당 역할은 [회의 안건](team-decisions.md)에 모았다. 팀 답변을 받은 뒤에만 [통합 계획](integration-plan.md)을 실행한다.
