# 0063. API 문서는 springdoc으로 서비스마다 만들고, Swagger UI는 Gateway의 /api/docs 한 곳에서 띄운다

- 상태: 채택 (ISE-295)
- 날짜: 2026-11-16
- 결정자: 안준현
- 관련 AI 판단: [`docs/ai-judgments/junhyun/daily/2026-11-16.md`](../ai-judgments/junhyun/daily/2026-11-16.md) (붙이기로 한 판단. 방식은 AI가 정했다)

## 맥락

- SA MASTER(결정)는 MVP 시연을 "API 호출(HTTP 요청 파일·Swagger)"로 한다고 적었다. 하지만 11/16까지 어떤 모듈도 springdoc을 쓰지 않았고, 시연은 `http/` 요청 파일과 프론트 화면으로 했다.
- API는 서비스 네 곳(user·reservation·payment·assistant)에 나뉘어 있고 바깥에서는 Gateway(WebFlux)만 보인다. 서비스는 Gateway가 검증해 넣은 사용자 헤더를 서명 없이 믿으므로 바깥에서 서비스로 바로 가는 길을 열면 안 된다(`frontend/nginx/app.conf`).
- 배포 nginx는 `/api/`만 Gateway로 넘기고 나머지는 화면(SPA)으로 돌린다.

## 결정

1. 라이브러리는 springdoc-openapi **3.2.0**을 쓴다. Spring Boot 4.1.2를 부모로 빌드된 판이라 우리 Boot와 맞는다. 버전은 루트 `build.gradle`의 `springdoc-openapi-bom` 한 곳에서 정한다.
2. 서비스 네 곳은 문서만 내는 `springdoc-openapi-starter-webmvc-api`를 쓴다. 화면 파일은 넣지 않는다.
3. Gateway가 `springdoc-openapi-starter-webflux-ui`로 Swagger UI 하나를 띄우고, 서비스 문서를 드롭다운으로 고르게 한다. 진입 주소는 `/api/docs`이고 화면 파일(`/api/swagger-ui/index.html`)로 넘긴다. 서비스 문서는 `/api/docs/<서비스 이름>`이다. 모두 `/api/` 아래라 배포 nginx를 고치지 않는다.
4. 서비스 문서는 Gateway 라우트(`<서비스>-api-docs`)가 그 서비스의 `/v3/api-docs`로 넘긴다. 문서 경로는 로그인 없이 연다(AccessPolicy `GET /api/docs/*`).
5. 문서의 서버 주소는 `/`로 둔다. Try it out 요청이 Gateway를 거쳐 로그인 확인·사용자 헤더 주입·입장 토큰 검증을 그대로 받는다. 인증은 JWT Bearer 하나를 문서 전체에 건다(`CommonApiDocsConfiguration`).
6. 문서에는 `/api/v1/**`만 넣고 `/api/v1/internal/**`은 뺀다. Gateway가 만들어 넣는 헤더(`X-Entry-Token-Id`)는 숨기고, Gateway가 요구하는 입장 토큰(`X-Entry-Token`)은 좌석 조회·선점 세 API에 입력 칸으로 보여 준다.

## 검토한 대안

- **서비스마다 Swagger UI를 띄움**: 설정은 가장 적다. 하지만 서비스 포트로 바로 열어야 해 Gateway 인증을 건너뛰고, 배포에서는 서비스 포트를 열지 않으므로 쓸 수 없다. 기각.
- **Gateway가 서비스 문서를 하나로 합침**: 화면에서 고르는 단계가 없다. springdoc이 기본으로 하지 않아 직접 합치는 코드가 필요하고, 서비스마다 같은 이름의 스키마가 다른 내용으로 부딪힐 수 있다. 기각.
- **Swagger UI를 `/swagger-ui`(springdoc 기본 경로)에 둠**: 배포 nginx가 `/api/` 밖은 화면으로 돌리므로 nginx에 경로를 더해야 한다. `/api/docs` 아래에 두면 로컬·배포 설정이 같다. 기각.
- **Gateway 라우트 대신 Eureka로 서비스 문서를 찾아 자동 등록**: 서비스가 늘 때 설정을 고치지 않아도 된다. 서비스가 네 개로 고정이고, 라우트로 두면 `GatewayRoutesTest`가 화면 목록과 라우트가 맞는지 검사할 수 있다. 기각.

## 결과

- 로컬과 배포 모두 `<주소>/api/docs`로 연다. 배포 서버에서는 누구나 API 목록을 볼 수 있다. 문서에 적힌 API를 부를 때는 Gateway 인증과 역할 검사를 그대로 거치므로 문서가 열린다고 권한이 넓어지지 않는다.
- 문서는 코드에서 자동으로 만든다. 요약·예시 같은 설명은 아직 없다. 필요한 API부터 `@Operation`·`@Schema`로 보탠다.
- Gateway에 springdoc이 들어가도 서블릿 라이브러리는 들어오지 않는다(`GatewayClasspathTest`).
- Spring Boot를 올리면 springdoc도 그 Boot에 맞는 판으로 함께 올린다.
- Gateway 메모리는 배포 측정에서 빠듯했다(1,024MiB 컨테이너에서 최대 약 785MiB). springdoc이 기동 때 클래스를 더 올리므로 다음 배포 측정에서 Gateway와 서비스 메모리를 본다(미측정).
- Swagger UI 파일(약 1.7MB)은 `text/javascript`로 오므로 nginx 압축 대상에 이 형식을 더했다(`frontend/nginx/app.conf`).
- 배포 서버에서 문서를 닫으려면 Gateway에 `springdoc.swagger-ui.enabled=false`, `springdoc.api-docs.enabled=false`를 주고 문서 라우트를 지운다.
