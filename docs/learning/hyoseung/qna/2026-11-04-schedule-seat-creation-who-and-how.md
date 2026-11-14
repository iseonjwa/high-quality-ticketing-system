---
title: 회차 좌석 생성은 누가 부르고, 공연장·등급·가격은 어디서 오나
date: 2026-11-04
asker: hyos1
level: 기초
tags: [concert, schedule-seats, admin, venue, seat-grade]
---

# 회차 좌석 생성은 누가 부르고, 공연장·등급·가격은 어디서 오나

## 질문

회차 좌석 생성 요청에는 좌석 등급과 가격을 넣는다. 등급을 알려면 공연장 좌석을 먼저 조회해야 할 것 같은데, 누가 이 API를 부르고 공연장·등급·가격은 각각 어떻게 아나.

## 답

### 누가 부르나

사람이다. 운영자(ADMIN)가 운영자 화면 `frontend/src/pages/admin/AdminConcertPage.tsx`에서 버튼을 눌러 부른다. 테스트할 때는 `http/lottery-e2e.http`로 같은 순서를 직접 보낸다. 스케줄러나 이벤트가 자동으로 부르지 않는다.

운영자는 세 API를 순서대로 부른다.

| 순서 | API | 운영자가 넣는 값 | 결과 |
|---|---|---|---|
| 1 | `POST /admin/concerts` | 제목, 설명 | `concertId` |
| 2 | `POST /admin/concerts/{concertId}/schedules` | **venueId**, 공연 시각, 오픈 시각 | `scheduleId` |
| 3 | `POST /admin/schedules/{scheduleId}/seats` | **등급별 가격** | 만든 좌석 수 15,000 |

### 세 값은 각각 어디서 오나

| 값 | 어디서 오나 | 코드 |
|---|---|---|
| 어느 공연장 | 2단계(회차 등록)에서 운영자가 `venueId`를 넣어 회차에 묶는다. 3단계 요청에는 공연장이 없다. 서버가 회차에서 꺼낸다 | `ConcertAdminService.createScheduleSeats`의 `schedule.getVenue().getId()` |
| 어떤 등급이 있나 | 서버가 이미 안다. 공연장 좌석(`venue_seats`) 시드에 등급이 적혀 있다. 서버는 요청의 등급 모음과 공연장 등급 모음이 같은지 확인하고, 다르면 400을 준다 | `venueSeatRepository.findGradesByVenueId(venueId)`와 `prices.keySet().equals(venueGrades)` |
| 가격 | 시스템이 알 수 없는 값이다. 기획사가 정하는 사업 결정이라 운영자가 입력한다. 회차마다 다를 수 있어 회차 좌석에 저장한다 | `ScheduleSeatCreateRequest.prices` → `schedule_seats.price` |

즉 운영자는 등급을 조회하지 않고, 알고 있는 등급(VIP, R, S)에 가격만 붙여 보낸다. 등급이 틀리면 서버가 막는다. 조회 대신 검증으로 맞춘 구조다.

### 서버 안의 흐름 (3단계)

1. 회차를 잠금 조회한다(`findByIdForUpdate`). 없으면 404다.
2. 이미 회차 좌석이 있으면 409 `SCHEDULE_SEATS_ALREADY_CREATED`다. 잠금 덕분에 두 요청이 동시에 와도 하나만 만든다.
3. 회차의 공연장 ID로 공연장 등급 모음을 읽고, 요청 등급 모음과 다르면 400이다. 빠지면 가격 없는 좌석이 생기고, 남으면 오타일 수 있어서다.
4. `INSERT ... SELECT`로 `venue_seats` 15,000행을 한 번에 `schedule_seats`로 복사하고 등급에 맞는 가격을 붙인다(`JpaScheduleSeatRepository.createFromVenueSeats`).

### 보내는 쪽은 값을 어떻게 알고 넣나 (추가 질문)

시스템이 알려 주지 않는다. 요청을 만드는 쪽이 미리 알고 있다.

| 값 | 운영자 화면이 아는 방법 |
|---|---|
| `venueId` | 운영자가 번호를 직접 친다. 공연장이 시드로 넣은 KSPO DOME 하나(id 1)뿐이라 "1"을 안다. 공연장을 고를 목록 API가 없다 |
| 등급(VIP, R, S) | 프론트 코드에 박혀 있다. `frontend/src/admin/formSchemas.ts`의 `SEAT_GRADES = ['VIP', 'R', 'S']`가 시드 등급과 같게 미리 적혀 있고, 화면은 이 세 칸을 그린다 |
| 가격 | 운영자가 정해서 친다. 기획사가 정하는 값이라 시스템에서 올 수 없다 |

화면에서 운영자가 칸을 채우면 `toSeatPrices`가 `[{grade, price}]`로 바꿔 요청 본문에 넣는다. 서버의 등급 비교(400)는 프론트에 박힌 등급과 시드가 어긋났을 때를 잡는 안전장치다.

정석은 화면이 먼저 공연장을 조회해(`GET /admin/venues`, `GET /admin/venues/{id}/grades` 같은 API) 그 결과로 선택지와 등급 칸을 그리는 것이다. 공연장이 하나뿐이라 이 조회를 생략하고 미리 아는 값으로 대신했다.

### 지금 비어 있는 곳

공연장 목록이나 공연장 등급을 조회하는 API는 없다. 공연장은 시드로 넣은 KSPO DOME 하나(id 1)뿐이라 운영자 화면은 공연장 번호를 직접 입력받고("공연장 목록이 아직 없어 번호로 넣어요"), 가격 칸은 VIP·R·S로 고정해 둔다. 공연장이 여러 곳이 되면 `GET /admin/venues`(공연장과 등급별 좌석 수) 같은 조회 API가 있어야 운영자가 번호와 등급을 외우지 않아도 된다. 지금 범위(SA 11-5)에는 없는 API다.

## 출처

- `reservation-service/.../concert/application/ConcertAdminService.java`
- `reservation-service/.../concert/presentation/dto/request/ScheduleSeatCreateRequest.java`, `ScheduleCreateRequest.java`
- `frontend/src/pages/admin/AdminConcertPage.tsx`, `frontend/src/api/admin.ts`
- `http/lottery-e2e.http` 2~4단계
- `docs/core/SA MASTER.md` 공연·좌석 API 표
