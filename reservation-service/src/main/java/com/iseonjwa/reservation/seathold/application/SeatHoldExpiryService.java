package com.iseonjwa.reservation.seathold.application;

import com.iseonjwa.reservation.reservation.domain.entity.Reservation;
import com.iseonjwa.reservation.reservation.domain.entity.ReservationSeat;
import com.iseonjwa.reservation.reservation.domain.entity.ReservationSeatStatus;
import com.iseonjwa.reservation.reservation.domain.entity.ReservationStatus;
import com.iseonjwa.reservation.reservation.domain.entity.ReservationStatusHistory;
import com.iseonjwa.reservation.reservation.domain.entity.UserScheduleQuotaId;
import com.iseonjwa.reservation.reservation.domain.repository.ReservationRepository;
import com.iseonjwa.reservation.reservation.domain.repository.ReservationSeatRepository;
import com.iseonjwa.reservation.reservation.domain.repository.ReservationStatusHistoryRepository;
import com.iseonjwa.reservation.reservation.domain.repository.UserScheduleQuotaRepository;
import com.iseonjwa.reservation.seathold.domain.repository.SeatHoldInventoryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 결제되지 않고 선점 기한이 지난 예매를 회수한다(SA 8-3, E7). 예매 HELD → EXPIRED, 예매 좌석 HELD → RELEASED,
 * 회차 좌석 HELD → AVAILABLE, 보유 매수 차감, 상태 이력 기록을 예매 한 건마다 한 트랜잭션으로 묶는다.
 *
 * <p>Note: 같은 예매를 결제 결과 소비자(PaymentApproved·Declined)도 바꾼다. 예매 전이를 "HELD이고 기한이 지났을 때만"
 * 조건부 UPDATE로 먼저 하고, 0행이면 좌석·매수를 건드리지 않고 끝낸다. 먼저 바꾼 쪽만 후속 쓰기를 해서 반환이 두 번 일어나지 않는다
 * (ADR 0027의 "후속 쓰기는 영향 행이 1일 때만").
 *
 * <p>TODO: MVP 기준선이다(ADR 0035). M4 TS-4에서 대상 조회의 {@code FOR UPDATE SKIP LOCKED}와
 * 전역 잠금 순서(user_schedule_quotas → schedule_seats 오름차순 → reservations)를 적용한다. 지금은 예매 행을 먼저 잠근다.
 */
@Service
public class SeatHoldExpiryService {
    static final String EXPIRED_REASON = "HOLD_EXPIRED";
    // 스케줄러가 바꾼 행은 사람 감사자가 없다. 변경 이유는 이력의 reason으로 남긴다(SA 12-1)
    private static final Long SYSTEM_ACTOR = null;

    private static final Logger log = LoggerFactory.getLogger(SeatHoldExpiryService.class);

    private final ReservationRepository reservations;
    private final ReservationSeatRepository reservationSeats;
    private final ReservationStatusHistoryRepository histories;
    private final UserScheduleQuotaRepository quotas;
    private final SeatHoldInventoryRepository inventory;
    private final Clock clock;

    public SeatHoldExpiryService(ReservationRepository reservations, ReservationSeatRepository reservationSeats,
            ReservationStatusHistoryRepository histories, UserScheduleQuotaRepository quotas,
            SeatHoldInventoryRepository inventory, @Qualifier("commonAuditingClock") Clock clock) {
        this.reservations = reservations;
        this.reservationSeats = reservationSeats;
        this.histories = histories;
        this.quotas = quotas;
        this.inventory = inventory;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<Long> findExpiredReservationIds(int limit) {
        return reservations.findExpiredHeldIds(clock.instant(), limit);
    }

    /**
     * 예매 한 건을 만료한다. 이미 다른 상태로 바뀌었거나 아직 기한 전이면 아무것도 하지 않고 false를 돌려준다.
     */
    @Transactional
    public boolean expire(Long reservationId) {
        var now = clock.instant();
        if (reservations.expireHeld(reservationId, now, SYSTEM_ACTOR) == 0) {
            return false;
        }
        var reservation = reservations.findById(reservationId)
                .orElseThrow(() -> new IllegalStateException("만료한 예매가 없습니다: " + reservationId));

        releaseSeats(reservation, now);
        releaseQuota(reservation, now);
        histories.save(ReservationStatusHistory.of(reservationId, ReservationStatus.HELD, ReservationStatus.EXPIRED,
                EXPIRED_REASON, now));
        // TODO(ISE-88): source = LOTTERY 예매면 추첨 만료 훅(onExpired)을 같은 트랜잭션에서 호출한다
        return true;
    }

    // Note: 판매 여부의 기준은 reservation_seats다(D2). 회차 좌석 반환 행 수가 모자라도 롤백하지 않는다.
    // 롤백하면 예매가 HELD로 남아 매 주기 같은 실패를 되풀이하고 예매 좌석도 계속 묶인다. 어긋남은 경고로 남기고 대사(ISE-14)가 잡는다
    private void releaseSeats(Reservation reservation, Instant now) {
        var scheduleSeatIds = heldScheduleSeatIds(reservation.getId());
        reservationSeats.releaseHeld(reservation.getId(), now, SYSTEM_ACTOR);
        if (scheduleSeatIds.isEmpty()) {
            log.warn("만료한 예매에 HELD 좌석이 없습니다. reservationId={}", reservation.getId());
            return;
        }
        var released = inventory.releaseHeldSeats(reservation.getScheduleId(), scheduleSeatIds, now, SYSTEM_ACTOR);
        if (released != scheduleSeatIds.size()) {
            log.warn("만료한 예매의 회차 좌석 일부가 HELD가 아니었습니다. reservationId={}, seatIds={}, released={}",
                    reservation.getId(), scheduleSeatIds, released);
        }
    }

    private List<Long> heldScheduleSeatIds(Long reservationId) {
        var ids = new ArrayList<Long>();
        for (ReservationSeat seat : reservationSeats.findByReservationId(reservationId)) {
            if (seat.getStatus() == ReservationSeatStatus.HELD) {
                ids.add(seat.getScheduleSeatId());
            }
        }
        ids.sort(null);
        return ids;
    }

    // 보유 매수가 예매 수량보다 적으면 원장이 어긋난 것이다. 음수로 만들지 않고 경고만 남긴다(좌석 반환과 같은 이유)
    private void releaseQuota(Reservation reservation, Instant now) {
        var quotaId = new UserScheduleQuotaId(reservation.getUserId(), reservation.getScheduleId());
        if (quotas.subtractHeldCount(quotaId, reservation.getQuantity(), now, SYSTEM_ACTOR) == 0) {
            log.warn("만료한 예매의 보유 매수를 차감하지 못했습니다. reservationId={}, userId={}, scheduleId={}, quantity={}",
                    reservation.getId(), reservation.getUserId(), reservation.getScheduleId(),
                    reservation.getQuantity());
        }
    }
}
