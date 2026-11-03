---
title: 입구 어댑터를 presentation에 두는 4계층 패키지 세팅
date: 2026-11-03
tags: [계층형 아키텍처, 패키지 구조, 어댑터]
---

# 입구 어댑터를 presentation에 두는 4계층 패키지 세팅

## 질문
입구 어댑터(Kafka 소비자·스케줄러) 위치 안건에서, 4계층 구조는 어떻게 정의되고 B안(presentation 이동)을 적용하면 패키지를 어떻게 세팅하는가?

## 답
4계층은 "누가 부르는가"로 나눈다. 밖에서 들어오는 입구는 모두 presentation, 밖으로 나가는 구현은 모두 infrastructure다.

| 계층 | 담는 것 | 의존 |
| --- | --- | --- |
| presentation | 입구: 컨트롤러(HTTP), Kafka 소비자, 스케줄러. 요청·메시지 DTO, 입력 검증 | → application |
| application | 유스케이스, 트랜잭션 경계, 멱등 처리 | → domain |
| domain | 엔티티와 규칙, 저장소·외부 연동 인터페이스 | 없음(JPA 애너테이션만 예외) |
| infrastructure | 출구: JPA 구현, Redis, Kafka 생산자·Outbox, PG 클라이언트 | → domain 인터페이스 구현 |

기능 패키지 하나의 모양은 다음과 같다.

```
lottery/
├─ presentation/
│  ├─ LotteryController
│  ├─ dto/request, dto/response
│  └─ scheduling/      LotteryDrawScheduler 등 4개
├─ application/
├─ domain/
└─ infrastructure/     JPA 구현, 외부 호출 클라이언트
```

소비자가 있는 모듈은 `presentation/messaging/`을 같은 수준에 둔다.

## 현재 위치와 이동 대상(2026-11-03 코드 기준)

| 클래스 | 현재 | B안 |
| --- | --- | --- |
| `SeatHoldExpiryScheduler` | `seathold/infrastructure/scheduling` | `seathold/presentation/scheduling` |
| `Lottery*Scheduler` 4개 | `lottery/infrastructure/scheduler` | `lottery/presentation/scheduling` |
| `PaymentCommandListener`, `PaymentCommandDlqListener` | `refund/infrastructure/messaging` | `refund/presentation/messaging` |
| `RecoveryScheduler` | `payment/infrastructure/recovery` | `payment/<기능>/presentation/scheduling`(기능 패키지는 결제 담당이 정함) |

- 폴더 이름이 `scheduling`과 `scheduler`로 섞여 있다. 옮길 때 `scheduling`(`messaging`과 같은 동명사형)으로 맞춘다.
- `RecoveryScheduler`는 기능 패키지 밖(`payment/infrastructure`)에 있어 다른 둘과 모양이 다르다. 결제 담당 판단이 필요하다.
- 테스트 3개(`LotteryAssignmentSchedulerTest`, `PaymentCommandListener*Test`, `RecoverySchedulerTest`)도 같은 경로로 옮긴다.
- 기본 패키지(`com.iseonjwa.<서비스>`) 아래 이동이라 컴포넌트 스캔 설정은 바뀌지 않는다.
- 제외: `OutboxRelayScheduler`(밖으로 내보내는 출구, common), `LocalMessagingProbe`(local 진단).

## 감수한 점
- presentation이 "화면"이라는 이름 때문에 소비자가 들어 있는 것이 처음엔 어색하다. SA 표에 "입구 전부"라고 적어 푼다.
- 의존 규칙은 문서로만 지킨다. 다시 어긋나면 ArchUnit 같은 아키텍처 테스트로 강제하는 것을 검토한다(현재 저장소에는 없다).

## 관련
- [인바운드·아웃바운드 어댑터](2026-11-03-inbound-outbound-adapter.md)
- [presentation 이동은 헥사고날이 아니다](2026-11-03-presentation-move-is-not-hexagonal.md)
- [4계층 아키텍처](2026-10-27-four-layer-architecture.md)
