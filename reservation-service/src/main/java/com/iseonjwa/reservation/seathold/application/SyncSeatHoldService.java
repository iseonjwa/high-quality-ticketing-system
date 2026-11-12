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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

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
    // SA 9-4. 핫 좌석에서 잠금을 오래 기다리며 커넥션을 쥐지 않게 한다
    static final Duration LOCK_TIMEOUT = Duration.ofMillis(500);

    private final SeatHoldInventoryRepository inventory;
    private final UserScheduleQuotaRepository quotas;
    private final ReservationRepository reservations;
    private final ReservationSeatRepository reservationSeats;
    private final ReservationStatusHistoryRepository histories;
    private final SeatHoldRequestRepository seatHoldRequests;
    private final HeldReservationResults results;
    private final Clock clock;
    private final TransactionTemplate writeTransaction;
    private final TransactionTemplate readTransaction;
    private final SecureRandom random = new SecureRandom();

    public SyncSeatHoldService(SeatHoldInventoryRepository inventory, UserScheduleQuotaRepository quotas,
            ReservationRepository reservations, ReservationSeatRepository reservationSeats,
            ReservationStatusHistoryRepository histories,
            SeatHoldRequestRepository seatHoldRequests, HeldReservationResults results,
            @Qualifier("commonAuditingClock") Clock clock, PlatformTransactionManager transactionManager) {
        this.writeTransaction = new TransactionTemplate(transactionManager);
        this.readTransaction = new TransactionTemplate(transactionManager);
        this.readTransaction.setReadOnly(true);
        this.inventory = inventory;
        this.quotas = quotas;
        this.reservations = reservations;
        this.reservationSeats = reservationSeats;
        this.histories = histories;
        this.seatHoldRequests = seatHoldRequests;
        this.results = results;
        this.clock = clock;
    }

    /**
     * Note: 트랜잭션을 이 메서드 안에서 연다. 같은 멱등키의 다른 요청에 진 트랜잭션은 rollback-only가 되므로,
     * 먼저 커밋된 결과를 다시 읽는 일은 그 트랜잭션이 끝난 뒤 새 읽기 트랜잭션에서 해야 한다(ADR 0032, TS-3).
     */
    public SyncHoldOutcome hold(HoldSeatsCommand command) {
        var seatIds = SeatHoldRules.sortedDistinctSeatIds(command.seatIds());
        try {
            return writeTransaction.execute(status -> holdInTransaction(command, seatIds));
        } catch (ConcurrentSameKeyException lost) {
            return readTransaction.execute(status -> seatHoldRequests.findById(command.requestId())
                    .map(previous -> replay(previous, command, seatIds))
                    .orElseThrow(() -> new IllegalStateException("먼저 커밋된 선점 요청이 없습니다: " + command.requestId(),
                            lost)));
        }
    }

    private SyncHoldOutcome holdInTransaction(HoldSeatsCommand command, List<Long> seatIds) {
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

        claimRequest(command, schedule.getId(), seatIds);
        // Note: 멱등키 선점 뒤에 건다. 같은 키의 연타는 첫 요청이 끝날 때까지 기다려야 재조회로 같은 결과를 줄 수 있다
        inventory.applyLockTimeout(LOCK_TIMEOUT);
        Reservation reservation;
        try {
            addHeldCount(command.userId(), schedule, seatIds.size(), now);
            holdSeats(schedule.getId(), seatIds, command.userId(), now);
            reservation = saveReservation(command, schedule, seats, now);
        } catch (PessimisticLockingFailureException timeout) {
            // 다른 트랜잭션이 좌석이나 매수 행을 오래 쥐고 있다. 기다리는 대신 "이미 선택된 좌석"으로 바로 돌려보낸다(SA 12-7)
            throw new BusinessException(SeatHoldErrorCode.SEAT_ALREADY_HELD);
        }
        if (seatHoldRequests.markHeld(command.requestId(), reservation.getId(), now, command.userId()) != 1) {
            throw new IllegalStateException("선점 요청을 HELD로 바꾸지 못했습니다: " + command.requestId());
        }

        return new SyncHoldOutcome.Held(results.withToken(reservation, seatIds));
    }

    // Note: 멱등키를 매수·좌석보다 먼저 쓴다. 같은 키의 두 번째 요청은 PK에서 첫 요청의 커밋을 기다린 뒤 유니크 위반을 받고,
    // 좌석 경로로 들어가 자기 좌석에 409를 받는 일이 없어진다. 첫 요청이 실패해 롤백되면 두 번째 요청이 이어서 선점한다
    private void claimRequest(HoldSeatsCommand command, Long scheduleId, List<Long> seatIds) {
        try {
            seatHoldRequests.save(SeatHoldRequest.request(command.requestId(), command.userId(), scheduleId, seatIds));
        } catch (DataIntegrityViolationException exception) {
            throw new ConcurrentSameKeyException(exception);
        }
    }

    private static final class ConcurrentSameKeyException extends RuntimeException {
        ConcurrentSameKeyException(Throwable cause) {
            super(cause);
        }
    }

    // 같은 멱등키 재요청(contracts.md 3절). 좌석·매수를 다시 건드리지 않고 첫 요청이 만든 예매로 응답한다.
    // 회차·판매 기간 검사보다 먼저 와서, 그사이 판매가 끝났거나 회차가 지워져도 이미 잡은 선점 결과를 돌려준다
    private SyncHoldOutcome replay(SeatHoldRequest previous, HoldSeatsCommand command, List<Long> seatIds) {
        SeatHoldRules.requireSameRequest(previous, command, seatIds);
        // Note: 동기형은 HELD 행만 남기지만, 접수형으로 받은 요청(REQUESTED·REJECTED)이 남은 채 동기형으로 다시 뜰 수 있다.
        // 처리 중인 요청을 실패로 답하면 사용자가 새 좌석을 고르는 사이 앞 요청이 HELD가 되어 모르는 예매가 좌석·매수를 묶는다.
        // 그래서 접수형으로 시작한 요청은 202로 답하고 결과는 조회 API로 보게 한다(ADR 0058)
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
