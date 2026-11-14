package com.iseonjwa.reservation.seathold.application;

import com.iseonjwa.common.response.BusinessException;
import com.iseonjwa.reservation.concert.domain.entity.Schedule;
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
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * 동기형 선점과 접수형 소비자가 함께 쓰는 좌석 선점 본체(SA 6-4, ISE-17). 매수 반영 → 좌석 AVAILABLE→HELD →
 * 예매·예매 좌석·이력 저장을 호출한 쪽의 트랜잭션 안에서 한다. 실패하면 {@link BusinessException}을 던지고,
 * 호출한 쪽이 트랜잭션을 롤백해 먼저 바뀐 매수·좌석도 되돌린다.
 *
 * <p>Note: 잠금 대기 시한({@code lock_timeout})은 호출한 쪽이 이 메서드 전에 건다. 동기형과 소비자의 값이 다르다.
 */
@Component
public class SeatHolding {
    private static final String HELD_REASON = "SEAT_HELD";

    private final SeatHoldInventoryRepository inventory;
    private final UserScheduleQuotaRepository quotas;
    private final ReservationRepository reservations;
    private final ReservationSeatRepository reservationSeats;
    private final ReservationStatusHistoryRepository histories;
    private final SecureRandom random = new SecureRandom();

    public SeatHolding(SeatHoldInventoryRepository inventory, UserScheduleQuotaRepository quotas,
            ReservationRepository reservations, ReservationSeatRepository reservationSeats,
            ReservationStatusHistoryRepository histories) {
        this.inventory = inventory;
        this.quotas = quotas;
        this.reservations = reservations;
        this.reservationSeats = reservationSeats;
        this.histories = histories;
    }

    /**
     * 좌석을 잡고 예매를 만든다. 선점 만료 시각은 {@code now}(처리 시점) 기준이다.
     *
     * @param seats 회차 소속을 확인한 좌석. {@code seatIds}와 같은 좌석이다
     */
    public Reservation hold(UUID requestId, Long userId, Schedule schedule, List<ScheduleSeat> seats,
            List<Long> seatIds, Instant now) {
        try {
            addHeldCount(userId, schedule, seatIds.size(), now);
            holdSeats(schedule.getId(), seatIds, userId, now);
            return saveReservation(requestId, userId, schedule, seats, now);
        } catch (PessimisticLockingFailureException timeout) {
            // 다른 트랜잭션이 좌석이나 매수 행을 오래 쥐고 있다. 기다리는 대신 "이미 선택된 좌석"으로 바로 돌려보낸다(SA 12-7)
            throw new SeatLockTimeoutException();
        }
    }

    // 검사와 증가를 조건부 UPDATE 한 문장으로 한다(TS-2). 읽고 검사한 뒤 더하면 같은 사용자의 탭들이 같은 값을 읽고 모두 통과했다
    private void addHeldCount(Long userId, Schedule schedule, int quantity, Instant now) {
        var quotaId = new UserScheduleQuotaId(userId, schedule.getId());
        if (quotas.addHeldCountWithinLimit(quotaId, quantity, schedule.getMaxPerUser(), now, userId) == 0) {
            throw new BusinessException(SeatHoldErrorCode.PURCHASE_LIMIT_EXCEEDED);
        }
    }

    // 하나라도 AVAILABLE이 아니면 갱신 행 수가 모자란다. 예외로 트랜잭션이 롤백돼 먼저 바뀐 좌석과 매수도 되돌아간다
    private void holdSeats(Long scheduleId, List<Long> seatIds, Long userId, Instant now) {
        var updated = inventory.holdAvailableSeats(scheduleId, seatIds, now, userId);
        if (updated != seatIds.size()) {
            throw new BusinessException(SeatHoldErrorCode.SEAT_ALREADY_HELD);
        }
    }

    private Reservation saveReservation(UUID requestId, Long userId, Schedule schedule, List<ScheduleSeat> seats,
            Instant now) {
        var sortedSeats = seats.stream().sorted(Comparator.comparing(ScheduleSeat::getId)).toList();
        var prices = new ArrayList<Money>();
        for (var seat : sortedSeats) {
            prices.add(seat.getPrice());
        }
        // 토큰 exp와 응답 시각이 초 단위라 만료 시각도 초로 맞춘다
        var holdExpiresAt = now.truncatedTo(ChronoUnit.SECONDS).plus(Duration.ofMinutes(schedule.getHoldMinutes()));
        var reservation = reservations.save(Reservation.hold(ReservationNo.next(now, random), requestId,
                userId, schedule.getId(), prices, holdExpiresAt));

        var heldSeats = new ArrayList<ReservationSeat>();
        for (var seat : sortedSeats) {
            heldSeats.add(ReservationSeat.hold(reservation.getId(), seat.getId(), seat.getPrice()));
        }
        try {
            reservationSeats.saveAll(heldSeats);
        } catch (ActiveSeatConflictException exception) {
            // 좌석 상태와 예매 좌석 원장이 어긋났을 때 부분 유니크 인덱스가 마지막으로 막는다(D2)
            throw new BusinessException(SeatHoldErrorCode.SEAT_ALREADY_HELD);
        }
        histories.save(ReservationStatusHistory.of(reservation.getId(), null, ReservationStatus.HELD, HELD_REASON, now));
        return reservation;
    }
}
