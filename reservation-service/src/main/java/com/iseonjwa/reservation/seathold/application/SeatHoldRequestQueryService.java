package com.iseonjwa.reservation.seathold.application;

import com.iseonjwa.common.response.BusinessException;
import com.iseonjwa.common.response.CommonErrorCode;
import com.iseonjwa.reservation.reservation.domain.repository.ReservationRepository;
import com.iseonjwa.reservation.seathold.domain.entity.SeatHoldRequest;
import com.iseonjwa.reservation.seathold.domain.entity.SeatHoldRequestStatus;
import com.iseonjwa.reservation.seathold.domain.repository.SeatHoldRequestRepository;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.UUID;

/**
 * 선점 접수 결과 조회(contracts.md 2-2절). 모드와 상관없이 동작한다(ADR 0057). 동기형은 성공한 선점만 행을 남기므로
 * HELD 아니면 404다.
 */
@Service
public class SeatHoldRequestQueryService {
    private final SeatHoldRequestRepository seatHoldRequests;
    private final ReservationRepository reservations;
    private final HeldReservationResults results;
    private final Clock clock;

    public SeatHoldRequestQueryService(SeatHoldRequestRepository seatHoldRequests, ReservationRepository reservations,
            HeldReservationResults results, @Qualifier("commonAuditingClock") Clock clock) {
        this.seatHoldRequests = seatHoldRequests;
        this.reservations = reservations;
        this.results = results;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public SeatHoldRequestResult find(UUID requestId, Long userId) {
        // 남의 요청도 없는 요청과 같은 404로 답해 존재 여부를 드러내지 않는다
        var request = seatHoldRequests.findById(requestId)
                .filter(found -> found.getUserId().equals(userId))
                .orElseThrow(() -> new BusinessException(CommonErrorCode.NOT_FOUND));

        return switch (request.getStatus()) {
            case REQUESTED -> SeatHoldRequestResult.requested(request.getRequestId());
            case REJECTED -> SeatHoldRequestResult.rejected(request.getRequestId(), request.getReasonCode());
            case HELD -> SeatHoldRequestResult.held(request.getRequestId(), heldReservation(request));
        };
    }

    // 예매의 현재 상태를 보여 준다. 결제 토큰은 아직 결제할 수 있을 때만 새로 서명한다(contracts.md 3-2 ②~④)
    private SeatHoldResult heldReservation(SeatHoldRequest request) {
        var reservation = reservations.findById(request.getReservationId())
                .orElseThrow(() -> new IllegalStateException("선점 요청의 예매가 없습니다: " + request.getRequestId()));
        if (SeatHoldRules.isPayable(reservation, clock.instant())) {
            return results.withToken(reservation, request.getSeatIds());
        }
        return results.withoutToken(reservation, request.getSeatIds());
    }

    public record SeatHoldRequestResult(UUID requestId, SeatHoldRequestStatus status, String reasonCode,
            SeatHoldResult reservation) {
        static SeatHoldRequestResult requested(UUID requestId) {
            return new SeatHoldRequestResult(requestId, SeatHoldRequestStatus.REQUESTED, null, null);
        }

        static SeatHoldRequestResult rejected(UUID requestId, String reasonCode) {
            return new SeatHoldRequestResult(requestId, SeatHoldRequestStatus.REJECTED, reasonCode, null);
        }

        static SeatHoldRequestResult held(UUID requestId, SeatHoldResult reservation) {
            return new SeatHoldRequestResult(requestId, SeatHoldRequestStatus.HELD, null, reservation);
        }
    }
}
