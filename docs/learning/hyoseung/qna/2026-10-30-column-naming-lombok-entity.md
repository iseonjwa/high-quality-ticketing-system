---
title: createdAt이 created_at으로 바뀌는 건 누가 하나, 엔티티에 Lombok을 써도 되나
date: 2026-10-30
asker: hyos1
level: 기초
tags: [jpa, hibernate, naming-strategy, lombok, entity]
---

# createdAt이 created_at으로 바뀌는 건 누가 하나, 엔티티에 Lombok을 써도 되나

## 질문
`@Column(name = "created_at")`을 안 써도 `createdAt`이 `created_at`으로 바뀐다는데 PostgreSQL도 그런가? 그러면 빼고, getter는 `@Getter`, 기본 생성자는 `@NoArgsConstructor(access = AccessLevel.PROTECTED)`로 바꿔도 되지 않나? AI가 어노테이션을 안 쓴 이유는 스프링 의존성을 줄이려고인가?

## 답
### 이름 변환은 DB가 아니라 Spring Boot가 한다
PostgreSQL은 이름을 바꿔 주지 않는다. Spring Boot가 Hibernate에 `CamelCaseToUnderscoresNamingStrategy`를 기본으로 설정해서, 엔티티 필드 이름을 SQL로 만들 때 바꾼다.

| 자바 | SQL |
|---|---|
| 필드 `startsAt` | 컬럼 `starts_at` |
| 필드 `maxPerUser` | 컬럼 `max_per_user` |
| 연관 필드 `concert` (`@ManyToOne`) | 외래 키 `concert_id` (필드 이름 + `_` + 상대 PK) |
| 클래스 `VenueSeat` | 테이블 `venue_seat` (단수) |

그래서 컬럼의 `name`은 빼도 된다. 테이블은 우리가 복수형(`venue_seats`)이라 `@Table(name = ...)`이 필요하다. DB와 DB 회사가 바뀌어도 같은 규칙이다(MySQL, PostgreSQL 모두).

확인 방법: `ddl-auto: validate`라서 이름이 틀리면 앱이 뜨지 않는다. name을 지운 뒤 reservation-service 테스트 16개가 모두 통과했으니 변환 결과가 실제 테이블과 맞다.

`nullable = false`, `length = 200`은 남겼다. `validate`는 이것까지 검사하지 않지만, 코드만 봐도 DB 제약이 보이게 하는 문서 역할을 한다.

### Lombok은 쓸 수 있다. AI가 안 쓴 이유
스프링 의존성 때문이 아니다(Lombok은 스프링이 아니라 컴파일할 때 코드를 만들어 주는 도구다). 팀 결정(10/27 D13)으로 모든 모듈에서 쓸 수 있고 루트 `build.gradle`에 이미 있다. AI는 `BaseEntity`와 결제 서비스가 손으로 쓴 getter라 그 모양을 따라 했다. `docs/guides/code-style.md` 8절은 "기존 코드는 Lombok으로 일부러 바꾸지 않는다"이므로, 바꾼 범위는 내가 만든 공연 엔티티 5개다. 공통 모듈 `BaseEntity`와 다른 사람 코드는 그대로 둔다.

| 손으로 쓴 것 | Lombok |
|---|---|
| `protected Concert() {}` | `@NoArgsConstructor(access = AccessLevel.PROTECTED)` |
| `public String getTitle() { return title; }` … | 클래스에 `@Getter` |

- 기본 생성자가 `protected`인 이유: JPA가 DB에서 읽은 행으로 객체를 만들 때 쓰고, 다른 코드가 빈 객체를 함부로 만들지 못하게 한다.
- 엔티티에 `@Setter`·`@Data`는 쓰지 않는다. 값 변경은 의미 있는 메서드(`Schedule.create`처럼 규칙을 검사하는 곳)로만 한다. `@Data`는 `equals`·`hashCode`·`toString`까지 만들어 지연 로딩 연관을 건드린다.
- 주의: `Schedule.maxPerUser`는 DB가 SMALLINT라 `short` 필드다. `@Getter`는 필드 타입 그대로 `short`를 돌려준다(전에는 `int`로 돌려줬다).

## 핵심 정리
- camelCase → snake_case는 Spring Boot의 Hibernate 이름 전략이 한다. 컬럼 name은 생략, 복수형 테이블 이름은 명시.
- Lombok은 팀 허용. 엔티티는 `@Getter` + `@NoArgsConstructor(PROTECTED)`, `@Setter`·`@Data`는 쓰지 않는다.

## 참고
- 루트 `build.gradle` Lombok 의존성, `docs/guides/code-style.md` 8절
- `reservation-service/.../concert/domain/entity/`
