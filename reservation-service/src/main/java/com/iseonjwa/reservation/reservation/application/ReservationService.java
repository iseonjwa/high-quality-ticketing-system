package com.iseonjwa.reservation.reservation.application;

import com.iseonjwa.common.response.BusinessException;
import com.iseonjwa.common.response.CommonErrorCode;
import com.iseonjwa.reservation.reservation.domain.entity.Reservation;
import com.iseonjwa.reservation.reservation.domain.entity.ReservationStatus;
import com.iseonjwa.reservation.reservation.domain.repository.ReservationRepository;
import com.iseonjwa.reservation.reservation.domain.repository.ReservationSeatRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;

/**
 * 사용자의 예매 상세·목록 조회(SA 6-5, 11-5). 로그인은 Gateway가 검증하고 사용자 ID만 넘겨 준다.
 */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class ReservationService {

    private final ReservationRepository reservationRepository;
    private final ReservationSeatRepository reservationSeatRepository;
    private final PaymentTokenIssuer paymentTokenIssuer;
    private final Clock clock;

    // 예매 상세
    public ReservationDetailResult getReservation(Long userId, Long reservationId) {
        // 다른 사용자의 예매는 있는지조차 드러내지 않게 없는 예매와 같은 404로 답한다(reservation contracts.md 2-2절과 같은 기준)
        Reservation reservation = reservationRepository.findById(reservationId)
                .filter(found -> found.getUserId().equals(userId))
                .orElseThrow(() -> new BusinessException(CommonErrorCode.NOT_FOUND));
        return new ReservationDetailResult(reservation,
                reservationSeatRepository.findSeatDetails(reservationId), paymentToken(reservation));
    }

    // 내 예매 목록
    public ReservationListResult getMyReservations(Long userId, int page, int size) {
        return new ReservationListResult(reservationRepository.findMine(userId, page, size), page, size,
                reservationRepository.countByUserId(userId));
    }

    // Note: 선점 재요청(SyncSeatHoldService)과 같은 기준이다. 선점 만료 10초 전부터는 토큰을 주지 않는다.
    // 그 안에 결제하면 승인이 선점 만료 뒤에 도착해 환불로 끝날 수 있다(설계 검토 07 G6)
    private String paymentToken(Reservation reservation) {
        if (reservation.getStatus() != ReservationStatus.HELD || !clock.instant()
                .isBefore(reservation.getHoldExpiresAt().minus(PaymentTokenIssuer.EXPIRY_MARGIN))) {
            return null;
        }
        // 조회할 때마다 새로 서명한다. 만료는 선점 만료에서 계산해 선점 응답의 토큰과 같고 jti만 다르다
        return paymentTokenIssuer.issue(reservation.getUserId(), reservation.getId(),
                reservation.getTotalAmount().toBigDecimal().longValueExact(), reservation.getHoldExpiresAt()).token();
    }
}
