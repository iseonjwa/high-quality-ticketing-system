# 문제 해결 기록

막혔던 문제와 원인, 해결 방법을 남긴다. 작성 방법은 [`troubleshooting` 스킬](../.agents/skills/troubleshooting/SKILL.md)을 따른다. 항목은 `TS-{번호}`로 맨 아래에 추가한다.

### TS-1 멀티모듈 전환 후 루트 `./gradlew build` 실패 (common bootJar, test fixtures, Eureka 의존성)
- 서비스·작성: 공통(common·Gateway·Eureka·도메인 서비스) / kwonwoo / 2026-10-27
- 문제: 단일 모듈을 `common`, `eureka-registry`, `gateway-edge`, `*-service`로 나눈 뒤 빌드가 실패했다. 앞 오류를 고치면 다음 오류가 드러나는 순서였다.
  1. `Execution failed for task ':common:bootJar'` / `Main class name has not been configured`
  2. `Unable to find a variant of 'project ':common'' with the requested capability: feature 'test-fixtures'`
  3. `org.springframework.boot:spring-cloud-starter-netflix-eureka-client FAILED` (user·reservation·payment 서비스)
- 원인:
  1. 라이브러리인 `common`에 `org.springframework.boot` 플러그인이 적용돼 실행 jar(`bootJar`)를 만들려 했고, `main` 클래스가 없었다.
  2. 루트가 서비스에 `testFixtures(project(':common'))`를 걸었지만 `common`에 `java-test-fixtures` 플러그인이 없었다.
  3. Eureka client의 group을 `org.springframework.boot`로 적었다. 올바른 group은 `org.springframework.cloud`다.
- 해결:
  - `common/build.gradle`을 `java-library` + `java-test-fixtures`로 바꾸고 Boot 플러그인을 뺐다. `@SpringBootTest`만 있던 `CommonApplicationTests`는 설정 클래스가 없어 실패하므로 지웠다.
  - 공통 의존성을 루트 `build.gradle`로 모았다. 모든 모듈은 Java 21·BOM·Lombok·`spring-boot-starter-test`, 실행 모듈은 Boot 플러그인·Actuator·Prometheus, 서비스는 common·Eureka client·webmvc·JPA·validation·PostgreSQL이다.
  - 모듈 안에 남은 Initializr 단독 빌드 파일(`settings.gradle`, `gradlew`, `gradle/wrapper`, `.gitattributes`)을 지웠다.
  - 테스트에서 Gateway·서비스는 `eureka.client.enabled=false`로 Eureka 접속 시도를 끈다. `eureka-registry`에 같은 값을 주면 `No qualifying bean of type 'com.netflix.appinfo.ApplicationInfoManager'`로 실패하므로 제외했다.
  - 루트 `ext`의 `springCloudVersion`을 서브프로젝트가 암묵적으로 찾는 방식은 Gradle 9.8에서 deprecation 경고(Gradle 10에서 오류)가 나서 스크립트 변수로 바꿨다.
  - 결과: `./gradlew clean build --warning-mode all` 성공, 경고 0, 테스트 5개 통과. Eureka와 Gateway jar를 띄워 Gateway 등록과 경로별 `lb://` 서비스 이름 해석을 확인했다.
- AI 활용: Claude Code가 `--continue`와 모듈별 `dependencies` 해석으로 숨은 오류까지 찾고 수정안을 냈다. 판단 기록은 [ai-judgments/kwonwoo/2026-10-27-multimodule-build-fix.md](ai-judgments/kwonwoo/2026-10-27-multimodule-build-fix.md).
- 배운 점: 멀티모듈에서 첫 실패가 뒤 오류를 가리므로 `./gradlew build --continue`와 `:<모듈>:dependencies`로 한 번에 드러낸다. 라이브러리 모듈에는 Boot 플러그인을 적용하지 않는다.

### TS-2 IntelliJ Gradle 동기화 실패: `Task 'prepareKotlinBuildScriptModel' not found in project ':common'`
- 서비스·작성: 공통(IntelliJ 설정) / kwonwoo / 2026-10-27
- 문제: IntelliJ에서 Gradle을 다시 불러오면 모든 모듈이 `Task 'prepareKotlinBuildScriptModel' not found in project ':common'.` / `BUILD FAILED`로 실패했다. 터미널 `./gradlew clean build`는 성공했다.
- 원인: 모듈을 Spring Initializr로 따로 만들면서 IntelliJ의 `.idea/gradle.xml`에 루트 외에 모듈 6개가 각각 독립 Gradle 프로젝트로 연결돼 있었다. 모듈 안의 `settings.gradle`을 지운 뒤에는 모듈 폴더에서 실행한 Gradle이 루트 빌드의 하위 프로젝트(`:common`)로 동작한다. IDE 연동용 `prepareKotlinBuildScriptModel` 작업은 루트 프로젝트에만 있어서 하위 프로젝트 기준 요청이 실패한다. `./gradlew -p common prepareKotlinBuildScriptModel`로 같은 오류를 재현했고, 루트에서 실행하면 성공했다.
- 해결: Gradle 창에서 모듈 6개의 연결을 Unlink Gradle Project로 해제하고 루트만 남긴다(`.idea/gradle.xml`의 `GradleProjectSettings`가 루트 하나). 이어서 Reload All Gradle Projects를 실행한다. `.idea/`는 커밋 대상이 아니라 팀원마다 각자 한 번 해야 한다.
- 배운 점: 멀티모듈에서는 루트만 Gradle 프로젝트로 연결한다. Initializr로 모듈을 추가하면 모듈 안의 `settings.gradle`·`gradlew`와 IDE의 별도 연결이 함께 생기므로 둘 다 정리한다.
