# 0034. 트래픽 집중 지점 격리와 스케일업을 위해 좌석 선점(seathold)을 별도 모듈로 분리하고 단방향 의존 규칙을 둔다

- 상태: 제안 (예매 흐름 담당 반효승 확인 필요, ISE-10)
- 날짜: 2026-10-30
- 결정자: 최권우 (좌석 선점 담당)
- 관련 AI 판단: [트래픽 집중 지점 격리와 향후 스케일업을 위해 좌석 선점(seathold)을 별도 도메인 모듈로 분리하되 단방향 의존 규칙을 둔다](../ai-judgments/kwonwoo/2026-10-30-seathold-module-separation.md)

## 맥락

SA MASTER 13장의 `reservation-service` 패키지 구조 초안은 `concert`, `queue`, `reservation`, `lottery`, `global` 5개 패키지로만 나뉘어 있어, 좌석 선점(동기형 선점·만료 스케줄러·대사·M4 Kafka 접수형 `SeatHoldConsumer`·30초 타임아웃)과 일반 예매 흐름(단건/목록 조회·결제 결과 `payment.result` 소비·예매 취소·티켓 발급)이 모두 `reservation` 패키지 하나에 들어가게 되어 있었다.

그러나 두 영역은 다음과 같은 구조적 차이가 있다.
1. **트래픽 집중도와 확장 방향의 비대칭성**: 티켓 오픈 정각의 폭주 트래픽과 DB 비관적 락 경합, M4의 Kafka 접수형 큐잉(`seat-hold.request`)은 모두 '좌석 선점(`seathold`)' 입구에 집중된다. 반면 선점 이후의 예매 조회·결제 확정(`reservation`)은 5분 선점 유지 시간 동안 분산되어 처리된다.
2. **향후 스케일업 및 분리 용이성**: 부하 측정 결과에 따라 선점 접수 계층이나 컨슈머를 독립 확장(Scale-up / Scale-out)할 때 모듈 경계가 이미 나뉘어 있어야 코드 결합 없이 분리할 수 있다.
3. **공유 테이블에 의한 순환 참조 위험**: 반면 두 모듈을 아무 규칙 없이 나누고 `UserScheduleQuota`를 `seathold`에 두면, 선점 시에는 `seathold → reservation`(`Reservation` 생성), 결제 거절·취소 시에는 `reservation → seathold`(`UserScheduleQuota` 차감) 참조가 생겨 양방향 순환 의존이 발생한다.

## 결정

1. **`seathold` 모듈 패키지 신설**:
   - `com.iseonjwa.reservation.seathold.{presentation, application, domain, infrastructure}` 패키지를 둔다.
   - 선점 API 컨트롤러, 동기형/접수형 선점 서비스, `SeatHoldConsumer`, 30초 접수 타임아웃 스케줄러, 5분 선점 만료 스케줄러, 좌석·매수 정합성 대사 서비스를 `seathold` 모듈이 담당한다.
2. **엔티티 소유권과 단방향 의존 규칙 (`seathold` → `reservation`, `concert`)**:
   - **`seathold.domain`**: 선점 입구 전용 데이터인 **`SeatHoldRequest`, `SeatHoldRequestStatus`**와 그 저장소(`SeatHoldRequestRepository`)만 소유한다.
   - **`reservation.domain`**: 예매 생명주기 전반(선점·결제 승인·결제 거절·취소·추첨)에서 함께 쓰이는 원장 데이터인 **`Reservation`, `ReservationSeat`, `ReservationStatusHistory`, `UserScheduleQuota`(`UserScheduleQuotaId`)**와 그 저장소를 소유한다.
   - **의존 방향 고정**: `seathold`는 `reservation.domain`과 `concert.domain`(`Schedule`, `ScheduleSeat`)을 단방향으로 호출한다. **`reservation`과 `concert`는 `seathold` 패키지를 절대 import하지 않는다.**
3. **공통 잠금 순서(SA 8-4) 준수**:
   - 선점 만료(`seathold`)와 결제 거절·취소(`reservation`) 모두 `user_schedule_quotas` → `schedule_seats`(ID 오름차순) → `reservations` 잠금 순서를 지키는 도메인/저장소 상태 전이 규칙을 사용한다.

## 검토한 대안

- **`reservation` 단일 도메인 패키지 유지 (방법 1)**:
  - SA 13장 초안을 고치지 않아도 되고 한 패키지 안에 모든 엔티티가 모이지만, M4 접수형 고도화까지 진행하면 30개 이상의 클래스가 한 패키지에 섞이고 트래픽 집중 지점(선점 입구)의 경계가 코드 구조에 드러나지 않는다.
- **`UserScheduleQuota`까지 `seathold.domain`에 배치**:
  - 매수 한도 검사가 선점 시점에 일어나므로 직관적으로 보이지만, 결제 거절(`PaymentDeclined`)과 예매 취소(`cancel`), 추첨(`lottery`)에서도 `user_schedule_quotas.held_count`를 증감해야 하므로 `reservation ↔ seathold` 양방향 순환 참조가 생긴다.

## 결과

- 얻는 것:
  - 오픈 정각 트래픽이 집중되는 좌석 선점(`seathold`)과 예매 원장·결제 SAGA(`reservation`)의 경계가 패키지 수준에서 명확히 분리된다.
  - `seathold → reservation` 단방향 의존만 존재하므로 순환 참조 없이 깔끔한 모듈러 모놀리스 구조를 유지하며, 향후 스케일업이나 분리 시에도 `seathold`만 떼어내기 수월하다.
- 감수하는 것:
  - `seathold.application`에서 `reservation.domain`의 엔티티·저장소(또는 포트)를 import해 조합해야 한다.
- 후속 작업:
  - 예매 흐름 담당(반효승) 확인 후 SA 13장 패키지 구조에 `seathold`를 반영한다.
