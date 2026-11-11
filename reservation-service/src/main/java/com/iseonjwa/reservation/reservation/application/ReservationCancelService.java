package com.iseonjwa.reservation.reservation.application;

import com.iseonjwa.common.messaging.outbox.OutboxWriter;
import com.iseonjwa.common.response.BusinessException;
import com.iseonjwa.common.response.CommonErrorCode;
import com.iseonjwa.reservation.concert.domain.repository.ScheduleRepository;
import com.iseonjwa.reservation.concert.domain.repository.ScheduleSeatRepository;
import com.iseonjwa.reservation.reservation.domain.entity.Reservation;
import com.iseonjwa.reservation.reservation.domain.entity.ReservationSeat;
import com.iseonjwa.reservation.reservation.domain.entity.ReservationSeatStatus;
import com.iseonjwa.reservation.reservation.domain.entity.ReservationSource;
import com.iseonjwa.reservation.reservation.domain.entity.ReservationStatus;
import com.iseonjwa.reservation.reservation.domain.entity.ReservationStatusHistory;
import com.iseonjwa.reservation.reservation.domain.entity.UserScheduleQuotaId;
import com.iseonjwa.reservation.reservation.domain.repository.ReservationRepository;
import com.iseonjwa.reservation.reservation.domain.repository.ReservationSeatRepository;
import com.iseonjwa.reservation.reservation.domain.repository.ReservationStatusHistoryRepository;
import com.iseonjwa.reservation.reservation.domain.repository.TicketRepository;
import com.iseonjwa.reservation.reservation.domain.repository.UserScheduleQuotaRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 사용자가 확정(CONFIRMED)된 일반 예매를 공연 시작 전까지 취소한다. 좌석과 티켓을 돌려주고 결제 서비스에
 * {@code RefundRequested(reason = CANCELED)}를 요청한다(SA 상태 전이 표, 10-3). 전부 한 트랜잭션이다.
 */
@Service
@Transactional
@RequiredArgsConstructor
public class ReservationCancelService {
    private static final String CANCEL_REASON = "USER_CANCELED";

    private static final Logger log = LoggerFactory.getLogger(ReservationCancelService.class);

    private final ReservationRepository reservations;
    private final ReservationSeatRepository reservationSeats;
    private final ReservationStatusHistoryRepository histories;
    private final UserScheduleQuotaRepository quotas;
    private final ScheduleRepository schedules;
    private final ScheduleSeatRepository scheduleSeats;
    private final TicketRepository tickets;
    private final OutboxWriter outbox;
    private final Clock clock;

    public ReservationCancelResult cancel(Long userId, Long reservationId) {
        // 다른 사용자의 예매는 없는 예매와 같은 404로 답한다(예매 상세 조회와 같은 기준)
        var reservation = reservations.findById(reservationId)
                .filter(found -> found.getUserId().equals(userId))
                .orElseThrow(() -> new BusinessException(CommonErrorCode.NOT_FOUND));
        // Note: 취소는 예매마다 한 번만 일어나는 전이라 예매 상태가 곧 멱등 기록이다. Idempotency-Key는 저장하지 않고,
        // 이미 CANCELED인 예매에 다시 오면 아무것도 쓰지 않고 첫 응답과 같은 결과를 돌려준다
        if (reservation.getStatus() == ReservationStatus.CANCELED) {
            return resultOf(reservation);
        }
        var now = clock.instant();
        requireCancelable(reservation, now);

        if (reservations.cancelConfirmed(reservationId, now, userId) == 0) {
            return resolveLostRace(reservationId);
        }
        // Note: 매수 행을 좌석보다 먼저 잠근다. 좌석 선점(SyncSeatHoldService)이 매수 행 → 회차 좌석 순서로 잠그므로
        // 같은 사용자가 같은 회차에서 선점과 취소를 동시에 할 때 반대 순서면 교착이 날 수 있다
        subtractQuota(reservation, now, userId);
        var confirmedSeats = confirmedSeats(reservationId);
        reservationSeats.releaseConfirmed(reservationId, now, userId);
        releaseScheduleSeats(reservation, confirmedSeats, now, userId);
        tickets.voidIssuedByReservation(reservationId, now, userId);
        histories.save(ReservationStatusHistory.of(reservationId, ReservationStatus.CONFIRMED,
                ReservationStatus.CANCELED, CANCEL_REASON, now));
        outbox.write(PaymentResultService.REFUND_TOPIC, String.valueOf(reservationId),
                PaymentResultService.REFUND_EVENT_TYPE, PaymentResultService.REFUND_EVENT_VERSION,
                RefundRequestedPayload.canceled(reservation));
        return resultOf(reservation);
    }

    // 검사 순서: 추첨 → 상태 → 기한. 추첨 예매는 상태와 상관없이 취소할 수 없다고 먼저 알린다
    private void requireCancelable(Reservation reservation, Instant now) {
        if (reservation.getSource() == ReservationSource.LOTTERY) {
            throw new BusinessException(ReservationErrorCode.LOTTERY_RESERVATION_NOT_CANCELABLE);
        }
        if (reservation.getStatus() != ReservationStatus.CONFIRMED) {
            throw new BusinessException(ReservationErrorCode.RESERVATION_NOT_CANCELABLE);
        }
        var schedule = schedules.findById(reservation.getScheduleId())
                .orElseThrow(() -> new BusinessException(ReservationErrorCode.RESERVATION_NOT_CANCELABLE));
        if (!now.isBefore(schedule.getStartsAt())) {
            throw new BusinessException(ReservationErrorCode.CANCEL_PERIOD_ENDED);
        }
    }

    // 조건부 UPDATE가 0행이다. 같은 취소 요청이 먼저 끝났으면 재요청으로 보고, 다른 이유로 상태가 바뀌었으면 거절한다
    private ReservationCancelResult resolveLostRace(Long reservationId) {
        var current = reservations.findById(reservationId)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.NOT_FOUND));
        if (current.getStatus() == ReservationStatus.CANCELED) {
            return resultOf(current);
        }
        throw new BusinessException(ReservationErrorCode.RESERVATION_NOT_CANCELABLE);
    }

    private void subtractQuota(Reservation reservation, Instant now, Long userId) {
        var quotaId = new UserScheduleQuotaId(reservation.getUserId(), reservation.getScheduleId());
        if (quotas.subtractHeldCount(quotaId, reservation.getQuantity(), now, userId) == 0) {
            log.warn("취소한 예매의 보유 매수를 차감하지 못했습니다. reservationId={}, quantity={}",
                    reservation.getId(), reservation.getQuantity());
        }
    }

    private void releaseScheduleSeats(Reservation reservation, List<ReservationSeat> confirmedSeats, Instant now,
            Long userId) {
        var released = scheduleSeats.releaseSoldSeats(reservation.getScheduleId(), scheduleSeatIds(confirmedSeats), now,
                userId);
        if (released != confirmedSeats.size()) {
            log.warn("취소한 예매의 회차 좌석 일부가 SOLD가 아니었습니다. reservationId={}, seats={}, released={}",
                    reservation.getId(), confirmedSeats.size(), released);
        }
    }

    private List<ReservationSeat> confirmedSeats(Long reservationId) {
        return reservationSeats.findByReservationId(reservationId).stream()
                .filter(seat -> seat.getStatus() == ReservationSeatStatus.CONFIRMED)
                .toList();
    }

    // Note: IN 목록의 순서는 UPDATE가 행을 잠그는 순서를 정하지 않는다. 그래도 여기서는 순서가 엇갈릴 상대가 없다.
    // 같은 예매의 취소끼리는 앞의 예매 행 잠금에서 줄을 서고, 선점은 AVAILABLE 좌석만 잠가 SOLD 좌석을 건드리지 않는다
    private static List<Long> scheduleSeatIds(List<ReservationSeat> seats) {
        var ids = new ArrayList<Long>();
        for (var seat : seats) {
            ids.add(seat.getScheduleSeatId());
        }
        return ids;
    }

    private static ReservationCancelResult resultOf(Reservation reservation) {
        return new ReservationCancelResult(reservation.getId(), reservation.getPaymentId());
    }
}
