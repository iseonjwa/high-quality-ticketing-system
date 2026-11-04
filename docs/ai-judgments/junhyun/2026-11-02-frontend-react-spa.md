---
title: 프론트를 손으로 쓴 정적 HTML·JS(팀 결정 #7) 대신 저장소 안 frontend/의 React SPA로 전환하고 추천 스택을 채택
date: 2026-11-02
updated: 2026-11-02
judge: Junhyun An
ai: Claude Code
verdict: 수용
area: 기타
presentation: 아님
shared: 예
---

# 프론트를 손으로 쓴 정적 HTML·JS(팀 결정 #7) 대신 저장소 안 frontend/의 React SPA로 전환하고 추천 스택을 채택

## 맥락
- 해결하려던 작업: M4 프론트(SA 6-8) 설계를 시작하면서 저장소 위치와 스택을 정한다. 다음 단계는 디자인 시스템이다.
- AI 활용 방식: 저장소를 `frontend/` 폴더와 별도 저장소 중 어디에 둘지, Spring Boot 안에 넣을지 별도 프론트 스택을 쓸지 물었다. Claude Code가 SA MASTER, Gateway 설정, Notion 결정 기록 #7을 확인한 뒤 비교했다. React로 정한 뒤에는 함께 쓸 도구를 추천받았다.

## AI 제안
- 코드는 이 저장소의 `frontend/`에 둔다. API 계약과 화면을 한 PR로 고치고, 배포가 compose 하나로 끝난다. 별도 저장소의 이득(독립 배포 주기, 팀 경계)은 이 프로젝트에 없다.
- Spring Boot 안(Thymeleaf 모듈, Gateway `static/`)에는 넣지 않는다. 브라우저가 토큰을 들고 Gateway를 부르는 구조라 서버 템플릿이 할 일이 거의 없고, JVM이 늘거나 Gateway 재빌드에 화면이 묶인다.
- 팀 결정 #7(10/24, 정적 HTML·JS)의 바꿀 조건 "화면이 늘어 생성 코드 관리가 어려워지면 SPA로 전환"을 근거로 SPA로 기운다. 디자인 시스템에 컴포넌트 단위가 필요하고, 10/24 이후 화면이 늘었고(추첨, 취소, AI 상담), 생성 방식이 Artifact에서 저장소 안의 Claude Code로 바뀌었다.
- React와 함께 Vite, TypeScript, React Router, TanStack Query, 직접 쓴 `fetch` 래퍼, React Hook Form + Zod, Tailwind CSS + shadcn/ui, MSW, Vitest + Testing Library + Playwright, ESLint + Prettier, npm을 쓰고 Zustand는 필요할 때만 쓴다. 칸마다 이유와 대안은 [ADR 0040](../../decisions/0040-frontend-react-spa-in-monorepo.md) 3절에 있다.
- 상세 비교: [저장소 위치와 스택](../../learning/junhyun/qna/2026-11-02-frontend-repo-and-stack.md), [React와 함께 쓸 도구](../../learning/junhyun/qna/2026-11-02-react-spa-stack.md)

## 판단
- 결과: 수용
- 판단자: Junhyun An
- 근거: React 전환은 "SPA쪽으로 하고, 프론트는 아예 리액트를 쓰는 방향으로 전환하자". 다른 프론트 스택을 써 본 경험이 있다고 답했다. 스택은 "스택 표대로 해줘. 저 이유들도 다 기록해놔주고", 위치는 "ㅇㅇ 확정". 그 밖의 이유는 미기재.
- 검토한 대안: 손으로 쓴 HTML·JS(결정 #7 유지), Thymeleaf 모듈, Gateway `static/` 내장, 별도 저장소, Next.js·Vue·Svelte

## 반영
- [ADR 0040](../../decisions/0040-frontend-react-spa-in-monorepo.md)에 위치·스택·도구별 이유를 기록했다.
- 미반영: 배포 연결 방식(nginx 중계 또는 BFF) 결정, Notion 결정 기록 #7 갱신

## 검증
구현 전

## 판단 이력
- 2026-11-02 수용: React SPA로 전환한다. 화면 범위는 전부 계획에 넣고 구현은 단계적으로 한다.
- 2026-11-02 수용: 추천 스택 표를 그대로 채택하고, 코드 위치를 이 저장소의 `frontend/`로 확정한다.

## 발표·면접 포인트
- 처음에는 프론트 비용을 줄이려고 생성한 정적 HTML·JS를 골랐고, 결정할 때 적어 둔 바꿀 조건(화면 수 증가)이 충족되어 SPA로 바꿨다. 결정을 뒤집을 때도 처음 정한 조건을 근거로 삼았다.
