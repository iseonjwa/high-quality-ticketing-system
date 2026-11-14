---
title: 저장소를 왜 JpaRepository 하나로 안 만들고 인터페이스·구현으로 나눴나
date: 2026-10-31
asker: hyos1
level: 기초
tags: [jpa, spring-data, repository, layered-architecture, dip]
---

# 저장소를 왜 JpaRepository 하나로 안 만들고 인터페이스·구현으로 나눴나

## 질문
보통 `public interface ConcertRepository extends JpaRepository<Concert, Long>` 하나 만들고 메서드 이름으로 쿼리를 만들지 않나? 왜 파일을 세 개로 나눴나?

## 답
### 흔한 방식 (파일 1개)
```java
public interface ConcertRepository extends JpaRepository<Concert, Long> {
    List<Concert> findByTitleContaining(String keyword);   // 메서드 이름으로 쿼리 생성
}
```
제네릭 순서는 `<엔티티, ID 타입>`이다. `JpaRepository<Long, Concert>`가 아니다.

### 이 저장소의 방식 (파일 3개)
| 파일 | 위치 | 역할 |
|---|---|---|
| `ConcertRepository` | `domain/repository` | 서비스가 쓰는 인터페이스. Spring Data를 모른다 |
| `SpringDataConcertRepository extends JpaRepository<Concert, Long>` | `infrastructure/persistence` | 실제 Spring Data. 메서드 이름 쿼리는 여기에 쓴다 |
| `JpaConcertRepository implements ConcertRepository` | `infrastructure/persistence` | 위 둘을 잇는다(받은 호출을 Spring Data에 넘긴다) |

### 왜 이렇게 했나
1. **팀 규칙**: `concert/domain/package-info.java`에 "도메인 계층은 다른 계층과 외부 기술에 의존하지 않는다. JPA 애너테이션만 예외"라고 적혀 있다. `JpaRepository`는 Spring Data라는 외부 기술이라, 도메인 인터페이스가 이를 상속하면 규칙에 어긋난다.
2. **팀 전체가 이 모양이다**: user-service(`SpringDataUserRepository`), payment-service(`SpringDataPaymentRepository`), 권우 님 reservation·seathold(`SpringDataReservationRepository` 등) 모두 같은 구조다.
3. **얻는 것(DIP, 의존성 역전)**: 서비스는 "공연을 저장한다, id로 찾는다"만 안다. `JpaRepository`가 주는 `deleteAll()`, `findAll()` 같은 수십 개 메서드가 서비스에 노출되지 않는다. 나중에 Redis 캐시나 다른 저장 방식을 붙여도 서비스 코드는 바뀌지 않는다. 테스트에서 가짜 구현을 끼우기도 쉽다.

비용은 파일이 늘고, 메서드 하나를 추가할 때 세 곳을 고쳐야 한다는 점이다.

### 메서드 이름 쿼리는 그대로 쓴다
쓰는 위치만 `SpringData...` 쪽이다.
```java
// infrastructure/persistence/SpringDataConcertRepository
interface SpringDataConcertRepository extends JpaRepository<Concert, Long> {
    boolean existsByTitle(String title);           // Spring Data가 쿼리 생성
}
// domain/repository/ConcertRepository
boolean existsByTitle(String title);
// infrastructure/persistence/JpaConcertRepository
public boolean existsByTitle(String title) { return springDataConcertRepository.existsByTitle(title); }
```

### 후속 질문(11/01): "기술이 다른 자식이 생길 걸 대비해 부모(설계도)를 나눴다는 말인가"
맞다. 다만 두 가지를 보탠다.
- 부모(`ConcertRepository`)의 자식은 `JpaConcertRepository`다. `SpringDataConcertRepository`는 자식이 아니라 자식이 안에서 쓰는 도구다.
  ```
  ConcertAdminService → ConcertRepository(부모, 설계도)
                              ↑ implements
                        JpaConcertRepository(자식) → SpringDataConcertRepository(Spring Data 도구)
  ```
- 핵심은 "언젠가 바꿀 대비"보다 "누가 누구를 아는가"다. 서비스는 부모만 알고 자식이 무슨 기술을 쓰는지 모른다. 실제로 저장 기술을 바꾸는 일은 드물고, 매일 얻는 이득은 서비스에 필요한 메서드만 보인다는 점과 테스트에서 가짜 자식을 끼우기 쉽다는 점이다.

## 핵심 정리
- 흔한 방식도 틀리지 않았다. 이 저장소는 도메인이 Spring Data에 의존하지 않게 인터페이스(domain)와 구현(infrastructure)을 나누는 것이 팀 공통 구조다.
- 메서드 이름 쿼리는 `SpringData...Repository`에 선언하고, 도메인 인터페이스에서 필요한 것만 열어 준다.

## 면접 포인트
- "Repository를 왜 도메인 인터페이스와 구현으로 나누나": DIP. 도메인·응용 계층이 영속 기술(Spring Data JPA)에 의존하지 않게 하고, 필요한 연산만 노출한다. 대가는 위임 코드가 늘어나는 것.

## 참고
- `reservation-service/.../concert/domain/package-info.java`
- `payment-service/.../payment/infrastructure/persistence/JpaPaymentRepository.java`
