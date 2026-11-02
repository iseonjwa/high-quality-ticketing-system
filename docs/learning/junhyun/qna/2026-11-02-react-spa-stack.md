---
title: React SPA로 정한 뒤 함께 쓸 도구는 무엇으로 고르나 (티켓팅 프론트 사례)
date: 2026-11-02
updated: 2026-11-02
asker: Junhyun An
level: 중급
tags: [react, vite, typescript, tanstack-query, tailwind, shadcn-ui, msw, frontend-stack]
---

# React SPA로 정한 뒤 함께 쓸 도구는 무엇으로 고르나 (티켓팅 프론트 사례)

## 질문
프론트는 React SPA로 가기로 했다. 다른 스택도 있는지, 무엇을 추천하는지 알려 달라.

## 짧은 답
React는 화면을 그리는 라이브러리라서, 빌드·라우팅·서버 데이터·스타일은 따로 고른다. 이 프로젝트의 화면은 폴링(대기 순번, 결제 확인 중, 접수 결과)과 계약 필드가 많다. 그래서 고르는 기준은 세 가지다. 서버 데이터를 다루기 쉬운가, 계약을 타입으로 묶을 수 있는가, 다음 단계인 디자인 시스템을 올리기 쉬운가. 추천 조합은 Vite + TypeScript + React Router + TanStack Query + Tailwind CSS + shadcn/ui + MSW다. 버전은 스캐폴딩 시점의 안정 버전으로 고정하고 ADR에 적는다.

## 자세히

### 1. React 말고 다른 선택지

| 선택지 | 성격 | 이 프로젝트에서 |
|---|---|---|
| Next.js | React 위의 풀스택 프레임워크. 서버 렌더링, 서버 함수 | 프론트 서버가 API를 대신 부르는 BFF 구조에 맞다. Node 서버 컨테이너가 필요하고, 화면 대부분이 로그인 뒤라 검색 노출 이점이 없다 |
| Vue 3 + Vite | 반응형 상태가 단순하다 | 충분히 쓸 만하다. AI가 생성한 예시와 디자인 시스템 생태계는 React가 더 크다 |
| Svelte 5 / SvelteKit | 번들이 작고 문법이 짧다 | 생태계와 AI 생성 품질이 React보다 얇다 |

React로 결정했으므로 아래는 React와 함께 쓸 도구다.

### 2. 함께 쓸 도구

| 영역 | 추천 | 이 프로젝트에서 고른 이유 | 대안 |
|---|---|---|---|
| 빌드 | Vite | 빌드 결과가 정적 파일이라 nginx 컨테이너로 내보낸다. 개발 서버의 `server.proxy`로 `/api`를 Gateway에 넘겨 로컬에서도 같은 출처를 만든다 | Create React App은 지원이 끝났다 |
| 언어 | TypeScript | 상태 값(`WAITING`·`READY`·`ADMITTED`, `HELD`·`REQUESTED`, `APPROVED`·`UNKNOWN`)과 단위가 다른 필드(`nextPollAfterMillis`, `nextPollAfterSeconds`)를 타입으로 구분한다 | JavaScript |
| 라우팅 | React Router (SPA 방식) | 화면이 열 개 남짓이고, 로그인·입장 토큰이 필요한 화면을 경로 단위로 막는다. 자료와 AI 생성 예시가 가장 많다 | TanStack Router (경로 타입 안전, 익히는 비용) |
| 서버 데이터 | TanStack Query | 폴링 간격을 직전 응답 값으로 정할 수 있다(`refetchInterval`에 함수). 선점 뒤 좌석 조회 무효화, 실패 재시도, 로딩·에러 상태를 한 방식으로 다룬다 | SWR, `useEffect` 직접 작성 |
| HTTP | `fetch` 래퍼 직접 작성 | 공통 응답(`data`·`code`·`traceId`) 풀기, 401이면 재발급을 한 번만 하고 재시도, `Idempotency-Key`를 재시도 때 같은 값으로 재사용, `X-Entry-Token` 첨부를 한 곳에 둔다. 규칙이 프로젝트 고유라 어차피 감싸야 한다 | axios 인터셉터, ky |
| 클라이언트 상태 | 필요할 때만 Zustand | 로그인 토큰처럼 화면 사이에서 공유하는 값만. 고른 좌석은 화면 안 상태로 충분하다 | React Context |
| 폼·검증 | React Hook Form + Zod | 회원가입, 운영자 등록 폼(공연·회차·좌석·추첨). Zod 스키마로 응답도 런타임 검증해 계약 어긋남을 바로 본다 | 직접 검증 |
| 스타일·디자인 시스템 | Tailwind CSS + shadcn/ui | 색·간격·글꼴을 CSS 변수(디자인 토큰)로 두고 Tailwind 테마에 연결한다. shadcn/ui는 컴포넌트 코드를 저장소에 복사해 소유하므로 티켓팅에 맞게 고친다. 접근성은 Radix 기본 동작을 쓴다 | MUI, Mantine (완성된 모양, 바꾸기 어려움) |
| API 목 | MSW (Mock Service Worker) | 아직 없는 API(공연·좌석 조회, 내 예매, 추첨, 취소)를 계약대로 흉내 내 먼저 만든다. 409 이선좌, 202 `UNKNOWN`, 503 `PG_UNAVAILABLE` 같은 실패 화면도 재현한다 | json-server |
| 테스트 | Vitest + React Testing Library, 시연 흐름은 Playwright | 단위·컴포넌트는 Vitest, 오픈 → 대기 → 선점 → 결제 한 흐름은 브라우저 테스트 | Jest |
| 린트·포맷 | ESLint + Prettier | Vite 템플릿 기본 구성에 React Hooks 규칙 포함 | Biome |
| 패키지 관리 | npm | Node에 기본으로 들어 있어 추가 설치가 없다 | pnpm |

### 3. 좌석 배치도
라이브러리 없이 SVG나 CSS Grid로 그린다. 좌석은 행·번호가 정수(ADR 0033)라 좌표 계산이 단순하다. 좌석 수가 수천 개를 넘어 렌더링이 느려지면 그때 Canvas를 검토한다.

## 이 프로젝트에서는
- 프론트 결정: [`docs/ai-judgments/junhyun/2026-11-02-frontend-react-spa.md`](../../../ai-judgments/junhyun/2026-11-02-frontend-react-spa.md). 위 조합은 [ADR 0040](../../../decisions/0040-frontend-react-spa-in-monorepo.md)으로 채택했다.
- 폴링 규칙: SA 11-5, `docs/design/queue/contracts.md` 2절(입장 토큰은 `sessionStorage`, 403 `ENTRY_TOKEN_INVALID`면 지우고 다시 진입), `docs/design/payment/api.md`(202는 "확인 중", `nextPollAfterSeconds` 뒤 조회).
- M4 토스 테스트 모드 어댑터가 들어오면 토스 결제 SDK(브라우저 스크립트)를 결제 화면에 붙인다.

## 면접 포인트
- 서버 데이터와 화면 상태를 왜 나눴나: 서버 데이터는 캐시·재시도·무효화가 필요한 다른 종류의 상태라 TanStack Query에 맡기고, 화면에만 있는 값만 컴포넌트 상태로 둔다.
- 백엔드가 준비되지 않은 API를 어떻게 먼저 붙였나: 저장소의 계약 문서대로 MSW 목을 만들고, 실제 API가 나오면 목을 끄고 같은 코드로 붙였다.

## 참고
- React, Vite, React Router, TanStack Query, Tailwind CSS, shadcn/ui, MSW 공식 문서 (버전은 스캐폴딩 시점에 확인)
