# 0040. 프론트는 같은 저장소의 frontend/에 React SPA로 만들고 별도 컨테이너로 띄운다

- 상태: 채택 (프론트 담당 결정. Notion 결정 기록 #7을 대체한다. 프론트 결정은 Notion이 아니라 이 저장소 문서를 정본으로 둔다. 2026-11-02 ISE-110에서 Refresh 토큰 보관 위치를 더했다)
- 날짜: 2026-11-02
- 결정자: Junhyun An (프론트 담당, SA 6-8 "기술 선택은 담당자")
- 관련 AI 판단: [React SPA 전환](../ai-judgments/junhyun/2026-11-02-frontend-react-spa.md), [BFF 대신 nginx 중계](../ai-judgments/junhyun/2026-11-02-frontend-relay-over-bff.md), [Notion 대신 저장소 문서에 기록](../ai-judgments/junhyun/2026-11-02-records-in-docs-over-notion.md), [팀 규칙으로 올림](../ai-judgments/junhyun/2026-11-02-docs-first-as-team-rule.md)
- 관련 학습 노트: [저장소 위치와 스택](../learning/junhyun/qna/2026-11-02-frontend-repo-and-stack.md), [React와 함께 쓸 도구](../learning/junhyun/qna/2026-11-02-react-spa-stack.md), [중계와 BFF](../learning/junhyun/qna/2026-11-02-reverse-proxy-vs-bff.md), [앞단 대기실과 서버 대기열](../learning/junhyun/qna/2026-11-02-waiting-room-vs-server-queue.md)
- 설계 요약: [`docs/design/frontend/README.md`](../design/frontend/README.md)

## 맥락

- Notion 「SA 기획·설계 결정 기록」 #7(10/24 결정)은 화면을 AI로 생성한 정적 HTML·JS로 만들고 Gateway API를 부르기로 했다. React(Vite) SPA와 Thymeleaf는 검토했지만 고르지 않았다. 바꿀 조건은 "화면이 늘어 생성 코드 관리가 어려워지면 SPA 프레임워크로 전환"이었다.
- 10/24 이후 화면 범위가 늘었다. SA 6-8의 일반 예매 흐름 6개 화면에 추첨(응모·결과·빌링키 등록), 예매 취소, 운영자 화면, AI 상담이 계획에 들어온다. 구현은 단계적으로 한다.
- 다음 단계에서 디자인 시스템을 만든다. 컴포넌트 단위가 필요하다.
- 화면 생성 방식이 Claude Artifact의 단일 파일에서 저장소 안의 Claude Code로 바뀌어, 구조를 갖춘 프로젝트를 만들 수 있다.
- 배포는 EC2 1대의 docker compose이고(SA 9-1), 프로젝트 기간에만 띄운다. 상용 서비스가 아니다.
- 브라우저가 Access 토큰을 들고 `Authorization: Bearer`로 Gateway를 부른다(SA 11-1). 입장 토큰은 프론트 `sessionStorage`에 둔다(`docs/design/queue/contracts.md` 2절).
- 화면이 쓸 API 중 공연·좌석 조회, 내 예매, 추첨, 취소는 아직 구현되지 않았다(11/2 기준).

## 결정

### 1. 코드 위치: 이 저장소의 `frontend/`

| 이유 | 내용 |
|---|---|
| 계약과 화면을 한 PR로 | API 계약 문서(SA 11장, `docs/design/*/contracts.md`)와 DTO가 이 저장소에 있다. API가 바뀌면 백엔드와 화면을 함께 고치고, AI 도구가 계약을 같은 작업 공간에서 읽는다 |
| 배포가 하나 | 기존 `compose.yml`에 컨테이너를 하나 더한다. 두 번째 배포 경로가 생기지 않는다 |
| 분리 이득이 없음 | 별도 저장소는 프론트 팀이 따로 있거나, 배포 주기가 다르거나, 여러 백엔드가 같은 화면을 쓸 때 이득이다. 이 프로젝트는 셋 다 아니다 |

### 2. 스택: React SPA, Spring Boot에 넣지 않는다

React 말고 다른 프레임워크는 아래 이유로 고르지 않았다.

| 대안 | 고르지 않은 이유 |
|---|---|
| Next.js | 프론트 서버가 API를 대신 부르는 BFF 구조에 맞다. Node 서버 컨테이너가 필요하고, 화면 대부분이 로그인 뒤라 검색 노출 이점이 없다 |
| Vue 3 + Vite | 쓸 만하지만 AI 생성 예시와 디자인 시스템 생태계가 React보다 작다 |
| Svelte 5 / SvelteKit | 생태계와 AI 생성 품질이 React보다 얇다 |
| Thymeleaf 모듈 | 토큰을 브라우저가 들고 다니는 구조라 서버 렌더링에는 세션에 토큰을 두는 BFF가 필요하다. 폴링·남은 시간 화면은 어차피 JS가 맡는다. JVM이 하나 늘어 compose 기준 512MB가 더 든다. #7에서도 "어디서 화면을 내줄지 애매하고 순번 갱신 UI가 번거로움"으로 빠졌다 |
| Gateway `classpath:/static` 내장 | 인증 필터가 라우팅된 요청에만 걸리는 `GlobalFilter`라 기술적으로는 된다. 화면을 고칠 때마다 Gateway를 다시 빌드·재시작하고, 부하 측정 대상인 Gateway JVM이 정적 파일까지 내준다 |
| 손으로 쓴 HTML·JS (#7 유지) | 인증 갱신, 입장 토큰, 멱등키, 폴링, 에러 문구 같은 공통 로직과 UI 조각을 파일마다 반복하기 쉽다. 디자인 시스템이 CSS 변수 수준에 머문다 |

### 3. 함께 쓸 도구

| 영역 | 선택 | 이유 | 고르지 않은 대안 |
|---|---|---|---|
| 빌드 | Vite | 빌드 결과가 정적 파일이라 별도 컨테이너로 내보낸다. 개발 서버의 `server.proxy`로 `/api`를 Gateway에 넘겨 로컬에서도 같은 출처를 만든다 | Create React App(지원 종료) |
| 언어 | TypeScript | 상태 값(`WAITING`·`READY`·`ADMITTED`, `HELD`·`REQUESTED`, `APPROVED`·`UNKNOWN`)과 단위가 다른 필드(`nextPollAfterMillis`, `nextPollAfterSeconds`)를 타입으로 구분한다 | JavaScript |
| 라우팅 | React Router (SPA 방식) | 화면이 열 개 남짓이고 로그인·입장 토큰이 필요한 화면을 경로 단위로 막는다. 자료와 AI 생성 예시가 가장 많다 | TanStack Router(경로 타입 안전, 익히는 비용) |
| 서버 데이터 | TanStack Query | 폴링 간격을 직전 응답 값으로 정한다(`refetchInterval`에 함수). 선점 뒤 좌석 조회 무효화, 실패 재시도, 로딩·에러 상태를 한 방식으로 다룬다 | SWR, `useEffect` 직접 작성 |
| HTTP | `fetch` 래퍼 직접 작성 | 공통 응답(`data`·`code`·`traceId`) 풀기, 401이면 재발급을 한 번만 하고 재시도, `Idempotency-Key`를 재시도 때 같은 값으로 재사용, `X-Entry-Token` 첨부를 한 곳에 둔다. 규칙이 프로젝트 고유라 어차피 감싸야 한다 | axios 인터셉터, ky |
| 클라이언트 상태 | 필요할 때만 Zustand | 로그인 토큰처럼 화면 사이에서 공유하는 값만 둔다. 고른 좌석은 화면 안 상태로 충분하다 | React Context |
| 폼·검증 | React Hook Form + Zod | 회원가입, 운영자 등록 폼(공연·회차·좌석·추첨). Zod 스키마로 응답도 런타임 검증해 계약 어긋남을 바로 본다 | 직접 검증 |
| 스타일·디자인 시스템 | Tailwind CSS + shadcn/ui | 색·간격·글꼴을 CSS 변수(디자인 토큰)로 두고 Tailwind 테마에 연결한다. shadcn/ui는 컴포넌트 코드를 저장소에 복사해 소유하므로 티켓팅에 맞게 고친다. 접근성은 Radix 기본 동작을 쓴다 | MUI, Mantine(완성된 모양, 바꾸기 어려움) |
| API 목 | MSW | 아직 없는 API를 계약대로 흉내 내 먼저 만든다. 409 이선좌, 202 `UNKNOWN`, 503 `PG_UNAVAILABLE` 같은 실패 화면도 재현한다 | json-server |
| 테스트 | Vitest + React Testing Library, 시연 흐름은 Playwright | 단위·컴포넌트는 Vitest, 오픈 → 대기 → 선점 → 결제 한 흐름은 브라우저 테스트 | Jest |
| 린트·포맷 | ESLint + Prettier | Vite 템플릿 기본 구성에 React Hooks 규칙이 들어 있다 | Biome |
| 패키지 관리 | npm | Node에 기본으로 들어 있어 추가 설치가 없다 | pnpm |
| 좌석 배치도 | SVG 또는 CSS Grid, 라이브러리 없음 | 좌석 행·번호가 정수(ADR 0033)라 좌표 계산이 단순하다. 수천 석을 넘어 느려지면 Canvas를 검토한다 | 좌석 배치 라이브러리 |

버전은 5절에 있다. Node 버전은 `.nvmrc`와 Dockerfile에 같은 값으로 둔다.

### 4. 실행: 별도 nginx 컨테이너가 화면을 내주고 `/api/`를 Gateway로 중계한다

```
브라우저 ── http://<호스트>/            → frontend 컨테이너(nginx): React 빌드 결과
         └─ http://<호스트>/api/v1/...  → nginx → gateway:8080 (compose 내부 네트워크)
```

- `frontend/`의 Dockerfile이 Node로 빌드하고(1단계), 결과 정적 파일을 nginx 이미지에 담는다(2단계). 다른 팀원은 Node 없이 compose를 띄운다.
- nginx는 정적 파일을 내주고, 없는 경로는 `index.html`로 돌려준다(SPA 새로고침). `/api/`로 시작하는 요청만 `gateway:8080`으로 넘긴다. 서비스로 직접 넘기는 경로는 두지 않는다. 서비스는 Gateway가 넣은 `X-User-Id`·`X-User-Role`을 서명 없이 믿기 때문이다(`docs/guides/workspace.md`).
- 브라우저는 한 출처만 보므로 Gateway에 CORS 설정을 넣지 않는다. 로컬 개발은 Vite 개발 서버의 `server.proxy`가 같은 역할을 한다. Gateway에 CORS가 없던 것은 지금까지 부른 쪽이 HTTP 요청 파일, JUnit 시스템 테스트, 부하 도구처럼 브라우저가 아닌 클라이언트였기 때문이다.
- 토큰은 브라우저가 들고 `Authorization: Bearer`로 보낸다(현재 계약 그대로). Access 토큰은 JS 메모리에만 둔다. Refresh 토큰은 탭 단위 `sessionStorage`에 둔다(아래 "Refresh 토큰 보관 위치"). 입장 토큰은 계약대로 `sessionStorage`에 둔다.
- 부하 도구는 지금처럼 Gateway를 직접 부른다. nginx는 측정 경로에 끼지 않는다.

#### 검토한 연결 방식

| 안 | 내용 | 채택하지 않은 이유 |
|---|---|---|
| A. 중계 (채택) | nginx가 요청을 그대로 Gateway로 넘긴다 | — |
| B. 중계 + Refresh 토큰만 httpOnly 쿠키 | 오래 사는 토큰을 JS에서 뺀다 | user-service 응답 계약 변경이 필요하다. 이후 보강 후보로 남긴다 |
| C. BFF | 프론트 서버가 세션에 토큰을 두고 API를 대신 부른다. 브라우저는 httpOnly 쿠키만 가진다 | 서버 앱·세션 저장소·CSRF 방어가 늘고, 입장 토큰 `sessionStorage` 같은 대기열 계약의 프론트 규칙이 바뀐다. 대기열이 Gateway 뒤에 있는 이 구조에서는 대기 중 폴링이 모두 BFF를 거친다 |

#### Refresh 토큰 보관 위치 (ISE-110)

Refresh 토큰은 탭 단위 `sessionStorage`에 두고, Access 토큰은 메모리에만 둔다. 새로고침 뒤 첫 요청은 저장한 Refresh 토큰으로 먼저 재발급한다.

서버 쪽 사실: user-service는 사용자당 Refresh 토큰을 하나만 Redis에 두고(`auth:refresh:{userId}`), 재발급마다 새 토큰으로 바꾼다. 바뀌기 전 토큰으로 다시 재발급하면 401 `TOKEN_REVOKED`다(`RedisSessionStore`). Refresh 토큰 수명은 14일, Access 토큰은 30분이다(`config-repo/user-service.yml`).

| 안 | 새로고침 | 새 탭 | 고르지 않은 이유 |
|---|---|---|---|
| 메모리만 | 로그인이 풀린다 | 다시 로그인 | 대기열·좌석 화면에서 새로고침 한 번에 다시 로그인해야 한다. 오픈 순간에 치명적이다 |
| `sessionStorage` (채택) | 유지 | 다시 로그인 | — |
| `localStorage` | 유지 | 유지 | 브라우저를 닫아도 14일 남는다. 여러 탭이 한 토큰을 나눠 쓰면 서버의 재발급 교체와 부딪혀, 탭 사이 잠금(Web Locks)과 토큰 동기화를 따로 만들어야 한다 |
| httpOnly 쿠키 (4절 B안) | 유지 | 유지 | JS가 읽지 못해 XSS 탈취를 막는다. user-service 응답과 Gateway를 바꿔야 해서 보강 후보로 남긴다 |

- 탭 하나 안에서는 동시에 401을 받은 요청들이 재발급 하나를 함께 기다린다. 각자 재발급하면 두 번째부터 `TOKEN_REVOKED`로 실패해 세션이 지워지기 때문이다. 탭 사이에서는 토큰을 나눠 쓰지 않아 이 충돌이 생기지 않는다.
- 입장 토큰도 계약상 탭 단위 `sessionStorage`다. 로그인과 입장의 범위가 같아진다.
- XSS로 `sessionStorage`를 읽을 수 있는 점은 `localStorage`와 같다. 노출 기간이 탭을 닫을 때까지로 짧아질 뿐이다. 근본 대책은 B안이다.
- 감수하는 것: 새 탭·새 창에서는 다시 로그인한다. 다른 탭에서 다시 로그인하면 서버의 토큰이 바뀌어, 먼저 연 탭은 Access 토큰이 만료될 때(최대 30분) 재발급에 실패하고 로그인 화면으로 간다. 탭 복제는 `sessionStorage`를 복사하므로 두 탭이 같은 토큰을 갖고, 먼저 재발급한 탭만 이어진다.
- 재발급이 4xx(만료·교체·잠김·임시 비밀번호)로 실패하면 세션을 지운다. 5xx·408·429와 연결 실패는 토큰이 아직 유효할 수 있어(Redis 장애 중 503 등) 지우지 않는다.
- 재발급을 기다리는 사이 로그아웃하거나 다시 로그인했으면 늦게 온 재발급 결과로 세션을 덮지 않는다. 로그아웃한 탭이 되살아나지 않게 하기 위해서다.
- 교체 방식의 한계: 서버는 토큰을 바꿨는데 응답이 유실되면(연결 끊김) 브라우저는 옛 토큰을 들고 있어 다음 재발급이 `TOKEN_REVOKED`로 끝나고 다시 로그인해야 한다. 클라이언트에서 막을 수 없다.

#### 학습한 실서비스 구조와 이번 선택

실서비스라면 어떻게 할지 확인했지만, 이 프로젝트는 백엔드에 집중하기 위해 프론트를 단순하게 간다.

| 층 | 실서비스라면 | 이번 선택 | 이유 |
|---|---|---|---|
| 웹 인증 | BFF. RFC 10017(OAuth 2.0 for Browser-Based Applications, BCP 212, 2026-08)은 결제·개인정보를 다루는 앱에 BFF를 강하게 권하고, BFF에 CSRF 방어를 반드시 요구한다 | 중계, 토큰은 브라우저 | 기간 한정 시연이고 화면 구현은 M4 한 주다. 현재 계약이 브라우저 보관을 전제로 한다 |
| 몰림 | 대기열을 원 서버 앞단에 둔다(직접 만들거나 상용 제품) | 대기열은 Reservation 안 모듈 그대로 | 앞단 대기실이 하는 일(순번, 일정 속도 입장, 서명 토큰, 진입점 검증)이 이 프로젝트의 대기열 설계와 같다. 위치만 다르며, 서비스 안에 둔 비용(대기 폴링이 Gateway까지 들어옴)은 jitter와 읽기 전용 순번 조회로 줄이고 부하 테스트로 잰다. 앞단으로 떼어 내는 길은 열려 있다 |
| 공개 페이지 | 서버 렌더링·정적 생성 + CDN 캐시(검색 노출, 링크 미리보기) | SPA | 기간 한정 배포라 검색 노출이 필요 없다 |
| 모바일 앱 | 앱용 API 또는 앱 전용 BFF | 없음 | 범위 밖 |
| 매크로·암표 | 보안문자, 본인인증, 봇 관리·WAF | 없음(보안문자는 SA에서 보류) | 범위 밖 |

### 5. 버전 (2026-11-02 스캐폴딩, ISE-107)

`frontend/.npmrc`의 `save-exact=true`로 `package.json`에 정확한 버전을 적고 `package-lock.json`을 함께 커밋한다. 올릴 때는 이 표를 함께 고친다.

| 대상 | 버전 | 메모 |
|---|---|---|
| Node | 24 LTS (`.nvmrc`) | 로컬에 설치된 LTS다. 26이 10/28에 Active LTS가 됐지만 필요한 기능이 없고, 24도 유지보수 LTS로 지원된다 |
| Vite | 8.3.5 | `create-vite` 9.3.3 `react-ts` 템플릿 |
| React | 19.3.1 | |
| TypeScript | 6.0.3 | 템플릿 기본은 7.0.2지만 typescript-eslint 8.72.1(canary 포함)이 6.1 미만만 지원해 설치가 막힌다. typescript-eslint가 7을 지원하면 올린다(TS-27) |
| React Router | 8.5.1 | 데이터 라우터(`createBrowserRouter`). `RouterProvider`는 `react-router/dom`, 나머지는 `react-router`에서 가져온다 |
| TanStack Query | 5.105.1 | |
| React Hook Form · `@hookform/resolvers` · Zod | 7.91.0 · 7.0.1 · 4.6.7 | |
| Tailwind CSS | 4.3.3 | `@tailwindcss/vite` 플러그인 |
| shadcn CLI · radix-ui | 4.23.0 · 1.8.1 | `--base radix --preset nova`(components.json `radix-nova`). 프리셋이 넣는 Geist 글꼴은 빼고 Pretendard를 쓴다(ISE-111) |
| Vitest · jsdom | 5.0.5 · 29.1.1 | 전역 API를 켜지 않고 `vitest`에서 가져온다 |
| Testing Library | react 16.3.3 · jest-dom 7.0.1 · user-event 14.6.8 | |
| ESLint · typescript-eslint | 10.14.0 · 8.72.1 | 템플릿 기본은 Oxlint지만 3절에서 정한 ESLint로 만들었다(`--eslint`) |
| Prettier · eslint-config-prettier | 3.9.10 · 10.1.8 | `semi: false`, `singleQuote: true`, `printWidth: 100`. 템플릿 코드 모양에 맞췄다 |
| Playwright | 1.64.1 (ISE-118) | 시연 흐름 브라우저 테스트. 정확한 버전으로 고정하고 Chromium만 쓴다. Vitest는 `src/**/*.test.*`만 집어 `e2e/`와 섞이지 않는다 |
| MSW | 3.0.3 (ISE-110) | 2.x의 `onUnhandledRequest`가 `onUnhandledFrame`으로 바뀌었다. `msw/vite` 플러그인이 개발 서버에서 워커 스크립트를 내주므로 `public/`에 복사하지 않고, 운영 빌드에 워커 파일이 들어가지 않는다 |
| Zustand | 5.0.16 (ISE-110) | 로그인 토큰 저장소. `persist`의 `partialize`로 Refresh 토큰만 `sessionStorage`에 남긴다 |
| Pretendard | 1.3.9 (ISE-111) | OFL-1.1. 가변 글꼴 dynamic-subset CSS를 `src/index.css`에서 가져와 Vite가 woff2(92조각, `unicode-range`로 필요한 것만 받는다)를 번들한다. CDN을 쓰지 않는다 |
| sonner | 2.0.8 (ISE-111) | shadcn `Sonner` 토스트. shadcn 컴포넌트가 쓰던 `next-themes`는 다크 모드를 하지 않아 뺐다 |
| `@tosspayments/tosspayments-sdk` | 2.10.0 (ISE-125) | 토스 결제창(구버전, API 개별 연동 키). 토스 모드에서 결제창을 열 때만 동적 import한다. 로더는 `vite.config.ts`의 `tosspayments` 묶음(약 3.6 kB)으로 떼어 처음 화면과 Mock 모드에서는 받지 않는다. SDK 본체는 로더가 실행될 때 `https://js.tosspayments.com/v2/standard`에서 받으므로 토스 모드만 외부에 의존한다 |

## 결과

- 얻는 것: 컴포넌트 단위 디자인 시스템, 계약 타입, 폴링·재시도 규칙의 한 곳 관리, 백엔드 구현 전 목 기반 개발. Gateway와 백엔드 계약은 바뀌지 않는다
- 감수하는 것:
  - 저장소에 Node 빌드가 들어온다(ADR 0012의 Java·Gradle 통일은 개발·검증 도구 범위라 앱 스택에는 적용하지 않는다)
  - React를 모르는 팀원은 화면 코드를 읽기 어렵다
  - 브라우저가 토큰을 들고 있어 XSS로 토큰이 탈취될 수 있다. Access 토큰을 메모리에만 두는 것으로 일부 줄인다
  - Notion 결정 기록 #7은 고치지 않는다. 프론트 결정은 이 ADR과 `docs/design/frontend/`를 따른다(`AGENTS.md` 대전제 4)
- 후속 작업 (Linear ISE-104 하위 이슈):
  - ISE-107(완료): 스캐폴딩, 루트 `.dockerignore`에 `frontend/node_modules`·`frontend/dist` 추가, 5절 버전 기록
  - ISE-108(완료): 프론트 워크플로(`frontend.yml`)는 `frontend/`가 바뀐 PR·push에서만 뜬다(`paths` 필터, 필수 검사 아님). `build.yml`은 필수 검사라 항상 돌고 `frontend/`만 바뀐 PR에서 Java 단계만 건너뛴다(`script/ci-changed-areas.sh`). `frontend/`만 바꾼 PR #192(ISE-111)에서 `java=false`로 Java 단계 4개가 skipped되고 `build`가 6초 만에 성공으로 보고되는 것을 확인했다(2026-11-02). `docs/`가 함께 바뀌면 `java=true`라 Java 검사가 돈다
  - ISE-109: compose에 frontend 서비스 추가, `checkPortExposure`의 공개 서비스 목록에 frontend 추가(`docs/guides/workspace.md` 포트 규칙). 외부 공개 포트가 바뀌므로 SA 9-2 보안그룹 규칙표와 배포 바인딩도 함께 고친다. 배포 담당(ISE-6)도 안준현이라 이 이슈 안에서 처리하고, 팀원 로컬 기동이 바뀌는 compose 변경은 PR 본문과 팀 공지로 알린다
  - ISE-110(완료): Refresh 토큰은 탭 단위 `sessionStorage`로 정해 4절에 적었다. API 클라이언트 규칙은 `docs/design/frontend/README.md` "API 클라이언트 규칙"에 있다
  - ISE-111(완료): 디자인 토큰·기본 컴포넌트·티켓팅 컴포넌트·공통 레이아웃. 구현 위치와 설계와 다르게 한 것은 `docs/design/frontend/design-system.md` 4절 아래 "구현"에 있다
