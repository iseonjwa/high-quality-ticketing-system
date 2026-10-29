---
title: 도메인은 예외 종류만 알고 상태 코드 매핑은 바깥 계층이 맡는 전략인가
date: 2026-10-28
updated: 2026-10-28
asker: Junhyun An
level: 중급
tags: [layered-architecture, exception, error-handling, spring]
---

# 도메인은 예외 종류만 알고 상태 코드 매핑은 바깥 계층이 맡는 전략인가

## 질문
"도메인에서는 예외 종류만 관리하고, application layer에서 예외와 상태코드를 매핑하는 전략인가?" PR #88(`UserErrorCode`를 application에서 domain으로 이동)을 리뷰하던 중에 나온 질문이다.

## 짧은 답
그 설명은 #88의 방식이 아니라, 앞서 말한 "더 엄격한 대안"이다. #88은 도메인의 에러 코드 enum이 HTTP 상태까지 들고(`ErrorCode.status()`), 도메인이 `BusinessException`을 던지며, 공통 핸들러가 상태를 그대로 응답한다. 질문의 전략은 도메인이 상태 코드를 전혀 모르게 하고 바깥에서 매핑하는 방식이다. 그리고 그 매핑은 보통 application이 아니라 presentation(예외 핸들러)의 몫이다. 상태 코드는 HTTP라는 전달 방식의 관심사이기 때문이다.

## 자세히

두 방식을 나란히 놓으면 이렇다.

| | A. 현재(#88, 이 프로젝트 기본) | B. 순수 도메인 예외 + 바깥 매핑 |
|---|---|---|
| 도메인이 던지는 것 | `BusinessException(UserErrorCode.ACCOUNT_LOCKED)` | `AccountLockedException` 같은 도메인 예외(상태 코드 없음) |
| 상태 코드 위치 | 에러 코드 enum 안(`status()`) | presentation의 핸들러·매핑 표 |
| 도메인이 아는 것 | `common.response`의 `ErrorCode`(HTTP 상태 포함) | 아무것도 모름 |
| 새 에러 추가 | enum 한 줄 | 예외 클래스 + 매핑 한 줄 |
| 같은 도메인을 다른 전달 방식(gRPC, 배치)에 재사용 | HTTP 상태가 따라온다 | 매핑만 갈아 끼운다 |

B의 장점은 도메인 순수성과 전달 방식 교체 용이성이다. 단점은 예외 클래스와 매핑이 늘어 코드량이 커지고, 매핑이 빠지면 500으로 새는 위험이 생긴다. A는 단순하고 에러 한 곳에서 이름, 상태, 메시지를 함께 본다. 대신 도메인이 HTTP를 간접적으로 안다.

어느 쪽이든 지켜야 하는 것은 의존 방향이다. application이나 presentation이 domain을 의존하고, 그 반대는 안 된다. 에러 enum을 application에 두면 도메인이 던지는 순간 이 방향이 뒤집힌다. #88은 그래서 enum을 domain으로 옮겼다.

## 이 프로젝트에서는
- `common.response`의 `ErrorCode`·`BusinessException`·`CommonErrorCode`가 상태를 포함한다. `common/.../web/CommonExceptionHandler`가 `BusinessException`을 `FailureResponse`로 바꾼다.
- 도메인별 에러 코드는 각 서비스가 `ErrorCode`를 구현한다(`docs/guides/common-modules.md`). 즉 A가 팀 기본이다.
- 개인 판단: MVP에서는 A로 충분하다. B로 바꾸는 비용은 예외 클래스 수만큼 커지고 얻는 것은 재사용 가능성인데, 이 서비스들은 HTTP 전용이다.

## 면접 포인트
- "예외와 HTTP 상태 코드를 어디서 매핑하나요?" → 상태 코드는 전달 계층의 관심사라 이상적으로는 presentation의 예외 핸들러가 맡는다. 우리는 단순함을 위해 에러 코드에 상태를 포함하고 도메인이 던지되, 응답 변환은 공통 핸들러 한 곳에서 한다.
- "도메인이 `common.response`를 의존하는 건 괜찮은가요?" → 상태를 가진 공통 타입이라 순수하지는 않다. 트레이드오프를 알고 선택했고, 필요하면 도메인 예외로 분리할 수 있다.

## 참고
- PR #88 `refactor(user): 계층 의존 방향 위반 수정과 토큰 발급 규칙을 User로 이동`
- `docs/guides/common-modules.md` 응답·예외 절
