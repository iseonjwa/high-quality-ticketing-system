---
title: 백엔드 MSA 프로젝트의 프론트를 같은 저장소에 둘지, Spring Boot 안에 넣을지
date: 2026-11-02
updated: 2026-11-02
asker: Junhyun An
level: 중급
tags: [frontend, monorepo, spa, same-origin, cors, reverse-proxy, deployment]
---

# 백엔드 MSA 프로젝트의 프론트를 같은 저장소에 둘지, Spring Boot 안에 넣을지

## 질문
현재 구축 중인 티켓팅 시스템의 프론트를 만들려고 한다. 저장소 안에 `frontend/` 폴더를 두는 것과 별도 저장소로 나누는 것 중 무엇이 좋은가. 스택은 Spring Boot 안에 넣을지, 별도 프론트 스택을 쓸지 고민이다. 배포는 하지만 상용 서비스가 아니고 프로젝트 기간에만 잠시 띄운다.

## 짧은 답
- 저장소: 같은 저장소의 `frontend/`가 맞다. 배포가 EC2 1대의 compose 하나이고, 화면이 따라야 할 API 계약 문서가 이 저장소에 있다. 별도 저장소의 장점(독립 배포 주기, 팀 경계, CI 분리)은 기간이 짧은 프로젝트에서 값을 하지 못한다.
- 스택: Spring Boot 안에 넣지 않는다. 토큰을 브라우저가 들고 `Authorization` 헤더로 보내는 구조라 서버 템플릿이 할 일이 없고, 새 JVM이 메모리 예산을 먹는다. 화면은 정적 파일로 만들고 nginx 컨테이너가 내준다.
- 정적 파일을 손으로 쓴 HTML·JS로 만들지(팀 결정 #7), React 같은 SPA로 빌드할지는 화면 수와 담당자의 프론트 경험으로 정한다.
- 배포는 nginx가 화면과 `/api/` 프록시를 함께 맡아 같은 출처(same-origin)로 만든다. 그러면 Gateway에 CORS 설정이 필요 없다.

## 자세히

### 1. 저장소 위치

| 기준 | 같은 저장소 `frontend/` | 별도 저장소 |
|---|---|---|
| 계약 동기화 | API가 바뀌면 백엔드와 화면을 한 PR로 고친다. AI 도구가 계약 문서와 DTO를 같은 작업 공간에서 읽는다 | 계약 문서를 복사하거나 링크로 따라간다. 어긋나도 늦게 안다 |
| 배포 | 기존 `compose.yml`에 컨테이너 하나를 더한다 | 두 번째 clone이나 이미지 배포, 또는 Vercel 같은 별도 호스팅이 필요하다 |
| CI | Java 빌드 워크플로가 화면 변경에도 돈다. `paths` 필터로 나눈다 | 처음부터 분리된다 |
| 도구 | Node가 새로 들어온다. Docker 다단계 빌드로 만들면 다른 팀원은 Node 없이 compose를 띄운다 | 백엔드 저장소는 Java·Gradle만 남는다 |
| 팀 규칙 | 기록 검사, 커밋 규칙이 화면 작업에도 적용된다 | 개인 규칙으로 갈 수 있다 |

별도 저장소가 맞는 경우는 프론트 팀이 따로 있거나, 배포 주기가 백엔드와 다르거나, 여러 백엔드가 같은 화면을 쓸 때다. 이 프로젝트는 셋 다 아니다.

### 2. Spring Boot 안에 넣는 방식이 맞지 않는 이유

Spring Boot에 화면을 넣는 방법은 두 가지다.

- 서버 템플릿(Thymeleaf) 모듈을 새로 만든다. 이 시스템은 Access 토큰을 브라우저가 들고 Gateway에 `Authorization: Bearer`로 보낸다. 서버가 화면을 그리면서 API를 대신 부르려면 서버 세션에 토큰을 두는 BFF(Backend for Frontend) 구조가 필요하다. 그러면 요청 경로에 한 단계가 더 생기고, "Gateway가 유일한 진입점"이라는 설계가 흐려진다. 대기 순번 폴링, 선점 남은 시간, 결제 확인 중 같은 화면은 어차피 브라우저 JS가 해야 해서 템플릿이 하는 일이 거의 없다. JVM 하나가 늘어 compose 기준 512MB가 더 든다.
- Gateway의 `classpath:/static`에 빌드 결과를 넣는다. Spring WebFlux는 정적 자원을 기본으로 내주고, 이 저장소의 인증 필터는 라우팅된 요청에만 걸리는 `GlobalFilter`라 정적 경로를 막지 않는다. 기술적으로는 된다. 다만 화면 한 줄을 고쳐도 Gateway 이미지를 다시 빌드하고 재시작해야 하고, 부하 측정 대상인 Gateway JVM이 정적 파일까지 내준다.

### 3. 정적 파일을 무엇으로 만들지

| 방식 | 장점 | 감수할 점 |
|---|---|---|
| 손으로 쓴 HTML·JS (ES 모듈) | 빌드 도구가 없다. 백엔드 개발자가 읽기 쉽다 | 인증 토큰 갱신, 입장 토큰 보관, 멱등키, 폴링, 에러 코드 문구 같은 공통 로직과 버튼·좌석 같은 UI 조각을 파일마다 반복하기 쉽다. 디자인 시스템이 CSS 변수와 클래스 규칙 수준에 머문다 |
| SPA (예: React + Vite + TypeScript) | 컴포넌트 단위로 디자인 시스템을 만든다. API 응답을 타입으로 적어 단위가 다른 필드(`nextPollAfterMillis`와 `nextPollAfterSeconds`)를 컴파일 단계에서 구분한다. AI 도구가 가장 많이 생성해 본 형태다 | Node와 빌드 단계가 생긴다. 프레임워크 동작(상태, 렌더링)을 모르면 생성된 코드를 디버깅하기 어렵다 |

빌드 결과는 둘 다 정적 파일이라 배포 방식은 같다.

### 4. 같은 출처로 배포하기

출처(origin)는 scheme, host, port의 조합이다. 화면을 `http://<ec2>`(80)에서 받고 API를 `http://<ec2>:8080`으로 부르면 포트가 달라 다른 출처가 되고, 브라우저가 CORS 응답 헤더를 요구한다.

nginx가 화면 파일과 `/api/` 프록시를 함께 맡으면 브라우저는 한 출처만 본다.

```
브라우저 ── http://<ec2>/            → nginx: 정적 파일
         └─ http://<ec2>/api/v1/...  → nginx → gateway:8080 (compose 내부 네트워크)
```

- 개발 중에는 Vite 개발 서버의 `server.proxy`나 같은 역할의 설정으로 `/api`를 `localhost:8080`에 넘긴다. 로컬에서도 CORS가 필요 없다.
- Vercel 같은 HTTPS 호스팅에 화면만 올리고 브라우저가 `http://<ec2>:8080`을 직접 부르면 혼합 콘텐츠(HTTPS 페이지의 HTTP 요청)라 브라우저가 막는다. 도메인과 인증서를 따로 준비해야 해서 짧은 프로젝트에는 비용이 크다.
- 부하 도구는 지금처럼 Gateway 8080을 직접 부르면 nginx가 측정 경로에 끼지 않는다.
- SPA는 주소(`/concerts/3`)를 브라우저 JS가 해석하므로, 새로고침해도 화면이 뜨도록 nginx가 없는 파일 경로에 `index.html`을 돌려준다(`try_files $uri /index.html`).

### 5. 폴더와 컨테이너는 다른 이야기다
- `frontend/` 폴더냐 별도 저장소냐는 코드를 어디에 두고 어떻게 PR·CI를 돌리느냐의 문제다.
- 별도 컨테이너냐는 어떻게 빌드하고 실행하느냐의 문제다. 같은 저장소의 `frontend/`에 둬도 자기 Dockerfile로 따로 빌드해 별도 컨테이너로 띄운다. Spring Boot 안에 들어가지 않는다.

### 6. "서버 간 통신"의 두 가지 뜻
| 방식 | API를 부르는 주체 | 토큰 위치 | 필요한 것 |
|---|---|---|---|
| 중계(리버스 프록시) | 브라우저 JS가 부르고 nginx가 Gateway로 넘긴다. nginx → Gateway 구간이 서버 간 통신이다 | 브라우저(메모리·`localStorage`·`sessionStorage`) | nginx 설정 몇 줄 |
| BFF(Backend for Frontend) | 프론트 서버(예: Next.js)가 직접 부른다. 브라우저는 프론트 서버만 안다 | 프론트 서버 세션, 브라우저에는 httpOnly 쿠키 | Node 서버, 세션 저장소, 서버 쪽 토큰 갱신. 대기열 폴링이 모두 프론트 서버를 거친다 |

BFF는 XSS로 토큰을 훔치기 어렵게 하는 장점이 있지만, 이 시스템의 계약(입장 토큰은 프론트 `sessionStorage`, `Authorization` 헤더)을 바꾸고 서버가 하나 늘어난다. 시연용 기간 한정 배포에서는 중계 방식으로 충분하다. 자세한 비교는 [중계와 BFF 노트](2026-11-02-reverse-proxy-vs-bff.md)에 있다.

### 7. Gateway에 CORS 설정이 없던 이유
CORS는 브라우저가 지키는 규칙이다. 지금까지 Gateway를 부른 것은 HTTP 요청 파일, JUnit 시스템 테스트, 부하 도구처럼 브라우저가 아닌 클라이언트라 CORS가 필요 없었다. 중계 방식이면 브라우저가 한 출처만 보므로 배포 후에도 필요 없다. 브라우저가 다른 출처에서 8080을 직접 불러야 하는 경우가 생길 때만 허용 출처를 정확히 적어 추가한다. 허용 출처를 넓게 열면 다른 사이트의 스크립트가 사용자 토큰으로 API를 부를 길이 생긴다.

## 이 프로젝트에서는
- 팀 결정 #7(Notion 「SA 기획·설계 결정 기록」, 10/24 결정): 안준현이 AI로 생성한 정적 HTML·JS로 Gateway API를 호출해 시연한다. React(Vite) SPA와 Thymeleaf는 검토 후 채택하지 않았다. 바꿀 조건은 "화면이 늘어 생성 코드 관리가 어려워지면 SPA 프레임워크로 전환"이다. 이 조건을 근거로 [ADR 0040](../../../decisions/0040-frontend-react-spa-in-monorepo.md)에서 React SPA로 바꿨다. Notion은 고치지 않고 저장소 문서를 정본으로 둔다. 팀원용 요약은 `docs/design/frontend/README.md`다.
- `docs/core/SA MASTER.md` 6-8: 최소 화면은 로그인 → 공연 상세(카운트다운) → 대기열 → 좌석 배치도·매수 선택 → 결제 → 결과·내 예매. 기술 선택은 담당자가 한다. 일정은 `docs/core/milestones.md` M4(11/9–11/16)다.
- 결정 #7의 "Gateway에 CORS 설정 필요"는 화면과 API의 출처가 다르다는 가정이다. nginx 같은 출처 구성이면 필요 없다.
- `gateway/src/main/java/com/iseonjwa/gateway/security/AccessPolicy.java`는 목록에 없는 경로를 로그인 필요(USER)로 본다. 화면이 부르는 API 중 공개는 회원가입·로그인·재발급·공연 목록·상세뿐이다.
- SA 9-2는 외부 공개 포트를 Gateway 8080 하나로 정했다. nginx 80을 열면 보안그룹 규칙표와 compose가 바뀌므로 배포 담당에게 `contract` 이슈로 알린다(`docs/guides/contract-issues.md`).

## 면접 포인트
- 백엔드 프로젝트에서 화면을 어디에 두었나: 같은 저장소에 두어 API 계약과 화면을 한 PR로 맞췄다. 별도 저장소는 배포 주기나 팀 경계가 다를 때 이득이다.
- CORS를 어떻게 처리했나: 리버스 프록시로 화면과 API를 같은 출처로 만들어 CORS 자체를 없앴다. CORS는 브라우저 보안 정책이라 서버 간 호출(부하 도구, 서비스 간 통신)에는 영향이 없다.
- Thymeleaf를 쓰지 않은 이유: 토큰을 브라우저가 들고 다니는 구조에서 서버 렌더링은 BFF가 필요하고, 실시간 갱신 화면은 결국 JS가 맡는다.

## 참고
- 저장소: `docs/core/SA MASTER.md` 2-4·6-8·9-2·9-3·11-1, `config-repo/gateway.yml`, `docs/design/queue/contracts.md` 2절
- MDN, Same-origin policy·Mixed content·CORS 문서
- Vite 문서, `server.proxy` 설정
