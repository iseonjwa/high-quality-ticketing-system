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
import java.util.HashSet;
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
    private final PaymentTokenIssuer paymentTokenIssuer;
    private final HeldSeatPreCheck preCheck;
    private final Clock clock;
    private final TransactionTemplate writeTransaction;
    private final TransactionTemplate readTransaction;
    private final SecureRandom random = new SecureRandom();

    public SyncSeatHoldService(SeatHoldInventoryRepository inventory, UserScheduleQuotaRepository quotas,
            ReservationRepository reservations, ReservationSeatRepository reservationSeats,
            ReservationStatusHistoryRepository histories,
            SeatHoldRequestRepository seatHoldRequests, PaymentTokenIssuer paymentTokenIssuer, HeldSeatPreCheck preCheck,
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
        this.paymentTokenIssuer = paymentTokenIssuer;
        this.preCheck = preCheck;
        this.clock = clock;
    }

    /**
     * Note: 트랜잭션을 이 메서드 안에서 연다. 같은 멱등키의 다른 요청에 진 트랜잭션은 rollback-only가 되므로,
     * 먼저 커밋된 결과를 다시 읽는 일은 그 트랜잭션이 끝난 뒤 새 읽기 트랜잭션에서 해야 한다(ADR 0032, TS-3).
     */
    public SeatHoldResult hold(HoldSeatsCommand command) {
        var seatIds = sortedDistinctSeatIds(command.seatIds());
        if (preCheck.surelyHeld(command.scheduleId(), seatIds)) {
            // Note: 같은 멱등키의 재요청이면 자기 좌석이 잡혀 보인다. 멱등키만 PK로 한 번 보고, 있으면 지금처럼 재응답한다.
            // 가벼운 조회(측정 후보 2)는 트랜잭션 없이 있는지만 보고, 있을 때만 재응답 경로(읽기 트랜잭션)로 간다
            if (preCheck.lightKeyLookup() && !seatHoldRequests.existsById(command.requestId())) {
                throw new BusinessException(SeatHoldErrorCode.SEAT_ALREADY_HELD);
            }
            return readTransaction.execute(status -> seatHoldRequests.findById(command.requestId())
                    .map(previous -> replay(previous, command, seatIds))
                    .orElseThrow(() -> new BusinessException(SeatHoldErrorCode.SEAT_ALREADY_HELD)));
        }
        try {
            return writeTransaction.execute(status -> holdInTransaction(command, seatIds));
        } catch (ConcurrentSameKeyException lost) {
            return readTransaction.execute(status -> seatHoldRequests.findById(command.requestId())
                    .map(previous -> replay(previous, command, seatIds))
                    .orElseThrow(() -> new IllegalStateException("먼저 커밋된 선점 요청이 없습니다: " + command.requestId(),
                            lost)));
        }
    }

    private SeatHoldResult holdInTransaction(HoldSeatsCommand command, List<Long> seatIds) {
        var previous = seatHoldRequests.findById(command.requestId());
        if (previous.isPresent()) {
            return replay(previous.get(), command, seatIds);
        }
        var schedule = inventory.findSchedule(command.scheduleId())
                .orElseThrow(() -> new BusinessException(CommonErrorCode.NOT_FOUND));
        if (seatIds.size() > schedule.getMaxPerUser()) {
            throw new BusinessException(SeatHoldErrorCode.SEAT_COUNT_MISMATCH);
        }
        var now = clock.instant();
        requireOnSale(schedule, now);
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

        return result(reservation, seatIds, issueToken(reservation));
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
    private SeatHoldResult replay(SeatHoldRequest previous, HoldSeatsCommand command, List<Long> seatIds) {
        if (!previous.getUserId().equals(command.userId())
                || !previous.getScheduleId().equals(command.scheduleId())
                || !previous.getSeatIds().equals(seatIds)) {
            throw new BusinessException(CommonErrorCode.IDEMPOTENCY_KEY_REUSED);
        }
        // 동기형은 성공한 선점만 행을 남기므로 HELD가 아니거나 예매가 없으면 데이터가 깨진 것이다
        if (previous.getStatus() != SeatHoldRequestStatus.HELD) {
            throw new IllegalStateException("동기형 선점 요청이 HELD가 아닙니다: " + previous.getRequestId());
        }
        var reservation = reservations.findById(previous.getReservationId())
                .orElseThrow(() -> new IllegalStateException("선점 요청의 예매가 없습니다: " + previous.getRequestId()));

        if (reservation.getStatus() == ReservationStatus.CONFIRMED) {
            return result(reservation, seatIds, null);
        }
        // Note: 만료 스케줄러가 돌기 전이면 기한이 지나도 HELD로 남아 있어서 상태만 보지 않고 시각도 본다
        if (reservation.getStatus() == ReservationStatus.HELD && clock.instant()
                .isBefore(reservation.getHoldExpiresAt().minus(PaymentTokenIssuer.EXPIRY_MARGIN))) {
            return result(reservation, seatIds, issueToken(reservation));
        }
        throw new BusinessException(SeatHoldErrorCode.RESERVATION_NOT_PAYABLE);
    }

    // 재요청에도 새로 서명한다. exp는 선점 만료에서 계산해 첫 토큰과 같고 jti만 다르다(결제 토큰 폐기 목록 없음)
    private PaymentTokenIssuer.IssuedPaymentToken issueToken(Reservation reservation) {
        return paymentTokenIssuer.issue(reservation.getUserId(), reservation.getId(),
                reservation.getTotalAmount().toBigDecimal().longValueExact(), reservation.getHoldExpiresAt());
    }

    // 결제 확정된 예매는 토큰을 주지 않는다
    private static SeatHoldResult result(Reservation reservation, List<Long> seatIds,
            PaymentTokenIssuer.IssuedPaymentToken token) {
        return new SeatHoldResult(reservation.getRequestId(), reservation.getId(), reservation.getReservationNo(),
                reservation.getScheduleId(), seatIds, reservation.getStatus(), reservation.getHoldExpiresAt(),
                reservation.getTotalAmount(), token == null ? null : token.token(),
                token == null ? null : token.expiresAt());
    }

    // 정렬한 목록을 응답·seat_hold_requests.seat_ids·멱등 본문 비교의 기준으로 같이 쓴다(contracts.md 1절)
    private static List<Long> sortedDistinctSeatIds(List<Long> requested) {
        if (requested.isEmpty() || new HashSet<>(requested).size() != requested.size()) {
            throw new BusinessException(SeatHoldErrorCode.SEAT_COUNT_MISMATCH);
        }
        return requested.stream().sorted().toList();
    }

    // 판매 기간은 오픈 시각부터 공연 시작 전까지다. 시각은 클라이언트 값이 아니라 서버 시계로 판단한다(E4)
    private static void requireOnSale(Schedule schedule, Instant now) {
        if (now.isBefore(schedule.getOpenAt()) || !now.isBefore(schedule.getStartsAt())) {
            throw new BusinessException(SeatHoldErrorCode.SALE_NOT_OPEN);
        }
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
