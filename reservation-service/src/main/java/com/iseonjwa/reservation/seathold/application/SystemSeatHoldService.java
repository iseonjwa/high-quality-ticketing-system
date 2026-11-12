package com.iseonjwa.reservation.seathold.application;

import com.iseonjwa.common.response.BusinessException;
import com.iseonjwa.common.response.CommonErrorCode;
import com.iseonjwa.reservation.concert.domain.entity.ScheduleSeat;
import com.iseonjwa.reservation.global.vo.Money;
import com.iseonjwa.reservation.reservation.domain.entity.Reservation;
import com.iseonjwa.reservation.reservation.domain.entity.ReservationSeat;
import com.iseonjwa.reservation.reservation.domain.entity.ReservationStatus;
import com.iseonjwa.reservation.reservation.domain.entity.ReservationStatusHistory;
import com.iseonjwa.reservation.reservation.domain.entity.UserScheduleQuotaId;
import com.iseonjwa.reservation.reservation.domain.repository.ActiveSeatConflictException;
import com.iseonjwa.reservation.reservation.domain.repository.ReservationRepository;
import com.iseonjwa.reservation.reservation.domain.repository.ReservationSeatRepository;
import com.iseonjwa.reservation.reservation.domain.repository.ReservationStatusHistoryRepository;
import com.iseonjwa.reservation.reservation.domain.repository.UserScheduleQuotaRepository;
import com.iseonjwa.reservation.seathold.domain.repository.SeatHoldInventoryRepository;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * 추첨 당첨 예매를 만드는 시스템 선점(D10, ISE-88). 사용자 요청이 아니라 추첨 배정 워커가 부르므로
 * 입장 토큰·대기열·판매 기간을 보지 않고, 결제 토큰도 만들지 않는다(자동결제).
 * 매수 한도·좌석 조건부 UPDATE·예매 원장은 일반 선점({@link SyncSeatHoldService})과 같은 규칙을 쓴다.
 */
@Service
public class SystemSeatHoldService {
    // 당첨 예매의 선점 시간. 자동결제가 끝날 때까지 기다리는 상한이다(D10)
    public static final Duration LOTTERY_HOLD = Duration.ofMinutes(30);
    private static final String HELD_REASON = "LOTTERY_ASSIGNED";

    private final SeatHoldInventoryRepository inventory;
    private final UserScheduleQuotaRepository quotas;
    private final ReservationRepository reservations;
    private final ReservationSeatRepository reservationSeats;
    private final ReservationStatusHistoryRepository histories;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public SystemSeatHoldService(SeatHoldInventoryRepository inventory, UserScheduleQuotaRepository quotas,
            ReservationRepository reservations, ReservationSeatRepository reservationSeats,
            ReservationStatusHistoryRepository histories, @Qualifier("commonAuditingClock") Clock clock) {
        this.inventory = inventory;
        this.quotas = quotas;
        this.reservations = reservations;
        this.reservationSeats = reservationSeats;
        this.histories = histories;
        this.clock = clock;
    }

    /**
     * 추첨석으로 HELD 예매({@code source = LOTTERY})를 만든다. 부르는 쪽(배정 워커)의 트랜잭션에 함께 묶인다.
     *
     * @throws BusinessException 매수 한도를 넘으면 {@code PURCHASE_LIMIT_EXCEEDED}, 추첨석이 아니거나 이미 잡혔으면
     *                           {@code SEAT_ALREADY_HELD}
     */
    // Note: MANDATORY로 둬서 배정 워커의 "예매 HELD → 응모 연결"이 한 트랜잭션이 되게 강제한다.
    // 따로 커밋되면 예매만 남고 응모는 연결되지 않은 채 워커가 죽을 수 있다(details.md 4절 배정 워커)
    @Transactional(propagation = Propagation.MANDATORY)
    public SystemHoldResult holdLotterySeats(Long userId, Long scheduleId, List<Long> seatIds) {
        var sortedSeatIds = seatIds.stream().distinct().sorted().toList();
        if (sortedSeatIds.isEmpty() || sortedSeatIds.size() != seatIds.size()) {
            throw new IllegalArgumentException("선점할 추첨석이 비었거나 중복이다: " + seatIds);
        }
        var schedule = inventory.findSchedule(scheduleId)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.NOT_FOUND));
        var seats = inventory.findSeats(scheduleId, sortedSeatIds);
        if (seats.size() != sortedSeatIds.size()) {
            throw new BusinessException(SeatHoldErrorCode.SEAT_COUNT_MISMATCH);
        }
        var now = clock.instant();

        // Note: 한도 검사와 증가를 조건부 UPDATE 한 문장으로 한다(ISE-251). 따로 읽고 더하면 같은 사용자의 일반 선점이
        // 그 사이에 커밋해 한도를 넘었다. 매수 행 잠금은 이 UPDATE에서 생겨 커밋까지 유지되므로, 일반 선점과 같은 순서
        // (매수 행 → 좌석 → 예매)로 잠근다. 순서가 다르면 서로 기다리다 교착이 날 수 있다
        var quotaId = new UserScheduleQuotaId(userId, scheduleId);
        if (quotas.addHeldCountWithinLimit(quotaId, sortedSeatIds.size(), schedule.getMaxPerUser(), now, userId) == 0) {
            throw new BusinessException(SeatHoldErrorCode.PURCHASE_LIMIT_EXCEEDED);
        }
        if (inventory.holdAvailableLotterySeats(scheduleId, sortedSeatIds, now, userId) != sortedSeatIds.size()) {
            throw new BusinessException(SeatHoldErrorCode.SEAT_ALREADY_HELD);
        }

        var reservation = saveReservation(userId, scheduleId, seats, now);
        return new SystemHoldResult(reservation.getId(), reservation.getTotalAmount(), reservation.getHoldExpiresAt());
    }

    private Reservation saveReservation(Long userId, Long scheduleId, List<ScheduleSeat> seats, Instant now) {
        var sortedSeats = seats.stream().sorted(Comparator.comparing(ScheduleSeat::getId)).toList();
        var prices = new ArrayList<Money>();
        for (var seat : sortedSeats) {
            prices.add(seat.getPrice());
        }
        var holdExpiresAt = now.truncatedTo(ChronoUnit.SECONDS).plus(LOTTERY_HOLD);
        var reservation = reservations.save(Reservation.holdForLottery(ReservationNo.next(now, random),
                UUID.randomUUID(), userId, scheduleId, prices, holdExpiresAt));

        var heldSeats = new ArrayList<ReservationSeat>();
        for (var seat : sortedSeats) {
            heldSeats.add(ReservationSeat.hold(reservation.getId(), seat.getId(), seat.getPrice()));
        }
        try {
            reservationSeats.saveAll(heldSeats);
        } catch (ActiveSeatConflictException exception) {
            throw new BusinessException(SeatHoldErrorCode.SEAT_ALREADY_HELD);
        }
        histories.save(ReservationStatusHistory.of(reservation.getId(), null, ReservationStatus.HELD, HELD_REASON, now));
        return reservation;
    }

    public record SystemHoldResult(Long reservationId, Money totalAmount, Instant holdExpiresAt) {}
}
