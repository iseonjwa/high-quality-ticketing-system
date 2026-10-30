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
import java.util.HashSet;
import java.util.List;

/**
 * 동기형 좌석 일괄 선점(M2, SA 6-4). 매수 반영 → 좌석 AVAILABLE→HELD → 예매·예매 좌석·이력·선점 요청 저장을 한 트랜잭션으로 묶는다.
 * 하나라도 실패하면 전체를 롤백해 실패한 seat_hold_requests 행을 남기지 않는다(ADR 0032).
 *
 * <p>TODO: 아래는 MVP 기준선이다(ADR 0035). M4 동시성 트러블슈팅 이슈에서 문제를 재현한 뒤 바꾼다.
 * 좌석 ID 오름차순 {@code FOR UPDATE}와 {@code lock_timeout}(TS-1), 매수 한도의 조건부 UPDATE 직렬화(TS-2),
 * 같은 멱등키 동시 요청의 제약 위반 재조회(TS-3). 같은 멱등키 사전 조회와 재응답은 ISE-12에서 붙인다.
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
    private final PaymentTokenIssuer paymentTokenIssuer;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public SyncSeatHoldService(SeatHoldInventoryRepository inventory, UserScheduleQuotaRepository quotas,
            ReservationRepository reservations, ReservationSeatRepository reservationSeats,
            ReservationStatusHistoryRepository histories,
            SeatHoldRequestRepository seatHoldRequests, PaymentTokenIssuer paymentTokenIssuer,
            @Qualifier("commonAuditingClock") Clock clock) {
        this.inventory = inventory;
        this.quotas = quotas;
        this.reservations = reservations;
        this.reservationSeats = reservationSeats;
        this.histories = histories;
        this.seatHoldRequests = seatHoldRequests;
        this.paymentTokenIssuer = paymentTokenIssuer;
        this.clock = clock;
    }

    @Transactional
    public SeatHoldResult hold(HoldSeatsCommand command) {
        var seatIds = sortedDistinctSeatIds(command.seatIds());
        var schedule = inventory.findSchedule(command.scheduleId())
                .orElseThrow(() -> new BusinessException(CommonErrorCode.NOT_FOUND));
        if (seatIds.size() > schedule.getMaxPerUser()) {
            throw new BusinessException(SeatHoldErrorCode.SEAT_COUNT_MISMATCH);
        }
        var now = clock.instant();
        requireOnSale(schedule, now);
        var seats = findSeatsOfSchedule(schedule.getId(), seatIds);

        addHeldCount(command.userId(), schedule, seatIds.size(), now);
        holdSeats(schedule.getId(), seatIds, command.userId(), now);
        var reservation = saveReservation(command, schedule, seats, now);
        seatHoldRequests.save(SeatHoldRequest.held(command.requestId(), command.userId(), schedule.getId(), seatIds,
                reservation.getId()));

        var token = paymentTokenIssuer.issue(command.userId(), reservation.getId(),
                reservation.getTotalAmount().toBigDecimal().longValueExact(), reservation.getHoldExpiresAt());
        return new SeatHoldResult(command.requestId(), reservation.getId(), reservation.getReservationNo(),
                schedule.getId(), seatIds, reservation.getStatus(), reservation.getHoldExpiresAt(),
                reservation.getTotalAmount(), token.token(), token.expiresAt());
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
