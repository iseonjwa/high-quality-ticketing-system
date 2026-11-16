---
title: Flyway 마이그레이션은 왜 고치거나 지우지 않고 새 파일로 되돌리나
date: 2026-11-16
asker: hyos1
level: 입문
tags: [flyway, migration, database, soft-delete]
---

# Flyway 마이그레이션은 왜 고치거나 지우지 않고 새 파일로 되돌리나

## 질문

Flyway 파일을 수정하면 문제가 생긴다고 해서 새 파일로 덮어쓰는 식으로 했다. 1,500석으로 줄이는 파일을 적용한 뒤 나중에 다시 15,000석으로 하려면 새로 만든 파일을 지우기만 하면 되나?

## 답

지우기만 해서는 돌아가지 않는다. 되돌리는 마이그레이션을 하나 더 넣는다.

1. Flyway는 파일을 한 번 실행하고, 실행한 버전·체크섬을 DB의 `flyway_schema_history` 표에 적는다. 파일이 바꾼 데이터(여기서는 13,500행의 `deleted_at`)는 그 순간 DB에 남는다. 파일을 지워도 데이터는 그대로다.
2. 서버가 뜰 때 Flyway는 기록과 저장소 파일을 맞춰 본다(validate). 적용한 파일의 내용을 고치면 체크섬이 달라서, 적용한 파일을 지우면 기록에 있는 파일이 없어서 검증에 실패하고 기동이 멈춘다.
3. 그래서 마이그레이션은 앞으로만 간다. 15,000석으로 돌아가려면 `deleted_at`을 다시 비우는 새 파일을 더한다.

```sql
UPDATE venue_seats SET deleted_at = NULL, updated_at = now()
WHERE venue_id = (SELECT min(id) FROM venues WHERE name = 'KSPO DOME') AND row_no > 2;
```

## 이 프로젝트에서

- 1,500석으로 줄일 때 행을 지우지(DELETE) 않고 지운 표시(soft delete)만 한 이유가 둘이다. 이미 만든 회차가 공연장 좌석을 FK로 참조해 DELETE가 실패하고, 표시만 하면 위 한 줄로 되돌릴 수 있다.
- 결정 기록: `docs/decisions/0060-seed-venue-1500-seats.md`, 이슈 ISE-291.

## 더 알아보기

- Flyway 문서 "Validate", "Undo"(유료판의 되돌리기 기능. 무료판은 되돌리는 마이그레이션을 직접 더한다).
