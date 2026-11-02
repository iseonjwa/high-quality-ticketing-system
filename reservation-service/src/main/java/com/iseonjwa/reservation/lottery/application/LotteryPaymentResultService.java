package com.iseonjwa.reservation.lottery.application;

import com.iseonjwa.reservation.lottery.domain.LotteryEntryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 당첨 예매의 결제 결과를 응모에 반영한다(D11·D12, ISE-90). 모든 전이는 "이 예매에 연결된 WON 응모"만 바꾸는
 * 조건부 UPDATE라, 같은 결과가 두 번 와도 두 번째는 아무것도 바꾸지 않는다. 승계는 하지 않는다(승계 워커 몫, D13).
 */
@Service
@RequiredArgsConstructor
public class LotteryPaymentResultService implements LotteryPaymentResultHandler {
    // 우리 책임 실패(결제 요청을 PG에 보내지 못함). 이 사유만 새 예매로 다시 시도한다(D12, ADR 0038)
    public static final String NOT_SENT = "NOT_SENT";
    public static final String HOLD_EXPIRED = "HOLD_EXPIRED";
    // 첫 시도 + 재시도 1회
    static final int MAX_PAYMENT_ATTEMPTS = 2;

    private final LotteryEntryRepository lotteryEntryRepository;

    // 호출한 쪽(결제 결과 소비자·만료 스케줄러)의 트랜잭션에 함께 묶인다. 없으면 새로 연다
    @Override
    @Transactional
    public void onPaid(Long reservationId) {
        lotteryEntryRepository.markPaid(reservationId);
    }

    @Override
    @Transactional
    public void onFailed(Long reservationId, String reasonCode) {
        if (NOT_SENT.equals(reasonCode)
                && lotteryEntryRepository.releaseForRetry(reservationId, MAX_PAYMENT_ATTEMPTS)) {
            return;
        }
        lotteryEntryRepository.markFailed(reservationId, reasonCode);
    }

    @Override
    @Transactional
    public void onExpired(Long reservationId) {
        lotteryEntryRepository.markFailed(reservationId, HOLD_EXPIRED);
    }
}
