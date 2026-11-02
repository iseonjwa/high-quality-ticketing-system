---
title: 프론트 Refresh 토큰을 탭 단위 sessionStorage에 두는 제안을 수용
date: 2026-11-02
updated: 2026-11-02
judge: Junhyun An
ai: Claude Code (구현 중 제안), 셀프 리뷰는 Claude Code 하위 에이전트(Opus)
verdict: 수용
area: 기타
presentation: 아님
shared: 예
---

# 프론트 Refresh 토큰을 탭 단위 sessionStorage에 두는 제안을 수용

## 맥락
- 해결하려던 작업: ISE-110에서 프론트 API 클라이언트(401 재발급, 멱등키, 입장 토큰)를 만들면서, ADR 0040이 남겨 둔 "Refresh 토큰 보관 위치"를 정한다.
- AI 활용 방식: 이슈를 맡기자 AI가 user-service의 세션 저장 방식(`RedisSessionStore`)과 토큰 수명 설정을 읽고 보관 위치를 비교해 제안했다. 코드와 ADR에 반영한 뒤 PR #177에서 판단을 요청했다.

## AI 제안
- Access 토큰은 메모리에만 두고, Refresh 토큰은 탭 단위 `sessionStorage`에 둔다. 새로고침 뒤 첫 요청은 저장한 Refresh 토큰으로 먼저 재발급한다.
- 근거: 서버는 사용자당 Refresh 토큰을 하나만 두고 재발급마다 바꾼다. 같은 토큰으로 두 번 재발급하면 401 `TOKEN_REVOKED`다. 탭 안에서는 동시 401의 재발급을 하나로 묶고, 탭 사이에서는 토큰을 나눠 쓰지 않는 쪽이 이 구조와 맞는다. 입장 토큰도 계약상 탭 단위 `sessionStorage`라 범위가 같아진다.
- 고르지 않은 대안: 메모리만(새로고침하면 로그인이 풀려 오픈 순간에 치명적), `localStorage`(브라우저를 닫아도 14일 남고, 여러 탭이 한 토큰을 나눠 쓰면 탭 사이 잠금과 동기화가 필요), httpOnly 쿠키(XSS 탈취를 막지만 user-service 응답과 Gateway를 바꿔야 해 보강 후보로 남김).
- 감수: 새 탭에서는 다시 로그인한다. 다른 탭에서 다시 로그인하면 먼저 연 탭은 최대 30분 뒤 로그인 화면으로 간다.

## 판단
- 결과: 수용
- 판단자: Junhyun An
- 근거: 미기재. 제안이 담긴 PR #177을 머지했고, "제안을 받아들인 것으로 판단 기록을 남길지"를 묻자 "ㅇㅇ"라고 답했다.
- 검토한 대안: 위 AI 제안의 대안 표(ADR 0040 4절)

## 반영
- [ADR 0040](../../decisions/0040-frontend-react-spa-in-monorepo.md) 4절 "Refresh 토큰 보관 위치"
- `frontend/src/auth/session.ts`(Zustand `persist`의 `partialize`로 Refresh 토큰만 저장), `frontend/src/auth/refresh.ts`(재발급 하나로 묶기, 재발급 중 세션이 바뀌면 결과로 덮지 않음)
- [설계 요약](../../design/frontend/README.md) "API 클라이언트 규칙"

## 검증
- `frontend` 테스트: Refresh 토큰만 `sessionStorage`에 남고 Access 토큰은 저장되지 않음, `persist` 복원을 거친 새로고침 뒤 첫 요청이 먼저 재발급함, 동시 401이 재발급을 한 번만 부름. 단일 재발급 코드를 빼면 해당 테스트가 실패하는 것을 확인했다.
- 헤드리스 Chrome의 `npm run dev:mock`에서 새로고침 뒤 로그인이 유지됨을 확인했다.

## 판단 이력
- 2026-11-02 수용: PR #177 머지 뒤 기록 요청에 동의했다.

## 발표·면접 포인트
- 토큰 보관 위치는 보안만이 아니라 서버의 세션 모델(사용자당 Refresh 하나, 재발급마다 교체)과 맞아야 한다. 교체형 Refresh에서는 동시 재발급을 하나로 묶지 않으면 두 번째 재발급이 실패해 사용자가 로그아웃된다.
