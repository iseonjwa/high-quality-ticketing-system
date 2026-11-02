# 프론트(frontend) 설계

시연용 웹 화면의 설계를 둔다. 결정과 이유는 [ADR 0040](../../decisions/0040-frontend-react-spa-in-monorepo.md)에 있고, 이 문서는 팀원이 한 번에 볼 수 있게 결론을 모은다. 담당은 안준현이다(SA 6-8).

상태: 구조·스택 결정(2026-11-02). 디자인 시스템은 확정하고 구현했다([design-system.md](design-system.md), ISE-111). API 클라이언트와 목도 있다(ISE-110). 화면은 로그인·회원가입·비밀번호 변경·내 정보(ISE-112)부터 만들고 있다. 코드는 저장소 [`frontend/`](../../../frontend/README.md)에 있다.

원칙: **백엔드에 집중하기 위해 프론트는 단순하게 간다.** 실서비스 구조(BFF, 앞단 대기실, 서버 렌더링 + CDN)는 확인했지만, 기간 한정 시연이라는 조건에 맞춰 가장 적은 구성으로 만든다. 무엇을 알고 무엇을 하지 않았는지는 ADR 0040 4절의 "학습한 실서비스 구조와 이번 선택" 표에 있다.

## 결정 요약

| 항목 | 결정 | 이유 한 줄 |
|---|---|---|
| 코드 위치 | 이 저장소의 `frontend/` | API 계약과 화면을 한 PR로 고치고, 배포가 compose 하나로 끝난다 |
| 스택 | React SPA (Vite + TypeScript) | 디자인 시스템에 컴포넌트가 필요하고 화면이 늘었다. Notion 결정 #7(정적 HTML·JS)의 바꿀 조건을 근거로 바꿨다 |
| 함께 쓸 도구 | React Router, TanStack Query, 직접 쓴 `fetch` 래퍼, React Hook Form + Zod, Tailwind CSS + shadcn/ui, MSW, Vitest + Testing Library + Playwright, ESLint + Prettier, npm. Zustand는 화면 사이에서 나누는 값(로그인 토큰)에만 | 칸마다 이유와 대안은 ADR 0040 3절 |
| Spring Boot 내장 | 하지 않는다 | 토큰을 브라우저가 들고 다니는 구조라 서버 템플릿이 할 일이 없고, JVM이 늘거나 Gateway 재빌드에 화면이 묶인다 |
| 실행 | 별도 nginx 컨테이너 | Node로 빌드한 정적 파일을 nginx가 내준다. 다른 팀원은 Node 없이 compose를 띄운다 |
| API 연결 | nginx가 `/api/`를 `gateway:8080`으로 중계 | 브라우저는 한 출처만 본다. Gateway·백엔드 계약은 바뀌지 않는다 |
| CORS | 넣지 않는다 | 같은 출처라 필요 없다. 로컬은 Vite `server.proxy`가 같은 역할을 한다 |
| 토큰 | Access는 JS 메모리, Refresh와 입장 토큰은 탭 단위 `sessionStorage` | 새로고침에도 로그인이 유지되고, 서버가 사용자당 Refresh 토큰 하나를 재발급마다 바꾸는 구조와 맞는다(ADR 0040 4절, ISE-110) |

## 구조

```
브라우저
  ├─ http://<호스트>/            → frontend 컨테이너(nginx): React 빌드 결과, 없는 경로는 index.html
  └─ http://<호스트>/api/v1/...  → nginx → gateway:8080 → user / reservation / payment
부하 도구 ──────────────────────────────→ gateway:8080 (nginx를 거치지 않는다)
```

nginx는 `/api/`만 Gateway로 넘기고 서비스로 직접 넘기지 않는다. 서비스는 Gateway가 넣은 사용자 헤더를 서명 없이 믿기 때문이다(`docs/guides/workspace.md`).

## 화면 계획

전부 계획에 넣고 단계별로 구현한다. 백엔드 API가 없는 화면은 계약 문서대로 MSW 목을 만들어 먼저 개발하고, API가 나오면 목을 끈다. 작업은 Linear 부모 이슈 ISE-104 아래 하위 이슈 하나가 PR 하나다.

| 단계 | 이슈 | 내용 | 쓰는 API (11/2 기준 구현 여부) |
|---|---|---|---|
| 0. 설계·기반 | ISE-105 | 구조·스택 결정과 이 문서 (ADR 0040) | — |
| | ISE-106 | 디자인 시스템 설계(토큰·컴포넌트 목록·화면 상태) | — |
| | ISE-107 | Vite·React·TypeScript 스캐폴딩, 도구 설정, 버전 고정 | — |
| | ISE-108 | 프론트 CI 워크플로, Java 빌드 `paths` 필터 | — |
| | ISE-109 | nginx 컨테이너(중계·SPA 폴백), compose 서비스, 포트 검사, 보안그룹·배포 바인딩 | — |
| | ISE-110 | API 클라이언트·인증 처리(재발급·멱등키·입장 토큰), MSW 목 기반 | — |
| | ISE-111 | 디자인 시스템 토큰과 기본 컴포넌트 | — |
| 1. 일반 예매 (SA 2-4 시연) | ISE-112 | 로그인·회원가입·비밀번호 변경 | 있음 |
| | ISE-113 | 공연 목록·상세(서버 시각 카운트다운) | 없음 (ISE-99 진행 중) |
| | ISE-114 | 대기열(순번 폴링·READY·입장 토큰) | 진입·순번 있음, 토큰 API 없음 (ISE-69) |
| | ISE-115 | 매수·좌석 선택과 선점(이선좌 409 재선택) | 선점 있음, 좌석 조회 없음 |
| | ISE-116 | 결제(선점 남은 시간·확인 중 폴링) | 결제 있음, 예매 조회 없음 |
| | ISE-117 | 결제 결과·내 예매·티켓 | 없음 |
| | ISE-118 | 시연 흐름 Playwright 테스트 | — |
| 2. 취소·접수형 | ISE-119 | 예매 취소와 환불 상태 | 없음 (결제 측 ISE-46) |
| | ISE-120 | 접수형 선점(202) 처리 중 → 결과 조회 | 없음 |
| 3. 추첨 | ISE-121 | 추첨 정보·응모·취소·결과 | 없음 (ISE-86) |
| | ISE-122 | 빌링키 등록 | 없음 (ISE-96) |
| 4. 운영자 | ISE-123 | 공연·회차·좌석 등록 | 있음 |
| | ISE-124 | 추첨 설정·사용자 비밀번호 초기화 | 비밀번호 초기화 있음, 추첨 설정 없음 (ISE-85) |
| 5. 기타 | ISE-125 | 토스 결제창(M4 토스 어댑터와 함께) | — |
| | ISE-126 | AI 상담(Could) | 미설계 |

선후 관계: ISE-107이 기반 이슈(108~111)를 막고, 화면 이슈는 ISE-110·111 뒤에 시작한다. 디자인 시스템 구현(111)은 설계(106) 뒤다.

화면이 따를 규칙의 정본은 SA 11장과 각 계약 문서다: [대기열](../queue/contracts.md) 2절, [예매](../reservation/contracts.md), [결제 API](../payment/api.md).

## API 클라이언트 규칙 (ISE-110)

화면은 `fetch`를 직접 부르지 않고 `frontend/src/api/`의 함수를 쓴다. 아래 규칙은 그 안에 한 번만 구현돼 있다.

| 규칙 | 동작 | 화면이 할 일 |
|---|---|---|
| 공통 응답 | 성공은 `data`만 돌려주고, 실패는 `ApiError`(`status`·`code`·`fieldErrors`·`traceId`)로 던진다. 성공 `data`는 Zod 스키마로 검증해 계약이 어긋나면 `RESPONSE_SCHEMA_MISMATCH`로 멈춘다 | 안내 문구는 `describeError(error)`로 얻는다(design-system.md 6절 표를 코드로 옮긴 것) |
| 401 | Gateway는 만료에 `TOKEN_EXPIRED`, 위조·형식 오류에 `UNAUTHORIZED`, 로그아웃된 토큰에 `TOKEN_REVOKED`를 준다(SA 11-3, ISE-131). 코드와 상관없이 401이면 재발급을 한 번 하고 같은 요청을 다시 보낸다. 동시에 받은 401은 재발급 하나를 함께 기다린다 | 재발급도 실패하면 세션이 만료로 지워진다(`expireSession`). 로그인이 필요한 화면을 `RequireAuth` 아래에 두면 로그인 화면으로 보내고 돌아올 경로를 남긴다(아래 "인증 화면 규칙"). `logout()`은 서버 요청이 실패해도 던지지 않는다. 헤더는 처음 화면으로 옮긴 뒤 부르고 `queryClient.clear()`로 이전 사용자의 조회 결과를 지운다 |
| 멱등키 | 선점·결제 함수는 `Idempotency-Key`를 인자로 받는다. 401 뒤 재시도는 같은 키와 같은 본문(복사해 둔 값)을 쓴다 | 의도마다 `newIdempotencyKey()`로 하나 만든다. `isOutcomeUnknown(error)`(응답 없음·504)면 실패로 보이지 말고 같은 키와 같은 본문으로 다시 보내거나 결과를 조회한다. 고른 좌석이 바뀌거나 예매 조회로 결제 토큰을 다시 받았으면 새 키로 보낸다(결제 토큰 원문도 본문 비교에 들어간다) |
| 입장 토큰 | `issueEntryToken`이 회차별로 저장하고, 좌석 조회·선점이 `X-Entry-Token`으로 붙인다. 저장한 토큰이 없으면 요청 없이 403 `ENTRY_TOKEN_INVALID`, 서버가 403을 주면 그 요청이 보낸 토큰만 지운다. 브라우저 시계로는 지우지 않는다(시계가 빠르면 대기열과 좌석 화면을 끝없이 오간다) | 대기열 진입 전에 `readEntryToken(scheduleId)`가 있고 `isEntryTokenFresh`면 대기열을 건너뛴다(서버 시각으로 보정한 now를 넘기면 더 정확하다). 403이면 대기열로 보낸다 |
| 조회 재시도 | TanStack Query 기본값: 연결 실패와 5xx만 두 번까지, `Retry-After`가 있으면 그 초에 0~+20%를 더해 기다린다 | 변경 요청은 자동 재시도하지 않는다. 결제 202·504의 "확인 중" 처리는 결제 화면(ISE-116)이 한다 |
| 목 | `npm run dev:mock`이면 MSW가 계약대로 응답한다. 주소에 `?mock=seat-conflict,payment-unknown`처럼 실패 시나리오를 고른다 | 시나리오 목록은 `frontend/README.md` |

- 결제 `APPROVED`는 예매 확정이 아니다. 결과 화면은 `getReservation`이 `CONFIRMED`가 될 때까지 다시 조회한다(목도 승인 직후 한 번은 `HELD`를 준다).
- 접수형 선점(202 `REQUESTED`)은 M4 고도화 모드라 응답 스키마에 아직 없다. ISE-120에서 더한다.
- 목 핸들러는 일반 예매 흐름(SA 2-4)만 있다. 취소·추첨·운영자 API는 그 화면 이슈에서 더한다.

## 인증 화면 규칙 (ISE-112)

| 규칙 | 동작 | 이유 |
|---|---|---|
| 경로 보호 | 로그인이 필요한 화면은 `app/routes.tsx`의 `RequireAuth` 아래에 둔다. 로그인 전이면 `/login`으로 보내며 history state `from`에 지금 주소(경로·검색어·해시)를 넘긴다. 보던 중 세션이 끝나도 같은 길로 간다 | 화면마다 로그인 검사를 따로 쓰지 않고, 로그인한 뒤 보던 화면으로 돌아간다 |
| 돌아갈 곳 | 로그인하면 `from`으로 간다. 앱 안의 경로만 받고(`//다른-호스트` 거절) 인증 화면이면 처음 화면으로 보낸다(`auth/navigation.ts`) | history state는 새로고침·뒤로 가기에도 남는다. 다른 사이트로 보내는 주소와 로그인 화면으로 되돌아오는 고리를 막는다 |
| 세션 만료 안내 | 재발급이 거절돼 세션이 끝나면 로그인 화면에 "로그인이 끝났어요. 다시 로그인해 주세요"를 보인다. 사용자가 로그아웃한 경우는 보이지 않는다(세션 저장소 `expired`) | 왜 로그인 화면에 왔는지 알려 준다 |
| 가입·변경 뒤 로그인 | 가입과 비밀번호 변경이 끝나면 같은 값으로 바로 로그인한다. 비밀번호를 바꾸면 이 탭의 옛 토큰부터 지운다. 로그인이 실패해도 앞 단계는 끝났으므로 실패로 보이지 않고 이메일을 채운 로그인 화면으로 보낸다 | 가입 응답에는 토큰이 없고, 비밀번호를 바꾸면 서버가 Refresh 토큰을 폐기한다. 옛 토큰이 남으면 로그인 화면이 로그인 상태로 보고 돌려보낸다 |
| 조회 결과 | 로그인을 보내기 전에 TanStack Query 캐시를 비운다 | 세션이 끝난 뒤 다른 계정으로 로그인해도 이전 사용자의 조회 결과가 보이지 않는다 |
| 입력 검사 | `auth/formSchemas.ts`가 서버 검증 규칙(비밀번호 8자 이상·UTF-8 72바이트 이하·공백만은 안 됨, 닉네임 30자 이하)을 먼저 확인한다. 이메일은 가입만 서버보다 엄격하게(도메인에 점) 보고, 로그인·비밀번호 변경은 서버(`@Email`)만큼 느슨하게 본다. 서버의 칸별 오류(400)는 그 칸 아래에 공통 안내로 옮긴다(서버 문구는 영어 기본 문구라 보이지 않는다) | 요청 전에 칸 아래에서 고치게 한다. 서버가 받는 이메일로 만든 계정이 화면에서 로그인하지 못하는 일을 막는다. 정본은 서버라 규칙이 바뀌면 함께 고친다 |
| 목 계정 | `npm run dev:mock`에서 `temp@iseonjwa.dev` / `TempPass2345`는 임시 비밀번호 계정이다. 어느 계정이든 비밀번호를 5번 틀리면 잠긴다(새로고침하면 풀린다) | 잠금·비밀번호 변경 화면을 백엔드 없이 본다 |

확인한 것(2026-11-02): 실제 Gateway(로컬 compose)에 붙인 개발 서버에서 가입 → 자동 로그인 → 내 정보 → 비밀번호 변경(자동 재로그인) → 로그아웃 → 옛 비밀번호 거절(401) → 새 비밀번호 로그인 → 5번 실패 뒤 잠금(423)을 headless Chrome으로 따라갔다.

후속:
- 운영 번들이 Vite의 500 kB 경고를 넘었다(574 kB, gzip 181 kB, React Hook Form이 더해짐). 화면이 늘어나는 ISE-113부터 경로별 지연 로딩(`lazy`)으로 나눈다.
- 로그아웃은 처음 화면으로 옮긴 뒤 `queryClient.clear()`를 부른다. 지금 처음 화면에는 조회가 없지만, ISE-113에서 공연 목록 조회가 붙으면 화면에 붙은 조회가 캐시에서 떨어져 다시 조회하지 않는다. 그때 `resetQueries()`로 바꾸거나 순서를 다시 본다.

## 팀원이 알아 둘 것

- Notion 「SA 기획·설계 결정 기록」 #7은 "정적 HTML·JS"로 남아 있다. 프론트 결정은 이 문서와 ADR 0040을 따른다. 저장소 기록과 Notion이 어긋나면 저장소 기록을 따른다(`AGENTS.md` 대전제 4).
- Gateway와 백엔드 API 계약은 바뀌지 않는다. CORS 설정도 넣지 않는다.
- compose에 frontend 서비스가 추가되면 공개 포트가 바뀐다. 보안그룹(SA 9-2), 배포 바인딩, `checkPortExposure` 공개 목록은 ISE-109에서 함께 고친다(배포 담당도 안준현, ISE-6). 로컬 `docker compose up`에 frontend 빌드가 더해지는 점은 그 PR과 팀 공지로 알린다.

## 관련 기록

| 종류 | 문서 |
|---|---|
| 결정 | [ADR 0040](../../decisions/0040-frontend-react-spa-in-monorepo.md) |
| 디자인 시스템 | [design-system.md](design-system.md) (ISE-106), 샘플 화면 [samples/seat-selection.html](samples/seat-selection.html) |
| AI 판단 | [React SPA 전환](../../ai-judgments/junhyun/2026-11-02-frontend-react-spa.md), [BFF 대신 nginx 중계](../../ai-judgments/junhyun/2026-11-02-frontend-relay-over-bff.md), [Notion 대신 저장소 문서에 기록](../../ai-judgments/junhyun/2026-11-02-records-in-docs-over-notion.md) |
| 학습 노트 | [저장소 위치와 스택](../../learning/junhyun/qna/2026-11-02-frontend-repo-and-stack.md), [React와 함께 쓸 도구](../../learning/junhyun/qna/2026-11-02-react-spa-stack.md), [중계와 BFF, 실서비스라면](../../learning/junhyun/qna/2026-11-02-reverse-proxy-vs-bff.md), [앞단 대기실과 서버 대기열](../../learning/junhyun/qna/2026-11-02-waiting-room-vs-server-queue.md) |
