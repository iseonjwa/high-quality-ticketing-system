---
title: 서비스 패키지는 어떤 계층 구조이고 계층·모듈 사이 의존은 원칙과 실제가 어디서 다른가
date: 2026-11-12
updated: 2026-11-12
asker: kangmin
level: 중급
tags: [layered-architecture, package-structure, dependency-rule, port-adapter, module-boundary]
---

# 서비스 패키지는 어떤 계층 구조이고 계층·모듈 사이 의존은 원칙과 실제가 어디서 다른가

## 질문
지금 서비스 패키지의 도메인·계층 구조와 의존 방향을 알려 달라. 원칙상으로는 그런데 예외적으로 그렇지 않은 곳을 정리해 달라. 각 계층이 알아도 되는 계층과 알면 안 되는 계층도 알려 달라.

## 짧은 답
- 구조는 **서비스(Gradle 모듈) → 업무 모듈(패키지) → 4계층**이다. 예: `com.iseonjwa.reservation.seathold.application`. user-service만 업무 모듈 없이 4계층이 바로 온다.
- 원칙(SA 13장, ADR 0043): presentation → application → domain, infrastructure → domain(인터페이스 구현). domain은 JPA 애너테이션 말고는 아무것도 모른다.
- 같은 모듈 안에서 **아래 계층이 위 계층을 아는 역방향 의존(domain→application, application→infrastructure 등)은 측정 결과 0건**이다.
- 예외는 계층 축보다 **모듈 축**과 **계층 밖 패키지(global, observability, job)**에 몰려 있다. 대표는 ADR 0034가 금지한 `reservation → seathold` import 1건과 결제 서비스의 모듈 간 양방향 의존이다.

## 자세히

### 1. 구조

| 서비스 | 업무 모듈(4계층을 각각 가짐) | 계층 밖 패키지 |
|---|---|---|
| reservation-service | concert, queue, seathold, reservation, lottery | global(설정, `global.vo.Money`, observability), infrastructure/messaging(Outbox), lottery/observability |
| payment-service | payment, refund, billing, reconciliation, ops, pg(application·infrastructure만) | global, observability, infrastructure/{job, housekeeping, recovery, messaging}, warmup |
| user-service | (모듈 없음) presentation·application·domain·infrastructure 바로 아래 | global |
| common | auth, identity, response, web, persistence, event, messaging | 서비스 코드를 import하지 않는다 |

gateway·eureka·config-server·mock-pg·assistant-service는 4계층을 쓰지 않는 기능별 패키지다.

### 2. 계층별로 알아도 되는 것과 알면 안 되는 것 (같은 모듈 안)

| 계층 | 알아도 된다 | 알면 안 된다 | 근거 |
|---|---|---|---|
| presentation (컨트롤러, Kafka 소비자, 스케줄러) | application, common(response·web) | infrastructure, 다른 서비스 | SA 13장 표, ADR 0043 |
| application (유스케이스, 트랜잭션) | domain, 자기 포트 인터페이스, 다른 모듈의 application·domain | presentation, infrastructure 구현 클래스 | SA 13장 표, ADR 0023 |
| domain (엔티티, 규칙, 저장소 인터페이스) | 자기 모듈 domain, JPA 애너테이션 | application, presentation, infrastructure, Kafka·Redis·웹 | SA 13장 표 |
| infrastructure (JPA·Redis·Kafka 생산자·PG 클라이언트) | domain, application이 정한 포트 인터페이스 | presentation, application 서비스 구현 | ADR 0043 "domain·application이 정한 인터페이스를 구현" |

- infrastructure → application은 SA 표에는 없지만 ADR 0023·0043이 허용한 형태다. 포트(`PgClient`, `BillingKeyEncryptor`, `PaymentTokenIssuer`)를 application에 두고 infrastructure가 구현하기 때문이다(의존성 역전).
- 모듈 사이: `seathold → reservation, concert`만 허용하고 반대는 금지(ADR 0034). 서비스 모듈끼리는 직접 의존 금지(SA 13장).

### 3. 측정한 실제 의존 (dev `0b125e9b`, main 소스 import 집계)

같은 모듈 안 허용 방향:
- presentation → application, application → domain, infrastructure → domain: 다수.
- infrastructure → application(포트 구현): `pg` 43, `reconciliation` 7, `payment` 3, `lottery` 3, `billing`·`ops`·`seathold` 각 1.
- domain의 프레임워크 import: `jakarta.persistence` 166, `org.hibernate` 17(JPA 범위), `org.springframework.data.domain.Persistable` 2.
- 같은 모듈 안 역방향(domain→상위, application→infrastructure·presentation, infrastructure→presentation): **0건**.

### 4. 원칙과 다른 곳

#### A. 계층 규칙의 예외

| # | 무엇 | 위치 | 성격 |
|---|---|---|---|
| A1 | presentation이 domain을 직접 안다(엔티티를 받는 응답 DTO `from(entity)`, 요청 DTO가 `SeatGrade`·`Lottery.LotterySettings` 사용) | concert 11, lottery 9, reservation 9, queue 1, payment 1 | SA 표는 presentation → application만 적었다. "엔티티를 응답으로 바로 내보내지 않는다"는 지켰지만 DTO 변환이 presentation에서 엔티티를 읽는다. 관행상 흔히 허용하는 절충 |
| A2 | domain이 Spring Data `Persistable`을 구현 | `IdempotencyRecord`, `SeatHoldRequest` | "JPA 애너테이션만 예외"보다 넓다. ID를 직접 정하는 엔티티의 `merge` 대신 `persist`를 쓰게 하려는 것으로 보인다(확인할 질문) |
| A3 | application·presentation이 지표 패키지를 직접 부른다 | `payment.application` → `observability.PaymentMetrics` 9, `lottery.application`·`lottery.presentation.scheduling` → `lottery.observability` 5, `refund.application` → `PaymentAlertLog` 5 | Micrometer가 응용 계층에 들어온다. 4계층 표에 지표 자리가 없어 생긴 회색지대 |
| A4 | infrastructure가 다른 모듈의 application 서비스를 직접 쥔다 | `payment.infrastructure.recovery.RecoveryConfiguration` → `PaymentRecheckService`, `RefundWorkerService` | 재확인·환불 작업 등록 설정. ADR 0043대로라면 스케줄 입구는 presentation/scheduling 자리다 |
| A5 | presentation이 infrastructure 설정을 안다 | `refund.presentation.messaging.PaymentCommandKafkaConfiguration` → `pg.infrastructure.PgProtectionProperties`, `reconciliation.presentation.scheduling` → `infrastructure.housekeeping`·`job` | 입구 설정이 인프라 설정값·작업 틀을 공유 |
| A6 | infrastructure가 global 설정을 안다 | `payment.infrastructure.token.JwtPaymentTokenVerifier` → `global.PaymentTokenKey` | 키 설정 보관 위치 문제. 영향 작음 |

`global.*Configuration`이 infrastructure 구현을 직접 import하는 것(`BillingKeyConfiguration` → `AesGcmBillingKeyEncryptor`, user `UserConfiguration` → `AuthService`)은 빈 조립 지점(composition root)이라 예외로 보지 않는다.

#### B. 모듈 경계의 예외

| # | 무엇 | 위치 | 성격 |
|---|---|---|---|
| B1 | **ADR 0034 위반**: reservation이 seathold를 import | `reservation.application.ReservationService` → `seathold.application.PaymentTokenIssuer` | 예매 상세 조회에서 결제 토큰을 다시 발급. 결정은 "reservation은 seathold를 절대 import하지 않는다". 포트를 reservation 쪽이나 공용 위치로 옮기거나 ADR을 고쳐야 한다 |
| B2 | reservation → lottery → seathold → reservation 순환 | `PaymentResultService` → `lottery.application.LotteryPaymentResultHandler`, `lottery.infrastructure.seat.*Adapter` → `seathold.application` | lottery가 포트(`SystemHoldPort`)와 어댑터로 seathold를 감싸 직접 결합은 줄였다. 다만 패키지 그래프는 B1까지 합쳐 순환이다 |
| B3 | seathold.domain이 concert.domain 엔티티를 저장소 시그니처에 쓴다 | `SeatHoldInventoryRepository` → `Schedule`, `ScheduleSeat` | ADR 0034가 허용한 방향(seathold → concert)이다. domain끼리 모듈을 넘는 사례로만 알아 둔다 |
| B4 | `global.vo.Money`를 모든 모듈·계층이 쓴다 | concert·reservation·seathold의 domain·application·infrastructure·presentation | 공유 값 객체(shared kernel). 의도한 공유 |
| B5 | payment ↔ refund 양방향 | `payment.domain.PaymentStatusHistory` → `refund.domain.RefundStatus`, `refund.application` → `payment.domain` 20·`payment.application` 2 | domain 수준 순환. 결제 서비스에는 ADR 0034 같은 모듈 방향 결정이 없다 |
| B6 | payment ↔ billing 양방향 | `billing.application` → `payment.application.PaymentErrorCode`, `payment.application` → `billing.application`·`billing.domain` | 오류 코드 공유 때문에 생긴 역방향 |
| B7 | `pg.application`이 `payment.domain`을 안다 | `PgClient`(`DeclineReason`), `PgCheckoutProfile`(`PgProvider`) | PG 포트가 결제 도메인 타입을 쓴다. pg가 독립 모듈이 아니라 payment의 하위 포트라는 뜻 |
| B8 | ops가 payment·refund·pg의 application·domain을 넓게 쓴다 | `ops.application` → `pg.application` 32, `refund` 16, `payment` 6 | 운영 명령 모듈의 성격상 예상한 넓은 의존 |

## 이 프로젝트에서는
- 규칙 원문: `docs/core/SA MASTER.md` 13장, `docs/decisions/0034-seathold-unidirectional-module-boundary.md`, `docs/decisions/0043-inbound-adapters-in-presentation.md`, `docs/decisions/0023-payment-pg-client-port.md`.
- 계층 규칙을 자동으로 검사하는 테스트(ArchUnit 등)는 저장소에 없다. 그래서 B1 같은 위반이 리뷰에서 빠질 수 있다.
- 측정은 각 서비스 `src/main/java`의 `import com.iseonjwa.<서비스>.*`를 패키지 경로로 (모듈, 계층)에 매핑해 셌다. 와일드카드 import와 같은 패키지 안 참조(import 없음)는 세지 않았다.

## 면접 포인트
- "계층형에서 infrastructure가 application을 아는 건 위반 아닌가?" → 포트를 application에 두고 infrastructure가 구현하는 의존성 역전이다. 아는 대상이 인터페이스뿐이라 application은 구현을 모른다.
- "규칙을 어떻게 지키나?" → 현재는 리뷰뿐이고 실제로 ADR 위반 1건이 들어와 있었다. ArchUnit으로 계층·모듈 방향을 테스트로 고정하는 것이 다음 단계다.
- "presentation이 엔티티를 아는 건 괜찮나?" → 응답에 엔티티를 그대로 내보내지만 않으면 흔히 허용한다. 대가는 엔티티 변경이 DTO까지 번진다는 점이다.

## 참고
- 측정 스크립트 실행 결과(2026-11-12, dev `0b125e9b`).
