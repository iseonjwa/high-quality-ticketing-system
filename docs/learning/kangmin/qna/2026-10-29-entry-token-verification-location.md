---
title: 입장 토큰을 Gateway, Reservation, 양쪽 중 어디서 검증할까
date: 2026-10-29
updated: 2026-10-29
asker: GGangMY
level: 중급
tags: [entry-token, gateway, verification, api-design, security]
---

# 입장 토큰을 Gateway, Reservation, 양쪽 중 어디서 검증할까

## 질문
Gateway에서 검증을 하는 것과, Reservation에서 검증을 하는 것과 양쪽에서 검증을 하는 것과 각 선택지에 따른 API 변경점과 장점 단점을 정리해줘

## 짧은 답
- 갈림길은 "토큰의 회차 ID와 비교할 **요청 회차**를 누가 읽을 수 있나"다. 좌석 조회는 회차가 경로에, 좌석 선점은 본문(`scheduleId`)에 있다. Gateway는 본문을 읽기 어렵고 Reservation은 둘 다 쉽게 읽는다.
- Gateway만 검증하려면 선점 API 경로를 바꾸거나 본문을 읽어야 한다. Reservation만 검증하면 외부 API는 그대로지만 SA 7-3(`#56`)의 "검증: Gateway"를 바꾼다. 양쪽으로 나누면 API는 그대로지만 검증이 두 곳에 걸친다.
- 추천은 **Reservation 단독으로 시작**하고, 부하 측정에서 잘못된 토큰 요청이 문제가 되면 Gateway 사전 검사를 더해 양쪽 분담으로 옮기는 것이다. 팀 결정을 바꾸는 일이라 대기열·좌석 선점·좌석 조회 담당과 팀 확인이 필요하다.

## 자세히

### 1. 선택지별 동작
| 선택지 | Gateway | Reservation |
|---|---|---|
| A. Gateway 단독 | 서명·`typ`·`aud`·`exp`·`sub` = `X-User-Id`·회차 = 요청 회차 전부 | 검사 없음 |
| B. Reservation 단독 | 헤더를 그대로 통과 | 전부(인터셉터 하나, 입장 토큰이 필요한 컨트롤러에만) |
| C. 양쪽 분담 | 서명·`typ`·`aud`·`exp`·`sub`. 통과하면 토큰의 회차 ID를 내부 헤더(예: `X-Entry-Schedule-Id`)로 넘긴다 | 내부 헤더 = 요청 회차만 비교 |
| C'. 양쪽 모두 전부 | 전부(본문 제외) | 전부 다시 | 

C'는 내부망 우회 대비(심층 방어)인데, 내부 서비스는 이미 외부에 노출되지 않고(SA 9-2 `expose`만) `X-User-Id`도 Gateway를 믿는 구조라 같은 수준의 신뢰를 깨는 이유가 약하다. 아래 비교에서는 A·B·C만 본다.

### 2. API·문서 변경점
| | A-1. Gateway, 경로로 회차 읽기 | A-2. Gateway, 본문 읽기 | B. Reservation 단독 | C. 양쪽 분담 |
|---|---|---|---|---|
| 외부 API | 선점 `POST /api/v1/reservations` → `POST /api/v1/schedules/{scheduleId}/reservations`, 본문 `scheduleId` 제거 | 없음 | 없음 | 없음 |
| 내부 헤더 | 없음 | 없음 | 없음 | `X-Entry-Schedule-Id` 추가. Gateway가 외부에서 온 같은 헤더를 지운다(지금 `X-User-Id`를 지우는 방식과 같음) |
| 키 `ENTRY_TOKEN_SECRET` | Gateway(검증)·Reservation(발급) | 같음 | Reservation만 | Gateway·Reservation |
| SA 수정 | 11-4·11-5 선점 경로, 6-4 | 7-3 표 그대로 | 7-3 "검증: Gateway" → Reservation, 6-3 "Gateway 필터", 9-5 표 행 | 7-3 검증 규칙을 두 줄로 나눔 |
| 담당 영향 | 좌석 선점(경로·테스트), 프론트 | 대기열(Gateway 필터) | 대기열(인터셉터), 좌석 선점·조회(적용 표시) | 대기열(필터+인터셉터), 좌석 선점·조회(적용 표시) |

### 3. 장단점
| 선택지 | 장점 | 단점 |
|---|---|---|
| A-1 | 검증 한 곳. 잘못된 토큰을 Gateway에서 끊어 Reservation까지 가지 않는다. SA 7-3·H1(Gateway 인증 일원화)과 맞는다 | 좌석 선점 API 계약이 바뀐다(ISE-7 진행 전이라 지금이 가장 싸다). Gateway가 입장 키를 가져 입장 토큰을 만들 수 있다(SA 7-3의 HMAC 한계) |
| A-2 | 외부 API를 바꾸지 않는다 | 가장 붐비는 선점 요청마다 본문을 메모리에 담았다가 다시 흘려보낸다(WebFlux). Gateway 필터가 선점 본문 형식을 알아야 해서 선점 API가 바뀌면 Gateway도 바뀐다 |
| B | 외부 API 그대로. 발급자와 검증자가 같은 서비스라 키가 한 곳에만 있어 HMAC 한계(검증자가 위조 가능)가 사라진다. 발급·검증 코드를 대기열 담당이 한 서비스에서 테스트한다(MockMvc). Gateway 변경 없음 | 잘못된 토큰 요청도 Reservation까지 온다(DB 전 단계에서 거절되므로 비용은 네트워크 한 번과 요청 스레드). 팀 결정 `#56`(검증: Gateway)과 SA 6-3·9-5를 바꾼다. 나중에 입장 토큰이 필요한 API가 다른 서비스로 가면 그 서비스에도 키를 나눠야 한다 |
| C | 외부 API 그대로. 위조·만료 토큰은 Gateway에서 끊긴다. 본문 버퍼링이 없다 | 검증이 두 곳이다. 컨트롤러에 회차 비교를 빠뜨리면 다른 회차 입장권이 통한다(적용 누락을 테스트로 막아야 한다). Gateway와 Reservation 양쪽 코드·키·테스트가 필요하다 |

### 4. 추천
B로 시작한다.
- 이유: 담당 간 조율이 가장 적다(Gateway·외부 API 변경 없음). 검증에 필요한 값(경로·본문의 회차, `X-User-Id`)이 Reservation에 모두 있다. 키가 발급 서비스에만 있어 보안 한계 하나가 없어진다.
- 감수: 잘못된 토큰 요청이 Reservation까지 온다. 팀 결정 `#56`을 바꾸므로 팀 확인과 ADR이 필요하다.
- 바꿀 조건: 부하 테스트에서 토큰 없음·만료 요청 비율이 높아 Reservation 요청 스레드나 응답 p95에 영향이 보이면, Gateway에 서명·만료 사전 검사를 더해 C로 옮긴다. B의 인터셉터는 그대로 두고 앞에 한 겹을 더하는 것이라 되돌리는 비용이 작다.
- A-1을 고를 경우: "Gateway 인증 일원화"를 지키는 것을 우선한다면 A-1이 가장 깔끔하다. 이때는 ISE-7 착수 전에 선점 경로를 바꿔야 한다.

### 5. 검증 항목별로 보기 (추가 질문)
선택지 전체가 아니라 검증 항목 하나하나를 "어디서 하면 무엇이 좋고 나쁜가"로 나누면, A·B·C의 장단점이 어디서 오는지 보인다.

| 검증 항목 | 필요한 것 | Gateway에서 하면 | Reservation에서 하면 | A | B | C |
|---|---|---|---|---|---|---|
| 서명 | 입장 키 | 위조 토큰을 가장 앞에서 끊는다. 대신 Gateway가 키를 가져 토큰을 만들 수 있다 | 키가 발급 서비스에만 있다. 위조 토큰도 Reservation까지 온다 | GW | RS | GW |
| `typ`·`aud` | 토큰 본문 | 서명과 같은 자리에서 한 번에 | 같음 | GW | RS | GW |
| `exp` | 현재 시각 | 발급(Reservation)과 다른 서버 시계로 판정한다. 시계가 어긋나면 만료가 몇 초 당겨지거나 늦어진다 | 발급과 같은 서비스 시계라 어긋남이 작다 | GW | RS | GW |
| `sub` = 로그인 사용자 | JWT 검증 결과(`X-User-Id`) | JWT 필터 뒤에 돌아야 한다(필터 순서 규칙) | Gateway가 넘긴 `X-User-Id`를 그대로 쓴다. 순서 걱정이 없다 | GW | RS | GW |
| 회차 = 요청 회차 | 요청의 회차(조회는 경로, 선점은 본문) | 경로는 쉽다. 본문은 버퍼링이 필요해 선점 경로를 바꾸거나(A-1) 본문을 읽어야 한다(A-2) | 경로·본문 모두 이미 파싱돼 있다 | GW | RS | RS |
| 적용 대상 API | 어느 API가 입장 토큰을 요구하나 | Gateway 라우트 설정 한 곳에 목록이 모인다. 컨트롤러 경로가 바뀌면 설정이 어긋날 수 있다 | 컨트롤러 옆 표시(애너테이션)로 코드와 같이 움직인다. 표시를 빠뜨릴 수 있어 테스트가 필요하다 | GW | RS | 둘 다 |
| 거절 응답 | 403 `ENTRY_TOKEN_INVALID` 실패 형식 | Gateway 오류 작성기(`GatewayErrorWriter`) | 공통 `web` 예외 처리기 | GW | RS | 사유에 따라 둘 중 하나 |

항목별로 정리하면:
- 서명·`typ`·`aud`·`exp`·`sub`는 **어디서 해도 같은 값으로 판정된다.** 차이는 "잘못된 요청을 어디서 끊나"(Gateway가 앞)와 "키와 시계를 어디에 두나"(Reservation이 발급과 같은 곳)뿐이다.
- 회차 비교만 **Reservation이 확실히 유리하다.** 선점 회차가 본문에 있기 때문이다. A의 단점(경로 변경이나 본문 버퍼링)은 전부 이 한 항목에서 나온다.
- 적용 대상 목록은 장단이 맞선다. Gateway는 목록이 한 곳이고 Reservation은 코드와 같이 움직인다.

그래서 선택지별 장단점은 이렇게 요약된다.
| 선택지 | 장점이 나오는 항목 | 단점이 나오는 항목 |
|---|---|---|
| A | 서명·만료 등 앞단 차단, 적용 대상 목록 한 곳 | 회차 비교(경로 변경 또는 본문 버퍼링), 키가 두 곳, 시계가 둘 |
| B | 회차 비교, 키·시계 한 곳, `sub` 순서 걱정 없음 | 잘못된 토큰이 Reservation까지 옴, 적용 표시 누락 위험 |
| C | 앞단 차단 + 회차 비교를 각자 잘하는 곳에서 | 적용 대상·거절 응답이 두 곳에 나뉘고, 내부 헤더를 지우는 규칙이 하나 더 생김 |

C는 항목마다 유리한 쪽을 고른 조합이다. 대신 "한 토큰의 검증이 두 곳에 걸친다"는 비용을 낸다. B에서 시작해 측정 뒤 C로 옮기자는 4절 추천은, 앞단 차단이 실제로 필요한지(잘못된 토큰 요청의 비율)를 먼저 숫자로 보자는 것이다.

### 6. B는 구체적으로 어떻게 동작하나 (추가 질문)
이미 있는 `@RequireRole` 구조(`common/web`의 `RequireRole`·`RequireRoleInterceptor`·`GatewayIdentityFilter`)를 그대로 따른다.

요청 흐름(좌석 선점 예):
1. 클라이언트: `POST /api/v1/reservations`, 헤더 `Authorization`(Access)·`X-Entry-Token`, 본문 `{scheduleId: 31, seatIds: [...]}`
2. Gateway: 지금처럼 JWT만 검증하고 `X-User-Id`를 붙인다. `X-Entry-Token`은 손대지 않고 넘긴다. Gateway 코드 변경 없음
3. Reservation `GatewayIdentityFilter`(기존): `X-User-Id`를 요청 속성에 둔다
4. Reservation `EntryTokenInterceptor`(신규): `@RequireEntryToken`이 붙은 핸들러에만 돈다. 서명(`ENTRY_TOKEN_SECRET`, `TokenDecoders.signingKey`) → `typ`·`aud` → `exp` → `sub` = `X-User-Id`. 통과하면 토큰의 회차 ID를 담은 `EntryPass`를 요청 속성에 둔다. 실패하면 403 `ENTRY_TOKEN_INVALID`
5. 컨트롤러가 `EntryPass`를 인자로 받아(인자 리졸버) 서비스에 넘긴다. 서비스가 `entryPass.assertSchedule(command.scheduleId())`로 회차를 비교한다. 다르면 같은 403

회차 비교를 인터셉터가 아니라 서비스에서 하는 이유: 인터셉터는 본문을 객체로 바꾸기(`@RequestBody`) **전에** 돈다. 인터셉터에서 본문을 읽으면 스트림을 써 버려 컨트롤러가 다시 읽을 수 없다. 그래서 1~3절에서 "Reservation은 본문을 이미 파싱해 둔다"고 한 것은 컨트롤러 단계 이야기이고, 인터셉터 단계에서는 Gateway와 같은 제약이 있다. 좌석 조회처럼 경로에 회차가 있는 API는 인터셉터에서 바로 비교할 수도 있지만, 두 API를 같은 방식으로 두려고 서비스 비교로 통일한다.

담당별 할 일:
| 담당 | 할 일 |
|---|---|
| 대기열 | `@RequireEntryToken`, `EntryTokenInterceptor`, `EntryPass`·인자 리졸버, 토큰 검증 테스트(위조·만료·`typ`·`aud`·`sub` 불일치) |
| 좌석 선점 | 선점 컨트롤러에 표시를 달고 서비스에서 `assertSchedule` 호출 |
| 좌석 조회 | 좌석 조회 컨트롤러에 같은 표시와 비교 |
| Gateway·공통 | 없음(공통 모듈에 둘 경우만 리뷰) |

빠뜨림 방지 테스트: 입장 토큰이 필요한 두 API를 토큰 없이, 다른 회차 토큰으로 호출해 403이 나는지 각 API 테스트에 넣는다. 표시를 빠뜨리거나 비교를 빼면 이 테스트가 실패한다(변이 테스트로 확인할 것).

위치: 입장 토큰은 Reservation만 쓰므로 `reservation-service` 안 `queue` 모듈에 둔다. 다른 서비스가 쓰게 되면 그때 `common/web`으로 옮긴다.

SA 변경: 7-3 "검증: Gateway" → Reservation, 6-3 "입장 토큰 검증 필터 (Gateway)" → Reservation 인터셉터, 9-5 표의 "좌석 조회·선점의 입장 토큰 검증" 행은 위치만 바뀌고 동작(Redis 없이 서명 검증)은 같다. 결정 기록 `#56` 수정 내역과 ADR이 필요하다.

### 7. 확인 못 한 것
- 오픈 순간 토큰 없음·만료 요청의 비율. 측정 전이다. B와 C를 가르는 숫자다.
- Spring Cloud Gateway(WebFlux)에서 본문 버퍼링이 처리량에 주는 영향. 측정 전이다(A-2 판단 근거).

## 이 프로젝트에서는
- 근거: SA 7-3(`#56` 토큰 규칙), 6-3(대기열 담당의 Gateway 필터), 6-5(좌석 조회 입장 토큰 필요), 9-2(내부 서비스 `expose`), 9-5(Redis 장애 시 입장 토큰 서명 검증은 계속), 11-4·11-5(선점 경로·본문)
- 코드: Gateway는 외부에서 온 `X-User-Id`·`X-User-Role`을 지운다(`GatewayAuthenticationFilter`). C의 내부 헤더도 같은 방식으로 지우면 된다
- 관련 노트: [ISE-20 계약 범위](2026-10-29-ise20-entry-token-contract-scope.md), [입장 토큰 구조](2026-10-29-entry-token-structure.md)

## 면접 포인트
- "토큰 검증을 Gateway가 아니라 서비스에서 한 이유는?" → 비교할 값(본문의 회차)이 서비스에만 있고, 발급자와 검증자를 같은 서비스에 두면 HMAC 키가 한 곳에만 있어 검증자가 토큰을 위조할 수 있는 한계가 사라졌다. 잘못된 요청이 서비스까지 오는 비용은 측정으로 보고 필요하면 Gateway 사전 검사를 더하기로 했다.

## 이력
- 2026-10-29: 검증 항목별 분석(5절)을 더했다.
- 2026-10-29: B의 구체 흐름(6절)을 더했다. 인터셉터는 본문 파싱 전에 돌아서 회차 비교는 서비스에서 한다는 점을 함께 바로잡았다.
