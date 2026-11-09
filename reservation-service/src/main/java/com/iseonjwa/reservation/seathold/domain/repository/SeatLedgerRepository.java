package com.iseonjwa.reservation.seathold.domain.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 좌석 원장 대사(ISE-14)용 조회와 보정. 판매 여부의 기준은 활성 예매 좌석({@code reservation_seats}의 HELD·CONFIRMED)이고(D2),
 * 회차 좌석 상태와 보유 매수는 그 원장에서 계산한 값과 같아야 한다.
 *
 * <ul>
 *   <li>회차 좌석: 활성 예매 좌석이 CONFIRMED면 SOLD, HELD면 HELD, 없으면 AVAILABLE</li>
 *   <li>보유 매수: 사용자·회차의 활성 예매 좌석 수. 결제 확정 뒤에도 매수 한도에 계속 들어가므로 CONFIRMED도 센다</li>
 * </ul>
 */
public interface SeatLedgerRepository {

    // 원장과 다른 회차 좌석을 좌석 ID 순으로 최대 limit건 찾는다
    List<SeatStatusMismatch> findSeatStatusMismatches(Long scheduleId, int limit);

    // 원장과 다른 보유 매수를 사용자 ID 순으로 최대 limit건 찾는다. 보유 매수 행이 없으면 0으로 본다
    List<HeldCountMismatch> findHeldCountMismatches(Long scheduleId, int limit);

    // 회차 좌석 행을 잠그고 지금 상태를 돌려준다. 회차가 다르거나 좌석이 없으면 빈 값이다
    Optional<String> lockSeatStatus(Long scheduleId, Long scheduleSeatId);

    // 활성 예매 좌석 원장에서 계산한 회차 좌석 상태
    String expectedSeatStatus(Long scheduleSeatId);

    // 상태가 expected와 다를 때만 바꾸고 바꾼 행 수를 돌려준다
    int updateSeatStatus(Long scheduleSeatId, String expected, Instant now, Long actorId);

    // 보유 매수 행이 없으면 0으로 만들고 잠근 뒤 지금 값을 돌려준다
    int lockHeldCount(Long userId, Long scheduleId, Instant now, Long actorId);

    // 사용자·회차의 활성 예매 좌석 수
    int countActiveSeats(Long userId, Long scheduleId);

    // 보유 매수가 expected와 다를 때만 바꾸고 바꾼 행 수를 돌려준다
    int updateHeldCount(Long userId, Long scheduleId, int expected, Instant now, Long actorId);

    record SeatStatusMismatch(Long scheduleSeatId, String actual, String expected) {}

    record HeldCountMismatch(Long userId, int actual, int expected) {}
}
