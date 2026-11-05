# 관리자 테스트 시나리오

기준 커밋 `0bc8141`. 표 읽는 법은 [README](README.md)에 있다. 이 문서는 시나리오 정의만 담고, 실행 결과는 [`runs/`](runs/)에 있다.

## 관리자 (14건)

| ID | 시나리오 | 유형 | 우선순위 | 기대 결과 | 테스트 경로 | 실행 환경 |
|---|---|---|---|---|---|---|
| ADM-001 | 공연 등록 201, 설명 생략 가능 | 정상 | P0 | 201, 공연 ID | `reservation-service/AdminConcertApiTest` | 자동 테스트, 로컬, 서버 |
| ADM-002 | 공연 제목 빈 값·200자 초과 | 경계값 | P2 | 400 | `reservation-service/AdminConcertApiTest` | 자동 테스트 |
| ADM-003 | 일반 사용자 403, 헤더 없음 401(관리자 API 공통) | 보안 | P0 | 403, 저장 안 함. 헤더 없으면 401 | `reservation-service/AdminConcertApiTest`, `reservation-service/AdminScheduleApiTest`, `reservation-service/AdminScheduleSeatApiTest`, `reservation-service/AdminLotteryApiTest` | 자동 테스트, 로컬, 서버 |
| ADM-004 | 회차 등록 201, 매수 한도 4·선점 5분 기본값 | 정상 | P0 | 201, 기본값 적용 | `reservation-service/AdminScheduleApiTest` | 자동 테스트, 로컬, 서버 |
| ADM-005 | 회차 등록 실패: 없는·지운 공연, 오픈≥시작, 한도<1 | 경계값 | P1 | 404 또는 400 | `reservation-service/AdminScheduleApiTest` | 자동 테스트 |
| ADM-006 | 회차 좌석 15000석 생성 (ISE-177) | 정상 | P0 | 등급별 가격으로 15000석 | `reservation-service/AdminScheduleSeatApiTest` | 자동 테스트, 로컬, 서버 |
| ADM-007 | 좌석 재생성 409 SCHEDULE_SEATS_ALREADY_CREATED | 실패 | P1 | 409, 좌석 수 유지 | `reservation-service/AdminScheduleSeatApiTest` | 자동 테스트 |
| ADM-008 | 좌석 가격 입력 오류: 누락·LOTTERY·없는 등급·중복·0원 | 경계값 | P1 | 400, 좌석 미생성 | `reservation-service/AdminScheduleSeatApiTest` | 자동 테스트 |
| ADM-009 | 추첨 설정: S석을 ID 순으로 LOTTERY로 떼고 OPEN (S8) | 정상 | P0 | 201, OPEN. 저트래픽 시간대 밖이면 경고 포함 | `reservation-service/AdminLotteryApiTest` | 자동 테스트, 로컬, 서버 |
| ADM-010 | 추첨 설정 409: 좌석 부족, 두 번째 설정, 오픈 경과, 응모 시작 경과 | 실패 | P1 | 409, 좌석·추첨 불변 | `reservation-service/AdminLotteryApiTest` | 자동 테스트 |
| ADM-011 | 추첨 설정 400: 등급·마감 순서·공연 60분 전 제한·좌석 0·매수 3 | 경계값 | P1 | 400. 없는 회차는 404 | `reservation-service/AdminLotteryApiTest` | 자동 테스트 |
| ADM-012 | 추첨 수정: 좌석 증감, 등급 변경, 일정만 수정 | 정상 | P1 | 늘리면 다음 ID, 줄이면 큰 ID부터 반환, 등급 변경 시 전부 되돌린 뒤 재처리 | `reservation-service/AdminLotteryApiTest` | 자동 테스트, 로컬 |
| ADM-013 | 추첨 수정 실패: 좌석 부족·응모 시작 409, 값 관계 400, 없음 404 | 실패 | P1 | 해당 코드, 데이터 불변 | `reservation-service/AdminLotteryApiTest` | 자동 테스트 |
| ADM-014 | [미구현] 공연·회차 수정·삭제 관리자 API | 정상 | P2 | 45a0900 컨트롤러에 없음. seed-demo.sh 주석에도 회차를 고치는 API가 없다고 적힘 | 없음 | 로컬 |

### 관리자: 직접 실행할 때

| ID | 사전 조건 | 절차 | 서버 데이터 변경 |
|---|---|---|---|
| ADM-001 | ADMIN | POST /api/v1/admin/concerts | 있음 |
| ADM-003 | USER 토큰 | 공연·회차·좌석·추첨 관리자 API 호출 | 없음 |
| ADM-004 | 공연·공연장 | POST /admin/concerts/{id}/schedules | 있음 |
| ADM-006 | 회차 | POST /admin/schedules/{id}/seats | 있음 |
| ADM-009 | 오픈 전 회차 | POST /admin/schedules/{id}/lotteries | 있음 |
| ADM-012 | 응모 시작 전 추첨 | PATCH /admin/lotteries/{id} | 있음 |
| ADM-014 | ADMIN | PATCH·DELETE 호출 | 서버 실행 안 함 |
