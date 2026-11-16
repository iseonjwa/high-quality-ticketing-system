---
title: Flyway는 무엇이고, 이 프로젝트에서 마이그레이션 파일은 어떻게 쓰는가
date: 2026-10-29
updated: 2026-10-30
asker: hyos1
level: 기초
tags: [flyway, migration, database, jpa, ddl]
---

# Flyway는 무엇이고, 이 프로젝트에서 마이그레이션 파일은 어떻게 쓰는가

## 질문
Flyway를 전혀 모른다. 테이블을 Flyway로 만들라는데 무엇을 어떻게 쓰는가.

## 답
### Flyway가 하는 일
DB 테이블을 만드는 SQL을 파일로 저장소에 두고, 앱이 시작할 때 아직 적용 안 된 파일만 순서대로 실행해 주는 도구다. "DB 구조의 Git"에 가깝다.

- 왜 필요한가: 팀원 5명의 로컬 DB, 테스트 DB, 배포 DB가 모두 같은 구조여야 한다. 손으로 `CREATE TABLE`을 치면 누군가는 빠뜨린다. 파일로 두면 코드와 함께 리뷰되고 어디서나 같은 순서로 적용된다.
- JPA와의 관계: 이 프로젝트는 `config-repo/application.yml`에 `ddl-auto: validate`를 둔다. JPA는 테이블을 만들지 않고 "엔티티와 테이블이 맞는가"만 검사한다. 테이블은 Flyway가 만든다. 맞지 않으면 앱이 뜨지 않는다.

### 동작 순서
1. 앱 시작 → Flyway가 `src/main/resources/db/migration/` 폴더를 읽는다.
2. DB의 `flyway_schema_history` 테이블에서 이미 적용한 파일 목록을 확인한다.
3. 안 적용한 파일을 버전 순서대로 실행하고 기록한다.
4. 그다음 JPA가 엔티티와 테이블을 비교한다(validate).

### 파일 규칙
- 이름: `V<버전>__<설명>.sql`. 밑줄이 **두 개**다. 예: `V1__users.sql`, `V2__login_security.sql`.
- **한 번 적용된 파일은 고치지 않는다.** Flyway가 파일 체크섬을 기록해 두어서, 내용이 바뀌면 "적용된 파일이 바뀌었다"며 앱 시작을 막는다. 고칠 게 생기면 새 파일(`V3__...`)을 추가한다.
  - 예외: 아직 dev에 머지되지 않고 내 로컬에서만 적용한 파일은 고쳐도 된다. 대신 로컬 DB를 지우고(볼륨 삭제) 다시 띄워야 한다.
- 같은 DB를 여러 사람이 쓰면 버전 번호가 겹칠 수 있다. reservation-service는 권우 님과 같은 DB라 번호 규칙을 맞춘다.

### 저장소 예시 (user-service)
`user-service/src/main/resources/db/migration/V1__users.sql`:
```sql
CREATE TABLE users (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    email VARCHAR(255) NOT NULL UNIQUE,
    ...
    created_at TIMESTAMPTZ NOT NULL,
    created_by BIGINT,
    updated_at TIMESTAMPTZ NOT NULL,
    updated_by BIGINT,
    deleted_at TIMESTAMPTZ,
    deleted_by BIGINT
);
```
마지막 여섯 줄이 SA 12-1의 `BaseEntity` 감사 컬럼이다. 업무 테이블에는 모두 들어간다.

### SA 표 → SQL로 옮기는 법
| SA 12-3 표 | SQL |
|---|---|
| `id BIGINT PK` | `id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY` |
| `NOT NULL` | 컬럼 뒤에 `NOT NULL` |
| `NOT NULL, FK` (예: `venue_id`) | `venue_id BIGINT NOT NULL REFERENCES venues (id)` |
| `UNIQUE(a, b, c)` | 마지막 줄에 `UNIQUE (a, b, c)` |
| `CHECK (VIP·R·S)` | `CHECK (grade IN ('VIP', 'R', 'S'))` |
| `INDEX(a)` | 테이블 밖에서 `CREATE INDEX idx_<테이블>_<컬럼> ON <테이블> (a);` |

### 후속 질문(10/30): 이미 적용된 구조를 바꿀 때의 흐름
`row_no`를 VARCHAR에서 INT로 바꾼 실제 사례(ADR 0033)다.

**체크섬은 데이터가 아니라 파일 내용의 지문이다.** 테이블에 행이 들어가거나 구조가 바뀌어도 체크섬은 변하지 않는다. `flyway_schema_history`에 "이 버전 파일을 적용했고, 그때 파일 내용의 체크섬은 이것"이 남는다.

앱이 뜰 때 Flyway가 하는 일:
1. **검증(validate)**: 이력에 있는 버전마다 지금 파일의 체크섬을 다시 계산해 이력과 비교한다. 다르면 "적용된 뒤 파일이 바뀌었다(오염)"로 보고 기동을 멈춘다.
2. **적용(migrate)**: 이력에 없는 버전 파일을 번호 순서대로 실행하고 이력에 추가한다.

그래서 구조를 바꿀 때는 적용된 파일을 고치지 않고 새 파일을 추가한다.
- `V20261029_1`(테이블 생성)은 그대로 둔다 → 체크섬이 같아 검증 통과
- `V20261030_1`에 `ALTER TABLE venue_seats ALTER COLUMN row_no TYPE INT USING row_no::int`를 쓴다 → 새 버전이라 적용된다. `USING`은 기존 행의 값을 어떻게 바꿀지 정한다. 숫자가 아닌 값이 하나라도 있으면 실패하고, PostgreSQL은 DDL도 트랜잭션이라 통째로 되돌아간다.
- `V20261030_2`에 시드를 넣는다 → 바뀐 타입(INT) 위에 들어간다.

**아직 머지 전인 파일은 고쳐도 된다.** 시드 파일은 dev에 올라가기 전이라 내 컴퓨터 테스트 DB에만 적용돼 있었다. 주석을 달고 번호를 1 → 2로 바꾸자 그 DB에서 체크섬·버전이 어긋났다. 테스트 DB는 tmpfs라 컨테이너를 재시작해 비우고 처음부터 다시 적용했다. 다른 사람은 이 파일을 적용한 적이 없으니 영향이 없다. `flyway repair`(이력의 체크섬을 지금 파일 기준으로 고침)라는 방법도 있지만, 공유된 DB에서 쓰면 파일과 실제 구조가 어긋난 것을 덮어 버리므로 내 로컬에서만 쓴다.

### 후속 질문(10/30): 행이 생기고 컬럼 타입이 바뀌는데 왜 체크섬은 그대로인가
"파일이 하나라도 달라지면 체크섬이 달라져 오염으로 본다"는 이해는 맞다. 헷갈린 지점은 비교 대상이다.

| 비교 대상 | 어디서 오나 |
|---|---|
| 기록된 체크섬 | 그 파일을 처음 실행할 때 파일 글자로 계산해 `flyway_schema_history`에 적은 값 |
| 지금 체크섬 | 기동할 때 같은 파일 글자로 다시 계산한 값(CRC32) |

둘 다 파일 텍스트로만 계산하고, Flyway는 실제 테이블을 보지 않는다.
- 10/29 `V20261029_1`(`row_no VARCHAR(5)`) 실행 → 체크섬 A 기록
- 10/30 `V20261030_1`(`ALTER ... TYPE INT`) 실행 → 새 줄에 체크섬 B 기록, 테이블은 INT
- 다음 기동: `V20261029_1` 파일 글자는 그대로 → 다시 계산해도 A → 통과
- 만약 `V20261029_1`의 `VARCHAR(5)`를 직접 `INT`로 고쳤다면 → A' ≠ A → 기동 실패(오염)

최종 테이블은 파일 하나가 아니라 파일들을 순서대로 실행한 누적 결과다(Git 커밋을 쌓는 것과 같다). 첫 파일은 VARCHAR, 실제 컬럼은 INT여도 모순이 아니다.

약점: Flyway는 테이블을 보지 않으므로 누가 DB에서 손으로 `ALTER`하면 알아채지 못한다. 이 프로젝트에서는 JPA `ddl-auto: validate`가 엔티티와 실제 테이블 차이를 잡아 일부 막는다. 구조 변경은 반드시 마이그레이션 파일로만 한다.

## 핵심 정리
- 테이블은 Flyway SQL 파일이 만들고, JPA는 `validate`로 맞는지만 확인한다.
- 파일 이름은 `V<버전>__<설명>.sql`, 적용된 파일은 고치지 않고 새 파일을 추가한다.

## 면접 포인트
- "`ddl-auto: update`를 왜 안 쓰나": 운영 DB 구조를 코드가 몰래 바꾸면 추적·되돌리기가 안 된다. 마이그레이션 파일로 변경 이력을 리뷰하고 모든 환경에 같은 순서로 적용한다.

## 참고
- `user-service/src/main/resources/db/migration/`, `payment-service/src/main/resources/db/migration/`
- `config-repo/application.yml`(`ddl-auto: validate`)
- `docs/core/SA MASTER.md` 12-1, 12-3
