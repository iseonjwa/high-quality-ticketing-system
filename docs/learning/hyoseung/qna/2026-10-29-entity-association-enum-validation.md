---
title: 공연·좌석 엔티티의 연관관계(객체 vs ID), enum 저장, 입력 검증 위치(DTO vs 엔티티)
date: 2026-10-29
updated: 2026-10-29
asker: hyos1
level: 기초
tags: [jpa, entity, association, enum, validation, modular-monolith]
---

# 공연·좌석 엔티티의 연관관계(객체 vs ID), enum 저장, 입력 검증 위치(DTO vs 엔티티)

## 질문
1. Reservation 서비스 안에서 "모듈로 나눈다"는 것이 무엇인가.
2. N쪽 엔티티가 1쪽을 객체(`@ManyToOne`)로 가질지 ID로 가질지. 1.5만 행 `schedule_seat`가 `Schedule`을 객체로 가져도 접근하지 않으면 상관없지 않은가.
3. 상태·등급은 enum + `@Enumerated`로 두면 문자열로 저장되는가.
4. `openAt < startsAt` 검증을 엔티티 생성 시 하면서 요청 DTO에서도 한 번 더 하면 어떤가.

## 답
### 1. Reservation 안 모듈 = 모듈러 모놀리스
SA 7-1(결정 기록 #14): Reservation 서비스 하나 안에 `concert`·`reservation`·`queue`·`lottery`·`notification`을 패키지 단위 모듈로 나눈다. 배포는 하나지만 모듈 사이 호출 규칙을 정해 경계를 지킨다. 측정으로 분리 이유가 생기면 그 모듈만 서비스로 뗀다.
- reservation·lottery는 직접 호출 허용, queue는 인터페이스로만, notification은 Kafka 이벤트로만, concert 조회는 읽기 직접 허용.
- "필요할 때 나눈다"는 판단과 같은 방향이다. 분리 가능성을 남기려고 경계만 미리 긋는다.
- 엔티티 연관관계에 주는 영향: 같은 모듈 안(venue·venue_seat·concert·schedule·schedule_seat)은 객체 참조로 묶어도 된다. 모듈 경계를 넘는 참조(권우 님 `reservation_seat` → 내 `schedule_seat`)는 ID로 두는 편이 나중에 떼기 쉽다. 이쪽은 ISE-10에서 권우 님이 정한다.

### 2. 객체 참조(`@ManyToOne`)를 고른 이유와 주의점
- N쪽이 FK를 가지므로 연관관계의 주인이다. 맞다.
- 객체로 두면 fetch join 한 번으로 1쪽을 함께 가져온다. ID만 두면 1쪽 정보가 필요할 때 따로 조회해야 한다. 맞다.
- 주의 1: `@ManyToOne`의 기본 fetch는 **EAGER**다. `fetch = FetchType.LAZY`를 반드시 적는다. 안 적으면 접근하지 않아도 즉시 조회된다.
- 주의 2: 1.5만 개 `ScheduleSeat`가 가진 `Schedule`은 모두 같은 회차다. 영속성 컨텍스트가 같은 ID의 프록시를 하나만 두므로, 접근하더라도 추가 쿼리는 1번이다. 객체 참조 자체는 문제가 아니다.
- 진짜 비용은 **엔티티 1.5만 개를 읽는 것 자체**다(메모리, 변경 감지 스냅샷). 좌석 조회 API처럼 읽기만 하는 곳은 엔티티 대신 필요한 컬럼만 DTO로 조회(프로젝션)하는 것을 그때 검토한다.

### 3. enum 저장
`@Enumerated(EnumType.STRING)`이다(`String.class` 아님). 기본값은 `ORDINAL`(순서 숫자)이라 enum 순서를 바꾸면 기존 데이터의 의미가 바뀐다. 항상 `STRING`을 명시한다. DB 쪽은 SA대로 `VARCHAR` + `CHECK`로 허용 값을 한 번 더 막는다.

### 4. 검증은 두 층으로 나누되 같은 규칙을 두 번 쓰지 않는다
| 층 | 맡는 것 | 예 | 실패 응답 |
|---|---|---|---|
| 요청 DTO (`@Valid`) | 형식: 값이 있는가, 범위 | `@NotNull startsAt`, `@Min(1) maxPerUser` | 400 + 필드별 오류(`FieldError`) |
| 엔티티 생성자·정적 팩토리 | 업무 규칙(불변식): 값끼리의 관계 | `openAt`이 `startsAt`보다 앞 | `BusinessException(ErrorCode)` → 400 |

- 엔티티 검증은 반드시 둔다. 엔티티는 API 말고도 시드, 테스트, 나중의 수정 API에서 만들어질 수 있고, 그 모든 경로에서 규칙이 지켜져야 한다.
- 같은 관계 규칙을 DTO에도 넣으면(클래스 단위 커스텀 검증기) 규칙이 두 곳에 생겨 한쪽만 고치는 실수가 생긴다. 응답 모양을 필드 오류로 맞추고 싶다는 이유가 분명할 때만 추가한다.
- AI 설계 검증 #7(`docs/sa-review/06-layered-architecture-check.md`)이 지적한 "빈약한 도메인"은 이 규칙이 서비스나 DTO에만 있고 엔티티에는 없는 상태다.

### 5. VO(값 객체)와 Money
- VO는 값 하나를 클래스로 감싼 것이다. 엔티티는 id로 구분하고, VO는 값이 같으면 같다. 테이블과 id가 없다.
- `Money`는 `BigDecimal`을 감싸 금액 규칙(음수 금지, 소수 금지, `compareTo` 비교)을 클래스 안에서 지킨다(SA 12-1).
- DB에는 숫자 컬럼 하나(`NUMERIC(15,0)`)로 저장되고, `@Convert(converter = MoneyConverter.class)`가 변환한다.
- 10/29 기준 `Money`는 권우 님 브랜치(`feat/ISE-10-seat-hold-schema`)의 `reservation/domain/vo/`에만 있고 dev에는 없다. 두 모듈이 함께 쓰도록 공유 위치로 옮기기로 했다.

## 핵심 정리
- 같은 모듈 안은 `@ManyToOne(fetch = LAZY)` 객체 참조, 모듈 경계를 넘으면 ID 참조를 우선 검토한다.
- enum은 `EnumType.STRING`.
- DTO는 형식, 엔티티는 업무 규칙. 같은 규칙을 두 곳에 쓰지 않는다.

## 면접 포인트
- "`@ManyToOne` 기본 fetch는?": EAGER. 그래서 LAZY를 명시하고 필요한 곳만 fetch join한다.
- "검증은 어디서 하나": 형식은 표현 계층 DTO, 불변식은 도메인 엔티티. 엔티티는 어떤 경로로 만들어져도 유효해야 하기 때문이다.

## 참고
- `docs/core/SA MASTER.md` 7-1(모듈 규칙), 12-3
- `common/.../response/BusinessException.java`, `FieldError.java`
