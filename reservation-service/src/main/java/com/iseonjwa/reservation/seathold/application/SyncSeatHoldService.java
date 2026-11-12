package com.iseonjwa.reservation.seathold.application;

import com.iseonjwa.common.response.BusinessException;
import com.iseonjwa.common.response.CommonErrorCode;
import com.iseonjwa.reservation.concert.domain.entity.Schedule;
import com.iseonjwa.reservation.concert.domain.entity.ScheduleSeat;
import com.iseonjwa.reservation.seathold.domain.repository.SeatHoldInventoryRepository;
import com.iseonjwa.reservation.global.vo.Money;
import com.iseonjwa.reservation.reservation.domain.entity.Reservation;
import com.iseonjwa.reservation.reservation.domain.entity.ReservationSeat;
import com.iseonjwa.reservation.reservation.domain.entity.ReservationStatus;
import com.iseonjwa.reservation.reservation.domain.entity.ReservationStatusHistory;
import com.iseonjwa.reservation.reservation.domain.entity.UserScheduleQuota;
import com.iseonjwa.reservation.reservation.domain.entity.UserScheduleQuotaId;
import com.iseonjwa.reservation.reservation.domain.repository.ActiveSeatConflictException;
import com.iseonjwa.reservation.reservation.domain.repository.ReservationRepository;
import com.iseonjwa.reservation.reservation.domain.repository.ReservationSeatRepository;
import com.iseonjwa.reservation.reservation.domain.repository.ReservationStatusHistoryRepository;
import com.iseonjwa.reservation.reservation.domain.repository.UserScheduleQuotaRepository;
import com.iseonjwa.reservation.seathold.domain.entity.SeatHoldRequest;
import com.iseonjwa.reservation.seathold.domain.entity.SeatHoldRequestStatus;
import com.iseonjwa.reservation.seathold.domain.repository.SeatHoldRequestRepository;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 동기형 좌석 일괄 선점(M2, SA 6-4). 매수 반영 → 좌석 AVAILABLE→HELD → 예매·예매 좌석·이력·선점 요청 저장을 한 트랜잭션으로 묶는다.
 * 하나라도 실패하면 전체를 롤백해 실패한 seat_hold_requests 행을 남기지 않는다(ADR 0032).
 *
 * <p>TODO: 아래는 MVP 기준선이다(ADR 0035). M4 동시성 트러블슈팅 이슈에서 문제를 재현한 뒤 바꾼다.
 * 좌석 ID 오름차순 {@code FOR UPDATE}와 {@code lock_timeout}(TS-1), 매수 한도의 조건부 UPDATE 직렬화(TS-2),
 * 같은 멱등키 동시 요청의 제약 위반 재조회(TS-3). 사전 조회는 첫 요청이 커밋된 뒤의 재요청만 잡는다. 첫 트랜잭션이
 * 끝나기 전에 같은 키가 또 오면 조회가 비어 선점 경로로 들어가고, 좌석 행 잠금을 기다린 뒤 갱신 0행이 되어
 * 자기 좌석인데도 409 SEAT_ALREADY_HELD를 받는다(contracts.md 3-2 ⑥).
 */
@Service
public class SyncSeatHoldService {
    private static final String HELD_REASON = "SEAT_HELD";

    private final SeatHoldInventoryRepository inventory;
    private final UserScheduleQuotaRepository quotas;
    private final ReservationRepository reservations;
    private final ReservationSeatRepository reservationSeats;
    private final ReservationStatusHistoryRepository histories;
    private final SeatHoldRequestRepository seatHoldRequests;
    private final HeldReservationResults results;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public SyncSeatHoldService(SeatHoldInventoryRepository inventory, UserScheduleQuotaRepository quotas,
            ReservationRepository reservations, ReservationSeatRepository reservationSeats,
            ReservationStatusHistoryRepository histories,
            SeatHoldRequestRepository seatHoldRequests, HeldReservationResults results,
            @Qualifier("commonAuditingClock") Clock clock) {
        this.inventory = inventory;
        this.quotas = quotas;
        this.reservations = reservations;
        this.reservationSeats = reservationSeats;
        this.histories = histories;
        this.seatHoldRequests = seatHoldRequests;
        this.results = results;
        this.clock = clock;
    }

    @Transactional
    public SyncHoldOutcome hold(HoldSeatsCommand command) {
        var seatIds = SeatHoldRules.sortedDistinctSeatIds(command.seatIds());
        var previous = seatHoldRequests.findById(command.requestId());
        if (previous.isPresent()) {
            return replay(previous.get(), command, seatIds);
        }
        var schedule = inventory.findSchedule(command.scheduleId())
                .orElseThrow(() -> new BusinessException(CommonErrorCode.NOT_FOUND));
        SeatHoldRules.requireWithinPerUserLimit(schedule, seatIds);
        var now = clock.instant();
        SeatHoldRules.requireOnSale(schedule, now);
        var seats = findSeatsOfSchedule(schedule.getId(), seatIds);

        addHeldCount(command.userId(), schedule, seatIds.size(), now);
        holdSeats(schedule.getId(), seatIds, command.userId(), now);
        var reservation = saveReservation(command, schedule, seats, now);
        seatHoldRequests.save(SeatHoldRequest.held(command.requestId(), command.userId(), schedule.getId(), seatIds,
                reservation.getId()));

        return new SyncHoldOutcome.Held(results.withToken(reservation, seatIds));
    }

    // 같은 멱등키 재요청(contracts.md 3절). 좌석·매수를 다시 건드리지 않고 첫 요청이 만든 예매로 응답한다.
    // 회차·판매 기간 검사보다 먼저 와서, 그사이 판매가 끝났거나 회차가 지워져도 이미 잡은 선점 결과를 돌려준다
    private SyncHoldOutcome replay(SeatHoldRequest previous, HoldSeatsCommand command, List<Long> seatIds) {
        SeatHoldRules.requireSameRequest(previous, command, seatIds);
        // Note: 동기형은 HELD 행만 남기지만, 접수형으로 받은 요청(REQUESTED·REJECTED)이 남은 채 동기형으로 다시 뜰 수 있다.
        // 처리 중인 요청을 실패로 답하면 사용자가 새 좌석을 고르는 사이 앞 요청이 HELD가 되어 모르는 예매가 좌석·매수를 묶는다.
        // 그래서 접수형으로 시작한 요청은 202로 답하고 결과는 조회 API로 보게 한다(ADR 0055)
        if (previous.getStatus() != SeatHoldRequestStatus.HELD) {
            return new SyncHoldOutcome.Accepted(previous.getRequestId());
        }
        // HELD인데 예매가 없으면 데이터가 깨진 것이다
        var reservation = reservations.findById(previous.getReservationId())
                .orElseThrow(() -> new IllegalStateException("선점 요청의 예매가 없습니다: " + previous.getRequestId()));

        if (reservation.getStatus() == ReservationStatus.CONFIRMED) {
            return new SyncHoldOutcome.Held(results.withoutToken(reservation, seatIds));
        }
        if (SeatHoldRules.isPayable(reservation, clock.instant())) {
            return new SyncHoldOutcome.Held(results.withToken(reservation, seatIds));
        }
        throw new BusinessException(SeatHoldErrorCode.RESERVATION_NOT_PAYABLE);
    }

    // 다른 회차의 좌석이나 없는 좌석 ID가 섞이면 조회 결과가 요청보다 적다
    private List<ScheduleSeat> findSeatsOfSchedule(Long scheduleId, List<Long> seatIds) {
        var seats = inventory.findSeats(scheduleId, seatIds);
        if (seats.size() != seatIds.size()) {
            throw new BusinessException(SeatHoldErrorCode.SEAT_COUNT_MISMATCH);
        }
        return seats;
    }

    // Note: 보유 매수를 읽은 뒤 따로 더하므로, 같은 사용자의 요청이 동시에 오면 둘 다 검사를 통과할 수 있다(TS-2 재현 대상)
    private void addHeldCount(Long userId, Schedule schedule, int quantity, Instant now) {
        var quotaId = new UserScheduleQuotaId(userId, schedule.getId());
        var heldCount = quotas.findById(quotaId).map(UserScheduleQuota::getHeldCount).orElse(0);
        if (heldCount + quantity > schedule.getMaxPerUser()) {
            throw new BusinessException(SeatHoldErrorCode.PURCHASE_LIMIT_EXCEEDED);
        }
        quotas.addHeldCount(quotaId, quantity, now, userId);
    }

    // 하나라도 AVAILABLE이 아니면 갱신 행 수가 모자란다. 예외로 트랜잭션이 롤백돼 먼저 바뀐 좌석과 매수도 되돌아간다
    private void holdSeats(Long scheduleId, List<Long> seatIds, Long userId, Instant now) {
        var updated = inventory.holdAvailableSeats(scheduleId, seatIds, now, userId);
        if (updated != seatIds.size()) {
            throw new BusinessException(SeatHoldErrorCode.SEAT_ALREADY_HELD);
        }
    }

    private Reservation saveReservation(HoldSeatsCommand command, Schedule schedule, List<ScheduleSeat> seats,
            Instant now) {
        var sortedSeats = seats.stream().sorted(Comparator.comparing(ScheduleSeat::getId)).toList();
        var prices = new ArrayList<Money>();
        for (var seat : sortedSeats) {
            prices.add(seat.getPrice());
        }
        // 토큰 exp와 응답 시각이 초 단위라 만료 시각도 초로 맞춘다
        var holdExpiresAt = now.truncatedTo(ChronoUnit.SECONDS).plus(Duration.ofMinutes(schedule.getHoldMinutes()));
        var reservation = reservations.save(Reservation.hold(ReservationNo.next(now, random), command.requestId(),
                command.userId(), schedule.getId(), prices, holdExpiresAt));

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
