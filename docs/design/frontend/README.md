# 프론트(frontend) 설계

시연용 웹 화면의 설계를 둔다. 결정과 이유는 [ADR 0040](../../decisions/0040-frontend-react-spa-in-monorepo.md)에 있고, 이 문서는 팀원이 한 번에 볼 수 있게 결론을 모은다. 담당은 안준현이다(SA 6-8).

상태: 구조·스택 결정(2026-11-02). 디자인 시스템은 확정했다([design-system.md](design-system.md)). 화면마다 HTML 샘플을 먼저 만든다. 코드는 저장소 [`frontend/`](../../../frontend/README.md)에 있다(ISE-107 스캐폴딩).

원칙: **백엔드에 집중하기 위해 프론트는 단순하게 간다.** 실서비스 구조(BFF, 앞단 대기실, 서버 렌더링 + CDN)는 확인했지만, 기간 한정 시연이라는 조건에 맞춰 가장 적은 구성으로 만든다. 무엇을 알고 무엇을 하지 않았는지는 ADR 0040 4절의 "학습한 실서비스 구조와 이번 선택" 표에 있다.

## 결정 요약

| 항목 | 결정 | 이유 한 줄 |
|---|---|---|
| 코드 위치 | 이 저장소의 `frontend/` | API 계약과 화면을 한 PR로 고치고, 배포가 compose 하나로 끝난다 |
| 스택 | React SPA (Vite + TypeScript) | 디자인 시스템에 컴포넌트가 필요하고 화면이 늘었다. Notion 결정 #7(정적 HTML·JS)의 바꿀 조건을 근거로 바꿨다 |
| 함께 쓸 도구 | React Router, TanStack Query, 직접 쓴 `fetch` 래퍼, React Hook Form + Zod, Tailwind CSS + shadcn/ui, MSW, Vitest + Testing Library + Playwright, ESLint + Prettier, npm. Zustand는 필요할 때만 | 칸마다 이유와 대안은 ADR 0040 3절 |
| Spring Boot 내장 | 하지 않는다 | 토큰을 브라우저가 들고 다니는 구조라 서버 템플릿이 할 일이 없고, JVM이 늘거나 Gateway 재빌드에 화면이 묶인다 |
| 실행 | 별도 nginx 컨테이너 | Node로 빌드한 정적 파일을 nginx가 내준다. 다른 팀원은 Node 없이 compose를 띄운다 |
| API 연결 | nginx가 `/api/`를 `gateway:8080`으로 중계 | 브라우저는 한 출처만 본다. Gateway·백엔드 계약은 바뀌지 않는다 |
| CORS | 넣지 않는다 | 같은 출처라 필요 없다. 로컬은 Vite `server.proxy`가 같은 역할을 한다 |
| 토큰 | Access는 JS 메모리, 입장 토큰은 `sessionStorage`(계약대로). Refresh 위치는 인증 화면 설계 때 정한다 | 현재 계약 그대로 쓴다 |

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
