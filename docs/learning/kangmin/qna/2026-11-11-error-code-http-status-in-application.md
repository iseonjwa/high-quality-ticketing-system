---
title: application 계층은 HTTP를 몰라야 하는데 왜 에러 코드(HTTP 상태 포함)가 application에 있나
date: 2026-11-11
updated: 2026-11-11
asker: GGangMY
level: 중급
tags: [layered-architecture, error-code, exception-handling, http, coupling]
---

# application 계층은 HTTP를 몰라야 하는데 왜 에러 코드(HTTP 상태 포함)가 application에 있나

## 질문
애플리케이션은 HTTP를 몰라야 되는데 왜 에러 코드는 애플리케이션에 있을까?

## 짧은 답
- "HTTP를 모른다"는 보통 **HTTP 프레임워크 타입에 의존하지 않는다**(`HttpStatus`, `ResponseEntity`, `HttpServletRequest`를 import하지 않는다)는 뜻이다. 이 프로젝트의 `ErrorCode`는 `int status()`만 가진 순수 Java 인터페이스라 이 기준은 지킨다.
- 다만 `409`, `404` 같은 **숫자의 의미는 HTTP 어휘**다. 컴파일 의존은 없지만 의미 의존은 있다. 이것은 의도한 타협이다.
- 타협한 이유: 에러 하나를 "코드·메시지·상태" 한 줄로 정의하고, 공통 예외 처리기 하나가 모든 서비스에서 같은 방식으로 응답한다. 에러를 추가할 때 고칠 곳이 한 곳이다.
- 에러 코드 자체(`SEAT_ALREADY_HELD`)는 HTTP가 아니라 업무 어휘다. HTTP 냄새가 나는 것은 상태 숫자 하나뿐이다.

## 자세히

### 1. "몰라야 한다"의 두 층위
| 층위 | 뜻 | 이 프로젝트 |
|---|---|---|
| 컴파일 의존 | application 코드가 Spring Web·Servlet 타입을 import하지 않는다 | 지킨다. `ErrorCode`, `BusinessException`은 Spring 의존이 없다 |
| 의미 의존 | application이 "이 실패는 HTTP 409다"라는 판단을 직접 한다 | 지키지 않는다. 각 `XxxErrorCode`가 상태 숫자를 직접 들고 있다 |

컴파일 의존을 끊는 목적은 테스트(웹 없이 서비스만 호출)와 입구 교체(HTTP·Kafka·스케줄러가 같은 유스케이스 호출)다. 이 두 목적은 int 숫자 하나로는 깨지지 않는다. Kafka 소비자가 `BusinessException`을 받아도 숫자를 무시하면 그만이다.

### 2. 선택지 비교
| 안 | 방식 | 얻는 것 | 대가 |
|---|---|---|---|
| A. 에러 코드에 상태 숫자 포함(현재) | `SEAT_ALREADY_HELD(409, "...")` | 정의가 한 곳. 처리기가 범용. 계약 문서의 "코드·상태" 표와 1:1 | application이 HTTP 의미를 안다. HTTP가 아닌 입구에서는 쓰이지 않는 필드 |
| B. 실패 종류(category)만 두고 표현 계층이 매핑 | `SEAT_ALREADY_HELD(CONFLICT, "...")`, 처리기가 `CONFLICT → 409` | 계층 경계가 깨끗하다. gRPC 등 다른 프로토콜에 같은 종류를 다르게 매핑 가능 | 종류 enum과 매핑 표가 하나 더 생긴다. 400과 422처럼 세밀한 구분을 하려면 종류가 늘어난다 |
| C. 표현 계층에 `Map<ErrorCode, 상태>` | 에러별 상태를 presentation에서 따로 정의 | 가장 엄격한 분리 | 에러 추가 시 두 곳 수정. 빠뜨리면 500으로 나간다 |
| D. 종류별 예외 클래스 | `NotFoundException`, `ConflictException` | Java 타입으로 분기 가능 | 예외 클래스가 늘고, 코드 문자열은 따로 관리해야 한다 |

B가 교과서적인 답이다. A는 "서비스가 모두 HTTP API를 내보내는 MSA"에서 단순함을 택한 실용안이다.

### 3. 언제 바꿔야 하나
- HTTP가 아닌 입구가 **실패 종류로 분기해야 할 때**. 예: Kafka 소비자가 "재시도할 실패인지, DLQ로 보낼 실패인지"를 `status() == 409` 같은 숫자로 판단하기 시작하면 HTTP 의미가 메시징 로직에 스며든 것이다. 이때 B로 옮긴다.
- gRPC처럼 다른 응답 프로토콜이 생길 때.

## 이 프로젝트에서는
- 계약: `common/src/main/java/com/iseonjwa/common/response/ErrorCode.java` — `int status()`, `String code()`, `String message()`만 있고 import가 없다. `docs/guides/common-modules.md`도 `response` 패키지를 "Spring 의존 없음"으로 적는다.
- `BusinessException` 생성자는 상태가 400~599인지 검사한다. 즉 업무 예외는 "HTTP 실패 응답이 될 수 있는 것"으로 설계돼 있다.
- HTTP 변환은 `common/.../web/CommonExceptionHandler.java`가 `ResponseEntity.status(error.status())`로 한 곳에서 한다. 상태 숫자를 실제 HTTP 응답으로 바꾸는 일은 표현 쪽 공통 코드가 맡는다.
- 확인한 사실(2026-11-11 grep): `presentation/messaging`·`scheduling` 입구에서 `BusinessException`의 상태로 분기하는 코드는 없다. 메시징 쪽 사용은 local 진단용 `LocalMessagingProbe` 하나다. 그래서 지금은 A의 대가가 실제 문제로 나타나지 않는다.
- 이 타협을 명시한 ADR은 찾지 못했다. `docs/decisions/`에서 `ErrorCode`를 다루는 기록은 공통 모듈을 하나로 묶은 0013뿐이다. 근거는 ISE-5 공통 계약(`common-modules.md`)이다.
- 덧붙여 위치가 섞여 있다: 대부분 `application` 패키지에 있지만 `UserErrorCode`, `LotteryErrorCode`는 `domain` 패키지에 있다.

## 면접 포인트
- "응용 계층이 HTTP 상태 코드를 알아도 되나?" → 컴파일 의존과 의미 의존을 나눠 답한다. 프레임워크 타입은 쓰지 않아 테스트와 입구 교체는 지켰고, 숫자 의미는 단순함을 위해 감수했다. HTTP가 아닌 입구가 그 숫자로 분기하기 시작하면 실패 종류(category)로 바꾸고 매핑을 표현 계층으로 옮긴다.
- "에러 코드 문자열과 HTTP 상태를 왜 둘 다 내려주나?" → 상태는 클라이언트·게이트웨이·모니터링이 보는 넓은 분류(409는 정상 업무 거절로 집계), 코드는 화면이 문구·분기를 정하는 세밀한 이유다.

## 참고
- `common/src/main/java/com/iseonjwa/common/response/ErrorCode.java`, `BusinessException.java`
- `common/src/main/java/com/iseonjwa/common/web/CommonExceptionHandler.java`
- `docs/guides/common-modules.md`, `docs/decisions/0043-inbound-adapters-in-presentation.md`
