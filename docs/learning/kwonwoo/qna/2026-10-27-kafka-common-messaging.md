---
title: Kafka 공통 코드(Outbox·중복 처리·DLQ)를 common에 둘까, 분리할까, 서비스마다 짤까
date: 2026-10-27
updated: 2026-10-27
asker: kwonwoo
level: 입문
tags: [kafka, outbox, idempotency, dlq, multi-module, msa]
---

# Kafka 공통 코드(Outbox·중복 처리·DLQ)를 common에 둘까, 분리할까, 서비스마다 짤까

## 질문
Kafka를 아직 잘 모른다. SA 13장의 `common-messaging`을 현재 설계(`common` 하나)와 비교했을 때, 프로젝트 규모상 어느 쪽이 이점이 큰가. 신입 백엔드 개발자가 이해할 수준으로 트레이드오프를 정리해 달라.

## 짧은 답
- Kafka는 서비스끼리 "일어난 일"을 메시지로 남기고 받는 쪽이 자기 속도로 읽어 가는 우편함이다. 이 프로젝트에서는 Reservation과 Payment만 쓴다(SA 4-2, 10-1).
- Kafka를 쓰면 Outbox, 중복 처리(processed_event), 재시도·DLQ가 필요하다. 이 코드는 Reservation과 Payment가 같은 규칙으로 써야 한다.
- 이 코드를 `common` 하나에 넣으면, `com.highquality`를 스캔하는 User 서비스가 Kafka 없이 릴레이 빈을 만들다가 기동에 실패한다. 서비스마다 따로 짜면 두 벌이 서로 어긋나기 쉽다. 그래서 `common-messaging`으로 분리하고 Reservation·Payment만 의존하게 하는 쪽이 낫다.
- 시점은 Outbox를 만들 때다. Gateway·인증 단계에서는 `common` 하나로 충분하다.

## 자세히

### Kafka가 필요한 이유
직접 호출(HTTP)은 받는 쪽이 죽어 있으면 실패하고, 보내는 쪽이 응답을 기다린다. Kafka는 메시지를 남겨 두므로 받는 쪽이 살아난 뒤 읽어 가면 된다. 이 프로젝트에서 쓰는 곳은 결제 결과(Payment → Reservation)와 환불 요청(Reservation → Payment)이고, 이후 접수형 선점과 추첨에도 쓴다. Gateway와 User는 Kafka를 쓰지 않는다.

### Kafka를 쓰면 생기는 세 가지 문제
| 문제 | 상황 | 해결책 |
|---|---|---|
| 저장은 됐는데 메시지가 안 나감 | DB 커밋 직후 서버가 죽음 | Outbox: 메시지를 업무 데이터와 같은 트랜잭션으로 `outbox` 테이블에 저장하고, 릴레이가 Kafka로 보냄 |
| 같은 메시지가 두 번 옴 | Kafka는 최소 한 번 전달 | processed_event: 처리한 eventId를 기록하고 이미 처리한 것은 건너뜀 |
| 계속 실패하는 메시지 | 깨진 메시지가 뒤를 막음 | 재시도 3회 후 DLQ(실패 보관함) 토픽으로 옮김 |

### 세 가지 배치 방식
| | common 하나에 넣기 | common-messaging 분리 | 서비스마다 구현 |
|---|---|---|---|
| 코드 중복 | 없음 | 없음 | 2벌 |
| Kafka 안 쓰는 서비스 영향 | 패키지 스캔 때문에 릴레이 빈과 outbox 엔티티가 잡혀 기동 실패·불필요 테이블 위험 | 없음 | 없음 |
| 규칙 일관성 | 같음 | 같음 | 어긋나기 쉬움 |
| 관리 비용 | 모듈 1개 | 모듈 1개 추가 | 추가 없음 |

`@ConditionalOnClass`와 엔티티 스캔 범위 지정으로 첫째 방식을 살릴 수도 있다. 하지만 지켜야 할 규칙이 늘고 하나만 빠져도 서비스가 깨진다. 응답 DTO처럼 가벼운 공통 코드와 달리 Kafka·JPA 런타임을 끌고 오는 코드는 무겁다. 이 무게 차이가 분리 기준이다.

### 이 프로젝트에서는
지금은 `common` 하나(응답 형식, MVC 예외 처리기, BaseEntity)로 간다. 결제 담당이 Outbox를 만들 때 `common-messaging`을 새로 만들고 Reservation·Payment만 의존하게 한다. SA 13장과 다른 점은 `common-response`·`common-web`을 `common`에 합친 것 하나다.

## 면접 포인트
- Outbox를 쓰는 이유: DB 저장과 메시지 발행을 한 트랜잭션으로 묶을 수 없으므로, 메시지를 같은 DB 트랜잭션에 저장하고 나중에 발행해 둘 중 하나만 성공하는 상황을 없앤다.
- 공통 모듈 분리 기준: 코드를 쓰는 서비스 수가 아니라 끌고 오는 의존성의 무게로 나눈다.
