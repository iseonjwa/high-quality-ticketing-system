---
title: 입장 토큰 유효 시간이 설정(queue.entry-token-ttl)과 상수(EntryTokenClaims.TTL) 두 곳에 있는 문제를, 설정 하나로 모으고 ISE-282와 나눠 별도 이슈로 진행하자는 AI 추천을 받아들임
date: 2026-11-15
updated: 2026-11-16
judge: youngeun
ai: Claude (Cowork)
verdict: 수용
area: 대기열
presentation: 아님
shared: 예
---

# 입장 토큰 유효 시간을 설정 하나로 모음

## 맥락
- 해결하려던 작업: ISE-282(차례 유효 시간)를 구현하던 중이었다.
- 질문: "TTL 값을 왜 application에서 읽어 repository에 인자로 넘기는가. repository가 `@Value`로 받으면 안 되나."
- AI 활용 방식: AI가 "정책 값은 한 곳에서 읽어 넘긴다"는 원칙을 설명하면서, 저장소에 이미 같은 값이 두 곳에 있다는 사실을 짚었다. 사용자가 "통일하는 게 좋아?"라고 물었다.
- 두 곳
  - Redis `issued` 유효 판단: 설정 `queue.entry-token-ttl: 10m` (`QueueProperties`)
  - JWT `exp`: 상수 `EntryTokenClaims.TTL` (`common`, `JwtEntryTokenIssuer`만 사용)

## AI 제안
- 통일한다. 한쪽만 바꾸면 조용히 어긋난다.
  - 설정만 늘리면: READY와 재발급 토큰이 나오지만 Gateway가 403을 주는 일이 반복된다.
  - 설정만 줄이면: 토큰은 유효한데 재발급이 404가 된다.
- 설정값으로 모은다.
  - 유효 시간은 정책 값이고, Redis 쪽은 이미 설정을 쓴다.
  - 상수 사용처는 발급기 하나이고 Gateway는 토큰의 `exp`만 본다.
  - 바꾸는 방법: `EntryTokenIssuer.issue`에 `Duration ttl`을 추가하고, `QueueService`가 설정값을 넘기고, 상수를 삭제한다. `contracts.md`의 `exp` 칸도 고친다.
- 오늘 측정 전 머지 일정이 있으니 ISE-282와 나눠 별도 이슈로 한다.

## 판단
- 결과: 수용
- 판단자: youngeun
- 근거: 미기재("만들어줘. 그리고 문서에도 남겨줘")
- 검토한 대안
  - 상수로 모으고 설정을 삭제: 값을 바꿀 때마다 코드를 고쳐 다시 배포해야 한다.
  - 둘 다 두고 기동 시 같은지 검사

## 반영
- Linear ISE-283 생성(상위 ISE-50, 관련 ISE-282·ISE-69).
- 2026-11-16 브랜치 `refactor/ISE-283-entry-token-ttl-config`
  - `EntryTokenIssuer.issue`에 `Duration ttl` 인자 추가, `JwtEntryTokenIssuer`가 `exp`를 그 값으로 계산
  - `QueueService.issueToken`이 `queueProperties.entryTokenTtl()`을 넘김
  - 상수 `EntryTokenClaims.TTL` 삭제
  - `docs/design/queue/contracts.md`의 `exp` 칸을 설정값 기준으로 고침

## 검증
- `JwtEntryTokenIssuerTest`: 기존 테스트를 새 인자로 바꾸고, 유효 시간을 3분으로 넘기면 `exp`가 3분 뒤가 되는 테스트를 더함.
- 상수 사용처가 남지 않았음을 검색으로 확인(`EntryTokenClaims.TTL` 0건).

## 판단 이력
- 2026-11-15 수용: 설정 하나로 모으고 ISE-283으로 분리.
- 2026-11-16 구현: 설정값을 발급기 인자로 넘기고 상수 삭제.

## 발표·면접 포인트
- 같은 정책 값이 설정과 상수 두 곳에 있으면 지금은 같아도 한쪽만 바뀌는 순간 장애가 된다. 정책 값은 application이 한 곳에서 읽어 저장소(Lua)와 토큰 발급기에 함께 넘기도록 모았다.
