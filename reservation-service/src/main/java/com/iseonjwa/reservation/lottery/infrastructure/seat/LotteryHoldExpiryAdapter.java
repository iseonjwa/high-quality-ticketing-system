package com.iseonjwa.reservation.lottery.infrastructure.seat;

import com.iseonjwa.reservation.lottery.application.LotteryPaymentResultHandler;
import com.iseonjwa.reservation.lottery.domain.LotteryEntryRepository;
import com.iseonjwa.reservation.seathold.application.LotteryHoldExpiryHook;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

// 선점 만료의 추첨 훅 구현. 응모 조회와 실패 처리는 추첨 처리기에 맡긴다
@Component
@RequiredArgsConstructor
public class LotteryHoldExpiryAdapter implements LotteryHoldExpiryHook {
    // 승계 마감(추첨 후 30분) + 연장 30분(D12·D14). 종결 상한 T+60분과 같다
    static final Duration EXTENSION_LIMIT_AFTER_DRAW = Duration.ofMinutes(60);

    private final LotteryEntryRepository lotteryEntryRepository;
    private final LotteryPaymentResultHandler resultHandler;

    @Override
    public Optional<Instant> extensionLimit(Long reservationId) {
        return lotteryEntryRepository.findWonDrawAtByReservationId(reservationId)
                .map(drawAt -> drawAt.plus(EXTENSION_LIMIT_AFTER_DRAW));
    }

    @Override
    public void onExpired(Long reservationId) {
        resultHandler.onExpired(reservationId);
    }
}
