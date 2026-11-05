---
title: IntelliJ의 @Transactional self-invocation 경고는 무슨 뜻인가
date: 2026-11-05
updated: 2026-11-05
asker: GGangMY
level: 중급
tags: [spring, transaction, aop, proxy]
---

# IntelliJ의 @Transactional self-invocation 경고는 무슨 뜻인가

## 질문
`LotteryEntryService.rejectReason`에서 `findLottery`를 부르는 줄에 뜬 경고를 해석해 달라. 경고 문구는 같은 객체 안에서 메서드를 부르면(self-invocation) 런타임에 실제 트랜잭션으로 이어지지 않는다는 내용이다.

## 짧은 답
같은 클래스 안에서 `findLottery`를 부르면 그 메서드의 `@Transactional(readOnly = true)`는 적용되지 않는다는 경고다. 스프링 트랜잭션은 빈을 감싼 프록시가 호출을 받을 때 시작되는데, 내부 호출은 프록시를 거치지 않기 때문이다. 이 호출은 `enter`의 쓰기 트랜잭션 안에서 실행되므로 지금 동작에 문제는 없다. 다만 애노테이션이 효과 있는 것처럼 읽히므로 정리하는 편이 낫다.

## 자세히

### 호출 경로별 동작

| 호출 | 프록시 경유 | `findLottery`의 애노테이션 |
|---|---|---|
| `LotteryController` → `lotteryEntryService.findLottery` | 예 | 적용된다. 읽기 전용 트랜잭션이 열린다 |
| `enter` → `rejectReason` → `findLottery` | 아니요 | 무시된다. `enter`의 트랜잭션을 그대로 쓴다 |

### 경고가 위험해지는 경우
- 트랜잭션이 없는 메서드에서 내부 호출한 쪽에만 `@Transactional`이 있으면 트랜잭션이 아예 열리지 않는다.
- 내부 호출로 `REQUIRES_NEW`를 기대하면 새 트랜잭션이 열리지 않아 함께 커밋되거나 롤백된다.

이 코드는 어느 쪽도 아니다. 호출자인 `enter`에 이미 `@Transactional`이 있다.

### 프록시를 거쳐도 결과가 같은 이유
전파 속성 기본값 `REQUIRED`는 진행 중인 트랜잭션에 참여한다. 그래서 프록시를 거치더라도 새 트랜잭션이 열리지 않고 `enter`의 쓰기 트랜잭션에 합류한다. `readOnly = true`도 참여 시점에는 바깥 트랜잭션을 읽기 전용으로 바꾸지 않는다.

### 해결 선택지

| 선택지 | 장점 | 감수할 점 |
|---|---|---|
| `rejectReason`에서 저장소를 직접 조회 | 리팩토링 전 코드로 돌아가 변경이 가장 작다 | 조회와 예외 변환 두 줄이 중복된다 |
| 애노테이션 없는 private 메서드로 조회 추출 | 중복이 사라진다 | `findLottery`가 위임만 하는 메서드가 된다 |
| 경고를 둔다 | 코드 변경이 없다 | 효과 없는 애노테이션을 호출하는 것처럼 읽힌다 |
| 자기 자신을 주입하거나 별도 빈으로 분리 | 프록시를 거치게 된다 | 여기서는 트랜잭션을 새로 적용할 필요가 없어 과하다 |

## 이 프로젝트에서는
- 대상은 `reservation-service/src/main/java/com/iseonjwa/reservation/lottery/application/LotteryEntryService.java`의 `rejectReason`이다.
- ISE-184 리팩토링에서 `rejectReason`의 저장소 직접 조회를 `findLottery` 호출로 바꾸면서 이 경고가 생겼다. 두 줄 중복을 감수하고 저장소 직접 조회로 되돌렸다.
- `reservation-service`에는 `AdviceMode`, AspectJ 설정이 없어 기본 프록시 방식으로 판단했다.

## 면접 포인트
- `@Transactional`이 내부 호출에서 동작하지 않는 이유: 트랜잭션 처리가 프록시에 있고, 내부 호출은 `this`로 대상 객체의 메서드를 직접 부르기 때문이다.
- 해결 방법: 메서드를 별도 빈으로 분리하는 것이 일반적이다. 다만 이미 호출자에 트랜잭션이 있고 전파가 `REQUIRED`라면 내부 호출에 애노테이션이 필요 없으므로 제거하거나 호출 구조를 정리하면 된다.

## 참고
- [Spring Framework 문서: Using @Transactional](https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative/annotations.html)
- 코드 읽기로 확인했다. 트랜잭션 로그를 켜서 실행해 보지는 않았다.
