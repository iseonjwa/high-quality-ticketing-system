# frontend

시연용 웹 화면이다. React SPA(Vite + TypeScript)이고, 결정과 이유는 [ADR 0040](../docs/decisions/0040-frontend-react-spa-in-monorepo.md), 화면 계획은 [`docs/design/frontend/`](../docs/design/frontend/README.md), 디자인 시스템은 [`design-system.md`](../docs/design/frontend/design-system.md)에 있다.

## 실행

Node 24(LTS)가 필요하다. 버전은 `.nvmrc`에 있다.

```bash
cd frontend
nvm use          # 또는 Node 24 설치
npm ci           # package-lock.json 그대로 설치
npm run dev      # http://localhost:5173
```

개발 서버는 `/api`로 시작하는 요청을 Gateway(`http://localhost:8080`)로 넘긴다. 브라우저는 개발 서버 한 곳만 보므로 CORS가 필요 없다. Gateway를 다른 주소에서 띄웠다면 `API_PROXY_TARGET=http://<주소> npm run dev`로 바꾼다. Gateway는 루트 compose로 띄운다([워크스페이스 가이드](../docs/guides/workspace.md)).

### 백엔드 없이 띄우기 (API 목)

```bash
npm run dev:mock   # MSW가 계약 문서대로 응답한다
```

- 로그인: `demo@iseonjwa.dev` / `password1234`(운영자는 `admin@iseonjwa.dev`, 같은 비밀번호). 공연 7, 판매 중 회차 31, 오픈 전 회차 32가 있다.
- 실패 화면은 주소에 `?mock=`로 고른다. 쉼표로 여러 개를 고를 수 있고 탭을 닫을 때까지 유지된다. `?mock=`(빈 값)이면 기본으로 돌아간다.

| 시나리오 | 동작 |
|---|---|
| (기본) | 대기열은 순번 조회 두 번 뒤 READY, 선점 성공, 결제 승인 |
| `queue-ready` | 대기열 진입하자마자 READY |
| `seat-conflict` | 첫 선점이 409 이선좌로 끝나고 요청 좌석 하나가 팔린 상태가 된다. 다시 고르면 성공한다 |
| `payment-declined` | 결제 200 `DECLINED`(한도 초과) |
| `payment-unknown` | 결제 202 `UNKNOWN`, 첫 결제 조회에서 `APPROVED` |
| `pg-unavailable` | 결제 503 `PG_UNAVAILABLE`, 좌석은 유지 |

- 결제가 승인돼도 예매는 바로 확정되지 않는다. 승인 뒤 예매 조회 한 번은 `HELD`, 다음 조회부터 `CONFIRMED`다(실제는 이벤트로 2초 안에 반영).
- 결제 토큰은 발급마다 다르다. 같은 결제 멱등키 재요청은 처음 응답을 그대로 돌려준다(202 뒤 승인돼도 202).
- 목 상태는 페이지 메모리에 있어 새로고침하면 처음으로 돌아간다. 로그인은 Refresh 토큰으로 이어진다(브라우저 목 모드에서만 처음 보는 Refresh 토큰을 받아 준다. 테스트는 서버처럼 거절한다).
- 목이 응답하는 간격(대기열 1초, 결제 확인 2초)은 실제 서버(순번 기반, 10초)보다 짧다. 화면은 응답에 담긴 간격을 그대로 쓴다.
- 운영 빌드에는 목 코드와 워커 파일이 들어가지 않는다(`import.meta.env.MODE === 'mock'`일 때만 불러온다).

## 명령

| 명령 | 하는 일 |
|---|---|
| `npm run dev` | 개발 서버 |
| `npm run dev:mock` | API 목을 켠 개발 서버(Vite mode `mock`) |
| `npm run build` | 타입 검사(`tsc -b`) 후 `dist/`에 정적 파일 빌드 |
| `npm run preview` | 빌드 결과를 로컬에서 띄움 |
| `npm run lint` | ESLint |
| `npm run format` / `format:check` | Prettier로 고치기 / 검사만 |
| `npm test` / `test:watch` | Vitest 한 번 실행 / 감시 모드 |

## 구조

```
src/
  main.tsx        진입점. QueryClient와 라우터를 붙인다
  app/routes.tsx  경로 목록. 테스트도 같은 목록으로 라우터를 만든다
  pages/          화면 단위 컴포넌트
  components/ui/  shadcn/ui 컴포넌트(저장소에 복사해 소유한다)
  lib/            공통 함수
  api/            API 클라이언트(client.ts)와 도메인별 요청 함수·응답 스키마, 에러 문구 표
  auth/           로그인 토큰(session.ts), 재발급(refresh.ts), 입장 토큰(entryTokens.ts)
  mocks/          MSW 핸들러·목 상태·실패 시나리오. 테스트와 `dev:mock`이 같이 쓴다
  test/setup.ts   Vitest 설정(jest-dom 매처, MSW 서버, 테스트마다 DOM·목 상태·저장소 정리)
```

화면이 API를 부르는 규칙(401 재발급, 멱등키, 입장 토큰)은 [`docs/design/frontend/README.md`](../docs/design/frontend/README.md) "API 클라이언트 규칙"에 있다.

- 경로 별칭 `@/`는 `src/`다.
- shadcn/ui 컴포넌트는 `npx shadcn@4.23.0 add <이름>`으로 더한다. 설정은 `components.json`(Radix 기반)에 있다.
- 테스트 파일은 대상 옆에 `*.test.ts(x)`로 둔다. API 테스트는 목 서버에 실제로 요청을 보내고, 목이 없는 경로를 부르면 실패한다.

## CI

`.github/workflows/frontend.yml`은 `frontend/`(또는 이 워크플로)가 바뀐 PR과 main·dev push에서만 뜬다. `npm ci` → `format:check` → `lint` → `test` → `build`를 차례로 돈다. 필수 검사가 아니라서 빨간 체크가 떠도 머지가 막히지 않는다. 머지 전에 직접 확인한다. `frontend/`만 바뀐 PR에서는 `build` 워크플로가 Java 검사를 건너뛴다. PR 전에 로컬에서 같은 순서로 돌려 본다.

## 의존성

`.npmrc`의 `save-exact=true`로 `package.json`에 정확한 버전을 적는다. 버전과 고른 이유는 ADR 0040 "버전" 절에 있다. 올릴 때는 그 표도 함께 고친다.
