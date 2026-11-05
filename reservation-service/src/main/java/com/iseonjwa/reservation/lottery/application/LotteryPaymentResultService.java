package com.iseonjwa.reservation.lottery.application;

import com.iseonjwa.reservation.lottery.domain.LotteryEntryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 당첨 예매의 결제 결과를 연결된 WON 응모만 바꾸는 조건부 UPDATE로 멱등하게 반영하고, 승계는 승계 워커에 맡긴다(D11·D12·D13, ISE-90).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LotteryPaymentResultService implements LotteryPaymentResultHandler {
    // 우리 책임 실패(결제 요청을 PG에 보내지 못함)로, 이 사유만 새 예매로 다시 시도한다(D12, ADR 0038)
    public static final String NOT_SENT = "NOT_SENT";
    public static final String HOLD_EXPIRED = "HOLD_EXPIRED";
    // 첫 시도 + 재시도 1회
    static final int MAX_PAYMENT_ATTEMPTS = 2;

    private final LotteryEntryRepository lotteryEntryRepository;

    // 호출한 쪽(결제 결과 소비자·만료 스케줄러)의 트랜잭션에 함께 묶이고, 없으면 새로 연다
    @Override
    @Transactional
    public void onPaid(Long reservationId) {
        if (!lotteryEntryRepository.markPaid(reservationId)) {
            logNotApplied(reservationId, "PAID");
        }
    }

    @Override
    @Transactional
    public void onFailed(Long reservationId, String reasonCode) {
        if (NOT_SENT.equals(reasonCode)
                && lotteryEntryRepository.releaseForRetry(reservationId, MAX_PAYMENT_ATTEMPTS)) {
            return;
        }
        if (!lotteryEntryRepository.markFailed(reservationId, reasonCode)) {
            logNotApplied(reservationId, "FAILED");
        }
    }

    @Override
    @Transactional
    public void onExpired(Long reservationId) {
        if (!lotteryEntryRepository.markFailed(reservationId, HOLD_EXPIRED)) {
            logNotApplied(reservationId, "FAILED");
        }
    }

    // Note: 연결된 WON 응모가 없으면 데이터 오류지만 예매 전이를 되돌려도 고쳐지지 않으므로 롤백하지 않고 사람이 보게 남긴다.
    private static void logNotApplied(Long reservationId, String target) {
        log.error("event=LOTTERY_RESULT_NOT_APPLIED reservationId={} target={}", reservationId, target);
    }
}
