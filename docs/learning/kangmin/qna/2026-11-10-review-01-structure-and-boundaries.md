---
title: 발표 대비 리뷰 1. 서비스 구성과 경계는 왜 이렇게 나눴나
date: 2026-11-10
updated: 2026-11-10
asker: kangmin
level: 중급
tags: [msa, modular-monolith, api-gateway, trust-model, common-module, layered-architecture, presentation-prep]
---

# 발표 대비 리뷰 1. 서비스 구성과 경계는 왜 이렇게 나눴나

## 질문
발표(11/19)를 앞두고 프로젝트 전체를 주제별로 리뷰한다. 첫 주제는 서비스를 몇 개로 어떻게 나눴는지, Gateway가 무엇을 맡는지, 서비스가 Gateway를 얼마나 믿는지, 공통 모듈과 계층 규칙을 어떻게 정했는지다.

## 짧은 답
- 업무 서비스는 User·Reservation·Payment 3개다. 경계는 "한 트랜잭션으로 지켜야 하는 불변식"과 "장애 격리가 실제로 되는가"로 그었다. 처음 안은 5개였다. Queue와 Lottery를 Reservation 안의 모듈로 넣고, 측정 조건을 만족하면 분리하기로 했다(SA 7-1).
- Reservation 안에서는 모듈러 모놀리스(한 프로세스 안에서 모듈 경계를 규칙으로 지키는 방식)를 쓴다. 의존 방향을 한쪽으로 고정하고, 역방향이 필요한 곳은 인터페이스를 호출받는 쪽 모듈에 두는 의존성 역전으로 푼다(ADR 0034).
- 인증 판정은 Gateway의 `AccessPolicy.of()` 한 곳에서 한다(ADR 0006). 서비스는 Gateway가 넣어 준 `X-User-*` 헤더를 믿는다(ADR 0008). 그래서 서비스 포트를 외부에 열지 않는다는 배포 전제가 필수다. 서비스가 JWT를 직접 검증하는 안(ADR 0009)은 고려했다가 철회했다.
- 공통 코드는 `common` 모듈 하나에 둔다. 웹·JPA·Kafka 의존은 `compileOnly`로 두고, 각 서비스는 필요한 설정만 `@Import`로 가져온다(ADR 0013). 들어오는 입구(컨트롤러·소비자·스케줄러)는 presentation 계층에 둔다(ADR 0043).

## 자세히

### 1. 서비스 경계: 5개 → 3개
| 선택지 | 장점 | 단점 |
|---|---|---|
| 5개 (User·Queue·Reservation·Lottery·Payment) | 서비스별 확장·배포가 독립적이다 | Queue는 자기 데이터가 없고 Queue가 멈추면 예매도 멈춘다. 떼어 내도 장애가 격리되지 않는다. Lottery는 Reservation과 좌석·예매를 공유해 분산 트랜잭션이 생긴다 |
| **3개 (User·Reservation·Payment)** | 좌석이 한 번만 팔린다는 불변식을 한 DB 트랜잭션으로 지킨다. 외부 PG의 지연·장애는 Payment에 가둔다 | Reservation이 커진다. 모듈 경계를 빌드 구조가 아니라 규칙으로 지켜야 한다 |
| 모놀리스 1개 | 가장 단순하다 | PG 장애가 예매 스레드를 잡는다. 과제의 MSA 요구에 맞지 않는다 |

- 고른 이유: SA 7-1의 "나눈 이유" 열에 따르면 User는 회원 데이터를 공유하지 않으려고, Reservation은 좌석 불변식 때문에, Payment는 PG 격리 때문에 나눴다. 추첨석은 좌석 등급 `LOTTERY`로 미리 떼어 두므로 일반 선점과 같은 좌석을 두고 다투지 않는다. 그래서 lottery를 같은 서비스에 두어도 좌석 경합이 생기지 않는다.
- 감수한 점: queue·lottery는 "측정해서 기준을 넘으면 분리한다"는 조건부 결정이다. lottery의 조건은 응모·자동결제 중 다른 회차 선점 p95가 20% 넘게 늘어나는 것이다(ADR 0036, 측정 시나리오 L3). 아직 측정하지 않았다.

### 2. Reservation 안의 모듈 규칙과 의존 방향
SA 7-1의 규칙은 다음과 같다. reservation·lottery는 서로 직접 호출할 수 있다. queue는 인터페이스로만 부른다. notification은 Kafka로만 만난다. concert는 읽기만 직접 할 수 있다. ADR 0034가 여기에 `seathold → reservation, concert` 단방향 규칙을 더했다.

2026-11-10에 `import` 문을 검색해 확인한 실제 의존은 다음과 같다(`reservation-service/src/main/java/com/iseonjwa/reservation/`).

| 호출하는 모듈 → 호출받는 모듈 | 파일 수 | 비고 |
|---|---|---|
| seathold → reservation | 4 | ADR 0034가 허용한 방향 |
| lottery → seathold | 2 | `SeatHoldSystemHoldAdapter`가 `SystemSeatHoldService`를 부른다 |
| lottery → concert | 7 | 좌석 등급·회차 읽기 |
| reservation → lottery | 1 | `PaymentResultService`가 `LotteryPaymentResultHandler`를 부른다(SA의 reservation↔lottery 직접 호출 허용) |
| reservation → seathold | 1 | `ReservationService`가 `PaymentTokenIssuer`를 쓴다. ADR 0034 규칙의 예외이고, 인터페이스 주석에 위치를 정할 TODO가 있다 |
| 다른 모듈 → queue | 0 | 입장 토큰은 Gateway가 검증하므로 다른 모듈이 queue를 부를 일이 없다 |

- 역방향이 필요한 곳은 의존성 역전으로 푼다. 선점 만료(seathold)가 추첨 당첨 예매를 만나면 추첨 쪽에 물어봐야 한다. 이때 인터페이스 `LotteryHoldExpiryHook`은 seathold에 두고, 구현은 lottery가 한다(`seathold/application/LotteryHoldExpiryHook.java:6-10`). 그래서 코드 의존 방향은 계속 lottery → seathold 하나다.
- 추첨은 선점 규칙을 다시 구현하지 않는다. 자기 포트 `SystemHoldPort`를 정의하고, 어댑터가 선점 모듈의 서비스를 부른다(`lottery/infrastructure/seat/SeatHoldSystemHoldAdapter.java:11-26`).
- 감수한 점: ArchUnit 같은 아키텍처 테스트가 없다. 저장소에서 `archunit`·`ArchRule`을 검색해도 나오지 않는다. 규칙은 리뷰로 지키며, 실제로 `PaymentTokenIssuer` 예외가 남아 있다. ADR 0043은 "의존 규칙이 다시 어긋나면 ArchUnit으로 강제한다"를 다시 볼 조건으로 적었다.

### 3. Gateway 단일 진입과 접근 판정 (ADR 0006)
| 선택지 | 장점 | 단점 |
|---|---|---|
| Gateway와 서비스가 각자 경로 판정(이전 상태) | 서비스가 혼자서도 방어한다 | 두 곳의 규칙이 어긋날 수 있다 |
| 공유 상수·공통 테스트로 두 곳을 맞춤(AI 제안) | 어긋남을 테스트로 잡는다 | 판정 지점이 둘인 구조는 그대로다 |
| **Gateway 한 곳에서만 판정** | 규칙을 바꿀 때 `AccessPolicy`와 그 테스트만 고친다 | Gateway를 우회하면 헤더만 갖춰도 통과한다 |

코드에서 확인할 곳:
- `gateway/.../security/AccessPolicy.java:35-49`: INTERNAL → ADMIN → PUBLIC_POST → PUBLIC_GET → 나머지 USER 순서로 판정한다. 라우터와 같은 `PathPattern`으로 Gateway가 파싱한 경로를 비교한다. 그래서 인코딩이나 matrix parameter로 경로를 바꿔 우회할 수 없다.
- `AccessPolicy.java:14-17`, `GatewayAuthenticationFilter.java:53-55`: `/internal/**`은 외부 요청에 404를 낸다(ADR 0008 A1). "있지만 금지"(403)가 아니라 "없음"으로 응답해 내부 경로의 존재를 숨긴다.
- `GatewayAuthenticationFilter.java:102-113`: 클라이언트가 보낸 `X-User-Id`·`X-User-Role`은 항상 지운다. 검증한 JWT의 값만 다시 넣는다. 공개 경로에서도 지운다. 헤더 위조를 막는 핵심 줄이다.
- `GatewayAuthenticationFilter.java:89-92`: 블랙리스트(로그아웃한 토큰) 조회는 Redis 100ms 타임아웃과 서킷 브레이커로 감싼다. Redis를 확인할 수 없으면 통과시키지 않고 503을 낸다(fail-closed, ADR 0005). 자세한 내용은 리뷰 2에서 다룬다.
- `EntryTokenPolicy.java:17-19`: 입장 토큰도 Gateway가 검사한다. 대상은 좌석 조회와 선점 두 경로뿐이다. 추첨 응모는 대기열을 거치지 않으므로 목록에 없다.

### 4. 서비스는 Gateway를 얼마나 믿나 (ADR 0008, 0009)
| | A. 네트워크만 신뢰 (**채택**) | B. Gateway가 내부 토큰 서명 | C. 서비스가 사용자 JWT 직접 검증 (ADR 0009, 철회) |
|---|---|---|---|
| 직접 도달·헤더 위조(T1·T2) 방어 | 없음, 네트워크 격리에 의존 | 있음 | 있음 |
| Gateway가 침해되면 | 전부 위조 가능 | 전부 위조 가능 | RS256이면 위조 불가, HS256이면 위조 가능 |
| 요청당 비용 | 없음 | 서명과 검증 | 서비스마다 검증 |

- 고른 이유: SA 7-2·7-3이 이미 A였다. 비대칭 서명(RS256)은 MVP 이후로 미뤘다. C를 하더라도 HS256이면 Gateway가 같은 비밀키를 갖고 있어서 "Gateway를 전적으로 믿지 않는다"는 목표를 이루지 못한다(ADR 0009 "선택 근거의 범위").
- 보강한 것: A1은 내부 경로 404, A2는 `@RequireRole` 인터셉터, A4는 서비스 포트가 외부에 열리면 실패하는 `script/check-port-exposure.py`를 CI에 넣은 것이다. 전제(포트 비공개)를 자동으로 검사하게 해서 감수한 위험을 줄였다.
- 감수한 점: 내부망·SSRF·설정 실수로 서비스에 직접 닿으면 아무 사용자로 행세할 수 있다. Kafka 이벤트에도 신원이 없다(T5). Access 토큰은 비밀번호를 초기화해도 만료(30분)까지 유효하다(T6, A3 보류).

### 5. 공통 모듈과 계층 규칙 (ADR 0013, 0043)
- `common` 하나에 응답 형식·에러 코드·인증 도우미·이벤트 봉투·outbox/inbox를 둔다. SA 13장의 4개 모듈안은 지금 코드 양에 비해 관리 비용이 컸다.
- 위험: Gateway는 WebFlux다. `common`에 `spring-webmvc`가 런타임 의존으로 들어가면 Gateway가 서블릿으로 뜬다. 그래서 웹·JPA·Kafka는 `compileOnly`로 둔다. `GatewayClasspathTest`가 Gateway 클래스패스에 `DispatcherServlet`·Hibernate·`KafkaTemplate` 클래스 파일이 없는지 확인한다. 의존을 `api`로 바꾸면 이 테스트가 실패한다는 것도 변이 테스트로 확인했다(ADR 0013).
- Kafka가 없는 User 서비스는 메시징 설정을 `@Import`하지 않는다. 컴포넌트 스캔을 쓰지 않으므로 릴레이 빈이 잘못 생기지 않는다.
- ADR 0043: 들어오는 입구(HTTP·Kafka 소비자·스케줄러)는 presentation에, 나가는 구현(JPA·Redis·Kafka 생산자·PG 클라이언트)은 infrastructure에 둔다. 의존 방향은 presentation → application → domain ← infrastructure로 한 표에 설명된다. 이 결정은 강민이 회의에서 확정했다.

## 예상 질문
1. MSA라면서 대기열·추첨·좌석 선점·예매가 한 서비스에 다 있다. 이게 MSA인가?
2. 서비스는 `X-User-Id` 헤더를 그냥 믿는다. 누가 헤더를 위조해서 보내면 어떻게 되나?
3. Gateway가 죽거나 Redis가 죽으면 로그인한 사용자는 어떻게 되나?
4. 모듈 경계를 어떻게 지키나? 누가 실수로 반대 방향을 import하면 막히나?
5. 공통 모듈을 하나로 두면 모든 서비스가 결합되지 않나?

## 예상 질문 모범 답

### 1. MSA라면서 대기열·추첨·선점·예매가 한 서비스에 다 있다. 이게 MSA인가?
- 2026-11-10 내 답: 분리해도 장애가 제대로 격리되지 않아서 분리할 필요를 느끼지 못했다.
- 피드백: 방향은 맞다. 다만 이 답은 queue에만 맞는 근거다. 모듈마다 근거가 다르고, "언제 나눌지"가 빠졌다.
- 모범 답: "서비스는 데이터 소유와 장애 격리로 나눴습니다. Payment는 외부 PG의 지연이 예매 스레드로 번지지 않게 분리했습니다. 선점·예매는 '좌석은 한 번만 팔린다'를 DB 트랜잭션 하나로 지켜야 해서 같은 서비스에 뒀습니다. 대기열은 자기 데이터가 없고, 대기열이 멈추면 예매도 멈추므로 떼어 내도 격리되지 않습니다. 추첨은 예매와 좌석을 공유해 분리하면 분산 트랜잭션이 생깁니다. 대신 추첨석을 등급으로 미리 떼어 실시간 선점과 경합하지 않게 했습니다. 서비스 안에서는 모듈 의존 방향을 고정한 모듈러 모놀리스로 경계를 지킵니다. 추첨 중 다른 회차 선점 p95가 20% 넘게 늘면 분리한다는 기준도 정해 두었습니다."
- 꼬리 질문 대비: "그 20%는 측정했나?" → 아직이면 "L3 시나리오로 측정할 예정이고, 지금은 L2 자동결제 측정까지 했다"처럼 사실대로 답한다.

### 2. 서비스는 `X-User-Id`를 그냥 믿는다. 헤더를 위조해서 보내면 어떻게 되나?
- 2026-11-10 내 답: Gateway에서 1차로 검사하고, 각 서비스가 user 서비스를 호출해 다시 검증한다.
- 피드백: **사실과 다르다.** 서비스는 user 서비스를 호출하지 않는다. ADR 0008은 "user-service 재확인 호출"을 SA와 충돌한다는 이유로 범위에서 뺐다. 서비스가 JWT를 직접 검증하는 안(ADR 0009)도 철회했다. 발표에서 이렇게 답하면 코드로 바로 반박당한다.
- 모범 답: "두 경우로 나뉩니다. Gateway를 거친 요청이면 Gateway가 클라이언트가 보낸 `X-User-*`를 항상 지우고 검증한 JWT의 값만 다시 넣습니다(`GatewayAuthenticationFilter.java:102-113`). 그래서 위조해도 소용이 없습니다. Gateway를 우회해 서비스에 직접 닿으면 위조가 통합니다. 이건 알고 감수한 위험이고, 서비스 포트를 외부에 열지 않는 것이 전제입니다. 이 전제는 CI의 `check-port-exposure.py`가 검사합니다. 서비스가 JWT를 직접 검증하는 안도 검토했습니다. 하지만 HS256에서는 Gateway도 같은 키를 갖고 있어 목표를 이루지 못합니다. 그래서 RS256 전환과 함께 MVP 이후로 미뤘습니다."

### 3. Redis가 죽으면 로그인한 사용자는 어떻게 되나? 왜 그렇게 정했나?
- 2026-11-10 내 답: 전체 마비였나? 잘 모르겠다.
- 피드백: 절반은 맞다. 로그인이 필요한 API만 503이 된다. 공개 API(공연 조회 등)는 블랙리스트를 조회하지 않으므로 그대로 동작한다. "왜"가 핵심이다.
- 모범 답: "Gateway는 로그아웃한 토큰을 Redis 블랙리스트로 거릅니다. 조회는 100ms 타임아웃과 서킷 브레이커로 감쌌습니다. 실패하거나 서킷이 열리면 보호 API에 503을 냅니다(fail-closed, ADR 0005). 공개 API는 영향이 없습니다. 장애 중에 통과시키는 fail-open도 검토했습니다. 하지만 그러면 로그아웃했거나 탈취된 토큰이 남은 유효 시간 동안 통합니다. 결제가 있는 서비스라 가용성보다 인증의 정확성을 택했습니다. 대가는 Redis 장애 동안 로그인 사용자 전체가 503을 받는다는 것입니다. 팀 회의에서 이 대가를 알고 채택했습니다. 기동 직후 Redis 연결이 늦어 503이 나던 문제는 PING 예열로 고쳤습니다."
- 꼬리 질문 대비: "Redis 단일 장애점 아닌가?" → 그렇다. 대기열도 같은 Redis를 쓴다. 복제·Sentinel은 범위 밖이다(리뷰 4에서 확인).

### 4. 모듈 경계는 어떻게 지키나? 누가 반대 방향으로 import하면 막히나?
- 2026-11-10 내 답: 정책상 경고하기로 되어 있다고 알고 있으나 확실하지 않다. 모듈 경계는 결합도와 의존성으로 판단할 수 있다.
- 피드백: **자동 경고는 없다.** ArchUnit 같은 아키텍처 테스트가 없어서 반대 방향 import는 컴파일도 테스트도 통과한다. 리뷰로만 지킨다. 실제로 `reservation → seathold`(`PaymentTokenIssuer`) 예외가 1건 있다. "결합도와 의존성으로 판단"은 일반론이라 질문에 대한 답이 되지 않는다.
- 모범 답: "규칙은 SA 7-1과 ADR 0034에 있습니다. 예를 들어 seathold는 reservation을 부를 수 있지만 그 반대는 안 됩니다. 반대 방향 호출이 필요한 곳은 인터페이스를 호출받는 쪽에 둡니다. 선점 만료가 추첨 예매를 만나면 seathold에 둔 `LotteryHoldExpiryHook`을 lottery가 구현하는 식입니다. 지금은 리뷰로 지키고 있고 자동 검사는 없습니다. 최근 import를 전수 검사했더니 예외가 1건 있었고, 그 코드에 TODO가 남아 있습니다. 다음 단계로 ArchUnit을 넣으면 됩니다."
- 장점: 모르는 것을 "확실하지 않다"고 밝힌 점은 좋다. 발표에서도 추측을 사실처럼 말하지 않는 편이 낫다.

### 5. 공통 모듈을 하나로 두면 모든 서비스가 결합되지 않나?
- 2026-11-10 내 답: 서비스끼리 의존하는 것이 아니라 공통 모듈만 가져다 쓰므로 그렇지 않다.
- 피드백: 반만 맞다. 서비스끼리 직접 의존하지 않는 것은 맞다. 하지만 공통 모듈을 거친 결합은 있다. `ApiResponse`나 이벤트 봉투를 바꾸면 모든 서비스가 영향을 받는다. "결합이 없다"고 하지 말고, 어떤 결합을 왜 받아들였는지와 무엇으로 막았는지를 말해야 한다.
- 모범 답: "공통 모듈을 통한 결합은 있습니다. 응답 형식·에러 코드·이벤트 봉투처럼 원래 모든 서비스가 같아야 하는 계약만 넣었습니다. 위험은 Gateway(WebFlux)에 MVC가 섞여 서블릿으로 뜨는 것이었습니다. 그래서 웹·JPA·Kafka는 `compileOnly`로 두고, 각 서비스는 필요한 설정만 `@Import`합니다. `GatewayClasspathTest`가 Gateway 클래스패스에 MVC·Hibernate·Kafka 클래스가 없는지 확인합니다. 모듈을 4개로 쪼개는 안도 있었지만, 코드 양에 비해 관리 비용이 커서 하나로 두었습니다."

### 다시 볼 것
- 2번(서비스가 user를 다시 호출한다는 오해)과 4번(자동 경고가 있다는 오해)은 사실 오류다. 발표 전에 다시 답해 본다.

## 참고
- `docs/core/SA MASTER.md` 7-1·7-2·7-3
- ADR 0006, 0008, 0009, 0013, 0034, 0043
- 의존 확인 명령(2026-11-10): `grep -rl "import com.iseonjwa.reservation.<모듈>\." reservation-service/src/main/java`
