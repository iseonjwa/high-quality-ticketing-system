package com.iseonjwa.reservation.reservation.application;

import com.iseonjwa.common.messaging.inbox.ProcessedEventService;
import com.iseonjwa.common.messaging.outbox.OutboxWriter;
import com.iseonjwa.reservation.concert.domain.repository.ScheduleSeatRepository;
import com.iseonjwa.reservation.lottery.application.LotteryPaymentResultHandler;
import com.iseonjwa.reservation.reservation.domain.entity.Reservation;
import com.iseonjwa.reservation.reservation.domain.entity.ReservationSeat;
import com.iseonjwa.reservation.reservation.domain.entity.ReservationSeatStatus;
import com.iseonjwa.reservation.reservation.domain.entity.ReservationSource;
import com.iseonjwa.reservation.reservation.domain.entity.ReservationStatus;
import com.iseonjwa.reservation.reservation.domain.entity.ReservationStatusHistory;
import com.iseonjwa.reservation.reservation.domain.entity.Ticket;
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

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.random.RandomGenerator;

/**
 * 결제 결과({@code payment.result})를 예매 원장과 대조해 예매 상태별 규칙으로 반영한다(ADR 0027, SA 8-3).
 * 처리 이력(processed_events), 상태 전이, 상태 이력, 환불 요청(outbox), 추첨 응모 변경을 이벤트 한 건마다 한 트랜잭션으로 묶는다.
 *
 * <p>Note: 같은 예매를 선점 만료 스케줄러도 바꾼다. 전이는 "지금 HELD일 때만" 조건부 UPDATE로 하고, 0행이면 예외를 던져
 * 트랜잭션을 통째로 되돌린다. Kafka 재시도(1·2·4초)가 처음부터 다시 읽으면 바뀐 상태의 규칙이 적용된다.
 *
 * <p>Note: "이상"은 상태를 바꾸지 않고 {@code event=PAYMENT_RESULT_ANOMALY} 오류 로그로 남겨 사람이 보게 한다(ADR 0027 이상 격리).
 *
 * <p>Note: 추첨 예매(source = LOTTERY)는 예매 전이가 실제로 일어났을 때만 같은 트랜잭션에서 추첨 처리기에 알린다(D11).
 * 상태를 바꾸지 않은 결과(재전달, 이상)는 알리지 않는다. 대조 실패로 HELD에 남은 예매는 만료 스케줄러가 1회 연장한 뒤 회수하며 알린다.
 */
@Service
@RequiredArgsConstructor
public class PaymentResultService {
    public static final String CONSUMER_GROUP = "reservation.payment-result";
    static final String REFUND_TOPIC = "payment.command";
    static final String REFUND_EVENT_TYPE = "RefundRequested";
    private static final int REFUND_EVENT_VERSION = 1;
    private static final String CURRENCY = "KRW";

    // RefundRequested.reason (SA 10-3)
    static final String EXPIRED_AFTER_APPROVAL = "EXPIRED_AFTER_APPROVAL";
    static final String DUPLICATE_APPROVAL = "DUPLICATE_APPROVAL";
    static final String NOT_CONFIRMABLE = "NOT_CONFIRMABLE";

    private static final Logger log = LoggerFactory.getLogger(PaymentResultService.class);

    private final ProcessedEventService processedEvents;
    private final ReservationRepository reservations;
    private final ReservationSeatRepository reservationSeats;
    private final ReservationStatusHistoryRepository histories;
    private final UserScheduleQuotaRepository quotas;
    private final ScheduleSeatRepository scheduleSeats;
    private final TicketRepository tickets;
    private final OutboxWriter outbox;
    private final LotteryPaymentResultHandler lotteryResults;
    private final Clock clock;
    private final RandomGenerator random = new SecureRandom();

    // 이미 처리한 이벤트(같은 eventId)면 아무것도 하지 않는다
    public void handle(PaymentResult result) {
        processedEvents.processOnce(result.getEventId(), CONSUMER_GROUP, () -> {
            var reservation = reservations.findById(result.getReservationId()).orElse(null);
            if (reservation == null) {
                anomaly(result, "예매가 없습니다");
                return;
            }
            switch (result.getType()) {
                case APPROVED -> onApproved(reservation, result);
                case DECLINED -> onDeclined(reservation, result);
                case REFUNDED -> onRefunded(reservation, result);
            }
        });
    }

    private void onApproved(Reservation reservation, PaymentResult result) {
        var samePayment = result.getPaymentId().equals(reservation.getPaymentId());
        switch (reservation.getStatus()) {
            case HELD -> approveHeld(reservation, result);
            // 같은 결제면 재전달이라 그대로 둔다. 다른 결제면 한 예매에 승인이 두 번 난 것이라 뒤의 결제를 환불한다
            case CONFIRMED, CANCELED -> {
                if (!samePayment) {
                    requestRefund(result, DUPLICATE_APPROVAL);
                    anomaly(result, "다른 결제로 이미 확정된 예매에 승인이 왔습니다");
                }
            }
            case EXPIRED -> {
                requestRefund(result, EXPIRED_AFTER_APPROVAL);
                log.warn("만료된 예매에 승인이 와 환불을 요청했습니다. reservationId={}, paymentId={}",
                        reservation.getId(), result.getPaymentId());
            }
            case RELEASED -> {
                requestRefund(result, NOT_CONFIRMABLE);
                anomaly(result, "결제 거절로 해제된 예매에 승인이 왔습니다");
            }
        }
    }

    private void approveHeld(Reservation reservation, PaymentResult result) {
        // 사용자·금액·통화가 다르면 확정하지 않고 환불한다. 예매는 HELD로 두어 만료 스케줄러가 회수한다
        if (!matchesLedger(reservation, result)) {
            requestRefund(result, NOT_CONFIRMABLE);
            anomaly(result, "예매와 사용자·금액·통화가 다릅니다");
            return;
        }
        var now = clock.instant();
        // 만료 판단은 소비 시각이 아니라 PG 승인 시각으로 한다. 소비가 늦어도 선점 시간 안에 승인됐으면 확정한다
        if (result.getOccurredAt().isAfter(reservation.getHoldExpiresAt())) {
            expireAfterApproval(reservation, result, now);
            return;
        }
        if (reservations.confirmHeld(reservation.getId(), result.getPaymentId(), now) == 0) {
            throw changedConcurrently(reservation);
        }
        var heldSeats = heldSeats(reservation.getId());
        reservationSeats.confirmHeld(reservation.getId(), now);
        var sold = scheduleSeats.sellHeldSeats(reservation.getScheduleId(), scheduleSeatIds(heldSeats), now);
        if (sold != heldSeats.size()) {
            log.warn("확정한 예매의 회차 좌석 일부가 HELD가 아니었습니다. reservationId={}, seats={}, sold={}",
                    reservation.getId(), heldSeats.size(), sold);
        }
        tickets.saveAll(heldSeats.stream()
                .map(seat -> Ticket.issue(TicketNo.next(now, random), seat.getId()))
                .toList());
        histories.save(ReservationStatusHistory.of(reservation.getId(), ReservationStatus.HELD,
                ReservationStatus.CONFIRMED, "PAYMENT_APPROVED", now));
        if (isLottery(reservation)) {
            lotteryResults.onPaid(reservation.getId());
        }
    }

    // 선점 기한이 지난 뒤 승인됐다. 만료와 같은 전이를 하고 승인된 결제를 환불한다
    private void expireAfterApproval(Reservation reservation, PaymentResult result, Instant now) {
        if (reservations.expireHeld(reservation.getId(), now, null) == 0) {
            throw changedConcurrently(reservation);
        }
        releaseSeatsAndQuota(reservation, now);
        histories.save(ReservationStatusHistory.of(reservation.getId(), ReservationStatus.HELD,
                ReservationStatus.EXPIRED, EXPIRED_AFTER_APPROVAL, now));
        requestRefund(result, EXPIRED_AFTER_APPROVAL);
        // Note: 여기서 EXPIRED가 되면 만료 스케줄러가 이 예매를 다시 잡지 않는다. 알리지 않으면 응모가 WON으로 남아 승계·종결이 막힌다(D14)
        if (isLottery(reservation)) {
            lotteryResults.onExpired(reservation.getId());
        }
    }

    private void onDeclined(Reservation reservation, PaymentResult result) {
        switch (reservation.getStatus()) {
            case HELD -> {
                if (!result.getUserId().equals(reservation.getUserId())) {
                    anomaly(result, "예매와 사용자가 다른 거절이 왔습니다");
                    return;
                }
                var now = clock.instant();
                if (reservations.releaseHeld(reservation.getId(), now) == 0) {
                    throw changedConcurrently(reservation);
                }
                releaseSeatsAndQuota(reservation, now);
                histories.save(ReservationStatusHistory.of(reservation.getId(), ReservationStatus.HELD,
                        ReservationStatus.RELEASED, "PAYMENT_DECLINED", now));
                if (isLottery(reservation)) {
                    lotteryResults.onFailed(reservation.getId(), result.getReasonCode());
                }
            }
            // 확정에 쓰인 결제는 거절될 수 없다
            case CONFIRMED, CANCELED -> anomaly(result, "확정·취소된 예매에 거절이 왔습니다");
            case EXPIRED, RELEASED -> log.info("이미 끝난 예매에 거절이 왔습니다. reservationId={}, status={}",
                    reservation.getId(), reservation.getStatus());
        }
    }

    private void onRefunded(Reservation reservation, PaymentResult result) {
        var samePayment = result.getPaymentId().equals(reservation.getPaymentId());
        // Note: 확정 예매의 결제가 환불됐는데 취소 요청이 없었다. 좌석을 자동으로 풀면 이중 판매로 번질 수 있어
        // 상태를 바꾸지 않고 사람이 확인하게 한다(ADR 0027)
        if (reservation.getStatus() == ReservationStatus.CONFIRMED && samePayment) {
            anomaly(result, "확정 예매의 결제가 환불됐습니다. 좌석은 풀지 않았습니다");
            return;
        }
        log.info("환불 완료를 받았습니다. reservationId={}, paymentId={}, status={}",
                reservation.getId(), result.getPaymentId(), reservation.getStatus());
    }

    private static boolean isLottery(Reservation reservation) {
        return reservation.getSource() == ReservationSource.LOTTERY;
    }

    private static boolean matchesLedger(Reservation reservation, PaymentResult result) {
        return result.getUserId().equals(reservation.getUserId())
                && result.getAmount().compareTo(reservation.getTotalAmount().toBigDecimal()) == 0
                && CURRENCY.equals(result.getCurrency());
    }

    // 예매 좌석 HELD → RELEASED, 회차 좌석 HELD → AVAILABLE, 보유 매수 차감(SA 8-3). 선점 만료와 같은 반환이다
    private void releaseSeatsAndQuota(Reservation reservation, Instant now) {
        var heldSeats = heldSeats(reservation.getId());
        reservationSeats.releaseHeld(reservation.getId(), now, null);
        var released = scheduleSeats.releaseHeldSeats(reservation.getScheduleId(), scheduleSeatIds(heldSeats), now);
        if (released != heldSeats.size()) {
            log.warn("해제한 예매의 회차 좌석 일부가 HELD가 아니었습니다. reservationId={}, seats={}, released={}",
                    reservation.getId(), heldSeats.size(), released);
        }
        var quotaId = new UserScheduleQuotaId(reservation.getUserId(), reservation.getScheduleId());
        if (quotas.subtractHeldCount(quotaId, reservation.getQuantity(), now, null) == 0) {
            log.warn("해제한 예매의 보유 매수를 차감하지 못했습니다. reservationId={}, quantity={}",
                    reservation.getId(), reservation.getQuantity());
        }
    }

    private List<ReservationSeat> heldSeats(Long reservationId) {
        return reservationSeats.findByReservationId(reservationId).stream()
                .filter(seat -> seat.getStatus() == ReservationSeatStatus.HELD)
                .toList();
    }

    // 좌석 ID 오름차순으로 넘긴다(SA 8-4 잠금 순서)
    private static List<Long> scheduleSeatIds(List<ReservationSeat> seats) {
        var ids = new ArrayList<Long>();
        for (var seat : seats) {
            ids.add(seat.getScheduleSeatId());
        }
        ids.sort(null);
        return ids;
    }

    private void requestRefund(PaymentResult approved, String reason) {
        outbox.write(REFUND_TOPIC, String.valueOf(approved.getReservationId()), REFUND_EVENT_TYPE,
                REFUND_EVENT_VERSION, RefundRequestedPayload.of(approved, reason));
    }

    private static void anomaly(PaymentResult result, String reason) {
        log.error("event=PAYMENT_RESULT_ANOMALY type={} eventId={} reservationId={} paymentId={} reason={}",
                result.getType(), result.getEventId(), result.getReservationId(), result.getPaymentId(), reason);
    }

    private static IllegalStateException changedConcurrently(Reservation reservation) {
        return new IllegalStateException("처리 중에 예매 상태가 바뀌었습니다. 다시 처리합니다. reservationId="
                + reservation.getId());
    }
}
