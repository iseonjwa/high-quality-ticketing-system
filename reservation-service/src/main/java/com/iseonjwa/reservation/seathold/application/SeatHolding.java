package com.iseonjwa.reservation.seathold.application;

import com.iseonjwa.common.response.BusinessException;
import com.iseonjwa.reservation.concert.domain.entity.Schedule;
import com.iseonjwa.reservation.concert.domain.entity.ScheduleSeat;
import com.iseonjwa.reservation.concert.domain.entity.SeatGrade;
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
import com.iseonjwa.reservation.seathold.domain.repository.SeatHoldRequestRepository;
import com.iseonjwa.reservation.seathold.observability.SeatLockMetrics;
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
 * 동기형 선점과 접수형 소비자가 함께 쓰는 좌석 선점 본체(SA 6-4, ISE-17). 매수 반영 → 예매·이력 저장 → 선점 요청 HELD →
 * 좌석 AVAILABLE→HELD → 예매 좌석 저장을 호출한 쪽의 트랜잭션 안에서 한다(순서는 ADR 0062). 실패하면
 * {@link BusinessException}을 던지고, 호출한 쪽이 트랜잭션을 롤백해 먼저 바뀐 매수·예매·요청도 되돌린다.
 *
 * <p>Note: 잠금 대기 시한({@code lock_timeout})은 호출한 쪽이 이 메서드 전에 건다. 이미 잡힌 좌석이 섞인 요청은 호출한 쪽이
 * 읽은 좌석 상태({@link SeatHoldRules#allHoldable})로 먼저 거른다. 여기까지 온 요청은 대부분 이긴다.
 */
@Component
public class SeatHolding {
    private static final String HELD_REASON = "SEAT_HELD";

    private final SeatHoldInventoryRepository inventory;
    private final UserScheduleQuotaRepository quotas;
    private final ReservationRepository reservations;
    private final ReservationSeatRepository reservationSeats;
    private final ReservationStatusHistoryRepository histories;
    private final SeatHoldRequestRepository requests;
    private final SeatLockMetrics seatLockMetrics;
    private final SecureRandom random = new SecureRandom();

    public SeatHolding(SeatHoldInventoryRepository inventory, UserScheduleQuotaRepository quotas,
            ReservationRepository reservations, ReservationSeatRepository reservationSeats,
            ReservationStatusHistoryRepository histories, SeatHoldRequestRepository requests,
            SeatLockMetrics seatLockMetrics) {
        this.inventory = inventory;
        this.quotas = quotas;
        this.reservations = reservations;
        this.reservationSeats = reservationSeats;
        this.histories = histories;
        this.requests = requests;
        this.seatLockMetrics = seatLockMetrics;
    }

    /**
     * 좌석을 잡고 예매를 만든 뒤 선점 요청을 HELD로 바꾼다. 선점 만료 시각은 {@code now}(처리 시점) 기준이다.
     *
     * @param seats 회차 소속을 확인한 좌석. {@code seatIds}와 같은 좌석이다
     */
    public Reservation hold(UUID requestId, Long userId, Schedule schedule, List<ScheduleSeat> seats,
            List<Long> seatIds, Instant now) {
        try {
            addHeldCount(userId, schedule, seatIds.size(), now);
            // Note: 다른 요청과 다투지 않는 쓰기(예매·이력·요청 HELD)를 좌석 UPDATE보다 먼저 한다. 좌석 잠금은 커밋까지 가므로
            // 잠근 뒤의 문장이 적을수록 같은 좌석을 노린 요청이 덜 기다린다(ADR 0062)
            var reservation = saveReservation(requestId, userId, schedule, seats, now);
            if (requests.markHeld(requestId, reservation.getId(), now, userId) != 1) {
                // 동기형은 이 트랜잭션에서 요청 행을 만들었고, 소비자는 요청 행을 잠그고 있으므로 일어나면 안 된다
                throw new IllegalStateException("선점 요청을 HELD로 바꾸지 못했습니다: " + requestId);
            }
            holdSeats(schedule.getId(), seatIds, userId, now);
            saveReservationSeats(reservation, seats);
            return reservation;
        } catch (PessimisticLockingFailureException timeout) {
            // 다른 트랜잭션이 좌석이나 매수 행을 오래 쥐고 있다. 기다리는 대신 "이미 선택된 좌석"으로 바로 돌려보낸다(SA 12-7)
            throw new SeatLockTimeoutException();
        }
    }

    /**
     * 자동 배정(ISE-264). 좌석을 서버가 고른다는 점만 다르고 잠금 순서와 상태 전이는 {@link #hold}와 같다.
     * 매수 행 조건부 UPDATE → 좌석 ID 오름차순 잠금(SKIP LOCKED) → 같은 조건부 UPDATE로 HELD → 예매 저장(SA 8-4, ADR 0034 3항).
     * 범위 안 남은 좌석이 quantity보다 적으면 {@link SeatHoldErrorCode#ZONE_SOLD_OUT}이다. 다른 범위로 넘기지 않는다.
     */
    public AutoHeldSeats holdAuto(UUID requestId, Long userId, Schedule schedule, SeatGrade grade, String section,
            int quantity, Instant now) {
        try {
            addHeldCount(userId, schedule, quantity, now);
            var seatIds = inventory.lockAvailableSeatIds(schedule.getId(), grade, section, quantity);
            if (seatIds.size() < quantity) {
                throw new BusinessException(SeatHoldErrorCode.ZONE_SOLD_OUT);
            }
            holdSeats(schedule.getId(), seatIds, userId, now);
            var seats = inventory.findSeats(schedule.getId(), seatIds);
            // Note: 예매와 예매 좌석 저장이 나뉘어 있다(hold의 순서 변경, ADR 0062). 여기서도 둘 다 불러야 좌석 원장이 남는다
            var reservation = saveReservation(requestId, userId, schedule, seats, now);
            saveReservationSeats(reservation, seats);
            return new AutoHeldSeats(reservation, seatIds);
        } catch (PessimisticLockingFailureException timeout) {
            // 좌석은 건너뛰므로 기다리지 않는다. 같은 사용자의 다른 탭이 매수 행을 쥐고 있을 때만 여기로 온다
            throw new SeatLockTimeoutException();
        }
    }

    // seatIds는 ID 오름차순이다
    public record AutoHeldSeats(Reservation reservation, List<Long> seatIds) {}

    // 검사와 증가를 조건부 UPDATE 한 문장으로 한다(TS-2). 읽고 검사한 뒤 더하면 같은 사용자의 탭들이 같은 값을 읽고 모두 통과했다
    private void addHeldCount(Long userId, Schedule schedule, int quantity, Instant now) {
        var quotaId = new UserScheduleQuotaId(userId, schedule.getId());
        if (quotas.addHeldCountWithinLimit(quotaId, quantity, schedule.getMaxPerUser(), now, userId) == 0) {
            throw new BusinessException(SeatHoldErrorCode.PURCHASE_LIMIT_EXCEEDED);
        }
    }

    // 하나라도 AVAILABLE이 아니면 갱신 행 수가 모자란다. 예외로 트랜잭션이 롤백돼 먼저 바뀐 좌석과 매수도 되돌아간다
    private void holdSeats(Long scheduleId, List<Long> seatIds, Long userId, Instant now) {
        var updated = seatLockMetrics.record(() -> inventory.holdAvailableSeats(scheduleId, seatIds, now, userId));
        if (updated != seatIds.size()) {
            throw new BusinessException(SeatHoldErrorCode.SEAT_ALREADY_HELD);
        }
    }

    private Reservation saveReservation(UUID requestId, Long userId, Schedule schedule, List<ScheduleSeat> seats,
            Instant now) {
        var prices = new ArrayList<Money>();
        for (var seat : sortedById(seats)) {
            prices.add(seat.getPrice());
        }
        // 토큰 exp와 응답 시각이 초 단위라 만료 시각도 초로 맞춘다
        var holdExpiresAt = now.truncatedTo(ChronoUnit.SECONDS).plus(Duration.ofMinutes(schedule.getHoldMinutes()));
        var reservation = reservations.save(Reservation.hold(ReservationNo.next(now, random), requestId,
                userId, schedule.getId(), prices, holdExpiresAt));
        histories.save(ReservationStatusHistory.of(reservation.getId(), null, ReservationStatus.HELD, HELD_REASON, now));
        return reservation;
    }

    // 좌석 UPDATE 뒤에 둔다. 좌석 상태를 먼저 바꾼 요청만 여기 오므로 부분 유니크 인덱스에서 서로 기다리는 일이 드물다
    private void saveReservationSeats(Reservation reservation, List<ScheduleSeat> seats) {
        var heldSeats = new ArrayList<ReservationSeat>();
        for (var seat : sortedById(seats)) {
            heldSeats.add(ReservationSeat.hold(reservation.getId(), seat.getId(), seat.getPrice()));
        }
        try {
            reservationSeats.saveAll(heldSeats);
        } catch (ActiveSeatConflictException exception) {
            // 좌석 상태와 예매 좌석 원장이 어긋났을 때 부분 유니크 인덱스가 마지막으로 막는다(D2)
            throw new BusinessException(SeatHoldErrorCode.SEAT_ALREADY_HELD);
        }
    }

    private static List<ScheduleSeat> sortedById(List<ScheduleSeat> seats) {
        return seats.stream().sorted(Comparator.comparing(ScheduleSeat::getId)).toList();
    }
}
