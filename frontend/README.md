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

## 명령

| 명령 | 하는 일 |
|---|---|
| `npm run dev` | 개발 서버 |
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
  test/setup.ts   Vitest 설정(jest-dom 매처, 테스트마다 DOM 정리)
```

- 경로 별칭 `@/`는 `src/`다.
- shadcn/ui 컴포넌트는 `npx shadcn@4.23.0 add <이름>`으로 더한다. 설정은 `components.json`(Radix 기반)에 있다.
- 테스트 파일은 대상 옆에 `*.test.tsx`로 둔다.

## CI

`.github/workflows/frontend.yml`이 PR과 main·dev push마다 돈다. `frontend/`(또는 이 워크플로, `script/ci-changed-areas.sh`)가 바뀌었으면 `npm ci` → `format:check` → `lint` → `test` → `build`를 차례로 돌고, 아니면 단계를 건너뛰고 성공으로 끝난다. `frontend/`만 바뀐 PR에서는 `build` 워크플로가 Java 검사를 건너뛴다. PR 전에 로컬에서 같은 순서로 돌려 본다.

## 의존성

`.npmrc`의 `save-exact=true`로 `package.json`에 정확한 버전을 적는다. 버전과 고른 이유는 ADR 0040 "버전" 절에 있다. 올릴 때는 그 표도 함께 고친다.
