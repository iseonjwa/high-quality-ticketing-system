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

### 컨테이너로 띄우기

```bash
docker compose up -d --build frontend   # http://localhost:8000
```

nginx가 빌드 결과를 서빙하고 `/api/`를 Gateway로 중계한다. 첫 빌드는 도커 안에서 Node로 하므로 몇 분 걸린다. 화면을 고치는 동안은 파일 저장이 바로 반영되는 개발 서버(5173)를 쓰고, 컨테이너는 배포와 같은 모양을 확인할 때 쓴다.

### 시연 모드 (요청 로그)

주소 끝에 `?demo=on`을 붙이면 그 탭에서 화면 오른쪽에 요청 로그가 열린다. 요청마다 메서드·경로·상태·traceId가 쌓이고, traceId를 누르면 Grafana "요청 로그" 대시보드가 Grafana 탭에서 열린다. 처음 열린 탭을 옆 창으로 옮겨 두면 다음부터 그 창에서 바뀐다. 왼쪽 경계를 끌어 너비를 바꾸고, `?demo=off`나 패널의 X로 끈다. Grafana 주소는 기본 화면을 연 호스트의 3000번(로컬 `localhost:3000`, 배포 `52.63.10.174:3000`)이고 `VITE_GRAFANA_URL`로 바꾼다. 규칙은 [`docs/design/frontend/README.md`](../docs/design/frontend/README.md) "시연 모드 규칙"에 있다. 패널이 앱 화면을 좁히므로 화면의 반응형 클래스는 `sm:`·`md:` 대신 `@min-[40rem]:`·`@min-[48rem]:`을 쓴다.

### 백엔드 없이 띄우기 (API 목)

```bash
npm run dev:mock   # MSW가 계약 문서대로 응답한다
```

- 로그인: `demo@iseonjwa.dev` / `password1234`(회원 번호 42. 운영자는 `admin@iseonjwa.dev`(회원 번호 1), 같은 비밀번호. 운영자 등록 API가 만드는 회차는 공연장 1번 KSPO DOME을 쓰고 등급은 VIP·R·S다). 공연은 셋이다. 공연 7은 판매 중 회차 31과 10분 뒤 오픈하는 회차 32, 공연 8은 이틀 뒤 오픈하는 회차 41, 공연 9는 끝난 회차 51이다. 공연 응답은 실제 API(ISE-99)와 같은 모양이다.
- 임시 비밀번호 계정: `temp@iseonjwa.dev` / `TempPass2345`. 로그인하면 비밀번호 변경 화면으로 간다. 어느 계정이든 비밀번호를 5번 틀리면 잠긴다(새로고침하면 목 상태와 함께 풀린다).
- 예매 문의 위젯(오른쪽 아래)의 목은 스트리밍(SSE)으로 답하고, 질문 낱말로 경우를 고른다. "검색"은 키가 없는 검색 모드, "지연"은 LLM 장애로 강등된 검색 모드, "끊김"은 답하다가 검색 모드로 강등, "중단"은 `done` 없이 끊김, "많이"는 429다. 실제 백엔드는 `assistant-service`이고 Gateway가 `/api/v1/assistant`로 중계한다. 키가 없어도 검색 모드로 끝까지 동작한다([설계](../docs/design/assistant/README.md)).
- 실패 화면은 주소에 `?mock=`로 고른다. 쉼표로 여러 개를 고를 수 있고 탭을 닫을 때까지 유지된다. `?mock=`(빈 값)이면 기본으로 돌아간다.

| 시나리오 | 동작 |
|---|---|
| (기본) | 대기열은 순번 조회 두 번 뒤 READY, 선점 성공, 결제 승인 |
| `queue-ready` | 대기열 진입하자마자 READY |
| `queue-unavailable` | 대기열 요청 첫 1번이 503(`Retry-After: 1`), 그다음부터 기본과 같다 |
| `queue-expired` | READY 뒤 첫 토큰 요청이 줄 기록을 지우고 404 `QUEUE_NOT_JOINED`. 다시 진입하면 기본처럼 돈다 |
| `seat-conflict` | 첫 선점이 409 이선좌로 끝나고 요청 좌석 하나가 팔린 상태가 된다. 다시 고르면 성공한다 |
| `hold-unknown` | 첫 선점이 서버에서는 성립하지만 응답 대신 504를 준다. 같은 멱등키로 다시 보내면 처음 결과(201)를 준다 |
| `payment-declined` | 결제 200 `DECLINED`(한도 초과) |
| `payment-unknown` | 결제 202 `UNKNOWN`, 첫 결제 조회에서 `APPROVED` |
| `pg-unavailable` | 결제 503 `PG_UNAVAILABLE`, 좌석은 유지 |
| `payment-manual` | 결제 409 `PAYMENT_ALREADY_EXISTS`(수동 확인 필요, `Location` 있음) |
| `payment-slow-confirm` | 결제는 승인되지만 예매가 계속 `HELD`라 확정 확인이 30초를 넘긴다 |
| `payment-late-approval` | 결제는 승인되지만 그 뒤 예매가 `EXPIRED`가 된다(선점 만료 뒤 승인 도착, 환불 안내) |
| `billing-unavailable` | 자동결제 카드 등록이 503 `PG_UNAVAILABLE`. 해지는 기본과 같다 |
| `lottery-waitlisted` | 추첨 3번의 데모 응모가 예비자(추첨 순위 13위) |
| `lottery-lost` | 추첨 3번의 데모 응모가 미당첨 |
| `lottery-failed` | 추첨 3번이 당첨 → 다음 조회부터 자동결제 실패(`BILLING_KEY_MISSING`) |
| `lottery-unknown` | 첫 응모가 서버에서는 성립하지만 504. 같은 키로 다시 보내면 201(지금 상태) |
| `lottery-closed` | 응모가 409 `LOTTERY_NOT_OPEN` |
| `refund-slow` | 예매를 취소해도 환불이 끝나지 않는다(결제가 `APPROVED`로 남아 2분 뒤 "환불이 늦어지고 있어요") |

- 추첨은 셋이다(`/lotteries/1`·`2`·`3`). 1번은 회차 32의 응모 중(최대 2매), 2번은 회차 41의 응모 시작 전(최대 1매), 3번은 회차 31의 추첨 완료다. 3번은 데모 계정이 1매 응모해 두었고 첫 조회에 당첨이다. 다음 조회 때 자동결제 카드가 있으면 결제 완료(확정 예매), 없으면 자동결제 실패(`BILLING_KEY_MISSING`)다(서버와 같다). 추첨 상태는 조회 때 시각으로 바뀐다(응모 마감 뒤 `CLOSED`, 추첨 시각 뒤 `DRAWN`).
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
| `npm run e2e:msw` | 시연 흐름 Playwright 테스트(MSW 모드). 아래 "시연 흐름 테스트" |
| `npm run e2e:compose` | 같은 흐름을 실제 스택(compose)에 대해 돈다(직렬). 아래 "compose 모드" |
| `npm run e2e` | 모든 대상 |

## 구조

```
src/
  main.tsx        진입점. QueryClient와 라우터를 붙인다
  app/routes.tsx  경로 목록. 테스트도 같은 목록으로 라우터를 만든다
  app/RequireAuth.tsx  로그인이 필요한 화면을 감싸는 경로(로그인 전이면 로그인 화면으로)
  app/bookingStep.ts  예매 흐름 경로의 단계 번호(handle). 헤더가 이 번호로 단계 표시를 그린다
  app/pageLayout.ts  폭을 화면이 정하는 경로(handle { layout: 'bleed' }). 홈·공연 상세가 배너를 화면 끝까지 그린다
  assets/concert-art/  공연 포스터(poster-N)·배너(wide-N) WebP. tools/concert-art가 만든다
  app/RequireAdmin.tsx  운영자 화면을 감싸는 경로(ADMIN이 아니면 안내만)
  pages/          화면 단위 컴포넌트. 인증 화면은 pages/auth/, 공연은 pages/concerts/, 대기열(화면과 입장 흐름)은 pages/queue/, 좌석 선택·선점은 pages/seats/, 결제(화면과 승인·확인 흐름)는 pages/payment/, 예매 결과·상세·내 예매는 pages/reservations/, 추첨 응모는 pages/lotteries/, 운영자는 pages/admin/
  admin/          운영자 입력 검사(formSchemas.ts, 추첨 설정 lotteryFormSchema.ts), 이 브라우저에서 설정한 추첨 기록(adminLotteryRecords.ts)
  components/form/  입력 칸(라벨·오류·도움말을 잇는 TextField, 고르기 SelectField, 비밀번호 보기 PasswordField)
  components/ui/  shadcn/ui 컴포넌트(저장소에 복사해 소유한다)
  components/ticketing/  티켓팅 전용 컴포넌트(좌석·좌석표, 시간 칩, 카운트다운, 대기 순번, 단계 표시, 에러 안내 등)
  components/layout/  공통 레이아웃(헤더·토스트, AppLayout), 로고, 사이트 아래 정보(SiteFooter), 화면이 헤더 오른쪽을 채우는 HeaderSlot, 경로 에러 화면
  pages/DesignSystemPage.tsx  `npm run dev`에서 `/_design`으로 여는 컴포넌트 확인 화면(운영 빌드에는 없다)
  lib/            공통 함수
  api/            API 클라이언트(client.ts)와 도메인별 요청 함수·응답 스키마, 에러 문구 표
  auth/           로그인 토큰(session.ts), 재발급(refresh.ts), 입장 토큰(entryTokens.ts), 잡은 좌석(heldSeats.ts), 결제 시도(paymentAttempts.ts), 자동결제 카드 등록 기록(billingKeyRecord.ts), 입력 검사(formSchemas.ts), 돌아갈 경로(navigation.ts)
  mocks/          MSW 핸들러·목 상태·실패 시나리오. 테스트와 `dev:mock`이 같이 쓴다
  demo/           시연 모드(켜짐 상태, 요청 기록, 오른쪽 요청 로그 패널, Grafana 주소)
  test/setup.ts   Vitest 설정(jest-dom 매처, MSW 서버, 테스트마다 DOM·목 상태·저장소 정리)
tools/concert-art/  공연 그림 생성기(art.html 캔버스 그림, render.mjs가 headless Chrome으로 그려 cwebp로 저장). 빌드에 들어가지 않는다
```

화면이 API를 부르는 규칙(401 재발급, 멱등키, 입장 토큰)은 [`docs/design/frontend/README.md`](../docs/design/frontend/README.md) "API 클라이언트 규칙"에 있다.

- 경로 별칭 `@/`는 `src/`다.
- shadcn/ui 컴포넌트는 `npx shadcn@4.23.0 add <이름>`으로 더한다. 설정은 `components.json`(Radix 기반)에 있다.
- 테스트 파일은 대상 옆에 `*.test.ts(x)`로 둔다. API 테스트는 목 서버에 실제로 요청을 보내고, 목이 없는 경로를 부르면 실패한다.

## 시연 흐름 테스트 (Playwright)

로그인 → 공연 상세 → 대기열 → 좌석 선점 → 결제 → 결과·내 예매를 브라우저로 한 번에 도는 테스트다. `e2e/`에 있고 Vitest(`src/**/*.test.*`)와 따로 돈다.

```bash
npx playwright install chromium   # 처음 한 번
npm run e2e:msw                   # 개발 서버(dev:mock, 5174 포트)를 띄워 MSW 모드로 돈다
```

- 시나리오 3개: 정상 / 409 이선좌 재선택(`?mock=seat-conflict`) / 결제 "확인 중"(`?mock=payment-unknown`).
- 요소는 접근 가능한 이름으로 잡고 앱 코드의 `testid`는 쓰지 않는다. 흐름 중간에 `page.goto`를 하면 목 상태가 처음으로 돌아가므로 첫 이동과 로그인 직후 이동에만 쓴다.
- 대상은 프로젝트(`msw`, `compose`)로 나눈다. spec은 `fixtures.ts`의 값(계정, 공연, 좌석, 시나리오 진입 주소)만 알아 두 대상이 같은 spec을 쓴다. compose 모드는 실제 스택에 붙는다.
- 실패하면 `playwright-report/`, `test-results/`(trace·스크린샷)가 남는다. `npx playwright show-report`로 본다.

### compose 모드 (실제 스택)

같은 spec 3개를 `docker compose`로 띄운 스택의 화면(nginx)에 대해 돈다. CI는 compose를 돌리지 않는다(MSW만).

```bash
# 저장소 루트에서. 프론트 이미지를 꼭 다시 빌드한다
docker compose -f compose.yml -f compose.local.yml up -d --build --wait
cd frontend
npx playwright install chromium   # 처음 한 번
npm run e2e:compose
```

- 준비는 fixture가 한다. 제목 "E2E 시연 흐름 공연"을 찾고(없으면 관리자 API로 만든다), 판매 중 회차를 재사용하고(없을 때만 새로 만들고 좌석을 생성한다), 테스트마다 사용자 둘을 공개 가입 API로 만든다(`e2e-<시각>-<난수>-<n>@iseonjwa.dev`). 주인공은 화면으로 로그인하고, 경쟁자는 API로만 대기열·선점을 한다. 빈 좌석은 입장 토큰으로 좌석을 조회해 그때그때 고른다(A구역).
- 409 시나리오는 주인공이 좌석을 고른 뒤 경쟁자가 같은 좌석을 먼저 선점해서 만든다.
- 환경 변수(모두 선택).

| 변수 | 기본값 | 뜻 |
|---|---|---|
| `E2E_BASE_URL` | `http://127.0.0.1:8000` | 화면 주소 |
| `E2E_ADMIN_EMAIL`, `E2E_ADMIN_PASSWORD` | 저장소 루트 `.env`의 `ADMIN_EMAIL`, `ADMIN_PASSWORD` | 공연·회차를 만들 관리자 계정. 값은 코드나 로그에 남기지 않는다 |
| `E2E_CONCERT_ID`, `E2E_SCHEDULE_ID` | 없음 | 둘 다 주면 관리자 단계를 건너뛰고 그 회차를 쓴다 |
| `E2E_VENUE_ID` | `1` | 회차를 새로 만들 때의 공연장(가격 등급은 VIP·R·S 기준) |
| `E2E_MOCK_PG_URL` | `http://127.0.0.1:8090` | Mock PG 주소(장애 주입 확인용) |

- 결제 "확인 중" 시나리오는 Mock PG 장애 주입 API(`/mock-pg/faults`)가 열려 있을 때만 돈다. 닫혀 있으면 이유를 남기고 건너뛴다. 돌리려면 mock-pg를 loadtest 프로필로 다시 띄운다. 확정까지 10~25초 걸린다. 끝나면 원래 프로필로 되돌린다.

```bash
docker compose -f compose.yml -f compose.local.yml -f loadtest/k6/compose.mockpg-loadtest.yml up -d --no-deps --wait mock-pg
npm run e2e:compose
docker compose -f compose.yml -f compose.local.yml up -d --no-deps --wait mock-pg   # 되돌리기
```

- 좌석이 모자라면(A구역 빈 좌석 2개 미만) 새 회차를 쓰거나 `E2E_SCHEDULE_ID`를 바꾼다. 테스트가 남긴 가입 사용자와 선점·예매는 지우지 않는다.

#### 배포 서버 리허설

- 실행하기 전에 팀 채널에 알린다. 가입 사용자와 예매가 배포 DB에 남고, 부하 측정 중에는 돌리지 않는다.
- 리허설 전용 회차를 하나 만들어 `E2E_BASE_URL`, `E2E_CONCERT_ID`, `E2E_SCHEDULE_ID`를 준다. 관리자 계정은 쓰지 않는다.
- Mock PG 포트는 밖에 열려 있지 않아 "확인 중" 시나리오는 건너뛴다. 정상과 409만 돈다.

## CI

`.github/workflows/frontend.yml`은 `frontend/`(또는 이 워크플로)가 바뀐 PR과 main·dev push에서만 뜬다. `npm ci` → `format:check` → `lint` → `test` → `build`를 차례로 돈다. 필수 검사가 아니라서 빨간 체크가 떠도 머지가 막히지 않는다. 머지 전에 직접 확인한다. `frontend/`만 바뀐 PR에서는 `build` 워크플로가 Java 검사를 건너뛴다. PR 전에 로컬에서 같은 순서로 돌려 본다. 같은 파일의 `e2e-msw` job이 Chromium을 설치해 `npx playwright test --project=msw`를 따로 돌고, 실패하면 리포트를 7일 보관한다.

## 의존성

`.npmrc`의 `save-exact=true`로 `package.json`에 정확한 버전을 적는다. 버전과 고른 이유는 ADR 0040 "버전" 절에 있다. 올릴 때는 그 표도 함께 고친다.
